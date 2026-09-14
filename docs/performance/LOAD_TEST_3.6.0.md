# Sustained load, 3.6.0 release candidate

A ten-minute run of four load generators against a three-pod Structures cluster on the
release-candidate build, recorded so the numbers behind "it held up" are on file. The point was
not a ceiling but due diligence: a decent, sustained, mixed load across both transports with
nothing failing, nothing restarting, nothing logged, and no drift over time.

## Setup

- Image `mindsignited/structures-server:3.6.0-pr11.bee0983` (Spring Boot 4.1.1, Vert.x 5.1.8,
  Jackson 3.1.5, continuum 3.1.0-SNAPSHOT `ffc6727b`), three replicas, no CPU or memory limits.
- KinD on an Apple Silicon Mac (Docker Desktop, 16 CPUs / 63 GB), two-node Elasticsearch 8.18
  in the same cluster, ingress-nginx with TLS in front (its limit raised to 2 CPU for the run so it
  could not be the bottleneck; it used 29 m at peak).
- Generators ran on the same machine, through the ingress: STOMP over WebSocket at
  `wss://localhost/v1`, OpenAPI at `https://localhost/api`. All against one structure,
  `load-testing.person` (multi-tenant, client-supplied ids), tenant `kinotic`.
- `structures-js/load-generator`, four processes for 600 s each:

| process | test | concurrency | rate cap |
|---|---|---|---|
| STOMP bulk save | `sustainedBulkSave`, 200 people per request | 2 | 5/s |
| STOMP search | `sustainedSearch`, `firstName: John`, page 100 | 16 | 40/s |
| STOMP find all | `sustainedFindAll`, page 100 | 4 | 20/s |
| OpenAPI mixed | `openApiMixed`: save 25%, bulk save (200) 5%, find by id 25%, find all 15%, search 20%, count 10% | 16 | 50/s |

Cluster metrics came from metrics-server (`kubectl top`, every 15 s) and Elasticsearch node
stats; server logs were scanned for WARN and ERROR over the window.

## Result

| operation | count | errors | ops/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| STOMP bulk save (200) | 3,099 | 0 | 5.0 | 28 ms | 65 ms | 104 ms | 1.47 s |
| STOMP search | 31,599 | 0 | 52.6 | 10 ms | 30 ms | 60 ms | 859 ms |
| STOMP find all | 12,499 | 0 | 20.7 | 5 ms | 16 ms | 36 ms | 927 ms |
| OpenAPI save | 10,477 | 0 | 17.5 | 26 ms | 76 ms | 363 ms | 1.91 s |
| OpenAPI bulk save (200) | 2,143 | 0 | 3.6 | 64 ms | 132 ms | 419 ms | 1.93 s |
| OpenAPI find by id | 10,493 | 0 | 17.5 | 4 ms | 19 ms | 68 ms | 797 ms |
| OpenAPI find all | 6,396 | 0 | 10.7 | 4 ms | 15 ms | 67 ms | 1.24 s |
| OpenAPI search | 8,548 | 0 | 14.2 | 5 ms | 18 ms | 151 ms | 788 ms |
| OpenAPI count | 4,143 | 0 | 6.9 | 3 ms | 14 ms | 76 ms | 857 ms |

- 89,397 client operations, 0 failures; about 122 requests/s sustained across the two transports,
  with 2,097,618 documents indexed (roughly 3,500/s) and 62,947 Elasticsearch queries.
- No pod restarts. No WARN or ERROR in any server pod's log for the window.
- Structures pods: 100–280 m CPU each at the median, peaks 177–397 m; memory 800 MiB at the start
  rising to 980–1,125 MiB by the end. Elasticsearch: 225 m median per node, 480 m peak, heap 68 % at most.
- No drift: per 30 s window, STOMP search p95 was 18–30 ms from the second window to the last,
  bulk save 35–71 ms, OpenAPI save 36–70 ms. The first window carries warm-up (p99 450–800 ms).
  One window (t=510 s) had a single spike to 1.5–1.9 s that hit STOMP bulk save and OpenAPI save
  together, which points at the write path's shared dependency (an Elasticsearch refresh or merge)
  rather than either transport.

## Worth knowing

- **Memory rose about 300 MiB over the run on two of the three pods.** Ten minutes cannot tell a
  JVM growing into its heap from a leak; a longer soak with the same load would. The pods run
  without a memory limit, so nothing constrained them.
- **`structures.local` costs 5 s per new connection on macOS.** `.local` names go to mDNS first
  and fall back to `/etc/hosts` after a 5 s timeout, for curl, Node and the e2e suite alike. The
  ingress and the mkcert certificate also cover `localhost`, which is what this run used; the e2e
  environment should too.
- Ignite's communication message queue was unbounded during this run (Ignite's default). continuum
  3.1.0 adds `continuum.cluster.communicationMessageQueueLimit`, and the chart sets 1024.

## Reproducing

Structure and tooling: `TEST_NAME=createPersonStructure` creates `load-testing.person` if it is
missing. Every generator takes `DURATION_SECONDS`, `REPORT_INTERVAL_SECONDS`, `REPORT_FILE`
(JSON summary), `LOG_TASKS=false`, and `START_DELAY_SECONDS` (default 60, for the Docker case).

```sh
cd structures-js/load-generator && pnpm build
export NODE_EXTRA_CA_CERTS="$(mkcert -CAROOT)/rootCA.pem" NODE_ENV=production OTEL_SDK_DISABLED=true \
       STRUCTURES_HOST=localhost STRUCTURES_PORT=443 STRUCTURES_USE_SSL=true \
       STRUCTURES_OPENAPI_BASE_URL=https://localhost/api START_DELAY_SECONDS=0 LOG_TASKS=false \
       DURATION_SECONDS=600 REPORT_INTERVAL_SECONDS=30
TEST_NAME=sustainedBulkSave BATCH_SIZE=200 MAX_CONCURRENT_REQUESTS=2  MAX_REQUESTS_PER_SECOND=5  REPORT_FILE=bulk.json   node dist/main.mjs &
TEST_NAME=sustainedSearch                  MAX_CONCURRENT_REQUESTS=16 MAX_REQUESTS_PER_SECOND=40 REPORT_FILE=search.json node dist/main.mjs &
TEST_NAME=sustainedFindAll                 MAX_CONCURRENT_REQUESTS=4  MAX_REQUESTS_PER_SECOND=20 REPORT_FILE=find.json   node dist/main.mjs &
TEST_NAME=openApiMixed                     MAX_CONCURRENT_REQUESTS=16 MAX_REQUESTS_PER_SECOND=50 REPORT_FILE=api.json    node dist/main.mjs &
wait
```
