package com.llmops.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.grpc.RagEngineGrpcClient;
import com.llmops.gateway.model.ChatMessage;
import com.llmops.gateway.model.ChatRequest;
import com.llmops.gateway.model.UserChatRequest;
import com.llmops.gateway.repository.ConversationRepository;
import com.llmops.gateway.security.JwtAuthFilter;
import com.llmops.gateway.service.ConversationCommandService;
import com.llmops.gateway.service.ConversationListService;
import com.llmops.gateway.service.ConversationQueryService;
import com.llmops.gateway.sharding.ShardRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ConversationRepository conversationRepository;
    private final RagEngineGrpcClient ragEngineClient;
    private final ConversationCommandService conversationCommandService;
    private final ConversationQueryService conversationQueryService;
    private final ConversationListService conversationListService;
    private final ShardRouter shardRouter;
    private final Counter requestCounter;
    private final PrometheusMeterRegistry prometheusRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public ChatController(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ConversationRepository conversationRepository,
            RagEngineGrpcClient ragEngineClient,
            ConversationCommandService conversationCommandService,
            ConversationQueryService conversationQueryService,
            ConversationListService conversationListService,
            ShardRouter shardRouter,
            MeterRegistry meterRegistry,
            PrometheusMeterRegistry prometheusRegistry) {
        this.redisTemplate = redisTemplate;
        this.conversationRepository = conversationRepository;
        this.ragEngineClient = ragEngineClient;
        this.conversationCommandService = conversationCommandService;
        this.conversationQueryService = conversationQueryService;
        this.conversationListService = conversationListService;
        this.shardRouter = shardRouter;
        this.prometheusRegistry = prometheusRegistry;
        this.requestCounter = Counter.builder("gateway_requests_total")
                .description("Total requests received by the gateway")
                .register(meterRegistry);
    }

    /** Extracts the authenticated userId injected by JwtAuthFilter. Falls back to request field. */
    private String resolveUserId(ServerWebExchange exchange, String fallback) {
        String jwtUser = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        return jwtUser != null ? jwtUser : fallback;
    }

    @PostMapping("/api/chat")
    public Mono<ResponseEntity<?>> chat(@Valid @RequestBody UserChatRequest request, ServerWebExchange exchange) {
        requestCounter.increment();
        // Override userId from JWT — never trust the body's user_id field
        final String authenticatedUserId = resolveUserId(exchange, request.userId());

        // 1. Fetch history from CQRS read model (falls back to Postgres on cache miss)
        return conversationQueryService.getConversationHistory(request.conversationId())
                .flatMap(history -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> rawMessages = (List<Map<String, String>>) history.get("messages");
                    
                    List<ChatMessage> historyMsgs = rawMessages.stream()
                            .map(m -> new ChatMessage(m.get("role"), m.get("content")))
                            .toList();

                    // Keep last 10 messages for context window
                    int start = Math.max(0, historyMsgs.size() - 10);
                    List<ChatMessage> history10 = historyMsgs.subList(start, historyMsgs.size());

                    ChatRequest clientRequest = new ChatRequest(
                            request.prompt(),
                            history10,
                            request.debug(),
                            request.stream()
                    );

                    // Build a JWT-scoped request so the authenticated userId is used for persistence
                    UserChatRequest scopedRequest = new UserChatRequest(
                            request.prompt(), request.conversationId(),
                            request.debug(), request.stream(), authenticatedUserId);

                    if (request.stream()) {
                        return handleStreamResponse(scopedRequest, clientRequest);
                    } else {
                        return handleNonStreamResponse(scopedRequest, clientRequest);
                    }
                });
    }

    private Mono<ResponseEntity<?>> handleStreamResponse(
            UserChatRequest request, ChatRequest clientRequest) {

        StringBuilder accumulatedAnswer = new StringBuilder();
        String title = buildTitle(request.prompt());

        Flux<JsonNode> streamRes = ragEngineClient.streamChat(clientRequest)
                .map(chunk -> {
                    try {
                        JsonNode node = mapper.readTree(chunk);
                        if (node.has("type") && "token".equals(node.get("type").asText())) {
                            accumulatedAnswer.append(node.get("data").asText());
                        }
                        return node;
                    } catch (Exception e) {
                        com.fasterxml.jackson.databind.node.ObjectNode errNode = mapper.createObjectNode();
                        errNode.put("type", "error");
                        errNode.put("data", e.getMessage());
                        return (JsonNode) errNode;
                    }
                })
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        String finalAnswer = accumulatedAnswer.toString();
                        Mono.fromRunnable(() ->
                                conversationCommandService.saveConversationAndEvent(
                                        request.conversationId(), title, request.userId(),
                                        request.prompt(), finalAnswer, "unknown")
                        ).subscribeOn(Schedulers.boundedElastic()).subscribe();
                    }
                });

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .body(streamRes));
    }

    private Mono<ResponseEntity<?>> handleNonStreamResponse(
            UserChatRequest request, ChatRequest clientRequest) {

        String title = buildTitle(request.prompt());

        return ragEngineClient.chat(clientRequest)
                .flatMap(res -> {
                    String answer = (String) res.getOrDefault("answer", "");
                    String reasoningType = (String) res.getOrDefault("reasoning_type", "commonsense");
                    
                    return Mono.fromRunnable(() ->
                            conversationCommandService.saveConversationAndEvent(
                                    request.conversationId(), title, request.userId(),
                                    request.prompt(), answer, reasoningType)
                    ).subscribeOn(Schedulers.boundedElastic())
                    .thenReturn(ResponseEntity.ok().body(res));
                });
    }

    /** Truncates the prompt to produce a concise conversation title. */
    private String buildTitle(String prompt) {
        return prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt;
    }

    @GetMapping("/api/history/{conversationId}")
    public Mono<ResponseEntity<Object>> getHistory(
            @PathVariable String conversationId, ServerWebExchange exchange) {
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        return Mono.fromCallable(() -> conversationListService.isOwner(conversationId, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(isOwner -> {
                    if (!isOwner) {
                        log.warn("User {} attempted to access conversation {} without ownership", userId, conversationId);
                        Object forbidden = Map.of("error", "Forbidden", "message", "You do not own this conversation");
                        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden));
                    }
                    return conversationQueryService.getConversationHistory(conversationId)
                            .map(history -> ResponseEntity.ok((Object) history))
                            .onErrorResume(e -> {
                                Object errorBody = Map.of("error", e.getMessage());
                                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody));
                            });
                });
    }

    @PostMapping("/api/admin/rebuild-history")
    public Mono<ResponseEntity<Map<String, String>>> rebuildHistory() {
        Mono<Void> deleteSummaries = redisTemplate.keys("conversation:*:summary")
                .flatMap(key -> redisTemplate.opsForValue().delete(key))
                .then();
        
        Mono<Void> deleteChats = redisTemplate.keys("chat:*")
                .flatMap(key -> redisTemplate.opsForValue().delete(key))
                .then();

        return Mono.when(deleteSummaries, deleteChats)
                .then(Mono.just(ResponseEntity.ok(Map.of("message", "Cleared Redis history cache. It will rebuild from Postgres on next access."))));
    }

    // ─── Conversation Management ────────────────────────────────────────────────

    /**
     * Returns all conversations for a user, newest first.
     * X-User-Id header is optional; defaults to "default_user".
     */
    @GetMapping("/api/conversations")
    public Mono<ResponseEntity<List<Map<String, String>>>> listConversations(ServerWebExchange exchange) {
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        return Mono.fromCallable(() -> ResponseEntity.ok(conversationListService.listAllShards(userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Deletes a single conversation and all its messages.
     * Also evicts the Redis summary key.
     */
    @DeleteMapping("/api/conversation/{id}")
    public Mono<ResponseEntity<Void>> deleteConversation(
            @PathVariable String id, ServerWebExchange exchange) {
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        return Mono.fromCallable(() -> conversationListService.isOwner(id, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(isOwner -> {
                    if (!isOwner) {
                        log.warn("User {} attempted to delete conversation {} without ownership", userId, id);
                        return Mono.just(ResponseEntity.<Void>status(HttpStatus.FORBIDDEN).build());
                    }
                    return Mono.fromRunnable(() -> conversationListService.deleteConversationInShards(id))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(redisTemplate.delete("conversation:" + id + ":summary"))
                            .then(Mono.just(ResponseEntity.<Void>noContent().build()));
                });
    }

    /**
     * Deletes ALL conversations and messages for a user.
     * Also evicts all matching Redis summary keys.
     */
    @DeleteMapping("/api/conversations")
    public Mono<ResponseEntity<Void>> clearAllConversations(ServerWebExchange exchange) {
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        return Mono.fromRunnable(() -> conversationListService.deleteAllShards(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(redisTemplate.keys("conversation:*:summary")
                        .flatMap(key -> redisTemplate.delete(key))
                        .then())
                .then(Mono.just(ResponseEntity.<Void>noContent().build()));
    }

    /**
     * Renames a conversation (updates the title in Postgres and evicts the Redis key).
     */
    @PatchMapping("/api/conversation/{id}/rename")
    public Mono<ResponseEntity<Map<String, String>>> renameConversation(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        String newName = body.get("name");
        if (newName == null || newName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().<Map<String, String>>build());
        }
        return Mono.fromCallable(() -> conversationListService.isOwner(id, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(isOwner -> {
                    if (!isOwner) {
                        log.warn("User {} attempted to rename conversation {} without ownership", userId, id);
                        return Mono.just((ResponseEntity<Map<String, String>>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.FORBIDDEN).build());
                    }
                    return Mono.fromCallable(() -> {
                        boolean found = conversationListService.renameInShards(id, newName);
                        if (found) {
                            return ResponseEntity.ok(Map.of("id", id, "name", newName));
                        } else {
                            return (ResponseEntity<Map<String, String>>) (ResponseEntity<?>) ResponseEntity.notFound().build();
                        }
                    }).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(resp -> redisTemplate.delete("conversation:" + id + ":summary")
                            .thenReturn(resp));
                });
    }


    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of("status", "healthy"));
    }

    @GetMapping("/ready")
    public Mono<ResponseEntity<Map<String, Object>>> ready() {
        Mono<Boolean> redisPing = redisTemplate.getConnectionFactory().getReactiveConnection().ping()
                .map(pong -> "PONG".equalsIgnoreCase(pong))
                .onErrorReturn(false);

        Mono<Boolean> dbPing = Mono.fromCallable(() -> {
            try {
                return conversationRepository.count() >= 0;
            } catch (Exception e) {
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic()).onErrorReturn(false);

        return Mono.zip(redisPing, dbPing)
                .map(tuple -> {
                    boolean redisOk = tuple.getT1();
                    boolean dbOk = tuple.getT2();
                    Map<String, Object> body = Map.of(
                            "status", redisOk && dbOk ? "ready" : "degraded",
                            "redis_connected", redisOk,
                            "db_connected", dbOk
                    );
                    if (redisOk && dbOk) {
                        return ResponseEntity.ok(body);
                    } else {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
                    }
                });
    }

    @GetMapping(value = "/metrics", produces = "text/plain")
    public Mono<String> getMetrics() {
        return Mono.fromCallable(prometheusRegistry::scrape)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
