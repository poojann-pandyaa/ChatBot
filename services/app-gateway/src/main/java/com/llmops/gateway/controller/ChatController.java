package com.llmops.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.client.RagEngineClient;
import com.llmops.gateway.entity.Conversation;
import com.llmops.gateway.model.ChatMessage;
import com.llmops.gateway.model.ChatRequest;
import com.llmops.gateway.model.UserChatRequest;
import com.llmops.gateway.repository.ConversationRepository;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ConversationRepository conversationRepository;
    private final RagEngineClient ragEngineClient;
    private final Counter requestCounter;
    private final PrometheusMeterRegistry prometheusRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public ChatController(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ConversationRepository conversationRepository,
            RagEngineClient ragEngineClient,
            MeterRegistry meterRegistry,
            PrometheusMeterRegistry prometheusRegistry) {
        this.redisTemplate = redisTemplate;
        this.conversationRepository = conversationRepository;
        this.ragEngineClient = ragEngineClient;
        this.prometheusRegistry = prometheusRegistry;
        this.requestCounter = Counter.builder("gateway_requests_total")
                .description("Total requests received by the gateway")
                .register(meterRegistry);
    }

    @PostMapping("/api/chat")
    public Mono<ResponseEntity<?>> chat(@RequestBody UserChatRequest request) {
        requestCounter.increment();
        String redisKey = "chat:" + request.conversationId();

        // 1. Fetch history from Redis BEFORE pushing the new prompt
        return redisTemplate.opsForList().range(redisKey, 0, -1)
                .collectList()
                .flatMap(history -> {
                    // Save conversation title to Postgres asynchronously
                    Mono.fromRunnable(() -> {
                        try {
                            if (!conversationRepository.existsById(request.conversationId())) {
                                String title = request.prompt().length() > 50
                                        ? request.prompt().substring(0, 50) + "..."
                                        : request.prompt();
                                conversationRepository.save(new Conversation(request.conversationId(), LocalDateTime.now(), title));
                            }
                        } catch (Exception e) {
                            log.warn("Database metadata save failed: {}", e.getMessage());
                        }
                    }).subscribeOn(Schedulers.boundedElastic()).subscribe();

                    // 2. Log user message to Redis history
                    Mono<Long> redisPushUser = redisTemplate.opsForList().rightPush(redisKey, "user:" + request.prompt());

                    // Format message history
                    List<ChatMessage> historyMsgs = history.stream().map(msg -> {
                        int colonIndex = msg.indexOf(':');
                        if (colonIndex != -1) {
                            return new ChatMessage(msg.substring(0, colonIndex), msg.substring(colonIndex + 1));
                        } else {
                            return new ChatMessage("user", msg);
                        }
                    }).toList();

                    // Keep last 10 messages for context
                    int start = Math.max(0, historyMsgs.size() - 10);
                    List<ChatMessage> history10 = historyMsgs.subList(start, historyMsgs.size());

                    ChatRequest clientRequest = new ChatRequest(
                            request.prompt(),
                            history10,
                            request.debug(),
                            request.stream()
                    );

                    if (request.stream()) {
                        StringBuilder accumulatedAnswer = new StringBuilder();

                        Flux<String> streamRes = ragEngineClient.streamChat(clientRequest)
                                .doOnNext(chunk -> {
                                    try {
                                        JsonNode node = mapper.readTree(chunk);
                                        if (node.has("type") && "token".equals(node.get("type").asText())) {
                                            accumulatedAnswer.append(node.get("data").asText());
                                        }
                                    } catch (Exception e) {
                                        // Ignore parsing errors for non-token stream frames (e.g. trace metadata)
                                    }
                                })
                                .doFinally(signalType -> {
                                    if (signalType == SignalType.ON_COMPLETE) {
                                        redisTemplate.opsForList().rightPush(redisKey, "assistant:" + accumulatedAnswer.toString())
                                                .subscribe();
                                    }
                                });

                        return redisPushUser.then(Mono.just(ResponseEntity.ok()
                                .contentType(MediaType.valueOf("application/x-ndjson"))
                                .body(streamRes)));
                    } else {
                        return redisPushUser.then(
                                ragEngineClient.chat(clientRequest)
                                        .flatMap(res -> {
                                            String answer = (String) res.getOrDefault("answer", "");
                                            return redisTemplate.opsForList().rightPush(redisKey, "assistant:" + answer)
                                                    .thenReturn(ResponseEntity.ok().body(res));
                                        })
                        );
                    }
                });
    }

    @GetMapping("/api/history/{conversationId}")
    public Mono<ResponseEntity<Object>> getHistory(@PathVariable String conversationId) {
        String redisKey = "chat:" + conversationId;
        return redisTemplate.opsForList().range(redisKey, 0, -1)
                .collectList()
                .map(messages -> {
                    List<Map<String, String>> formattedMessages = messages.stream().map(msg -> {
                        int colonIndex = msg.indexOf(':');
                        if (colonIndex != -1) {
                            return Map.of(
                                    "role", msg.substring(0, colonIndex),
                                    "content", msg.substring(colonIndex + 1)
                            );
                        } else {
                            return Map.of(
                                    "role", "user",
                                    "content", msg
                            );
                        }
                    }).toList();
                    Object body = Map.of(
                            "conversation_id", conversationId,
                            "messages", formattedMessages
                    );
                    return ResponseEntity.ok(body);
                })
                .onErrorResume(e -> {
                    Object errorBody = Map.of("error", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody));
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
                // Confirm database connectivity
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
