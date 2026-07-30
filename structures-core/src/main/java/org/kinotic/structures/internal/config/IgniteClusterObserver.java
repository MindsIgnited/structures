package org.kinotic.structures.internal.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CachePeekMode;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
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
 * Reading the routing inspection output: counts are this node's LOCAL view - primary and
 * backup copies, including partitions being rebalanced - so the same entry can appear in
 * more than one pod's log. Correlate the reported addresses across pods rather than
 * summing the counts. Precision is deliberately traded for robustness: a failed or partial
 * inspection is reported and retried by the next sample, so transient conditions resolve
 * themselves and it is a complaint that keeps repeating which indicates a real problem.
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
    private static final long BELOW_MINIMUM_REPORT_INTERVAL_MS = 3_600_000L;
    // A wedged Ignite read must never silence later inspections, so inspections run on a
    // small bounded pool: at worst a hung cluster consumes these threads and subsequent
    // inspections are skipped with a log line, which is itself evidence of the hang
    private static final int INSPECTOR_THREADS = 2;
    private static final int INSPECTOR_QUEUE_DEPTH = 8;
    private static final int MAX_STALE_ADDRESSES_LOGGED = 10;
    /** Sampled repeatedly so a slow cleanup is not reported as a leak; only the final sample warns */
    private static final long[] STALE_ROUTE_SAMPLE_DELAYS_MS = {5_000L, 20_000L, 60_000L};

    private final Ignite ignite;
    private final ClusterObserverProperties properties;

    private volatile boolean closed = false;
    private volatile boolean armed = false;
    private volatile long lastBelowMinimumReportNanos = -1;
    private volatile long belowMinimumSinceNanos = -1;
    private volatile long startedAtNanos = -1;
    private volatile long lastUnqueryableReportNanos = -1;
    private volatile long lastUnexpectedErrorReportNanos = -1;
    private volatile long lastNeverReachedReportNanos = -1;
    private volatile boolean neverReachedEscalated = false;

    private IgnitePredicate<Event> membershipListener;
    private IgnitePredicate<Event> segmentationListener;
    private ScheduledExecutorService scheduler;
    private ThreadPoolExecutor inspector;

    public IgniteClusterObserver(Ignite ignite, StructuresProperties structuresProperties) {
        this.ignite = ignite;
        this.properties = structuresProperties.getClusterObserver();
    }

    @PostConstruct
    public void start() {
        startedAtNanos = System.nanoTime();
        scheduler = newDaemonScheduler("structures-cluster-observer");
        // Inspections run on their own bounded pool so a slow or wedged cache read can
        // neither delay topology polling nor prevent later inspections from running. If
        // every thread is stuck, further inspections are skipped and logged - a skipped
        // inspection is itself a signal that cluster reads are hanging.
        inspector = new ThreadPoolExecutor(INSPECTOR_THREADS, INSPECTOR_THREADS,
                                           0L, TimeUnit.MILLISECONDS,
                                           new ArrayBlockingQueue<>(INSPECTOR_QUEUE_DEPTH),
                                           runnable -> {
                                               Thread thread = new Thread(runnable, "structures-cluster-inspector");
                                               thread.setDaemon(true);
                                               return thread;
                                           },
                                           (runnable, executor) -> log.warn(
                                                   "Skipping a routing inspection: previous inspections are "
                                                   + "still running, which usually means Ignite reads are "
                                                   + "blocked (activeInspections={})",
                                                   ((ThreadPoolExecutor) executor).getActiveCount()));

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
                scheduler.schedule(() -> {
                    try {
                        inspector.execute(() -> reportStaleRoutingState(departedNodeId, departedAtNanos, finalSample));
                    } catch (RejectedExecutionException e) {
                        log.debug("Routing inspection not dispatched, observer is shutting down");
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("Routing inspection not scheduled, observer is shutting down");
                return;
            }
        }
    }

    /**
     * Inspect this node's local view of the vertx routing caches for entries that still
     * reference a departed node.
     * <p>
     * Deliberately tolerant rather than precise. Reads are local and time-bounded, and any
     * failure - a timeout, a cursor invalidated by the rebalance a departure triggers, a
     * cache handle that is not ready - is reported and then simply retried by the next
     * sample or the next departure. Transient problems resolve themselves that way;
     * a genuinely stuck cluster shows up as the same complaint repeating, which is the
     * signal worth acting on. Counts are this node's local view only (primary and backup
     * copies, and entries in partitions being rebalanced), so the same entry can appear in
     * more than one pod's log - correlate across pods rather than summing.
     */
    private void reportStaleRoutingState(String departedNodeId, long departedAtNanos, boolean finalSample) {
        if (closed) {
            return;
        }
        // Real elapsed time, not the nominal schedule: a sample can run late when the
        // cluster is unhealthy, which is exactly when the timestamp matters
        long afterMs = elapsedMs(departedAtNanos);
        try {
            // cacheNames() is a local metadata read; it also keeps us from asking for a
            // cache this node has never seen
            Collection<String> cacheNames = ignite.cacheNames();
            boolean nodeInfoAvailable = cacheNames.contains(NODE_INFO_CACHE);
            boolean subsAvailable = cacheNames.contains(SUBS_CACHE);

            if (!nodeInfoAvailable && !subsAvailable) {
                logInspectionIncomplete(finalSample, afterMs, departedNodeId,
                                        "neither " + NODE_INFO_CACHE + " nor " + SUBS_CACHE
                                        + " exists on this node", null);
                return;
            }

            // Each half is independent: a failure in one must not cost the other. The
            // nodeInfo verdict in particular is the direct evidence of a failed cleanup
            // election, so it is reported whenever it can be obtained.
            InspectionOutcome<Boolean> nodeInfo = nodeInfoAvailable
                    ? checkNodeInfoPresent(departedNodeId)
                    : InspectionOutcome.unavailable("cache not present on this node");
            InspectionOutcome<SubsScanResult> subs = subsAvailable
                    ? scanSubs(departedNodeId)
                    : InspectionOutcome.unavailable("cache not present on this node");

            boolean nodeInfoStale = Boolean.TRUE.equals(nodeInfo.value);
            SubsScanResult scan = subs.value;
            int staleSubs = scan != null ? scan.staleEntries : 0;

            String coverage = String.format("nodeInfoCheck=%s, subsScan=%s",
                                            nodeInfo.describe(),
                                            scan != null ? scan.describe() : subs.describe());

            if (nodeInfoStale || staleSubs > 0) {
                String detail = String.format(
                        "nodeInfoStillPresent=%s, staleSubscriptionEntriesHere=%d, sampleAddresses=%s, %s",
                        nodeInfoStale, staleSubs, scan != null ? scan.addresses : List.of(), coverage);
                if (finalSample) {
                    log.warn("Stale routing state remains {} ms after departure of node {}: {}. Counts are "
                             + "this node's local view and can overlap other pods, so correlate rather "
                             + "than sum. Event bus sends to these addresses can fail with 'Not a member "
                             + "of the cluster' until the handlers re-register.",
                             afterMs, departedNodeId, detail);
                } else {
                    log.info("Routing cleanup still in progress {} ms after departure of node {}: {}",
                             afterMs, departedNodeId, detail);
                }
            } else if (nodeInfo.complete() && subs.complete()) {
                log.info("Routing caches are clean (local view) {} ms after departure of node {}: {}",
                         afterMs, departedNodeId, coverage);
            } else {
                logInspectionIncomplete(finalSample, afterMs, departedNodeId,
                                        "no stale entries seen, but the inspection did not complete "
                                        + "fully so this is NOT an all-clear (" + coverage + ")",
                                        null);
            }
        } catch (Throwable t) {
            // Throwable, not Exception: an Error from Ignite internals (an iterator over a
            // partition being evicted, for instance) must not vanish into the executor,
            // leaving a silence that reads like a clean result
            if (closed) {
                log.debug("Routing inspection for node {} abandoned during shutdown", departedNodeId, t);
            } else {
                logInspectionIncomplete(finalSample, afterMs, departedNodeId,
                                        "the inspection failed", t);
            }
        }
    }

    /**
     * Incomplete inspections are expected while a cluster is rebalancing, so early samples
     * report at INFO and only the final sample warns. Either way the reason is always
     * visible at production log levels: the whole point is that a failed check is never
     * mistaken for a clean one.
     */
    private void logInspectionIncomplete(boolean finalSample, long afterMs, String departedNodeId,
                                         String reason, Throwable cause) {
        if (finalSample) {
            log.warn("Inconclusive routing inspection {} ms after departure of node {}: {}",
                     afterMs, departedNodeId, reason, cause);
        } else {
            log.info("Routing inspection incomplete {} ms after departure of node {}: {} "
                     + "(will retry on the next sample)",
                     afterMs, departedNodeId, reason, cause);
        }
    }

    private InspectionOutcome<Boolean> checkNodeInfoPresent(String departedNodeId) {
        try {
            IgniteCache<String, Object> cache = ignite.cache(NODE_INFO_CACHE);
            if (cache == null) {
                return InspectionOutcome.unavailable("cache handle not available");
            }
            // Async with an explicit timeout: containsKey on a PARTITIONED cache is a
            // distributed read that can block on partition map exchange indefinitely,
            // which is precisely the condition being diagnosed
            Boolean present = cache.containsKeyAsync(departedNodeId)
                                   .get(inspectionTimeoutMs(), TimeUnit.MILLISECONDS);
            return InspectionOutcome.complete(present);
        } catch (Throwable t) {
            return InspectionOutcome.failed(describeFailure(t));
        }
    }

    private InspectionOutcome<SubsScanResult> scanSubs(String departedNodeId) {
        SubsScanResult result = new SubsScanResult();
        Iterator<Cache.Entry<Object, Object>> iterator = null;
        try {
            IgniteCache<Object, Object> cache = ignite.cache(SUBS_CACHE);
            if (cache == null) {
                return InspectionOutcome.unavailable("cache handle not available");
            }
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(inspectionTimeoutMs());
            int maxEntries = maxEntriesScanned();

            // Local reads only: no distributed query while the cluster is rebalancing.
            // Both peek modes are used deliberately - a departure's stale entries can sit
            // in either, and over-reporting on one pod is preferable to missing them.
            iterator = cache.withKeepBinary()
                            .localEntries(CachePeekMode.PRIMARY, CachePeekMode.BACKUP)
                            .iterator();
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
                if (departedNodeId.equals(key.field("nodeId"))) {
                    result.staleEntries++;
                    if (result.addresses.size() < MAX_STALE_ADDRESSES_LOGGED) {
                        result.addresses.add(String.valueOf(key.field("address")));
                    }
                }
            }
            return InspectionOutcome.complete(result);
        } catch (Throwable t) {
            // A cursor invalidated by partition eviction is normal during the rebalance a
            // departure triggers; report what was gathered and let the next sample retry
            result.failure = describeFailure(t);
            return InspectionOutcome.partial(result, result.failure);
        } finally {
            closeQuietly(iterator);
        }
    }

    private static String describeFailure(Throwable t) {
        return t.getClass().getSimpleName()
               + (t.getMessage() != null ? ": " + t.getMessage() : "");
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
            lastBelowMinimumReportNanos = -1;
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
        if (belowForMs < reportBelowMinimumAfterMs()) {
            return;
        }
        // Repeated hourly rather than once per episode: a node orphaned days ago must still
        // be visible in a recent log window, not only in one line from when it happened
        if (lastBelowMinimumReportNanos == -1
                || elapsedMs(lastBelowMinimumReportNanos) >= BELOW_MINIMUM_REPORT_INTERVAL_MS) {
            lastBelowMinimumReportNanos = System.nanoTime();
            log.error("Server topology has been at {} nodes, below the minimum of {}, for {} ms. "
                      + "This node is very likely orphaned or split-brained and would need a restart "
                      + "to rejoin the cluster (repeated at most every {} ms while it persists)",
                      serverNodes, minimumClusterSize, belowForMs, BELOW_MINIMUM_REPORT_INTERVAL_MS);
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
     * Outcome of one half of an inspection: whether it completed, and if not, why. Nothing
     * is ever inferred from a failure - an incomplete half simply prevents an all-clear.
     */
    private record InspectionOutcome<T>(T value, boolean complete, String detail) {

        private static <T> InspectionOutcome<T> complete(T value) {
            return new InspectionOutcome<>(value, true, "ok");
        }

        private static <T> InspectionOutcome<T> partial(T value, String detail) {
            return new InspectionOutcome<>(value, false, detail);
        }

        private static <T> InspectionOutcome<T> unavailable(String detail) {
            return new InspectionOutcome<>(null, false, detail);
        }

        private static <T> InspectionOutcome<T> failed(String detail) {
            return new InspectionOutcome<>(null, false, detail);
        }

        private String describe() {
            return detail;
        }
    }

    /**
     * What a local __vertx.subs scan saw. Counts are this node's local view (primary and
     * backup copies, and partitions mid-rebalance), so they can overlap other pods.
     */
    private static final class SubsScanResult {
        private int scanned;
        private int staleEntries;
        private int unreadableKeys;
        private boolean truncated;
        private boolean timedOut;
        private String failure;
        private final List<String> addresses = new ArrayList<>();

        private String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("localEntriesScanned=").append(scanned);
            if (truncated) {
                sb.append(", truncated=true");
            }
            if (timedOut) {
                sb.append(", timedOut=true");
            }
            if (unreadableKeys > 0) {
                sb.append(", unreadableKeys=").append(unreadableKeys);
            }
            if (failure != null) {
                sb.append(", failed=").append(failure);
            }
            return sb.toString();
        }
    }
}
