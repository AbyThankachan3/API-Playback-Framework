package com.ProxyInterceptor.HTTPInterceptor.Elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    private static final String API_KEY = "VlhHYWhwY0J0MmZNWV9MNUxIRlM6RGlrTmtuVXBmY2NDLWR1ajU4eDd0UQ==";

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // Add default header for API key authentication
        RestClientBuilder builder = RestClient.builder(
                new HttpHost("localhost", 9200, "http")
        ).setDefaultHeaders(new org.apache.http.Header[]{
                new org.apache.http.message.BasicHeader(
                        "Authorization", "ApiKey " + API_KEY
                )
        });

        RestClient restClient = builder.build();

        // Register Java time support for Instant/LocalDateTime etc.
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        JacksonJsonpMapper jsonpMapper = new JacksonJsonpMapper(mapper);

        return new ElasticsearchClient(
                new RestClientTransport(restClient, jsonpMapper)
        );
    }
}
