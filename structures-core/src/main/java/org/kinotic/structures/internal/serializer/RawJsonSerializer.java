package org.kinotic.structures.internal.serializer;

import org.kinotic.structures.api.domain.RawJson;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Created by Navíd Mitchell 🤪 on 5/9/23.
 */
public class RawJsonSerializer extends ValueSerializer<RawJson> {

    @Override
    public void serialize(RawJson value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        String json = new String(value.data(), StandardCharsets.UTF_8);
        gen.writeRawValue(json);
    }
}
