package com.llmops.rag.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class QualityGateService {

    private static final Map<String, Double> QUALITY_THRESHOLDS = Map.of(
            "commonsense", 0.1,
            "adaptive", 0.05,
            "strategic", 0.02
    );

    public record QualityGateResult(boolean passed, double averageScore, double threshold) {}

    public QualityGateResult evaluate(List<Map<String, Object>> rerankedResults, String reasoningType) {
        double threshold = QUALITY_THRESHOLDS.getOrDefault(reasoningType, 0.1);
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            return new QualityGateResult(false, 0.0, threshold);
        }

        int limit = Math.min(3, rerankedResults.size());
        double sum = 0.0;
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = rerankedResults.get(i);
            Number finalScoreNum = (Number) r.get("final_score");
            double score = finalScoreNum != null ? finalScoreNum.doubleValue() : 0.0;
            sum += score;
        }

        double averageScore = sum / limit;
        boolean passed = averageScore >= threshold;
        return new QualityGateResult(passed, averageScore, threshold);
    }

    public String refineQuery(String originalQuery, Map<String, Object> classification) {
        @SuppressWarnings("unchecked")
        List<String> subQuestions = (List<String>) classification.get("sub_questions");
        if (subQuestions != null && !subQuestions.isEmpty() && !subQuestions.get(0).equals(originalQuery)) {
            return subQuestions.get(0);
        }

        String intent = (String) classification.getOrDefault("intent", "factual");
        String refined = originalQuery.trim();
        String lowerRefined = refined.toLowerCase();

        if ("procedural".equals(intent)) {
            boolean hasKeywords = lowerRefined.contains("code") || lowerRefined.contains("example") || lowerRefined.contains("how to");
            if (!hasKeywords) {
                refined = refined + " code example implementation";
            }
        } else if ("debugging".equals(intent)) {
            boolean hasKeywords = lowerRefined.contains("error") || lowerRefined.contains("exception") || lowerRefined.contains("fix") || lowerRefined.contains("solve");
            if (!hasKeywords) {
                refined = refined + " error exception fix solution";
            }
        }

        return refined;
    }
}
