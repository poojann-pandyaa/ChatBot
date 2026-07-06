package com.llmops.rag.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final WebClient webClient;

    @Value("${services.ollama.model:gemma2:2b}")
    private String modelName;

    @Autowired
    public OllamaClient(WebClient ollamaWebClient) {
        this.webClient = ollamaWebClient;
    }

    @CircuitBreaker(name = "ollamaClient", fallbackMethod = "fallbackGenerate")
    @Retry(name = "ollamaClient")
    public Mono<String> generate(String prompt) {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "prompt", prompt,
                "options", Map.of(
                        "temperature", 0.2,
                        "top_p", 0.9,
                        "repeat_penalty", 1.0,
                        "num_ctx", 4096,
                        "num_predict", 2048
                ),
                "stream", false
        );

        return webClient.post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(res -> ((String) res.get("response")).trim());
    }

    @CircuitBreaker(name = "ollamaClient", fallbackMethod = "fallbackGenerateStream")
    @Retry(name = "ollamaClient")
    public Flux<String> generateStream(String prompt) {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "prompt", prompt,
                "options", Map.of(
                        "temperature", 0.2,
                        "top_p", 0.9,
                        "repeat_penalty", 1.0,
                        "num_ctx", 4096,
                        "num_predict", 2048
                ),
                "stream", true
        );

        return webClient.post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    public Mono<String> fallbackGenerate(String prompt, Throwable t) {
        log.warn("Ollama generate fallback triggered: {}", t.getMessage());
        return Mono.just("[MOCK GENERATION] Response to prompt: " + prompt.substring(0, Math.min(prompt.length(), 100)) + "...");
    }

    public Flux<String> fallbackGenerateStream(String prompt, Throwable t) {
        log.warn("Ollama generate stream fallback triggered: {}", t.getMessage());
        String mockText = "[MOCK STREAM GENERATION] Response to prompt: " + prompt.substring(0, Math.min(prompt.length(), 50)) + "...";
        String[] words = mockText.split(" ");
        return Flux.fromArray(words)
                .map(word -> "{\"response\":\"" + word + " \",\"done\":false}");
    }
}
