package com.llmops.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CQRS Read Model Builder.
 *
 * <p>Listens to the {@code chat-completed} Kafka topic, denormalizes the event payload,
 * and updates the read-optimized store (Redis hash/value) representing the
 * conversation history.</p>
 */
@Service
public class ConversationReadModelConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationReadModelConsumer.class);

    private static final String READ_MODEL_PREFIX = "conversation:";
    private static final String READ_MODEL_SUFFIX = ":summary";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationReadModelConsumer(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Updates the denormalized read model in Redis whenever a chat completes.
     * The history is read exclusively from this read model on the query side.
     */
    @KafkaListener(
            topics = ChatEventProducer.TOPIC,
            groupId = "read-model-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ChatCompletedEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("ConversationReadModelConsumer received event: conversationId={}, partition={}, offset={}",
                event.conversationId(), partition, offset);

        String readModelKey = READ_MODEL_PREFIX + event.conversationId() + READ_MODEL_SUFFIX;

        try {
            List<Map<String, String>> messages = new ArrayList<>();

            // Fetch existing read model if present
            String existingJson = redisTemplate.opsForValue().get(readModelKey);
            if (existingJson != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingData = objectMapper.readValue(existingJson, Map.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> existingMsgs = (List<Map<String, String>>) existingData.get("messages");
                    if (existingMsgs != null) {
                        messages.addAll(existingMsgs);

                        // Idempotency check: if this exact (query, answer) pair is already present,
                        // this is a redelivery — skip to avoid duplicating history.
                        boolean alreadyApplied = messages.stream().anyMatch(m ->
                                "user".equals(m.get("role")) && event.query().equals(m.get("content")));
                        if (alreadyApplied) {
                            log.info("Idempotency guard: read model for conversation {} already contains this event (offset={}). Skipping.",
                                    event.conversationId(), offset);
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse existing read model for conversation {}: {}", event.conversationId(), e.getMessage());
                }
            }

            // Append new user and assistant message pair
            messages.add(Map.of("role", "user", "content", event.query()));
            messages.add(Map.of("role", "assistant", "content", event.answer()));

            // Build new read model
            Map<String, Object> readModel = Map.of(
                    "conversation_id", event.conversationId(),
                    "messages", messages
            );

            String updatedJson = objectMapper.writeValueAsString(readModel);
            redisTemplate.opsForValue().set(readModelKey, updatedJson);

            log.info("Successfully updated CQRS read model for conversation {} (total messages: {})",
                    event.conversationId(), messages.size());
        } catch (Exception e) {
            log.error("Failed to update read model for conversation {}: {}", event.conversationId(), e.getMessage());
        }
    }
}
