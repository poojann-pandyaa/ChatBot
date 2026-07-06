package com.llmops.gateway.grpc;

import com.llmops.proto.ChatHistoryEntry;
import com.llmops.proto.ReasoningChatChunk;
import com.llmops.proto.ReasoningChatRequest;
import com.llmops.proto.ReasoningChatResponse;
import com.llmops.proto.ReasoningServiceGrpc;
import com.llmops.gateway.model.ChatMessage;
import com.llmops.gateway.model.ChatRequest;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for the rag-engine ReasoningService.
 * <p>
 * Replaces the WebClient-based {@link com.llmops.gateway.client.RagEngineClient}
 * with gRPC server-streaming and unary calls. Blocking iterator for the
 * server-streaming RPC is executed on a boundedElastic scheduler and converted
 * to a reactive {@link Flux}.
 * </p>
 */
@Service
public class RagEngineGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(RagEngineGrpcClient.class);

    @Value("${rag-engine.grpc.host:rag-engine}")
    private String ragEngineHost;

    @Value("${rag-engine.grpc.port:9091}")
    private int ragEnginePort;

    private ManagedChannel channel;
    private ReasoningServiceGrpc.ReasoningServiceBlockingStub blockingStub;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(ragEngineHost, ragEnginePort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
        blockingStub = ReasoningServiceGrpc.newBlockingStub(channel);
        log.info("RagEngineGrpcClient connected to {}:{}", ragEngineHost, ragEnginePort);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("RAG Engine gRPC channel shut down.");
        }
    }

    // ─── Server-streaming (stream=true) ─────────────────────────────────────

    /**
     * Calls the server-streaming StreamChat RPC and re-emits each chunk as a
     * JSON string equivalent to the original NDJSON frames:
     * <pre>{"type":"token","data":"..."}</pre>
     * <pre>{"type":"trace","data":{...}}</pre>
     */
    @CircuitBreaker(name = "ragEngineClient", fallbackMethod = "fallbackStreamChat")
    @Retry(name = "ragEngineClient")
    public Flux<String> streamChat(ChatRequest request) {
        ReasoningChatRequest grpcRequest = toGrpcRequest(request);

        return Flux.create(sink -> {
            // Run blocking iterator on a separate thread
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    Iterator<ReasoningChatChunk> iter = blockingStub.streamChat(grpcRequest);
                    while (iter.hasNext()) {
                        ReasoningChatChunk chunk = iter.next();
                        if ("done".equals(chunk.getType())) {
                            sink.complete();
                            return;
                        }
                        // Reconstruct the NDJSON line cleanly using Jackson to handle control chars & escaping
                        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
                        node.put("type", chunk.getType());
                        String rawData = chunk.getData();
                        if (rawData != null && (rawData.trim().startsWith("{") || rawData.trim().startsWith("["))) {
                            try {
                                node.set("data", mapper.readTree(rawData));
                            } catch (Exception ex) {
                                node.put("data", rawData);
                            }
                        } else {
                            node.put("data", rawData);
                        }
                        String json = mapper.writeValueAsString(node) + "\n";
                        sink.next(json);
                    }
                    sink.complete();
                } catch (Exception e) {
                    sink.error(e);
                }
            });
        });
    }

    // ─── Unary (stream=false) ────────────────────────────────────────────────

    @CircuitBreaker(name = "ragEngineClient", fallbackMethod = "fallbackChat")
    @Retry(name = "ragEngineClient")
    public Mono<Map<String, Object>> chat(ChatRequest request) {
        ReasoningChatRequest grpcRequest = toGrpcRequest(request);

        return Mono.fromCallable(() -> {
            ReasoningChatResponse resp = blockingStub.chat(grpcRequest);
            List<Map<String, Object>> sources = resp.getSourcesList().stream()
                    .map(s -> Map.<String, Object>of(
                            "chunk_id", s.getChunkId(),
                            "score", s.getScore(),
                            "question_id", s.getQuestionId() != null ? s.getQuestionId() : "",
                            "is_accepted", s.getIsAccepted(),
                            "domain", s.getDomain() != null ? s.getDomain() : "",
                            "chunk_text", s.getChunkText() != null ? s.getChunkText() : ""
                    ))
                    .toList();
            return Map.<String, Object>of(
                    "answer", resp.getAnswer(),
                    "reasoning_type", resp.getReasoningType(),
                    "sources", sources
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Fallbacks (identical semantics to the REST-based RagEngineClient) ───

    public Flux<String> fallbackStreamChat(ChatRequest request, Throwable t) {
        log.warn("gRPC streamChat fallback triggered: {}", t.getMessage());
        return Flux.just("{\"type\":\"token\",\"data\":\"[Gateway Fallback] RAG Engine is temporarily unavailable. Error: "
                + t.getMessage() + "\"}\n");
    }

    public Mono<Map<String, Object>> fallbackChat(ChatRequest request, Throwable t) {
        log.warn("gRPC chat fallback triggered: {}", t.getMessage());
        return Mono.just(Map.of(
                "answer", "[Gateway Fallback] RAG Engine is temporarily unavailable. Error: " + t.getMessage(),
                "reasoning_type", "commonsense",
                "sources", List.of()
        ));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ReasoningChatRequest toGrpcRequest(ChatRequest request) {
        ReasoningChatRequest.Builder builder = ReasoningChatRequest.newBuilder()
                .setPrompt(request.prompt())
                .setStream(request.stream())
                .setIncludeTrace(request.includeTrace());

        if (request.history() != null) {
            request.history().forEach(msg -> builder.addHistory(
                    ChatHistoryEntry.newBuilder()
                            .setRole(msg.role())
                            .setContent(msg.content())
                            .build()
            ));
        }
        return builder.build();
    }

    /**
     * Wraps a value as a JSON string literal if it doesn't look like JSON,
     * otherwise passes it through (for trace objects serialized by rag-engine).
     */
    private String jsonValue(String data) {
        if (data == null || data.isEmpty()) return "\"\"";
        String trimmed = data.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        // Escape quotes for string literal
        return "\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
