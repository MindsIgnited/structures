package org.kinotic.structures.internal.serializer;

import co.elastic.clients.elasticsearch._types.FieldValue;
import jakarta.json.JsonValue;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;

import tools.jackson.core.JacksonException;

/**
 * Created by Navíd Mitchell 🤪 on 11/6/23.
 */
public class FieldValueSerializer extends ValueSerializer<FieldValue> {

    @Override
    public void serialize(FieldValue field, JsonGenerator jsonGenerator, SerializationContext serializers) throws JacksonException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringProperty("kind", field._kind().name());
        switch (field._kind()) {
            case Double :
                jsonGenerator.writeNumberProperty("value", field.doubleValue());
                break;
            case Long :
                jsonGenerator.writeNumberProperty("value", field.longValue());
                break;
            case Boolean :
                jsonGenerator.writeBooleanProperty("value", field.booleanValue());
                break;
            case String :
                jsonGenerator.writeStringProperty("value", field.stringValue());
                break;
            case Null :
                jsonGenerator.writeNullProperty("value");
                break;
            case Any :
                // Arbitrary JSON held as JsonData, in one of two forms: parsed JSON (what the deserializer
                // below and the Elasticsearch client produce), or a Java object it was built from, which
                // JsonData can only render with a mapper it does not have here. Either way it is written as
                // the JSON it stands for, which is what FieldValueDeserializer reads back.
                jsonGenerator.writeName("value");
                Object any = field.anyValue().to(Object.class);
                if (any instanceof JsonValue jsonValue) {
                    jsonGenerator.writeRawValue(jsonValue.toString());
                } else {
                    jsonGenerator.writePOJO(any);
                }
                break;
            default :
                throw new IllegalStateException("Unknown kind " + field._kind());
        }
        jsonGenerator.writeEndObject();
    }
}
