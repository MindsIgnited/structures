package org.kinotic.structures.tests.core.cache;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCluster;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.future.IgniteFutureImpl;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteRunnable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.api.config.ContinuumProperties;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.internal.cache.ClusterCacheEvictionService;
import org.kinotic.structures.internal.cache.CacheEvictionEvent;
import org.kinotic.structures.internal.cache.events.CacheEvictionSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Structure and named query changes compose on the {@link ClusterCacheEvictionService} evict methods from Elasticsearch
 * client and Vert.x threads, so the cluster broadcast, its timeout and the retry delay must never block the caller,
 * and the caller's chain must continue on a Vert.x context, never on the Ignite thread that completed the broadcast.
 */
class ClusterCacheEvictionServiceTest {

    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> ATTEMPTS = AttributeKey.stringKey("attempts");

    private static Vertx vertx;

    private final List<GridFutureAdapter<Void>> broadcasts = new ArrayList<>();
    // The Ignite calls made, and whether each ran on an event loop, where none of them may run since they can block
    private final List<String> igniteCallsOnAnEventLoop = new CopyOnWriteArrayList<>();
    private final StructuresProperties structuresProperties = new StructuresProperties();

    private ContinuumProperties continuumProperties;
    private ApplicationEventPublisher eventPublisher;
    private ClusterGroup servers;
    private IgniteCompute compute;
    private InMemoryMetricReader metricReader;
    private SdkMeterProvider meterProvider;
    private ClusterCacheEvictionService service;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @BeforeEach
    void setUp() {
        structuresProperties.getClusterEviction()
                            .setMaxCacheSyncRetryAttempts(3)
                            .setCacheSyncRetryDelayMs(10L)
                            .setCacheSyncTimeoutMs(5_000L);

        continuumProperties = mock(ContinuumProperties.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        servers = mock(ClusterGroup.class);
        when(servers.nodes()).thenReturn(List.of(mock(ClusterNode.class)));
        IgniteCluster cluster = mock(IgniteCluster.class);
        when(cluster.forServers()).thenAnswer(invocation -> {
            recordIgniteCall("forServers");
            return servers;
        });
        compute = mock(IgniteCompute.class);
        Ignite ignite = mock(Ignite.class);
        when(ignite.cluster()).thenReturn(cluster);
        when(ignite.compute(servers)).thenReturn(compute);

        // Each broadcast gets a future the test completes by hand, and that really cancels. Runnable::run matches
        // Continuum's Ignite asyncContinuationExecutor: listeners run on whichever thread completes the future.
        when(compute.broadcastAsync(any(IgniteRunnable.class))).thenAnswer(invocation -> {
            recordIgniteCall("broadcastAsync");
            GridFutureAdapter<Void> broadcast = new GridFutureAdapter<>() {
                @Override
                public boolean cancel() {
                    recordIgniteCall("cancel");
                    return onCancelled();
                }
            };
            synchronized (broadcasts) {
                broadcasts.add(broadcast);
            }
            return (IgniteFuture<Void>) new IgniteFutureImpl<>(broadcast, Runnable::run);
        });

        metricReader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();

        service = new ClusterCacheEvictionService(continuumProperties,
                                                  structuresProperties,
                                                  ignite,
                                                  vertx,
                                                  eventPublisher,
                                                  OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build());
    }

    @AfterEach
    void tearDown() {
        meterProvider.close();
    }

    @Test
    void returnsBeforeTheBroadcastCompletes() throws Exception {
        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        verify(eventPublisher).publishEvent(any(CacheEvictionEvent.class));
        assertFalse(eviction.isDone(), "the caller must not wait on the broadcast");
        awaitBroadcasts(1);

        broadcast(0).onDone();
        eviction.get(5, TimeUnit.SECONDS);
        assertResult("success", "1");
    }

    @Test
    void continuesOnAnEventLoopNotTheIgniteThread() throws Exception {
        AtomicReference<String> continuedOn = new AtomicReference<>();
        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person")
                                                  .thenRun(() -> continuedOn.set(Thread.currentThread().getName()));

        awaitBroadcasts(1);
        Thread igniteThread = new Thread(() -> broadcast(0).onDone(), "ignite-pub-test");
        igniteThread.start();
        igniteThread.join();

        eviction.get(5, TimeUnit.SECONDS);
        assertTrue(continuedOn.get().startsWith("vert.x-eventloop-thread"), "continued on " + continuedOn.get());
    }

    @Test
    void continuesOnTheCallersOwnContext() throws Exception {
        Context callerContext = vertx.getOrCreateContext();
        CompletableFuture<Context> continuedOn = new CompletableFuture<>();
        callerContext.runOnContext(v -> service.evictModifiedStructure("app", "app.Person")
                                               .thenRun(() -> continuedOn.complete(Vertx.currentContext())));

        awaitBroadcasts(1);
        new Thread(() -> broadcast(0).onDone(), "ignite-pub-test").start();

        assertSame(callerContext, continuedOn.get(5, TimeUnit.SECONDS));
    }

    @Test
    void retriesAFailedBroadcast() throws Exception {
        CompletableFuture<Void> eviction = service.evictDeletedNamedQuery("app", "app.Person", "nq");

        awaitBroadcasts(1);
        broadcast(0).onDone(new RuntimeException("node left"));
        awaitBroadcasts(2);
        assertFalse(eviction.isDone());

        broadcast(1).onDone();
        eviction.get(5, TimeUnit.SECONDS);
        assertEquals(2, broadcastCount());
        assertResult("success", "2");
    }

    @Test
    void cancelsATimedOutBroadcastAndGivesUpWithoutFailing() throws Exception {
        structuresProperties.getClusterEviction()
                            .setMaxCacheSyncRetryAttempts(2)
                            .setCacheSyncTimeoutMs(50L);

        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        // Neither broadcast is ever answered: both time out and are cancelled, and the change still goes through
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
        assertEquals(2, broadcastCount());
        // The cancel runs on a worker thread, so it can land after the eviction completes
        awaitCancelled(0);
        awaitCancelled(1);
        assertResult("failure", "2");
    }

    @Test
    void countsAFailureWhenTheServersDisappearBeforeTheRetry() throws Exception {
        when(servers.nodes()).thenReturn(List.of(mock(ClusterNode.class)), List.of());

        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");
        awaitBroadcasts(1);
        broadcast(0).onDone(new RuntimeException("node left"));

        eviction.get(5, TimeUnit.SECONDS);
        assertEquals(1, broadcastCount());
        assertResult("failure", "1");
    }

    @Test
    void stillBroadcastsWhenALocalListenerFails() throws Exception {
        // CacheEvictionEvent is an ApplicationEvent, so the service calls this overload, not publishEvent(Object)
        doThrow(new IllegalStateException("listener bug")).when(eventPublisher).publishEvent(any(ApplicationEvent.class));

        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        awaitBroadcasts(1);
        broadcast(0).onDone();
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
    }

    @Test
    void neverCallsIgniteOnAnEventLoop() throws Exception {
        structuresProperties.getClusterEviction()
                            .setMaxCacheSyncRetryAttempts(2)
                            .setCacheSyncTimeoutMs(50L);

        // Started from an event loop, and every retry and cancel is driven from one too, by the timers
        CompletableFuture<Void> eviction = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(v -> service.evictModifiedStructure("app", "app.Person")
                                                            .whenComplete((r, e) -> eviction.complete(null)));

        eviction.get(5, TimeUnit.SECONDS);
        awaitIgniteCalls(6); // forServers, broadcastAsync and cancel, for each of the 2 attempts
        assertEquals(List.of(), igniteCallsOnAnEventLoop.stream().filter(call -> call.endsWith("on an event loop")).toList());
    }

    @Test
    void evictsOnlyLocallyWhenTheBroadcastIsTurnedOff() {
        structuresProperties.getClusterEviction().setMaxCacheSyncRetryAttempts(0);
        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        verify(eventPublisher).publishEvent(any(CacheEvictionEvent.class));
        assertTrue(eviction.isDone());
        verifyNoInteractions(compute);
    }

    @Test
    void doesNotFailTheChangeWhenTheClusterEvictionCannotStart() throws Exception {
        structuresProperties.getClusterEviction().setMaxCacheSyncRetryAttempts(null);
        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        verify(eventPublisher).publishEvent(any(CacheEvictionEvent.class));
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
        verifyNoInteractions(compute);
    }

    @Test
    void evictsOnlyLocallyWhenClusteringIsDisabled() {
        when(continuumProperties.isDisableClustering()).thenReturn(true);
        CompletableFuture<Void> eviction = service.evictModifiedStructure("app", "app.Person");

        verify(eventPublisher).publishEvent(any(CacheEvictionEvent.class));
        assertTrue(eviction.isDone());
        verifyNoInteractions(compute);
    }

    @Test
    void publishesTheLocalEventForEachChange() {
        structuresProperties.getClusterEviction().setMaxCacheSyncRetryAttempts(0);

        service.evictModifiedStructure("app", "app.Person");
        service.evictDeletedStructure("app", "app.Person");
        service.evictModifiedNamedQuery("app", "app.Person", "nq");
        service.evictDeletedNamedQuery("app", "app.Person", "nq");

        ArgumentCaptor<CacheEvictionEvent> events = ArgumentCaptor.forClass(CacheEvictionEvent.class);
        verify(eventPublisher, times(4)).publishEvent(events.capture());
        assertEquals(List.of("STRUCTURE MODIFY app:app.Person:null",
                             "STRUCTURE DELETE app:app.Person:null",
                             "NAMED_QUERY MODIFY app:app.Person:nq",
                             "NAMED_QUERY DELETE app:app.Person:nq"),
                     events.getAllValues().stream()
                           .peek(event -> assertEquals(CacheEvictionSource.LOCAL_MESSAGE, event.getEvictionSource()))
                           .map(event -> event.getEvictionSourceType() + " " + event.getEvictionOperation() + " "
                                         + event.getApplicationId() + ":" + event.getStructureId() + ":" + event.getNamedQueryId())
                           .toList());
    }

    /**
     * Asserts the eviction recorded exactly one cluster result, with this outcome and attempt count.
     */
    private void assertResult(String result, String attempts) {
        List<LongPointData> points = metricReader.collectAllMetrics().stream()
                                                 .filter(metric -> metric.getName().equals("cache.eviction.cluster.results"))
                                                 .map(MetricData::getLongSumData)
                                                 .flatMap(sum -> sum.getPoints().stream())
                                                 .toList();
        assertEquals(1, points.size(), "cluster results recorded: " + points);
        assertEquals(result, points.getFirst().getAttributes().get(RESULT));
        assertEquals(attempts, points.getFirst().getAttributes().get(ATTEMPTS));
        assertEquals(1, points.getFirst().getValue());
    }

    private void recordIgniteCall(String call) {
        igniteCallsOnAnEventLoop.add(call + (Context.isOnEventLoopThread() ? " on an event loop" : " off the event loop"));
    }

    private void awaitIgniteCalls(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (igniteCallsOnAnEventLoop.size() < count) {
            assertTrue(System.nanoTime() < deadline, "expected " + count + " Ignite calls, saw " + igniteCallsOnAnEventLoop);
            Thread.sleep(5);
        }
    }

    /**
     * Waits for a broadcast to be cancelled, since a timed out broadcast must not stay queued on the cluster.
     */
    private void awaitCancelled(int index) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!broadcast(index).isCancelled()) {
            assertTrue(System.nanoTime() < deadline, "broadcast " + index + " was never cancelled");
            Thread.sleep(5);
        }
    }

    private GridFutureAdapter<Void> broadcast(int index) {
        synchronized (broadcasts) {
            return broadcasts.get(index);
        }
    }

    private int broadcastCount() {
        synchronized (broadcasts) {
            return broadcasts.size();
        }
    }

    private void awaitBroadcasts(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (broadcastCount() < count) {
            assertTrue(System.nanoTime() < deadline, "expected " + count + " broadcasts, saw " + broadcastCount());
            Thread.sleep(5);
        }
    }

}
