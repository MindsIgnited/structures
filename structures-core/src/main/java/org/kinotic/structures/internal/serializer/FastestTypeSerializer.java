package org.kinotic.structures.internal.serializer;

import org.kinotic.structures.api.domain.FastestType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Created by Navíd Mitchell 🤪 on 6/6/23.
 */
public class FastestTypeSerializer extends ValueSerializer<FastestType> {

    @Override
    public void serialize(FastestType value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        gen.writePOJO(value.data());
    }
}
