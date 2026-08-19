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

import java.time.Duration;
import java.util.Map;

import com.llmops.rag.grpc.MlServiceGrpcClient;
import io.grpc.ConnectivityState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
@RestController
public class ReasoningController {

    private final RouterService routerService;
    private final Counter requestCounter;
    private final PrometheusMeterRegistry prometheusRegistry;
    private final WebClient qdrantWebClient;
    private final WebClient elasticsearchWebClient;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MlServiceGrpcClient mlServiceGrpcClient;

    @Autowired
    public ReasoningController(
            RouterService routerService,
            MeterRegistry meterRegistry,
            PrometheusMeterRegistry prometheusRegistry,
            @Qualifier("qdrantWebClient") WebClient qdrantWebClient,
            @Qualifier("elasticsearchWebClient") WebClient elasticsearchWebClient,
            ReactiveRedisTemplate<String, String> redisTemplate,
            MlServiceGrpcClient mlServiceGrpcClient) {
        this.routerService = routerService;
        this.prometheusRegistry = prometheusRegistry;
        this.qdrantWebClient = qdrantWebClient;
        this.elasticsearchWebClient = elasticsearchWebClient;
        this.redisTemplate = redisTemplate;
        this.mlServiceGrpcClient = mlServiceGrpcClient;
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
    public Mono<ResponseEntity<Map<String, String>>> health() {
        return Mono.fromCallable(() -> Map.of("status", "healthy"))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(3))
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "unhealthy")));
    }

    @GetMapping("/ready")
    public Mono<ResponseEntity<Map<String, Object>>> ready() {
        Mono<Boolean> qdrantPing = qdrantWebClient.get().uri("/collections")
                .retrieve().toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .map(res -> res.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);

        Mono<Boolean> elasticsearchPing = elasticsearchWebClient.get().uri("/_cluster/health")
                .retrieve().toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .map(res -> res.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);

        Mono<Boolean> redisPing = Mono.defer(() ->
                redisTemplate.getConnectionFactory().getReactiveConnection().ping()
        )
        .map(pong -> "PONG".equalsIgnoreCase(pong))
        .timeout(Duration.ofSeconds(3))
        .onErrorReturn(false);

        Mono<Boolean> mlServicePing = Mono.fromCallable(() -> {
            try {
                return mlServiceGrpcClient.getChannel().getState(true) == ConnectivityState.READY;
            } catch (Exception e) {
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic()).onErrorReturn(false);

        return Mono.zip(qdrantPing, elasticsearchPing, redisPing, mlServicePing)
                .map(tuple -> {
                    boolean qdrantOk = tuple.getT1();
                    boolean esOk = tuple.getT2();
                    boolean redisOk = tuple.getT3();
                    boolean mlOk = tuple.getT4();

                    Map<String, Object> body = Map.of(
                            "status", (qdrantOk && esOk && redisOk && mlOk) ? "ready" : "degraded",
                            "qdrant_connected", qdrantOk,
                            "elasticsearch_connected", esOk,
                            "redis_connected", redisOk,
                            "ml_service_connected", mlOk
                    );

                    if (qdrantOk && esOk && redisOk && mlOk) {
                        return ResponseEntity.ok(body);
                    } else {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
                    }
                });
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public Mono<String> getMetrics() {
        return Mono.fromCallable(prometheusRegistry::scrape)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
