package com.llmops.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient beans for services that the rag-engine communicates with via REST:
 * Qdrant (vector search), Elasticsearch (BM25 search), and Ollama (LLM generation).
 *
 * <p>Note: ml-service communication uses gRPC (see {@link com.llmops.rag.grpc.MlServiceGrpcClient}).
 * No WebClient bean for ml-service is registered here.</p>
 */
@Configuration
public class WebClientConfig {

    @Value("${services.qdrant.url}")
    private String qdrantUrl;

    @Value("${services.elasticsearch.url}")
    private String elasticsearchUrl;

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
    public WebClient ollamaWebClient(WebClient.Builder builder) {
        return builder.baseUrl(ollamaUrl).build();
    }
}
