package com.llmops.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service to handle queries (reads) for conversations.
 *
 * <p>Implements the CQRS pattern by reading directly and exclusively from the
 * denormalized read model in Redis, rather than query-parsing raw session history lists.</p>
 */
@Service
public class ConversationQueryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationQueryService.class);

    private static final String READ_MODEL_PREFIX = "conversation:";
    private static final String READ_MODEL_SUFFIX = ":summary";

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationQueryService(
            ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            ObjectMapper objectMapper) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the denormalized conversation history summary from the read model.
     *
     * @return a Map containing conversation_id and messages list, or an empty list if not found.
     */
    public Mono<Map<String, Object>> getConversationHistory(String conversationId) {
        String readModelKey = READ_MODEL_PREFIX + conversationId + READ_MODEL_SUFFIX;
        log.info("Querying CQRS read model for conversation: {}", conversationId);

        return reactiveRedisTemplate.opsForValue().get(readModelKey)
                .map(json -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> summary = objectMapper.readValue(json, Map.class);
                        return summary;
                    } catch (Exception e) {
                        log.error("Failed to parse read model JSON for conversation {}: {}", conversationId, e.getMessage());
                        return emptyHistory(conversationId);
                    }
                })
                .defaultIfEmpty(emptyHistory(conversationId));
    }

    private Map<String, Object> emptyHistory(String conversationId) {
        return Map.of(
                "conversation_id", conversationId,
                "messages", List.of()
        );
    }
}
