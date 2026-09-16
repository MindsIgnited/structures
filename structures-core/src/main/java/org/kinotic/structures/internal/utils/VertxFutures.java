package org.kinotic.structures.internal.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

/**
 * Bridges {@link CompletionStage}s completed on arbitrary threads back onto a Vert.x context.
 * Replaces the unmaintained vertx-completable-future library, which was compiled against Vert.x 3.
 */
public final class VertxFutures {

    private VertxFutures() {
    }

    /**
     * Returns a future that completes with the outcome of {@code source}, on the current Vert.x context
     * (or a fresh one when called off a Vert.x thread). Dependent stages registered on the returned
     * future therefore run on that context, wherever {@code source} itself was completed.
     * A {@link CompletionException} wrapping the source's failure is unwrapped, so the returned future
     * fails with the original cause.
     *
     * @param vertx  the Vert.x instance whose context to resume on
     * @param source the stage to follow
     * @param <T>    the result type
     * @return a future completed on the Vert.x context
     */
    public static <T> CompletableFuture<T> onContext(Vertx vertx, CompletionStage<T> source) {
        Context context = vertx.getOrCreateContext();
        CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, error) -> context.runOnContext(v -> {
            if (error != null) {
                result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                                             ? error.getCause()
                                             : error);
            } else {
                result.complete(value);
            }
        }));
        return result;
    }
}
