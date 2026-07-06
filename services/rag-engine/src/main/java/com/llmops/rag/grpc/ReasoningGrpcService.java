package com.llmops.rag.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.proto.ReasoningChatChunk;
import com.llmops.proto.ReasoningChatRequest;
import com.llmops.proto.ReasoningChatResponse;
import com.llmops.proto.ReasoningServiceGrpc;
import com.llmops.proto.SourceMetadata;
import com.llmops.rag.model.ChatMessage;
import com.llmops.rag.model.ChatResponse;
import com.llmops.rag.service.RouterService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * gRPC server implementation for the ReasoningService.
 * <p>
 * Wraps {@link RouterService} and bridges the reactive Flux/Mono pipeline to
 * the blocking gRPC {@link StreamObserver} API. The REST controller
 * {@link com.llmops.rag.controller.ReasoningController} remains in place for
 * backward compatibility during the transition.
 * </p>
 */
@Component
public class ReasoningGrpcService extends ReasoningServiceGrpc.ReasoningServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ReasoningGrpcService.class);
    private final RouterService routerService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReasoningGrpcService(RouterService routerService) {
        this.routerService = routerService;
    }

    // ─── Server-Streaming RPC ────────────────────────────────────────────────

    @Override
    public void streamChat(ReasoningChatRequest request,
                           StreamObserver<ReasoningChatChunk> responseObserver) {
        log.info("gRPC streamChat called for prompt: {}", truncate(request.getPrompt()));

        List<ChatMessage> history = toHistory(request);

        routerService.routeStreaming(request.getPrompt(), history, request.getIncludeTrace())
                .doOnNext(ndjsonLine -> {
                    // Each line is already a serialized JSON string like {"type":"token","data":"..."}
                    // Split out type and data to fill the proto chunk cleanly.
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = mapper.readValue(ndjsonLine.trim(), Map.class);
                        String type = (String) parsed.getOrDefault("type", "token");
                        Object data = parsed.getOrDefault("data", "");
                        String dataStr = data instanceof String
                                ? (String) data
                                : mapper.writeValueAsString(data);

                        ReasoningChatChunk chunk = ReasoningChatChunk.newBuilder()
                                .setType(type)
                                .setData(dataStr)
                                .build();
                        responseObserver.onNext(chunk);
                    } catch (Exception e) {
                        log.warn("Failed to parse stream chunk: {}", e.getMessage());
                        // Forward raw line as a token chunk so the stream isn't broken
                        responseObserver.onNext(ReasoningChatChunk.newBuilder()
                                .setType("token")
                                .setData(ndjsonLine.trim())
                                .build());
                    }
                })
                .doOnComplete(() -> {
                    responseObserver.onNext(ReasoningChatChunk.newBuilder()
                            .setType("done")
                            .setData("")
                            .build());
                    responseObserver.onCompleted();
                    log.info("gRPC streamChat completed.");
                })
                .doOnError(e -> {
                    log.error("gRPC streamChat error: {}", e.getMessage());
                    responseObserver.onError(
                            io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                })
                .subscribe();
    }

    // ─── Unary RPC ───────────────────────────────────────────────────────────

    @Override
    public void chat(ReasoningChatRequest request,
                     StreamObserver<ReasoningChatResponse> responseObserver) {
        log.info("gRPC chat (unary) called for prompt: {}", truncate(request.getPrompt()));

        List<ChatMessage> history = toHistory(request);

        routerService.routeNonStreaming(request.getPrompt(), history, request.getIncludeTrace())
                .doOnSuccess(chatResponse -> {
                    ReasoningChatResponse grpcResponse = toGrpcResponse(chatResponse, request.getIncludeTrace());
                    responseObserver.onNext(grpcResponse);
                    responseObserver.onCompleted();
                    log.info("gRPC chat completed.");
                })
                .doOnError(e -> {
                    log.error("gRPC chat error: {}", e.getMessage());
                    responseObserver.onError(
                            io.grpc.Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                })
                .subscribe();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<ChatMessage> toHistory(ReasoningChatRequest request) {
        return request.getHistoryList().stream()
                .map(e -> new ChatMessage(e.getRole(), e.getContent()))
                .toList();
    }

    private ReasoningChatResponse toGrpcResponse(ChatResponse chatResponse, boolean includeTrace) {
        ReasoningChatResponse.Builder builder = ReasoningChatResponse.newBuilder()
                .setAnswer(chatResponse.answer() != null ? chatResponse.answer() : "")
                .setReasoningType(chatResponse.reasoningType() != null ? chatResponse.reasoningType() : "commonsense");

        if (chatResponse.sources() != null) {
            chatResponse.sources().forEach(src -> builder.addSources(
                    SourceMetadata.newBuilder()
                            .setChunkId(src.chunkId())
                            .setScore(src.score())
                            .setQuestionId(src.questionId() != null ? src.questionId() : "")
                            .setIsAccepted(src.isAccepted())
                            .setDomain(src.domain() != null ? src.domain() : "")
                            .setChunkText(src.chunkText() != null ? src.chunkText() : "")
                            .build()
            ));
        }

        if (includeTrace && chatResponse.trace() != null) {
            try {
                builder.setTraceJson(mapper.writeValueAsString(chatResponse.trace()));
            } catch (Exception e) {
                log.warn("Failed to serialize trace: {}", e.getMessage());
            }
        }

        return builder.build();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
