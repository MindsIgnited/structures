package org.kinotic.structures.internal.utils;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.ResponseException;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.exceptions.VersionConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recognizes the 409 Conflict Elasticsearch answers with when an if_seq_no / if_primary_term check fails,
 * when a create finds the document already there, or when another write changes a document while an update is
 * applied to it, and turns it into a {@link VersionConflictException}.
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
     * @param structure    the Structure written to, which decides the advice in the message
     * @param objectMapper used to read the reason from a low level error response
     * @return a {@link VersionConflictException} if the failure was a 409 from Elasticsearch, otherwise the throwable unchanged
     */
    public static Throwable translate(Throwable throwable, Structure structure, ObjectMapper objectMapper) {
        for(Throwable cause = throwable; cause != null; cause = cause.getCause()){
            if(cause instanceof VersionConflictException){
                return cause;
            }
            if(cause instanceof ElasticsearchException e && e.status() == CONFLICT){
                return conflict(structure, e.error().reason());
            }
            if(cause instanceof ResponseException e
                    && e.getResponse().getStatusLine().getStatusCode() == CONFLICT){
                return conflict(structure, readReason(e, objectMapper));
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

    public static VersionConflictException conflict(Structure structure, String reason) {
        return new VersionConflictException("Version conflict writing " + structure.getName()
                                                    + ": " + (reason != null ? reason : "version conflict")
                                                    + ". " + advice(structure, false));
    }

    /**
     * @param conflicts the number of bulk items that conflicted
     * @param total     the number of items in the bulk request
     * @param reasons   the reasons of the items that conflicted, one per line
     */
    public static VersionConflictException bulkConflict(Structure structure, int conflicts, int total, String reasons) {
        // Elasticsearch applies each bulk item on its own, so only the items that conflicted were not written
        return new VersionConflictException("Version conflict writing " + structure.getName()
                                                    + ", " + conflicts + " of " + total + " items were not written:\n"
                                                    + reasons + advice(structure, true));
    }

    /**
     * With a version field the caller sent a stale version, or created an entity that exists. Without one, the
     * conflict comes from another write changing the same entity while an update was applied, so the update can
     * be sent again. Elasticsearch is not asked to retry it itself, since it would apply the partial update
     * over the other write without the caller knowing.
     */
    private static String advice(Structure structure, boolean bulk) {
        if(structure.isOptimisticLockingEnabled() || structure.isStream()){
            return bulk ? "Read those entities again to get their current versions."
                        : "Read the entity again to get its current version.";
        }
        return bulk ? "Another write changed those entities at the same time, send those items again."
                    : "Another write changed the entity at the same time, send the request again.";
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
