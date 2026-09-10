package org.kinotic.structures.migration.config;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Created by Navíd Mitchell 🤪 on 4/26/23.
 */
@Configuration
@RequiredArgsConstructor
public class MigrationElasticsearchConfig {

    private final MirationProperties properties;

    @Bean
    public ElasticsearchAsyncClient elasticsearchAsyncClient(JsonpMapper jsonpMapper){

        RestClientBuilder builder = RestClient.builder(new HttpHost(properties.getElasticHost(),
                                                                    properties.getElasticPort(),
                                                                    properties.getElasticScheme()));

        if(properties.hasElasticUsernameAndPassword()){
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY,
                                                   new UsernamePasswordCredentials(properties.getElasticUsername(),
                                                                                   properties.getElasticPassword()));

                return httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider);
            });
        }

        RestClient restClient = builder.build();

        // Create the transport with a Jackson mapper
        ElasticsearchTransport transport = new RestClientTransport(restClient, jsonpMapper);

        return new ElasticsearchAsyncClient(transport);
    }

    /**
     * Spring Boot 4 auto-configures a Jackson 3 mapper only, so the Jackson 2 {@link ObjectMapper} the
     * Elasticsearch client is built on has to be declared here. The defaults mirror what Boot 3's
     * JacksonAutoConfiguration applied.
     */
    @Bean
    public ObjectMapper objectMapper(List<Module> modules){
        return JsonMapper.builder()
                         .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                         .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                         .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                         .findAndAddModules()
                         .addModules(modules)
                         .build();
    }

    @Bean
    public JsonpMapper jsonpMapper(ObjectMapper objectMapper){
        return new JacksonJsonpMapper(objectMapper);
    }

}

