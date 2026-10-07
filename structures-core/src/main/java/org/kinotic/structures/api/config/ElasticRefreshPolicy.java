package org.kinotic.structures.api.config;

import co.elastic.clients.elasticsearch._types.Refresh;

/**
 * What a single entity write asks of Elasticsearch before returning, so its change shows up in searches.
 * The values mirror the Elasticsearch refresh parameter, and bind from {@code true}, {@code false} and {@code wait_for}.
 */
public enum ElasticRefreshPolicy {

    /**
     * Force a refresh of the shards the write touched before returning, so the change is searchable as soon as the
     * call returns. Each forced refresh writes a new small segment, which costs merges and cache churn under heavy writes.
     */
    TRUE(Refresh.True),

    /**
     * Return without waiting. The change is searchable after the next scheduled refresh, or once syncIndex is called.
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
     * @return the Elasticsearch refresh value for this policy
     */
    public Refresh toRefresh() {
        return refresh;
    }
}
