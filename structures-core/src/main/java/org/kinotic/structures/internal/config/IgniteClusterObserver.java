package org.kinotic.structures.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgnitePredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes Ignite cluster membership from the structures side and diagnoses the
 * orphaned-node / split-brain failure modes, logging evidence of each:
 * <ul>
 *   <li>membership changes (join/left/failed) with topology context</li>
 *   <li>segmentation events (a segmented server node can never rejoin without a restart)</li>
 *   <li>stale vertx routing state after a node departs: with PARTITIONED 0-backup
 *   __vertx.* caches the departed node's nodeInfo entry can be destroyed with its
 *   partition before vertx-ignite's cleanup listener runs, the cleanup election then
 *   no-ops on every survivor, and stale __vertx.subs entries remain - the source of
 *   "Not a member of the cluster" event bus send failures
 *   (see {@link VertxClusterCacheConfiguration} for the fix)</li>
 *   <li>server topology staying below structures.cluster.observer.minimumClusterSize -
 *   the split-brain condition Ignite cannot detect by design (group splits keep a healthy
 *   ring on each side; a restart into a partition forms a fresh singleton topology)</li>
 * </ul>
 *
 * By default this component only observes and logs. Set
 * structures.cluster.observer.shutdownEnabled=true to also shut the process down (non-zero
 * exit, so the orchestrator starts a fresh instance) when this node is segmented or stays
 * below the minimum cluster size beyond the grace period. minimumClusterSize should be a
 * majority of the replica count (floor(n/2)+1). This is a diagnostic port of continuum 3.x's
 * IgniteOrphanedNodeGuard for use while structures is on continuum 2.6.x.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "continuum.disableClustering", havingValue = "false", matchIfMissing = true)
public class IgniteClusterObserver {

    private static final int EXIT_CODE = 1;
    private static final long TOPOLOGY_POLL_MS = 10_000L;
    private static final long STALE_ROUTE_CHECK_DELAY_MS = 15_000L;
    private static final int MAX_STALE_ADDRESSES_LOGGED = 10;

    private final Ignite ignite;
    private final ConfigurableApplicationContext applicationContext;

    @Value("${structures.cluster.observer.minimumClusterSize:1}")
    private int minimumClusterSize;

    @Value("${structures.cluster.observer.orphanGracePeriodMs:60000}")
    private long orphanGracePeriodMs;

    @Value("${structures.cluster.observer.startupQuorumTimeoutMs:300000}")
    private long startupQuorumTimeoutMs;

    /**
     * Master switch for taking action. False (default) = observe and log only, no outward
     * behavior change. True = shut the process down on segmentation or sustained loss of
     * the minimum cluster size, so the orchestrator can start a fresh instance.
     */
    @Value("${structures.cluster.observer.shutdownEnabled:false}")
    private boolean shutdownEnabled;

    @Value("${structures.cluster.observer.shutdownWatchdogTimeoutMs:30000}")
    private long shutdownWatchdogTimeoutMs;

    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private volatile boolean closed = false;
    private volatile boolean armed = false;
    private volatile boolean observeOnlyReported = false;
    private volatile long belowMinimumSinceNanos = -1;
    private volatile long startedAtNanos = -1;
    private volatile int lastObservedServerNodes = -1;
    private volatile boolean topologyUnavailableLogged = false;

    private IgnitePredicate<Event> membershipListener;
    private IgnitePredicate<Event> segmentationListener;
    private ScheduledExecutorService scheduler;

    public IgniteClusterObserver(Ignite ignite, ConfigurableApplicationContext applicationContext) {
        this.ignite = ignite;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "structures-cluster-observer");
            thread.setDaemon(true);
            return thread;
        });

        // Membership diagnostics: log joins/departures with topology context and, after a
        // departure, verify the vertx routing caches were actually cleaned up
        membershipListener = event -> {
            DiscoveryEvent discoveryEvent = (DiscoveryEvent) event;
            String eventNodeId = discoveryEvent.eventNode().id().toString();
            long topologyVersion = discoveryEvent.topologyVersion();
            int serverNodes = safeServerTopologySize();
            switch (event.type()) {
                case EventType.EVT_NODE_JOINED ->
                        log.info("Cluster node joined: {} (topologyVersion={}, serverNodes={})",
                                 eventNodeId, topologyVersion, serverNodes);
                case EventType.EVT_NODE_LEFT ->
                        log.info("Cluster node left: {} (topologyVersion={}, serverNodes={})",
                                 eventNodeId, topologyVersion, serverNodes);
                case EventType.EVT_NODE_FAILED ->
                        log.warn("Cluster node FAILED: {} (topologyVersion={}, serverNodes={})",
                                 eventNodeId, topologyVersion, serverNodes);
                default -> { /* not registered for others */ }
            }
            if (event.type() == EventType.EVT_NODE_LEFT || event.type() == EventType.EVT_NODE_FAILED) {
                // Check after vertx-ignite's cleanup listener has had ample time to run
                scheduler.schedule(() -> reportStaleRoutingState(eventNodeId),
                                   STALE_ROUTE_CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
            }
            return true;
        };
        ignite.events().localListen(membershipListener,
                                    EventType.EVT_NODE_JOINED,
                                    EventType.EVT_NODE_LEFT,
                                    EventType.EVT_NODE_FAILED);

        // Segmentation is always logged; a segmented server node can never rejoin without
        // a restart, so with shutdownEnabled we exit for a fresh instance
        segmentationListener = event -> {
            actOn("Ignite node was segmented from the cluster. "
                  + "Segmented server nodes cannot rejoin without a restart.");
            return false; // one shot
        };
        ignite.events().localListen(segmentationListener, EventType.EVT_NODE_SEGMENTED);

        log.info("Ignite cluster observer started: minimumClusterSize={}, orphanGracePeriodMs={}, "
                 + "startupQuorumTimeoutMs={}, shutdownEnabled={}",
                 minimumClusterSize, orphanGracePeriodMs, startupQuorumTimeoutMs, shutdownEnabled);

        if (minimumClusterSize > 1) {
            startedAtNanos = System.nanoTime();
            scheduler.scheduleWithFixedDelay(this::checkTopologySafely,
                                             0,
                                             TOPOLOGY_POLL_MS,
                                             TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        closed = true;
        // A normal context shutdown must never be escalated to a JVM halt by an in-flight poll
        shutdownInitiated.set(true);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (membershipListener != null) {
            try {
                ignite.events().stopLocalListen(membershipListener,
                                                EventType.EVT_NODE_JOINED,
                                                EventType.EVT_NODE_LEFT,
                                                EventType.EVT_NODE_FAILED);
            } catch (Exception e) {
                log.debug("Could not remove membership listener during shutdown", e);
            }
        }
        if (segmentationListener != null) {
            try {
                ignite.events().stopLocalListen(segmentationListener, EventType.EVT_NODE_SEGMENTED);
            } catch (Exception e) {
                log.debug("Could not remove segmentation listener during shutdown", e);
            }
        }
    }

    /**
     * Inspect the vertx-ignite routing caches after a node departed and log any stale
     * state left behind - the direct evidence of the cleanup-election failure that
     * produces "Not a member of the cluster" event bus send errors.
     */
    private void reportStaleRoutingState(String departedNodeId) {
        if (closed) {
            return;
        }
        try {
            IgniteCache<String, ?> nodeInfoCache = ignite.cache("__vertx.nodeInfo");
            boolean nodeInfoPresent = nodeInfoCache != null && nodeInfoCache.containsKey(departedNodeId);

            int staleSubs = 0;
            List<String> staleAddresses = new ArrayList<>();
            // Read the subs cache in binary form to avoid a compile-time dependency on
            // vertx-ignite's IgniteRegistrationInfo (field names match its writeBinary)
            IgniteCache<Object, Object> subsCache = ignite.cache("__vertx.subs");
            if (subsCache != null) {
                for (Cache.Entry<Object, Object> entry : subsCache.withKeepBinary()) {
                    if (entry.getKey() instanceof BinaryObject key
                            && departedNodeId.equals(key.field("nodeId"))) {
                        staleSubs++;
                        if (staleAddresses.size() < MAX_STALE_ADDRESSES_LOGGED) {
                            staleAddresses.add(key.field("address"));
                        }
                    }
                }
            }

            if (nodeInfoPresent || staleSubs > 0) {
                log.warn("Stale routing state remains for departed node {}: nodeInfoStillPresent={}, "
                         + "staleSubscriptionEntries={}, sampleAddresses={}. Event bus sends to these "
                         + "addresses can fail with 'Not a member of the cluster' until handlers re-register.",
                         departedNodeId, nodeInfoPresent, staleSubs, staleAddresses);
            } else {
                log.debug("Routing caches are clean after departure of node {}", departedNodeId);
            }
        } catch (Exception e) {
            log.debug("Could not inspect routing caches after departure of node {}", departedNodeId, e);
        }
    }

    /**
     * An exception escaping a scheduled task silently cancels all future executions - never
     * let that happen
     */
    private void checkTopologySafely() {
        try {
            checkTopology();
        } catch (Throwable t) {
            log.error("Unexpected error in cluster observer check", t);
        }
    }

    private void checkTopology() {
        if (closed || shutdownInitiated.get()) {
            return;
        }

        int serverNodes;
        try {
            serverNodes = ignite.cluster().forServers().nodes().size();
        } catch (Exception e) {
            lastObservedServerNodes = 0;
            if (!topologyUnavailableLogged) {
                topologyUnavailableLogged = true;
                log.warn("Ignite topology is not queryable", e);
            }
            return;
        }
        topologyUnavailableLogged = false;

        lastObservedServerNodes = serverNodes;

        if (log.isDebugEnabled()) {
            log.debug("Topology poll: serverNodes={}, minimum={}, armed={}, belowMinimumForMs={}",
                      serverNodes, minimumClusterSize, armed,
                      belowMinimumSinceNanos == -1 ? 0 : elapsedMs(belowMinimumSinceNanos));
        }

        if (serverNodes >= minimumClusterSize) {
            if (!armed) {
                armed = true;
                log.info("Cluster observer armed: server topology reached {} nodes", serverNodes);
            } else if (belowMinimumSinceNanos != -1) {
                log.info("Server topology recovered to {} nodes after {} ms below minimum",
                         serverNodes, elapsedMs(belowMinimumSinceNanos));
            }
            belowMinimumSinceNanos = -1;
            observeOnlyReported = false;
            return;
        }

        // Below the minimum. Normal startup never trips this (arm-after-join), but a node
        // that NEVER reaches the minimum likely started into an ongoing partition; Ignite
        // topologies never merge once formed.
        if (!armed) {
            if (startupQuorumTimeoutMs > 0 && elapsedMs(startedAtNanos) >= startupQuorumTimeoutMs) {
                actOn("Server topology never reached the minimum cluster size of "
                      + minimumClusterSize + " within " + startupQuorumTimeoutMs
                      + " ms of startup. This node likely started into an ongoing partition "
                      + "and would run split-brained.");
            }
            return;
        }

        if (belowMinimumSinceNanos == -1) {
            belowMinimumSinceNanos = System.nanoTime();
            log.warn("Server topology dropped to {} nodes (minimum {}). Action in {} ms unless it recovers "
                     + "(shutdownEnabled={})",
                     serverNodes, minimumClusterSize, orphanGracePeriodMs, shutdownEnabled);
            return;
        }

        long belowForMs = elapsedMs(belowMinimumSinceNanos);
        if (belowForMs >= orphanGracePeriodMs) {
            actOn("Server topology has been below the minimum cluster size of "
                  + minimumClusterSize + " for " + belowForMs
                  + " ms. This node is likely orphaned from the cluster.");
        }
    }

    private void actOn(String reason) {
        if (closed) {
            return;
        }

        if (!shutdownEnabled) {
            // Report once per below-minimum episode so the log stays readable
            if (!observeOnlyReported) {
                observeOnlyReported = true;
                log.error("[observe-only] Cluster observer would shut this node down: {}", reason);
            }
            return;
        }

        if (!shutdownInitiated.compareAndSet(false, true)) {
            return;
        }

        log.error("Shutting down so the orchestrator can start a fresh instance: {}", reason);

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(shutdownWatchdogTimeoutMs);
            } catch (InterruptedException ignored) {
                return;
            }
            log.error("Graceful shutdown did not complete within {} ms, halting JVM", shutdownWatchdogTimeoutMs);
            Runtime.getRuntime().halt(EXIT_CODE);
        }, "structures-cluster-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // Never shut down on an Ignite thread: closing the context stops Ignite, which
        // would deadlock waiting on the very thread we are running on
        Thread shutdown = new Thread(() -> {
            try {
                System.exit(SpringApplication.exit(applicationContext, () -> EXIT_CODE));
            } catch (Throwable t) {
                log.error("Error during graceful shutdown, halting JVM", t);
                Runtime.getRuntime().halt(EXIT_CODE);
            }
        }, "structures-cluster-shutdown");
        shutdown.setDaemon(false);
        shutdown.start();
    }

    private int safeServerTopologySize() {
        try {
            return ignite.cluster().forServers().nodes().size();
        } catch (Exception e) {
            return -1;
        }
    }

    // Monotonic elapsed time: wall-clock can step forward under NTP corrections or VM
    // pauses and would count that step against the grace periods
    private static long elapsedMs(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }
}
