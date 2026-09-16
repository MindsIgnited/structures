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

## Soak: one hour on the develop snapshot

The question both ten-minute runs left open was memory: pods rose 300-480 MiB in ten minutes, which
cannot tell a JVM growing into its heap from a leak. The same four generators for 3,600 s, on
`mindsignited/structures-server:3.6.0-SNAPSHOT` (digest `b20c3df0…`, the develop build of the PR #12
merge `e371b4de`; the code that ships as 3.6.0 plus the npm consumer bumps), three pods started
minutes before the run, `load-testing.person` from 0 documents. A detail that frames the memory
numbers: the pods run with no memory limit, so the buildpack's memory calculator sizes the heap from
the host and the JVM starts with `-Xmx49895592K` (47.6 GB). Nothing pushes it to collect early.

**First attempt, aborted at 21 minutes: the rig failed, not the server.** Elasticsearch node 0 was
OOM-killed (exit 137) at 21 minutes against its 2 GiB limit with a 1 GiB heap; both nodes had sat at
1.9 GiB, and the second one survived only because it was killed first. Its data being an emptyDir,
the node came back empty, and a two-node cluster cannot recover from that either way (no quorum for
the survivor, nothing to join for the newcomer), so every write got 503 from then on. The server pods
kept running and logged only the health-check failures until their liveness probe, which reports
Elasticsearch's health, restarted them twelve minutes into the outage. The 21 clean minutes are still
data: the three pods went 877, 1,008, 1,014 MiB at the start to 899, 1,157, 1,413 MiB at five minutes
and 919, 1,155, 1,422 MiB at twenty-one. Flat after the first five. The Elasticsearch limit is now
4 GiB (`dev-tools/kind/config/elasticsearch/values.yaml`), LOAD_TESTING.md has the reset procedure,
and the run was repeated from a rebuilt store.

**Second attempt, the full hour.**

| operation | count | errors | ops/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| STOMP bulk save (200) | 18,299 | 0 | 5.1 | 28 ms | 66 ms | 117 ms | 1.25 s |
| STOMP search | 191,699 | 0 | 53.2 | 11 ms | 30 ms | 60 ms | 1.35 s |
| STOMP find all | 73,999 | 0 | 20.5 | 5 ms | 14 ms | 24 ms | 909 ms |
| OpenAPI save | 68,069 | 0 | 18.9 | 27 ms | 69 ms | 114 ms | 1.13 s |
| OpenAPI bulk save (200) | 13,807 | 0 | 3.8 | 63 ms | 132 ms | 192 ms | 1.22 s |
| OpenAPI find by id | 68,345 | 0 | 19.0 | 4 ms | 18 ms | 31 ms | 1.14 s |
| OpenAPI find all | 40,749 | 0 | 11.3 | 4 ms | 12 ms | 24 ms | 1.19 s |
| OpenAPI search | 54,677 | 0 | 15.2 | 5 ms | 15 ms | 30 ms | 1.19 s |
| OpenAPI count | 27,053 | 0 | 7.5 | 3 ms | 11 ms | 22 ms | 730 ms |

- 556,697 client operations, 0 failures, about 154 requests/s for 3,614 s; 6,489,269 documents
  indexed from an empty index (about 1,800/s), 4.2 GB on disk at the end.
- No pod restarts. No WARN or ERROR in any of the three server pods' logs for the window, each pod
  scanned by name.
- Every per-window maximum above 0.6 s falls in the first ten minutes (cold pods, empty index).
  From then on: STOMP search p95 18-76 ms per 30 s window, STOMP bulk save 34-174 ms, OpenAPI save
  36-199 ms, reads 8-34 ms, and no window's maximum above 0.53 s. No drift between the second and
  the sixth ten-minute block.
- Elasticsearch: 256 and 252 m CPU median, peaks 878 and 998 m; memory 1,420 and 1,437 MiB rising
  to 1,626 and 1,624 MiB over the hour (the 2 GiB limit would have been crossed again around the
  fortieth minute), heap 58-64 % at the end. Ingress 23 m median.

**Memory, per pod, MiB at minute marks:**

| pod | 0 | 5 | 10 | 20 | 30 | 40 | 50 | 60 | CPU median / peak |
|---|---|---|---|---|---|---|---|---|---|
| 6t465 | 868 | 1,268 | 1,270 | 1,271 | 1,270 | 1,344 | 1,410 | 1,411 | 158 m / 747 m |
| nt5zx | 826 | 1,037 | 1,038 | 1,071 | 1,126 | 1,181 | 1,185 | 1,189 | 183 m / 563 m |
| q48xd | 756 | 1,134 | 1,138 | 1,191 | 1,210 | 1,185 | 1,194 | 1,208 | 60 m / 404 m |

The shape is the same on all three: a jump of 300-400 MiB in the first five minutes, then a slow
climb of 74-152 MiB spread over the middle of the hour, then flat for the last ten to twenty minutes
(one pod flat from minute 20). That is a JVM settling its heap under a 47.6 GB ceiling with nothing
asking it to be frugal, not a leak; a leak at this load would not stop. What the ten-minute runs saw
was the first five minutes of this curve. An hour cannot exclude a very slow leak, but it moves the
question from "does it grow" to "does it grow after it has settled", and the answer here is no.

Recommendation that falls out of it: give the server pods a memory limit in any deployment whose
memory matters. The buildpack sizes the heap from the container limit when there is one, and the
chart's `javaToolOptions` sets none, so today the heap ceiling is whatever the node has. The KinD
values run without limits on purpose, so that growth shows in `kubectl top` rather than as an OOM
kill, and that stays.

Also seen: the OpenAPI process again ran above its nominal cap (about 75 ops/s against 50, the same
p-queue window behaviour), and the STOMP-heavy pod (6t465) was the one whose memory stepped up
between minutes 30 and 50, which is where the sticky STOMP connections put the ingest work.

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
