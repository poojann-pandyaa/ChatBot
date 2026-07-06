package com.llmops.rag.service;

import com.llmops.rag.model.ReasoningTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
public class ReasoningEngine {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEngine.class);

    private final RetrieverService retrieverService;
    private final RerankerService rerankerService;

    @Autowired
    public ReasoningEngine(RetrieverService retrieverService, RerankerService rerankerService) {
        this.retrieverService = retrieverService;
        this.rerankerService = rerankerService;
    }

    /**
     * Routes execution to one of three retrieval strategies based on reasoning type:
     * commonsense (single query), adaptive (parallel sub-questions), or strategic (combined).
     */
    public Mono<ReasoningTrace> execute(ReasoningTrace trace) {
        String reasoningType = (String) trace.getClassification().getOrDefault("reasoning_type", "commonsense");
        return switch (reasoningType) {
            case "adaptive" -> adaptivePath(trace);
            case "strategic" -> strategicPath(trace);
            default -> commonsensePath(trace);
        };
    }

    /** Simple retrieval: embed the main query, retrieve, rerank, store top-5. */
    private Mono<ReasoningTrace> commonsensePath(ReasoningTrace trace) {
        log.info("Executing Commonsense Path...");
        return retrieverService.retrieve(trace.getQuery(), 20)
                .flatMap(candidates -> rerankerService.rerank(trace.getQuery(), candidates, 5))
                .map(reranked -> {
                    List<Integer> ids = reranked.stream().map(r -> (Integer) r.get("chunk_id")).toList();
                    trace.getRetrievedPerSubquery().put("main", ids);
                    trace.setRerankedFinal(reranked);
                    return trace;
                });
    }

    /** Multi-question retrieval: retrieve and rerank per sub-question, then deduplicate. */
    private Mono<ReasoningTrace> adaptivePath(ReasoningTrace trace) {
        log.info("Executing Adaptive Path...");
        @SuppressWarnings("unchecked")
        List<String> subQuestions = (List<String>) trace.getClassification().getOrDefault("sub_questions", List.of());

        return Flux.fromIterable(subQuestions)
                .flatMap(sq -> retrieverService.retrieve(sq, 10)
                        .flatMap(cands -> rerankerService.rerank(sq, cands, 3))
                        .map(ranked -> {
                            List<Integer> ids = ranked.stream().map(r -> (Integer) r.get("chunk_id")).toList();
                            synchronized (trace.getRetrievedPerSubquery()) {
                                trace.getRetrievedPerSubquery().put(sq, ids);
                            }
                            return ranked;
                        })
                )
                .collectList()
                .map(lists -> {
                    List<Map<String, Object>> allCandidates = lists.stream()
                            .flatMap(Collection::stream)
                            .toList();
                    trace.setRerankedFinal(deduplicate(allCandidates));
                    return trace;
                });
    }

    /**
     * Hierarchical retrieval: main query (level-1) and sub-questions run in parallel,
     * results are combined and deduplicated.
     */
    private Mono<ReasoningTrace> strategicPath(ReasoningTrace trace) {
        log.info("Executing Strategic Path...");
        @SuppressWarnings("unchecked")
        List<String> subQuestions = (List<String>) trace.getClassification().getOrDefault("sub_questions", List.of());

        Mono<List<Map<String, Object>>> mainRetrieve = retrieverService.retrieve(trace.getQuery(), 10)
                .map(list -> {
                    List<Integer> ids = list.stream().limit(3).map(r -> (Integer) r.get("chunk_id")).toList();
                    trace.getRetrievedPerSubquery().put("level1_main", ids);
                    return list;
                });

        Mono<List<Map<String, Object>>> subRerank = Flux.fromIterable(subQuestions)
                .flatMap(sq -> retrieverService.retrieve(sq, 10)
                        .flatMap(cands -> rerankerService.rerank(sq, cands, 3))
                        .map(ranked -> {
                            List<Integer> ids = ranked.stream().map(r -> (Integer) r.get("chunk_id")).toList();
                            synchronized (trace.getRetrievedPerSubquery()) {
                                trace.getRetrievedPerSubquery().put(sq, ids);
                            }
                            return ranked;
                        })
                )
                .collectList()
                .map(lists -> lists.stream().flatMap(Collection::stream).toList());

        return Mono.zip(mainRetrieve, subRerank)
                .map(tuple -> {
                    List<Map<String, Object>> combined = new ArrayList<>();
                    for (Map<String, Object> c : tuple.getT1()) {
                        Map<String, Object> copy = new HashMap<>(c);
                        copy.put("final_score", c.getOrDefault("score", 0.0));
                        combined.add(copy);
                    }
                    combined.addAll(tuple.getT2());
                    trace.setRerankedFinal(deduplicate(combined));
                    return trace;
                });
    }

    /** Removes duplicate chunk IDs, preserving first-seen ordering. */
    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> candidates) {
        Set<Integer> seen = new HashSet<>();
        List<Map<String, Object>> deduped = new ArrayList<>();
        for (Map<String, Object> cand : candidates) {
            int cid = (Integer) cand.get("chunk_id");
            if (seen.add(cid)) {
                deduped.add(cand);
            }
        }
        return deduped;
    }
}
