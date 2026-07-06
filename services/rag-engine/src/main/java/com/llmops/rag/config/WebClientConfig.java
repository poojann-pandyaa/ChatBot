package com.llmops.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${services.qdrant.url}")
    private String qdrantUrl;

    @Value("${services.elasticsearch.url}")
    private String elasticsearchUrl;

    @Value("${services.ml-service.url}")
    private String mlServiceUrl;

    @Value("${services.ollama.url}")
    private String ollamaUrl;

    @Bean
    public WebClient qdrantWebClient(WebClient.Builder builder) {
        return builder.baseUrl(qdrantUrl).build();
    }

    @Bean
    public WebClient elasticsearchWebClient(WebClient.Builder builder) {
        return builder.baseUrl(elasticsearchUrl).build();
    }

    @Bean
    public WebClient mlServiceWebClient(WebClient.Builder builder) {
        return builder.baseUrl(mlServiceUrl).build();
    }

    @Bean
    public WebClient ollamaWebClient(WebClient.Builder builder) {
        return builder.baseUrl(ollamaUrl).build();
    }
}
