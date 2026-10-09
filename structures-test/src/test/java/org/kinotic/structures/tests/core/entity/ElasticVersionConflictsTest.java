package org.kinotic.structures.tests.core.entity;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.exceptions.VersionConflictException;
import org.kinotic.structures.internal.utils.ElasticVersionConflicts;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * The advice in a conflict's message depends on whether the Structure has a version field. A conflict on a Structure
 * without one comes from two writes racing, which the integration tests can't produce on demand.
 */
class ElasticVersionConflictsTest {

    private static final String REASON = "[kinotic-1]: version conflict, required seqNo [0], primary term [1]";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void versionedConflictSaysToReadTheEntityAgain() {
        VersionConflictException conflict = translate(conflict(REASON), structure().setVersionFieldName("version"));

        Assertions.assertTrue(conflict.getMessage().contains(REASON), conflict.getMessage());
        Assertions.assertTrue(conflict.getMessage().endsWith("Read the entity again to get its current version."),
                              conflict.getMessage());
    }

    @Test
    void unversionedConflictSaysToSendTheRequestAgain() {
        VersionConflictException conflict = translate(conflict(REASON), structure());

        Assertions.assertTrue(conflict.getMessage().contains(REASON), conflict.getMessage());
        Assertions.assertTrue(conflict.getMessage().endsWith("send the request again."), conflict.getMessage());
    }

    @Test
    void messageSaysVersionConflictWithoutAReason() {
        VersionConflictException conflict = translate(conflict(null), structure());

        Assertions.assertTrue(conflict.getMessage().contains("version conflict"), conflict.getMessage());
    }

    @Test
    void otherFailuresAreLeftAlone() {
        IllegalStateException failure = new IllegalStateException("boom");

        Assertions.assertSame(failure, ElasticVersionConflicts.translate(failure, structure(), objectMapper));
    }

    @Test
    void bulkConflictCountsTheItemsNotWritten() {
        VersionConflictException conflict = ElasticVersionConflicts.bulkConflict(structure(), 2, 5, REASON + "\n");

        Assertions.assertTrue(conflict.getMessage().contains("2 of 5 items were not written"), conflict.getMessage());
    }

    private VersionConflictException translate(Throwable failure, Structure structure) {
        return Assertions.assertInstanceOf(VersionConflictException.class,
                                           ElasticVersionConflicts.translate(new CompletionException(failure),
                                                                             structure,
                                                                             objectMapper));
    }

    private static ElasticsearchException conflict(String reason) {
        return new ElasticsearchException("es/update",
                                          ErrorResponse.of(r -> r.status(409)
                                                                 .error(e -> e.type("version_conflict_engine_exception")
                                                                              .reason(reason))));
    }

    private static Structure structure() {
        return new Structure().setName("Person");
    }
}
