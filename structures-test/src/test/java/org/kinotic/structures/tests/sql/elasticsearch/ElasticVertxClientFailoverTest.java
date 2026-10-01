package org.kinotic.structures.tests.sql.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.core.api.crud.Page;
import org.kinotic.structures.api.config.ElasticConnectionInfo;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.api.domain.QueryOptions;
import org.kinotic.structures.auth.internal.services.DefaultCaffeineCacheFactory;
import org.kinotic.structures.internal.api.services.sql.elasticsearch.DefaultElasticVertxClient;
import org.kinotic.structures.support.elastic.ElasticsearchTestConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Named queries reach Elasticsearch through {@link DefaultElasticVertxClient}. These pin that its requests are spread
 * over every configured connection and survive a node that is gone, the way the Elasticsearch RestClient's do, rather
 * than every named query failing with the first connection.
 */
class ElasticVertxClientFailoverTest {

    private static final String SQL_RESPONSE = "{\"columns\":[{\"name\":\"one\",\"type\":\"integer\"}],\"rows\":[[1]]}";
    private static final String UNAVAILABLE_RESPONSE =
            "{\"error\":{\"type\":\"unavailable_shards_exception\",\"reason\":\"no shards\"},\"status\":503}";

    private Vertx vertx;
    private final List<DefaultElasticVertxClient> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() throws Exception {
        clients.forEach(DefaultElasticVertxClient::destroy);
        vertx.close().await(10, TimeUnit.SECONDS);
    }

    @Test
    void requestsAreSpreadOverEveryNode() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1),
                                                  respondingNode(first, 200, SQL_RESPONSE),
                                                  respondingNode(second, 200, SQL_RESPONSE),
                                                  respondingNode(third, 200, SQL_RESPONSE));

        for (int i = 0; i < 9; i++) {
            assertEquals(List.of(Map.of("one", 1)), selectOne(client));
        }

        assertEquals(List.of(3, 3, 3), List.of(first.get(), second.get(), third.get()));
    }

    @Test
    void queriesSucceedWhileNodesAreUnavailable() throws Exception {
        AtomicInteger unavailableRequests = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1),
                                                  respondingNode(unavailableRequests, 503, UNAVAILABLE_RESPONSE),
                                                  refusingNode(),
                                                  elasticsearchNode());

        for (int i = 0; i < 6; i++) {
            assertEquals(List.of(Map.of("one", 1)), selectOne(client));
        }

        assertEquals(1, unavailableRequests.get(), "the unavailable node was left out of the rotation after it failed");
    }

    @Test
    void translateFailsOverToo() throws Exception {
        createIndex("failover_test");
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), refusingNode(), elasticsearchNode());

        for (int i = 0; i < 2; i++) {
            assertNotNull(client.translateSql("SELECT * FROM failover_test", null).get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void whenNoNodeCanBeReachedTheErrorNamesEveryNode() {
        ElasticConnectionInfo first = refusingNode();
        ElasticConnectionInfo second = refusingNode();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), first, second);

        ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client));

        IllegalStateException cause = assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(cause.getMessage().contains(first.toHostAndPort()), cause.getMessage());
        assertTrue(cause.getMessage().contains(second.toHostAndPort()), cause.getMessage());
        assertInstanceOf(ConnectException.class, cause.getCause());
        assertEquals(1, cause.getSuppressed().length);
    }

    @Test
    void aSingleUnreachableNodeFailsWithTheOriginalCause() {
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), refusingNode());

        ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client));

        assertInstanceOf(ConnectException.class, e.getCause());
    }

    @Test
    void theLastNodesUnavailableResponseIsReportedAsElasticsearchSentIt() {
        AtomicInteger requests = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), respondingNode(requests, 503, UNAVAILABLE_RESPONSE));

        ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client));

        assertEquals("SQL unavailable_shards_exception no shards", e.getCause().getMessage());
    }

    @Test
    void aRequestThatTimesOutIsNotRetriedAndTheNodeStaysInTheRotation() throws Exception {
        AtomicInteger slowRequests = new AtomicInteger();
        AtomicInteger otherRequests = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMillis(300),
                                                  silentNode(slowRequests),
                                                  respondingNode(otherRequests, 200, SQL_RESPONSE));

        ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client));
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertEquals(0, otherRequests.get(), "a slow query is not run a second time on another node");

        assertEquals(List.of(Map.of("one", 1)), selectOne(client));
        assertThrows(ExecutionException.class, () -> selectOne(client));
        assertEquals(2, slowRequests.get(), "the slow node was not taken out of the rotation");
    }

    @Test
    void elasticsearchAcceptsTheRequestTimeoutInSeconds() throws Exception {
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), elasticsearchNode());

        assertEquals(List.of(Map.of("one", 1)), selectOne(client, requestTimeout(30)));
    }

    @Test
    void theRequestTimeoutIsSentWithItsUnit() throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), node(vertx.createHttpServer().requestHandler(
                request -> request.body().onSuccess(body -> {
                    sent.set(body.toJsonObject());
                    request.response().putHeader("content-type", "application/json").end(SQL_RESPONSE);
                }))));

        selectOne(client, requestTimeout(300));

        assertEquals("300s", sent.get().getString("request_timeout"));
    }

    @Test
    void aQueryWithItsOwnRequestTimeoutIsWaitedOnForThatLong() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMillis(300), delayedNode(requests, Duration.ofSeconds(1)));

        ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client));
        assertInstanceOf(TimeoutException.class, e.getCause(), "the named query default is shorter than the query");

        assertEquals(List.of(Map.of("one", 1)), selectOne(client, requestTimeout(1)));
    }

    @Test
    void aRequestTimeoutBelowOneSecondIsRejected() {
        AtomicInteger requests = new AtomicInteger();
        DefaultElasticVertxClient client = client(Duration.ofMinutes(1), respondingNode(requests, 200, SQL_RESPONSE));

        for (int seconds : new int[]{0, -1}) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> selectOne(client, requestTimeout(seconds)));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
        assertEquals(0, requests.get());
    }

    private static QueryOptions requestTimeout(int seconds) {
        QueryOptions options = new QueryOptions();
        options.setRequestTimeout(seconds);
        return options;
    }

    private List<Map<String, Object>> selectOne(DefaultElasticVertxClient client) throws Exception {
        return selectOne(client, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> selectOne(DefaultElasticVertxClient client, QueryOptions options) throws Exception {
        Page<Map> page = client.querySql("SELECT 1 AS one", null, null, options, null, Map.class)
                               .get(30, TimeUnit.SECONDS);
        return (List) page.getContent();
    }

    private DefaultElasticVertxClient client(Duration namedQueryTimeout, ElasticConnectionInfo... connections) {
        StructuresProperties properties = new StructuresProperties()
                .setElasticConnections(List.of(connections))
                .setElasticConnectionTimeout(Duration.ofSeconds(2))
                .setElasticNamedQueryTimeout(namedQueryTimeout);
        DefaultElasticVertxClient client = new DefaultElasticVertxClient(JsonMapper.builder().build(),
                                                                         properties,
                                                                         vertx,
                                                                         new DefaultCaffeineCacheFactory(Optional.empty()));
        clients.add(client);
        return client;
    }

    private static void createIndex(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ElasticsearchTestConfiguration.getElasticsearchUrl() + "/" + name))
                                         .PUT(HttpRequest.BodyPublishers.noBody())
                                         .build();
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            // 400 when an earlier run already created it
            assertTrue(status == 200 || status == 400, "creating index " + name + " answered " + status);
        }
    }

    private static ElasticConnectionInfo elasticsearchNode() {
        return new ElasticConnectionInfo(ElasticsearchTestConfiguration.ELASTICSEARCH_CONTAINER.getHost(),
                                         ElasticsearchTestConfiguration.ELASTICSEARCH_CONTAINER.getMappedPort(9200),
                                         "http");
    }

    /**
     * A port nothing listens on, like the address a restarted node used to have
     */
    private static ElasticConnectionInfo refusingNode() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return new ElasticConnectionInfo("127.0.0.1", socket.getLocalPort(), "http");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ElasticConnectionInfo respondingNode(AtomicInteger requests, int statusCode, String body) {
        return node(vertx.createHttpServer().requestHandler(request -> {
            requests.incrementAndGet();
            request.response()
                   .setStatusCode(statusCode)
                   .putHeader("content-type", "application/json")
                   .end(body);
        }));
    }

    /**
     * Answers like Elasticsearch, but only after the delay, like a node running a slow query
     */
    private ElasticConnectionInfo delayedNode(AtomicInteger requests, Duration delay) {
        return node(vertx.createHttpServer().requestHandler(request -> {
            requests.incrementAndGet();
            vertx.setTimer(delay.toMillis(), id -> request.response()
                                                      .putHeader("content-type", "application/json")
                                                      .end(SQL_RESPONSE));
        }));
    }

    /**
     * Accepts requests and never answers them, like a node that hangs on a query
     */
    private ElasticConnectionInfo silentNode(AtomicInteger requests) {
        return node(vertx.createHttpServer().requestHandler(request -> requests.incrementAndGet()));
    }

    private static ElasticConnectionInfo node(HttpServer server) {
        try {
            int port = server.listen(0, "127.0.0.1").await(10, TimeUnit.SECONDS).actualPort();
            return new ElasticConnectionInfo("127.0.0.1", port, "http");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
