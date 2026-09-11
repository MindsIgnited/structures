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
 * <p>
 * <b>Diagnostic only, and off unless {@code structures.cluster-info.enabled} is true.</b> It reports
 * node ids, addresses and topology to any caller the gateway has authenticated, without an
 * authorization check of its own, so it is not something to expose on a production deployment. It
 * exists for tests that need to attribute work to a specific node.
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
