package com.llmops.rag.controller;

import com.llmops.rag.model.ChatRequest;
import com.llmops.rag.service.RouterService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public class ReasoningController {

    private final RouterService routerService;
    private final Counter requestCounter;
    private final PrometheusMeterRegistry prometheusRegistry;

    @Autowired
    public ReasoningController(
            RouterService routerService,
            MeterRegistry meterRegistry,
            PrometheusMeterRegistry prometheusRegistry) {
        this.routerService = routerService;
        this.prometheusRegistry = prometheusRegistry;
        this.requestCounter = Counter.builder("rag_requests_total")
                .description("Total requests received by the RAG engine")
                .register(meterRegistry);
    }

    @PostMapping("/v1/reasoning-chat")
    public Mono<ResponseEntity<?>> reasoningChat(@RequestBody ChatRequest request) {
        requestCounter.increment();
        if (request.stream()) {
            Flux<String> stream = routerService.routeStreaming(request.prompt(), request.history(), request.includeTrace());
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.valueOf("application/x-ndjson"))
                    .body(stream));
        } else {
            return routerService.routeNonStreaming(request.prompt(), request.history(), request.includeTrace())
                    .map(res -> ResponseEntity.ok().body(res));
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public Mono<String> getMetrics() {
        return Mono.fromCallable(prometheusRegistry::scrape)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
