package com.llmops.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.rag.client.MlServiceClient;
import com.llmops.rag.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.llmops.rag.config.RedisCommandExecutor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final MlServiceClient mlServiceClient;
    private final FollowupDetector followupDetector;
    private final QualityGateService qualityGateService;
    private final ReasoningEngine reasoningEngine;
    private final GeneratorService generatorService;
    private final RerankerService rerankerService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RouterService(
            MlServiceClient mlServiceClient,
            FollowupDetector followupDetector,
            QualityGateService qualityGateService,
            ReasoningEngine reasoningEngine,
            GeneratorService generatorService,
            RerankerService rerankerService,
            ReactiveRedisTemplate<String, String> redisTemplate) {
        this.mlServiceClient = mlServiceClient;
        this.followupDetector = followupDetector;
        this.qualityGateService = qualityGateService;
        this.reasoningEngine = reasoningEngine;
        this.generatorService = generatorService;
        this.rerankerService = rerankerService;
        this.redisTemplate = redisTemplate;
    }

    private byte[] toByteArray(List<Double> vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.size() * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (Double d : vector) {
            buffer.putFloat(d.floatValue());
        }
        return buffer.array();
    }

    private String safeDecode(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        }
        return obj.toString();
    }

    public Mono<Map<String, Object>> checkCache(List<Double> qVector, String reasoningType) {
        byte[] vectorBytes = toByteArray(qVector);
        return RedisCommandExecutor.execute(redisTemplate, "FT.SEARCH",
                "idx:semantic_cache".getBytes(StandardCharsets.UTF_8),
                "*=>[KNN 1 @embedding $vec_param AS score]".getBytes(StandardCharsets.UTF_8),
                "PARAMS".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8),
                "vec_param".getBytes(StandardCharsets.UTF_8),
                vectorBytes,
                "SORTBY".getBytes(StandardCharsets.UTF_8),
                "score".getBytes(StandardCharsets.UTF_8),
                "DIALECT".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8)
        ).next()
        .flatMap(res -> {
            try {
                if (res instanceof List) {
                    List<?> list = (List<?>) res;
                    if (list.size() > 2) {
                        Object fieldsObj = list.get(2);
                        if (fieldsObj instanceof List) {
                            List<?> fields = (List<?>) fieldsObj;
                            Map<String, String> properties = new HashMap<>();
                            for (int i = 0; i < fields.size(); i += 2) {
                                String key = safeDecode(fields.get(i));
                                String val = safeDecode(fields.get(i + 1));
                                properties.put(key, val);
                            }
                            double score = 1.0;
                            if (properties.containsKey("score")) {
                                score = Double.parseDouble(properties.get("score"));
                            }
                            double threshold = "commonsense".equals(reasoningType) || "unknown".equals(reasoningType) ? 0.05 : 0.08;
                            if (score <= threshold) {
                                log.info("Semantic Cache HIT! Score: {} (threshold: {})", score, threshold);
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> sources = mapper.readValue(properties.get("sources"), List.class);
                                return Mono.just(Map.of(
                                        "answer", properties.get("answer"),
                                        "reasoning_type", properties.get("reasoning_type"),
                                        "sources", sources,
                                        "score", score
                                ));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Cache parsing failed: {}", e.getMessage());
            }
            return Mono.empty();
        })
        .onErrorResume(e -> {
            log.warn("Semantic cache lookup failed: {}", e.getMessage());
            return Mono.empty();
        });
    }

    private Mono<Void> saveToCache(String rewrittenPrompt, List<Double> qVector, String answer, String reasoningType, List<SourceMetadata> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rewrittenPrompt.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String key = "cache:" + hexString.toString();
            byte[] embeddingBytes = toByteArray(qVector);
            String sourcesJson = mapper.writeValueAsString(sources);

            return redisTemplate.execute(conn -> {
                Mono<Boolean> hset = conn.hashCommands().hMSet(
                        ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)),
                        Map.of(
                                ByteBuffer.wrap("embedding".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(embeddingBytes),
                                ByteBuffer.wrap("query".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(rewrittenPrompt.getBytes(StandardCharsets.UTF_8)),
                                ByteBuffer.wrap("answer".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(answer.getBytes(StandardCharsets.UTF_8)),
                                ByteBuffer.wrap("reasoning_type".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(reasoningType.getBytes(StandardCharsets.UTF_8)),
                                ByteBuffer.wrap("sources".getBytes(StandardCharsets.UTF_8)), ByteBuffer.wrap(sourcesJson.getBytes(StandardCharsets.UTF_8))
                        )
                );
                Mono<Boolean> expire = conn.keyCommands().expire(
                        ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)),
                        java.time.Duration.ofSeconds(86400)
                );
                return hset.then(expire);
            }).then()
            .doOnSuccess(v -> log.info("Saved to semantic cache: {}", key))
            .onErrorResume(e -> {
                log.warn("Async cache save failed: {}", e.getMessage());
                return Mono.empty();
            });
        } catch (Exception e) {
            log.error("Failed to hash cache query: {}", e.getMessage());
            return Mono.empty();
        }
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

    public Mono<ChatResponse> routeNonStreaming(String prompt, List<ChatMessage> history, boolean includeTrace) {
        ReasoningTrace trace = new ReasoningTrace(prompt);
        trace.setHistory(history);

        boolean isFollowup = followupDetector.isFollowup(prompt, history);
        trace.getRouterDecisions().put("is_followup", isFollowup);

        Mono<String> promptMono = isFollowup ? generatorService.rewriteQuery(prompt, history) : Mono.just(prompt);

        return promptMono.flatMap(rewrittenPrompt -> {
            if (isFollowup) {
                trace.getRouterDecisions().put("query_rewritten", true);
                trace.getRouterDecisions().put("rewritten_query", rewrittenPrompt);
                trace.setQuery(rewrittenPrompt);
            }

            return mlServiceClient.embed(rewrittenPrompt)
                    .flatMap(qVector -> checkCache(qVector, "commonsense")
                            .map(cacheHit -> {
                                trace.getRouterDecisions().put("cache_hit", true);
                                trace.getRouterDecisions().put("path_taken", "cache_hit");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> rawSources = (List<Map<String, Object>>) cacheHit.get("sources");
                                List<SourceMetadata> sources = rawSources.stream().map(s -> new SourceMetadata(
                                        ((Number) s.get("chunk_id")).intValue(),
                                        ((Number) s.get("score")).doubleValue(),
                                        (String) s.get("question_id"),
                                        (Boolean) s.get("is_accepted"),
                                        (String) s.get("domain"),
                                        (String) s.get("chunk_text")
                                )).toList();
                                return new ChatResponse(
                                        (String) cacheHit.get("answer"),
                                        (String) cacheHit.get("reasoning_type"),
                                        sources,
                                        includeTrace ? trace.toMap() : null
                                );
                            })
                            .switchIfEmpty(Mono.defer(() -> mlServiceClient.classify(rewrittenPrompt)
                                    .flatMap(classification -> {
                                        trace.setClassification(classification);
                                        String reasoningType = (String) classification.getOrDefault("reasoning_type", "commonsense");

                                        return reasoningEngine.execute(trace)
                                                .flatMap(engineTrace -> {
                                                    QualityGateService.QualityGateResult qgResult = qualityGateService.evaluate(engineTrace.getRerankedFinal(), reasoningType);
                                                    trace.getRouterDecisions().put("quality_score", qgResult.averageScore());

                                                    Mono<ReasoningTrace> pipelineMono;
                                                    if (!qgResult.passed()) {
                                                        log.warn("Quality gate FAILED. Retrying with refinement.");
                                                        trace.getRouterDecisions().put("retrieval_retried", true);
                                                        trace.getRouterDecisions().put("retry_reason", "low_relevance");
                                                        trace.getRouterDecisions().put("path_taken", "retry_rag");

                                                        String refinedQuery = qualityGateService.refineQuery(rewrittenPrompt, classification);
                                                        trace.getRouterDecisions().put("refined_query", refinedQuery);

                                                        ReasoningTrace retryTrace = new ReasoningTrace(refinedQuery);
                                                        retryTrace.setClassification(classification);

                                                        pipelineMono = reasoningEngine.execute(retryTrace)
                                                                .flatMap(retryEngineTrace -> {
                                                                    List<Map<String, Object>> combined = deduplicate(combineLists(trace.getRerankedFinal(), retryEngineTrace.getRerankedFinal()));
                                                                    List<Map<String, Object>> candidatesToRerank = combined.stream().map(c -> Map.of(
                                                                            "chunk_id", c.get("chunk_id"),
                                                                            "metadata", c.get("metadata")
                                                                    )).toList();
                                                                    return rerankerService.rerank(rewrittenPrompt, candidatesToRerank, 5)
                                                                            .map(rerankedCombined -> {
                                                                                trace.setRerankedFinal(rerankedCombined);
                                                                                return trace;
                                                                            });
                                                                });
                                                    } else {
                                                        trace.getRouterDecisions().put("path_taken", "commonsense".equals(reasoningType) ? "simple_rag" : "multi_step_rag");
                                                        pipelineMono = Mono.just(trace);
                                                    }

                                                    return pipelineMono.flatMap(finalTrace -> {
                                                        List<SourceMetadata> sources = formatSources(finalTrace.getRerankedFinal());
                                                        String promptForGeneration = generatorService.buildPrompt(
                                                                finalTrace.getQuery(),
                                                                finalTrace.getRerankedFinal(),
                                                                reasoningType,
                                                                (List<String>) classification.get("sub_questions"),
                                                                history
                                                        );
                                                        finalTrace.setGenerationPrompt(promptForGeneration);

                                                        return generatorService.generate(promptForGeneration, reasoningType)
                                                                .flatMap(answer -> {
                                                                    finalTrace.setFinalAnswer(answer);
                                                                    // Cache async save
                                                                    saveToCache(rewrittenPrompt, qVector, answer, reasoningType, sources).subscribe();
                                                                    return Mono.just(new ChatResponse(
                                                                            answer,
                                                                            reasoningType,
                                                                            sources,
                                                                            includeTrace ? finalTrace.toMap() : null
                                                                    ));
                                                                });
                                                    });
                                                });
                                    })
                            ))
            );
        });
    }

    public Flux<String> routeStreaming(String prompt, List<ChatMessage> history, boolean includeTrace) {
        ReasoningTrace trace = new ReasoningTrace(prompt);
        trace.setHistory(history);

        boolean isFollowup = followupDetector.isFollowup(prompt, history);
        trace.getRouterDecisions().put("is_followup", isFollowup);

        Mono<String> promptMono = isFollowup ? generatorService.rewriteQuery(prompt, history) : Mono.just(prompt);

        return promptMono.flatMapMany(rewrittenPrompt -> {
            if (isFollowup) {
                trace.getRouterDecisions().put("query_rewritten", true);
                trace.getRouterDecisions().put("rewritten_query", rewrittenPrompt);
                trace.setQuery(rewrittenPrompt);
            }

            return mlServiceClient.embed(rewrittenPrompt)
                    .flatMapMany(qVector -> checkCache(qVector, "commonsense")
                            .flatMapMany(cacheHit -> {
                                trace.getRouterDecisions().put("cache_hit", true);
                                trace.getRouterDecisions().put("path_taken", "cache_hit");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> rawSources = (List<Map<String, Object>>) cacheHit.get("sources");
                                List<SourceMetadata> sources = rawSources.stream().map(s -> new SourceMetadata(
                                        ((Number) s.get("chunk_id")).intValue(),
                                        ((Number) s.get("score")).doubleValue(),
                                        (String) s.get("question_id"),
                                        (Boolean) s.get("is_accepted"),
                                        (String) s.get("domain"),
                                        (String) s.get("chunk_text")
                                )).toList();

                                Map<String, Object> tracePayload = Map.of(
                                        "reasoning_type", cacheHit.get("reasoning_type"),
                                        "sub_questions", List.of(rewrittenPrompt),
                                        "sources", sources,
                                        "router_decisions", trace.getRouterDecisions()
                                );

                                String traceLine = serializeJson(Map.of("type", "trace", "data", tracePayload)) + "\n";
                                String tokenLine = serializeJson(Map.of("type", "token", "data", cacheHit.get("answer"))) + "\n";

                                return Flux.just(traceLine, tokenLine);
                            })
                            .switchIfEmpty(Flux.defer(() -> mlServiceClient.classify(rewrittenPrompt)
                                    .flatMapMany(classification -> {
                                        trace.setClassification(classification);
                                        String reasoningType = (String) classification.getOrDefault("reasoning_type", "commonsense");

                                        return reasoningEngine.execute(trace)
                                                .flatMapMany(engineTrace -> {
                                                    QualityGateService.QualityGateResult qgResult = qualityGateService.evaluate(engineTrace.getRerankedFinal(), reasoningType);
                                                    trace.getRouterDecisions().put("quality_score", qgResult.averageScore());

                                                    Mono<ReasoningTrace> pipelineMono;
                                                    if (!qgResult.passed()) {
                                                        log.warn("Quality gate FAILED (streaming). Retrying with refinement.");
                                                        trace.getRouterDecisions().put("retrieval_retried", true);
                                                        trace.getRouterDecisions().put("retry_reason", "low_relevance");
                                                        trace.getRouterDecisions().put("path_taken", "retry_rag");

                                                        String refinedQuery = qualityGateService.refineQuery(rewrittenPrompt, classification);
                                                        trace.getRouterDecisions().put("refined_query", refinedQuery);

                                                        ReasoningTrace retryTrace = new ReasoningTrace(refinedQuery);
                                                        retryTrace.setClassification(classification);

                                                        pipelineMono = reasoningEngine.execute(retryTrace)
                                                                .flatMap(retryEngineTrace -> {
                                                                    List<Map<String, Object>> combined = deduplicate(combineLists(trace.getRerankedFinal(), retryEngineTrace.getRerankedFinal()));
                                                                    List<Map<String, Object>> candidatesToRerank = combined.stream().map(c -> Map.of(
                                                                            "chunk_id", c.get("chunk_id"),
                                                                            "metadata", c.get("metadata")
                                                                    )).toList();
                                                                    return rerankerService.rerank(rewrittenPrompt, candidatesToRerank, 5)
                                                                            .map(rerankedCombined -> {
                                                                                trace.setRerankedFinal(rerankedCombined);
                                                                                return trace;
                                                                            });
                                                                });
                                                    } else {
                                                        trace.getRouterDecisions().put("path_taken", "commonsense".equals(reasoningType) ? "simple_rag" : "multi_step_rag");
                                                        pipelineMono = Mono.just(trace);
                                                    }

                                                    return pipelineMono.flatMapMany(finalTrace -> {
                                                        List<SourceMetadata> sources = formatSources(finalTrace.getRerankedFinal());
                                                        String promptForGeneration = generatorService.buildPrompt(
                                                                finalTrace.getQuery(),
                                                                finalTrace.getRerankedFinal(),
                                                                reasoningType,
                                                                (List<String>) classification.get("sub_questions"),
                                                                history
                                                        );
                                                        finalTrace.setGenerationPrompt(promptForGeneration);

                                                        Map<String, Object> tracePayload = Map.of(
                                                                "reasoning_type", reasoningType,
                                                                "sub_questions", classification.getOrDefault("sub_questions", List.of()),
                                                                "sources", sources,
                                                                "router_decisions", finalTrace.getRouterDecisions()
                                                        );
                                                        String traceLine = serializeJson(Map.of("type", "trace", "data", tracePayload)) + "\n";

                                                        StringBuilder accumulatedAnswer = new StringBuilder();
                                                        Flux<String> tokensFlux = generatorService.generateStream(promptForGeneration)
                                                                .map(token -> {
                                                                    accumulatedAnswer.append(token);
                                                                    return serializeJson(Map.of("type", "token", "data", token)) + "\n";
                                                                })
                                                                .doOnComplete(() -> {
                                                                    saveToCache(rewrittenPrompt, qVector, accumulatedAnswer.toString(), reasoningType, sources).subscribe();
                                                                })
                                                                .onErrorResume(e -> Flux.just(serializeJson(Map.of("type", "error", "data", e.getMessage())) + "\n"));

                                                        return Flux.concat(Flux.just(traceLine), tokensFlux);
                                                    });
                                                });
                                    })
                            ))
            );
        });
    }

    private List<SourceMetadata> formatSources(List<Map<String, Object>> rerankedFinal) {
        List<SourceMetadata> list = new ArrayList<>();
        if (rerankedFinal != null) {
            for (Map<String, Object> c : rerankedFinal) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) c.get("metadata");
                
                Number scoreNum = (Number) c.getOrDefault("final_score", c.getOrDefault("score", 0.0));
                double score = scoreNum != null ? scoreNum.doubleValue() : 0.0;
                
                Number chunkIdNum = (Number) c.get("chunk_id");
                int chunkId = chunkIdNum != null ? chunkIdNum.intValue() : 0;
                
                Number qIdNum = (Number) meta.getOrDefault("question_id", "");
                String questionId = qIdNum != null ? qIdNum.toString() : "";
                
                Boolean isAcc = (Boolean) meta.getOrDefault("is_accepted", false);
                boolean isAccepted = isAcc != null && isAcc;
                
                String domain = (String) meta.getOrDefault("domain", "");
                String chunkText = (String) meta.getOrDefault("chunk_text", "");

                list.add(new SourceMetadata(chunkId, score, questionId, isAccepted, domain, chunkText));
            }
        }
        return list;
    }

    private List<Map<String, Object>> combineLists(List<Map<String, Object>> l1, List<Map<String, Object>> l2) {
        List<Map<String, Object>> combined = new ArrayList<>();
        if (l1 != null) combined.addAll(l1);
        if (l2 != null) combined.addAll(l2);
        return combined;
    }

    private String serializeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
