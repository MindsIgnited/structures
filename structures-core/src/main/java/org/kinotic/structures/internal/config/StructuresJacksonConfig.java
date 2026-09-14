package org.kinotic.structures.internal.config;

import co.elastic.clients.elasticsearch._types.FieldValue;
import org.apache.commons.lang3.tuple.Pair;
import org.kinotic.continuum.idl.api.schema.C3Type;
import org.kinotic.continuum.idl.api.schema.decorators.C3Decorator;
import org.kinotic.continuum.internal.utils.MetaUtil;
import org.kinotic.structures.api.domain.DefaultTenantSpecificId;
import org.kinotic.structures.api.domain.FastestType;
import org.kinotic.structures.api.domain.RawJson;
import org.kinotic.structures.api.domain.TenantSpecificId;
import org.kinotic.structures.api.domain.idl.PageC3Type;
import org.kinotic.structures.api.domain.idl.PageableC3Type;
import org.kinotic.structures.api.domain.idl.QueryOptionsC3Type;
import org.kinotic.structures.api.domain.idl.TenantSelectionC3Type;
import org.kinotic.structures.internal.serializer.FastestTypeSerializer;
import org.kinotic.structures.internal.serializer.FieldValueDeserializer;
import org.kinotic.structures.internal.serializer.FieldValueSerializer;
import org.kinotic.structures.internal.serializer.RawJsonDeserializer;
import org.kinotic.structures.internal.serializer.RawJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReader;
import tools.jackson.core.Version;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.module.SimpleAbstractTypeResolver;
import tools.jackson.databind.module.SimpleModule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structures' contribution to the one Jackson 3 mapper Spring Boot builds: the IDL subtypes that must
 * resolve by type id, and the serializers for the payload types that travel on published service
 * signatures. Boot registers every {@link tools.jackson.databind.JacksonModule} bean on the mapper it
 * auto-configures, which is the mapper continuum, the Elasticsearch client and Structures all share.
 * <p>
 * Jackson 3 writes dates as ISO-8601 text by default, so nothing about dates is configured here.
 * <p>
 * Created by Navíd Mitchell 🤪 on 5/9/23.
 */
@Configuration
public class StructuresJacksonConfig {

    private static final Logger log = LoggerFactory.getLogger(StructuresJacksonConfig.class);

    @Bean
    public SimpleModule structuresJacksonModule(ApplicationContext applicationContext){
        SimpleModule ret = new SimpleModule("StructuresModule", Version.unknownVersion());

        for(Pair<Class<?>, String> subtype : idlSubtypes(applicationContext)){
            ret.registerSubtypes(new NamedType(subtype.getLeft(), subtype.getRight()));
        }

        // RawJson is copied off the parser as bytes and written back raw, so it needs a mapper of its
        // own only for the generator that does the copy; nothing about it depends on the configured one
        ret.addDeserializer(RawJson.class, new RawJsonDeserializer(JsonMapper.builder().build()));
        ret.addSerializer(RawJson.class, new RawJsonSerializer());

        ret.addDeserializer(FieldValue.class, new FieldValueDeserializer());
        ret.addSerializer(FieldValue.class, new FieldValueSerializer());

        // FastestType is a return type only, and its serializer writes the unwrapped inner value, so the
        // wire form does not round trip: nothing names the record's single component. A Java client that
        // reads one back gets FastestType(null) through Jackson's default record handling, since unknown
        // properties are ignored. Register a deserializer that rewraps if that ever needs to work.
        ret.addSerializer(FastestType.class, new FastestTypeSerializer());

        SimpleAbstractTypeResolver resolver = new SimpleAbstractTypeResolver();
        resolver.addMapping(TenantSpecificId.class, DefaultTenantSpecificId.class);
        ret.setAbstractTypes(resolver);

        return ret;
    }

    /**
     * The Structures IDL subtypes that must be resolvable by type id, as (class, type id) pairs.
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
