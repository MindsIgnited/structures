package org.kinotic.structures.tests.core.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The web server answers over the transports Vert.x enables by default, HTTP/2 over cleartext (h2c)
 * included. Pinned because the Spring Boot BOM manages Netty, and a Netty older than the one Vert.x
 * was built against is a NoSuchMethodError on the event loop the first time a client upgrades - which
 * no other test sees, since they all speak HTTP/1.1.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpTransportTest extends ElasticTestBase {

    @Autowired
    private StructuresProperties structuresProperties;

    // The control, and first: a transport failure must not be able to hide behind a broken route
    @Test
    @Order(1)
    void answersOverHttp1() throws Exception {
        HttpResponse<String> response = get(HttpClient.Version.HTTP_1_1);
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
        assertEquals(200, response.statusCode(), "the health check answered");
    }

    @Test
    @Order(2)
    void answersOverHttp2Cleartext() throws Exception {
        // Java's client sends "Upgrade: h2c" for a plain http URL when asked for HTTP/2, and reports
        // the version the exchange actually completed on. A server whose HTTP/2 codec cannot be built
        // drops the connection during the upgrade instead.
        HttpResponse<String> response = get(HttpClient.Version.HTTP_2);
        assertEquals(HttpClient.Version.HTTP_2, response.version(), "the h2c upgrade must complete");
        assertEquals(200, response.statusCode(), "the health check answered");
    }

    private HttpResponse<String> get(HttpClient.Version version) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(version).connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                                             .uri(URI.create("http://localhost:" + structuresProperties.getWebServerPort() + structuresProperties.getHealthCheckPath()))
                                             .timeout(Duration.ofSeconds(10))
                                             .GET()
                                             .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
