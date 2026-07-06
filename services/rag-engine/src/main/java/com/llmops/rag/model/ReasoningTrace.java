package com.llmops.rag.model;

import java.util.*;

public class ReasoningTrace {
    private String query;
    private List<ChatMessage> history = new ArrayList<>();
    private Map<String, Object> classification = new HashMap<>();
    private Map<String, List<Integer>> retrievedPerSubquery = new HashMap<>();
    private List<Map<String, Object>> rerankedFinal = new ArrayList<>();
    private String generationPrompt = "";
    private String finalAnswer = "";
    private final Map<String, Object> routerDecisions = new HashMap<>();

    public ReasoningTrace(String query) {
        this.query = query;
        this.routerDecisions.put("original_query", query);
        this.routerDecisions.put("is_followup", false);
        this.routerDecisions.put("query_rewritten", false);
        this.routerDecisions.put("rewritten_query", null);
        this.routerDecisions.put("cache_hit", false);
        this.routerDecisions.put("retrieval_retried", false);
        this.routerDecisions.put("retry_reason", null);
        this.routerDecisions.put("refined_query", null);
        this.routerDecisions.put("quality_score", null);
        this.routerDecisions.put("path_taken", "unknown");
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public void setHistory(List<ChatMessage> history) {
        this.history = history;
    }

    public Map<String, Object> getClassification() {
        return classification;
    }

    public void setClassification(Map<String, Object> classification) {
        this.classification = classification;
    }

    public Map<String, List<Integer>> getRetrievedPerSubquery() {
        return retrievedPerSubquery;
    }

    public void setRetrievedPerSubquery(Map<String, List<Integer>> retrievedPerSubquery) {
        this.retrievedPerSubquery = retrievedPerSubquery;
    }

    public List<Map<String, Object>> getRerankedFinal() {
        return rerankedFinal;
    }

    public void setRerankedFinal(List<Map<String, Object>> rerankedFinal) {
        this.rerankedFinal = rerankedFinal;
    }

    public String getGenerationPrompt() {
        return generationPrompt;
    }

    public void setGenerationPrompt(String generationPrompt) {
        this.generationPrompt = generationPrompt;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public Map<String, Object> getRouterDecisions() {
        return routerDecisions;
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> rerankedFinalConfigs = new ArrayList<>();
        if (rerankedFinal != null) {
            for (Map<String, Object> r : rerankedFinal) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) r.get("metadata");
                rerankedFinalConfigs.add(metadata);
            }
        }

        Map<String, Object> map = new HashMap<>();
        map.put("query", query);
        map.put("history", history);
        map.put("classification", classification);
        map.put("retrieved_per_subquery", retrievedPerSubquery);
        map.put("reranked_final_configs", rerankedFinalConfigs);
        map.put("generation_prompt", generationPrompt);
        map.put("final_answer", finalAnswer);
        map.put("router_decisions", routerDecisions);
        return map;
    }
}
