package org.kinotic.structures.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgnitePredicate;
import org.kinotic.structures.api.config.ClusterObserverProperties;
import org.kinotic.structures.api.config.StructuresProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Purely diagnostic observer of Ignite cluster membership. It never changes behavior:
 * it does not shut anything down, does not gate readiness, and takes no action of any
 * kind - it only logs what it sees, so it is safe to run continuously in production.
 * <p>
 * It is built to run for days and stay trustworthy: every Ignite read it performs is
 * time-bounded, every repeated condition is throttled, and any result it cannot fully
 * substantiate is reported as inconclusive rather than as an all-clear. A diagnostic that
 * cries wolf is worse than none, so the reporting rules are deliberately conservative.
 * <p>
 * What it records:
 * <ul>
 *   <li>membership changes (join/left/failed) with topology version and server count,
 *   logged inline from the event itself so the line survives a JVM halt moments later</li>
 *   <li>segmentation events - a segmented server node can never rejoin without a restart,
 *   so this is the highest value line it produces. Continuum's non-development
 *   FailureHandler halts the JVM shortly after listeners are notified, so this may be the
 *   last thing the process writes.</li>
 *   <li>stale vertx routing state after a node departs. vertx-ignite cleans up a departed
 *   node's subscriptions only on the single survivor whose nodeInfoMap.remove(id) returns
 *   true; if that entry is already gone, no node runs cleanSubs and stale __vertx.subs
 *   entries remain, which is what produces "Not a member of the cluster" event bus send
 *   failures. Whether that is what happens here is UNCONFIRMED - continuum contributes a
 *   "*" cache template (PARTITIONED, backups=1, PRIMARY_SYNC) covering the __vertx.*
 *   caches, so entries are not lost outright on a single node failure. This observer
 *   exists to capture evidence rather than assume a mechanism.</li>
 *   <li>server topology below the configured minimum cluster size - the split-brain
 *   condition Ignite cannot detect by design, since group splits keep a healthy ring on
 *   each side and a restart into a partition forms a fresh singleton topology that Ignite
 *   never merges.</li>
 * </ul>
 * Counting rules for the routing inspection, so numbers from different pods can be
 * compared and summed: an entry is counted as stale by the node that currently owns its
 * partition as PRIMARY, verified through the affinity function rather than inferred from
 * local storage. That keeps each entry counted exactly once cluster-wide and excludes
 * partitions in the process of being rebalanced away, which still hold their old contents
 * and would otherwise produce phantom findings during a rolling restart. Entries held
 * only as backups are counted and reported separately, never mixed into the primary total.
 *
 * @see ClusterObserverProperties
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "continuum.disableClustering", havingValue = "false", matchIfMissing = true)
public class IgniteClusterObserver {

    private static final String NODE_INFO_CACHE = "__vertx.nodeInfo";
    private static final String SUBS_CACHE = "__vertx.subs";
    private static final long TOPOLOGY_POLL_MS = 10_000L;
    private static final long UNQUERYABLE_REPORT_INTERVAL_MS = 600_000L;
    private static final long UNEXPECTED_ERROR_REPORT_INTERVAL_MS = 600_000L;
    private static final long NEVER_REACHED_REPORT_INTERVAL_MS = 3_600_000L;
    private static final int MAX_STALE_ADDRESSES_LOGGED = 10;
    /** Sampled repeatedly so a slow cleanup is not reported as a leak; only the final sample warns */
    private static final long[] STALE_ROUTE_SAMPLE_DELAYS_MS = {5_000L, 20_000L, 60_000L};

    private final Ignite ignite;
    private final ClusterObserverProperties properties;

    private volatile boolean closed = false;
    private volatile boolean armed = false;
    private volatile boolean belowMinimumReported = false;
    private volatile long belowMinimumSinceNanos = -1;
    private volatile long startedAtNanos = -1;
    private volatile long lastUnqueryableReportNanos = -1;
    private volatile long lastUnexpectedErrorReportNanos = -1;
    private volatile long lastNeverReachedReportNanos = -1;
    private volatile boolean neverReachedEscalated = false;

    private IgnitePredicate<Event> membershipListener;
    private IgnitePredicate<Event> segmentationListener;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService inspector;

    public IgniteClusterObserver(Ignite ignite, StructuresProperties structuresProperties) {
        this.ignite = ignite;
        this.properties = structuresProperties.getClusterObserver();
    }

    @PostConstruct
    public void start() {
        startedAtNanos = System.nanoTime();
        scheduler = newDaemonScheduler("structures-cluster-observer");
        // Inspections get their own thread so a slow cache read can never delay topology polling
        inspector = newDaemonScheduler("structures-cluster-inspector");

        // Logged inline, from data carried on the event itself. No cluster calls here: Ignite
        // notifies listeners on its monitored discovery worker, and this line must be on disk
        // before a segmentation halt can discard it.
        membershipListener = event -> {
            try {
                DiscoveryEvent discoveryEvent = (DiscoveryEvent) event;
                String eventNodeId = discoveryEvent.eventNode().id().toString();
                long topologyVersion = discoveryEvent.topologyVersion();
                int serverNodes = countServers(discoveryEvent.topologyNodes());
                switch (event.type()) {
                    case EventType.EVT_NODE_JOINED ->
                            log.info("Cluster node joined: {} (topologyVersion={}, serverNodes={})",
                                     eventNodeId, topologyVersion, serverNodes);
                    case EventType.EVT_NODE_LEFT -> {
                        log.info("Cluster node left: {} (topologyVersion={}, serverNodes={})",
                                 eventNodeId, topologyVersion, serverNodes);
                        scheduleStaleRouteSamples(eventNodeId);
                    }
                    case EventType.EVT_NODE_FAILED -> {
                        log.warn("Cluster node FAILED: {} (topologyVersion={}, serverNodes={})",
                                 eventNodeId, topologyVersion, serverNodes);
                        scheduleStaleRouteSamples(eventNodeId);
                    }
                    default -> { /* not registered for others */ }
                }
            } catch (Throwable t) {
                // Never let a diagnostic throw into Ignite's discovery thread
                log.warn("Error handling cluster membership event", t);
            }
            return true;
        };
        ignite.events().localListen(membershipListener,
                                    EventType.EVT_NODE_JOINED,
                                    EventType.EVT_NODE_LEFT,
                                    EventType.EVT_NODE_FAILED);

        // Logged, never acted on. Continuum's FailureHandler decides what happens to the
        // process; inline for the same durability reason as above.
        segmentationListener = event -> {
            try {
                DiscoveryEvent discoveryEvent = (DiscoveryEvent) event;
                log.error("Node segmentation detected: this Ignite node was segmented from the "
                          + "cluster and cannot rejoin without a restart "
                          + "(topologyVersion={}, serverNodesVisible={})",
                          discoveryEvent.topologyVersion(), countServers(discoveryEvent.topologyNodes()));
            } catch (Throwable t) {
                log.error("Node segmentation detected: this Ignite node was segmented from the "
                          + "cluster and cannot rejoin without a restart", t);
            }
            return false; // one shot
        };
        ignite.events().localListen(segmentationListener, EventType.EVT_NODE_SEGMENTED);

        if (minimumClusterSize() > 1) {
            scheduler.scheduleWithFixedDelay(this::checkTopologySafely,
                                             0,
                                             TOPOLOGY_POLL_MS,
                                             TimeUnit.MILLISECONDS);
            log.info("Ignite cluster observer started (diagnostic only): minimumClusterSize={}",
                     minimumClusterSize());
        } else {
            log.info("Ignite cluster observer started (diagnostic only): membership, segmentation "
                     + "and routing diagnostics active, topology reporting off "
                     + "(set structures.cluster-observer.minimum-cluster-size above 1 to enable it)");
        }
    }

    @PreDestroy
    public void stop() {
        closed = true;
        // Deregister listeners BEFORE stopping the executors, so a departure arriving in
        // between cannot schedule onto a terminated executor and throw back into Ignite
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
        // shutdown(), never shutdownNow(): interrupting a thread inside an Ignite cache
        // operation can tear down in-flight futures and make this node leave as FAILED
        // rather than LEFT. Tasks observe `closed` and return promptly, and both executors
        // are daemons, so nothing can hold the JVM open.
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (inspector != null) {
            inspector.shutdown();
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
            try {
                inspector.schedule(() -> reportStaleRoutingState(departedNodeId, departedAtNanos, finalSample),
                                   delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("Routing inspection not scheduled, observer is shutting down");
                return;
            }
        }
    }

    /**
     * Inspect this node's local view of the vertx routing caches for entries that still
     * reference a departed node. Local reads only, so this adds no distributed query load
     * while the cluster is already rebalancing, and every Ignite call is time-bounded so a
     * cluster hang cannot silence the observer.
     */
    private void reportStaleRoutingState(String departedNodeId, long departedAtNanos, boolean finalSample) {
        if (closed) {
            return;
        }
        // Real elapsed time, not the nominal schedule: a sample can run late when the
        // cluster is unhealthy, which is exactly when the timestamp matters
        long afterMs = elapsedMs(departedAtNanos);
        try {
            // cacheNames() is a local metadata read; ignite.cache() on an unknown name can
            // otherwise trigger a blocking cluster-wide dynamic cache start
            Collection<String> cacheNames = ignite.cacheNames();
            boolean nodeInfoAvailable = cacheNames.contains(NODE_INFO_CACHE);
            boolean subsAvailable = cacheNames.contains(SUBS_CACHE);

            if (!nodeInfoAvailable && !subsAvailable) {
                log.warn("Inconclusive routing inspection {} ms after departure of node {}: neither "
                         + "{} nor {} exists on this node, so no conclusion can be drawn about stale "
                         + "routing state", afterMs, departedNodeId, NODE_INFO_CACHE, SUBS_CACHE);
                return;
            }

            // Each half is independent: a missing subs cache must not cost us the nodeInfo
            // verdict, which is the direct evidence of a failed cleanup election
            Boolean nodeInfoPresent = nodeInfoAvailable ? checkNodeInfoPresent(departedNodeId) : null;
            SubsScanResult subs = subsAvailable ? scanSubs(departedNodeId) : null;

            String coverage = String.format(
                    "nodeInfoChecked=%s, subsScanned=%s",
                    nodeInfoPresent != null ? "yes" : "no (unavailable or timed out)",
                    subs != null ? subs.describe() : "no (cache unavailable)");

            boolean sawStale = Boolean.TRUE.equals(nodeInfoPresent)
                               || (subs != null && (subs.primaryStale > 0 || subs.backupStale > 0));
            boolean conclusive = nodeInfoPresent != null && subs != null && subs.conclusive();

            if (sawStale) {
                String detail = String.format(
                        "nodeInfoStillPresent=%s, staleSubsOwnedHere=%d, staleSubsBackupCopiesHere=%d, "
                        + "sampleAddresses=%s, %s",
                        nodeInfoPresent, subs != null ? subs.primaryStale : -1,
                        subs != null ? subs.backupStale : -1,
                        subs != null ? subs.addresses : List.of(), coverage);
                if (finalSample) {
                    log.warn("Stale routing state remains {} ms after departure of node {}: {}. "
                             + "staleSubsOwnedHere counts only partitions this node currently owns as "
                             + "primary, so it is safe to sum across pods; backup copies are reported "
                             + "separately and duplicate another pod's primary count. Event bus sends "
                             + "to these addresses can fail with 'Not a member of the cluster' until "
                             + "the handlers re-register.",
                             afterMs, departedNodeId, detail);
                } else {
                    log.info("Routing cleanup still in progress {} ms after departure of node {}: {}",
                             afterMs, departedNodeId, detail);
                }
            } else if (conclusive) {
                log.info("Routing caches are clean (local view) {} ms after departure of node {}: {}",
                         afterMs, departedNodeId, coverage);
            } else {
                log.warn("Inconclusive routing inspection {} ms after departure of node {}: no stale "
                         + "entries seen, but the inspection did not complete fully so this is NOT an "
                         + "all-clear ({})", afterMs, departedNodeId, coverage);
            }
        } catch (Exception e) {
            if (closed) {
                log.debug("Routing inspection for node {} abandoned during shutdown", departedNodeId, e);
            } else {
                // WARN, not DEBUG: a failed inspection must never be mistaken for a clean result
                log.warn("Could not inspect routing caches {} ms after departure of node {}; "
                         + "no conclusion can be drawn about stale routing state",
                         afterMs, departedNodeId, e);
            }
        }
    }

    /**
     * @return TRUE/FALSE if the check completed, or null if it could not be completed
     * within the configured timeout - never guess, the caller reports it as inconclusive
     */
    private Boolean checkNodeInfoPresent(String departedNodeId) {
        try {
            IgniteCache<String, Object> cache = ignite.cache(NODE_INFO_CACHE);
            if (cache == null) {
                return null;
            }
            // Async with an explicit timeout: containsKey on a PARTITIONED cache is a
            // distributed read that can block on partition map exchange indefinitely,
            // which is precisely the condition being diagnosed
            return cache.containsKeyAsync(departedNodeId)
                        .get(inspectionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("nodeInfo lookup for departed node {} did not complete", departedNodeId, e);
            return null;
        }
    }

    private SubsScanResult scanSubs(String departedNodeId) {
        SubsScanResult result = new SubsScanResult();
        IgniteCache<Object, Object> cache = ignite.cache(SUBS_CACHE);
        if (cache == null) {
            return null;
        }
        Affinity<Object> affinity = ignite.affinity(SUBS_CACHE);
        ClusterNode localNode = ignite.cluster().localNode();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(inspectionTimeoutMs());
        int maxEntries = maxEntriesScanned();

        Iterable<Cache.Entry<Object, Object>> entries =
                cache.withKeepBinary().localEntries(CachePeekMode.PRIMARY, CachePeekMode.BACKUP);
        Iterator<Cache.Entry<Object, Object>> iterator = entries.iterator();
        try {
            while (iterator.hasNext()) {
                if (result.scanned >= maxEntries) {
                    result.truncated = true;
                    break;
                }
                if (System.nanoTime() > deadlineNanos) {
                    result.timedOut = true;
                    break;
                }
                Cache.Entry<Object, Object> entry = iterator.next();
                result.scanned++;
                if (!(entry.getKey() instanceof BinaryObject key)) {
                    result.unreadableKeys++;
                    continue;
                }
                if (!departedNodeId.equals(key.field("nodeId"))) {
                    continue;
                }
                // Ownership from the affinity function, not from the fact the data is here:
                // partitions being rebalanced away still hold their old contents, and counting
                // those would produce phantom findings during every rolling restart
                if (affinity.isPrimary(localNode, key)) {
                    result.primaryStale++;
                    if (result.addresses.size() < MAX_STALE_ADDRESSES_LOGGED) {
                        result.addresses.add(String.valueOf(key.field("address")));
                    }
                } else if (affinity.isBackup(localNode, key)) {
                    result.backupStale++;
                }
                // Entries in partitions this node no longer owns are deliberately ignored
            }
        } finally {
            closeQuietly(iterator);
        }
        return result;
    }

    /**
     * An exception escaping a scheduled task silently cancels all future executions - never
     * let that happen, and never let a persistent failure flood the log either
     */
    private void checkTopologySafely() {
        try {
            checkTopology();
        } catch (Throwable t) {
            if (lastUnexpectedErrorReportNanos == -1
                    || elapsedMs(lastUnexpectedErrorReportNanos) >= UNEXPECTED_ERROR_REPORT_INTERVAL_MS) {
                lastUnexpectedErrorReportNanos = System.nanoTime();
                log.error("Unexpected error in cluster observer check (further occurrences logged at "
                          + "most every {} ms)", UNEXPECTED_ERROR_REPORT_INTERVAL_MS, t);
            }
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

        int minimumClusterSize = minimumClusterSize();

        if (log.isDebugEnabled()) {
            log.debug("Topology poll: serverNodes={}, minimum={}, armed={}, belowMinimumForMs={}",
                      serverNodes, minimumClusterSize, armed,
                      belowMinimumSinceNanos == -1 ? 0 : elapsedMs(belowMinimumSinceNanos));
        }

        if (serverNodes >= minimumClusterSize) {
            if (!armed) {
                armed = true;
                long formationMs = elapsedMs(startedAtNanos);
                if (lastNeverReachedReportNanos != -1) {
                    // Explicitly retract the earlier report so days of logs are not left with a
                    // scary line that later turned out to be slow startup
                    log.info("Server topology reached the minimum of {} ({} nodes) after {} ms; the "
                             + "earlier report about never reaching the minimum is resolved",
                             minimumClusterSize, serverNodes, formationMs);
                } else {
                    log.info("Cluster observer armed: server topology reached {} nodes after {} ms",
                             serverNodes, formationMs);
                }
                lastNeverReachedReportNanos = -1;
                neverReachedEscalated = false;
            } else if (belowMinimumSinceNanos != -1) {
                log.info("Server topology recovered to {} nodes after {} ms below the minimum of {}",
                         serverNodes, elapsedMs(belowMinimumSinceNanos), minimumClusterSize);
            }
            belowMinimumSinceNanos = -1;
            belowMinimumReported = false;
            return;
        }

        if (!armed) {
            reportNeverReachedMinimum(serverNodes, minimumClusterSize);
            return;
        }

        if (belowMinimumSinceNanos == -1) {
            belowMinimumSinceNanos = System.nanoTime();
            log.warn("Server topology dropped to {} nodes, below the minimum of {}. This node may be "
                     + "orphaned from the cluster; watching", serverNodes, minimumClusterSize);
            return;
        }

        long belowForMs = elapsedMs(belowMinimumSinceNanos);
        // One escalation per episode so a long orphan does not flood the log
        if (!belowMinimumReported && belowForMs >= reportBelowMinimumAfterMs()) {
            belowMinimumReported = true;
            log.error("Server topology has been at {} nodes, below the minimum of {}, for {} ms. "
                      + "This node is very likely orphaned or split-brained and would need a restart "
                      + "to rejoin the cluster", serverNodes, minimumClusterSize, belowForMs);
        }
    }

    /**
     * A node that has not yet reached the minimum may simply be starting into a cluster
     * that is still forming, which on Kubernetes can legitimately take minutes. So this
     * warns first, escalates to an error only once slow startup is no longer plausible,
     * and repeats at a low rate so a long-running pod's current state is always visible in
     * recent logs rather than only in a one-shot line from days ago.
     */
    private void reportNeverReachedMinimum(int serverNodes, int minimumClusterSize) {
        long runningMs = elapsedMs(startedAtNanos);
        if (runningMs < reportNeverReachedMinimumAfterMs()) {
            return;
        }
        boolean escalate = runningMs >= escalateNeverReachedMinimumAfterMs();
        boolean firstEscalation = escalate && !neverReachedEscalated;
        if (!firstEscalation
                && lastNeverReachedReportNanos != -1
                && elapsedMs(lastNeverReachedReportNanos) < NEVER_REACHED_REPORT_INTERVAL_MS) {
            return;
        }
        lastNeverReachedReportNanos = System.nanoTime();
        if (escalate) {
            neverReachedEscalated = true;
            log.error("Server topology has never reached the minimum of {} in the {} ms since startup "
                      + "(currently {} nodes). Cluster formation should be long complete, so this node "
                      + "has most likely formed its own topology - which Ignite can never merge - and "
                      + "would need a restart to join the real cluster",
                      minimumClusterSize, runningMs, serverNodes);
        } else {
            log.warn("Server topology has not yet reached the minimum of {} in the {} ms since startup "
                     + "(currently {} nodes). This is normal while a cluster is still forming; it will "
                     + "be reported as an error if it persists past {} ms",
                     minimumClusterSize, runningMs, serverNodes, escalateNeverReachedMinimumAfterMs());
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

    private static int countServers(Collection<ClusterNode> nodes) {
        if (nodes == null) {
            return -1;
        }
        int count = 0;
        for (ClusterNode node : nodes) {
            if (!node.isClient()) {
                count++;
            }
        }
        return count;
    }

    private static void closeQuietly(Iterator<?> iterator) {
        if (iterator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Could not close cache iterator", e);
            }
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
    // pauses and would misreport how long a condition has lasted
    private static long elapsedMs(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }

    private int minimumClusterSize() {
        return properties.getMinimumClusterSize() != null ? properties.getMinimumClusterSize() : 1;
    }

    private long reportBelowMinimumAfterMs() {
        return properties.getReportBelowMinimumAfterMs() != null
                ? properties.getReportBelowMinimumAfterMs() : 60_000L;
    }

    private long reportNeverReachedMinimumAfterMs() {
        return properties.getReportNeverReachedMinimumAfterMs() != null
                ? properties.getReportNeverReachedMinimumAfterMs() : 300_000L;
    }

    private long escalateNeverReachedMinimumAfterMs() {
        return properties.getEscalateNeverReachedMinimumAfterMs() != null
                ? properties.getEscalateNeverReachedMinimumAfterMs() : 900_000L;
    }

    private long inspectionTimeoutMs() {
        return properties.getInspectionTimeoutMs() != null ? properties.getInspectionTimeoutMs() : 10_000L;
    }

    private int maxEntriesScanned() {
        return properties.getMaxEntriesScanned() != null ? properties.getMaxEntriesScanned() : 50_000;
    }

    /**
     * Result of a local __vertx.subs scan. primaryStale is the cluster-summable count;
     * backupStale duplicates another node's primary count and is kept separate.
     */
    private static final class SubsScanResult {
        private int scanned;
        private int primaryStale;
        private int backupStale;
        private int unreadableKeys;
        private boolean truncated;
        private boolean timedOut;
        private final List<String> addresses = new ArrayList<>();

        private boolean conclusive() {
            return !truncated && !timedOut && unreadableKeys == 0;
        }

        private String describe() {
            return String.format("localEntriesScanned=%d, truncated=%b, timedOut=%b, unreadableKeys=%d",
                                 scanned, truncated, timedOut, unreadableKeys);
        }
    }
}
