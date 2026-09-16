package org.kinotic.structures.migration.config;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import tools.jackson.databind.json.JsonMapper;
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

    @Bean
    public JsonpMapper jsonpMapper(JsonMapper jsonMapper){
        return new Jackson3JsonpMapper(jsonMapper);
    }

}

