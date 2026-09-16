package org.kinotic.structures.tests.core.echo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.services.echo.EchoService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * {@link EchoService} is published, so whenever its bean exists the service is reachable by any caller
 * the gateway has authenticated. It is trivial by design and would give nothing away if it were, but
 * an endpoint only tests use has no business being present in production, so it stays off unless a
 * deployment opts in with {@code structures.echo-service.enabled}.
 * <p>
 * This pins that default. The test profile does not set the property, so the bean must not be present.
 */
class EchoServiceDisabledTest extends ElasticTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void echoServiceIsNotPublishedUnlessEnabled() {
        assertEquals(0,
                     applicationContext.getBeanNamesForType(EchoService.class).length,
                     "EchoService must not be registered unless structures.echo-service.enabled is true");
    }
}
