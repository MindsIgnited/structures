package org.kinotic.structures.tests.core.cache;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.idl.api.schema.ArrayC3Type;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.continuum.idl.api.schema.IntC3Type;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.QueryDecorator;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.NamedQueriesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationDefinition;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationDefinitionService;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A named query definition holds its structure's name, while the GraphQL operations built from it are cached by the
 * structure's id. Saving or deleting named queries must still evict them, or GraphQL keeps serving the old queries.
 */
public class NamedQueryCacheEvictionTests extends ElasticTestBase {

    private static final String QUERY = "countPeople";

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private GqlOperationDefinitionService gqlOperationDefinitionService;
    @Autowired
    private NamedQueriesService namedQueriesService;
    @Autowired
    private StructureService structureService;
    @Autowired
    private TestDataService testDataService;

    private Structure structure;

    @BeforeEach
    public void publishStructure() {
        String applicationId = "org.kinotic.namedqueryeviction" + System.nanoTime();
        // Mixed case, so the name and the lower case id really differ
        String name = "Person" + System.nanoTime();
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.NONE).setName(name);
        Structure toCreate = new Structure();
        toCreate.setName(name);
        toCreate.setApplicationId(applicationId);
        toCreate.setProjectId(applicationId + "_default");
        toCreate.setDescription("Defines a Person for the named query cache eviction tests");
        toCreate.setEntityDefinition(schema);

        structure = applicationService.createApplicationIfNotExist(applicationId, "Named query cache eviction tests")
                                      .thenCompose(v -> structureService.create(toCreate))
                                      .thenCompose(created -> structureService.publish(created.getId())
                                                                              .thenCompose(v -> structureService.findById(created.getId())))
                                      .join();
        Assertions.assertNotEquals(structure.getName(), structure.getId());
    }

    @Test
    public void savingNamedQueriesEvictsTheGraphQLOperations() {
        // Loads the operations into the cache before there are any named queries
        Assertions.assertFalse(hasQueryOperation());

        namedQueriesService.save(namedQueries()).join();

        Assertions.assertTrue(hasQueryOperation(), "GraphQL still has the operations from before the save");
    }

    @Test
    public void deletingNamedQueriesEvictsTheGraphQLOperations() {
        NamedQueriesDefinition saved = namedQueriesService.save(namedQueries()).join();
        Assertions.assertTrue(hasQueryOperation());

        namedQueriesService.deleteById(saved.getId()).join();

        Assertions.assertFalse(hasQueryOperation(), "GraphQL still has the operations from before the delete");
    }

    private boolean hasQueryOperation() {
        return gqlOperationDefinitionService.getNamedQueryOperationDefinitions(structure)
                                            .stream()
                                            .map(GqlOperationDefinition::getOperationName)
                                            .anyMatch(operationName -> operationName.contains(QUERY));
    }

    private NamedQueriesDefinition namedQueries() {
        ObjectC3Type result = new ObjectC3Type().setName(structure.getName() + "CountResult")
                                                .setNamespace(structure.getApplicationId())
                                                .addProperty("count", new IntC3Type())
                                                .addProperty("lastName", new StringC3Type());
        List<C3Decorator> decorators = new ArrayList<>();
        decorators.add(new QueryDecorator().setStatements("SELECT COUNT(firstName) as count, lastName FROM \""
                                                                  + structure.getId() + "\" GROUP BY lastName"));
        FunctionDefinition query = new FunctionDefinition().setName(QUERY)
                                                           .setParameters(new LinkedList<>())
                                                           .setReturnType(new ArrayC3Type().setContains(result));
        query.setDecorators(decorators);
        return new NamedQueriesDefinition().setId(structure.getId())
                                           .setApplicationId(structure.getApplicationId())
                                           .setProjectId(structure.getProjectId())
                                           .setStructure(structure.getName())
                                           .setNamedQueries(List.of(query));
    }
}
