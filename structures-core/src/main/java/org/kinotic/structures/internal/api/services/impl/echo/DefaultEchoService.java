package org.kinotic.structures.internal.api.services.impl.echo;

import java.time.Duration;
import java.util.UUID;

import org.kinotic.structures.api.domain.echo.EchoResponse;
import org.kinotic.structures.api.services.echo.EchoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link EchoService}.
 * <p>
 * The instance id is generated once per JVM and is deliberately unrelated to the Ignite node id or
 * anything else about the deployment, so it distinguishes instances without describing them.
 * <p>
 * The trace line is what lets a test attribute a call to the instance that actually ran it, from the
 * server's own record rather than from the response.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "structures.echo-service", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultEchoService implements EchoService {

    private final String instanceId = UUID.randomUUID().toString();

    @Override
    public Mono<EchoResponse> echo(String message) {
        log.trace("Echo handled by instance {}", instanceId);
        return Mono.just(new EchoResponse(message, instanceId));
    }

    @Override
    public Mono<EchoResponse> echoAfter(String message, long delayMs) {
        long delay = Math.max(0, Math.min(delayMs, MAX_ECHO_DELAY_MS));
        log.trace("Delayed echo of {} ms accepted by instance {}", delay, instanceId);
        return Mono.delay(Duration.ofMillis(delay))
                   .map(ignored -> new EchoResponse(message, instanceId));
    }
}
