package com.llmops.rag.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@ConfigurationProperties(prefix = "rag.quality-gate")
public class QualityGateService {

    private Map<String, Double> thresholds = Map.of(
            "commonsense", 0.1,
            "adaptive", 0.05,
            "strategic", 0.02
    );

    public void setThresholds(Map<String, Double> thresholds) {
        this.thresholds = thresholds;
    }

    public record QualityGateResult(boolean passed, double score, double threshold) {}

    public QualityGateResult evaluate(List<Map<String, Object>> rerankedResults, String reasoningType) {
        double threshold = thresholds.getOrDefault(reasoningType, 0.1);
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            return new QualityGateResult(false, 0.0, threshold);
        }

        // Use the highest base_ce_score (raw CrossEncoder relevance).
        // Since rerankedResults are sorted by final_score, the first element's 
        // base_ce_score might not strictly be the absolute max if metadata bonuses 
        // shifted the order, so we find the max base_ce_score among the top 3.
        int limit = Math.min(3, rerankedResults.size());
        double maxScore = -999.0;
        for (int i = 0; i < limit; i++) {
            Map<String, Object> r = rerankedResults.get(i);
            Number ceScoreNum = (Number) r.get("base_ce_score");
            double score = ceScoreNum != null ? ceScoreNum.doubleValue() : 0.0;
            if (score > maxScore) {
                maxScore = score;
            }
        }

        boolean passed = maxScore >= threshold;
        return new QualityGateResult(passed, maxScore, threshold);
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
