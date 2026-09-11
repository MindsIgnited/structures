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
 * <p>
 * This is the expensive half of the bridge. Reading costs three passes over the payload - Jackson 3 parses it
 * into a tree, the tree is written back out to a string, and Jackson 2 parses that - and holds the tree and the
 * string at once. Measured against a 830KB bulk array it ran 3.7x slower than parsing with Jackson 2 directly
 * and allocated 4.5x as much, 14.4MB against 3.2MB; the ratios hold from 16KB upwards. Serializing, by contrast,
 * is one extra string and costs about 2x allocation and 1.2x time.
 * <p>
 * That asymmetry lands on the wrong side: the types bridged here are parameters to save, update, bulkSave and
 * bulkUpdate, so entity ingest pays it while reads mostly do not. Streaming tokens straight from the Jackson 3
 * parser into a Jackson 2 {@code TokenBuffer} would remove both intermediates, at the cost of hand-written token
 * copying that has to cover every type including embedded objects and binary. Worth doing if bulk ingest volume
 * makes this show up in a profile.
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
