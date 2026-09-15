package org.kinotic.structures.api.services.echo;

import org.kinotic.continuum.api.annotations.Publish;
import org.kinotic.structures.api.domain.echo.EchoResponse;

import reactor.core.publisher.Mono;

/**
 * Returns what it is given, along with an opaque identifier for the instance that handled the call.
 * <p>
 * This exists so tests can tell which instance served a request, which is how local delivery of service
 * RPC is verified. It deliberately exposes nothing else: there is no cluster topology, no node id, no
 * addresses, and no access to any data. Everything it can tell a caller is either what that caller
 * already sent or a random value that means nothing outside a single test run.
 * <p>
 * It is still off unless {@code structures.echo-service.enabled} is true, since there is no reason to
 * carry an endpoint in production that only tests use. The point of keeping it this trivial is that
 * the gate is defence in depth rather than the only thing standing between a caller and something
 * sensitive.
 */
@Publish
public interface EchoService {

    /**
     * Echoes the supplied message back.
     *
     * @param message to return unchanged
     * @return the message and the id of the instance that handled the call
     */
    Mono<EchoResponse> echo(String message);

    /**
     * Echoes the supplied message back after a delay.
     * <p>
     * Exists so a test can hold a request open on a specific server instance long enough to take that
     * instance down underneath it, which is how the client's behaviour on node loss is exercised. The
     * delay is capped server side so the endpoint cannot be used to tie up an instance for long.
     *
     * @param message to return unchanged
     * @param delayMs to wait before replying, capped at {@value #MAX_ECHO_DELAY_MS}
     * @return the message and the id of the instance that handled the call
     */
    Mono<EchoResponse> echoAfter(String message, long delayMs);

    /** Upper bound on {@link #echoAfter}'s delay. */
    long MAX_ECHO_DELAY_MS = 60_000L;
}
