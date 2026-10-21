package org.kinotic.structures.internal.utils;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Created by Navíd Mitchell 🤪 on 5/7/24.
 */
public class OpenApiUtils {

    public static void addCursorPagingWithoutSortParameters(Operation operation){
        operation.addParametersItem(new Parameter().name("cursor")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new StringSchema().nullable(true))
                                                   .description("The page to get with a cursor. Use null to get the first page."));
        operation.addParametersItem(new Parameter().name("size")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new IntegerSchema()._default(25)
                                                                              .maximum(BigDecimal.valueOf(1000)))
                                                   .description("The number of items per page. The default is 25."));
    }

    public static void addPagingAndSortingParameters(Operation operation){
        operation.addParametersItem(new Parameter().name("page")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new IntegerSchema().nullable(true))
                                                   .description("The page number to get. The first page is 0. (This is not needed if cursor paging is being used.)"));

        operation.addParametersItem(new Parameter().name("cursor")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new StringSchema().nullable(true))
                                                   .description("The page to get with a cursor. Use null to get the first page. (This is not needed if offset paging is being used.)"));

        operation.addParametersItem(new Parameter().name("size")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new IntegerSchema()._default(25)
                                                                              .maximum(BigDecimal.valueOf(1000)))
                                                   .description("The number of items per page. The default is 25."));
        addSortingParameters(operation);
    }

    /**
     * Adds the {@code waitFor} query parameter the Sync writes take
     */
    public static void addWaitForParameter(Operation operation){
        operation.addParametersItem(new Parameter().name("waitFor")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new BooleanSchema()._default(false))
                                                   .description("True to wait for the next scheduled refresh instead of forcing one. "
                                                                        + "The call then takes up to the index's refresh interval (1 second by default) longer. "
                                                                        + "If that interval is longer than the server's Elasticsearch timeout, or the client's, "
                                                                        + "the call fails with a timeout even though the write was applied."));
    }

    public static void addSortingParameters(Operation operation){
        operation.addParametersItem(new Parameter().name("sort")
                                                   .in("query")
                                                   .required(false)
                                                   .schema(new StringSchema())
                                                   .description("The field to apply sorting to."
                                                                        +  " Multiple sort fields can be applied by separating them with a comma."
                                                                        +  " The sort order for each sort field will be ascending unless it is prefixed with a minus (“-“), in which case it will be descending.")
                                                   .examples(Map.of("Single Sort", new Example().value("name"),
                                                                    "Multiple Sort", new Example().value("name,-age"))));
    }

    public static RequestBody createJsonRequest(Schema<?> schema, String description){
        return new RequestBody()
                .description(description)
                .content(new Content().addMediaType("application/json",
                                                    new MediaType().schema(schema)));
    }

    public static ObjectSchema createPageSchema(Schema<?> pageContentSchema){
        ObjectSchema pageSchema = new ObjectSchema();
        pageSchema.addProperty("content", new ArraySchema()
                          .items(pageContentSchema))
                          .description("The content of the page.")
                  .addProperty("totalElements", new IntegerSchema()
                          .nullable(true)
                          .description("The total number of entities."))
                  .addProperty("cursor", new StringSchema()
                          .nullable(true)
                          .description("The cursor to be used for subsequent retrieval of data. Null if no more data is available, or if cursor paging is not being used."));
        return pageSchema;
    }

    public static ArraySchema createStringArraySchema(String description){
        ArraySchema arraySchema = new ArraySchema();
        arraySchema.items(new StringSchema());
        arraySchema.description(description);
        return arraySchema;
    }

    public static Parameter createPathParameter(String name,
                                                String description){
        return new Parameter().name(name)
                              .in("path")
                              .required(true)
                              .schema(new StringSchema())
                              .description(description);
    }

    public static RequestBody createArrayRequest(Schema<?> items, String description){
        ArraySchema arraySchema = new ArraySchema();
        arraySchema.items(items);
        return createJsonRequest(arraySchema, description);
    }

    public static RequestBody createStringArrayRequest(String description){
        return createArrayRequest(new StringSchema(), description);
    }

    public static RequestBody createTextRequest(String description){
        return new RequestBody()
                .description(description)
                .content(new Content().addMediaType("text/plain",
                                                    new MediaType().schema(new StringSchema())));
    }

}
