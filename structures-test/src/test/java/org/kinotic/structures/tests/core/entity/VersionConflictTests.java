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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.MultiTenancyType;
import org.kinotic.structures.api.domain.idl.decorators.VersionDecorator;
import org.kinotic.structures.api.exceptions.VersionConflictException;
import org.kinotic.structures.api.services.ApplicationService;
import org.kinotic.structures.api.services.EntitiesService;
import org.kinotic.structures.api.services.StructureService;
import org.kinotic.structures.internal.api.domain.DefaultEntityContext;
import org.kinotic.structures.internal.sample.DummyParticipant;
import org.kinotic.structures.internal.sample.TestDataService;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.kinotic.structures.tests.core.entity.AbstractEntityRefreshTests.VersionedPerson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * A write that loses an optimistic locking race must reach the caller as a {@link VersionConflictException},
 * and as a 409 over REST, not as the raw Elasticsearch error or a 500.
 */
public class VersionConflictTests extends ElasticTestBase {

    // DummySecurityService accepts these over basic auth, as a participant of the "kinotic" tenant
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
                                                              .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));

    // The REST API defaults to 8080, which a local KinD cluster also publishes; a request could reach that instead
    private static final int OPEN_API_PORT = freePort();

    @DynamicPropertySource
    static void openApiPort(DynamicPropertyRegistry registry) {
        registry.add("structures.open-api-port", () -> OPEN_API_PORT);
    }

    @Autowired
    private ApplicationService applicationService;
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

    // The default DummyParticipant is in the "kinotic" tenant too, so REST calls see what these saves wrote
    private final EntityContext context = new DefaultEntityContext(new DummyParticipant());
    private Structure structure;

    @BeforeEach
    public void createStructure() {
        ObjectC3Type schema = testDataService.createPersonSchema(MultiTenancyType.SHARED)
                                             .addProperty("version", new StringC3Type(), List.of(new VersionDecorator()));
        Structure toCreate = new Structure();
        toCreate.setName("ConflictPerson_" + System.nanoTime());
        toCreate.setApplicationId("org.kinotic.sample");
        toCreate.setProjectId("org.kinotic.sample_default");
        toCreate.setDescription("Defines a Person with a version field");
        toCreate.setEntityDefinition(schema);

        structure = applicationService.createApplicationIfNotExist("org.kinotic.sample", "Sample application")
                                      .thenCompose(v -> structureService.create(toCreate))
                                      .thenCompose(created -> structureService.publish(created.getId())
                                                                              .thenCompose(v -> structureService.findById(created.getId())))
                                      .join();
    }

    @Test
    public void updateWithAStaleVersionIsAVersionConflict() {
        VersionedPerson saved = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));
        update(copyOf(saved).setLastName("Byron"));

        assertVersionConflict(() -> update(saved.setFirstName("Augusta")));
    }

    @Test
    public void saveWithAStaleVersionIsAVersionConflict() {
        VersionedPerson saved = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));
        update(copyOf(saved).setLastName("Byron"));

        assertVersionConflict(() -> save(saved.setFirstName("Augusta")));
    }

    @Test
    public void savingAnExistingIdWithoutAVersionIsAVersionConflict() {
        VersionedPerson saved = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));

        // Without a version a save is a create, which Elasticsearch refuses for an id it already has
        assertVersionConflict(() -> save(new VersionedPerson().setId(saved.getId()).setFirstName("Augusta")));
    }

    @Test
    public void bulkUpdateWithAStaleItemIsAVersionConflict() {
        VersionedPerson stale = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));
        VersionedPerson current = save(new VersionedPerson().setFirstName("Charles").setLastName("Babbage"));
        update(copyOf(stale).setLastName("Byron"));

        stale.setFirstName("Augusta");
        current.setFirstName("Charlie");
        assertVersionConflict(() -> entitiesService.bulkUpdate(structure.getId(),
                                                               toTokenBuffer(List.of(stale, current)),
                                                               context).join());

        // Elasticsearch applies each bulk item on its own, so the one with a current version was still written
        Assertions.assertEquals("Charlie", findById(current.getId()).getFirstName());
        Assertions.assertEquals("Ada", findById(stale.getId()).getFirstName());
    }

    @Test
    public void restUpdateWithAStaleVersionAnswers409() throws Exception {
        VersionedPerson saved = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));
        update(copyOf(saved).setLastName("Byron"));

        HttpResponse<String> response = post("/update", saved.setFirstName("Augusta"));

        Assertions.assertEquals(409, response.statusCode(), response::body);
        String error = objectMapper.readTree(response.body()).path("error").asString();
        Assertions.assertTrue(error.contains("version conflict"), error);
        assertNoElasticsearchDetails(error);
    }

    @Test
    public void restSaveAnswers200WhenThereIsNoConflict() throws Exception {
        VersionedPerson saved = save(new VersionedPerson().setFirstName("Ada").setLastName("Lovelace"));

        HttpResponse<String> response = post("", saved.setFirstName("Augusta"));

        Assertions.assertEquals(200, response.statusCode(), response::body);
    }

    private static void assertVersionConflict(Executable executable) {
        CompletionException thrown = Assertions.assertThrows(CompletionException.class, executable);
        VersionConflictException conflict = Assertions.assertInstanceOf(VersionConflictException.class,
                                                                        thrown.getCause(),
                                                                        () -> "Expected a version conflict but got " + thrown);
        Assertions.assertTrue(conflict.getMessage().contains("version conflict"), conflict.getMessage());
        assertNoElasticsearchDetails(conflict.getMessage());
    }

    // The low level client's own message names the Elasticsearch host and request URI, which callers must not see
    private static void assertNoElasticsearchDetails(String message) {
        Assertions.assertFalse(message.contains("host ["), message);
        Assertions.assertFalse(message.contains("URI ["), message);
    }

    private HttpResponse<String> post(String pathSuffix, Object body) throws Exception {
        String application = structure.getApplicationId().toLowerCase();
        String name = structure.getName().toLowerCase();
        URI uri = URI.create("http://localhost:" + structuresProperties.getOpenApiPort()
                                     + structuresProperties.getOpenApiPath() + application + "/" + name + pathSuffix);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                                             .uri(uri)
                                             .timeout(Duration.ofSeconds(30))
                                             .header("Authorization", BASIC_AUTH)
                                             .header("Content-Type", "application/json")
                                             .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                                             .build();
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

    private VersionedPerson save(VersionedPerson person) {
        return read(entitiesService.save(structure.getId(), toTokenBuffer(person), context));
    }

    private VersionedPerson update(VersionedPerson person) {
        return read(entitiesService.update(structure.getId(), toTokenBuffer(person), context));
    }

    private VersionedPerson findById(String id) {
        RawJson rawJson = entitiesService.findById(structure.getId(), id, RawJson.class, context).join();
        return objectMapper.readValue(rawJson.data(), VersionedPerson.class);
    }

    private VersionedPerson copyOf(VersionedPerson person) {
        return objectMapper.convertValue(person, VersionedPerson.class);
    }

    private TokenBuffer toTokenBuffer(Object entity) {
        TokenBuffer tokenBuffer = TokenBuffer.forGeneration();
        objectMapper.writeValue(tokenBuffer, entity);
        return tokenBuffer;
    }

    private VersionedPerson read(CompletableFuture<TokenBuffer> future) {
        try (JsonParser parser = future.join().asParser()) {
            return objectMapper.readValue(parser, VersionedPerson.class);
        }
    }
}
