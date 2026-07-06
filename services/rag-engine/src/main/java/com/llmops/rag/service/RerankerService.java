package com.llmops.rag.service;

import com.llmops.rag.client.MlServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class RerankerService {

    private final MlServiceClient mlServiceClient;

    @Autowired
    public RerankerService(MlServiceClient mlServiceClient) {
        this.mlServiceClient = mlServiceClient;
    }

    public Mono<List<Map<String, Object>>> rerank(String query, List<Map<String, Object>> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Mono.just(List.of());
        }

        List<String> documents = candidates.stream().map(cand -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) cand.get("metadata");
            return (String) meta.getOrDefault("chunk_text", "");
        }).toList();

        return mlServiceClient.rerank(query, documents)
                .map(scores -> {
                    List<Map<String, Object>> scored = new ArrayList<>();
                    for (int i = 0; i < candidates.size(); i++) {
                        Map<String, Object> cand = candidates.get(i);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = (Map<String, Object>) cand.get("metadata");
                        
                        double baseScore = i < scores.size() ? scores.get(i) : 0.0;
                        
                        // Parse StackExchange metadata properties
                        Number stackScoreNum = (Number) meta.getOrDefault("score", 0);
                        double stackScore = stackScoreNum != null ? stackScoreNum.doubleValue() : 0.0;
                        
                        Boolean isAcceptedBool = (Boolean) meta.getOrDefault("is_accepted", false);
                        boolean isAccepted = isAcceptedBool != null && isAcceptedBool;

                        double scoreSignal = 0.1 * Math.min(stackScore / 100.0, 1.0);
                        double acceptedSignal = isAccepted ? 0.15 : 0.0;
                        double finalScore = baseScore + scoreSignal + acceptedSignal;

                        scored.add(Map.of(
                                "chunk_id", cand.get("chunk_id"),
                                "metadata", meta,
                                "base_ce_score", baseScore,
                                "final_score", finalScore
                        ));
                    }
                    
                    // Sort descending by final score
                    List<Map<String, Object>> sorted = new ArrayList<>(scored);
                    sorted.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("final_score")).doubleValue()).reversed());
                    
                    return sorted.stream().limit(topK).toList();
                });
    }
}
