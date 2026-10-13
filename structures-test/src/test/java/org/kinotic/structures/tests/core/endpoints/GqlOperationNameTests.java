package org.kinotic.structures.tests.core.endpoints;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.idl.api.schema.ArrayC3Type;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.NamedQueriesDefinition;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.PageC3Type;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.QueryDecorator;
import org.kinotic.structures.api.domain.idl.decorators.TenantIdDecorator;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.NamedQueriesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationNames;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Structures and named queries whose GraphQL operation names another structure in the application already has are
 * refused, and {@link GqlOperationNames} matches what the schema really has.
 */
public class GqlOperationNameTests extends ElasticTestBase {

    // DummySecurityService accepts these over basic auth
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
                                                              .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));

    // The REST and GraphQL defaults (8080, 4000) can be taken by a local KinD cluster or another server
    private static final int OPEN_API_PORT = freePort();
    private static final int GRAPHQL_PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("structures.open-api-port", () -> OPEN_API_PORT);
        registry.add("structures.graphql-port", () -> GRAPHQL_PORT);
    }


    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private NamedQueriesService namedQueriesService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StructureService structureService;
    @Autowired
    private StructuresProperties structuresProperties;
    @Autowired
    private TestDataService testDataService;

    /**
     * Keeps {@link GqlOperationNames} in step with the schema generators, since the checks rely on it
     */
    @Test
    public void operationNamesMatchTheSchema() throws Exception {
        String applicationId = newApplicationId();
        Structure structure = create(applicationId, "Person", true);
        publish(structure);
        NamedQueriesDefinition namedQueries = saveNamedQueries(structure, "listPeople", "pagePeople");

        Set<String> expected = GqlOperationNames.forStructure(structure, namedQueries, null).keySet();

        // One request per type, since the server refuses introspection that asks for fields twice
        Set<String> generated = new HashSet<>();
        for (String type : List.of("Query", "Mutation")) {
            JsonNode fields = graphQl(applicationId, "{ __type(name: \"%s\") { fields { name } } }".formatted(type))
                    .path("__type")
                    .path("fields");
            for (JsonNode field : fields) {
                generated.add(field.path("name").asString());
            }
        }
        generated.removeIf(name -> name.startsWith("_")); // federation's _service and _entities
        Assertions.assertEquals(expected, generated);
    }

    @Test
    public void publishingAStructureWithATakenNameIsRefused() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "Job" + suffix, false));
        Structure syncJob = create(applicationId, "SyncJob" + suffix, false);

        IllegalArgumentException refused = assertRefused(() -> structureService.publish(syncJob.getId()).join());
        Assertions.assertTrue(refused.getMessage().contains("GraphQL operation saveSyncJob" + suffix), refused::getMessage);
        Assertions.assertFalse(structureService.findById(syncJob.getId()).join().isPublished());
    }

    @Test
    public void publishingAStructureWhoseNamesAnotherHasTakenIsRefusedWhicheverIsFirst() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "SyncJob" + suffix, false));
        Structure job = create(applicationId, "Job" + suffix, false);

        IllegalArgumentException refused = assertRefused(() -> structureService.publish(job.getId()).join());
        Assertions.assertTrue(refused.getMessage().contains("saveSyncJob" + suffix), refused::getMessage);
    }

    @Test
    public void structuresWithDistinctNamesCanBePublished() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "Job" + suffix, false));
        Structure task = create(applicationId, "Task" + suffix, false);

        Assertions.assertDoesNotThrow(() -> structureService.publish(task.getId()).join());
    }

    /**
     * countJobByQuery is the countByQuery operationId of Job and the count operationId of JobByQuery. OpenAPI paths
     * still differ, so this is allowed; only GraphQL names are checked.
     */
    @Test
    public void structuresWhoseOnlyOpenApiOperationIdsRepeatCanBePublished() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "Job" + suffix, false));
        Structure jobByQuery = create(applicationId, "Job" + suffix + "ByQuery", false);

        Assertions.assertDoesNotThrow(() -> structureService.publish(jobByQuery.getId()).join());
    }

    @Test
    public void savingANamedQueryWithATakenNameIsRefused() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        // saveX + Job is saveXJob, which is the save operation of structure XJob
        publish(create(applicationId, "XJob" + suffix, false));
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);

        IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, "saveX"));
        Assertions.assertTrue(refused.getMessage().contains("GraphQL operation saveXJob" + suffix), refused::getMessage);
        Assertions.assertNull(namedQueriesService.findByApplicationAndStructure(applicationId, job.getName()).join());
    }

    @Test
    public void savingANamedQueryNamedLikeABuiltInOperationIsRefused() {
        String applicationId = newApplicationId();
        Structure job = create(applicationId, "Job" + System.nanoTime(), false);
        publish(job);

        IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, "count"));
        Assertions.assertTrue(refused.getMessage().contains("the count operation of structure " + job.getName()),
                              refused::getMessage);
    }

    private static IllegalArgumentException assertRefused(Supplier<?> call) {
        CompletionException thrown = Assertions.assertThrows(CompletionException.class, call::get);
        return Assertions.assertInstanceOf(IllegalArgumentException.class,
                                           thrown.getCause(),
                                           () -> "Expected the clash to be refused but got " + thrown);
    }

    private static String newApplicationId() {
        return "org.kinotic.endpointnames" + System.nanoTime();
    }

    /**
     * Creates a Person structure without publishing it
     *
     * @param tenantSelection true to add a tenant id field, as used by the admin operations
     */
    private Structure create(String applicationId, String name, boolean tenantSelection) {
        // GraphQL names its types after the schema, which must be unique within the application
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED).setName(name);
        if (tenantSelection) {
            schema.addProperty("tenantId", new StringC3Type(), List.of(new TenantIdDecorator()));
        }
        Structure toCreate = new Structure();
        toCreate.setName(name);
        toCreate.setApplicationId(applicationId);
        toCreate.setProjectId(applicationId + "_default");
        toCreate.setDescription("Defines a Person for the endpoint name tests");
        toCreate.setEntityDefinition(schema);

        return applicationService.createApplicationIfNotExist(applicationId, "Endpoint name tests")
                                 .thenCompose(v -> structureService.create(toCreate))
                                 .join();
    }

    private void publish(Structure structure) {
        structureService.publish(structure.getId()).join();
    }

    /**
     * Saves SELECT named queries for the structure; each name ending in "page" returns a page, the rest a list
     */
    private NamedQueriesDefinition saveNamedQueries(Structure structure, String... queryNames) {
        List<FunctionDefinition> queries = new ArrayList<>();
        for (String queryName : queryNames) {
            ObjectC3Type result = new ObjectC3Type().setName(structure.getName() + queryName + "Result")
                                                    .setNamespace("org.kinotic.endpointnames")
                                                    .addProperty("firstName", new StringC3Type());
            FunctionDefinition query = new FunctionDefinition().setName(queryName)
                                                               .setParameters(new LinkedList<>())
                                                               .setReturnType(queryName.toLowerCase().startsWith("page")
                                                                                      ? new PageC3Type().setContentType(result)
                                                                                      : new ArrayC3Type().setContains(result));
            List<C3Decorator> decorators = new ArrayList<>();
            decorators.add(new QueryDecorator().setStatements("SELECT firstName FROM \"" + structure.getId() + "\""));
            query.setDecorators(decorators);
            queries.add(query);
        }
        NamedQueriesDefinition namedQueries = new NamedQueriesDefinition().setId(structure.getId())
                                                                          .setApplicationId(structure.getApplicationId())
                                                                          .setProjectId(structure.getProjectId())
                                                                          .setStructure(structure.getName())
                                                                          .setNamedQueries(queries);
        return namedQueriesService.save(namedQueries).join();
    }

    /**
     * @return the data of a GraphQL response, after checking it has no errors
     */
    private JsonNode graphQl(String applicationId, String query) throws Exception {
        URI uri = URI.create("http://localhost:" + structuresProperties.getGraphqlPort()
                                     + structuresProperties.getGraphqlPath() + applicationId);
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(uri)
                                         .timeout(Duration.ofSeconds(30))
                                         .header("Authorization", BASIC_AUTH)
                                         .header("Content-Type", "application/json")
                                         .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(Map.of("query", query))))
                                         .build();
        HttpResponse<String> response = send(request);
        Assertions.assertEquals(200, response.statusCode(), response::body);
        JsonNode json = objectMapper.readTree(response.body());
        Assertions.assertTrue(json.path("errors").isMissingNode() || json.path("errors").isEmpty(), response::body);
        return json.path("data");
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
