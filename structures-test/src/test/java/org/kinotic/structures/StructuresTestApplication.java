package org.kinotic.structures;

import org.kinotic.structures.support.keycloak.KeycloakTestContextInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;


// Spring Boot 4 moved these auto-configurations into optional modules that are not on our classpath,
// so they are excluded by name. ReactiveElasticsearchClientAutoConfiguration no longer exists at all.
@SpringBootApplication(excludeName = {"org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = KeycloakTestContextInitializer.class)
@EnableConfigurationProperties
public class StructuresTestApplication {
    public static void main(String[] args) {
		SpringApplication.run(StructuresTestApplication.class, args);
	}

}
