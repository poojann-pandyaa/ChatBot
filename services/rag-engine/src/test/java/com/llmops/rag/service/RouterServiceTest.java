package com.llmops.rag.service;

import com.llmops.rag.config.RagCacheProperties;
import com.llmops.rag.grpc.MlServiceGrpcClient;
import com.llmops.rag.model.ChatResponse;
import com.llmops.rag.model.ReasoningTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RouterServiceTest {

    private RouterService routerService;
    private MlServiceGrpcClient mlServiceClient;
    private FollowupDetector followupDetector;
    private QualityGateService qualityGateService;
    private ReasoningEngine reasoningEngine;
    private GeneratorService generatorService;
    private RerankerService rerankerService;
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setup() {
        mlServiceClient = mock(MlServiceGrpcClient.class);
        followupDetector = mock(FollowupDetector.class);
        qualityGateService = mock(QualityGateService.class);
        reasoningEngine = mock(ReasoningEngine.class);
        generatorService = mock(GeneratorService.class);
        rerankerService = mock(RerankerService.class);
        redisTemplate = mock(ReactiveRedisTemplate.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        RagCacheProperties cacheProperties = new RagCacheProperties();

        routerService = new RouterService(
                mlServiceClient,
                followupDetector,
                qualityGateService,
                reasoningEngine,
                generatorService,
                rerankerService,
                redisTemplate,
                meterRegistry,
                cacheProperties
        );
    }

    @Test
    public void testHighAmbiguityShortCircuit() {
        String query = "what about it";
        when(followupDetector.isFollowup(query, List.of())).thenReturn(false);
        when(generatorService.rewriteQuery(query, List.of())).thenReturn(Mono.just(query));
        when(mlServiceClient.embed(query)).thenReturn(Mono.just(List.of(0.1, 0.2)));
        
        // Return a classification with ambiguity="high"
        when(mlServiceClient.classify(query)).thenReturn(Mono.just(Map.of(
                "ambiguity", "high",
                "reasoning_type", "commonsense"
        )));

        // We use spy on routerService to stub checkCache since Redis is hard to mock fully
        RouterService spyRouter = spy(routerService);
        doReturn(Mono.empty()).when(spyRouter).checkCache(any(), any());

        Mono<ChatResponse> responseMono = spyRouter.routeNonStreaming(query, List.of(), true);

        StepVerifier.create(responseMono)
                .assertNext(response -> {
                    assertEquals("Could you please clarify or provide more context for \"what about it\"? I need a bit more detail to give a good answer.", response.answer());
                    assertEquals("commonsense", response.reasoningType());
                    
                    Map<String, Object> trace = response.trace();
                    Map<String, Object> decisions = (Map<String, Object>) trace.get("router_decisions");
                    assertEquals("clarification_requested", decisions.get("path_taken"));
                })
                .verifyComplete();

        // Verify that ReasoningEngine was NEVER called
        verify(reasoningEngine, never()).execute(any(ReasoningTrace.class));
    }
    @Test
    public void testFormatSourcesWithStringQuestionId() {
        Map<String, Object> candidate = Map.of(
            "score", 0.9,
            "chunk_id", 123,
            "metadata", Map.of(
                "question_id", "Q-12345",
                "is_accepted", true,
                "domain", "test-domain",
                "chunk_text", "Some text"
            )
        );
        
        List<com.llmops.rag.model.SourceMetadata> result = routerService.formatSources(List.of(candidate));
        assertEquals(1, result.size());
        assertEquals("Q-12345", result.get(0).questionId());
    }

    @Test
    public void testFormatSourcesWithMissingQuestionId() {
        Map<String, Object> candidate = Map.of(
            "score", 0.9,
            "chunk_id", 123,
            "metadata", Map.of(
                "is_accepted", true,
                "domain", "test-domain",
                "chunk_text", "Some text"
            )
        );
        
        List<com.llmops.rag.model.SourceMetadata> result = routerService.formatSources(List.of(candidate));
        assertEquals(1, result.size());
        assertEquals("", result.get(0).questionId());
    }

    @Test
    public void testFormatSourcesWithNullMetadata() {
        java.util.Map<String, Object> cand = new java.util.HashMap<>();
        cand.put("chunk_id", 123);
        cand.put("metadata", null);

        List<com.llmops.rag.model.SourceMetadata> result = routerService.formatSources(List.of(cand));
        assertEquals(1, result.size());
        assertEquals("", result.get(0).questionId());
    }
}
