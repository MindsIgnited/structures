package org.kinotic.structures.tests.core.idl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kinotic.continuum.idl.api.schema.ObjectC3Type;
import org.kinotic.continuum.idl.api.schema.StringC3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.structures.api.domain.idl.decorators.EntityServiceDecoratorsDecorator;
import org.kinotic.structures.api.domain.idl.decorators.IdDecorator;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.springframework.beans.factory.annotation.Autowired;

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
}
