package org.kinotic.structuresserver;

import org.kinotic.continuum.api.annotations.EnableContinuum;
import org.kinotic.continuum.gateway.api.annotations.EnableContinuumGateway;
import org.kinotic.structures.api.annotations.EnableStructures;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Spring Boot 4 moved these auto-configurations into optional modules that are not on our classpath,
// so they are excluded by name. ReactiveElasticsearchClientAutoConfiguration no longer exists at all.
@SpringBootApplication(excludeName = {"org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration",
									  "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"})
@EnableContinuum
@EnableContinuumGateway
@EnableStructures
public class StructuresServerApplication {
	public static void main(String[] args) {
		SpringApplication.run(StructuresServerApplication.class, args);
	}
}
