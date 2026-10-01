package org.kinotic.structures.api.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Options for a Named Query
 * Created by Navíd Mitchell 🤪 on 6/19/24.
 */
@NoArgsConstructor
@Getter
@Setter
public class QueryOptions {

    /**
     * The time zone dates are read and written in, such as {@code Europe/Paris} or {@code Z}
     */
    private String timeZone;
    /**
     * How many seconds Elasticsearch may spend on the query before it gives up.
     * Defaults to 90 seconds in Elasticsearch, and Structures waits up to {@code structures.elastic-named-query-timeout}
     * (2 minutes by default) for an answer unless this is set.
     */
    private Integer requestTimeout;
    /**
     * How long Elasticsearch keeps the cursor for the next page alive between requests, as an Elasticsearch time value
     * with its unit, such as {@code 2m}. Defaults to {@code 2m}.
     */
    private String pageTimeout;

}
