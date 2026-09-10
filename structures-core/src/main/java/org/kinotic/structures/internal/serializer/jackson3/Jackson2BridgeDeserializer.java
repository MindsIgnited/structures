package org.kinotic.structures.internal.serializer.jackson3;

import com.fasterxml.jackson.databind.ObjectMapper;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads the current value as JSON with Jackson 3 and hands it to Structures' Jackson 2 {@link ObjectMapper} to
 * build the target type. The inverse of {@link Jackson2BridgeSerializer}; see that class for why the two mappers
 * have to interoperate.
 */
public class Jackson2BridgeDeserializer<T> extends ValueDeserializer<T> {

    private final ObjectMapper jackson2;
    private final Class<T> type;

    public Jackson2BridgeDeserializer(ObjectMapper jackson2, Class<T> type) {
        this.jackson2 = jackson2;
        this.type = type;
    }

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return jackson2.readValue(node.toString(), type);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not bridge JSON to " + type.getName()
                                            + " for deserialization", e);
        }
    }
}
