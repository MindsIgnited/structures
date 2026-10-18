package org.kinotic.structures.tests.core.entity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.api.exceptions.AuthorizationException;
import org.kinotic.continuum.api.security.Participant;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.TenantSpecificId;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecorator;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecoratorsConfig;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecoratorsDecorator;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.PolicyDecorator;
import org.kinotic.structures.api.domain.idl.decorators.TenantIdDecorator;
import org.kinotic.structures.api.services.AdminJsonEntitiesService;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.api.services.JsonEntitiesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.api.services.security.AuthorizationServiceFactory;
import org.kinotic.structures.api.services.security.graphos.PolicyAuthorizationRequest;
import org.kinotic.structures.api.services.security.graphos.PolicyAuthorizationServiceFactory;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.annotation.JsonInclude;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * With refreshes after single writes switched off, the Sync writes must still be searchable as soon as they return,
 * over RPC, REST and GraphQL, and must be authorized with the same policies as the writes they refresh after.
 * Scheduled refreshes are switched off on every index here, so only a refresh a write asks for makes it searchable.
 */
@SpringBootTest(properties = {"structures.elastic-refresh-after-mutation=false",
                              "structures.elastic-refresh-after-delete=false"})
public class EntitySyncTests extends ElasticTestBase {

    // Structures in their own application, apart from those of other tests
    private static final String APPLICATION_ID = "org.kinotic.synctests";

    // DummySecurityService accepts these over basic auth, as a participant of the "kinotic" tenant
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
                                                              .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));

    // The REST and GraphQL defaults (8080, 4000) can be taken by a local KinD cluster or another server
    private static final int OPEN_API_PORT = freePort();
    private static final int GRAPHQL_PORT = freePort();

    // Writes guarded by this policy are refused by DenyingPolicyConfiguration
    private static final String DENIED_POLICY = "denied";

    // The tenant the admin tests write their entities for
    private static final String TENANT = "tenant01";

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("structures.open-api-port", () -> OPEN_API_PORT);
        registry.add("structures.graphql-port", () -> GRAPHQL_PORT);
    }

    /**
     * Swaps the default no-op authorization for policies, so a structure can deny saves, updates and deletes.
     */
    @TestConfiguration
    static class DenyingPolicyConfiguration {
        @Bean
        @Primary
        public AuthorizationServiceFactory denyingPolicyAuthorizationServiceFactory() {
            return new PolicyAuthorizationServiceFactory((requests, securityContext) -> {
                for (PolicyAuthorizationRequest request : requests) {
                    if (DENIED_POLICY.equals(request.policy())) {
                        request.deny();
                    } else {
                        request.authorize();
                    }
                }
                return CompletableFuture.completedFuture(null);
            });
        }
    }

    @Autowired
    private AdminJsonEntitiesService adminJsonEntitiesService;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ElasticsearchClient client;
    @Autowired
    private EntitiesService entitiesService;
    @Autowired
    private JsonEntitiesService jsonEntitiesService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StructureService structureService;
    @Autowired
    private StructuresProperties structuresProperties;
    @Autowired
    private TestDataService testDataService;

    private final Participant participant = new DummyParticipant();
    private final EntityContext context = new DefaultEntityContext(participant);

    @Test
    public void rpcSyncWritesAreSearchableOnReturn() throws IOException {
        Structure structure = createStructure("RpcSync", null, false);

        SyncPerson plain = rpcSave(structure, person("Ada", "Lovelace"), false);
        Assertions.assertEquals(0L, count(structure), "A plain save is searchable, so refreshes are not off");

        // Refreshing a shard makes every earlier write on it searchable too, so the plain save shows up as well
        SyncPerson synced = rpcSave(structure, person("Charles", "Babbage"), true);
        Assertions.assertEquals(2L, count(structure));

        rpcUpdate(structure, plain.setLastName("Plain"), false);
        Assertions.assertEquals(0L, countByQuery(structure, "lastName: Plain"));
        rpcUpdate(structure, synced.setLastName("Synced"), true);
        Assertions.assertEquals(1L, countByQuery(structure, "lastName: Synced"));

        jsonEntitiesService.deleteById(structure.getId(), plain.getId(), participant).join();
        Assertions.assertEquals(2L, count(structure));
        jsonEntitiesService.deleteByIdSync(structure.getId(), synced.getId(), participant).join();
        Assertions.assertEquals(0L, count(structure));
    }

    @Test
    public void adminDeleteByIdSyncIsSearchableOnReturn() throws IOException {
        Structure structure = createStructure("AdminSync", null, true);

        SyncPerson saved = rpcSave(structure, person("Ada", "Lovelace").setTenantId(TENANT), true);
        EntityContext tenantContext = new DefaultEntityContext(participant).setTenantSelection(List.of(TENANT));
        Assertions.assertEquals(1L, entitiesService.count(structure.getId(), tenantContext).join());

        adminJsonEntitiesService.deleteById(structure.getId(), TenantSpecificId.create(saved.getId(), TENANT), participant)
                                .join();
        Assertions.assertEquals(1L,
                                entitiesService.count(structure.getId(), tenantContext).join(),
                                "A plain delete is searchable, so refreshes are not off");

        SyncPerson second = rpcSave(structure, person("Charles", "Babbage").setTenantId(TENANT), true);
        adminJsonEntitiesService.deleteByIdSync(structure.getId(), TenantSpecificId.create(second.getId(), TENANT), participant)
                                .join();
        Assertions.assertEquals(0L, entitiesService.count(structure.getId(), tenantContext).join());
    }

    @Test
    public void restSyncWritesAreSearchableOnReturn() throws Exception {
        Structure structure = createStructure("RestSync", null, false);

        HttpResponse<String> saved = rest(structure, "POST", "/save-sync", person("Ada", "Lovelace"));
        Assertions.assertEquals(200, saved.statusCode(), saved::body);
        SyncPerson person = objectMapper.readValue(saved.body(), SyncPerson.class);
        Assertions.assertEquals(1L, count(structure));

        HttpResponse<String> updated = rest(structure, "POST", "/update-sync", person.setLastName("Synced"));
        Assertions.assertEquals(200, updated.statusCode(), updated::body);
        Assertions.assertEquals("Synced", objectMapper.readValue(updated.body(), SyncPerson.class).getLastName());
        Assertions.assertEquals(1L, countByQuery(structure, "lastName: Synced"));

        HttpResponse<String> deleted = rest(structure, "DELETE", "/" + person.getId() + "/sync", null);
        Assertions.assertEquals(200, deleted.statusCode(), deleted::body);
        Assertions.assertEquals(0L, count(structure));
    }

    @Test
    public void graphQlSyncWritesAreSearchableOnReturn() throws Exception {
        Structure structure = createStructure("GqlSync", null, false);
        String name = structure.getName();

        String id = UUID.randomUUID().toString();
        JsonNode saved = graphQl("mutation { saveSync%s(input: {id: \"%s\", firstName: \"Ada\", lastName: \"Lovelace\"}) { id } }"
                                         .formatted(name, id))
                .path("saveSync" + name);
        Assertions.assertEquals(id, saved.path("id").asString(), saved::toString);
        Assertions.assertEquals(1L, count(structure));

        JsonNode updated = graphQl("mutation { updateSync%s(input: {id: \"%s\", lastName: \"Synced\"}) { lastName } }"
                                           .formatted(name, id))
                .path("updateSync" + name);
        Assertions.assertEquals("Synced", updated.path("lastName").asString());
        Assertions.assertEquals(1L, countByQuery(structure, "lastName: Synced"));

        JsonNode deleted = graphQl("mutation { deleteSync%s(id: \"%s\") }".formatted(name, id))
                .path("deleteSync" + name);
        Assertions.assertEquals(id, deleted.asString());
        Assertions.assertEquals(0L, count(structure));
    }

    /**
     * saveSync for Clash and save for SyncClash share a GraphQL name, and the non-Sync mutation must keep it
     */
    @Test
    public void syncOperationNamesDoNotReplaceExistingOnes() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Structure clash = createStructure("Clash", suffix, null, false);
        Structure syncClash = createStructure("SyncClash", suffix, null, false);
        String sharedName = "saveSync" + clash.getName();

        String id = UUID.randomUUID().toString();
        JsonNode saved = graphQl("mutation { %s(input: {id: \"%s\", firstName: \"Ada\", lastName: \"Lovelace\"}) { id } }"
                                         .formatted(sharedName, id))
                .path(sharedName);
        Assertions.assertEquals(id, saved.path("id").asString(), saved::toString);
        entitiesService.syncIndex(syncClash.getId(), context).join();
        entitiesService.syncIndex(clash.getId(), context).join();
        Assertions.assertEquals(1L, count(syncClash), "The mutation should still save " + syncClash.getName());
        Assertions.assertEquals(0L, count(clash));
    }

    /**
     * Denies one write at a time. Its Sync twin must be denied too, over RPC and REST and through the admin service,
     * while the other Sync writes stay allowed, so each Sync write is authorized as its own write and no other.
     */
    @Test
    public void eachSyncWriteUsesThePolicyOfItsOwnWrite() throws Exception {
        List<EntityServiceDecorator> denied = List.of(new PolicyDecorator().setPolicies(List.of(List.of(DENIED_POLICY))));
        for (String deniedWrite : List.of("save", "update", "deleteById")) {
            EntityServiceDecoratorsConfig config = new EntityServiceDecoratorsConfig();
            switch (deniedWrite) {
                case "save" -> config.setSave(denied);
                case "update" -> config.setUpdate(denied);
                default -> config.setDeleteById(denied);
            }
            // With a tenant id field, so the admin delete applies too
            Structure structure = createStructure("Policy" + deniedWrite,
                                                  new EntityServiceDecoratorsDecorator().setConfig(config),
                                                  true);
            SyncPerson person = person("Ada", "Lovelace").setTenantId(TENANT);
            TenantSpecificId tenantSpecificId = TenantSpecificId.create(person.getId(), TENANT);

            Map<String, Supplier<CompletableFuture<?>>> rpc = new LinkedHashMap<>();
            rpc.put("save", () -> jsonEntitiesService.saveSync(structure.getId(), toTokenBuffer(person), participant));
            rpc.put("update", () -> jsonEntitiesService.updateSync(structure.getId(), toTokenBuffer(person), participant));
            rpc.put("deleteById", () -> jsonEntitiesService.deleteByIdSync(structure.getId(), person.getId(), participant));

            for (Map.Entry<String, Supplier<CompletableFuture<?>>> call : rpc.entrySet()) {
                String what = call.getKey() + "Sync with " + deniedWrite + " denied";
                if (call.getKey().equals(deniedWrite)) {
                    assertDenied(call.getValue(), what);
                } else {
                    Assertions.assertDoesNotThrow(() -> call.getValue().get().join(), what);
                }
            }

            Map<String, HttpResponse<String>> rest = new LinkedHashMap<>();
            rest.put("save", rest(structure, "POST", "/save-sync", person));
            rest.put("update", rest(structure, "POST", "/update-sync", person));
            rest.put("deleteById", rest(structure, "DELETE", "/" + person.getId() + "/sync", null));
            for (Map.Entry<String, HttpResponse<String>> response : rest.entrySet()) {
                int expected = response.getKey().equals(deniedWrite) ? 403 : 200;
                Assertions.assertEquals(expected,
                                        response.getValue().statusCode(),
                                        () -> "REST " + response.getKey() + "Sync with " + deniedWrite + " denied: " + response.getValue().body());
            }

            // The admin deletes are authorized as deleteById as well
            boolean deleteDenied = deniedWrite.equals("deleteById");
            Supplier<CompletableFuture<?>> adminDelete
                    = () -> adminJsonEntitiesService.deleteByIdSync(structure.getId(), tenantSpecificId, participant);
            if (deleteDenied) {
                assertDenied(adminDelete, "admin deleteByIdSync with deleteById denied");
            } else {
                Assertions.assertDoesNotThrow(() -> adminDelete.get().join(), "admin deleteByIdSync with " + deniedWrite + " denied");
            }
            HttpResponse<String> adminRest = adminRest(structure, "DELETE", "/" + TENANT + "/" + person.getId() + "/sync");
            Assertions.assertEquals(deleteDenied ? 403 : 200,
                                    adminRest.statusCode(),
                                    () -> "admin REST deleteByIdSync with " + deniedWrite + " denied: " + adminRest.body());
        }
    }

    private static void assertDenied(Supplier<CompletableFuture<?>> call, String what) {
        CompletionException thrown = Assertions.assertThrows(CompletionException.class, () -> call.get().join(), what);
        Assertions.assertInstanceOf(AuthorizationException.class,
                                    thrown.getCause(),
                                    () -> what + ": expected the write to be denied but got " + thrown);
    }

    /**
     * Creates and publishes a Person structure, with scheduled refreshes switched off on its index.
     *
     * @param decorators     decorators for the structure's entity service, or null for none
     * @param tenantSelection true to add a tenant id field, which the admin operations need
     */
    private Structure createStructure(String prefix,
                                      EntityServiceDecoratorsDecorator decorators,
                                      boolean tenantSelection) throws IOException {
        return createStructure(prefix, String.valueOf(System.nanoTime()), decorators, tenantSelection);
    }

    private Structure createStructure(String prefix,
                                      String suffix,
                                      EntityServiceDecoratorsDecorator decorators,
                                      boolean tenantSelection) throws IOException {
        String name = prefix + suffix;
        // GraphQL names its types after the schema, which must be unique within the application
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED).setName(name);
        if (tenantSelection) {
            schema.addProperty("tenantId", new StringC3Type(), List.of(new TenantIdDecorator()));
        }
        if (decorators != null) {
            schema.addDecorator(decorators);
        }

        Structure toCreate = new Structure();
        toCreate.setName(name);
        toCreate.setApplicationId(APPLICATION_ID);
        toCreate.setProjectId(APPLICATION_ID + "_default");
        toCreate.setDescription("Defines a Person for the Sync write tests");
        toCreate.setEntityDefinition(schema);

        Structure structure = applicationService.createApplicationIfNotExist(APPLICATION_ID, "Sync write tests")
                                                .thenCompose(v -> structureService.create(toCreate))
                                                .thenCompose(created -> structureService.publish(created.getId())
                                                                                        .thenCompose(v -> structureService.findById(created.getId())))
                                                .join();

        client.indices().putSettings(b -> b.index(structure.getItemIndex())
                                           .settings(s -> s.refreshInterval(t -> t.time("-1"))));
        return structure;
    }

    private static SyncPerson person(String firstName, String lastName) {
        return new SyncPerson().setId(UUID.randomUUID().toString()).setFirstName(firstName).setLastName(lastName);
    }

    private long count(Structure structure) {
        return entitiesService.count(structure.getId(), context).join();
    }

    private long countByQuery(Structure structure, String query) {
        return entitiesService.countByQuery(structure.getId(), query, context).join();
    }

    private SyncPerson rpcSave(Structure structure, SyncPerson person, boolean sync) {
        TokenBuffer entity = toTokenBuffer(person);
        return read(sync
                    ? jsonEntitiesService.saveSync(structure.getId(), entity, participant)
                    : jsonEntitiesService.save(structure.getId(), entity, participant));
    }

    private SyncPerson rpcUpdate(Structure structure, SyncPerson person, boolean sync) {
        TokenBuffer entity = toTokenBuffer(person);
        return read(sync
                    ? jsonEntitiesService.updateSync(structure.getId(), entity, participant)
                    : jsonEntitiesService.update(structure.getId(), entity, participant));
    }

    private HttpResponse<String> rest(Structure structure, String method, String pathSuffix, Object body) throws Exception {
        return send(structuresProperties.getOpenApiPath(), structure, method, pathSuffix, body);
    }

    private HttpResponse<String> adminRest(Structure structure, String method, String pathSuffix) throws Exception {
        return send(structuresProperties.getOpenApiAdminPath(), structure, method, pathSuffix, null);
    }

    private HttpResponse<String> send(String basePath,
                                      Structure structure,
                                      String method,
                                      String pathSuffix,
                                      Object body) throws Exception {
        String application = structure.getApplicationId().toLowerCase();
        String name = structure.getName().toLowerCase();
        URI uri = URI.create("http://localhost:" + structuresProperties.getOpenApiPort()
                                     + basePath + application + "/" + name + pathSuffix);
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body))
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(uri)
                                         .timeout(Duration.ofSeconds(30))
                                         .header("Authorization", BASIC_AUTH)
                                         .header("Content-Type", "application/json")
                                         .method(method, publisher)
                                         .build();
        return send(request);
    }

    /**
     * @return the data of a GraphQL response, after checking it has no errors
     */
    private JsonNode graphQl(String query) throws Exception {
        URI uri = URI.create("http://localhost:" + structuresProperties.getGraphqlPort()
                                     + structuresProperties.getGraphqlPath() + APPLICATION_ID);
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

    private TokenBuffer toTokenBuffer(Object entity) {
        TokenBuffer tokenBuffer = TokenBuffer.forGeneration();
        objectMapper.writeValue(tokenBuffer, entity);
        return tokenBuffer;
    }

    private SyncPerson read(CompletableFuture<TokenBuffer> future) {
        try (JsonParser parser = future.join().asParser()) {
            return objectMapper.readValue(parser, SyncPerson.class);
        }
    }

    // Leaves out a missing tenantId, which only some of these structures have
    @Getter
    @Setter
    @Accessors(chain = true)
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SyncPerson {
        private String id;
        private String firstName;
        private String lastName;
        private String tenantId;
    }
}
