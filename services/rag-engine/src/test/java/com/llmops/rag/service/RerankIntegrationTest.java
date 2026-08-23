package com.llmops.rag.service;

import com.llmops.rag.grpc.MlServiceGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

@SpringBootTest
public class RerankIntegrationTest {

    @Autowired
    private RerankerService rerankerService;

    @Test
    public void testRerank() {
        String query = "Write OOps concepts on java";
        List<Map<String, Object>> candidates = List.of(
            Map.of("chunk_id", 1, "metadata", Map.of("chunk_text", "I've read a lot of people saying that some things shouldn't be written in an object orientated style...")),
            Map.of("chunk_id", 2, "metadata", Map.of("chunk_text", "Other than standard OO concepts, what are some other strategies that allow for producing good, cl..."))
        );
        List<Map<String, Object>> results = rerankerService.rerank(query, candidates, 2).block();
        System.out.println("Rerank results: " + results);
    }
}
