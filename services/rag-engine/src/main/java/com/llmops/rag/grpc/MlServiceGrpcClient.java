package com.llmops.rag.grpc;

import com.llmops.proto.ClassifyRequest;
import com.llmops.proto.ClassifyResponse;
import com.llmops.proto.EmbedRequest;
import com.llmops.proto.EmbedResponse;
import com.llmops.proto.MlServiceGrpc;
import com.llmops.proto.RerankRequest;
import com.llmops.proto.RerankResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.File;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * gRPC client for the ml-service.
 *
 * <p>Provides three operations — classify, embed, and rerank — using blocking stubs
 * offloaded to a boundedElastic scheduler so they do not block the reactive event loop.</p>
 *
 * <p>Each method has a circuit breaker + retry, with a keyword-heuristic fallback
 * for classify and zero-vector/zero-score fallbacks for embed/rerank.</p>
 */
@Service("mlServiceGrpcClient")
public class MlServiceGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceGrpcClient.class);

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public MlServiceGrpcClient(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Value("${ml-service.grpc.host:ml-service}")
    private String mlServiceHost;

    @Value("${ml-service.grpc.port:50051}")
    private int mlServicePort;

    private ManagedChannel channel;
    private MlServiceGrpc.MlServiceBlockingStub stub;

    private List<String> strategicPatterns = new ArrayList<>();
    private List<String[]> strategicNounPairs = new ArrayList<>();
    private List<String> adaptiveExplain = new ArrayList<>();
    private List<String> adaptiveUsage = new ArrayList<>();
    private Pattern orPattern = Pattern.compile("\\b\\w+\\s+or\\s+\\w+\\b");

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(mlServiceHost, mlServicePort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        stub = MlServiceGrpc.newBlockingStub(channel);
        log.info("MlServiceGrpcClient connected to {}:{}", mlServiceHost, mlServicePort);

        try {
            File configFile = new File("/configs/classifier_rules.json");
            if (!configFile.exists()) {
                configFile = new File("../../configs/classifier_rules.json");
            }
            if (!configFile.exists()) {
                configFile = new File("configs/classifier_rules.json");
            }
            if (configFile.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(configFile);
                root.get("strategic_vs_patterns").forEach(n -> strategicPatterns.add(n.asText()));
                root.get("strategic_noun_pairs").forEach(n -> strategicNounPairs.add(new String[]{n.get(0).asText(), n.get(1).asText()}));
                root.get("adaptive_explain_signals").forEach(n -> adaptiveExplain.add(n.asText()));
                root.get("adaptive_usage_signals").forEach(n -> adaptiveUsage.add(n.asText()));
                log.info("Loaded classifier rules from {}", configFile.getAbsolutePath());
            } else {
                log.error("Could not find classifier_rules.json");
            }
        } catch (Exception e) {
            log.error("Failed to load classifier rules", e);
        }
    }

    public ManagedChannel getChannel() {
        return channel;
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("MlService gRPC channel shut down.");
        }
    }

    // ─── Classify ────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackClassify")
    @Retry(name = "mlServiceClient")
    public Mono<Map<String, Object>> classify(String query) {
        return Mono.fromCallable(() -> {
            ClassifyResponse resp = stub.classify(
                    ClassifyRequest.newBuilder().setQuery(query).build());
            
            String reasoningType = resp.getReasoningType();
            String scope = resp.getScope();
            
            // If the ML model defaulted to commonsense, run our Regex heuristics as an override
            if ("commonsense".equals(reasoningType)) {
                reasoningType = keywordFallback(query);
                if (!"commonsense".equals(reasoningType)) {
                    scope = "multi_topic";
                }
            }
            
            return (Map<String, Object>) Map.of(
                    "intent", resp.getIntent(),
                    "reasoning_type", reasoningType,
                    "entities", resp.getEntitiesList(),
                    "scope", scope,
                    "ambiguity", resp.getAmbiguity(),
                    "sub_questions", resp.getSubQuestionsList()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Embed ────────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackEmbed")
    @Retry(name = "mlServiceClient")
    public Mono<List<Double>> embed(String text) {
        return Mono.fromCallable(() -> {
            EmbedResponse resp = stub.embed(
                    EmbedRequest.newBuilder().setText(text).build());
                    
            if (resp.getEmbeddingBytes() != null && !resp.getEmbeddingBytes().isEmpty()) {
                byte[] bytes = resp.getEmbeddingBytes().toByteArray();
                java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(bytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer();
                List<Double> result = new java.util.ArrayList<>(fb.limit());
                for (int i = 0; i < fb.limit(); i++) {
                    result.add((double) fb.get(i));
                }
                return result;
            }

            return resp.getEmbeddingList().stream()
                    .map(Float::doubleValue)
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Rerank ───────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "mlServiceClient", fallbackMethod = "fallbackRerank")
    @Retry(name = "mlServiceClient")
    public Mono<List<Double>> rerank(String query, List<String> documents) {
        return Mono.fromCallable(() -> {
            RerankResponse resp = stub.rerank(
                    RerankRequest.newBuilder()
                            .setQuery(query)
                            .addAllDocuments(documents)
                            .build());
            return resp.getScoresList().stream()
                    .map(Float::doubleValue)
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Fallbacks ────────────────────────────────────────────────────────────

    public Mono<Map<String, Object>> fallbackClassify(String query, Throwable t) {
        log.warn("Classification fallback triggered: {}", t.getMessage());
        String keywordType = keywordFallback(query);
        
        meterRegistry.counter("ml_service_classify_fallback_total", "reasoning_type", keywordType).increment();
        
        return Mono.just(Map.of(
                "intent", "factual",
                "reasoning_type", keywordType,
                "entities", List.of(),
                "scope", "commonsense".equals(keywordType) ? "single_topic" : "multi_topic",
                "ambiguity", "low",
                "sub_questions", List.of(query)
        ));
    }

    public Mono<List<Double>> fallbackEmbed(String text, Throwable t) {
        log.warn("Embedding fallback triggered: {}", t.getMessage());
        return Mono.just(Collections.nCopies(768, 0.0));
    }

    public Mono<List<Double>> fallbackRerank(String query, List<String> documents, Throwable t) {
        log.warn("Reranking fallback triggered: {}", t.getMessage());
        return Mono.just(Collections.nCopies(documents.size(), 0.0));
    }

    // ─── Keyword fallback helper ──────────────────────────────────────────────

    private String keywordFallback(String query) {
        String q = query.toLowerCase().trim();
        for (String[] pair : strategicNounPairs) {
            if (q.contains(pair[0]) && q.contains(pair[1])) {
                return "strategic";
            }
        }
        for (String pattern : strategicPatterns) {
            if (q.contains(pattern)) {
                if (pattern.equals(" or ")) {
                    Matcher m = orPattern.matcher(q);
                    if (m.find()) return "strategic";
                } else {
                    return "strategic";
                }
            }
        }
        boolean hasExplain = adaptiveExplain.stream().anyMatch(q::contains);
        boolean hasUsage = adaptiveUsage.stream().anyMatch(q::contains);
        return (hasExplain && hasUsage) ? "adaptive" : "commonsense";
    }
}
