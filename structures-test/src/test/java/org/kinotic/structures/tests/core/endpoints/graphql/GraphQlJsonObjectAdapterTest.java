package org.kinotic.structures.tests.core.endpoints.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.graphql.instrumentation.JsonObjectAdapter;
import org.junit.jupiter.api.Test;

/**
 * Vert.x's GraphQL module is compiled against a newer graphql-java than the one Structures pinned for
 * years, and the gap is a NoClassDefFoundError the first time its instrumentation runs - graphql-java
 * classes it names that the older jar does not have. Pins that the graphql-java on the classpath is
 * one vertx-web-graphql actually links against, through the adapter that lets a fetcher return a
 * {@link JsonObject}.
 */
class GraphQlJsonObjectAdapterTest {

    @Test
    void vertxJsonObjectAdapterLinksAgainstTheGraphQlJavaOnTheClasspath() {
        GraphQLSchema schema = new SchemaGenerator()
                .makeExecutableSchema(new SchemaParser().parse("type Query { thing: Thing } type Thing { name: String, size: Int }"),
                                      RuntimeWiring.newRuntimeWiring()
                                                   .type("Query", w -> w.dataFetcher("thing", env -> new JsonObject().put("name", "widget").put("size", 3)))
                                                   .build());

        GraphQL graphQL = GraphQL.newGraphQL(schema)
                                 .instrumentation(new JsonObjectAdapter())
                                 .build();

        ExecutionResult result = graphQL.execute("{ thing { name size } }");

        assertTrue(result.getErrors().isEmpty(), () -> "unexpected errors: " + result.getErrors());
        Map<String, Object> data = result.getData();
        assertEquals(Map.of("name", "widget", "size", 3), data.get("thing"));
    }
}
