package org.kinotic.structures.tests.core.endpoints.openapi;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.WordUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.idl.api.schema.ArrayC3Type;
import org.kinotic.continuum.idl.api.schema.C3Type;
import org.kinotic.continuum.idl.api.schema.FunctionDefinition;
import org.kinotic.continuum.idl.api.schema.IntC3Type;
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
import org.kinotic.structures.internal.endpoints.openapi.OpenApiVertxRouterFactory;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the generated OpenAPI spec against the routes the server really registers, so the two can't drift apart.
 * The structure has multi-tenant selection and a named query of each kind, so every kind of route is in the spec.
 */
public class OpenApiSpecTests extends ElasticTestBase {

    // The REST default (8080) can be taken by a local KinD cluster or another server
    private static final int OPEN_API_PORT = freePort();

    // DummySecurityService accepts these over basic auth
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
                                                              .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));

    private static final String LIST_QUERY = "countPeople";
    private static final String PAGE_QUERY = "pagePeopleCounts";

    private static final Set<String> OPERATION_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("structures.open-api-port", () -> OPEN_API_PORT);
    }

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private NamedQueriesService namedQueriesService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OpenApiVertxRouterFactory openApiVertxRouterFactory;
    @Autowired
    private StructureService structureService;
    @Autowired
    private StructuresProperties structuresProperties;
    @Autowired
    private TestDataService testDataService;

    private Structure structure;
    private JsonNode spec;

    @BeforeEach
    public void publishStructureAndFetchSpec() throws Exception {
        String applicationId = "org.kinotic.openapispec" + System.nanoTime();
        structure = publishStructure(applicationId, "Person" + System.nanoTime());
        saveNamedQueries();
        spec = fetchSpec(applicationId);
    }

    /**
     * Publishes a Person structure with multi-tenant selection under the given name, in the given application
     */
    private Structure publishStructure(String applicationId, String name) {
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED)
                                             .setName(name)
                                             .addProperty("tenantId", new StringC3Type(), List.of(new TenantIdDecorator()));
        Structure toCreate = new Structure();
        toCreate.setName(name);
        toCreate.setApplicationId(applicationId);
        toCreate.setProjectId(applicationId + "_default");
        toCreate.setDescription("Defines a Person for the OpenAPI spec tests");
        toCreate.setEntityDefinition(schema);

        return applicationService.createApplicationIfNotExist(applicationId, "OpenAPI spec tests")
                                 .thenCompose(v -> structureService.create(toCreate))
                                 .thenCompose(created -> structureService.publish(created.getId())
                                                                         .thenCompose(v -> structureService.findById(created.getId())))
                                 .join();
    }

    private JsonNode fetchSpec(String applicationId) throws Exception {
        URI uri = URI.create("http://localhost:" + structuresProperties.getOpenApiPort()
                                     + "/api-docs/" + applicationId + "/openapi.json");
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            response = client.send(HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                                   HttpResponse.BodyHandlers.ofString());
        }
        Assertions.assertEquals(200, response.statusCode(), response::body);
        return objectMapper.readTree(response.body());
    }

    @Test
    public void everyDocumentedOperationIsARoute() {
        Set<String> routes = routeOperations();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (String method : operationMethods(path.getValue())) {
                String operation = method.toUpperCase() + " " + path.getKey();
                if (!routes.contains(operation)) {
                    missing.add(operation);
                }
            }
        }
        Assertions.assertEquals(List.of(), missing, "Documented operations with no route");
    }

    @Test
    public void everyRouteIsDocumented() {
        List<String> undocumented = new ArrayList<>();
        for (String operation : routeOperations()) {
            String[] methodAndPath = operation.split(" ", 2);
            if (spec.path("paths").path(methodAndPath[1]).path(methodAndPath[0].toLowerCase()).isMissingNode()) {
                undocumented.add(operation);
            }
        }
        Assertions.assertEquals(List.of(), undocumented, "Routes missing from the spec");
    }

    /**
     * The error schema's name is not reserved: a structure named ErrorResponse keeps its schema, its operations still
     * answer it, and the errors of every operation reference the error schema under the next free name
     */
    @Test
    public void aStructureNamedErrorResponseKeepsItsSchema() throws Exception {
        publishStructure(structure.getApplicationId(), "ErrorResponse");
        JsonNode spec = fetchSpec(structure.getApplicationId());
        JsonNode schemas = spec.path("components").path("schemas");

        Assertions.assertTrue(schemas.path("ErrorResponse").path("properties").has("firstName"),
                              () -> "the structure's schema was replaced: " + schemas.path("ErrorResponse"));
        Assertions.assertEquals("string", schemas.path("ErrorResponse2").path("properties").path("error").path("type").asString(),
                                () -> schemas.path("ErrorResponse2").toString());

        Set<String> errorRefs = new TreeSet<>();
        Set<String> okRefs = new TreeSet<>();
        String byId = structuresProperties.getOpenApiPath() + structure.getApplicationId().toLowerCase() + "/errorresponse/{id}";
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (String method : operationMethods(path.getValue())) {
                for (Map.Entry<String, JsonNode> response : path.getValue().path(method).path("responses").properties()) {
                    String ref = response.getValue().path("content").path("application/json").path("schema").path("$ref").asString();
                    if (!response.getKey().startsWith("2") && !response.getKey().equals("404")) {
                        errorRefs.add(ref);
                    } else if (path.getKey().equals(byId) && method.equals("get") && response.getKey().equals("200")) {
                        okRefs.add(ref);
                    }
                }
            }
        }
        Assertions.assertEquals(Set.of("#/components/schemas/ErrorResponse2"), errorRefs);
        Assertions.assertEquals(Set.of("#/components/schemas/ErrorResponse"), okRefs, "GET " + byId + " must still answer the structure");
    }

    /**
     * Errors answer {"error": message} as application/json, except an entity that isn't found, which answers an empty 404
     */
    @Test
    public void errorsAreDocumentedAsTheyAreAnswered() throws Exception {
        JsonNode errorSchema = spec.path("components").path("schemas").path("ErrorResponse");
        Assertions.assertEquals("string", errorSchema.path("properties").path("error").path("type").asString(), errorSchema::toString);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (String method : operationMethods(path.getValue())) {
                String where = method.toUpperCase() + " " + path.getKey();
                for (Map.Entry<String, JsonNode> response : path.getValue().path(method).path("responses").properties()) {
                    String ref = response.getValue().path("content").path("application/json").path("schema").path("$ref").asString();
                    boolean error = !response.getKey().startsWith("2") && !response.getKey().equals("404");
                    if (error && !ref.equals("#/components/schemas/ErrorResponse")) {
                        problems.add(where + " " + response.getKey() + " doesn't document the ErrorResponse body");
                    }
                    if (response.getKey().equals("404") && response.getValue().has("content")) {
                        problems.add(where + " 404 documents a body");
                    }
                }
            }
        }
        Assertions.assertEquals(List.of(), problems);

        // A request with no query, and one with a bad password, really answer an ErrorResponse
        String api = structuresProperties.getOpenApiPath() + structure.getApplicationId().toLowerCase()
                + "/" + structure.getName().toLowerCase();
        for (String authorization : List.of(BASIC_AUTH, "Basic " + Base64.getEncoder().encodeToString("guest:wrong".getBytes(StandardCharsets.UTF_8)))) {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                                                            .uri(URI.create("http://localhost:" + structuresProperties.getOpenApiPort() + api + "/count/by-query"))
                                                            .header("Authorization", authorization)
                                                            .header("Content-Type", "text/plain")
                                                            .POST(HttpRequest.BodyPublishers.ofString(""))
                                                            .build());
            Assertions.assertTrue(response.statusCode() >= 400, response::body);
            Assertions.assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                                  () -> response.statusCode() + " " + response.headers());
            Assertions.assertTrue(objectMapper.readTree(response.body()).path("error").isString(), response::body);
        }
    }

    @Test
    public void syncOperationsMirrorTheirPlainOperations() {
        String application = structure.getApplicationId().toLowerCase();
        String name = structure.getName().toLowerCase();
        String structureName = WordUtils.capitalize(structure.getName());
        String api = structuresProperties.getOpenApiPath() + application + "/" + name;
        String admin = structuresProperties.getOpenApiAdminPath() + application + "/" + name;

        assertMirrors(operation(api + "/save-sync", "post"), operation(api, "post"), "saveSync" + structureName);
        assertMirrors(operation(api + "/update-sync", "post"), operation(api + "/update", "post"), "updateSync" + structureName);
        assertMirrors(operation(api + "/{id}/sync", "delete"), operation(api + "/{id}", "delete"), "deleteSync" + structureName);
        assertMirrors(operation(admin + "/{tenantId}/{id}/sync", "delete"),
                      operation(admin + "/{tenantId}/{id}", "delete"),
                      "deleteSync" + structureName + "Admin");
    }

    @Test
    public void specIsWellFormed() {
        Map<String, Integer> operationIds = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            Set<String> placeholders = new TreeSet<>();
            Matcher matcher = PATH_PARAMETER.matcher(path.getKey());
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            for (String method : operationMethods(path.getValue())) {
                JsonNode operation = path.getValue().path(method);
                String where = method.toUpperCase() + " " + path.getKey();

                String operationId = operation.path("operationId").asString();
                if (operationId.isBlank()) {
                    problems.add(where + " has no operationId");
                } else {
                    operationIds.merge(operationId, 1, Integer::sum);
                }

                Set<String> declared = new TreeSet<>();
                for (JsonNode parameter : operation.path("parameters")) {
                    if (parameter.path("in").asString().equals("path")) {
                        declared.add(parameter.path("name").asString());
                        if (!parameter.path("required").asBoolean()) {
                            problems.add(where + " path parameter " + parameter.path("name").asString() + " is not required");
                        }
                    }
                }
                if (!declared.equals(placeholders)) {
                    problems.add(where + " declares path parameters " + declared + " for " + placeholders);
                }
                if (!operation.path("responses").has("200")) {
                    problems.add(where + " documents no 200 response");
                }
                // A response with an empty body is documented without content, not as JSON without a schema
                for (Map.Entry<String, JsonNode> response : operation.path("responses").properties()) {
                    for (Map.Entry<String, JsonNode> mediaType : response.getValue().path("content").properties()) {
                        if (mediaType.getValue().path("schema").isMissingNode()) {
                            problems.add(where + " " + response.getKey() + " documents " + mediaType.getKey() + " content without a schema");
                        }
                    }
                }
            }
        }
        operationIds.forEach((id, count) -> {
            if (count > 1) {
                problems.add("operationId " + id + " is used " + count + " times");
            }
        });

        Set<String> schemas = new HashSet<>();
        spec.path("components").path("schemas").propertyNames().forEach(schemas::add);
        collectReferences(spec, problems, schemas);

        Assertions.assertEquals(List.of(), problems);
    }

    private void assertMirrors(JsonNode sync, JsonNode plain, String expectedOperationId) {
        Assertions.assertFalse(sync.isMissingNode(), () -> expectedOperationId + " is missing from the spec");
        Assertions.assertFalse(plain.isMissingNode(), () -> "The plain twin of " + expectedOperationId + " is missing from the spec");
        Assertions.assertEquals(expectedOperationId, sync.path("operationId").asString());

        // A Sync operation takes one parameter its plain twin does not: an optional boolean waitFor in the query
        JsonNode waitFor = null;
        ArrayNode syncParameters = objectMapper.createArrayNode();
        for (JsonNode parameter : sync.path("parameters")) {
            if (parameter.path("name").asString().equals("waitFor")) {
                waitFor = parameter;
            } else {
                syncParameters.add(parameter);
            }
        }
        Assertions.assertNotNull(waitFor, () -> expectedOperationId + " has no waitFor parameter");
        Assertions.assertEquals("query", waitFor.path("in").asString(), expectedOperationId);
        Assertions.assertFalse(waitFor.path("required").asBoolean(), expectedOperationId);
        Assertions.assertEquals("boolean", waitFor.path("schema").path("type").asString(), expectedOperationId);
        Assertions.assertEquals(plain.path("parameters").isMissingNode() ? objectMapper.createArrayNode() : plain.path("parameters"),
                                syncParameters,
                                () -> expectedOperationId + " parameters differ from its plain twin");

        for (String part : List.of("requestBody", "responses", "security", "tags")) {
            JsonNode syncPart = withoutResponseDescriptions(part, sync.path(part));
            JsonNode plainPart = withoutResponseDescriptions(part, plain.path(part));
            Assertions.assertEquals(plainPart, syncPart, () -> expectedOperationId + " " + part + " differ from its plain twin");
        }
    }

    /**
     * Each 200 response is described after its operation's summary, so descriptions are left out of the comparison
     */
    private JsonNode withoutResponseDescriptions(String part, JsonNode node) {
        if (!part.equals("responses") || !node.isObject()) {
            return node;
        }
        JsonNode copy = node.deepCopy();
        copy.properties().forEach(response -> ((ObjectNode) response.getValue()).remove("description"));
        return copy;
    }

    private JsonNode operation(String path, String method) {
        return spec.path("paths").path(path).path(method);
    }

    private static List<String> operationMethods(JsonNode pathItem) {
        List<String> ret = new ArrayList<>();
        for (String field : pathItem.propertyNames()) {
            if (OPERATION_METHODS.contains(field)) {
                ret.add(field);
            }
        }
        return ret;
    }

    /**
     * Every $ref must point to a schema in components
     */
    private static void collectReferences(JsonNode node, List<String> problems, Set<String> schemas) {
        if (node.isObject()) {
            JsonNode ref = node.path("$ref");
            if (ref.isString()) {
                String reference = ref.asString();
                String prefix = "#/components/schemas/";
                if (!reference.startsWith(prefix) || !schemas.contains(reference.substring(prefix.length()))) {
                    problems.add("$ref " + reference + " does not resolve");
                }
            }
            node.values().forEach(child -> collectReferences(child, problems, schemas));
        } else if (node.isArray()) {
            node.values().forEach(child -> collectReferences(child, problems, schemas));
        }
    }

    /**
     * @return the routes for entities and their named queries, leaving out the handlers for every request and the spec
     */
    private List<Route> entityRoutes() {
        List<Route> ret = new ArrayList<>();
        for (Route route : openApiVertxRouterFactory.createRouter().getRoutes()) {
            String path = route.getPath();
            if (path == null || route.methods() == null || route.methods().isEmpty()) {
                continue;
            }
            if (path.startsWith(structuresProperties.getOpenApiPath()) || path.startsWith(structuresProperties.getOpenApiAdminPath())) {
                ret.add(route);
            }
        }
        Assertions.assertFalse(ret.isEmpty(), "No entity routes found");
        return ret;
    }

    /**
     * @return each entity route as "METHOD path" in the spec's form: the structure and named queries filled in, and
     * each :param written {param}
     */
    private Set<String> routeOperations() {
        String application = structure.getApplicationId().toLowerCase();
        String name = structure.getName().toLowerCase();
        Set<String> ret = new TreeSet<>();
        for (Route route : entityRoutes()) {
            String queryName = route.getPath().contains("named-query-page") ? PAGE_QUERY : LIST_QUERY;
            String path = route.getPath()
                               .replace(":structureApplication", application)
                               .replace(":structureName", name)
                               .replace(":queryName", queryName)
                               .replaceAll(":([A-Za-z]+)", "{$1}");
            for (HttpMethod method : route.methods()) {
                ret.add(method.name() + " " + path);
            }
        }
        return ret;
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void saveNamedQueries() {
        List<FunctionDefinition> queries = new ArrayList<>();
        queries.add(aggregate(LIST_QUERY, false));
        queries.add(aggregate(PAGE_QUERY, true));
        NamedQueriesDefinition namedQueries = new NamedQueriesDefinition().setId(structure.getId())
                                                                          .setApplicationId(structure.getApplicationId())
                                                                          .setProjectId(structure.getProjectId())
                                                                          .setStructure(structure.getName())
                                                                          .setNamedQueries(queries);
        namedQueriesService.save(namedQueries).join();
    }

    private FunctionDefinition aggregate(String queryName, boolean page) {
        ObjectC3Type result = new ObjectC3Type().setName(structure.getName() + queryName + "Result")
                                                .setNamespace("org.kinotic.openapispec")
                                                .addProperty("count", new IntC3Type())
                                                .addProperty("lastName", new StringC3Type());
        C3Type returnType = page ? new PageC3Type().setContentType(result) : new ArrayC3Type().setContains(result);
        FunctionDefinition query = new FunctionDefinition().setName(queryName)
                                                           .setParameters(new LinkedList<>())
                                                           .setReturnType(returnType);
        List<C3Decorator> decorators = new ArrayList<>();
        decorators.add(new QueryDecorator().setStatements("SELECT COUNT(firstName) as count, lastName FROM \""
                                                                  + structure.getId() + "\" GROUP BY lastName"));
        query.setDecorators(decorators);
        return query;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
