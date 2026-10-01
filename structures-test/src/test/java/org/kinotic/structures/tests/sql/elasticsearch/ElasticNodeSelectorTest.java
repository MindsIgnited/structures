package org.kinotic.structures.tests.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.kinotic.structures.internal.api.services.sql.elasticsearch.ElasticNodeSelector;

/**
 * Pins how named query requests are spread over the configured Elasticsearch connections, and how a node that failed
 * is kept out of the rotation without ever being given up on.
 */
class ElasticNodeSelectorTest {

    private final AtomicLong now = new AtomicLong();
    private final ElasticNodeSelector<String> selector = new ElasticNodeSelector<>(List.of("a", "b", "c"), now::get);

    @Test
    void liveNodesAreHandedOutRoundRobin() {
        assertEquals(List.of("a", "b", "c"), selector.nodesForRequest());
        assertEquals(List.of("b", "c", "a"), selector.nodesForRequest());
        assertEquals(List.of("c", "a", "b"), selector.nodesForRequest());
        assertEquals(List.of("a", "b", "c"), selector.nodesForRequest());
    }

    @Test
    void aDeadNodeIsLeftOutWhileAnyNodeIsAlive() {
        assertTrue(selector.markDead("a"));

        assertEquals(List.of("b", "c"), selector.nodesForRequest());
        assertEquals(List.of("c", "b"), selector.nodesForRequest());
        assertTrue(selector.isDead("a"));
    }

    @Test
    void whenEveryNodeIsDeadOnlyTheOneDueBackFirstIsTried() {
        selector.markDead("b");
        advance(Duration.ofSeconds(10));
        selector.markDead("c");
        advance(Duration.ofSeconds(10));
        selector.markDead("a");

        assertEquals(List.of("b"), selector.nodesForRequest());

        // b failed its revival and now has two minutes to sit out, so c is due back first
        advance(Duration.ofSeconds(40));
        selector.markDead("b");
        assertEquals(List.of("c"), selector.nodesForRequest());
    }

    @Test
    void aDeadNodeRejoinsTheRotationOnceItsBackOffExpires() {
        selector.markDead("a");
        advance(Duration.ofMinutes(1).minusNanos(1));
        assertTrue(selector.isDead("a"));

        advance(Duration.ofNanos(1));
        assertFalse(selector.isDead("a"));
        assertTrue(selector.nodesForRequest().contains("a"));
    }

    @Test
    void eachFailedRevivalDoublesTheBackOffUpToThirtyMinutes() {
        Duration[] expected = {
                Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8),
                Duration.ofMinutes(16), Duration.ofMinutes(30), Duration.ofMinutes(30)
        };
        selector.markDead("a");
        for (Duration deadTime : expected) {
            advance(deadTime.minusNanos(1));
            assertTrue(selector.isDead("a"), "still dead just before " + deadTime);
            advance(Duration.ofNanos(1));
            assertFalse(selector.isDead("a"), "revived after " + deadTime);
            // the revival attempt fails
            selector.markDead("a");
        }
    }

    @Test
    void failuresOfRequestsAlreadyInFlightDoNotExtendTheBackOff() {
        assertTrue(selector.markDead("a"));
        for (int i = 0; i < 50; i++) {
            assertFalse(selector.markDead("a"));
        }

        advance(Duration.ofMinutes(1));
        assertFalse(selector.isDead("a"), "fifty concurrent failures still only count once");
    }

    @Test
    void aNodeThatAnswersIsAliveAgainWithItsBackOffReset() {
        selector.markDead("a");
        advance(Duration.ofMinutes(1));
        selector.markDead("a"); // failed revival, now two minutes

        assertTrue(selector.markAlive("a"));
        assertFalse(selector.isDead("a"));
        assertFalse(selector.markAlive("a"), "already alive");

        selector.markDead("a");
        advance(Duration.ofMinutes(1));
        assertFalse(selector.isDead("a"), "the back-off started over at one minute");
    }

    @Test
    void identicalConnectionsAreTrackedSeparately() {
        String first = new String("es:9200");
        String second = new String("es:9200");
        ElasticNodeSelector<String> twins = new ElasticNodeSelector<>(List.of(first, second), now::get);

        twins.markDead(first);

        assertTrue(twins.isDead(first));
        assertFalse(twins.isDead(second));
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
