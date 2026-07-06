package com.llmops.gateway.client;

import com.llmops.gateway.model.ChatMessage;
import com.llmops.gateway.model.ChatRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class RagEngineClient {

    private final WebClient webClient;

    @Autowired
    public RagEngineClient(WebClient ragEngineWebClient) {
        this.webClient = ragEngineWebClient;
    }

    @CircuitBreaker(name = "ragEngineClient", fallbackMethod = "fallbackStreamChat")
    @Retry(name = "ragEngineClient")
    public Flux<String> streamChat(ChatRequest request) {
        return webClient.post()
                .uri("/v1/reasoning-chat")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class);
    }

    @CircuitBreaker(name = "ragEngineClient", fallbackMethod = "fallbackChat")
    @Retry(name = "ragEngineClient")
    public Mono<Map<String, Object>> chat(ChatRequest request) {
        return webClient.post()
                .uri("/v1/reasoning-chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // Fallbacks
    public Flux<String> fallbackStreamChat(ChatRequest request, Throwable t) {
        return Flux.just("{\"type\":\"token\",\"data\":\"[Gateway Fallback] RAG Engine is temporarily unavailable. Error: " + t.getMessage() + "\"}");
    }

    public Mono<Map<String, Object>> fallbackChat(ChatRequest request, Throwable t) {
        return Mono.just(Map.of(
                "answer", "[Gateway Fallback] RAG Engine is temporarily unavailable. Error: " + t.getMessage(),
                "reasoning_type", "commonsense",
                "sources", List.of()
        ));
    }
}
