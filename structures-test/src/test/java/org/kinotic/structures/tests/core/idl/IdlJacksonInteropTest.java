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
import org.kinotic.structures.api.domain.FastestType;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.FlattenedDecorator;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.json.JsonData;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecoratorsDecorator;
import org.kinotic.structures.api.domain.idl.decorators.IdDecorator;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.TokenBuffer;

/**
 * There is one Jackson mapper in the application - the Jackson 3 one Spring Boot builds, which
 * continuum, the Elasticsearch client and Structures all share. These pin what Structures needs it
 * to do: resolve the Structures IDL subtypes by type id, carry the payload types on published
 * service signatures, and write dates as text.
 */
class IdlJacksonInteropTest extends ElasticTestBase {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void structuresDecoratorsRoundTripThroughTheSharedMapper() {
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

    @Test
    void payloadTypesRoundTripThroughTheSharedMapper() {
        String json = "{\"id\":\"1\",\"name\":\"Bob\",\"tags\":[\"a\",\"b\"]}";

        TokenBuffer buffer = jsonMapper.readValue(json, TokenBuffer.class);
        TokenBuffer bufferResult = jsonMapper.readValue(jsonMapper.writeValueAsString(buffer), TokenBuffer.class);
        assertEquals(jsonMapper.readTree(json),
                     jsonMapper.readTree(jsonMapper.writeValueAsString(bufferResult)));

        RawJson rawJson = new RawJson(json.getBytes(StandardCharsets.UTF_8));
        RawJson rawJsonResult = jsonMapper.readValue(jsonMapper.writeValueAsString(rawJson), RawJson.class);
        assertEquals(jsonMapper.readTree(json),
                     jsonMapper.readTree(rawJsonResult.data()));

        // FastestType is a return type only, so serialization is what has to hold
        FastestType fastest = new FastestType(Map.of("id", "1"));
        assertEquals(jsonMapper.readTree("{\"id\":\"1\"}"),
                     jsonMapper.readTree(jsonMapper.writeValueAsString(fastest)));
    }

    @Test
    void tokenBufferArrayRoundTripsThroughTheSharedMapper() {
        String json = "[{\"id\":\"1\"},{\"id\":\"2\"}]";

        TokenBuffer buffer = jsonMapper.readValue(json, TokenBuffer.class);
        TokenBuffer result = jsonMapper.readValue(jsonMapper.writeValueAsString(buffer), TokenBuffer.class);
        assertEquals(jsonMapper.readTree(json),
                     jsonMapper.readTree(jsonMapper.writeValueAsString(result)));
    }

    @Test
    void fieldValueOfAnyKindRoundTrips() {
        // A search cursor is the last hit's sort values serialized through this mapper; a sort on a
        // non-scalar value arrives as kind Any and has to survive the trip like the scalar kinds do
        FieldValue any = FieldValue.of(JsonData.of(Map.of("lat", 45.5, "lon", -122.6)));
        String json = jsonMapper.writeValueAsString(any);
        FieldValue back = jsonMapper.readValue(json, FieldValue.class);
        assertEquals(FieldValue.Kind.Any, back._kind());
        assertEquals(jsonMapper.readTree("{\"lat\":45.5,\"lon\":-122.6}"),
                     jsonMapper.readTree(back.anyValue().toJson().toString()));

        // and the scalar kinds still do
        for (FieldValue scalar : List.of(FieldValue.of("text"), FieldValue.of(42L), FieldValue.of(1.5), FieldValue.of(true), FieldValue.NULL)) {
            FieldValue scalarBack = jsonMapper.readValue(jsonMapper.writeValueAsString(scalar), FieldValue.class);
            assertEquals(scalar._kind(), scalarBack._kind());
            assertEquals(scalar._get(), scalarBack._get());
        }
    }

    @Test
    void nullForAPrimitiveFieldIsCoercedAsItAlwaysWas() {
        // Jackson 3 fails a null bound to a primitive by default; Jackson 2, which every client of
        // 3.5 was written against, coerced it to false or 0. A hand-built client sending
        // {"published": null} keeps working.
        Structure structure = jsonMapper.readValue("{\"name\":\"Person\",\"published\":null}", Structure.class);
        assertEquals(false, structure.isPublished());
        FlattenedDecorator decorator = jsonMapper.readValue("{\"depthLimit\":null,\"index\":null}", FlattenedDecorator.class);
        assertEquals(0, decorator.getDepthLimit());
    }

    @Test
    void datesSerializeAsTextRatherThanEpochNumbers() {
        // Boot 3 had to switch these off on Jackson 2; Jackson 3 writes text by default. Pinned so a
        // mapper customisation cannot quietly put epoch numbers back into API responses and the index.
        // Jackson 3 spells UTC as Z where Jackson 2 wrote +00:00 - the same instant, and ISO-8601 both
        // ways, so a consumer parsing the text is unaffected.
        assertEquals("\"2023-11-14T22:13:20.000Z\"",
                     jsonMapper.writeValueAsString(new Date(1700000000000L)));

        assertEquals("\"2023-11-14T22:13:20Z\"",
                     jsonMapper.writeValueAsString(Instant.ofEpochMilli(1700000000000L)));

        assertEquals("\"PT1H\"", jsonMapper.writeValueAsString(Duration.ofHours(1)));
    }
}
