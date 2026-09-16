package org.kinotic.structures.tests.core.endpoints.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.ObjectMapperFactory;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * swagger-core ships in two flavours, and only the jakarta one can see the jakarta.validation and
 * jakarta.xml.bind annotations everything on a Spring Boot 4 classpath carries: the javax flavour
 * names javax.* classes nothing provides, and either ignores the annotations or fails to link the
 * first time its model resolver meets one. Pins that the swagger-core on the classpath is the
 * jakarta flavour, by asking it to read a bean it can only describe correctly if it is.
 */
class SwaggerModelResolverTest {

    public static class Widget {
        @NotNull
        public String name;

        @Size(max = 12)
        public String code;
    }

    @Test
    void modelResolverReadsJakartaValidationConstraints() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(Widget.class);

        Schema<?> widget = schemas.get("Widget");
        assertNotNull(widget, "the bean was resolved");
        assertEquals(List.of("name"), widget.getRequired(), "@jakarta.validation.constraints.NotNull marks the property required");
        assertEquals(12, widget.getProperties().get("code").getMaxLength(), "@jakarta.validation.constraints.Size sets maxLength");
    }

    @Test
    void swaggerMapperStillSerialisesTheModel() {
        // the one swagger entry point Structures used before this test existed
        assertNotNull(ObjectMapperFactory.createJson().valueToTree(new Schema<>().name("x")));
    }
}
