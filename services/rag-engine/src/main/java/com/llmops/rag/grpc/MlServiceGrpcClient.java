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
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for the ml-service.
 * <p>
 * Replaces the previous {@link com.llmops.rag.client.MlServiceClient} (WebClient-based)
 * with gRPC blocking stubs executed on a bounded-elastic scheduler so they integrate
 * cleanly with the reactive pipeline without blocking the event loop.
 * </p>
 * <p>
 * The same fallback logic from the WebClient version is preserved verbatim so
 * graceful degradation behaviour is unchanged.
 * </p>
 */
@Service("mlServiceGrpcClient")
public class MlServiceGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceGrpcClient.class);

    @Value("${ml-service.grpc.host:ml-service}")
    private String mlServiceHost;

    @Value("${ml-service.grpc.port:50051}")
    private int mlServicePort;

    private ManagedChannel channel;
    private MlServiceGrpc.MlServiceBlockingStub stub;

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(mlServiceHost, mlServicePort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        stub = MlServiceGrpc.newBlockingStub(channel);
        log.info("MlServiceGrpcClient connected to {}:{}", mlServiceHost, mlServicePort);
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
            return (Map<String, Object>) Map.of(
                    "intent", resp.getIntent(),
                    "reasoning_type", resp.getReasoningType(),
                    "entities", resp.getEntitiesList(),
                    "scope", resp.getScope(),
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

    // ─── Fallbacks (identical logic to MlServiceClient REST version) ─────────

    public Mono<Map<String, Object>> fallbackClassify(String query, Throwable t) {
        log.warn("gRPC Classification fallback triggered: {}", t.getMessage());
        String keywordType = keywordFallback(query);
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
        log.warn("gRPC Embedding fallback triggered: {}", t.getMessage());
        return Mono.just(Collections.nCopies(768, 0.0));
    }

    public Mono<List<Double>> fallbackRerank(String query, List<String> documents, Throwable t) {
        log.warn("gRPC Reranking fallback triggered: {}", t.getMessage());
        return Mono.just(Collections.nCopies(documents.size(), 0.0));
    }

    // ─── Keyword fallback helper (duplicated from MlServiceClient) ────────────

    private String keywordFallback(String query) {
        String q = query.toLowerCase().trim();
        List<String> strategicPatterns = List.of(
                " vs ", " versus ", " or ", "which is better",
                "which should i choose", "pros and cons of",
                "tradeoffs between", "compare and contrast"
        );
        for (String pattern : strategicPatterns) {
            if (q.contains(pattern)) return "strategic";
        }
        List<String> adaptiveExplain = List.of(
                "what is", "explain", "how does", "what are",
                "describe", "define", "difference between"
        );
        List<String> adaptiveUsage = List.of(
                "when should", "when to use", "and when", "and how",
                "how to use", "and why", "should i use", "when do i",
                "which is faster", "which is better", "how do i implement",
                "how to implement"
        );
        boolean hasExplain = adaptiveExplain.stream().anyMatch(q::contains);
        boolean hasUsage = adaptiveUsage.stream().anyMatch(q::contains);
        return (hasExplain && hasUsage) ? "adaptive" : "commonsense";
    }
}
