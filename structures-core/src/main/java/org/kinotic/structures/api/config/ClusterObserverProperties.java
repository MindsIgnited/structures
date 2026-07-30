package org.kinotic.structures.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Configuration for the diagnostic Ignite cluster observer.
 * <p>
 * The observer never changes behavior - it only logs - so these settings affect what is
 * reported and how often, never what the node does.
 *
 * @see StructuresProperties
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
public class ClusterObserverProperties {

    /**
     * Minimum number of server nodes expected in the topology. When above 1, episodes where
     * the topology is below this count are logged, which is the split-brain condition Ignite
     * cannot detect on its own. Set it to a majority of the replica count (floor(n/2)+1),
     * e.g. 2 for 3 replicas. The default of 1 leaves topology reporting off; membership,
     * segmentation and routing diagnostics are always active.
     */
    private Integer minimumClusterSize = 1;

    /**
     * How long the topology may stay below {@link #getMinimumClusterSize()} before it is
     * reported. Reported once per episode, with an explicit recovery line if it resolves.
     */
    private Long reportBelowMinimumAfterMs = 60_000L;

    /**
     * How long a node may run without the topology ever reaching
     * {@link #getMinimumClusterSize()} before that is reported as a warning. Slow cluster
     * formation is normal, so this only warns; see
     * {@link #getEscalateNeverReachedMinimumAfterMs()} for the point at which it is treated
     * as a real problem.
     */
    private Long reportNeverReachedMinimumAfterMs = 300_000L;

    /**
     * How long a node may run without the topology ever reaching
     * {@link #getMinimumClusterSize()} before it is reported as an error. By this point slow
     * startup is no longer a plausible explanation and the node has most likely formed its
     * own topology, which Ignite can never merge.
     */
    private Long escalateNeverReachedMinimumAfterMs = 900_000L;

    /**
     * Upper bound on any single Ignite read the observer performs. Bounded so a cluster
     * hang (the very condition being diagnosed) can never stall the observer itself.
     */
    private Long inspectionTimeoutMs = 10_000L;

    /**
     * Maximum number of local cache entries examined per inspection. A scan that hits this
     * cap is reported as inconclusive rather than clean.
     */
    private Integer maxEntriesScanned = 50_000;
}
