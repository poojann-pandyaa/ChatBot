package com.llmops.gateway.controller;

import com.llmops.gateway.grpc.RagEngineGrpcClient;
import com.llmops.gateway.model.UserChatRequest;
import com.llmops.gateway.repository.ConversationRepository;
import com.llmops.gateway.service.ConversationCommandService;
import com.llmops.gateway.service.ConversationQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class ChatControllerTest {

    private WebTestClient webTestClient;
    private ReactiveRedisTemplate<String, String> redisTemplate;
    private ConversationRepository conversationRepository;
    private RagEngineGrpcClient ragEngineClient;
    private ConversationCommandService conversationCommandService;
    private ConversationQueryService conversationQueryService;

    @BeforeEach
    public void setup() {
        redisTemplate = Mockito.mock(ReactiveRedisTemplate.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        ragEngineClient = Mockito.mock(RagEngineGrpcClient.class);
        conversationCommandService = Mockito.mock(ConversationCommandService.class);
        conversationQueryService = Mockito.mock(ConversationQueryService.class);
        
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PrometheusMeterRegistry prometheusRegistry = Mockito.mock(PrometheusMeterRegistry.class);

        @SuppressWarnings("unchecked")
        ReactiveListOperations<String, String> listOps = Mockito.mock(ReactiveListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(Flux.fromIterable(List.of("user:previous prompt", "assistant:previous response")));
        when(listOps.rightPush(anyString(), anyString())).thenReturn(Mono.just(1L));



        ChatController controller = new ChatController(
                redisTemplate,
                conversationRepository,
                ragEngineClient,
                conversationCommandService,
                conversationQueryService,
                meterRegistry,
                prometheusRegistry
        );
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
    public void testChatEndpointNonStreaming() {
        Map<String, Object> mockRes = Map.of(
                "answer", "This is the RAG answer",
                "reasoning_type", "commonsense",
                "sources", List.of()
        );
        when(ragEngineClient.chat(any())).thenReturn(Mono.just(mockRes));

        UserChatRequest request = new UserChatRequest("Hello", "session-123", false, false, "user-456");

        webTestClient.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answer").isEqualTo("This is the RAG answer")
                .jsonPath("$.reasoning_type").isEqualTo("commonsense");
    }

    @Test
    public void testGetHistoryEndpoint() {
        Map<String, Object> mockHistory = Map.of(
                "conversation_id", "session-123",
                "messages", List.of(
                        Map.of("role", "user", "content", "Hello"),
                        Map.of("role", "assistant", "content", "This is the RAG answer")
                )
        );
        when(conversationQueryService.getConversationHistory("session-123")).thenReturn(Mono.just(mockHistory));

        webTestClient.get().uri("/api/history/session-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.conversation_id").isEqualTo("session-123")
                .jsonPath("$.messages[0].role").isEqualTo("user")
                .jsonPath("$.messages[1].role").isEqualTo("assistant");
    }
}
