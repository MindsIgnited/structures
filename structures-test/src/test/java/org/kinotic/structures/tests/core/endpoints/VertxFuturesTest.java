package org.kinotic.structures.tests.core.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kinotic.structures.internal.utils.VertxFutures;

/**
 * The OpenAPI router hands service futures - completed on Elasticsearch and Ignite threads - to
 * {@link VertxFutures#onContext} so the response is written from the request's own context. These
 * pin the contract the unmaintained vertx-completable-future library used to provide.
 */
class VertxFuturesTest {

    private static Vertx vertx;

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close();
    }

    @Test
    void dependentStagesRunOnTheContextTheFutureWasAdaptedOn() throws Exception {
        CompletableFuture<String> source = new CompletableFuture<>();
        AtomicReference<Context> adaptedOn = new AtomicReference<>();
        CompletableFuture<Context> ranOn = new CompletableFuture<>();

        vertx.runOnContext(v -> {
            adaptedOn.set(Vertx.currentContext());
            VertxFutures.onContext(vertx, source).thenAccept(value -> ranOn.complete(Vertx.currentContext()));
        });

        // complete it somewhere that is not a Vert.x thread at all
        Thread completer = new Thread(() -> source.complete("done"));
        completer.start();
        completer.join();

        assertSame(adaptedOn.get(), ranOn.get(5, TimeUnit.SECONDS), "the dependent stage ran on the adapting context");
    }

    @Test
    void failuresArriveOnTheContextWithTheOriginalCause() {
        IllegalStateException failure = new IllegalStateException("boom");
        // a dependent stage, so the source's own failure is already wrapped in a CompletionException
        CompletableFuture<String> source = CompletableFuture.<String>failedFuture(failure).thenApply(s -> s + "!");

        CompletableFuture<String> adapted = VertxFutures.onContext(vertx, source);

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> adapted.get(5, TimeUnit.SECONDS));
        assertSame(failure, thrown.getCause(), "the original cause, not a CompletionException around it");
    }

    @Test
    void valuesPassThroughUnchanged() throws Exception {
        assertEquals("value", VertxFutures.onContext(vertx, CompletableFuture.completedFuture("value")).get(5, TimeUnit.SECONDS));
    }
}
