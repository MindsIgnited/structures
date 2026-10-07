# Working well with Elasticsearch

**Status:** refresh settings for single writes done; write throttling planned. This is the place to
record changes that make Structures easier on Elasticsearch. The first entry is throttling writes
so they stop slowing reads.

## Terms used here

- **Refresh:** Elasticsearch doesn't make new or changed documents searchable straight away. It
  collects them and publishes them to search in batches, by default once a second. Each publish is
  a refresh. A caller can also force one immediately.
- **Segment:** each refresh writes the batched changes into a new file on disk, called a segment.
  Elasticsearch keeps merging small segments into bigger ones in the background. Many small
  segments mean more merging and slower searches.
- **Shard and replica:** an index is split into shards, and each shard can have copies (replicas)
  on other nodes. Every copy has to index every document written to its shard.
- **Bulk call:** one request that saves or updates many documents at once (`bulkSave`,
  `bulkUpdate`), as opposed to a single `save` or `update`.
- **`syncIndex`:** a Structures call that forces a refresh of a structure's whole index, so
  everything written so far becomes searchable.

## Write throttling (October 2026)

### What happened

A Structures deployment was scaled from one pod to three. One pod had been limiting how fast
clients could write, without anyone intending it to. With three pods, the write rate to
Elasticsearch roughly tripled, and both reads and writes slowed down. The Structures pods stayed
healthy; the slowness was in Elasticsearch.

The workload looked like this:

- Most writes arrive as bulk calls. Single `save` and `update` calls are much rarer.
- The clients doing those writes can tolerate being slowed down, even paused for a minute or more.
- Some clients need to search for data straight after writing it.

The goal is to throttle writes inside Structures, so no client has to change. When Elasticsearch
is busy, writes should wait, not fail.

### Why writes slow reads today

- **Every Elasticsearch node indexes every document.** Indexes and index templates are created
  with 3 shards and 2 replicas (`CrudServiceTemplate.createIndex` and `createIndexTemplate`). On a
  3-node cluster each node holds a full copy of the data, so adding Structures pods raises the
  write rate without adding any write capacity.
- **Reads and writes share one connection pool.** `StructuresElasticsearchConfig` builds one
  `RestClient` and never sets pool sizes, so the client's defaults apply: 10 connections per host
  and 30 in total, per pod. A burst of bulk calls can take every connection, and reads then wait
  inside Structures before Elasticsearch even sees them. No connection request timeout is set
  either, so a request waiting for a connection has no time limit.
- **Single writes force a refresh.** By default, `DefaultEntityService.save`, `update` and
  `deleteById` ask Elasticsearch to refresh before returning. Each forced refresh writes a new
  small segment on every copy of the shard it touched, which adds merging and clears caches. Bulk
  calls don't force a refresh, so with mostly bulk traffic this matters less, but it is still a
  cost. This is now configurable; see "Done: refresh settings for single writes" below.
- **`syncIndex` refreshes the whole index.** If clients that need to search their own writes call
  it after every bulk call, every bulk call pays for a full refresh.
- **`bulkUpdate` costs more than `bulkSave`.** `bulkUpdate` sends partial updates, so
  Elasticsearch fetches each existing document, merges in the change and writes the whole document
  again. `bulkSave` just writes the document it is given.

### Plan

#### 1. Separate read and write clients

Two `ElasticsearchAsyncClient` beans, each with its own connection pool:

- **Built by one factory method** in `StructuresElasticsearchConfig`, so both get the same hosts,
  credentials, timeouts, TCP options and failover behaviour. If the two drift apart, one of them
  loses the failover behaviour described under `elasticConnections` in the
  [server config reference](../../webdocs/reference/structures-server-config.md).
- **The reader stays the default bean.** `CrudServiceTemplate`, named queries and the other
  services don't change. The writer is injected with a qualifier (e.g. `esWriteClient`) into
  `DefaultEntityService`, and only its write paths use it: `doPersist`, `doPersistBulkLogic` and
  `deleteByQuery`.
- **The writer's pool size is the write throttle.** With a cap of 4 connections, each pod sends at
  most 4 write requests to Elasticsearch at a time, or 12 across 3 pods. Writes over the cap wait
  for a free connection. The client simply sees a slower response and needs no changes.
- **Set a connection request timeout on the writer.** A write that waits longer than this fails
  with an error the client can retry. The timeout must be shorter than the client's own request
  timeout. Otherwise the client gives up while its write is still queued, may send it again, and
  adds load instead of removing it.
- **Size the reader pool explicitly** too, instead of relying on the defaults.

#### 2. Split large bulk calls into chunks

A pool limits requests, not documents: a 10,000-document bulk call and a 10-document one each hold
one connection. Splitting bulk calls into fixed-size chunks in `doPersistBulkLogic` makes the limit
firm: connections × chunk size. For example, 4 connections × 500 documents = 2,000 documents in
flight per pod.

- **Send the chunks of one call one after another, not in parallel.** That keeps the original
  order when the same id appears more than once in a call. It also means one call holds at most
  one write connection at a time, so other callers get a turn between chunks.
- **Keep today's error handling.** Today every document in the call is attempted, and the call
  fails with all the error reasons combined if any of them failed. Do the same across chunks: run
  all of them, collect the errors, and fail once at the end. An Elasticsearch bulk request is not
  all-or-nothing anyway, so a chunk that succeeded before another failed matches what already
  happens within a single bulk call.

#### 3. Adaptive write limiter (only if the metrics call for it)

A pool's size can't easily change while the server runs. If a fixed limit turns out to be too
blunt, put a limiter in front of the writer client:

- Count documents in flight, not requests.
- Waiting writes queue in arrival order and must not block a thread. A plain `Semaphore.acquire()`
  would stall the Vert.x event loop, so it has to be a queue of `CompletableFuture`s.
- Lower the limit when Elasticsearch slows down or starts rejecting requests (HTTP 429), and raise
  it slowly when it recovers (additive increase, multiplicative decrease). Driving it from read
  latency protects reads directly, instead of relying on a number tuned once.
- If one tenant turns out to use the whole budget, add per-tenant fairness.

#### Done: refresh settings for single writes

Two settings decide what a single write asks of Elasticsearch before returning. Both default to
`true`, which is what Structures always did, so nothing changes unless they are set:

- `structures.elasticRefreshAfterMutation`: for a single `save` or `update`.
- `structures.elasticRefreshAfterDelete`: for a delete by id.

In Helm they are `properties.structures.elastic.refreshAfterMutation` and `refreshAfterDelete`.
Each takes one of three values, the same ones Elasticsearch's own `refresh` parameter takes:

| Value | The call returns | Searchable when it returns | Cost to Elasticsearch |
|-------|------------------|----------------------------|-----------------------|
| `true` (default) | after forcing a refresh | yes | a new small segment for every write |
| `wait_for` | after the next scheduled refresh (within 1 s by default) | yes | none extra; the caller waits instead |
| `false` | straight away | no: after the next scheduled refresh, or a `syncIndex` call | none |

Find by id sees a change straight away whatever the value, because Elasticsearch reads a single
document by id without waiting for a refresh. Bulk calls and delete by query never refresh.

The values are an enum, `ElasticRefreshPolicy`. Spring Boot matches enum values ignoring case and
punctuation, so `true`, `TRUE`, `wait_for` and `wait-for` all work, from YAML, Helm or environment
variables.

**Which value to use.** `wait_for` keeps the old guarantee (search sees a change as soon as the
call returns) without the forced refresh, so it's the natural replacement for `true` where callers
search their own writes. `false` is cheapest. It suits callers that don't search their own writes,
or that call `syncIndex` only when they actually need to. The plan is to try both in test
environments first.

**`wait_for` holds a connection while it waits.** Reads and writes still share one pool of 10
connections per Elasticsearch host (step 1 above would separate them). A `wait_for` write keeps
its connection for up to the refresh interval, so many concurrent ones queue behind each other and
can hold up reads. The test suite shows it: `testMultiTenantSearch` saves about 50 entities at once
in each of 10 rounds and takes about 60 s with `wait_for`, against about 1 s with `true`. Until
reads and writes have separate pools, `wait_for` suits occasional single writes, not high-volume
ones. Two more interactions:

- **Refresh interval.** The wait is up to the index's refresh interval. If busy indexes move to a
  5-10 s interval (see below), `wait_for` writes to them wait that long.
- **Disabled refresh.** An index with `refresh_interval: -1` makes `wait_for` writes wait until
  something else refreshes it.

**Watch out for optimistic locking with `false`.** On structures with a version field, search
results carry the version an entity had at the last refresh. With `false`, an entity found by
search straight after it was updated still has its old version, and updating it fails with a
version conflict. Clients should read an entity by id before updating it, since that always
returns the current version, or call `syncIndex` first. `true` and `wait_for` don't have this
problem.

Tests:

- `EntityRefreshAfterMutationDisabledTests`, `EntityRefreshAfterDeleteDisabledTests` and
  `EntityRefreshWaitForTests` check exactly when each change becomes searchable, and that the
  version conflict above happens only with `false`. For `true` and `false` they switch scheduled
  refreshes off on the index, so nothing else can make a change searchable. For `wait_for` they
  leave them on, since a `wait_for` write would otherwise never return.
- `EntityCrudRefreshDisabledTests`, `BulkUpdateRefreshDisabledTests`, `EntityCrudWaitForTests` and
  `BulkUpdateWaitForTests` run every entity service test again with both settings `false`, and
  again with both `wait_for`. They pass because those tests already call `syncIndex` before
  searching, which is what clients using `false` should do.

Follow-ups, not done yet:

- **A setting for bulk calls.** `elasticRefreshAfterBulk`, defaulting to `false` (today's
  behaviour), would let bulk writers that search their own data use `wait_for` instead of
  `syncIndex`, which refreshes the whole index. Best left until the metrics are in, and until
  reads and writes have separate pools.
- **Per-structure or per-call control.** The settings apply to every structure at once. A
  structure-level option, or a per-request one, could override them, so only the busiest indexes
  stop forcing refreshes while structures used by interactive apps keep the default. The enum
  already fits this.

#### Other changes worth making

- **Refresh less often** (`refresh_interval` of 5-10 s) on the indexes that take the most bulk
  writes. That means fewer segments and less merging, at the cost of new writes taking a few
  seconds longer to show up in searches.
- **Review whether 2 replicas is needed.** With 1 replica, each node does about a third less
  indexing, at some cost to durability. The count is hard-coded in `CrudServiceTemplate`, so making
  it a setting is part of this change.
- **Use `bulkSave` when the whole document is sent.** Decided October 2026: clients that always
  send complete documents move from `bulkUpdate` to `bulkSave`, which avoids the fetch-and-merge
  `bulkUpdate` costs Elasticsearch on every document. Before moving a client, check:
  - **`bulkSave` replaces the stored document.** Any field missing from what is sent is dropped, so
    the client really must send the whole document every time.
  - **Structures with optimistic locking need the version.** On those, `bulkSave` of a document
    without a version is sent as a create, which fails if the document already exists.
    `bulkUpdate` would create or update it instead. Stream structures always create.
  - **Unchanged documents are written again.** `bulkUpdate` checks whether anything changed
    (`detectNoop`), so a document sent unchanged costs Elasticsearch a fetch but no write.
    `bulkSave` writes it again every time. If a client mostly sends documents that haven't changed,
    `bulkUpdate` may be cheaper for it. The bulk response marks each skipped document as a `noop`,
    so counting those shows which case a client is in.

### Proposed settings

Names are a sketch, to be settled during implementation:

```yaml
structures:
  elastic-read-pool:
    max-connections: 30
  elastic-write-pool:
    max-connections: 4          # per pod; the cluster total is this x the number of pods
    max-wait: 30s               # connection request timeout; keep below the clients' request timeout
  elastic-bulk-chunk-size: 500
```

The limits are per pod. If the number of pods changes (for example, through autoscaling), the
total load on Elasticsearch changes with it.

**Where to start sizing:** keep the total number of concurrent bulk requests across all pods near
the size of Elasticsearch's write thread pool, which is one thread per CPU core on each node. Then
adjust from the metrics.

### Metrics to collect first

Measure the current behaviour before changing anything.

**Structures, per pod:**

- Bulk documents written per second, by structure and by operation (`bulkSave` or `bulkUpdate`)
- How many documents bulk calls carry (the spread, not just the average)
- How long bulk calls take
- Read latency (median and 95th percentile) for search, named queries and find by id
- How often `syncIndex` is called, and by which clients
- Once steps 1-2 are in: connections in use per pool, time spent waiting for a write connection,
  and how often that wait times out

**Elasticsearch:**

- `GET _cat/thread_pool/write,search?v`: active, queued and rejected requests for writes and
  searches
- `GET _nodes/stats/indices/indexing,search,refresh,merges`: refresh and merge counts and time, and
  search time per query
- `GET _nodes/stats/indexing_pressure`

### Verifying

On KinD with the load generator (see [LOAD_TESTING.md](../../LOAD_TESTING.md)):

1. Keep reads running at a steady rate (`sustainedSearch` and `sustainedFindAll`), and push bulk
   writes hard with `sustainedBulkSave` at high concurrency and large bulk sizes.
2. Compare 95th percentile read latency with no write limit and with a small write pool plus
   chunking.
3. Check that writes slow down but don't fail, and that no waiting write times out at the chosen
   `max-wait`.
4. Watch the Elasticsearch write thread pool for rejected requests in both runs.

### Open questions

- When a client gets an error or a 429, does it retry, back off, or drop the data? This decides how
  long writes may wait before Structures rejects them.
- What is the request timeout of the continuum RPC client? `max-wait` has to stay below it.
- Which clients need to search their own writes, and do they call `syncIndex` to do it?
- How many CPU cores does each production Elasticsearch node have? That sets the size of its write
  thread pool, which is where pool sizing starts.
