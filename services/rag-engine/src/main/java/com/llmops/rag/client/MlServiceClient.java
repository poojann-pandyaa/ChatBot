package com.llmops.rag.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class MlServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);

    private final WebClient webClient;

    @Autowired
    public MlServiceClient(WebClient mlServiceWebClient) {
        this.webClient = mlServiceWebClient;
    }

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackClassify")
    @Retry(name = "mlServiceClient")
    public Mono<Map<String, Object>> classify(String query) {
        return webClient.post()
                .uri("/classify")
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackEmbed")
    @Retry(name = "mlServiceClient")
    public Mono<List<Double>> embed(String text) {
        return webClient.post()
                .uri("/embed")
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> {
                    @SuppressWarnings("unchecked")
                    List<Double> embedding = (List<Double>) res.get("embedding");
                    return embedding;
                });
    }

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackRerank")
    @Retry(name = "mlServiceClient")
    public Mono<List<Double>> rerank(String query, List<String> documents) {
        return webClient.post()
                .uri("/rerank")
                .bodyValue(Map.of("query", query, "documents", documents))
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> {
                    @SuppressWarnings("unchecked")
                    List<Double> scores = (List<Double>) res.get("scores");
                    return scores;
                });
    }

    // Fallbacks
    public Mono<Map<String, Object>> fallbackClassify(String query, Throwable t) {
        log.warn("Classification fallback triggered: {}", t.getMessage());
        String keywordType = keywordFallback(query);
        return Mono.just(Map.of(
                "intent", "factual",
                "reasoning_type", keywordType,
                "entities", List.of(),
                "scope", "commonsense".equals(keywordType) ? "single_topic" : "multi_topic",
                "ambiguity", "low",
                "sub_questions", List.of(query)
        ));
    }

    public Mono<List<Double>> fallbackEmbed(String text, Throwable t) {
        log.warn("Embedding fallback triggered: {}", t.getMessage());
        // Return 768 zeros
        return Mono.just(List.copyOf(java.util.Collections.nCopies(768, 0.0)));
    }

    public Mono<List<Double>> fallbackRerank(String query, List<String> documents, Throwable t) {
        log.warn("Reranking fallback triggered: {}", t.getMessage());
        // Return zeros as baseline scores
        return Mono.just(List.copyOf(java.util.Collections.nCopies(documents.size(), 0.0)));
    }

    private String keywordFallback(String query) {
        String q = query.toLowerCase().trim();
        List<String> strategicPatterns = List.of(
                " vs ", " versus ", " or ", "which is better",
                "which should i choose", "pros and cons of",
                "tradeoffs between", "compare and contrast"
        );
        for (String pattern : strategicPatterns) {
            if (q.contains(pattern)) {
                return "strategic";
            }
        }

        List<String> adaptiveExplain = List.of(
                "what is", "explain", "how does", "what are",
                "describe", "define", "difference between"
        );
        List<String> adaptiveUsage = List.of(
                "when should", "when to use", "and when", "and how",
                "how to use", "and why", "should i use", "when do i",
                "which is faster", "which is better", "how do i implement",
                "how to implement"
        );

        boolean hasExplain = adaptiveExplain.stream().anyMatch(q::contains);
        boolean hasUsage = adaptiveUsage.stream().anyMatch(q::contains);

        if (hasExplain && hasUsage) {
            return "adaptive";
        }
        return "commonsense";
    }
}
