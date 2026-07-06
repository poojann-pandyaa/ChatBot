package com.llmops.rag.service;

import com.llmops.rag.client.ElasticsearchClient;
import com.llmops.rag.client.MlServiceClient;
import com.llmops.rag.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class RetrieverServiceTest {

    private RetrieverService retrieverService;
    private MlServiceClient mlServiceClient;
    private QdrantClient qdrantClient;
    private ElasticsearchClient elasticsearchClient;

    @BeforeEach
    public void setup() {
        mlServiceClient = Mockito.mock(MlServiceClient.class);
        qdrantClient = Mockito.mock(QdrantClient.class);
        elasticsearchClient = Mockito.mock(ElasticsearchClient.class);
        retrieverService = new RetrieverService(mlServiceClient, qdrantClient, elasticsearchClient);
    }

    @Test
    public void testHybridRetrieveRrfScoring() {
        when(mlServiceClient.embed(anyString())).thenReturn(Mono.just(List.of(0.1, 0.2)));

        List<Map<String, Object>> mockDense = List.of(
                Map.of("chunk_id", 1, "payload", Map.of("chunk_text", "result 1"))
        );
        List<Map<String, Object>> mockSparse = List.of(
                Map.of("chunk_id", 1, "payload", Map.of("chunk_text", "result 1")),
                Map.of("chunk_id", 2, "payload", Map.of("chunk_text", "result 2"))
        );

        when(qdrantClient.search(anyList(), anyInt())).thenReturn(Mono.just(mockDense));
        when(elasticsearchClient.search(anyString(), anyInt())).thenReturn(Mono.just(mockSparse));

        Mono<List<Map<String, Object>>> retrieveMono = retrieverService.retrieve("search query", 5);

        StepVerifier.create(retrieveMono)
                .expectNextMatches(results -> {
                    // chunk_id 1 is present in both dense and sparse, so it should rank first due to higher RRF score
                    return results.size() == 2 &&
                            (Integer) results.get(0).get("chunk_id") == 1 &&
                            (Integer) results.get(1).get("chunk_id") == 2;
                })
                .verifyComplete();
    }
}
