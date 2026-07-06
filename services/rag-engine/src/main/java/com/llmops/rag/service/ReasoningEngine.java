package com.llmops.rag.service;

import com.llmops.rag.model.ReasoningTrace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
public class ReasoningEngine {

    private final RetrieverService retrieverService;
    private final RerankerService rerankerService;

    @Autowired
    public ReasoningEngine(RetrieverService retrieverService, RerankerService rerankerService) {
        this.retrieverService = retrieverService;
        this.rerankerService = rerankerService;
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> candidates) {
        Set<Integer> seen = new HashSet<>();
        List<Map<String, Object>> deduped = new ArrayList<>();
        for (Map<String, Object> cand : candidates) {
            int cid = (Integer) cand.get("chunk_id");
            if (!seen.contains(cid)) {
                seen.add(cid);
                deduped.add(cand);
            }
        }
        return deduped;
    }

    public Mono<ReasoningTrace> commonsensePath(ReasoningTrace trace) {
        System.out.println("Executing Commonsense Path...");
        return retrieverService.retrieve(trace.getQuery(), 20)
                .flatMap(candidates -> rerankerService.rerank(trace.getQuery(), candidates, 5))
                .map(reranked -> {
                    List<Integer> ids = reranked.stream().map(r -> (Integer) r.get("chunk_id")).toList();
                    trace.getRetrievedPerSubquery().put("main", ids);
                    trace.setRerankedFinal(reranked);
                    return trace;
                });
    }

    public Mono<ReasoningTrace> adaptivePath(ReasoningTrace trace) {
        System.out.println("Executing Adaptive Path...");
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
                    List<Map<String, Object>> allCandidates = new ArrayList<>();
                    for (List<Map<String, Object>> list : lists) {
                        allCandidates.addAll(list);
                    }
                    trace.setRerankedFinal(deduplicate(allCandidates));
                    return trace;
                });
    }

    public Mono<ReasoningTrace> strategicPath(ReasoningTrace trace) {
        System.out.println("Executing Strategic Path...");
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
                .map(lists -> {
                    List<Map<String, Object>> allSub = new ArrayList<>();
                    for (List<Map<String, Object>> list : lists) {
                        allSub.addAll(list);
                    }
                    return allSub;
                });

        return Mono.zip(mainRetrieve, subRerank)
                .map(tuple -> {
                    List<Map<String, Object>> level1Candidates = tuple.getT1();
                    List<Map<String, Object>> subCandidates = tuple.getT2();

                    List<Map<String, Object>> combined = new ArrayList<>();
                    for (Map<String, Object> c : level1Candidates) {
                        Map<String, Object> copy = new HashMap<>(c);
                        copy.put("final_score", c.getOrDefault("score", 0.0));
                        combined.add(copy);
                    }
                    combined.addAll(subCandidates);

                    trace.setRerankedFinal(deduplicate(combined));
                    return trace;
                });
    }

    public Mono<ReasoningTrace> execute(ReasoningTrace trace) {
        String rType = (String) trace.getClassification().getOrDefault("reasoning_type", "commonsense");
        if ("commonsense".equals(rType)) {
            return commonsensePath(trace);
        } else if ("adaptive".equals(rType)) {
            return adaptivePath(trace);
        } else if ("strategic".equals(rType)) {
            return strategicPath(trace);
        } else {
            return commonsensePath(trace);
        }
    }
}
