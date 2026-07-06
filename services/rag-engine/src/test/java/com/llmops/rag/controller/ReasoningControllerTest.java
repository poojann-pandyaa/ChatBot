package com.llmops.rag.controller;

import com.llmops.rag.model.ChatRequest;
import com.llmops.rag.model.ChatResponse;
import com.llmops.rag.service.RouterService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class ReasoningControllerTest {

    private WebTestClient webTestClient;
    private RouterService routerService;

    @BeforeEach
    public void setup() {
        routerService = Mockito.mock(RouterService.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PrometheusMeterRegistry prometheusRegistry = Mockito.mock(PrometheusMeterRegistry.class);

        ReasoningController controller = new ReasoningController(routerService, meterRegistry, prometheusRegistry);
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
