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
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import graphql.language.OperationDefinition;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.apache.commons.text.WordUtils;
import org.kinotic.continuum.idl.api.schema.ArrayC3Type;
import org.kinotic.continuum.idl.api.schema.C3Type;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.continuum.idl.api.schema.IntC3Type;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.continuum.idl.api.schema.VoidC3Type;
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
import org.kinotic.structures.internal.api.services.StructureDAO;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationDefinition;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationDefinitionService;
import org.kinotic.structures.internal.endpoints.graphql.GqlOperationNames;
import org.kinotic.structures.internal.utils.PagingUtils;
import org.kinotic.structures.tests.core.support.TestHelper;
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
    private GqlOperationDefinitionService gqlOperationDefinitionService;
    @Autowired
    private StructureDAO structureDAO;
    @Autowired
    private TestDataService testDataService;
    @Autowired
    private TestHelper testHelper;

    /**
     * Keeps {@link GqlOperationNames} in step with the schema generators, since the checks rely on it
     */
    @Test
    public void operationNamesMatchTheSchema() throws Exception {
        String applicationId = newApplicationId();
        Structure structure = create(applicationId, "Person", true);
        publish(structure);
        NamedQueriesDefinition namedQueries = saveNamedQueries(structure,
                                                               structure.getName(),
                                                               select(structure, "countPeople", false),
                                                               select(structure, "pagePeopleCounts", true));

        Map<GqlOperationNames.OperationName, String> expected
                = GqlOperationNames.forStructure(builtInOperations(), structure, namedQueries, null);

        // One request per type, since the server refuses introspection that asks for fields twice
        for (OperationDefinition.Operation type : List.of(OperationDefinition.Operation.QUERY,
                                                          OperationDefinition.Operation.MUTATION)) {
            String typeName = type == OperationDefinition.Operation.QUERY ? "Query" : "Mutation";
            JsonNode fields = graphQl(applicationId, "{ __type(name: \"%s\") { fields { name } } }".formatted(typeName))
                    .path("__type")
                    .path("fields");
            Set<String> generated = new HashSet<>();
            for (JsonNode field : fields) {
                generated.add(field.path("name").asString());
            }
            generated.removeIf(name -> name.startsWith("_")); // federation's _service and _entities

            Set<String> expectedOfType = expected.keySet()
                                                 .stream()
                                                 .filter(name -> name.type() == type)
                                                 .map(GqlOperationNames.OperationName::name)
                                                 .collect(Collectors.toSet());
            Assertions.assertEquals(expectedOfType, generated, typeName);
        }
    }

    @Test
    public void publishingAStructureWithATakenNameIsRefused() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "Job" + suffix, false));
        Structure syncJob = create(applicationId, "SyncJob" + suffix, false);

        IllegalArgumentException refused = assertRefused(() -> structureService.publish(syncJob.getId()).join());
        Assertions.assertTrue(refused.getMessage().contains("GraphQL mutation saveSyncJob" + suffix), refused::getMessage);
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
        // countX + Job is countXJob, which is the count query of structure XJob
        publish(create(applicationId, "XJob" + suffix, false));
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);

        IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, job.getName(), select(job, "countX", false)));
        Assertions.assertTrue(refused.getMessage().contains("GraphQL query countXJob" + suffix), refused::getMessage);
        Assertions.assertNull(namedQueriesService.findByApplicationAndStructure(applicationId, job.getName()).join());
    }

    @Test
    public void savingANamedQueryNamedLikeABuiltInOperationIsRefused() {
        String applicationId = newApplicationId();
        Structure job = create(applicationId, "Job" + System.nanoTime(), false);
        publish(job);

        IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, job.getName(), select(job, "count", false)));
        Assertions.assertTrue(refused.getMessage().contains("the count operation of structure " + job.getName()),
                              refused::getMessage);
    }

    /**
     * Unpublishing deletes the index, so the republish is checked again like any publish
     */
    @Test
    public void republishingIsRefusedIfAClashingStructureWasPublishedMeanwhile() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);
        structureService.unPublish(job.getId()).join();
        publish(create(applicationId, "SyncJob" + suffix, false));

        IllegalArgumentException refused = assertRefused(() -> structureService.publish(job.getId()).join());
        Assertions.assertTrue(refused.getMessage().contains("saveSyncJob" + suffix), refused::getMessage);
    }

    /**
     * Applications published before the check can already clash. Saving named queries checks only the named queries,
     * so those structures can still get new ones.
     */
    @Test
    public void namedQueriesCanBeSavedForStructuresThatAlreadyClash() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);
        Structure syncJob = testHelper.publishWithoutTheNameCheck(create(applicationId, "SyncJob" + suffix, false));

        Assertions.assertDoesNotThrow(() -> saveNamedQueries(job, job.getName(), select(job, "countPeople", false)));
        Assertions.assertDoesNotThrow(() -> saveNamedQueries(syncJob, syncJob.getName(), select(syncJob, "countPeople", false)));
    }

    /**
     * GraphQL keeps queries and mutations apart. Named queries are queries, so one named deleteX on Job is the query
     * deleteXJob, which doesn't clash with the delete mutation of XJob, while findAllX does clash with its findAll query.
     */
    @Test
    public void namedQueriesOnlyClashWithOperationsOfTheSameType() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "XJob" + suffix, false));
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);

        Assertions.assertDoesNotThrow(() -> saveNamedQueries(job, job.getName(), select(job, "deleteX", false)));

        IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, job.getName(), select(job, "findAllX", false)));
        Assertions.assertTrue(refused.getMessage().contains("GraphQL query findAllXJob" + suffix), refused::getMessage);
    }

    /**
     * Only a single SELECT with an aggregate can be run, so other named queries are refused instead of generating
     * operations that fail on every call
     */
    @Test
    public void namedQueriesThatCantBeRunAreRefused() {
        String applicationId = newApplicationId();
        Structure job = create(applicationId, "Job" + System.nanoTime(), false);
        publish(job);
        String from = " FROM \"" + job.getId() + "\"";

        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("DELETE" + from + " WHERE firstName = 'nobody'", "is a DELETE statement");
        refusals.put("INSERT INTO \"" + job.getId() + "\" (firstName) VALUES ('nobody')", "is a INSERT statement");
        refusals.put("UPDATE \"" + job.getId() + "\" SET firstName = 'nobody'", "is a UPDATE statement");
        refusals.put("SELECT firstName" + from, "is a SELECT without an aggregate");
        refusals.put("SELECT COUNT(firstName) as count" + from + "; SELECT COUNT(lastName) as count" + from, "has multiple statements");
        refusals.put("   ", "has no query defined");
        refusals.put("SHOW TABLES", "has an unsupported statement");

        for (Map.Entry<String, String> refusal : refusals.entrySet()) {
            FunctionDefinition query = namedQuery("badQuery", new VoidC3Type(), refusal.getKey());
            IllegalArgumentException refused = assertRefused(() -> saveNamedQueries(job, job.getName(), query));
            Assertions.assertTrue(refused.getMessage().contains("Named query badQuery " + refusal.getValue()), refused::getMessage);
        }
        Assertions.assertNull(namedQueriesService.findByApplicationAndStructure(applicationId, job.getName()).join());

        // A statement on several lines, as a template literal gives, is accepted
        Assertions.assertDoesNotThrow(() -> saveNamedQueries(job, job.getName(), namedQuery("countAll",
                                                                                            new VoidC3Type(),
                                                                                            "\n    SELECT COUNT(firstName) as count" + from + "\n")));
    }

    /**
     * Named queries are found by the exact structure name, so ones saved with another case generate no operations and
     * aren't checked
     */
    @Test
    public void namedQueriesForAnotherCaseOfTheStructureNameAreNotChecked() {
        String applicationId = newApplicationId();
        String suffix = String.valueOf(System.nanoTime());
        publish(create(applicationId, "XJob" + suffix, false));
        Structure job = create(applicationId, "Job" + suffix, false);
        publish(job);

        // countXJob would clash with the count query of XJob if these were Job's named queries
        Assertions.assertDoesNotThrow(() -> saveNamedQueries(job, job.getName().toLowerCase(), select(job, "countX", false)));
    }

    /**
     * The check reads every published structure in the application, following cursors sorted by id
     */
    @Test
    public void pagingFollowsCursorsToTheLastPage() {
        String applicationId = newApplicationId();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Structure structure = create(applicationId, "Paged" + i + "x" + System.nanoTime(), false);
            publish(structure);
            ids.add(structure.getId());
        }

        List<Structure> found = PagingUtils.findAll(pageable -> structureDAO.findAllPublishedForApplication(applicationId, pageable),
                                                    "id",
                                                    2)
                                           .join();

        Assertions.assertEquals(ids.stream().sorted().toList(), found.stream().map(Structure::getId).toList());
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

    private List<GqlOperationDefinition> builtInOperations() {
        List<GqlOperationDefinition> ret = new ArrayList<>(gqlOperationDefinitionService.getBuiltInOperationDefinitions());
        ret.addAll(gqlOperationDefinitionService.getSyncOperationDefinitions());
        return ret;
    }

    /**
     * A SELECT with an aggregate, the only kind of named query that can be run so far
     *
     * @param page true to return a page of results, otherwise a list
     */
    private static FunctionDefinition select(Structure structure, String queryName, boolean page) {
        ObjectC3Type result = new ObjectC3Type().setName(structure.getName() + queryName + "Result")
                                                .setNamespace("org.kinotic.endpointnames")
                                                .addProperty("count", new IntC3Type())
                                                .addProperty("lastName", new StringC3Type());
        return namedQuery(queryName,
                          page ? new PageC3Type().setContentType(result) : new ArrayC3Type().setContains(result),
                          "SELECT COUNT(firstName) as count, lastName FROM \"" + structure.getId() + "\" GROUP BY lastName");
    }

    private static FunctionDefinition namedQuery(String queryName, C3Type returnType, String statement) {
        FunctionDefinition query = new FunctionDefinition().setName(queryName)
                                                           .setParameters(new LinkedList<>())
                                                           .setReturnType(returnType);
        List<C3Decorator> decorators = new ArrayList<>();
        decorators.add(new QueryDecorator().setStatements(statement));
        query.setDecorators(decorators);
        return query;
    }

    /**
     * @param structureName the structure name to save the named queries under, normally the structure's own
     */
    private NamedQueriesDefinition saveNamedQueries(Structure structure, String structureName, FunctionDefinition... queries) {
        NamedQueriesDefinition namedQueries = new NamedQueriesDefinition().setId(structure.getId())
                                                                          .setApplicationId(structure.getApplicationId())
                                                                          .setProjectId(structure.getProjectId())
                                                                          .setStructure(structureName)
                                                                          .setNamedQueries(new ArrayList<>(List.of(queries)));
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
