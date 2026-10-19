package org.kinotic.structures.tests.core.cache;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCluster;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.future.IgniteFutureImpl;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteRunnable;
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
 * and the caller's chain must not continue on the Ignite thread that completed the broadcast.
 */
class ClusterCacheEvictionServiceTest {

    private final List<GridFutureAdapter<Void>> broadcasts = new ArrayList<>();
    private final StructuresProperties structuresProperties = new StructuresProperties();

    private ContinuumProperties continuumProperties;
    private ApplicationEventPublisher eventPublisher;
    private IgniteCompute compute;
    private ClusterCacheEvictionService service;

    @BeforeEach
    void setUp() {
        structuresProperties.getClusterEviction()
                            .setMaxCacheSyncRetryAttempts(3)
                            .setCacheSyncRetryDelayMs(10L)
                            .setCacheSyncTimeoutMs(5_000L);

        continuumProperties = mock(ContinuumProperties.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ClusterGroup servers = mock(ClusterGroup.class);
        when(servers.nodes()).thenReturn(List.of(mock(ClusterNode.class)));
        IgniteCluster cluster = mock(IgniteCluster.class);
        when(cluster.forServers()).thenReturn(servers);
        compute = mock(IgniteCompute.class);
        Ignite ignite = mock(Ignite.class);
        when(ignite.cluster()).thenReturn(cluster);
        when(ignite.compute(servers)).thenReturn(compute);

        // Each broadcast gets a future the test completes by hand. Runnable::run matches Continuum's Ignite
        // asyncContinuationExecutor: listeners run on whichever thread completes the future.
        when(compute.broadcastAsync(any(IgniteRunnable.class))).thenAnswer(invocation -> {
            GridFutureAdapter<Void> broadcast = new GridFutureAdapter<>();
            synchronized (broadcasts) {
                broadcasts.add(broadcast);
            }
            return (IgniteFuture<Void>) new IgniteFutureImpl<>(broadcast, Runnable::run);
        });

        service = new ClusterCacheEvictionService(continuumProperties,
                                                  structuresProperties,
                                                  ignite,
                                                  eventPublisher,
                                                  OpenTelemetry.noop());
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
    }

    @Test
    void continuesOffTheIgniteThread() throws Exception {
        AtomicReference<String> continuedOn = new AtomicReference<>();
        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"))
                                                  .thenRun(() -> continuedOn.set(Thread.currentThread().getName()));

        Thread igniteThread = new Thread(() -> broadcast(0).onDone(), "ignite-pub-test");
        igniteThread.start();
        igniteThread.join();

        eviction.get(5, TimeUnit.SECONDS);
        assertNotEquals("ignite-pub-test", continuedOn.get());
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
    }

    @Test
    void retriesATimedOutBroadcastThenGivesUpWithoutFailing() throws Exception {
        structuresProperties.getClusterEviction()
                            .setMaxCacheSyncRetryAttempts(2)
                            .setCacheSyncTimeoutMs(50L);

        CompletableFuture<Void> eviction = service.evictCaches(CacheEvictionEvent.localModifiedStructure("app", "app.Person"));

        // Neither broadcast ever completes: both attempts time out, and the change still goes through
        eviction.get(5, TimeUnit.SECONDS);
        assertFalse(eviction.isCompletedExceptionally());
        assertEquals(2, broadcastCount());
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
