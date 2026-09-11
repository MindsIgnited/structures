package org.kinotic.structures.tests.core.idl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecoratorsDecorator;
import org.kinotic.structures.api.domain.idl.decorators.IdDecorator;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.kinotic.structures.api.domain.FastestType;
import org.kinotic.structures.api.domain.RawJson;

import tools.jackson.databind.json.JsonMapper;

/**
 * Continuum serializes the IDL with Jackson 3 while Structures' own code is built on Jackson 2, so the
 * Structures {@link C3Decorator} subtypes have to be registered with both mappers. When only the Jackson 2
 * module knew them, continuum's mapper resolved nothing but its own built-in decorators and every schema
 * carrying a Structures decorator failed to deserialize with:
 * <p>
 * {@code Could not resolve type id 'EntityServiceDecorators' as a subtype of C3Decorator: known type ids = [NotNull]}
 * <p>
 * That is invisible to a Jackson 2 only test, which is why this asserts against the Jackson 3 mapper.
 */
class IdlJacksonInteropTest extends ElasticTestBase {

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void structuresDecoratorsRoundTripThroughContinuumsMapper() {
        ObjectC3Type schema = new ObjectC3Type()
                .setName("Person")
                .setNamespace("org.kinotic.structures.tests")
                .addDecorator(new EntityServiceDecoratorsDecorator())
                .addProperty("id", new StringC3Type(), List.of(new IdDecorator()));

        ObjectC3Type result = jsonMapper.readValue(jsonMapper.writeValueAsString(schema), ObjectC3Type.class);

        List<C3Decorator> typeDecorators = result.getDecorators();
        assertEquals(1, typeDecorators.size());
        assertInstanceOf(EntityServiceDecoratorsDecorator.class, typeDecorators.getFirst());

        List<C3Decorator> idDecorators = result.getProperties().getFirst().getDecorators();
        assertEquals(1, idDecorators.size());
        assertInstanceOf(IdDecorator.class, idDecorators.getFirst());
    }

    /**
     * JsonEntitiesService takes and returns Jackson 2 TokenBuffers, and RawJson/FastestType ride on the other
     * published signatures. Continuum's Jackson 3 mapper has no idea what those are on its own, which surfaced
     * against the built image as:
     * <p>
     * {@code Cannot deserialize value of type `com.fasterxml.jackson.databind.util.TokenBuffer` from Array value}
     */
    @Test
    void jackson2PayloadTypesRoundTripThroughContinuumsMapper() throws Exception {
        String json = "{\"id\":\"1\",\"name\":\"Bob\",\"tags\":[\"a\",\"b\"]}";

        TokenBuffer buffer = objectMapper.readValue(json, TokenBuffer.class);
        TokenBuffer bufferResult = jsonMapper.readValue(jsonMapper.writeValueAsString(buffer), TokenBuffer.class);
        assertEquals(objectMapper.readTree(json),
                     objectMapper.readTree(objectMapper.writeValueAsString(bufferResult)));

        RawJson rawJson = new RawJson(json.getBytes(StandardCharsets.UTF_8));
        RawJson rawJsonResult = jsonMapper.readValue(jsonMapper.writeValueAsString(rawJson), RawJson.class);
        assertEquals(objectMapper.readTree(json),
                     objectMapper.readTree(rawJsonResult.data()));

        // FastestType is a return type only, so serialization is what has to hold
        FastestType fastest = new FastestType(Map.of("id", "1"));
        assertEquals(objectMapper.readTree("{\"id\":\"1\"}"),
                     objectMapper.readTree(jsonMapper.writeValueAsString(fastest)));
    }

    /**
     * A TokenBuffer holding an array is what bulkSave receives, and the array shape is what the reported
     * failure named explicitly.
     */
    @Test
    void tokenBufferArrayRoundTripsThroughContinuumsMapper() throws Exception {
        String json = "[{\"id\":\"1\"},{\"id\":\"2\"}]";

        TokenBuffer buffer = objectMapper.readValue(json, TokenBuffer.class);
        TokenBuffer result = jsonMapper.readValue(jsonMapper.writeValueAsString(buffer), TokenBuffer.class);

        assertEquals(objectMapper.readTree(json),
                     objectMapper.readTree(objectMapper.writeValueAsString(result)));
    }

    /**
     * Boot 3 disabled WRITE_DATES_AS_TIMESTAMPS and WRITE_DURATIONS_AS_TIMESTAMPS on the mapper it
     * auto-configured. Declaring the mapper by hand dropped that, and since Jackson then writes epoch
     * numbers instead of ISO-8601 it changes both API responses and what the Elasticsearch client
     * stores, symmetrically enough that reading our own data back still works.
     * <p>
     * Structure.created, updated and publishedTimestamp are {@link Date}, so this is not hypothetical.
     */
    @Test
    void datesSerializeAsTextRatherThanEpochNumbers() throws Exception {
        assertEquals("\"2023-11-14T22:13:20.000+00:00\"",
                     objectMapper.writeValueAsString(new Date(1700000000000L)));

        assertEquals("\"2023-11-14T22:13:20Z\"",
                     objectMapper.writeValueAsString(Instant.ofEpochMilli(1700000000000L)));

        assertEquals("\"PT1H\"", objectMapper.writeValueAsString(Duration.ofHours(1)));
    }
}
