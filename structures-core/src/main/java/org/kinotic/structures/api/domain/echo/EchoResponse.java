package org.kinotic.structures.api.domain.echo;

/**
 * The reply from {@link org.kinotic.structures.api.services.echo.EchoService}.
 *
 * @param message the message that was sent, returned unchanged
 * @param instanceId identifies the server instance that handled the call. It is a random value
 *                   generated when the instance starts and has no relationship to the Ignite node id,
 *                   the host, or anything else about the deployment, so it is useful for telling two
 *                   instances apart and useless for learning anything about them.
 */
public record EchoResponse(String message, String instanceId) {
}
