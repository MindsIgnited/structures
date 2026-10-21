package org.kinotic.structures.api.config;

import co.elastic.clients.elasticsearch._types.Refresh;

/**
 * What a single entity write asks of Elasticsearch before returning, so its change shows up in searches.
 * The values mirror the Elasticsearch refresh parameter, and bind from {@code true}, {@code false} and {@code wait_for}.
 * <p>
 * The Sync writes ({@code saveSync}, {@code updateSync}, {@code deleteByIdSync}) ignore the policy. They use
 * {@link #TRUE}, or {@link #WAIT_FOR} when the caller passes {@code waitFor}. Forcing is their default because
 * {@link #WAIT_FOR} holds a pooled connection, which reads share, until the next scheduled refresh, and its wait grows
 * with the index's refresh interval, so it costs most under the heavy writes that make {@link #FALSE} worth setting.
 * A forced refresh touches only the shard the write went to and returns once that is done, and only the callers that
 * ask for it pay for it. {@code waitFor} suits callers that can wait out the refresh interval, to spare Elasticsearch
 * the extra segment.
 */
public enum ElasticRefreshPolicy {

    /**
     * Force a refresh of the shards the write touched before returning, so the change is searchable as soon as the
     * call returns. Each forced refresh writes a new small segment, which costs merges and cache churn under heavy writes.
     */
    TRUE(Refresh.True),

    /**
     * Return without waiting. The change is searchable after the next scheduled refresh, a Sync write to the same
     * shard, or a syncIndex call.
     */
    FALSE(Refresh.False),

    /**
     * Wait for the next scheduled refresh before returning, so the change is searchable as soon as the call returns
     * without forcing a refresh. The call takes up to the index's refresh interval (1 second by default) longer.
     */
    WAIT_FOR(Refresh.WaitFor);

    private final Refresh refresh;

    ElasticRefreshPolicy(Refresh refresh) {
        this.refresh = refresh;
    }

    /**
     * @param waitFor the waitFor flag the Sync write was called with
     * @return the policy a Sync write uses: {@link #WAIT_FOR} with {@code waitFor}, otherwise {@link #TRUE}
     */
    public static ElasticRefreshPolicy forSyncWrite(boolean waitFor) {
        return waitFor ? WAIT_FOR : TRUE;
    }

    /**
     * @return the Elasticsearch refresh value for this policy
     */
    public Refresh toRefresh() {
        return refresh;
    }
}
