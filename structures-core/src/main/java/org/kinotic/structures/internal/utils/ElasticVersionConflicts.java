package org.kinotic.structures.internal.utils;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.ResponseException;
import org.kinotic.structures.api.exceptions.VersionConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recognizes the 409 Conflict Elasticsearch answers with when an if_seq_no / if_primary_term check fails,
 * or when a create finds the document already there, and turns it into a {@link VersionConflictException}.
 * <p>
 * The async client hands a 409 back as the low level {@link ResponseException}, since the transport only asks the
 * low level client to pass 400, 401, 403, 404 and 405 through. Its message names the Elasticsearch host and the
 * request URI, so the reason is read from the response body instead of passing that message on.
 */
public final class ElasticVersionConflicts {

    private static final int CONFLICT = 409;

    private ElasticVersionConflicts() {
    }

    /**
     * @param throwable    the failure of an Elasticsearch request, possibly wrapped
     * @param structureName the name of the Structure written to, used in the message
     * @param objectMapper used to read the reason from a low level error response
     * @return a {@link VersionConflictException} if the failure was a 409 from Elasticsearch, otherwise the throwable unchanged
     */
    public static Throwable translate(Throwable throwable, String structureName, ObjectMapper objectMapper) {
        for(Throwable cause = throwable; cause != null; cause = cause.getCause()){
            if(cause instanceof VersionConflictException){
                return cause;
            }
            if(cause instanceof ElasticsearchException e && e.status() == CONFLICT){
                return conflict(structureName, e.error().reason());
            }
            if(cause instanceof ResponseException e
                    && e.getResponse().getStatusLine().getStatusCode() == CONFLICT){
                return conflict(structureName, readReason(e, objectMapper));
            }
        }
        return throwable;
    }

    /**
     * @return true if the bulk item failed with a 409
     */
    public static boolean isConflict(BulkResponseItem item) {
        return item.error() != null && item.status() == CONFLICT;
    }

    public static VersionConflictException conflict(String structureName, String reason) {
        return new VersionConflictException("Version conflict writing " + structureName
                                                    + (reason != null ? ": " + reason : "")
                                                    + ". Read the entity again to get its current version.");
    }

    /**
     * @param reasons the reasons of the bulk items that conflicted, one per line
     */
    public static VersionConflictException bulkConflict(String structureName, String reasons) {
        // Elasticsearch applies each bulk item on its own, so the items that did not conflict were written
        return new VersionConflictException("Version conflicts writing " + structureName
                                                    + ", the other items were written:\n" + reasons
                                                    + "Read those entities again to get their current versions.");
    }

    private static String readReason(ResponseException e, ObjectMapper objectMapper) {
        try {
            // The low level client buffers the entity of a ResponseException, so it can be read here
            JsonNode body = objectMapper.readTree(EntityUtils.toString(e.getResponse().getEntity()));
            JsonNode reason = body.path("error").path("reason");
            return reason.isString() ? reason.asString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
