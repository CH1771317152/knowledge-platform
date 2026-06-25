package com.platform.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.platform.search.application.SearchCursorCodec;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(SearchProperties.class)
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(org.springframework.core.env.Environment env) {
        String uris = env.getProperty("spring.elasticsearch.uris", "http://localhost:9200");
        return RestClient.builder(HttpHost.create(uris)).build();
    }

    @Bean
    ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    /**
     * Shared {@link SearchCursorCodec} bean for the query service. Constructed with the cursor secret
     * and TTL from {@code platform.search.cursor}; one instance is shared by every search request so
     * the HMAC key is not reloaded per call.
     */
    @Bean
    SearchCursorCodec searchCursorCodec(SearchProperties properties) {
        return new SearchCursorCodec(properties.cursor().secret(), properties.cursor().ttlSeconds());
    }
}
