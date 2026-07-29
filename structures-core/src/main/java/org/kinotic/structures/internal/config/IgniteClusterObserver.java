package org.kinotic.structures.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgnitePredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Purely diagnostic observer of Ignite cluster membership. It never changes behavior:
 * it does not shut anything down, does not gate readiness, and takes no action of any
 * kind - it only logs what it sees, so it is safe to run everywhere clustering is on.
 * <p>
 * What it records:
 * <ul>
 *   <li>membership changes (join/left/failed) with topology version and server count</li>
 *   <li>segmentation events - a segmented server node can never rejoin without a restart,
 *   so this is the highest value line it produces. Note continuum's non-development
 *   FailureHandler halts the JVM on the same thread right after listeners are notified,
 *   so this log may be the last thing the process writes.</li>
 *   <li>stale vertx routing state after a node departs. vertx-ignite cleans up a departed
 *   node's subscriptions only on the single survivor whose nodeInfoMap.remove(id) returns
 *   true; if that entry is already gone, no node runs cleanSubs and stale __vertx.subs
 *   entries remain, which is what produces "Not a member of the cluster" event bus send
 *   failures. Whether that is what happens here is UNCONFIRMED - continuum contributes a
 *   "*" cache template (PARTITIONED, backups=1, PRIMARY_SYNC) covering the __vertx.*
 *   caches, so entries are not lost outright on a single node failure. This observer
 *   exists to capture evidence rather than assume a mechanism. Because vertx-ignite's
 *   cleanup removes entries one at a time and can legitimately take a while, the check is
 *   sampled several times after a departure so an in-progress cleanup is distinguishable
 *   from a leak; only the final sample warns.</li>
 *   <li>server topology below structures.cluster.observer.minimumClusterSize (the only
 *   configuration this class has, default 1 = topology watchdog off). That is the
 *   split-brain condition Ignite cannot detect by design: group splits keep a healthy ring
 *   on each side, and a restart into a partition forms a fresh singleton topology. Set it
 *   to a majority of the replica count (floor(n/2)+1) to have those episodes logged.</li>
 * </ul>
 * Inspections run on their own thread, are bounded, and read only node-local cache
 * partitions, so they add no cluster-wide query load during a failure.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "continuum.disableClustering", havingValue = "false", matchIfMissing = true)
public class IgniteClusterObserver {

    private static final long TOPOLOGY_POLL_MS = 10_000L;
    private static final long ORPHAN_REPORT_AFTER_MS = 60_000L;
    private static final int MAX_STALE_ADDRESSES_LOGGED = 10;
    private static final int MAX_ENTRIES_SCANNED = 50_000;
    /** Sampled repeatedly so a slow cleanup is not reported as a leak; only the last warns */
    private static final long[] STALE_ROUTE_SAMPLE_DELAYS_MS = {5_000L, 20_000L, 60_000L};

    private final Ignite ignite;

    /**
     * Minimum number of server nodes expected in the topology. When above 1, episodes
     * below it are logged (with duration). Purely informational - nothing is ever shut
     * down. Set it to a majority of the replica count, e.g. 2 for 3 replicas.
     */
    @Value("${structures.cluster.observer.minimumClusterSize:${structures.cluster.observer.minimum-cluster-size:1}}")
    private int minimumClusterSize;

    private volatile boolean closed = false;
    private volatile boolean armed = false;
    private volatile boolean belowMinimumReported = false;
    private volatile long belowMinimumSinceNanos = -1;
    private final AtomicBoolean inspectionInProgress = new AtomicBoolean(false);

    private IgnitePredicate<Event> membershipListener;
    private IgnitePredicate<Event> segmentationListener;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService inspector;

    public IgniteClusterObserver(Ignite ignite) {
        this.ignite = ignite;
    }

    @PostConstruct
    public void start() {
        scheduler = newDaemonScheduler("structures-cluster-observer");
        // Inspections get their own thread: a cache read during a partition can block for
        // a long time, and it must never stall topology polling
        inspector = newDaemonScheduler("structures-cluster-inspector");

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
                scheduleStaleRouteSamples(eventNodeId);
            }
            return true;
        };
        ignite.events().localListen(membershipListener,
                                    EventType.EVT_NODE_JOINED,
                                    EventType.EVT_NODE_LEFT,
                                    EventType.EVT_NODE_FAILED);

        // Logged, never acted on. Continuum's FailureHandler decides what happens to the
        // process; this line is the evidence that segmentation is what happened.
        segmentationListener = event -> {
            log.error("Node segmentation detected: this Ignite node was segmented from the cluster. "
                      + "Segmented server nodes cannot rejoin without a restart (serverNodes={})",
                      safeServerTopologySize());
            return false; // one shot
        };
        ignite.events().localListen(segmentationListener, EventType.EVT_NODE_SEGMENTED);

        if (minimumClusterSize > 1) {
            scheduler.scheduleWithFixedDelay(this::checkTopologySafely,
                                             0,
                                             TOPOLOGY_POLL_MS,
                                             TimeUnit.MILLISECONDS);
            log.info("Ignite cluster observer started (diagnostic only): minimumClusterSize={}",
                     minimumClusterSize);
        } else {
            log.info("Ignite cluster observer started (diagnostic only): membership and routing "
                     + "diagnostics active, topology watchdog off "
                     + "(set structures.cluster.observer.minimumClusterSize above 1 to enable it)");
        }
    }

    @PreDestroy
    public void stop() {
        closed = true;
        // Deregister listeners BEFORE stopping the executors, otherwise a departure arriving
        // in between would schedule onto a terminated executor and throw
        // RejectedExecutionException back into Ignite's discovery notification thread
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
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (inspector != null) {
            inspector.shutdownNow();
        }
    }

    private void scheduleStaleRouteSamples(String departedNodeId) {
        if (closed) {
            return;
        }
        for (int i = 0; i < STALE_ROUTE_SAMPLE_DELAYS_MS.length; i++) {
            boolean finalSample = i == STALE_ROUTE_SAMPLE_DELAYS_MS.length - 1;
            long delay = STALE_ROUTE_SAMPLE_DELAYS_MS[i];
            try {
                inspector.schedule(() -> reportStaleRoutingState(departedNodeId, delay, finalSample),
                                   delay, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Executor already stopping; nothing to diagnose
                log.debug("Could not schedule routing state inspection", e);
                return;
            }
        }
    }

    /**
     * Inspect this node's LOCAL partitions of the vertx routing caches for entries that
     * still reference a departed node. Local-only by design: it costs nothing beyond a
     * node-local iteration, adds no distributed query load while the cluster is already
     * rebalancing, and every surviving node logs its own view, which together cover the
     * cluster.
     */
    private void reportStaleRoutingState(String departedNodeId, long afterMs, boolean finalSample) {
        if (closed) {
            return;
        }
        // Never let overlapping departures stack up inspections
        if (!inspectionInProgress.compareAndSet(false, true)) {
            log.debug("Skipping routing state inspection for {}, another inspection is running",
                      departedNodeId);
            return;
        }
        try {
            IgniteCache<String, ?> nodeInfoCache = ignite.cache("__vertx.nodeInfo");
            boolean nodeInfoPresent = nodeInfoCache != null && nodeInfoCache.containsKey(departedNodeId);

            int staleSubs = 0;
            int scanned = 0;
            boolean truncated = false;
            List<String> staleAddresses = new ArrayList<>();
            IgniteCache<Object, Object> subsCache = ignite.cache("__vertx.subs");
            if (subsCache != null) {
                // Local scan, binary form: no cluster-wide query, and no compile-time
                // dependency on vertx-ignite's IgniteRegistrationInfo (the binary field
                // names match its writeBinary implementation)
                ScanQuery<Object, Object> query = new ScanQuery<>();
                query.setLocal(true);
                try (QueryCursor<Cache.Entry<Object, Object>> cursor
                             = subsCache.withKeepBinary().query(query)) {
                    for (Cache.Entry<Object, Object> entry : cursor) {
                        if (++scanned > MAX_ENTRIES_SCANNED) {
                            truncated = true;
                            break;
                        }
                        if (entry.getKey() instanceof BinaryObject key
                                && departedNodeId.equals(key.field("nodeId"))) {
                            staleSubs++;
                            if (staleAddresses.size() < MAX_STALE_ADDRESSES_LOGGED) {
                                staleAddresses.add(key.field("address"));
                            }
                        }
                    }
                }
            }

            if (!nodeInfoPresent && staleSubs == 0) {
                log.info("Routing caches are clean (local view) {} ms after departure of node {}: "
                         + "scannedLocalSubs={}", afterMs, departedNodeId, scanned);
            } else if (finalSample) {
                log.warn("Stale routing state remains {} ms after departure of node {}: "
                         + "nodeInfoStillPresent={}, staleLocalSubscriptionEntries={}, "
                         + "sampleAddresses={}, scannedLocalSubs={}, truncated={}. Event bus sends to "
                         + "these addresses can fail with 'Not a member of the cluster' until the "
                         + "handlers re-register.",
                         afterMs, departedNodeId, nodeInfoPresent, staleSubs, staleAddresses,
                         scanned, truncated);
            } else {
                log.info("Routing cleanup still in progress {} ms after departure of node {}: "
                         + "nodeInfoStillPresent={}, staleLocalSubscriptionEntries={}",
                         afterMs, departedNodeId, nodeInfoPresent, staleSubs);
            }
        } catch (Exception e) {
            // WARN, not DEBUG: a failed inspection must never be mistaken for a clean result
            log.warn("Could not inspect routing caches {} ms after departure of node {}; "
                     + "no conclusion can be drawn about stale routing state",
                     afterMs, departedNodeId, e);
        } finally {
            inspectionInProgress.set(false);
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
        if (closed) {
            return;
        }

        int serverNodes = safeServerTopologySize();
        if (serverNodes < 0) {
            log.warn("Ignite topology is not queryable");
            return;
        }

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
                log.info("Server topology recovered to {} nodes after {} ms below the minimum of {}",
                         serverNodes, elapsedMs(belowMinimumSinceNanos), minimumClusterSize);
            }
            belowMinimumSinceNanos = -1;
            belowMinimumReported = false;
            return;
        }

        // Below the minimum. Startup (nodes joining one by one) is not interesting, so only
        // report once the topology has actually reached the minimum at least once.
        if (!armed) {
            return;
        }

        if (belowMinimumSinceNanos == -1) {
            belowMinimumSinceNanos = System.nanoTime();
            log.warn("Server topology dropped to {} nodes, below the minimum of {}. This node may be "
                     + "orphaned from the cluster; watching",
                     serverNodes, minimumClusterSize);
            return;
        }

        long belowForMs = elapsedMs(belowMinimumSinceNanos);
        // One escalation per episode so a long orphan does not flood the log
        if (!belowMinimumReported && belowForMs >= ORPHAN_REPORT_AFTER_MS) {
            belowMinimumReported = true;
            log.error("Server topology has been at {} nodes, below the minimum of {}, for {} ms. "
                      + "This node is very likely orphaned or split-brained and would need a restart "
                      + "to rejoin the cluster",
                      serverNodes, minimumClusterSize, belowForMs);
        }
    }

    private ScheduledExecutorService newDaemonScheduler(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private int safeServerTopologySize() {
        try {
            return ignite.cluster().forServers().nodes().size();
        } catch (Exception e) {
            return -1;
        }
    }

    // Monotonic elapsed time: wall-clock can step forward under NTP corrections or VM
    // pauses and would misreport how long a node has been below the minimum
    private static long elapsedMs(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }
}
