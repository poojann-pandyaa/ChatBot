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
public class QdrantClient {

    private final WebClient webClient;
    
    @Value("${services.qdrant.collection:stackexchange_chunks}")
    private String collectionName;

    @Autowired
    public QdrantClient(WebClient qdrantWebClient) {
        this.webClient = qdrantWebClient;
    }

    @CircuitBreaker(name = "qdrantClient", fallbackMethod = "fallbackSearch")
    @Retry(name = "qdrantClient")
    public Mono<List<Map<String, Object>>> search(List<Double> vector, int limit) {
        Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", limit,
                "with_payload", true
        );

        return webClient.post()
                .uri("/collections/" + collectionName + "/points/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> resultList = (List<Map<String, Object>>) res.get("result");
                    if (resultList == null) {
                        return List.of();
                    }
                    return resultList.stream().map(hit -> {
                        Object hitId = hit.get("id");
                        int idVal = 0;
                        if (hitId instanceof Number) {
                            idVal = ((Number) hitId).intValue();
                        } else if (hitId instanceof String) {
                            try {
                                idVal = Integer.parseInt((String) hitId);
                            } catch (NumberFormatException e) {
                                // Default fallback if non-numeric ID format
                            }
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = (Map<String, Object>) hit.getOrDefault("payload", Map.of());
                        return Map.of(
                                "chunk_id", idVal,
                                "payload", payload
                        );
                    }).toList();
                });
    }

    public Mono<List<Map<String, Object>>> fallbackSearch(List<Double> vector, int limit, Throwable t) {
        System.err.println("Qdrant search fallback triggered: " + t.getMessage());
        return Mono.just(List.of());
    }
}
