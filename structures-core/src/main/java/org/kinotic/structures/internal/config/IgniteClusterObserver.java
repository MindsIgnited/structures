package org.kinotic.structures.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CachePeekMode;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Purely diagnostic observer of Ignite cluster membership. It never changes behavior:
 * it does not shut anything down, does not gate readiness, and takes no action of any
 * kind - it only logs what it sees, so it is safe to run everywhere clustering is on.
 * <p>
 * All work is handed to its own daemon threads; nothing but the handoff runs on Ignite's
 * discovery worker, because stalling that worker would trip Ignite's own failure handler.
 * <p>
 * What it records:
 * <ul>
 *   <li>membership changes (join/left/failed) with topology version and server count</li>
 *   <li>segmentation events - a segmented server node can never rejoin without a restart,
 *   so this is the highest value line it produces. Note continuum's non-development
 *   FailureHandler halts the JVM shortly after listeners are notified, so this log may be
 *   the last thing the process writes.</li>
 *   <li>stale vertx routing state after a node departs. vertx-ignite cleans up a departed
 *   node's subscriptions only on the single survivor whose nodeInfoMap.remove(id) returns
 *   true; if that entry is already gone, no node runs cleanSubs and stale __vertx.subs
 *   entries remain, which is what produces "Not a member of the cluster" event bus send
 *   failures. Whether that is what happens here is UNCONFIRMED - continuum contributes a
 *   "*" cache template (PARTITIONED, backups=1, PRIMARY_SYNC) covering the __vertx.*
 *   caches, so entries are not lost outright on a single node failure. This observer
 *   exists to capture evidence rather than assume a mechanism. Cleanup removes entries one
 *   at a time and can legitimately take a while, so the check is sampled several times
 *   after a departure; only the final sample warns. Every result states the coverage it
 *   achieved, and a scan that could not cover the data never reports "clean" - an
 *   inconclusive result must never be mistaken for an all-clear.</li>
 *   <li>server topology below structures.cluster.observer.minimumClusterSize (the only
 *   configuration this class has, default 1 = topology reporting off). That is the
 *   split-brain condition Ignite cannot detect by design: group splits keep a healthy ring
 *   on each side, and a restart into a partition forms a fresh singleton topology that
 *   Ignite never merges. Set it to a majority of the replica count (floor(n/2)+1) to have
 *   those episodes logged, including a node that never reaches the minimum at all.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "continuum.disableClustering", havingValue = "false", matchIfMissing = true)
public class IgniteClusterObserver {

    private static final long TOPOLOGY_POLL_MS = 10_000L;
    private static final long REPORT_AFTER_BELOW_MINIMUM_MS = 60_000L;
    private static final long REPORT_NEVER_REACHED_MINIMUM_MS = 300_000L;
    private static final long UNQUERYABLE_REPORT_INTERVAL_MS = 600_000L;
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
    private volatile boolean neverReachedMinimumReported = false;
    private volatile long belowMinimumSinceNanos = -1;
    private volatile long startedAtNanos = -1;
    private volatile long lastUnqueryableReportNanos = -1;

    private IgnitePredicate<Event> membershipListener;
    private IgnitePredicate<Event> segmentationListener;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService inspector;

    public IgniteClusterObserver(Ignite ignite) {
        this.ignite = ignite;
    }

    @PostConstruct
    public void start() {
        startedAtNanos = System.nanoTime();
        scheduler = newDaemonScheduler("structures-cluster-observer");
        // Inspections get their own thread: a cache read during a partition can block for
        // a long time, and it must never stall topology polling
        inspector = newDaemonScheduler("structures-cluster-inspector");

        // The listener captures the event and hands off immediately. Ignite notifies local
        // listeners inline on the discovery worker, which is a monitored critical worker:
        // logging (and any cluster call) there risks tripping SYSTEM_WORKER_BLOCKED.
        membershipListener = event -> {
            DiscoveryEvent discoveryEvent = (DiscoveryEvent) event;
            String eventNodeId = discoveryEvent.eventNode().id().toString();
            long topologyVersion = discoveryEvent.topologyVersion();
            int eventType = event.type();
            submit(scheduler, () -> logMembershipChange(eventType, eventNodeId, topologyVersion));
            if (eventType == EventType.EVT_NODE_LEFT || eventType == EventType.EVT_NODE_FAILED) {
                scheduleStaleRouteSamples(eventNodeId);
            }
            return true;
        };
        ignite.events().localListen(membershipListener,
                                    EventType.EVT_NODE_JOINED,
                                    EventType.EVT_NODE_LEFT,
                                    EventType.EVT_NODE_FAILED);

        // Logged, never acted on. Continuum's FailureHandler decides what happens to the
        // process. Logged inline rather than handed off: the JVM is likely to be halted
        // moments from now, and an off-thread log would never be written.
        segmentationListener = event -> {
            log.error("Node segmentation detected: this Ignite node was segmented from the cluster. "
                      + "Segmented server nodes cannot rejoin without a restart. {}",
                      describeServerTopology());
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
                     + "diagnostics active, topology reporting off "
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

    private void logMembershipChange(int eventType, String eventNodeId, long topologyVersion) {
        switch (eventType) {
            case EventType.EVT_NODE_JOINED ->
                    log.info("Cluster node joined: {} (topologyVersion={}, {})",
                             eventNodeId, topologyVersion, describeServerTopology());
            case EventType.EVT_NODE_LEFT ->
                    log.info("Cluster node left: {} (topologyVersion={}, {})",
                             eventNodeId, topologyVersion, describeServerTopology());
            case EventType.EVT_NODE_FAILED ->
                    log.warn("Cluster node FAILED: {} (topologyVersion={}, {})",
                             eventNodeId, topologyVersion, describeServerTopology());
            default -> { /* not registered for others */ }
        }
    }

    private void scheduleStaleRouteSamples(String departedNodeId) {
        if (closed) {
            return;
        }
        long departedAtNanos = System.nanoTime();
        for (int i = 0; i < STALE_ROUTE_SAMPLE_DELAYS_MS.length; i++) {
            boolean finalSample = i == STALE_ROUTE_SAMPLE_DELAYS_MS.length - 1;
            long delay = STALE_ROUTE_SAMPLE_DELAYS_MS[i];
            submit(inspector,
                   () -> reportStaleRoutingState(departedNodeId, departedAtNanos, finalSample),
                   delay);
        }
    }

    /**
     * Inspect this node's local partitions of the vertx routing caches for entries that
     * still reference a departed node. Local by design: it costs nothing beyond a
     * node-local iteration and adds no distributed query load while the cluster is already
     * rebalancing. Primary AND backup partitions are read, because a departure triggers
     * rebalancing and an entry this node holds only as a backup is exactly the kind a
     * primary-only scan would miss. Every log line states the coverage achieved so an
     * inconclusive scan can never be read as an all-clear.
     */
    private void reportStaleRoutingState(String departedNodeId, long departedAtNanos, boolean finalSample) {
        if (closed) {
            return;
        }
        // Real elapsed time, not the nominal schedule: a sample can run late when the
        // cluster is unhealthy, which is exactly when the timestamp matters
        long afterMs = elapsedMs(departedAtNanos);
        try {
            IgniteCache<String, ?> nodeInfoCache = ignite.cache("__vertx.nodeInfo");
            IgniteCache<Object, Object> subsCache = ignite.cache("__vertx.subs");

            if (nodeInfoCache == null || subsCache == null) {
                log.warn("Inconclusive routing inspection {} ms after departure of node {}: "
                         + "vertx caches are not available on this node "
                         + "(nodeInfoCachePresent={}, subsCachePresent={}). No conclusion can be "
                         + "drawn about stale routing state.",
                         afterMs, departedNodeId, nodeInfoCache != null, subsCache != null);
                return;
            }

            boolean nodeInfoPresent = nodeInfoCache.containsKey(departedNodeId);

            int staleSubs = 0;
            int scanned = 0;
            int nonBinaryKeys = 0;
            boolean truncated = false;
            List<String> staleAddresses = new ArrayList<>();
            // localEntries with explicit peek modes rather than a ScanQuery: it is
            // node-local (no distributed query while the cluster is rebalancing) AND it
            // includes backup partitions. A plain scan query only covers partitions this
            // node is primary for, which is exactly where a departure's stale entries can
            // hide during rebalancing. Binary form avoids a compile-time dependency on
            // vertx-ignite's IgniteRegistrationInfo (the field names match its writeBinary).
            for (Cache.Entry<Object, Object> entry : subsCache.withKeepBinary()
                                                              .localEntries(CachePeekMode.PRIMARY,
                                                                            CachePeekMode.BACKUP)) {
                if (scanned >= MAX_ENTRIES_SCANNED) {
                    truncated = true;
                    break;
                }
                scanned++;
                if (entry.getKey() instanceof BinaryObject key) {
                    if (departedNodeId.equals(key.field("nodeId"))) {
                        staleSubs++;
                        if (staleAddresses.size() < MAX_STALE_ADDRESSES_LOGGED) {
                            staleAddresses.add(key.field("address"));
                        }
                    }
                } else {
                    nonBinaryKeys++;
                }
            }

            // An unexamined remainder or unreadable keys mean the scan cannot support an
            // all-clear, so say so rather than implying the caches are clean
            boolean conclusive = !truncated && nonBinaryKeys == 0;
            String coverage = String.format(
                    "localEntriesScanned=%d (primary+backup), truncated=%b, unreadableKeys=%d",
                    scanned, truncated, nonBinaryKeys);

            if (nodeInfoPresent || staleSubs > 0) {
                if (finalSample) {
                    log.warn("Stale routing state remains {} ms after departure of node {}: "
                             + "nodeInfoStillPresent={}, staleLocalSubscriptionEntries={}, "
                             + "sampleAddresses={}, {}. Event bus sends to these addresses can fail "
                             + "with 'Not a member of the cluster' until the handlers re-register.",
                             afterMs, departedNodeId, nodeInfoPresent, staleSubs, staleAddresses, coverage);
                } else {
                    log.info("Routing cleanup still in progress {} ms after departure of node {}: "
                             + "nodeInfoStillPresent={}, staleLocalSubscriptionEntries={}, {}",
                             afterMs, departedNodeId, nodeInfoPresent, staleSubs, coverage);
                }
            } else if (conclusive) {
                log.info("Routing caches are clean (local view) {} ms after departure of node {}: {}",
                         afterMs, departedNodeId, coverage);
            } else {
                log.warn("Inconclusive routing inspection {} ms after departure of node {}: no stale "
                         + "entries seen, but the scan did not cover all local entries so this is NOT "
                         + "an all-clear ({})",
                         afterMs, departedNodeId, coverage);
            }
        } catch (Exception e) {
            if (closed || Thread.currentThread().isInterrupted()) {
                // Ordinary shutdown interrupted the read; not a cluster problem
                log.debug("Routing inspection for node {} aborted during shutdown", departedNodeId, e);
            } else {
                // WARN, not DEBUG: a failed inspection must never be mistaken for a clean result
                log.warn("Could not inspect routing caches {} ms after departure of node {}; "
                         + "no conclusion can be drawn about stale routing state",
                         afterMs, departedNodeId, e);
            }
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

        int serverNodes;
        try {
            serverNodes = ignite.cluster().forServers().nodes().size();
        } catch (Exception e) {
            reportUnqueryableTopology(e);
            return;
        }
        lastUnqueryableReportNanos = -1;

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

        // Never reached the minimum. Normal startup climbs to it within seconds; a node
        // that stays here has very likely started into an ongoing partition and formed its
        // own singleton topology, which Ignite never merges back. Report it once - this is
        // the split-brain case that produces no segmentation event at all.
        if (!armed) {
            if (!neverReachedMinimumReported
                    && elapsedMs(startedAtNanos) >= REPORT_NEVER_REACHED_MINIMUM_MS) {
                neverReachedMinimumReported = true;
                log.error("Server topology has never reached the minimum of {} since startup {} ms ago "
                          + "(currently {} nodes). This node may have started into an ongoing partition "
                          + "and formed its own topology, which Ignite cannot merge; it would need a "
                          + "restart to join the real cluster",
                          minimumClusterSize, elapsedMs(startedAtNanos), serverNodes);
            }
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
        if (!belowMinimumReported && belowForMs >= REPORT_AFTER_BELOW_MINIMUM_MS) {
            belowMinimumReported = true;
            log.error("Server topology has been at {} nodes, below the minimum of {}, for {} ms. "
                      + "This node is very likely orphaned or split-brained and would need a restart "
                      + "to rejoin the cluster",
                      serverNodes, minimumClusterSize, belowForMs);
        }
    }

    /**
     * The topology can stay unqueryable indefinitely (a stopped Ignite node in a live JVM),
     * so this is throttled: an unbounded repeat would bury the evidence this class exists
     * to produce.
     */
    private void reportUnqueryableTopology(Exception cause) {
        if (lastUnqueryableReportNanos == -1
                || elapsedMs(lastUnqueryableReportNanos) >= UNQUERYABLE_REPORT_INTERVAL_MS) {
            lastUnqueryableReportNanos = System.nanoTime();
            log.warn("Ignite topology is not queryable; cluster observations are unavailable "
                     + "(further occurrences logged at most every {} ms)",
                     UNQUERYABLE_REPORT_INTERVAL_MS, cause);
        }
    }

    /**
     * Server topology description for log context. Includes the failure reason rather than
     * a bare sentinel, so a line that could not read the topology says why.
     */
    private String describeServerTopology() {
        try {
            return "serverNodes=" + ignite.cluster().forServers().nodes().size();
        } catch (Exception e) {
            return "serverNodes=unknown (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    private void submit(ScheduledExecutorService executor, Runnable task) {
        submit(executor, task, 0);
    }

    private void submit(ScheduledExecutorService executor, Runnable task, long delayMs) {
        try {
            executor.schedule(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("Unexpected error in cluster observer task", t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Executor already stopping; nothing to diagnose
            log.debug("Cluster observer task not scheduled, observer is shutting down");
        }
    }

    private ScheduledExecutorService newDaemonScheduler(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    // Monotonic elapsed time: wall-clock can step forward under NTP corrections or VM
    // pauses and would misreport how long a node has been below the minimum
    private static long elapsedMs(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }
}
