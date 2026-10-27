package org.kinotic.structures.internal.cache;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.lang.IgniteFuture;
import org.kinotic.continuum.api.config.ContinuumProperties;
import org.kinotic.structures.api.config.ClusterEvictionProperties;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.internal.cache.events.CacheEvictionSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Evicts the caches for a structure or named query that changed on this node, here and on every other server
 * node in the cluster.
 *
 * The services that change structures and named queries call the evict methods here and compose on the result, so a
 * change is only reported once the whole cluster has dropped its stale caches. This is the only way to evict for a
 * change: only this service can create the local {@link CacheEvictionEvent}s that do it. Nothing here
 * blocks: those calls run on Elasticsearch client and Vert.x threads, so the broadcast, its timeout and the delay
 * between retries are all asynchronous, and run on a Vert.x context rather than on the threads that complete them.
 * The Ignite calls that can block, starting a broadcast and cancelling one that timed out, run on worker threads.
 *
 * Includes OpenTelemetry metrics for monitoring cache eviction health and
 * performance.
 *
 */
@Slf4j
@Component
public class ClusterCacheEvictionService {

    private final StructuresProperties structuresProperties;
    private final ContinuumProperties continuumProperties;
    private final Ignite ignite;
    private final Vertx vertx;
    private final ApplicationEventPublisher eventPublisher;

    private final LongCounter evictionRequestCounter;
    private final LongCounter clusterResultCounter;
    private final LongHistogram clusterDurationHistogram;
    private final LongCounter retryCounter;

    public ClusterCacheEvictionService(ContinuumProperties continuumProperties,
            StructuresProperties structuresProperties,
            Ignite ignite,
            Vertx vertx,
            ApplicationEventPublisher eventPublisher,
            OpenTelemetry openTelemetry) {
        this.continuumProperties = continuumProperties;
        this.structuresProperties = structuresProperties;
        this.ignite = ignite;
        this.vertx = vertx;
        this.eventPublisher = eventPublisher;

        Meter meter = openTelemetry.getMeter("structures.cache.eviction");

        evictionRequestCounter = meter.counterBuilder("cache.eviction.requests")
                .setDescription("Total cache eviction requests received")
                .setUnit("requests")
                .build();

        clusterResultCounter = meter.counterBuilder("cache.eviction.cluster.results")
                .setDescription("Cluster cache eviction results (success or failure)")
                .setUnit("results")
                .build();

        clusterDurationHistogram = meter.histogramBuilder("cache.eviction.cluster.duration")
                .setDescription("Cluster cache eviction duration")
                .setUnit("ms")
                .ofLongs()
                .build();

        retryCounter = meter.counterBuilder("cache.eviction.cluster.retries")
                .setDescription("Number of retry attempts for cluster cache evictions")
                .setUnit("retries")
                .build();
    }

    /**
     * Evicts the caches for a structure that was saved, published or unpublished on this node.
     *
     * @return as {@link #evictCaches(CacheEvictionEvent)} describes
     */
    public CompletableFuture<Void> evictModifiedStructure(String applicationId, String structureId) {
        return evictCaches(CacheEvictionEvent.localModifiedStructure(applicationId, structureId));
    }

    /**
     * Evicts the caches for a structure that was deleted on this node.
     *
     * @return as {@link #evictCaches(CacheEvictionEvent)} describes
     */
    public CompletableFuture<Void> evictDeletedStructure(String applicationId, String structureId) {
        return evictCaches(CacheEvictionEvent.localDeletedStructure(applicationId, structureId));
    }

    /**
     * Evicts the caches for a named query that was saved on this node.
     *
     * @return as {@link #evictCaches(CacheEvictionEvent)} describes
     */
    public CompletableFuture<Void> evictModifiedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return evictCaches(CacheEvictionEvent.localModifiedNamedQuery(applicationId, structureId, namedQueryId));
    }

    /**
     * Evicts the caches for a named query that was deleted on this node.
     *
     * @return as {@link #evictCaches(CacheEvictionEvent)} describes
     */
    public CompletableFuture<Void> evictDeletedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return evictCaches(CacheEvictionEvent.localDeletedNamedQuery(applicationId, structureId, namedQueryId));
    }

    /**
     * Evicts the caches for a change made on this node. This node's listeners receive the event before this
     * returns, then the eviction is broadcast to every server node, retrying as configured.
     *
     * @param event a {@link CacheEvictionSource#LOCAL_MESSAGE} event for the structure or named query that changed
     * @return completes once every server node has evicted, or once the retries are used up, on a Vert.x context.
     *         An eviction failure never fails the result: it is logged and counted, but must not fail the change that
     *         caused it.
     */
    private CompletableFuture<Void> evictCaches(CacheEvictionEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // The other nodes still need to evict, whatever went wrong here
            log.error("Failed to evict local caches (source: {})", event.getSource(), e);
        }

        if (continuumProperties.isDisableClustering()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> clusterEviction;
        try {
            if (structuresProperties.getClusterEviction().getMaxCacheSyncRetryAttempts() < 1) {
                log.warn("Cluster cache eviction is turned off (max-cache-sync-retry-attempts < 1), so other nodes keep their caches for: {}:{}:{}",
                         event.getApplicationId(), event.getStructureId(), event.getNamedQueryId());
                return CompletableFuture.completedFuture(null);
            }

            // The caller's own context when it runs on Vert.x, otherwise an event loop context of its own
            Context context = vertx.getOrCreateContext();

            clusterEviction = evictCachesClusterWideWithRetry(context, event, System.currentTimeMillis());
        } catch (Exception e) {
            // The change this eviction is for has already been made, so it must not fail because of the eviction
            clusterEviction = CompletableFuture.failedFuture(e);
        }

        return clusterEviction
                .exceptionally(e -> {
                    log.error("Failed to handle cache eviction (source: {})", event.getSource(), e);
                    return null;
                });
    }

    /**
     * Evicts caches cluster-wide with retry logic to ensure all nodes are
     * processed.
     * Refreshes cluster topology on each retry attempt to handle node failures
     * gracefully.
     * Tracks metrics for monitoring and alerting.
     *
     * @param context   the Vert.x context the broadcast's completion, timeout and retries run on
     * @param event     the cache eviction event containing eviction details
     * @param timestamp generated once for all retry attempts to ensure consistent versioning
     */
    private CompletableFuture<Void> evictCachesClusterWideWithRetry(Context context, CacheEvictionEvent event, long timestamp) {
        long startTime = System.currentTimeMillis();

        // Track eviction request
        Attributes requestAttributes = Attributes.builder()
                .put("eviction.type", event.getEvictionSourceType().name())
                .put("eviction.operation", event.getEvictionOperation().name())
                .put("eviction.source", event.getEvictionSource().name())
                .build();
        evictionRequestCounter.add(1, requestAttributes);

        log.trace("Starting {} cache eviction for: {}:{}:{} with timestamp: {}",
                event.getEvictionSourceType(), event.getApplicationId(),
                event.getStructureId(), event.getNamedQueryId(), timestamp);

        return attemptClusterEviction(context, event, timestamp, 1, null).thenAccept(outcome -> {
            if (outcome.result() == AttemptResult.NO_SERVERS) {
                return;
            }
            boolean success = outcome.result() == AttemptResult.SUCCESS;

            // Track retry attempts if any occurred
            if (outcome.attempts() > 1) {
                Attributes retryAttributes = Attributes.builder()
                        .put("eviction.type", event.getEvictionSourceType().name())
                        .put("eviction.operation", event.getEvictionOperation().name())
                        .build();
                retryCounter.add(outcome.attempts() - 1, retryAttributes);
            }

            // Track duration and result
            long duration = System.currentTimeMillis() - startTime;
            Attributes resultAttributes = Attributes.builder()
                    .put("eviction.type", event.getEvictionSourceType().name())
                    .put("eviction.operation", event.getEvictionOperation().name())
                    .put("result", success ? "success" : "failure")
                    .put("attempts", String.valueOf(outcome.attempts()))
                    .build();

            clusterDurationHistogram.record(duration, resultAttributes);
            clusterResultCounter.add(1, resultAttributes);

            if (!success) {
                // If we get here, all retry attempts failed
                log.error("Failed to complete {} cache eviction on cluster for: {}:{}:{} (timestamp: {}) after {} attempts",
                        event.getEvictionSourceType(), event.getApplicationId(),
                        event.getStructureId(), event.getNamedQueryId(),
                        timestamp, outcome.attempts(), outcome.lastException());
            }
        });
    }

    /**
     * Broadcasts the eviction once, and schedules the next attempt if it fails or times out.
     * The cluster group is refreshed on each attempt to handle topology changes
     * (e.g., nodes going down or new nodes joining).
     *
     * @param previousFailure why the previous attempt failed, or null on the first attempt
     */
    private CompletableFuture<AttemptOutcome> attemptClusterEviction(Context context,
                                                                     CacheEvictionEvent event,
                                                                     long timestamp,
                                                                     int attempt,
                                                                     Throwable previousFailure) {
        ClusterEvictionProperties clusterEviction = structuresProperties.getClusterEviction();
        int maxAttempts = clusterEviction.getMaxCacheSyncRetryAttempts();

        // Ignite can block while it looks up the servers and sends them the task, so that runs on a worker thread,
        // unordered so one stuck broadcast does not hold up the others on this context
        return context.executeBlocking(() -> startBroadcast(context, event, timestamp, attempt, maxAttempts), false)
                      .toCompletionStage()
                      .toCompletableFuture()
                      .thenCompose(broadcast -> {
                          if (broadcast.result() == null) {
                              // No point retrying if no servers available, but an earlier attempt that failed still counts
                              return CompletableFuture.completedFuture(previousFailure == null
                                                                               ? new AttemptOutcome(AttemptResult.NO_SERVERS, attempt, null)
                                                                               : new AttemptOutcome(AttemptResult.FAILURE, attempt - 1, previousFailure));
                          }
                          return broadcast.result().thenCompose(failure -> completeAttempt(context, event, timestamp, attempt,
                                                                                           broadcast.serverCount(), failure));
                      });
    }

    /**
     * Looks up the server nodes and broadcasts the eviction to them. This can block, so it must not run on an event loop.
     *
     * @return the number of server nodes, and the broadcast's outcome as {@link #awaitBroadcast} reports it, which is
     *         null when there are no server nodes
     */
    private StartedBroadcast startBroadcast(Context context,
                                            CacheEvictionEvent event,
                                            long timestamp,
                                            int attempt,
                                            int maxAttempts) {
        int serverCount = 0;
        try {
            ClusterGroup servers = ignite.cluster().forServers();
            serverCount = servers.nodes().size();

            if (serverCount == 0) {
                log.warn("No server nodes available for cluster cache eviction (attempt {}/{})",
                        attempt, maxAttempts);
                return new StartedBroadcast(0, null);
            }

            // Log cluster state for debugging
            log.trace("Attempt {}/{}: Broadcasting to {} server nodes for {}:{}:{}",
                    attempt, maxAttempts, serverCount, event.getApplicationId(),
                    event.getStructureId(), event.getNamedQueryId());

            ClusterCacheEvictionTask task = new ClusterCacheEvictionTask(
                    event.getEvictionSourceType(),
                    event.getEvictionOperation(),
                    event.getApplicationId(),
                    event.getStructureId(),
                    event.getNamedQueryId(),
                    timestamp);

            // Broadcast to all current server nodes using the same timestamp for
            // idempotency
            return new StartedBroadcast(serverCount,
                                        awaitBroadcast(context,
                                                       ignite.compute(servers).broadcastAsync(task),
                                                       structuresProperties.getClusterEviction().getCacheSyncTimeoutMs()));
        } catch (Exception e) {
            // Counted as a failed attempt, so it is retried like a broadcast that failed
            return new StartedBroadcast(serverCount, CompletableFuture.completedFuture(e));
        }
    }

    /**
     * Records the outcome of a broadcast, and schedules the next attempt if it failed and attempts remain.
     *
     * @param failure why the broadcast failed or timed out, or null if every server node evicted
     */
    private CompletableFuture<AttemptOutcome> completeAttempt(Context context,
                                                              CacheEvictionEvent event,
                                                              long timestamp,
                                                              int attempt,
                                                              int nodes,
                                                              Throwable failure) {
        ClusterEvictionProperties clusterEviction = structuresProperties.getClusterEviction();
        int maxAttempts = clusterEviction.getMaxCacheSyncRetryAttempts();

        if (failure == null) {
            log.debug(
                    "{} cache eviction successfully completed on all {} cluster nodes for: {}:{}:{} (timestamp: {}, attempt {}/{})",
                    event.getEvictionSourceType(), nodes,
                    event.getApplicationId(), event.getStructureId(), event.getNamedQueryId(),
                    timestamp, attempt, maxAttempts);
            return CompletableFuture.completedFuture(new AttemptOutcome(AttemptResult.SUCCESS, attempt, null));
        }

        log.warn("{} cache eviction failed on cluster for: {}:{}:{} (timestamp: {}, attempt {}/{}): {}",
                event.getEvictionSourceType(), event.getApplicationId(),
                event.getStructureId(), event.getNamedQueryId(),
                timestamp, attempt, maxAttempts, failure.toString());

        if (attempt >= maxAttempts) {
            return CompletableFuture.completedFuture(new AttemptOutcome(AttemptResult.FAILURE, attempt, failure));
        }

        log.debug("Waiting {}ms before retry attempt {}", clusterEviction.getCacheSyncRetryDelayMs(), attempt + 1);
        return delay(context, clusterEviction.getCacheSyncRetryDelayMs())
                .thenCompose(ignored -> attemptClusterEviction(context, event, timestamp, attempt + 1, failure));
    }

    /**
     * Waits for a broadcast without blocking, cancelling it if it outlives the timeout so a node that never answers
     * does not collect a queued job for every attempt.
     * Continuum runs Ignite listeners on the thread that completes the future, so both outcomes hop to the context,
     * which also serialises the race between them.
     *
     * @return completes on the context with null on success, or with why the broadcast failed or timed out
     */
    private CompletableFuture<Throwable> awaitBroadcast(Context context, IgniteFuture<Void> igniteFuture, long timeoutMs) {
        CompletableFuture<Throwable> ret = new CompletableFuture<>();
        long timerId = vertx.setTimer(Math.max(1, timeoutMs), id -> context.runOnContext(v -> {
            if (ret.complete(new TimeoutException("Cluster cache eviction did not complete within " + timeoutMs + "ms"))) {
                // Cancelling sends a request to every node the task went to, the one that never answered included,
                // and that can block, so it runs on a worker thread
                context.executeBlocking(igniteFuture::cancel, false)
                       .onFailure(e -> log.debug("Could not cancel the timed out cluster cache eviction broadcast", e));
            }
        }));
        igniteFuture.listen(done -> context.runOnContext(v -> {
            vertx.cancelTimer(timerId);
            try {
                done.get();
                ret.complete(null);
            } catch (Exception e) {
                ret.complete(e);
            }
        }));
        return ret;
    }

    private CompletableFuture<Void> delay(Context context, long delayMs) {
        CompletableFuture<Void> ret = new CompletableFuture<>();
        vertx.setTimer(Math.max(1, delayMs), id -> context.runOnContext(v -> ret.complete(null)));
        return ret;
    }

    private enum AttemptResult {
        SUCCESS,
        FAILURE,
        NO_SERVERS
    }

    private record AttemptOutcome(AttemptResult result, int attempts, Throwable lastException) {
    }

    /**
     * @param serverCount the server nodes the broadcast went to
     * @param result      the broadcast's outcome, null when there were no server nodes and so no broadcast
     */
    private record StartedBroadcast(int serverCount, CompletableFuture<Throwable> result) {
    }

}
