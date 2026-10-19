package org.kinotic.structures.internal.cache;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.lang.IgniteFuture;
import org.kinotic.continuum.api.config.ContinuumProperties;
import org.kinotic.structures.api.config.ClusterEvictionProperties;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.internal.cache.events.CacheEvictionEvent;
import org.kinotic.structures.internal.cache.events.CacheEvictionSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Evicts the caches for a structure or named query that changed on this node, here and on every other server
 * node in the cluster.
 *
 * The services that change structures and named queries call {@link #evictCaches(CacheEvictionEvent)} and compose
 * on the result, so a change is only reported once the whole cluster has dropped its stale caches. Nothing here
 * blocks: those calls run on Elasticsearch client and Vert.x threads, so the broadcast, its timeout and the delay
 * between retries are all asynchronous.
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
    private final ApplicationEventPublisher eventPublisher;

    private final LongCounter evictionRequestCounter;
    private final LongCounter clusterResultCounter;
    private final LongHistogram clusterDurationHistogram;
    private final LongCounter retryCounter;

    public ClusterCacheEvictionService(ContinuumProperties continuumProperties,
            StructuresProperties structuresProperties,
            Ignite ignite,
            ApplicationEventPublisher eventPublisher,
            OpenTelemetry openTelemetry) {
        this.continuumProperties = continuumProperties;
        this.structuresProperties = structuresProperties;
        this.ignite = ignite;
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
     * Evicts the caches for a change made on this node. This node's listeners receive the event before this
     * returns, then the eviction is broadcast to every server node, retrying as configured.
     *
     * @param event a {@link CacheEvictionSource#LOCAL_MESSAGE} event for the structure or named query that changed
     * @return completes once every server node has evicted, or once the retries are used up. It never completes
     *         exceptionally: a failed eviction is logged and counted, but must not fail the change that caused it.
     */
    public CompletableFuture<Void> evictCaches(CacheEvictionEvent event) {
        if (event.getEvictionSource() != CacheEvictionSource.LOCAL_MESSAGE) {
            throw new IllegalArgumentException("Only local cache eviction events can be broadcast to the cluster");
        }

        eventPublisher.publishEvent(event);

        if (continuumProperties.isDisableClustering()) {
            return CompletableFuture.completedFuture(null);
        }

        return evictCachesClusterWideWithRetry(event, System.currentTimeMillis())
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
     * @param event     the cache eviction event containing eviction details
     * @param timestamp generated once for all retry attempts to ensure consistent versioning
     */
    private CompletableFuture<Void> evictCachesClusterWideWithRetry(CacheEvictionEvent event, long timestamp) {
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

        return attemptClusterEviction(event, timestamp, 1).thenAccept(outcome -> {
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
     */
    private CompletableFuture<AttemptOutcome> attemptClusterEviction(CacheEvictionEvent event, long timestamp, int attempt) {
        ClusterEvictionProperties clusterEviction = structuresProperties.getClusterEviction();
        int maxAttempts = clusterEviction.getMaxCacheSyncRetryAttempts();

        CompletableFuture<Void> broadcast;
        int serverCount = 0;
        try {
            ClusterGroup servers = ignite.cluster().forServers();
            serverCount = servers.nodes().size();

            if (serverCount == 0) {
                log.warn("No server nodes available for cluster cache eviction (attempt {}/{})",
                        attempt, maxAttempts);
                return CompletableFuture.completedFuture(new AttemptOutcome(AttemptResult.NO_SERVERS, attempt, null));
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
            broadcast = toCompletableFuture(ignite.compute(servers).broadcastAsync(task));
        } catch (Exception e) {
            broadcast = CompletableFuture.failedFuture(e);
        }

        int nodes = serverCount;
        return broadcast
                .orTimeout(clusterEviction.getCacheSyncTimeoutMs(), TimeUnit.MILLISECONDS)
                // Continuum runs Ignite listeners on the thread that completes the future, and orTimeout completes
                // on the JDK's single delay thread, so move off both before running anything else
                .handleAsync((ignored, e) -> e)
                .thenCompose(e -> {
                    if (e == null) {
                        log.debug(
                                "{} cache eviction successfully completed on all {} cluster nodes for: {}:{}:{} (timestamp: {}, attempt {}/{})",
                                event.getEvictionSourceType(), nodes,
                                event.getApplicationId(), event.getStructureId(), event.getNamedQueryId(),
                                timestamp, attempt, maxAttempts);
                        return CompletableFuture.completedFuture(new AttemptOutcome(AttemptResult.SUCCESS, attempt, null));
                    }

                    Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                    log.warn("{} cache eviction failed on cluster for: {}:{}:{} (timestamp: {}, attempt {}/{}): {}",
                            event.getEvictionSourceType(), event.getApplicationId(),
                            event.getStructureId(), event.getNamedQueryId(),
                            timestamp, attempt, maxAttempts, cause.toString());

                    if (attempt >= maxAttempts) {
                        return CompletableFuture.completedFuture(new AttemptOutcome(AttemptResult.FAILURE, attempt, cause));
                    }

                    log.debug("Waiting {}ms before retry attempt {}", clusterEviction.getCacheSyncRetryDelayMs(), attempt + 1);
                    return CompletableFuture
                            .runAsync(() -> {}, CompletableFuture.delayedExecutor(clusterEviction.getCacheSyncRetryDelayMs(),
                                                                                    TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> attemptClusterEviction(event, timestamp, attempt + 1));
                });
    }

    private static CompletableFuture<Void> toCompletableFuture(IgniteFuture<Void> igniteFuture) {
        CompletableFuture<Void> ret = new CompletableFuture<>();
        igniteFuture.listen(done -> {
            try {
                done.get();
                ret.complete(null);
            } catch (Exception e) {
                ret.completeExceptionally(e);
            }
        });
        return ret;
    }

    private enum AttemptResult {
        SUCCESS,
        FAILURE,
        NO_SERVERS
    }

    private record AttemptOutcome(AttemptResult result, int attempts, Throwable lastException) {
    }

}
