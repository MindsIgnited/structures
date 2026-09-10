package org.kinotic.structures;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// ReactiveElasticsearchClientAutoConfiguration was removed in Spring Boot 4, so there is nothing to exclude
@SpringBootApplication
@EnableConfigurationProperties
public class StructuresMigrationApplication {
	public static void main(String[] args) {
		SpringApplication.run(StructuresMigrationApplication.class, args);
	}
}
