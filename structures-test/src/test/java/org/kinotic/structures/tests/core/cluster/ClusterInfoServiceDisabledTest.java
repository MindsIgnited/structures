package org.kinotic.structures.tests.core.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.services.cluster.ClusterInfoService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * {@link ClusterInfoService} is published, so whenever its bean exists the service is reachable by any
 * caller the gateway has authenticated. It reports node ids, addresses and topology and performs no
 * authorization of its own, so it must stay off unless a deployment opts in with
 * {@code structures.cluster-info.enabled}.
 * <p>
 * This pins that default. The test profile does not set the property, so the bean must not be present,
 * and a change that made it unconditional would fail here rather than quietly exposing cluster
 * internals in production.
 */
class ClusterInfoServiceDisabledTest extends ElasticTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void clusterInfoServiceIsNotPublishedUnlessEnabled() {
        assertEquals(0,
                     applicationContext.getBeanNamesForType(ClusterInfoService.class).length,
                     "ClusterInfoService must not be registered unless structures.cluster-info.enabled is true");
    }
}
