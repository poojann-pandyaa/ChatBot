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

    public ConversationQueryService(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            MessageRepository messageRepository,
            ObjectMapper objectMapper) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the denormalized conversation history summary from the read model.
     * Falls back to Postgres on cache miss.
     */
    public Mono<Map<String, Object>> getConversationHistory(String conversationId) {
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
                .switchIfEmpty(Mono.defer(() -> rebuildHistoryFromPostgres(conversationId, readModelKey)))
                .defaultIfEmpty(emptyHistory(conversationId));
    }

    private Mono<Map<String, Object>> rebuildHistoryFromPostgres(String conversationId, String readModelKey) {
        log.info("Cache miss for conversation {}. Rebuilding from Postgres...", conversationId);
        return Mono.fromCallable(() -> {
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
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(history -> {
            try {
                // Cache warm
                String json = objectMapper.writeValueAsString(history);
                return reactiveRedisTemplate.opsForValue().set(readModelKey, json)
                        .thenReturn(history);
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
