package com.llmops.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.Message;
import com.llmops.gateway.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service to handle queries (reads) for conversations.
 *
 * <p>Implements the CQRS pattern by reading directly and exclusively from the
 * denormalized read model in Redis. If Redis misses, it falls back to Postgres
 * to rebuild the cache.</p>
 */
@Service
public class ConversationQueryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationQueryService.class);

    private static final String READ_MODEL_PREFIX = "conversation:";
    private static final String READ_MODEL_SUFFIX = ":summary";

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final com.llmops.gateway.sharding.ShardRouter shardRouter;

    public ConversationQueryService(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            MessageRepository messageRepository,
            ObjectMapper objectMapper,
            com.llmops.gateway.sharding.ShardRouter shardRouter) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.shardRouter = shardRouter;
    }

    /**
     * Retrieves the denormalized conversation history summary from the read model.
     * Falls back to Postgres on cache miss OR on Redis connection failure.
     *
     * <p>Redis is a soft dependency: any connection-level error (e.g. SocketException,
     * RedisConnectionException) is caught here and treated as a cache miss so that
     * the synchronous Postgres fallback path is used instead of propagating a 500.</p>
     */
    public Mono<Map<String, Object>> getConversationHistory(String conversationId, String userId) {
        String readModelKey = READ_MODEL_PREFIX + conversationId + READ_MODEL_SUFFIX;
        log.info("Querying CQRS read model for conversation: {}", conversationId);

        return reactiveRedisTemplate.opsForValue().get(readModelKey)
                .flatMap(json -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> summary = objectMapper.readValue(json, Map.class);
                        return Mono.just(summary);
                    } catch (Exception e) {
                        log.error("Failed to parse read model JSON for conversation {}: {}", conversationId, e.getMessage());
                        return Mono.empty(); // Treat parse error as cache miss
                    }
                })
                // ── Redis soft-dependency: connection errors fall through to Postgres ──
                .onErrorResume(ex -> {
                    log.warn("Redis unavailable for conversation {} ({}): falling back to Postgres.",
                            conversationId, ex.getMessage());
                    return rebuildHistoryFromPostgres(conversationId, readModelKey, userId);
                })
                .switchIfEmpty(Mono.defer(() -> rebuildHistoryFromPostgres(conversationId, readModelKey, userId)))
                .defaultIfEmpty(emptyHistory(conversationId));
    }

    private Mono<Map<String, Object>> rebuildHistoryFromPostgres(String conversationId, String readModelKey, String userId) {
        log.info("Cache miss for conversation {}. Rebuilding from Postgres...", conversationId);
        return Mono.fromCallable(() -> {
            shardRouter.bindReadRoute(userId);
            try {
                List<Message> dbMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
                if (dbMessages.isEmpty()) {
                    return emptyHistory(conversationId);
                }
                List<Map<String, String>> mappedMessages = dbMessages.stream()
                        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                        .collect(Collectors.toList());
                
                return Map.of(
                        "conversation_id", conversationId,
                        "messages", mappedMessages
                );
            } catch (Exception ex) {
                log.error("Postgres unavailable or query failed for conversation {}: {}", conversationId, ex.getMessage());
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, 
                        "Service temporarily unavailable (Database failure)"
                );
            } finally {
                com.llmops.gateway.sharding.DataSourceContextHolder.clear();
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(history -> {
            try {
                // Cache warm — best-effort, must not crash if Redis is down
                String json = objectMapper.writeValueAsString(history);
                return reactiveRedisTemplate.opsForValue().set(readModelKey, json)
                        .thenReturn(history)
                        .onErrorResume(ex -> {
                            log.warn("Redis cache warm failed for conversation {} ({}): returning Postgres data directly.",
                                    conversationId, ex.getMessage());
                            return Mono.just(history);
                        });
            } catch (Exception e) {
                log.error("Failed to serialize rebuilt history for cache warm {}: {}", conversationId, e.getMessage());
                return Mono.just(history); // still return history even if cache warm fails
            }
        });
    }

    private Map<String, Object> emptyHistory(String conversationId) {
        return Map.of(
                "conversation_id", conversationId,
                "messages", List.of()
        );
    }
}
