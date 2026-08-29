package com.llmops.rag.service;

import com.llmops.rag.grpc.MlServiceGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RerankerServiceTest {

    private RerankerService rerankerService;
    private MlServiceGrpcClient mlServiceClient;

    @BeforeEach
    public void setup() {
        mlServiceClient = mock(MlServiceGrpcClient.class);
        rerankerService = new RerankerService(mlServiceClient);
    }

    @Test
    public void testRerankWithNullMetadata() {
        when(mlServiceClient.rerank(anyString(), anyList())).thenReturn(Mono.just(List.of(0.9)));
        
        Map<String, Object> cand = new HashMap<>();
        cand.put("chunk_id", 1);
        cand.put("metadata", null); // Trigger NPE if not handled

        List<Map<String, Object>> result = rerankerService.rerank("test", List.of(cand), 1).block();
        
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).get("chunk_id"));
    }
}
