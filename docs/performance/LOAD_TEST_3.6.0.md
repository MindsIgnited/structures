# Sustained load, 3.6.0 release candidate

A ten-minute run of four load generators against a three-pod Structures cluster on the
release-candidate build, recorded so the numbers behind "it held up" are on file. The point was
not a ceiling but due diligence: a decent, sustained, mixed load across both transports with
nothing failing, nothing restarting, nothing logged, and no drift over time.

## Setup

- Image `mindsignited/structures-server:3.6.0-pr11.bee0983` (Spring Boot 4.1.1, Vert.x 5.1.8,
  Jackson 3.1.5, continuum 3.1.0-SNAPSHOT `ffc6727b`), three replicas, no CPU or memory limits.
- KinD on an Apple Silicon Mac (Docker Desktop, 16 CPUs / 63 GB), two-node Elasticsearch 8.19.13
  in the same cluster (1 CPU / 2 GiB per node, 1 GiB heap), ingress-nginx with TLS in front (its
  limit raised to 2 CPU for the run so it could not be the bottleneck; it used 29 m at peak).
  Full hardware and infrastructure detail in [LOAD_TESTING.md](../../LOAD_TESTING.md).
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

## Second run: the final image, on a cluster built from nothing

The same ten minutes again on the build that goes to `main`, `mindsignited/structures-server:3.6.0-pr11.1e30e55`
(continuum 3.1.0 release, Spring Boot 4.1.1, Vert.x 5.1.8, Jackson 3.1.5; the review follow-ups
in `1e30e55d` included, among them the ingest path's bound field readers). The KinD cluster was
deleted and recreated first (`kind-cluster.sh delete --force`, `create`, `deploy --tag`), so this
run also covers the migration job on an empty Elasticsearch: it created the history index and
applied system migration 1, and the four structure indices existed before the first request.
Kubernetes v1.34.0, four nodes, Elasticsearch 8.19.13 x 2, ingress-nginx v1.15.1, the Ignite
communication queue limit at 1024 through the config map. Before the run: Gradle suite 109 tests,
e2e native + openapi 55/55, k8s 5/5 (which restart pods; the run started on pods two to three
minutes old). `load-testing.person` started at 0 documents (3 shards, 2 replicas) and ended at
1,106,420. Same four processes, same caps, `DURATION_SECONDS=600`.

| operation | count | errors | ops/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| STOMP bulk save (200) | 3,199 | 0 | 5.2 | 27 ms | 79 ms | 160 ms | 1.42 s |
| STOMP search | 32,199 | 0 | 53.6 | 9 ms | 27 ms | 65 ms | 1.09 s |
| STOMP find all | 12,599 | 0 | 20.9 | 5 ms | 17 ms | 49 ms | 1.13 s |
| OpenAPI save | 12,020 | 0 | 20.0 | 28 ms | 83 ms | 218 ms | 2.26 s |
| OpenAPI bulk save (200) | 2,273 | 0 | 3.8 | 65 ms | 158 ms | 289 ms | 2.26 s |
| OpenAPI find by id | 11,954 | 0 | 19.9 | 4 ms | 23 ms | 69 ms | 928 ms |
| OpenAPI find all | 7,173 | 0 | 11.9 | 4 ms | 18 ms | 139 ms | 958 ms |
| OpenAPI search | 9,453 | 0 | 15.7 | 5 ms | 20 ms | 123 ms | 950 ms |
| OpenAPI count | 4,627 | 0 | 7.7 | 4 ms | 16 ms | 106 ms | 676 ms |

- 95,497 client operations, 0 failures, about 155 requests/s over 617 s (the OpenAPI process ran
  at 79 ops/s against its nominal 50/s cap; see below). 1,106,420 documents indexed, about 1,800/s;
  Elasticsearch counted 2,212,840 index operations across the two nodes' primaries and replicas,
  which is the measure the first run's 2,097,618 used, and 66,051 shard-level search queries.
- No pod restarts. No WARN or ERROR in any server pod's log for the window.
- Structures pods: 86, 103 and 288 m CPU at the median, peaks 231, 265 and 965 m; memory 767, 877
  and 797 MiB at the start, 1,130, 1,162 and 1,275 MiB at the end. Elasticsearch 250 and 273 m
  median, 482 m peak, heap 24-36 % at the end. Ingress 23 m median, 28 m peak.
- No drift: from the second 30 s window to the last, STOMP search p95 was 16-39 ms, STOMP bulk
  save 33-144 ms, OpenAPI save 46-103 ms. The first window carries warm-up (p95 227-277 ms). One
  window (t=150 s) had the write-path spike again, on STOMP bulk save (max 1.42 s) and OpenAPI
  save and bulk save (p99 1.4 s, max 2.26 s) together and on nothing else: the same signature as
  the first run's t=510 s window, an Elasticsearch refresh or merge, not a transport.

Against the first run, the shape is the same. Reads are equal or better (STOMP search p95 27 ms
against 30, OpenAPI reads 16-23 ms against 14-19 at a higher offered rate). Writes are within the
window-to-window range of either run (bulk save p50 27 ms against 28, p95 79 against 65; OpenAPI
save p50 28 against 26, p95 83 against 76), on an index growing from empty and with the OpenAPI
process issuing 79 ops/s where it issued 70 before. Nothing here separates the bound field readers
from noise, which is the expected result: the change removed one small allocation per decorated
field per entity, not time.

Worth knowing, in addition to the first run's notes:

- **Memory rose again, 285-478 MiB per pod over the ten minutes**, from a lower start than the
  first run (the pods were minutes old). Same open question, same answer: a longer soak.
- **One pod ran three times hotter than the other two** (288 m median against 86 and 103). The
  three STOMP generators hold one WebSocket each and the ingress keeps those sticky to a pod, so
  the STOMP work and the Ignite routing that follows it land on one pod; the OpenAPI traffic is
  spread by nginx. Worth a look at the balance when more STOMP clients are involved.
- **The generator's rate cap is a ceiling in name only when the queue keeps draining.** p-queue's
  fixed-window limiter restarts its window when the queue empties and refills within the same
  second, which this workload does constantly, so the OpenAPI process issued 79 ops/s against a
  50/s cap (70 in the first run). The numbers above are the observed rates, which is what matters
  for comparing runs; a sliding-window limiter, or a lower nominal cap, is a load-generator change
  for the next cycle.
- The processes ran 600-617 s: tasks already queued when the deadline passed still run, up to the
  queue depth of 100, at the rate cap.

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
