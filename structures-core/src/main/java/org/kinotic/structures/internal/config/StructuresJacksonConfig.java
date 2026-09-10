package org.kinotic.structures.internal.config;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.commons.lang3.tuple.Pair;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
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
     * Structures and the Elasticsearch client are built on has to be declared here. The defaults mirror
     * what Boot 3's JacksonAutoConfiguration applied, and every Jackson 2 module bean is registered so
     * {@link #structuresJacksonModule(ApplicationContext)} still takes effect.
     */
    @Bean
    public ObjectMapper objectMapper(List<Module> modules){
        return Jackson2ObjectMapperBuilder.json()
                                          .modulesToInstall(modules.toArray(new Module[0]))
                                          .build();
    }

    @Bean
    public SimpleModule structuresJacksonModule(ApplicationContext applicationContext){
        SimpleModule ret = new SimpleModule("StructuresModule", Version.unknownVersion());

        Set<MetadataReader> decoratorMetas = MetaUtil.findClassesAssignableToType(applicationContext,
                                                                                  List.of("org.kinotic.structures.api.domain.idl.decorators"),
                                                                                  C3Decorator.class);
        // Register all C3Decorator's with Jackson
        for(MetadataReader decoratorMeta : decoratorMetas){

            if(!decoratorMeta.getClassMetadata().isAbstract()) {
                try {
                    Pair<Class<?>, String> decoratorInfo = getDecoratorInfo(decoratorMeta);

                    ret.registerSubtypes(new NamedType(decoratorInfo.getLeft(), decoratorInfo.getRight()));

                } catch (NoSuchFieldException e) {
                    log.warn("{} Could not be mapped. A public static final field named 'type' must exist on the class.",
                             decoratorMeta.getClassMetadata().getClassName());
                }
            }
        }
        // register additional needed types
        ret.registerSubtypes(new NamedType(PageableC3Type.class, "pageable"));
        ret.registerSubtypes(new NamedType(PageC3Type.class, "page"));
        ret.registerSubtypes(new NamedType(TenantSelectionC3Type.class, "tenantSelection"));
        ret.registerSubtypes(new NamedType(QueryOptionsC3Type.class, "queryOptions"));

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
