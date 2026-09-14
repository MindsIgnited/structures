# Load Testing Structures

How Structures is load tested before a release is promoted, what the setup assumes, what each
test exercises, and what the runs have shown. The most recent run is recorded in
[docs/performance/LOAD_TEST_3.6.0.md](docs/performance/LOAD_TEST_3.6.0.md); this document is the
method behind it.

## Goals and expectations

The runs are due diligence, not a benchmark of the ceiling. A release is expected to take a
sustained, mixed load over both transports for at least ten minutes and show:

- **No failures.** Every operation returns; a single client-visible error is investigated.
- **No instability.** No pod restarts, no `WARN` or `ERROR` in any server pod's log for the window.
- **No drift.** Latency in the last 30 s window looks like the second (the first carries warm-up).
- **Headroom.** CPU and memory stay well below what the machine offers, so the numbers reflect
  the software and not the host.
- **Latency in the expected band** for this topology: reads and searches p95 under 50 ms,
  single saves under 100 ms, 200-document bulk saves under 200 ms. Outliers are read against the
  window they occur in, not the run total.

Absolute numbers are only comparable between runs on the same hardware and topology; between
releases, the useful comparison is the shape - same load, same profile of p50/p95/p99, same
resource footprint.

## Assumptions

- The cluster is the **KinD cluster** `dev-tools/kind` creates: three Structures replicas behind
  ingress-nginx with TLS, a two-node Elasticsearch, Ignite clustering over Kubernetes discovery.
  It is a functional stand-in for production topology, not for production hardware: every node is
  a container on one machine, and the load generators run on that same machine.
- The generators, the ingress and the pods share the host's CPU. Generator processes are kept
  light (four Node processes at a combined ~120 req/s cost a fraction of one core) so they do not
  compete with the system under test; watch `docker stats` if the rates are raised.
- Traffic goes through the ingress, as it would in production: STOMP over WebSocket at
  `wss://localhost/v1`, OpenAPI at `https://localhost/api`. Use `localhost`, not
  `structures.local`: on macOS a `.local` name goes to mDNS before `/etc/hosts` and every new
  connection waits 5 s for that to time out. The mkcert certificate and the ingress rules cover both.
- One structure, `load-testing.person` (multi-tenant `SHARED`, client-supplied ids, an address
  sub-object, a short `age`), one tenant. Data accumulates across runs; the run record notes the
  starting document count where it matters.
- Elasticsearch is left at its defaults (1 s refresh). Write-path spikes that hit STOMP and
  OpenAPI in the same window are usually its refresh or merge activity, not either transport.
- The server pods run with no CPU or memory limits, so a leak or a runaway shows up as growth in
  `kubectl top`, not as an OOM kill.

## Hardware and test infrastructure

The 3.6.0 run, and the reference for future comparison:

| | |
|---|---|
| Machine | MacBook Pro, Apple M4 Max, 16 cores (12 performance, 4 efficiency), 128 GB, macOS 26.5 |
| Docker | Docker Desktop, engine 29.2, VM with 16 CPUs / 63 GB |
| Kubernetes | KinD v0.30, Kubernetes v1.34, one control-plane + three worker nodes (each sees all 16 CPUs) |
| Ingress | ingress-nginx controller v1.15, on the control-plane node, 2 CPU / 1 GiB limit (`dev-tools/kind/config/ingress-nginx/values.yaml`) |
| Elasticsearch | 8.19.13, two nodes, 1 CPU / 2 GiB each, 1 GiB heap (`dev-tools/kind/config/elasticsearch/values.yaml`) |
| Structures | three replicas, no resource limits, image built by CI from the release-candidate commit |
| Metrics | metrics-server v0.9 (`kubectl top`, sampled every 15 s), Elasticsearch `_nodes/stats`, pod logs |
| Generators | Node 24, `structures-js/load-generator`, `@kinotic/continuum-client` 3.0.0, on the host |

`./dev-tools/kind/kind-cluster.sh create` installs all of this; `deploy --tag <image tag>` puts the
candidate image on it.

## The tests

All live in `structures-js/load-generator`. Each is one Node process driving one workload with a
concurrency cap (`MAX_CONCURRENT_REQUESTS`), a rate cap (`MAX_REQUESTS_PER_SECOND`) and a duration
(`DURATION_SECONDS`); a run is several of them at once. Every operation is timed in the executor,
so each process reports count, errors, rate and p50/p90/p95/p99/max per operation every
`REPORT_INTERVAL_SECONDS` and at the end, and writes the totals as JSON to `REPORT_FILE`.

**STOMP tests** open one WebSocket through the ingress, authenticate as `admin`, and call the
entity service the way the TypeScript client does; requests are multiplexed on that connection,
so these measure the server, the event bus and Ignite routing rather than connection setup.

- `sustainedBulkSave` - `bulkSave` of `BATCH_SIZE` generated people (default 200) per request:
  the ingest path, JSON parsing, id handling and an Elasticsearch bulk index per request.
- `sustainedSearch` - `search` with a Lucene query (`SEARCH_TEXT`, default `firstName: John`),
  page `PAGE_SIZE` (100): query parsing, tenant filtering, a search per request.
- `sustainedFindAll` - `findAll` of the first page of `PAGE_SIZE`: the cheapest read, a match-all
  with tenant filter, useful as the baseline the others are read against.

**`openApiMixed`** drives the OpenAPI endpoints of the same structure over HTTPS through the
ingress with keep-alive connections, weighted the way an application would use them, so the HTTP
router, its JSON handling and authentication are exercised alongside STOMP:

| operation | share | what it exercises |
|---|---|---|
| save (one person) | 25 % | single-document ingest, JSON body parsing |
| bulk save (`BATCH_SIZE`) | 5 % | bulk ingest over HTTP |
| find by id | 25 % | a get by id; ids come from this process's own saves, so they hit real rows |
| find all (page) | 15 % | first page of `PAGE_SIZE` |
| search | 20 % | Lucene query as a `text/plain` body |
| count | 10 % | `count/all` |

**`createPersonStructure`** creates and publishes `load-testing.person` if it is missing and is
run once before the first load test against a cluster.

The older count-bounded tests (`bulkLoadSmall`/`Medium`/`Large`, `search`, `findAll`, the
multi-tenant variants, `generateComplexStructures`) still exist and get the same reporting.

## Running a test

```sh
cd structures-js/load-generator && pnpm install && pnpm build
export NODE_EXTRA_CA_CERTS="$(mkcert -CAROOT)/rootCA.pem" NODE_ENV=production OTEL_SDK_DISABLED=true \
       STRUCTURES_HOST=localhost STRUCTURES_PORT=443 STRUCTURES_USE_SSL=true \
       STRUCTURES_OPENAPI_BASE_URL=https://localhost/api START_DELAY_SECONDS=0 LOG_TASKS=false

# once per cluster
TEST_NAME=createPersonStructure MAX_CONCURRENT_REQUESTS=1 node dist/main.mjs

# the standard ten-minute run
export DURATION_SECONDS=600 REPORT_INTERVAL_SECONDS=30
TEST_NAME=sustainedBulkSave BATCH_SIZE=200 MAX_CONCURRENT_REQUESTS=2  MAX_REQUESTS_PER_SECOND=5  REPORT_FILE=bulk.json   node dist/main.mjs &
TEST_NAME=sustainedSearch                  MAX_CONCURRENT_REQUESTS=16 MAX_REQUESTS_PER_SECOND=40 REPORT_FILE=search.json node dist/main.mjs &
TEST_NAME=sustainedFindAll                 MAX_CONCURRENT_REQUESTS=4  MAX_REQUESTS_PER_SECOND=20 REPORT_FILE=find.json   node dist/main.mjs &
TEST_NAME=openApiMixed                     MAX_CONCURRENT_REQUESTS=16 MAX_REQUESTS_PER_SECOND=50 REPORT_FILE=api.json    node dist/main.mjs &
wait
```

While it runs, sample the cluster every 15 s: `kubectl top pods` for the Structures,
Elasticsearch and ingress pods, `docker stats` for the KinD nodes, and Elasticsearch
`_nodes/stats` (indexing and search totals, heap). Afterwards, check restarts
(`kubectl get pods`) and the logs of every server pod for `WARN` and `ERROR` over the window.

`OTEL_SDK_DISABLED=true` keeps the generator's OpenTelemetry SDK from trying to export to a
collector that is not there; `START_DELAY_SECONDS` defaults to 60 for the Docker case where the
server is still starting.

## Outcomes so far

**3.6.0 candidate** ([full record](docs/performance/LOAD_TEST_3.6.0.md)) - Spring Boot 4.1.1,
Vert.x 5.1.8, Jackson 3, continuum 3.1.0, the four processes above for ten minutes:

- 89,397 operations at ~122 requests/s across both transports, ~3,500 documents/s indexed
  (2.1 M in the run), **0 failures**, no restarts, no `WARN` or `ERROR` logged.
- STOMP search p50 10 ms / p95 30 ms / p99 60 ms; STOMP bulk save (200) p50 28 ms / p95 65 ms;
  OpenAPI save p50 26 ms / p95 76 ms; OpenAPI reads p95 15–19 ms.
- Structures pods 100–280 m CPU at the median, peaks under 400 m; Elasticsearch 225 m median per
  node, heap 68 % at most; ingress 29 m at peak.
- Flat across the run's windows after warm-up. One window carried a single 1.5–1.9 s spike on both
  write paths at once (Elasticsearch refresh or merge).
- Open question: pod memory rose about 300 MiB over the ten minutes on two of three pods. A JVM
  growing into its heap looks the same as a slow leak at this length; a longer soak at the same
  load would settle it.

## What a release run should add

- The same four processes, same rates, on the candidate image, compared against the previous
  record's table.
- A longer soak (an hour) at least once per major dependency change, for the memory question.
- Any new transport or hot path gets a workload here before it ships.
