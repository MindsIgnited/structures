package org.kinotic.structures.tests.core.entity;

import org.junit.jupiter.api.Test;
import org.kinotic.continuum.api.exceptions.AuthorizationException;
import org.kinotic.continuum.core.api.crud.Pageable;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.TenantIdDecorator;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.TokenBuffer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Exercises a {@link MultiTenancyType#SHARED} {@link Structure} with a {@link TenantIdDecorator} field from both
 * kinds of participant: one that belongs to a tenant, and one connected without a tenant that names the tenants
 * it wants through a tenant selection, the way the admin service does.
 */
@SpringBootTest
public class TenantSelectionTests extends ElasticTestBase {

    private static final String APPLICATION_ID = "org.kinotic.sample";
    private static final String TENANT_ID_FIELD = "tenantId";

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private EntitiesService entitiesService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StructureService structureService;
    @Autowired
    private TestDataService testDataService;

    @Test
    public void testTenantedParticipantOwnsTheTenantIdField() {
        Structure structure = createTenantPersonStructure("_ownsField");

        StepVerifier.create(Mono.fromFuture(savePerson(structure, contextFor("tenant1"), "", "Blank")))
                    .expectNextMatches(saved -> "tenant1".equals(saved.get(TENANT_ID_FIELD).asString()))
                    .as("A blank tenant id is filled with the participant's tenant")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(savePerson(structure, contextFor("tenant1"), "tenant2", "Other")))
                    .as("Another tenant's id is rejected")
                    .expectError(IllegalArgumentException.class)
                    .verify();

        StepVerifier.create(Mono.fromFuture(savePersonAsMap(structure, contextFor("tenant1"), "", "BlankMap")))
                    .expectNextMatches(saved -> "tenant1".equals(saved.get(TENANT_ID_FIELD)))
                    .as("The Map upsert path fills a blank tenant id the same way")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(savePersonAsMap(structure, contextFor("tenant1"), "tenant2", "OtherMap")))
                    .as("The Map upsert path rejects another tenant's id the same way")
                    .expectError(IllegalArgumentException.class)
                    .verify();

        entitiesService.syncIndex(structure.getId(), contextFor("tenant1")).join();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(), contextFor("tenant1"))))
                    .expectNext(2L)
                    .as("The participant sees its own tenant")
                    .verifyComplete();
    }

    @Test
    public void testTenantedParticipantIsConfinedToItsTenant() {
        Structure structure = createTenantPersonStructure("_confined");

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(), contextFor("tenant1", "tenant1"))))
                    .expectNext(0L)
                    .as("Selecting its own tenant is allowed")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(), contextFor("tenant1", "tenant2"))))
                    .as("Selecting another tenant is refused")
                    .expectError(AuthorizationException.class)
                    .verify();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(),
                                                                  contextFor("tenant1", EntityContext.ALL_TENANTS))))
                    .as("Selecting every tenant is refused")
                    .expectError(AuthorizationException.class)
                    .verify();
    }

    @Test
    public void testParticipantWithoutATenantSelectsTenants() {
        Structure structure = createTenantPersonStructure("_acrossTenants");

        StepVerifier.create(Mono.fromFuture(savePerson(structure, contextFor(null), "", "Blank")))
                    .as("Without a tenant of its own the data must name one")
                    .expectError(IllegalArgumentException.class)
                    .verify();

        for(int i = 0; i < 2; i++) {
            savePerson(structure, contextFor(null), "tenant1", "One" + i).join();
        }
        for(int i = 0; i < 3; i++) {
            savePerson(structure, contextFor(null), "tenant2", "Two" + i).join();
        }
        entitiesService.syncIndex(structure.getId(), contextFor(null)).join();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(), contextFor(null))))
                    .as("A read without a tenant or a selection is refused")
                    .expectError(IllegalArgumentException.class)
                    .verify();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(), contextFor(null, "tenant2"))))
                    .expectNext(3L)
                    .as("A selection counts the named tenant")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(entitiesService.count(structure.getId(),
                                                                  contextFor(null, EntityContext.ALL_TENANTS))))
                    .expectNext(5L)
                    .as("The wildcard counts every tenant")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(entitiesService.countByQuery(structure.getId(),
                                                                         "lastName: Two*",
                                                                         contextFor(null, EntityContext.ALL_TENANTS))))
                    .expectNext(3L)
                    .as("The wildcard applies to a query")
                    .verifyComplete();

        StepVerifier.create(Mono.fromFuture(entitiesService.findAll(structure.getId(),
                                                                    Pageable.ofSize(10),
                                                                    RawJson.class,
                                                                    contextFor(null, EntityContext.ALL_TENANTS))))
                    .expectNextMatches(page -> page.getTotalElements() == 5 && page.getContent().size() == 5)
                    .as("The wildcard returns every tenant's rows")
                    .verifyComplete();
    }

    private EntityContext contextFor(String participantTenantId, String... tenantSelection) {
        DefaultEntityContext ret = new DefaultEntityContext(new DummyParticipant(participantTenantId, "user"));
        if(tenantSelection.length > 0) {
            ret.setTenantSelection(List.of(tenantSelection));
        }
        return ret;
    }

    private Structure createTenantPersonStructure(String structureNameSuffix) {
        Structure structure = new Structure();
        structure.setName("TenantPerson" + structureNameSuffix)
                 .setApplicationId(APPLICATION_ID)
                 .setProjectId(APPLICATION_ID + "_default")
                 .setDescription("Defines a Person that names its tenant")
                 .setEntityDefinition(testDataService.createPersonSchema(MultiTenancyType.SHARED)
                                                     .addProperty(TENANT_ID_FIELD,
                                                                  new StringC3Type(),
                                                                  List.of(new TenantIdDecorator())));

        return applicationService.createApplicationIfNotExist(APPLICATION_ID, "Sample application")
                                 .thenCompose(unused -> structureService.create(structure))
                                 .thenCompose(saved -> structureService.publish(saved.getId())
                                                                       .thenApply(published -> saved))
                                 .join();
    }

    private CompletableFuture<JsonNode> savePerson(Structure structure,
                                                   EntityContext context,
                                                   String tenantId,
                                                   String lastName) {
        TokenBuffer tokenBuffer = TokenBuffer.forGeneration();
        objectMapper.writeValue(tokenBuffer, newPerson(tenantId, lastName));
        return entitiesService.save(structure.getId(), tokenBuffer, context)
                              .thenApply(saved -> objectMapper.readTree(saved.asParser()));
    }

    private CompletableFuture<Map<String, Object>> savePersonAsMap(Structure structure,
                                                                   EntityContext context,
                                                                   String tenantId,
                                                                   String lastName) {
        return entitiesService.save(structure.getId(), newPerson(tenantId, lastName), context);
    }

    private Map<String, Object> newPerson(String tenantId, String lastName) {
        // the id field must be present for the AutoGeneratedId preprocessor to assign a generated id
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("id", "");
        ret.put("firstName", "Test");
        ret.put("lastName", lastName);
        ret.put(TENANT_ID_FIELD, tenantId);
        return ret;
    }

}
