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
import org.kinotic.structures.internal.cache.events.CacheEvictionEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Structure and named query changes compose on {@link ClusterCacheEvictionService#evictCaches} from Elasticsearch
 * client and Vert.x threads, so the cluster broadcast, its timeout and the retry delay must never block the caller,
 * and the caller's chain must continue on a Vert.x context, never on the Ignite thread that completed the broadcast.
 */
class ClusterCacheEvictionServiceTest {

    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> ATTEMPTS = AttributeKey.stringKey("attempts");

    private static Vertx vertx;

    private final List<GridFutureAdapter<Void>> broadcasts = new ArrayList<>();
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
        when(cluster.forServers()).thenReturn(servers);
        compute = mock(IgniteCompute.class);
        Ignite ignite = mock(Ignite.class);
        when(ignite.cluster()).thenReturn(cluster);
        when(ignite.compute(servers)).thenReturn(compute);

        // Each broadcast gets a future the test completes by hand, and that really cancels. Runnable::run matches
        // Continuum's Ignite asyncContinuationExecutor: listeners run on whichever thread completes the future.
        when(compute.broadcastAsync(any(IgniteRunnable.class))).thenAnswer(invocation -> {
            GridFutureAdapter<Void> broadcast = new GridFutureAdapter<>() {
                @Override
                public boolean cancel() {
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
        CacheEvictionEvent event = CacheEvictionEvent.localModifiedStructure("app", "app.Person");

        CompletableFuture<Void> eviction = service.evictCaches(event);

        verify(eventPublisher).publishEvent(event);
        assertFalse(eviction.isDone(), "the caller must not wait on the broadcast");
        assertEquals(1, broadcastCount());

        broadcast(0).onDone();
        eviction.get(5, TimeUnit.SECONDS);
        assertResult("success", "1");
    }

    @Test
    void continuesOnAnEventLoopNotTheIgniteThread() throws Exception {
        AtomicReference<String> continuedOn = new AtomicReference<>();
        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"))
                                                  .thenRun(() -> continuedOn.set(Thread.currentThread().getName()));

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
        callerContext.runOnContext(v -> service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"))
                                               .thenRun(() -> continuedOn.complete(Vertx.currentContext())));

        awaitBroadcasts(1);
        new Thread(() -> broadcast(0).onDone(), "ignite-pub-test").start();

        assertSame(callerContext, continuedOn.get(5, TimeUnit.SECONDS));
    }

    @Test
    void retriesAFailedBroadcast() throws Exception {
        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localDeletedNamedQuery("app", "app.Person", "nq"));

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

        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"));

        // Neither broadcast is ever answered: both time out and are cancelled, and the change still goes through
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
        assertEquals(2, broadcastCount());
        assertTrue(broadcast(0).isCancelled(), "a timed out broadcast must not stay queued on the cluster");
        assertTrue(broadcast(1).isCancelled());
        assertResult("failure", "2");
    }

    @Test
    void countsAFailureWhenTheServersDisappearBeforeTheRetry() throws Exception {
        when(servers.nodes()).thenReturn(List.of(mock(ClusterNode.class)), List.of());

        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"));
        broadcast(0).onDone(new RuntimeException("node left"));

        eviction.get(5, TimeUnit.SECONDS);
        assertEquals(1, broadcastCount());
        assertResult("failure", "1");
    }

    @Test
    void stillBroadcastsWhenALocalListenerFails() throws Exception {
        doThrow(new IllegalStateException("listener bug")).when(eventPublisher).publishEvent(any(Object.class));

        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"));

        assertEquals(1, broadcastCount());
        broadcast(0).onDone();
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
    }

    @Test
    void evictsOnlyLocallyWhenClusteringIsDisabled() {
        when(continuumProperties.isDisableClustering()).thenReturn(true);
        CacheEvictionEvent event = CacheEvictionEvent.localModifiedStructure("app", "app.Person");

        CompletableFuture<Void> eviction = service.evictCaches(event);

        verify(eventPublisher).publishEvent(event);
        assertTrue(eviction.isDone());
        verifyNoInteractions(compute);
    }

    @Test
    void rejectsClusterEvents() {
        assertThrows(IllegalArgumentException.class,
                     () -> service.evictCaches(CacheEvictionEvent.clusterModifiedStructure("app", "app.Person")));
        verifyNoInteractions(eventPublisher, compute);
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
