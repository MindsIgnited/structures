package org.kinotic.structures.tests.core.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Spring Boot 3 shipped a docker-java.properties inside spring-boot-test that raised docker-java's
 * default API version; Boot 4 dropped it, and the Testcontainers Structures pinned fell back to an
 * API version modern Docker Engines refuse. Pins that the Testcontainers on the classpath negotiates
 * a usable API version on its own, so no such resource is needed in the test tree.
 */
class TestcontainersDockerApiTest {

    @Test
    void negotiatesAnApiVersionModernDockerEnginesAccept() {
        assertTrue(DockerClientFactory.instance().isDockerAvailable(), "a Docker environment was found");
        String apiVersion = DockerClientFactory.instance().client().versionCmd().exec().getApiVersion();
        assertTrue(compare(apiVersion, "1.44") >= 0, "negotiated Docker API " + apiVersion + " is at least 1.44");
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }
}
