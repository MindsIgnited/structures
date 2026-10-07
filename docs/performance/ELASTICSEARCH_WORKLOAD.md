# Working well with Elasticsearch

**Status:** switches for forced refreshes done; write throttling planned. This is the place to
record changes that make Structures easier on Elasticsearch. The first entry is throttling writes
so they stop slowing reads.

## Write throttling (October 2026)

### What happened

Production went from one Structures pod to three. The single pod had been limiting the
integrations without anyone intending it to, so write throughput roughly tripled once the extra
pods were up. Both reads and writes then slowed down. The Structures pods look healthy; the
latency is in Elasticsearch.

What we know about the traffic:

- Most integration writes are `bulkUpdate` and `bulkSave`. Single `save` and `update` calls are
  much rarer.
- Integrations can be slowed down, even paused for a minute or more. How fast they run is allowed
  to vary.
- Some integrations need to read their own writes.

The goal is to throttle inside Structures, so the integrations need no changes. Writes should wait
when Elasticsearch is busy, not fail.

### Why writes hurt reads today

- **Every Elasticsearch node indexes every document.** Indexes and index templates are created
  with 3 shards and 2 replicas (`CrudServiceTemplate.createIndex` and `createIndexTemplate`). On a
  3-node cluster each node holds a full copy, so adding Structures pods raises the write rate
  without adding any write capacity.
- **Reads and writes share one connection pool.** `StructuresElasticsearchConfig` builds one
  `RestClient` and never sets pool sizes, so the low-level client's defaults apply (10 connections
  per host, 30 in total, per pod). A burst of bulk calls can take every connection, and reads then
  wait inside Structures before Elasticsearch even sees them. No connection request timeout is set
  either, so a request waiting for a connection has no time limit.
- **Single writes force a refresh.** `DefaultEntityService.save` and `update`, and deletes through
  `ReadPreProcessor.beforeDelete`, all pass `Refresh.True`. Each one makes a new small segment on
  every shard copy it touches, which adds merging and clears caches. Bulk calls don't refresh, so
  this matters less given the traffic mix, but it is still a cost.
- **`syncIndex` refreshes the whole index.** We don't know how often integrations call it. If the
  ones that need to read their own writes call it after every bulk, it is a hidden refresh on
  every bulk.
- **`bulkUpdate` costs more than `bulkSave`.** It sends partial updates with `docAsUpsert`, so
  Elasticsearch fetches each existing document, merges the change and indexes the whole document
  again. `bulkSave` just indexes the document it is given.

### Plan

#### 1. Separate read and write clients

Two `ElasticsearchAsyncClient` beans, each with its own connection pool:

- **Built by one factory method** in `StructuresElasticsearchConfig`, so both get the same hosts,
  credentials, timeouts, TCP options and failover behaviour. If the two drift apart, one of them
  loses the failover work from PR #21.
- **The reader stays the default bean.** `CrudServiceTemplate`, named queries and the other
  services don't change. The writer is injected with a qualifier (e.g. `esWriteClient`) into
  `DefaultEntityService`, and only its write paths use it: `doPersist`, `doPersistBulkLogic` and
  `deleteByQuery`.
- **The writer's pool size is the write throttle.** With a cap of 4 connections, each pod sends
  at most 4 write requests to Elasticsearch at a time, or 12 across 3 pods. Writes over the cap
  wait for a connection, which slows the integration down without any change on its side.
- **Set a connection request timeout on the writer.** A write that waits longer fails with a
  retryable error. The timeout must be shorter than the integrations' own client timeout.
  Otherwise an integration gives up while its write is still queued, may retry, and adds load
  instead of removing it.
- **Size the reader pool explicitly** too, instead of relying on the defaults.

#### 2. Split large bulk calls into chunks

A pool limits requests, not documents. A 10,000-document bulk and a 10-document bulk each hold
one connection. Splitting bulk calls into fixed-size chunks in `doPersistBulkLogic` makes the cap
firm: connections × chunk size. For example, 4 connections × 500 documents = 2,000 documents in
flight per pod.

- **Send the chunks of one call one after another, not in parallel.** That keeps the original
  order when the same id appears more than once in a call, and it means one call holds at most
  one write connection at a time, so other integrations get a turn between chunks.
- **Keep today's error handling.** Today every item is attempted and the call fails with the
  combined error reasons if any item failed. Do the same across chunks: run all of them, collect
  the errors, and fail once at the end. Elasticsearch bulk requests are not atomic, so a chunk
  that succeeded before another failed matches what already happens inside a single bulk.

#### 3. Adaptive write limiter (only if the metrics call for it)

A pool's size can't easily change while the server runs. If a fixed cap turns out to be too
blunt, put a limiter in front of the writer client:

- Count documents in flight, not requests.
- Waiting writes queue in arrival order and must not block a thread. A plain `Semaphore.acquire()`
  would stall the Vert.x event loop, so it has to be a queue of `CompletableFuture`s.
- Lower the limit when Elasticsearch latency or 429 rejections rise, and raise it slowly when they
  recover (additive increase, multiplicative decrease). Driving it from read latency protects
  reads directly instead of relying on a number tuned once.
- If one integration turns out to use the whole budget, add per-tenant fairness.

#### Done: switches for forced refreshes

Two settings, both `true` by default so nothing changes unless they are set:

- `structures.elasticRefreshAfterMutation`: whether a single `save` or `update` forces a refresh.
- `structures.elasticRefreshAfterDelete`: whether a delete by id forces a refresh.

In Helm they are `properties.structures.elastic.refreshAfterMutation` and `refreshAfterDelete`.
The plan is to turn them off in controlled environments first. Integrations that need to read
their own writes then call `syncIndex` only when they actually need it, instead of every write
paying for a refresh. With a setting off, a change becomes searchable after the next scheduled
refresh (1 s by default) or a `syncIndex` call, whichever comes first. Find by id sees it straight
away either way, since an Elasticsearch get is realtime.

The tests are `EntityRefreshAfterMutationDisabledTests` and
`EntityRefreshAfterDeleteDisabledTests`. They switch scheduled refreshes off on the index, so they
check exactly when each change becomes searchable.

#### Other changes worth making

- **Consider `Refresh.WaitFor` instead of `Refresh.True`** for callers that still want to read
  their writes. The request waits for the next scheduled refresh (1 s by default) instead of
  forcing one. This could become a third value for the settings above.
- **Use a longer `refresh_interval`** (5-10 s) on the indexes that take the most bulk writes. This
  means fewer segments and less merging, at the cost of new writes taking a few seconds longer to
  show up in searches.
- **Review whether 2 replicas is needed.** With 1 replica, each node does about a third less
  indexing, at some cost to durability. Since the count is hard-coded in `CrudServiceTemplate`,
  making it a setting is part of this change.
- **Prefer `bulkSave` where an integration can send whole documents.** It avoids the
  fetch-and-merge that `bulkUpdate` costs Elasticsearch on every document.

### Proposed settings

Names are a sketch, to be settled during implementation:

```yaml
structures:
  elastic-read-pool:
    max-connections: 30
  elastic-write-pool:
    max-connections: 4          # per pod; the cluster total is this x the number of pods
    max-wait: 30s               # connection request timeout; keep below the integrations' client timeout
  elastic-bulk-chunk-size: 500
```

The limits are per pod. If the number of pods changes (for example, through autoscaling), the
total load on Elasticsearch changes with it.

**Where to start sizing:** keep the total number of concurrent bulk requests across all pods near
the size of Elasticsearch's write thread pool, which is one thread per core on each node. Then
adjust from the metrics.

### Metrics to collect first

Get a production baseline before changing anything.

**Structures, per pod:**

- Bulk documents per second, by structure and by operation (`bulkSave` or `bulkUpdate`)
- Distribution of bulk call sizes
- Bulk call latency
- Read latency at p50 and p95 for search, named queries and find by id
- How often `syncIndex` is called, and by which integrations
- Once steps 1-2 are in: connections in use per pool, time spent waiting for a write connection,
  and wait timeouts

**Elasticsearch:**

- `GET _cat/thread_pool/write,search?v`: active, queued and rejected for both pools
- `GET _nodes/stats/indices/indexing,search,refresh,merges`: refresh and merge counts and time, and
  search time per query
- `GET _nodes/stats/indexing_pressure`

### Verifying

On KinD with the load generator (see [LOAD_TESTING.md](../../LOAD_TESTING.md)):

1. Keep reads running at a steady rate (`sustainedSearch` and `sustainedFindAll`), and push bulk
   writes hard with `sustainedBulkSave` at high concurrency and large bulk sizes.
2. Compare read p95 with no write limit and with a small write pool plus chunking.
3. Check that writes slow down but don't fail, and that no waiting write times out at the chosen
   `max-wait`.
4. Watch the Elasticsearch write thread pool for rejections in both runs.

### Open questions

- When an integration gets an error or a 429, does it retry, back off or drop the data? This
  decides how long writes may queue before Structures rejects them.
- What is the continuum RPC client's request timeout? `max-wait` has to stay below it.
- Which integrations need to read their own writes, and do they call `syncIndex` to do it?
- How many cores does each production Elasticsearch node have? That sets the write thread pool
  size, which is where pool sizing starts.
