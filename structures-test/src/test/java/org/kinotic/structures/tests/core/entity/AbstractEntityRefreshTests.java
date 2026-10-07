package org.kinotic.structures.tests.core.entity;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.core.api.crud.Pageable;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.VersionDecorator;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.internal.sample.Person;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.kinotic.structures.tests.core.support.StructureAndPersonHolder;
import org.kinotic.structures.tests.core.support.TestHelper;
import org.springframework.beans.factory.annotation.Autowired;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * Checks when single saves, updates and deletes become searchable, given the elasticRefreshAfterMutation and
 * elasticRefreshAfterDelete settings a subclass runs with. Scheduled refreshes are switched off on each structure's
 * index, so a change is only searchable once a forced refresh or syncIndex has made it so.
 */
public abstract class AbstractEntityRefreshTests extends ElasticTestBase {

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ElasticsearchClient client;
    @Autowired
    private EntitiesService entitiesService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StructureService structureService;
    @Autowired
    private StructuresProperties structuresProperties;
    @Autowired
    private TestDataService testDataService;
    @Autowired
    private TestHelper testHelper;

    @Test
    public void testChangesAreSearchableOnlyAfterARefresh() throws IOException {
        boolean refreshesAfterMutation = structuresProperties.isElasticRefreshAfterMutation();
        boolean refreshesAfterDelete = structuresProperties.isElasticRefreshAfterDelete();
        EntityContext context = new DefaultEntityContext(new DummyParticipant());
        StructureAndPersonHolder holder = testHelper.createAndVerify(1,
                                                                     true,
                                                                     context,
                                                                     "_" + System.currentTimeMillis());
        Structure structure = holder.getStructure();

        disableScheduledRefresh(structure);
        syncIndex(structure, context);
        Assertions.assertEquals(1L, count(structure, context));

        Person person = save(structure, testDataService.createRandomTestPeople(1).join().getFirst(), context, Person.class);
        Assertions.assertEquals(refreshesAfterMutation ? 2L : 1L, count(structure, context),
                                "Saved entity searchable before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(2L, count(structure, context));

        String lastNameQuery = "lastName: Refreshcheck";
        update(structure, person.setLastName("Refreshcheck"), context, Person.class);
        Assertions.assertEquals(refreshesAfterMutation ? 1L : 0L, countByQuery(structure, lastNameQuery, context),
                                "Updated entity searchable before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(1L, countByQuery(structure, lastNameQuery, context));

        entitiesService.deleteById(structure.getId(), person.getId(), context).join();
        Assertions.assertEquals(refreshesAfterDelete ? 1L : 2L, count(structure, context),
                                "Deleted entity gone from searches before syncIndex");
        syncIndex(structure, context);
        Assertions.assertEquals(1L, count(structure, context));
    }

    /**
     * Search results carry the version a document had at the last refresh. Without a forced refresh after an
     * update, an entity found by searching straight afterwards still has its old version, so updating it fails
     * with a version conflict. Finding the entity by id returns the current version either way.
     */
    @Test
    public void testOptimisticLockingVersionFromSearch() throws IOException {
        boolean refreshesAfterMutation = structuresProperties.isElasticRefreshAfterMutation();
        EntityContext context = new DefaultEntityContext(new DummyParticipant());
        Structure structure = createVersionedPersonStructure("_" + System.currentTimeMillis());

        disableScheduledRefresh(structure);
        VersionedPerson saved = save(structure,
                                     new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"),
                                     context,
                                     VersionedPerson.class);
        syncIndex(structure, context);

        VersionedPerson updated = update(structure, saved.setLastName("Byron"), context, VersionedPerson.class);
        Assertions.assertNotEquals(saved.getVersion(), updated.getVersion());

        // Entities with a version field can only be read back as raw JSON
        VersionedPerson fromSearch = fromRawJson(entitiesService.findAll(structure.getId(),
                                                                         Pageable.ofSize(10),
                                                                         RawJson.class,
                                                                         context)
                                                                .join()
                                                                .getContent()
                                                                .getFirst());
        Assertions.assertEquals(refreshesAfterMutation ? updated.getVersion() : saved.getVersion(),
                                fromSearch.getVersion(),
                                "Version seen by search straight after the update");

        fromSearch.setFirstName("Augusta");
        if(refreshesAfterMutation){
            Assertions.assertDoesNotThrow(() -> update(structure, fromSearch, context, VersionedPerson.class));
        }else{
            CompletionException thrown = Assertions.assertThrows(CompletionException.class,
                                                                 () -> update(structure, fromSearch, context, VersionedPerson.class));
            Assertions.assertTrue(isVersionConflict(thrown), () -> "Expected a version conflict but got " + thrown);

            VersionedPerson fromId = fromRawJson(entitiesService.findById(structure.getId(),
                                                                          updated.getId(),
                                                                          RawJson.class,
                                                                          context).join());
            Assertions.assertEquals(updated.getVersion(), fromId.getVersion());
            Assertions.assertDoesNotThrow(() -> update(structure, fromId.setFirstName("Augusta"), context, VersionedPerson.class));
        }
    }

    private Structure createVersionedPersonStructure(String suffix) {
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED)
                                             .addProperty("version", new StringC3Type(), List.of(new VersionDecorator()));
        Structure structure = new Structure();
        structure.setName("VersionedPerson" + suffix);
        structure.setApplicationId("org.kinotic.sample");
        structure.setProjectId("org.kinotic.sample_default");
        structure.setDescription("Defines a Person with a version field");
        structure.setEntityDefinition(schema);

        return applicationService.createApplicationIfNotExist("org.kinotic.sample", "Sample application")
                                 .thenCompose(v -> structureService.create(structure))
                                 .thenCompose(created -> structureService.publish(created.getId())
                                                                         .thenCompose(v -> structureService.findById(created.getId())))
                                 .join();
    }

    private static boolean isVersionConflict(Throwable thrown) {
        for(Throwable cause = thrown; cause != null; cause = cause.getCause()){
            if(cause instanceof ElasticsearchException e && e.status() == 409){
                return true;
            }
            // update sends its request through the low level client, which reports errors as a ResponseException
            if(cause instanceof ResponseException e && e.getResponse().getStatusLine().getStatusCode() == 409){
                return true;
            }
        }
        return false;
    }

    private void disableScheduledRefresh(Structure structure) throws IOException {
        client.indices().putSettings(b -> b.index(structure.getItemIndex())
                                           .settings(s -> s.refreshInterval(t -> t.time("-1"))));
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

    private <T> T save(Structure structure, T entity, EntityContext context, Class<T> type) {
        return read(entitiesService.save(structure.getId(), toTokenBuffer(entity), context), type);
    }

    private <T> T update(Structure structure, T entity, EntityContext context, Class<T> type) {
        return read(entitiesService.update(structure.getId(), toTokenBuffer(entity), context), type);
    }

    private VersionedPerson fromRawJson(RawJson rawJson) {
        return objectMapper.readValue(rawJson.data(), VersionedPerson.class);
    }

    private TokenBuffer toTokenBuffer(Object entity) {
        TokenBuffer tokenBuffer = TokenBuffer.forGeneration();
        objectMapper.writeValue(tokenBuffer, entity);
        return tokenBuffer;
    }

    private <T> T read(CompletableFuture<TokenBuffer> future, Class<T> type) {
        try (JsonParser parser = future.join().asParser()) {
            return objectMapper.readValue(parser, type);
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    @NoArgsConstructor
    public static class VersionedPerson {
        private String id;
        private String firstName;
        private String lastName;
        private String version;
    }
}
