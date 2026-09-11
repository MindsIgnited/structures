package org.kinotic.structures.internal.config;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.apache.commons.lang3.tuple.Pair;
import org.kinotic.continuum.idl.api.schema.C3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.continuum.internal.utils.MetaUtil;
import org.kinotic.structures.api.domain.FastestType;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.TenantSpecificId;
import org.kinotic.structures.api.domain.idl.PageC3Type;
import org.kinotic.structures.api.domain.idl.PageableC3Type;
import org.kinotic.structures.api.domain.idl.QueryOptionsC3Type;
import org.kinotic.structures.api.domain.idl.TenantSelectionC3Type;
import org.kinotic.structures.api.domain.DefaultTenantSpecificId;
import org.kinotic.structures.internal.serializer.*;
import org.kinotic.structures.internal.serializer.jackson3.Jackson2BridgeDeserializer;
import org.kinotic.structures.internal.serializer.jackson3.Jackson2BridgeSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import tools.jackson.databind.JacksonModule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Created by Navíd Mitchell 🤪 on 5/9/23.
 */
@Configuration
public class StructuresJacksonConfig {

    private static final Logger log = LoggerFactory.getLogger(StructuresJacksonConfig.class);

    /**
     * Spring Boot 4 auto-configures a Jackson 3 mapper only, so the Jackson 2 {@link ObjectMapper} that
     * Structures and the Elasticsearch client are built on has to be declared here, and every Jackson 2
     * module bean is registered so {@link #structuresJacksonModule(ApplicationContext)} still takes effect.
     * <p>
     * The two date features are what Boot 3 applied on top of the builder's own defaults. Without them
     * Jackson writes dates as epoch numbers rather than ISO-8601, which silently changes both API
     * responses and what the Elasticsearch client stores. The builder only disables DEFAULT_VIEW_INCLUSION
     * and FAIL_ON_UNKNOWN_PROPERTIES by itself, so leaving these out is not a no-op. Those four are the
     * whole of what Boot 3 configured by default, so the feature set here matches what we had.
     * <p>
     * {@code applicationContext} is what installs Spring's handler instantiator, so a serializer or
     * deserializer named in a {@code @JsonSerialize} style annotation gets autowired rather than built
     * through its no-arg constructor. Nothing here needs that today, but losing it fails quietly at the
     * point someone writes the first handler with a dependency.
     * <p>
     * What is not carried over is {@code spring.jackson.*}. Boot's customizer also applied
     * default-property-inclusion, time-zone, locale, date-format, visibility and the per-feature maps
     * from those properties, and none of that reaches this mapper. Boot also contributed
     * {@code JsonComponentModule} and {@code JsonMixinModule} as module beans, which Boot 4 no longer
     * registers, so {@code @JsonComponent} and {@code @JsonMixin} are not picked up either. Nothing uses
     * any of it today, so nothing is lost, but each will appear to do nothing rather than fail. Wire
     * them here if that changes rather than wondering why the annotation is ignored.
     */
    @Bean
    public ObjectMapper objectMapper(ApplicationContext applicationContext, List<Module> modules){
        return Jackson2ObjectMapperBuilder.json()
                                          .applicationContext(applicationContext)
                                          .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                                                             SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                                          .modulesToInstall(modules.toArray(new Module[0]))
                                          .build();
    }

    @Bean
    public SimpleModule structuresJacksonModule(ApplicationContext applicationContext){
        SimpleModule ret = new SimpleModule("StructuresModule", Version.unknownVersion());

        for(Pair<Class<?>, String> subtype : idlSubtypes(applicationContext)){
            ret.registerSubtypes(new NamedType(subtype.getLeft(), subtype.getRight()));
        }

        // register internal serializer deserializers
        ret.addDeserializer(RawJson.class, new RawJsonDeserializer(new ObjectMapper()));
        ret.addSerializer(RawJson.class, new RawJsonSerializer());

        ret.addDeserializer(FieldValue.class, new FieldValueDeserializer());
        ret.addSerializer(FieldValue.class, new FieldValueSerializer());

        ret.addSerializer(FastestType.class, new FastestTypeSerializer());

        SimpleAbstractTypeResolver resolver = new SimpleAbstractTypeResolver();
        resolver.addMapping(TenantSpecificId.class, DefaultTenantSpecificId.class);

        ret.setAbstractTypes(resolver);

        return ret;
    }

    /**
     * Continuum serializes the IDL with Jackson 3, so Structures' {@link C3Decorator} and {@link C3Type}
     * subtypes have to be registered on a Jackson 3 module as well as the Jackson 2 one above. Without this,
     * continuum's mapper only knows its own built-in decorators and any schema carrying a Structures
     * decorator fails to deserialize with "Could not resolve type id ... known type ids = [NotNull]".
     */
    @Bean
    public JacksonModule structuresJackson3Module(ApplicationContext applicationContext,
                                                  ObjectMapper objectMapper){
        tools.jackson.databind.module.SimpleModule ret =
                new tools.jackson.databind.module.SimpleModule("StructuresModule",
                                                               tools.jackson.core.Version.unknownVersion());

        for(Pair<Class<?>, String> subtype : idlSubtypes(applicationContext)){
            ret.registerSubtypes(new tools.jackson.databind.jsontype.NamedType(subtype.getLeft(),
                                                                              subtype.getRight()));
        }

        // These travel on published service signatures but are Jackson 2 shaped, so continuum's Jackson 3
        // mapper delegates them to the Jackson 2 mapper that has always produced them. See Jackson2BridgeSerializer.
        bridge(ret, objectMapper, TokenBuffer.class);
        bridge(ret, objectMapper, RawJson.class);
        // FastestType is a return type only, and its serializer writes the unwrapped inner value, so the
        // wire form does not round trip: nothing names the record's single component. Reading one back
        // yields FastestType(null) either way, through the bridge or through Jackson 3's default record
        // deserializer, since unknown properties are ignored - so only the serializer is registered, and
        // a Java client that tries to read one gets null rather than an error. Register a deserializer
        // that rewraps if that ever needs to work.
        ret.addSerializer(FastestType.class, new Jackson2BridgeSerializer<>(objectMapper));

        tools.jackson.databind.module.SimpleAbstractTypeResolver resolver =
                new tools.jackson.databind.module.SimpleAbstractTypeResolver();
        resolver.addMapping(TenantSpecificId.class, DefaultTenantSpecificId.class);
        ret.setAbstractTypes(resolver);

        return ret;
    }

    private <T> void bridge(tools.jackson.databind.module.SimpleModule module,
                            ObjectMapper objectMapper,
                            Class<T> type){
        module.addSerializer(type, new Jackson2BridgeSerializer<>(objectMapper));
        module.addDeserializer(type, new Jackson2BridgeDeserializer<>(objectMapper, type));
    }

    /**
     * The Structures IDL subtypes that must be resolvable by type id, as (class, type id) pairs. Both the
     * Jackson 2 and the Jackson 3 module register exactly this set, so the two mappers agree on the wire.
     */
    private List<Pair<Class<?>, String>> idlSubtypes(ApplicationContext applicationContext){
        List<Pair<Class<?>, String>> ret = new ArrayList<>();

        Set<MetadataReader> decoratorMetas = MetaUtil.findClassesAssignableToType(applicationContext,
                                                                                  List.of("org.kinotic.structures.api.domain.idl.decorators"),
                                                                                  C3Decorator.class);
        // Register all C3Decorator's with Jackson
        for(MetadataReader decoratorMeta : decoratorMetas){

            if(!decoratorMeta.getClassMetadata().isAbstract()) {
                try {
                    ret.add(getDecoratorInfo(decoratorMeta));
                } catch (NoSuchFieldException e) {
                    log.warn("{} Could not be mapped. A public static final field named 'type' must exist on the class.",
                             decoratorMeta.getClassMetadata().getClassName());
                }
            }
        }
        // register additional needed types
        ret.add(Pair.of(PageableC3Type.class, "pageable"));
        ret.add(Pair.of(PageC3Type.class, "page"));
        ret.add(Pair.of(TenantSelectionC3Type.class, "tenantSelection"));
        ret.add(Pair.of(QueryOptionsC3Type.class, "queryOptions"));

        return ret;
    }


    private Pair<Class<?>, String> getDecoratorInfo(MetadataReader metadataReader) throws NoSuchFieldException{
        try {
            Class<?> decoratorClass = Class.forName(metadataReader.getClassMetadata().getClassName());
            Field typeField = decoratorClass.getDeclaredField("type");
            String type = (String) typeField.get(null);
            return Pair.of(decoratorClass, type);
        } catch (IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
