package org.kinotic.structures.api.services.cluster;

import org.kinotic.continuum.api.annotations.Publish;
import org.kinotic.structures.api.domain.cluster.ClusterInfo;

import reactor.core.publisher.Mono;

/**
 * Provides information about the ignite structures cluster.
 * <p>
 * {@link ClusterInfo#getLocalNodeId()} identifies the node that actually executed the call rather
 * than the node the caller is connected to, so a caller can tell whether its request was served
 * locally or dispatched elsewhere in the cluster.
 */
@Publish
public interface ClusterInfoService {
    
    /**
     * Returns the information about the ignite structures cluster.
     * 
     * @return the information about the ignite structures cluster
     */
    Mono<ClusterInfo> getClusterInfo();

}
