

/**
 * Options for a Named Query
 * Created by Navíd Mitchell 🤪 on 2/25/25.
 */
export class QueryOptions {

    /**
     * The time zone dates are read and written in, such as `Europe/Paris` or `Z`
     */
    public timeZone!: string
    /**
     * How many seconds Elasticsearch may spend on the query before it gives up.
     * Defaults to 90 seconds in Elasticsearch, and the server waits up to `structures.elastic-named-query-timeout`
     * (2 minutes by default) for an answer unless this is set.
     */
    public requestTimeout!: number
    /**
     * How long Elasticsearch keeps the cursor for the next page alive between requests, as an Elasticsearch time value
     * with its unit, such as `2m`. Defaults to `2m`.
     */
    public pageTimeout!: string

}
