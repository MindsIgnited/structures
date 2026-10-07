package org.kinotic.structures.tests.core.entity;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.internal.sample.Person;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.kinotic.structures.tests.core.support.StructureAndPersonHolder;
import org.kinotic.structures.tests.core.support.TestHelper;
import org.springframework.beans.factory.annotation.Autowired;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Checks when single saves, updates and deletes become searchable, given the elasticRefreshAfterMutation and
 * elasticRefreshAfterDelete settings of the subclass. Scheduled refreshes are switched off on the structure's index,
 * so a change is only searchable once a forced refresh or syncIndex has made it so.
 */
public abstract class AbstractEntityRefreshTests extends ElasticTestBase {

    @Autowired
    private EntitiesService entitiesService;
    @Autowired
    private ElasticsearchClient client;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TestDataService testDataService;
    @Autowired
    private TestHelper testHelper;

    protected abstract boolean refreshesAfterMutation();

    protected abstract boolean refreshesAfterDelete();

    @Test
    public void testChangesAreSearchableOnlyAfterARefresh() throws IOException {
        EntityContext context = new DefaultEntityContext(new DummyParticipant());
        StructureAndPersonHolder holder = testHelper.createAndVerify(1,
                                                                     true,
                                                                     context,
                                                                     "_" + System.currentTimeMillis());
        Structure structure = holder.getStructure();

        client.indices().putSettings(b -> b.index(structure.getItemIndex())
                                           .settings(s -> s.refreshInterval(t -> t.time("-1"))));
        syncIndex(structure, context);
        Assertions.assertEquals(1L, count(structure, context));

        Person person = save(structure, testDataService.createRandomTestPeople(1).join().getFirst(), context);
        Assertions.assertEquals(refreshesAfterMutation() ? 2L : 1L, count(structure, context),
                                "Saved entity searchable before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(2L, count(structure, context));

        String lastNameQuery = "lastName: Refreshcheck";
        update(structure, person.setLastName("Refreshcheck"), context);
        Assertions.assertEquals(refreshesAfterMutation() ? 1L : 0L, countByQuery(structure, lastNameQuery, context),
                                "Updated entity searchable before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(1L, countByQuery(structure, lastNameQuery, context));

        entitiesService.deleteById(structure.getId(), person.getId(), context).join();
        Assertions.assertEquals(refreshesAfterDelete() ? 1L : 2L, count(structure, context),
                                "Deleted entity gone from searches before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(1L, count(structure, context));
    }

    private long count(Structure structure, EntityContext context) {
        return entitiesService.count(structure.getId(), context).join();
    }

    private long countByQuery(Structure structure, String query, EntityContext context) {
        return entitiesService.countByQuery(structure.getId(), query, context).join();
    }

    private void syncIndex(Structure structure, EntityContext context) {
        entitiesService.syncIndex(structure.getId(), context).join();
    }

    private Person save(Structure structure, Person person, EntityContext context) {
        return toPerson(entitiesService.save(structure.getId(), toTokenBuffer(person), context));
    }

    private Person update(Structure structure, Person person, EntityContext context) {
        return toPerson(entitiesService.update(structure.getId(), toTokenBuffer(person), context));
    }

    private TokenBuffer toTokenBuffer(Person person) {
        TokenBuffer tokenBuffer = TokenBuffer.forGeneration();
        objectMapper.writeValue(tokenBuffer, person);
        return tokenBuffer;
    }

    private Person toPerson(CompletableFuture<TokenBuffer> future) {
        try (JsonParser parser = future.join().asParser()) {
            return objectMapper.readValue(parser, Person.class);
        }
    }
}
