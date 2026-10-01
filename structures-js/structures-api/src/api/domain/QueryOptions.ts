

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
     * How many seconds Elasticsearch may spend searching each shard before the query fails. Defaults to 90 seconds in
     * Elasticsearch. Combining the results afterwards is not bounded by it, so a query can run somewhat past it.
     * The server waits up to `structures.elastic-named-query-timeout` (2 minutes by default) for an answer, or this many seconds plus 5 when that is
     * longer.
     */
    public requestTimeout!: number
    /**
     * How long Elasticsearch keeps the cursor for the next page alive between requests, as an Elasticsearch time value
     * with its unit, such as `2m`. Defaults to `2m`.
     */
    public pageTimeout!: string

}
