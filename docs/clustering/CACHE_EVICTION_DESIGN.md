# Cache Eviction Design and Implementation

## Overview

This document describes how Structures evicts its local caches when a structure or named query changes, on the
node that made the change and on every other server node in the cluster.

## Problem Statement

The Structures platform uses multiple local caches (Caffeine) for performance optimization:
- **Entity Service Cache**: Caches entity services by structure ID
- **GraphQL Handler Cache**: Caches GraphQL handlers by application ID
- **GQL Operation Definition Cache**: Caches the GraphQL operations built from named queries, by structure ID
- **Named Query Executor Cache**: Caches the executors for named queries

When a structure or named query is saved, published or deleted, every node must drop the entries built from the old
version, or it keeps serving stale entities, queries and GraphQL schemas.

## Key Challenges

1. **Nothing may block**: changes complete on Elasticsearch client and Vert.x threads, so the eviction cannot wait
   on the cluster by blocking a thread
2. **The caller must know when the cluster has evicted**: a change is only reported once every server node has
   dropped its stale caches, so a client that reads straight after a write sees the new version on any node
3. **Nodes come and go**: a node can leave or hang in the middle of a broadcast
4. **An eviction failure must not fail the change**: the change has already been written by then

## Current Implementation

### Architecture

```
Structure / named query change (DefaultStructureService, DefaultNamedQueriesService)
     │  write to Elasticsearch, then compose on:
     ▼
ClusterCacheEvictionService.evictCaches(CacheEvictionEvent.local...(...))
     │
     ├─ 1. publishEvent(event) ─────────────► @EventListener handleCacheEviction on this node
     │                                        (EntitiesService, GqlOperationDefinitionService,
     │                                         DelegatingGqlHandler, NamedQueriesService)
     │
     └─ 2. broadcast ClusterCacheEvictionTask to every server node (Ignite compute grid)
              │  timeout, retry with a refreshed topology, metrics
              ▼
         ClusterCacheEvictionTask.run() on each node
              └─ publishEvent(CacheEvictionEvent.cluster...(...)) ─► the same @EventListeners there
     │
     ▼
returned CompletableFuture completes once every server node has evicted, or the retries are used up
```

Each cache owner listens for `CacheEvictionEvent` with `@EventListener` and drops its own entries, whether the event
came from this node or from the cluster. No listener broadcasts anything: only `ClusterCacheEvictionService` does, and
only for the `LOCAL_MESSAGE` events passed to `evictCaches`. **A new change that affects these caches must call
`evictCaches` and compose on the result. Publishing a `CacheEvictionEvent` yourself only evicts on this node.**

### Event Keys

| Field | Value |
|-------|-------|
| `applicationId` | the structure's application id |
| `structureId` | the structure's id, `StructuresUtil.structureNameToId(applicationId, name)`, which is lower case. A `NamedQueriesDefinition` holds the structure's **name** in `structure`, so it must be converted, since the GraphQL operation cache is keyed by structure id |
| `namedQueryId` | the `NamedQueriesDefinition` id, for named query events |

### ClusterCacheEvictionService

```java
public CompletableFuture<Void> evictCaches(CacheEvictionEvent event)
```

1. Publishes the event locally, so this node's listeners have evicted before `evictCaches` returns. A listener that
   throws is logged, and the broadcast still goes out.
2. Unless clustering is disabled, or `max-cache-sync-retry-attempts` is less than 1, broadcasts a
   `ClusterCacheEvictionTask` to `ignite.cluster().forServers()` and returns a future for the outcome.
3. The future never fails because of the eviction: a failure is logged and counted, but the change it is for has
   already been made.

Threading, since nothing here may block:

- Looking up the servers and starting the broadcast (`broadcastAsync`), and cancelling a broadcast that timed out,
  can block inside Ignite, so they run on Vert.x worker threads (`Context.executeBlocking`, unordered).
- The timeout and the delay between retries are Vert.x timers.
- Every outcome hops to one Vert.x context: the caller's own when it runs on Vert.x, otherwise an event loop context
  of its own. Callers therefore continue on an event loop, never on the Ignite thread that completed the broadcast.
  Do not block on the returned future from an event loop.

### ClusterCacheEvictionTask

An `IgniteRunnable` that carries only ids, so it serialises safely. On each node it publishes the matching
`CacheEvictionEvent.cluster...` event to that node's listeners. Its `ApplicationEventPublisher` and
`ProcessedEvictionsCache` are injected by Ignite with `@SpringResource`. The timestamp is the same for every attempt
of one eviction, so a node that already processed it, for example when a retry reaches it again, skips it.

### Retry Configuration

`structures.cluster-eviction.*` (`ClusterEvictionProperties`):

| Property | Default | Meaning |
|----------|---------|---------|
| `max-cache-sync-retry-attempts` | 3 | Attempts, the first broadcast included. Less than 1 turns the broadcast off |
| `cache-sync-retry-delay-ms` | 1000 | Delay between attempts |
| `cache-sync-timeout-ms` | 30000 | How long one broadcast may take before it is cancelled and retried |

**Retry Logic**:
- Each attempt refreshes the server topology, so a node that left is not broadcast to again
- A broadcast that times out is cancelled, so a node that never answers does not collect a queued job per attempt
- If no server nodes remain, retrying stops; an earlier failed attempt is still counted as a failure
- The same timestamp is used for every attempt, so the retries are idempotent

### Named Query Writes

Saving and deleting named queries both refresh the Elasticsearch index. The GQL operation cache reloads a definition
with a search, which only sees a write after a refresh, so without one a node that reloads straight after the eviction
would cache the old definition again.

## Usage Examples

```java
// After the change is written
return structureDAO.save(structure)
                   .thenCompose(saved -> clusterCacheEvictionService
                           .evictCaches(CacheEvictionEvent.localModifiedStructure(saved.getApplicationId(), saved.getId()))
                           .thenApply(v -> saved));
```

## OpenTelemetry Metrics

The cache eviction system exposes the following metrics for monitoring and alerting:

### Available Metrics

| Metric Name | Type | Description | Attributes |
|-------------|------|-------------|------------|
| `cache.eviction.requests` | Counter | Total cache eviction requests received | `eviction.type`, `eviction.operation`, `eviction.source` |
| `cache.eviction.cluster.results` | Counter | Cluster eviction results (success/failure) | `eviction.type`, `eviction.operation`, `result`, `attempts` |
| `cache.eviction.cluster.duration` | Histogram | Cluster eviction duration in milliseconds | `eviction.type`, `eviction.operation`, `result`, `attempts` |
| `cache.eviction.cluster.retries` | Counter | Number of retry attempts for cluster evictions | `eviction.type`, `eviction.operation` |

### Metric Attributes

- **eviction.type**: `STRUCTURE` or `NAMED_QUERY`
- **eviction.operation**: `MODIFY` or `DELETE`
- **eviction.source**: `LOCAL_MESSAGE` or `CLUSTER_MESSAGE`
- **result**: `success` or `failure`
- **attempts**: Number of attempts (e.g., "1", "2", "3")

### Example Prometheus Queries

```promql
# Cache eviction request rate by type
rate(cache_eviction_requests_total[5m])

# Cluster eviction success rate
rate(cache_eviction_cluster_results_total{result="success"}[5m]) 
  / 
rate(cache_eviction_cluster_results_total[5m])

# Average cluster eviction duration
rate(cache_eviction_cluster_duration_sum[5m]) 
  / 
rate(cache_eviction_cluster_duration_count[5m])

# Retry rate (should be low)
rate(cache_eviction_cluster_retries_total[5m])

# P95 cluster eviction latency
histogram_quantile(0.95, 
  rate(cache_eviction_cluster_duration_bucket[5m]))
```

### Recommended Alerts

```yaml
# Alert if cluster eviction failure rate > 5%
- alert: CacheEvictionHighFailureRate
  expr: |
    (
      rate(cache_eviction_cluster_results_total{result="failure"}[5m])
      /
      rate(cache_eviction_cluster_results_total[5m])
    ) > 0.05
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High cache eviction failure rate"

# Alert if cluster eviction latency > 5s (P95)
- alert: CacheEvictionSlowPerformance
  expr: |
    histogram_quantile(0.95,
      rate(cache_eviction_cluster_duration_bucket[5m])
    ) > 5000
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Slow cache eviction performance"

# Alert if retry rate is high (> 10% of requests)
- alert: CacheEvictionHighRetryRate
  expr: |
    (
      rate(cache_eviction_cluster_retries_total[5m])
      /
      rate(cache_eviction_requests_total[5m])
    ) > 0.10
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High cache eviction retry rate"
```

## Cluster Testing

The cache eviction system includes comprehensive cluster testing capabilities to verify that cache eviction propagates correctly across all nodes.

### Testing Approaches

**1. Unit and integration tests** (`structures-test`)

- `ClusterCacheEvictionServiceTest` - the broadcast, timeout, cancel and retry logic against a mocked Ignite, and
  that no Ignite call runs on an event loop
- `NamedQueryCacheEvictionTests` - saving and deleting named queries evicts the GraphQL operations built from them

```bash
./gradlew :structures-test:test --tests '*CacheEviction*'
```

**2. Kubernetes e2e** (`structures-js/structures-e2e/test/k8s/k8s-cache-eviction.test.ts`)

Changes a structure through one pod and checks every other pod serves the new version. This is the real check of
cluster propagation.

**3. Manual Docker Compose Testing** (Pre-release validation)

See `docs/clustering/CLUSTER_TESTING.md` for detailed testing procedures.

### Test Scenarios

**Automated Tests**:
- `testClusterFormation()` - Verify all nodes start and join cluster
- `testCacheEvictionPropagatesAcrossCluster()` - Modify on node1, verify eviction on node2/node3
- `testNodeFailureHandling()` - Kill node during eviction, verify retry succeeds
- `testDeletionPropagation()` - Delete structure/named query, verify cluster-wide eviction
- `testMetricsRecorded()` - Verify OpenTelemetry metrics are emitted correctly

**Manual Test Procedures**:
1. Structure modification propagation
2. Structure deletion propagation  
3. Named query modification propagation
4. Node failure during eviction
5. Metrics verification in Grafana
6. Log verification across nodes

### Cluster Configuration

**Docker/Testcontainers** (Static IP Discovery):
```yaml
continuum:
  disableClustering: false
  cluster:
    discoveryType: SHAREDFS
    localAddresses: "node1:47500,node2:47500,node3:47500"
    discoveryPort: 47500
    communicationPort: 47100
```

**Kubernetes** (Kubernetes Discovery):
```yaml
continuum:
  disableClustering: false
  cluster:
    discoveryType: KUBERNETES
    kubernetesNamespace: structures
    kubernetesServiceName: structures-ignite
    discoveryPort: 47500
    communicationPort: 47100
```

### Performance Expectations

**Normal Operation**:
- Cache eviction completes in < 1 second
- Retry rate < 1%
- Success rate > 99%
- P95 latency < 2 seconds

**During Node Failure**:
- First attempt fails to unreachable node
- Retry succeeds within 1-2 seconds (refreshed topology excludes dead node)
- Total duration < 5 seconds
- Cluster continues operating with remaining nodes

### Troubleshooting

**Nodes not joining cluster**:
- Check Docker network connectivity
- Verify discovery addresses in configuration (`continuum.cluster.localAddresses`)
- Increase `continuum.cluster.joinTimeoutMs` if nodes are slow
- Check logs for "Topology snapshot" messages

**Cache eviction not propagating**:
- Verify cluster topology shows all nodes
- Check network connectivity between containers
- Look for "cache eviction failed" in logs
- Increase retry attempts or timeout

**High retry rate**:
- Check network latency between nodes
- Increase cluster sync timeout
- Verify node health and resource usage
- Look for node restarts in logs

See `docker-compose/CLUSTER_TESTING.md` for complete troubleshooting guide.

## Kubernetes Production Deployment

### Discovery Configuration

For production Kubernetes deployments, use Kubernetes discovery instead of static IP:

**Application Configuration** (via environment variables or ConfigMap):
```yaml
continuum:
  disableClustering: false
  cluster:
    discoveryType: KUBERNETES
    kubernetesNamespace: structures
    kubernetesServiceName: structures-ignite
    discoveryPort: 47500
    communicationPort: 47100

# Or as environment variables:
# CONTINUUM_CLUSTER_DISCOVERY_TYPE: KUBERNETES
# CONTINUUM_CLUSTER_KUBERNETES_NAMESPACE: structures
# CONTINUUM_CLUSTER_KUBERNETES_SERVICE_NAME: structures-ignite
```

**Headless Service** (for Ignite discovery):
```yaml
apiVersion: v1
kind: Service
metadata:
  name: structures-ignite
spec:
  clusterIP: None  # Headless service
  selector:
    app: structures
  ports:
    - name: discovery
      port: 47500
    - name: communication
      port: 47100
```

**StatefulSet** (for stable network identities):
```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: structures
spec:
  serviceName: structures-ignite
  replicas: 3  # Recommended minimum for fault tolerance
  selector:
    matchLabels:
      app: structures
  template:
    spec:
      containers:
      - name: structures
        env:
        - name: CONTINUUM_CLUSTER_DISCOVERY_TYPE
          value: "KUBERNETES"
        - name: CONTINUUM_CLUSTER_KUBERNETES_NAMESPACE
          value: "structures"
        - name: CONTINUUM_CLUSTER_KUBERNETES_SERVICE_NAME
          value: "structures-ignite"
```

### Production Recommendations

**Cluster Sizing**:
- Minimum: 3 nodes (provides fault tolerance)
- Recommended: 3-5 nodes (balances redundancy and complexity)
- Large deployments: 5-7 nodes (higher availability)

**Resource Requirements** (per node):
- CPU: 2-4 cores
- Memory: 4-8 GB
- Disk: SSD for best Elasticsearch performance

**Monitoring**:
- Set up alerts for cache eviction failure rate > 5%
- Monitor P95 latency < 5 seconds
- Alert on retry rate > 10%
- Track cluster size changes

**High Availability**:
- Deploy nodes across availability zones
- Use pod anti-affinity rules
- Configure proper liveness/readiness probes
- Set up PodDisruptionBudgets

---
**Document Version**: 6.0  
**Last Updated**: January 2026  
**Status**: ✅ COMPLETE - Local and cluster-wide cache eviction with OpenTelemetry metrics and cluster testing
