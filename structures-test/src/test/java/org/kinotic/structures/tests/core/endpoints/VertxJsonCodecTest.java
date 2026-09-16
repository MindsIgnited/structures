package org.kinotic.structures.tests.core.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.Test;

/**
 * Vert.x's JSON codec is built on Jackson 2, which reaches the classpath only as a transitive of
 * Vert.x and a few other libraries, at whatever version the Spring Boot BOM selects. Vert.x compiles
 * against a newer Jackson 2 than Boot manages, so this pins that the codec links: every Vert.x JSON
 * entry point Structures relies on, through the databind-backed paths that name Jackson 2 members.
 */
class VertxJsonCodecTest {

    public record Widget(String name, int size) { }

    @Test
    void vertxJsonCodecLinksAgainstTheJackson2OnTheClasspath() throws Exception {
        Widget widget = new Widget("widget", 3);

        JsonObject json = JsonObject.mapFrom(widget);
        assertEquals("widget", json.getString("name"));
        assertEquals(widget, json.mapTo(Widget.class));

        Buffer encoded = Json.encodeToBuffer(new JsonArray().add(json));
        List<Widget> decoded = new DatabindCodec().fromBuffer(encoded, new TypeReference<List<Widget>>() { });
        assertEquals(List.of(widget), decoded);

        Map<String, Object> asMap = DatabindCodec.mapper().convertValue(widget, new TypeReference<Map<String, Object>>() { });
        assertEquals(Map.of("name", "widget", "size", 3), asMap);
        assertEquals("{\"name\":\"widget\",\"size\":3}", DatabindCodec.mapper().writeValueAsString(widget));
    }
}
