package com.llmops.rag.controller;

import com.llmops.rag.grpc.MlServiceGrpcClient;
import com.llmops.rag.model.ChatRequest;
import com.llmops.rag.model.ChatResponse;
import com.llmops.rag.service.RouterService;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class ReasoningControllerTest {

    private WebTestClient webTestClient;
    private RouterService routerService;
    private WebClient qdrantWebClient;
    private WebClient elasticsearchWebClient;
    private ReactiveRedisTemplate<String, String> redisTemplate;
    private MlServiceGrpcClient mlServiceGrpcClient;
    
    // Mocks for WebClient chaining
    private WebClient.RequestHeadersUriSpec qdrantUriSpec;
    private WebClient.RequestHeadersSpec qdrantHeadersSpec;
    private WebClient.ResponseSpec qdrantResponseSpec;

    private WebClient.RequestHeadersUriSpec esUriSpec;
    private WebClient.RequestHeadersSpec esHeadersSpec;
    private WebClient.ResponseSpec esResponseSpec;

    private ReactiveRedisConnectionFactory redisFactory;
    private ReactiveRedisConnection redisConnection;
    private ManagedChannel grpcChannel;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setup() {
        routerService = Mockito.mock(RouterService.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PrometheusMeterRegistry prometheusRegistry = Mockito.mock(PrometheusMeterRegistry.class);
        
        qdrantWebClient = Mockito.mock(WebClient.class);
        elasticsearchWebClient = Mockito.mock(WebClient.class);
        redisTemplate = Mockito.mock(ReactiveRedisTemplate.class);
        mlServiceGrpcClient = Mockito.mock(MlServiceGrpcClient.class);

        // Qdrant mock setup
        qdrantUriSpec = Mockito.mock(WebClient.RequestHeadersUriSpec.class);
        qdrantHeadersSpec = Mockito.mock(WebClient.RequestHeadersSpec.class);
        qdrantResponseSpec = Mockito.mock(WebClient.ResponseSpec.class);
        when(qdrantWebClient.get()).thenReturn(qdrantUriSpec);
        when(qdrantUriSpec.uri(anyString())).thenReturn(qdrantHeadersSpec);
        when(qdrantHeadersSpec.retrieve()).thenReturn(qdrantResponseSpec);

        // Elasticsearch mock setup
        esUriSpec = Mockito.mock(WebClient.RequestHeadersUriSpec.class);
        esHeadersSpec = Mockito.mock(WebClient.RequestHeadersSpec.class);
        esResponseSpec = Mockito.mock(WebClient.ResponseSpec.class);
        when(elasticsearchWebClient.get()).thenReturn(esUriSpec);
        when(esUriSpec.uri(anyString())).thenReturn(esHeadersSpec);
        when(esHeadersSpec.retrieve()).thenReturn(esResponseSpec);

        // Redis mock setup
        redisFactory = Mockito.mock(ReactiveRedisConnectionFactory.class);
        redisConnection = Mockito.mock(ReactiveRedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(redisFactory);
        when(redisFactory.getReactiveConnection()).thenReturn(redisConnection);

        // gRPC mock setup
        grpcChannel = Mockito.mock(ManagedChannel.class);
        when(mlServiceGrpcClient.getChannel()).thenReturn(grpcChannel);

        ReasoningController controller = new ReasoningController(
                routerService, 
                meterRegistry, 
                prometheusRegistry,
                qdrantWebClient,
                elasticsearchWebClient,
                redisTemplate,
                mlServiceGrpcClient);
                
        webTestClient = WebTestClient.bindToController(controller).build();
    }

    @Test
    public void testHealthEndpoint() {
        webTestClient.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("healthy");
    }

    @Test
    public void testReadyEndpointAllHealthy() {
        when(qdrantResponseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));
        when(esResponseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));
        when(grpcChannel.getState(false)).thenReturn(ConnectivityState.READY);

        webTestClient.get().uri("/ready")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ready")
                .jsonPath("$.qdrant_connected").isEqualTo(true)
                .jsonPath("$.elasticsearch_connected").isEqualTo(true)
                .jsonPath("$.redis_connected").isEqualTo(true)
                .jsonPath("$.ml_service_connected").isEqualTo(true);
    }

    @Test
    public void testReadyEndpointDegraded() {
        // Qdrant fails
        when(qdrantResponseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()));
        when(esResponseSpec.toBodilessEntity()).thenReturn(Mono.just(ResponseEntity.ok().build()));
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));
        when(grpcChannel.getState(false)).thenReturn(ConnectivityState.READY);

        webTestClient.get().uri("/ready")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo("degraded")
                .jsonPath("$.qdrant_connected").isEqualTo(false)
                .jsonPath("$.elasticsearch_connected").isEqualTo(true)
                .jsonPath("$.redis_connected").isEqualTo(true)
                .jsonPath("$.ml_service_connected").isEqualTo(true);
    }

    @Test
    public void testReasoningChatEndpointNonStreaming() {
        ChatResponse mockResponse = new ChatResponse(
                "You can reverse a list using list.reverse() or slicing.",
                "commonsense",
                List.of(),
                null
        );

        when(routerService.routeNonStreaming(anyString(), any(), anyBoolean()))
                .thenReturn(Mono.just(mockResponse));

        ChatRequest request = new ChatRequest("How do I reverse a list?", List.of(), true, false);

        webTestClient.post().uri("/v1/reasoning-chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answer").isEqualTo("You can reverse a list using list.reverse() or slicing.")
                .jsonPath("$.reasoning_type").isEqualTo("commonsense");
    }
}
