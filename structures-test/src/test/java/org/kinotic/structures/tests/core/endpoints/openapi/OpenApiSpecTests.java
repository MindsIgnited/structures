package org.kinotic.structures.tests.core.endpoints.openapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the generated OpenAPI spec against the routes the server really registers, so the two can't drift apart.
 * The structure has multi-tenant selection and a named query of each kind, so every kind of route is in the spec.
 */
public class OpenApiSpecTests extends ElasticTestBase {

    // The REST default (8080) can be taken by a local KinD cluster or another server
    private static final int OPEN_API_PORT = freePort();

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
        String name = "Person" + System.nanoTime();
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED)
                                             .setName(name)
                                             .addProperty("tenantId", new StringC3Type(), List.of(new TenantIdDecorator()));
        Structure toCreate = new Structure();
        toCreate.setName(name);
        toCreate.setApplicationId(applicationId);
        toCreate.setProjectId(applicationId + "_default");
        toCreate.setDescription("Defines a Person for the OpenAPI spec tests");
        toCreate.setEntityDefinition(schema);

        structure = applicationService.createApplicationIfNotExist(applicationId, "OpenAPI spec tests")
                                      .thenCompose(v -> structureService.create(toCreate))
                                      .thenCompose(created -> structureService.publish(created.getId())
                                                                              .thenCompose(v -> structureService.findById(created.getId())))
                                      .join();
        saveNamedQueries();

        URI uri = URI.create("http://localhost:" + structuresProperties.getOpenApiPort()
                                     + "/api-docs/" + applicationId + "/openapi.json");
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            response = client.send(HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                                   HttpResponse.BodyHandlers.ofString());
        }
        Assertions.assertEquals(200, response.statusCode(), response::body);
        spec = objectMapper.readTree(response.body());
    }

    @Test
    public void everyDocumentedOperationIsARoute() {
        List<Pattern> routes = new ArrayList<>();
        Map<Pattern, Set<HttpMethod>> methods = new HashMap<>();
        for (Route route : entityRoutes()) {
            // A route's :param matches one path segment
            Pattern pattern = Pattern.compile(Pattern.quote(route.getPath()).replaceAll(":[A-Za-z]+", "\\\\E[^/]+\\\\Q"));
            routes.add(pattern);
            methods.put(pattern, route.methods());
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            String concrete = PATH_PARAMETER.matcher(path.getKey()).replaceAll("x");
            for (String method : operationMethods(path.getValue())) {
                boolean found = routes.stream()
                                      .anyMatch(pattern -> pattern.matcher(concrete).matches()
                                              && methods.get(pattern).contains(HttpMethod.valueOf(method.toUpperCase())));
                if (!found) {
                    missing.add(method.toUpperCase() + " " + path.getKey());
                }
            }
        }
        Assertions.assertEquals(List.of(), missing, "Documented operations with no route");
    }

    @Test
    public void everyRouteIsDocumented() {
        String application = structure.getApplicationId().toLowerCase();
        String name = structure.getName().toLowerCase();

        List<String> undocumented = new ArrayList<>();
        for (Route route : entityRoutes()) {
            String queryName = route.getPath().contains("named-query-page") ? PAGE_QUERY : LIST_QUERY;
            // The spec names each path parameter as the route does, and fills in the structure and query
            String path = route.getPath()
                               .replace(":structureApplication", application)
                               .replace(":structureName", name)
                               .replace(":queryName", queryName)
                               .replaceAll(":([A-Za-z]+)", "{$1}");
            JsonNode pathItem = spec.path("paths").path(path);
            for (HttpMethod method : route.methods()) {
                if (pathItem.path(method.name().toLowerCase()).isMissingNode()) {
                    undocumented.add(method.name() + " " + path);
                }
            }
        }
        Assertions.assertEquals(List.of(), undocumented, "Routes missing from the spec");
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
        for (String part : List.of("parameters", "requestBody", "responses", "security", "tags")) {
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
