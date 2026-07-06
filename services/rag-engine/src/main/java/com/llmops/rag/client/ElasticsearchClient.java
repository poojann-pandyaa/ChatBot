package com.llmops.rag.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchClient {

    private final WebClient webClient;

    @Value("${services.elasticsearch.index:stackexchange_chunks}")
    private String indexName;

    @Autowired
    public ElasticsearchClient(WebClient elasticsearchWebClient) {
        this.webClient = elasticsearchWebClient;
    }

    @CircuitBreaker(name = "elasticsearchClient", fallbackMethod = "fallbackSearch")
    @Retry(name = "elasticsearchClient")
    public Mono<List<Map<String, Object>>> search(String query, int limit) {
        Map<String, Object> body = Map.of(
                "size", limit,
                "query", Map.of(
                        "match", Map.of(
                                "chunk_text", query
                        )
                )
        );

        return webClient.post()
                .uri("/" + indexName + "/_search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> hitsWrapper = (Map<String, Object>) res.get("hits");
                    if (hitsWrapper == null) {
                        return List.of();
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> hitsList = (List<Map<String, Object>>) hitsWrapper.get("hits");
                    if (hitsList == null) {
                        return List.of();
                    }
                    return hitsList.stream().map(hit -> {
                        String idStr = (String) hit.get("_id");
                        int idVal = 0;
                        try {
                            idVal = Integer.parseInt(idStr);
                        } catch (NumberFormatException e) {
                            // Default fallback
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> source = (Map<String, Object>) hit.getOrDefault("_source", Map.of());
                        return Map.of(
                                "chunk_id", idVal,
                                "payload", source
                        );
                    }).toList();
                });
    }

    public Mono<List<Map<String, Object>>> fallbackSearch(String query, int limit, Throwable t) {
        System.err.println("Elasticsearch search fallback triggered: " + t.getMessage());
        return Mono.just(List.of());
    }
}
