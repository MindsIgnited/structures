package org.kinotic.structures.internal.config;

import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration for the vertx-ignite cluster metadata caches.
 * <p>
 * vertx-ignite stores its cluster routing metadata (__vertx.subs, __vertx.nodeInfo) in caches
 * created with getOrCreateCache and no explicit configuration, which yields PARTITIONED with
 * 0 backups. When a node fails, every entry primary-owned by it is destroyed with the topology
 * change BEFORE vertx-ignite's cleanup listener runs. That silently loses healthy routing data
 * AND breaks the cleanup election (nodeInfoMap.remove returns false on every survivor, so
 * cleanSubs is skipped), leaving stale subscriptions that surface as
 * "Not a member of the cluster" event bus send failures.
 * <p>
 * Continuum collects CacheConfiguration beans into the IgniteConfiguration, and a name ending
 * in '*' acts as an Ignite cache template, so this REPLICATED template applies to all
 * __vertx.* caches: every node keeps a full copy, nothing is lost on node failure, and the
 * cleanup election is deterministic. These maps are tiny and low-write, so the replication
 * cost is negligible.
 */
@Configuration
@ConditionalOnProperty(value = "continuum.disableClustering", havingValue = "false", matchIfMissing = true)
public class VertxClusterCacheConfiguration {

    @Bean
    public CacheConfiguration<?, ?> vertxClusterCacheTemplate() {
        CacheConfiguration<?, ?> template = new CacheConfiguration<>("__vertx.*");
        template.setCacheMode(CacheMode.REPLICATED);
        template.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        return template;
    }
}
