package org.kinotic.structures.internal.api.services.sql.elasticsearch;

import org.apache.commons.lang3.Validate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

/**
 * Hands out the configured Elasticsearch nodes round robin, and keeps a node that failed out of the rotation until its
 * back-off expires. This mirrors the dead host handling of the Elasticsearch low-level RestClient, so the SQL requests
 * sent by {@link DefaultElasticVertxClient} survive the loss of a node the same way every other Elasticsearch call does.
 * <p>
 * A node that fails is skipped for {@link #MIN_DEAD_TIME}, doubling on every failed revival up to {@link #MAX_DEAD_TIME}.
 * Once its back-off expires it rejoins the rotation, and the next request it serves either revives it or sends it back.
 * Like the RestClient, a request is only offered the live nodes, and when there are none, just the dead node closest to
 * being revived, so an outage of the whole cluster costs each request one attempt rather than one per node.
 */
public class ElasticNodeSelector<N> {

    static final Duration MIN_DEAD_TIME = Duration.ofMinutes(1);
    static final Duration MAX_DEAD_TIME = Duration.ofMinutes(30);

    private final List<N> nodes;
    // null when the node at the same index is alive
    private final AtomicReferenceArray<DeadState> deadStates;
    private final AtomicInteger rotation = new AtomicInteger();
    private final LongSupplier nanoTime;

    public ElasticNodeSelector(List<N> nodes) {
        this(nodes, System::nanoTime);
    }

    /**
     * @param nanoTime the clock the back-offs are measured with, replaceable for tests
     */
    public ElasticNodeSelector(List<N> nodes, LongSupplier nanoTime) {
        Validate.notEmpty(nodes, "At least one Elasticsearch node is required");
        this.nodes = List.copyOf(nodes);
        this.deadStates = new AtomicReferenceArray<>(this.nodes.size());
        this.nanoTime = nanoTime;
    }

    /**
     * @return the nodes a request should try, in order: the live nodes, starting one further along than the previous
     * request did, or when every node is dead, only the one closest to being revived
     */
    public List<N> nodesForRequest() {
        long now = nanoTime.getAsLong();
        List<N> alive = new ArrayList<>(nodes.size());
        int closestToRevival = -1;
        long closestDeadUntil = 0;
        for (int i = 0; i < nodes.size(); i++) {
            DeadState state = deadStates.get(i);
            if (state == null || state.isExpired(now)) {
                alive.add(nodes.get(i));
            } else if (closestToRevival == -1 || state.deadUntilNanos - closestDeadUntil < 0) {
                closestToRevival = i;
                closestDeadUntil = state.deadUntilNanos;
            }
        }

        if (alive.isEmpty()) {
            return List.of(nodes.get(closestToRevival));
        }
        int start = Math.floorMod(rotation.getAndIncrement(), alive.size());
        List<N> ret = new ArrayList<>(alive.size());
        for (int i = 0; i < alive.size(); i++) {
            ret.add(alive.get((start + i) % alive.size()));
        }
        return ret;
    }

    /**
     * Records that the node answered, putting it back in the rotation if it was dead
     * @return true if the node was dead before this call
     */
    public boolean markAlive(N node) {
        int index = indexOf(node);
        // Called on every answer, nearly always for a node that is alive, so read before writing the shared slot
        return deadStates.get(index) != null && deadStates.getAndSet(index, null) != null;
    }

    /**
     * Records that the node could not be reached and takes it out of the rotation.
     * Failures of requests that were already in flight when the node was marked dead do not extend its back-off,
     * only a failed revival does, so a burst of concurrent failures counts once.
     * @return true if the node was alive before this call
     */
    public boolean markDead(N node) {
        int index = indexOf(node);
        long now = nanoTime.getAsLong();
        DeadState previous = deadStates.getAndUpdate(index, current -> {
            if (current == null) {
                return new DeadState(1, now);
            }
            return current.isExpired(now) ? new DeadState(current.failedAttempts + 1, now) : current;
        });
        return previous == null;
    }

    /**
     * @return true if the node is out of the rotation right now
     */
    public boolean isDead(N node) {
        DeadState state = deadStates.get(indexOf(node));
        return state != null && !state.isExpired(nanoTime.getAsLong());
    }

    private int indexOf(N node) {
        // Identity, since two connections may be configured identically; there are only ever a handful of nodes
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == node) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown Elasticsearch node " + node);
    }

    private static final class DeadState {
        private final int failedAttempts;
        private final long deadUntilNanos;

        private DeadState(int failedAttempts, long now) {
            this.failedAttempts = failedAttempts;
            this.deadUntilNanos = now + deadTime(failedAttempts).toNanos();
        }

        private boolean isExpired(long now) {
            return now - deadUntilNanos >= 0;
        }
    }

    static Duration deadTime(int failedAttempts) {
        // 1, 2, 4, 8, 16, then 30 minutes
        int doublings = Math.min(failedAttempts - 1, 5);
        Duration deadTime = MIN_DEAD_TIME.multipliedBy(1L << doublings);
        return deadTime.compareTo(MAX_DEAD_TIME) > 0 ? MAX_DEAD_TIME : deadTime;
    }
}
