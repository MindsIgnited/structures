package org.kinotic.structures.internal.serializer.jackson3;

import com.fasterxml.jackson.databind.ObjectMapper;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a value using Structures' Jackson 2 {@link ObjectMapper} and splices the result into a Jackson 3
 * generator verbatim.
 * <p>
 * Continuum serializes RPC payloads with Jackson 3 as of 3.1.0, but several types on the published Structures
 * service interfaces are Jackson 2 shaped - {@code TokenBuffer} is a Jackson 2 class outright, and {@code RawJson}
 * and {@code FastestType} rely on Jackson 2 serializers that already exist. Rather than port those to Jackson 3
 * and risk changing the bytes on the wire, continuum's mapper hands these types back to the Jackson 2 mapper that
 * has always produced them, so the representation is identical to what continuum 2.6 emitted.
 *
 * @see Jackson2BridgeDeserializer
 */
public class Jackson2BridgeSerializer<T> extends ValueSerializer<T> {

    private final ObjectMapper jackson2;

    public Jackson2BridgeSerializer(ObjectMapper jackson2) {
        this.jackson2 = jackson2;
    }

    @Override
    public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        try {
            gen.writeRawValue(jackson2.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not bridge " + value.getClass().getName()
                                            + " to Jackson 3 for serialization", e);
        }
    }
}
