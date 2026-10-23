package org.kinotic.structures.internal.cache;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.kinotic.structures.internal.cache.events.CacheEvictionSource;
import org.kinotic.structures.internal.cache.events.EvictionSourceOperation;
import org.kinotic.structures.internal.cache.events.EvictionSourceType;

import java.time.Instant;

import org.springframework.context.ApplicationEvent;

/**
 * Cache eviction event specifically for caches that need to be evicted
 * This is used when a structure or named query is updated and related caches need to be evicted
 *
 * Only {@link ClusterCacheEvictionService} creates local events, and only {@link ClusterCacheEvictionTask} creates
 * cluster events, so they are the only code that can publish one. A local event published anywhere else would evict
 * this node's caches and leave the other nodes' stale; changes evict through the service instead.
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class CacheEvictionEvent extends ApplicationEvent {

    private final EvictionSourceType evictionSourceType;
    private final CacheEvictionSource evictionSource;
    private final EvictionSourceOperation evictionOperation;
    private final Instant eventTimestamp;

    /**
     * Id of the structure that is being evicted
     */
    private final String structureId;
    /**
     * Id of the named query that is being evicted, if any
     */
    private final String namedQueryId;
    /**
     * Application id of the structure or associated named query that is being evicted
     */
    private final String applicationId;

    CacheEvictionEvent(CacheEvictionSource evictionSource, EvictionSourceType evictionSourceType, EvictionSourceOperation evictionOperation, String applicationId, String structureId, String namedQueryId) {
        super(evictionSource); // Use evictionSource as the Spring event source
        this.evictionSource = evictionSource;
        this.evictionSourceType = evictionSourceType;
        this.evictionOperation = evictionOperation;
        this.eventTimestamp = Instant.now();
        this.applicationId = applicationId;
        this.structureId = structureId;
        this.namedQueryId = namedQueryId;
    }

    static CacheEvictionEvent localModifiedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return new CacheEvictionEvent(CacheEvictionSource.LOCAL_MESSAGE, EvictionSourceType.NAMED_QUERY, EvictionSourceOperation.MODIFY, applicationId, structureId, namedQueryId);
    }

    static CacheEvictionEvent localDeletedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return new CacheEvictionEvent(CacheEvictionSource.LOCAL_MESSAGE, EvictionSourceType.NAMED_QUERY, EvictionSourceOperation.DELETE, applicationId, structureId, namedQueryId);
    }

    static CacheEvictionEvent localModifiedStructure(String applicationId, String structureId) {
        return new CacheEvictionEvent(CacheEvictionSource.LOCAL_MESSAGE, EvictionSourceType.STRUCTURE, EvictionSourceOperation.MODIFY, applicationId, structureId, null);
    }

    static CacheEvictionEvent localDeletedStructure(String applicationId, String structureId) {
        return new CacheEvictionEvent(CacheEvictionSource.LOCAL_MESSAGE, EvictionSourceType.STRUCTURE, EvictionSourceOperation.DELETE, applicationId, structureId, null);
    }

    static CacheEvictionEvent clusterModifiedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return new CacheEvictionEvent(CacheEvictionSource.CLUSTER_MESSAGE, EvictionSourceType.NAMED_QUERY, EvictionSourceOperation.MODIFY, applicationId, structureId, namedQueryId);
    }

    static CacheEvictionEvent clusterDeletedNamedQuery(String applicationId, String structureId, String namedQueryId) {
        return new CacheEvictionEvent(CacheEvictionSource.CLUSTER_MESSAGE, EvictionSourceType.NAMED_QUERY, EvictionSourceOperation.DELETE, applicationId, structureId, namedQueryId);
    }

    static CacheEvictionEvent clusterModifiedStructure(String applicationId, String structureId) {
        return new CacheEvictionEvent(CacheEvictionSource.CLUSTER_MESSAGE, EvictionSourceType.STRUCTURE, EvictionSourceOperation.MODIFY, applicationId, structureId, null);
    }

    static CacheEvictionEvent clusterDeletedStructure(String applicationId, String structureId) {
        return new CacheEvictionEvent(CacheEvictionSource.CLUSTER_MESSAGE, EvictionSourceType.STRUCTURE, EvictionSourceOperation.DELETE, applicationId, structureId, null);
    }
}
