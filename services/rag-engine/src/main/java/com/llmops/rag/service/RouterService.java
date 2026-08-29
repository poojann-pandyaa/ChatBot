package com.llmops.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.rag.grpc.MlServiceGrpcClient;
import com.llmops.rag.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.micrometer.core.instrument.MeterRegistry;
import com.llmops.rag.config.RagCacheProperties;

import com.llmops.rag.config.RedisCommandExecutor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final MlServiceGrpcClient mlServiceClient;
    private final FollowupDetector followupDetector;
    private final QualityGateService qualityGateService;
    private final ReasoningEngine reasoningEngine;
    private final GeneratorService generatorService;
    private final RerankerService rerankerService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final RagCacheProperties cacheProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RouterService(
            MlServiceGrpcClient mlServiceClient,
            FollowupDetector followupDetector,
            QualityGateService qualityGateService,
            ReasoningEngine reasoningEngine,
            GeneratorService generatorService,
            RerankerService rerankerService,
            ReactiveRedisTemplate<String, String> redisTemplate,
            MeterRegistry meterRegistry,
            RagCacheProperties cacheProperties) {
        this.mlServiceClient = mlServiceClient;
        this.followupDetector = followupDetector;
        this.qualityGateService = qualityGateService;
        this.reasoningEngine = reasoningEngine;
        this.generatorService = generatorService;
        this.rerankerService = rerankerService;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.cacheProperties = cacheProperties;
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

    private boolean isZeroOrEmptyVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()) return true;
        for (Double val : vector) {
            if (val != null && val != 0.0) return false;
        }
        return true;
    }

    public Mono<Map<String, Object>> checkCache(List<Double> qVector, String reasoningType) {
        if (isZeroOrEmptyVector(qVector)) {
            log.warn("Skipping semantic cache check for zero/empty vector.");
            return Mono.empty();
        }
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
                            double threshold = cacheProperties.getReadThresholds().getOrDefault(reasoningType != null ? reasoningType : "unknown", 0.05);
                            if (score <= threshold) {
                                String cachedReasoningType = properties.get("reasoning_type");
                                if (reasoningType != null && !reasoningType.equals("unknown") && cachedReasoningType != null && !reasoningType.equals(cachedReasoningType)) {
                                    log.info("Semantic Cache HIT but reasoning_type mismatch (expected: {}, cached: {}). Ignoring.", reasoningType, cachedReasoningType);
                                    return Mono.empty();
                                }
                                log.info("Semantic Cache HIT! Score: {} (threshold: {})", score, threshold);
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> sources = mapper.readValue(properties.get("sources"), List.class);
                                return Mono.just(Map.of(
                                        "answer", properties.get("answer"),
                                        "reasoning_type", cachedReasoningType,
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
        if (isZeroOrEmptyVector(qVector)) {
            log.warn("Skipping semantic cache save for zero/empty vector.");
            return Mono.empty();
        }
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
                
                long ttl = cacheProperties.getTtlSeconds().getOrDefault(reasoningType != null ? reasoningType : "unknown", 86400L);
                Mono<Boolean> expire = conn.keyCommands().expire(
                        ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8)),
                        java.time.Duration.ofSeconds(ttl)
                );
                return hset.then(expire);
            }).then()
            .retry(3)
            .doOnSuccess(v -> log.info("Saved to semantic cache: {}", key))
            .onErrorResume(e -> {
                log.warn("Async cache save failed after retries: {}", e.getMessage());
                meterRegistry.counter("rag_cache_writes_failed_total").increment();
                return Mono.empty();
            });
        } catch (Exception e) {
            log.error("Failed to hash cache query: {}", e.getMessage());
            meterRegistry.counter("rag_cache_writes_failed_total").increment();
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

            Mono<List<Double>> embedMono = mlServiceClient.embed(rewrittenPrompt);
            Mono<Map<String, Object>> classifyMono = mlServiceClient.classify(rewrittenPrompt);

            return Mono.zip(embedMono, classifyMono).flatMap(tuple -> {
                List<Double> qVector = tuple.getT1();
                Map<String, Object> classification = tuple.getT2();

                trace.setClassification(classification);
                String reasoningType = (String) classification.getOrDefault("reasoning_type", "commonsense");

                return checkCache(qVector, reasoningType)
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
                        .switchIfEmpty(Mono.defer(() -> {
                            String ambiguity = (String) classification.getOrDefault("ambiguity", "low");
                            if ("high".equals(ambiguity)) {
                                trace.getRouterDecisions().put("path_taken", "clarification_requested");
                                String clarifyingQuestion = "Could you please clarify or provide more context for \"" + rewrittenPrompt + "\"? I need a bit more detail to give a good answer.";
                                return Mono.just(new ChatResponse(
                                        clarifyingQuestion,
                                        reasoningType,
                                        List.of(),
                                        includeTrace ? trace.toMap() : null
                                ));
                            }
                            return reasoningEngine.execute(trace)
                                .flatMap(engineTrace -> {
                                    QualityGateService.QualityGateResult qgResult = qualityGateService.evaluate(engineTrace.getRerankedFinal(), reasoningType);
                                    trace.getRouterDecisions().put("quality_score", qgResult.score());

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
                                                                QualityGateService.QualityGateResult finalQg = qualityGateService.evaluate(rerankedCombined, reasoningType);
                                                                if (!finalQg.passed()) {
                                                                    trace.getRouterDecisions().put("quality_gate_failed_twice", true);
                                                                    log.warn("Quality gate FAILED TWICE. Using graceful fallback.");
                                                                    trace.setFinalAnswer("I couldn't find a confident answer in my knowledge base. Could you provide more context or rephrase your question?");
                                                                    trace.setRerankedFinal(rerankedCombined);
                                                                } else {
                                                                    trace.setRerankedFinal(rerankedCombined);
                                                                }
                                                                return trace;
                                                            });
                                                });
                                    } else {
                                        trace.getRouterDecisions().put("path_taken", "commonsense".equals(reasoningType) ? "simple_rag" : "multi_step_rag");
                                        pipelineMono = Mono.just(trace);
                                    }

                                    return pipelineMono.flatMap(finalTrace -> {
                                        if (finalTrace.getFinalAnswer() != null && finalTrace.getRouterDecisions().containsKey("quality_gate_failed_twice")) {
                                            List<SourceMetadata> sources = formatSources(finalTrace.getRerankedFinal());
                                            return Mono.just(new ChatResponse(
                                                    finalTrace.getFinalAnswer(),
                                                    reasoningType,
                                                    sources,
                                                    includeTrace ? finalTrace.toMap() : null
                                            ));
                                        }
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
                        }));
            });
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

            Mono<List<Double>> embedMono = mlServiceClient.embed(rewrittenPrompt);
            Mono<Map<String, Object>> classifyMono = mlServiceClient.classify(rewrittenPrompt);

            return Mono.zip(embedMono, classifyMono).flatMapMany(tuple -> {
                List<Double> qVector = tuple.getT1();
                Map<String, Object> classification = tuple.getT2();

                trace.setClassification(classification);
                String reasoningType = (String) classification.getOrDefault("reasoning_type", "commonsense");

                return checkCache(qVector, reasoningType)
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
                        .switchIfEmpty(Flux.defer(() -> {
                            String ambiguity = (String) classification.getOrDefault("ambiguity", "low");
                            if ("high".equals(ambiguity)) {
                                trace.getRouterDecisions().put("path_taken", "clarification_requested");
                                String clarifyingQuestion = "Could you please clarify or provide more context for \"" + rewrittenPrompt + "\"? I need a bit more detail to give a good answer.";
                                
                                Map<String, Object> tracePayload = Map.of(
                                        "reasoning_type", reasoningType,
                                        "sub_questions", classification.getOrDefault("sub_questions", List.of()),
                                        "sources", List.of(),
                                        "router_decisions", trace.getRouterDecisions()
                                );
                                String traceLine = serializeJson(Map.of("type", "trace", "data", tracePayload)) + "\n";
                                String tokenLine = serializeJson(Map.of("type", "token", "data", clarifyingQuestion)) + "\n";
                                return Flux.just(traceLine, tokenLine);
                            }
                            return reasoningEngine.execute(trace)
                                .flatMapMany(engineTrace -> {
                                    QualityGateService.QualityGateResult qgResult = qualityGateService.evaluate(engineTrace.getRerankedFinal(), reasoningType);
                                    trace.getRouterDecisions().put("quality_score", qgResult.score());

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
                                                                QualityGateService.QualityGateResult finalQg = qualityGateService.evaluate(rerankedCombined, reasoningType);
                                                                if (!finalQg.passed()) {
                                                                    trace.getRouterDecisions().put("quality_gate_failed_twice", true);
                                                                    log.warn("Quality gate FAILED TWICE (streaming). Using graceful fallback.");
                                                                    trace.setFinalAnswer("I couldn't find a confident answer in my knowledge base. Could you provide more context or rephrase your question?");
                                                                    trace.setRerankedFinal(rerankedCombined);
                                                                } else {
                                                                    trace.setRerankedFinal(rerankedCombined);
                                                                }
                                                                return trace;
                                                            });
                                                });
                                    } else {
                                        trace.getRouterDecisions().put("path_taken", "commonsense".equals(reasoningType) ? "simple_rag" : "multi_step_rag");
                                        pipelineMono = Mono.just(trace);
                                    }

                                    return pipelineMono.flatMapMany(finalTrace -> {
                                        List<SourceMetadata> sources = formatSources(finalTrace.getRerankedFinal());

                                        Map<String, Object> tracePayload = Map.of(
                                                "reasoning_type", reasoningType,
                                                "sub_questions", classification.getOrDefault("sub_questions", List.of()),
                                                "sources", sources,
                                                "router_decisions", finalTrace.getRouterDecisions()
                                        );
                                        String traceLine = serializeJson(Map.of("type", "trace", "data", tracePayload)) + "\n";

                                        if (finalTrace.getFinalAnswer() != null && finalTrace.getRouterDecisions().containsKey("quality_gate_failed_twice")) {
                                            String tokenLine = serializeJson(Map.of("type", "token", "data", finalTrace.getFinalAnswer())) + "\n";
                                            return Flux.just(traceLine, tokenLine);
                                        }

                                        String promptForGeneration = generatorService.buildPrompt(
                                                finalTrace.getQuery(),
                                                finalTrace.getRerankedFinal(),
                                                reasoningType,
                                                (List<String>) classification.get("sub_questions"),
                                                history
                                        );
                                        finalTrace.setGenerationPrompt(promptForGeneration);

                                        StringBuilder accumulatedAnswer = new StringBuilder();
                                        Flux<String> tokensFlux = generatorService.generateStream(promptForGeneration)
                                                .map(token -> {
                                                    accumulatedAnswer.append(token);
                                                    return serializeJson(Map.of("type", "token", "data", token)) + "\n";
                                                })
                                                .doOnComplete(() -> saveToCache(rewrittenPrompt, qVector, accumulatedAnswer.toString(), reasoningType, sources).subscribe())
                                                .onErrorResume(e -> Flux.just(serializeJson(Map.of("type", "error", "data", e.getMessage())) + "\n"));

                                        return Flux.concat(Flux.just(traceLine), tokensFlux);
                                    });
                                });
                        }));
            });
        });
    }

    List<SourceMetadata> formatSources(List<Map<String, Object>> rerankedFinal) {
        List<SourceMetadata> list = new ArrayList<>();
        if (rerankedFinal != null) {
            for (Map<String, Object> c : rerankedFinal) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) c.get("metadata");
                if (meta == null) {
                    meta = java.util.Map.of();
                }
                
                Number scoreNum = (Number) c.getOrDefault("final_score", c.getOrDefault("score", 0.0));
                double score = scoreNum != null ? scoreNum.doubleValue() : 0.0;
                
                Number chunkIdNum = (Number) c.get("chunk_id");
                int chunkId = chunkIdNum != null ? chunkIdNum.intValue() : 0;
                
                Object qIdObj = meta.get("question_id");
                String questionId = qIdObj != null ? qIdObj.toString() : "";
                
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
