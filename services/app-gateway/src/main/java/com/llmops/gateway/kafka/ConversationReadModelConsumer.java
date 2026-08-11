package com.llmops.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.Message;
import com.llmops.gateway.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CQRS Read Model Builder.
 *
 * <p>Listens to the {@code chat-completed} Kafka topic, reads the canonical
 * history from Postgres, and updates the read-optimized store (Redis hash/value)
 * representing the conversation history.</p>
 */
@Service
public class ConversationReadModelConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationReadModelConsumer.class);

    private static final String READ_MODEL_PREFIX = "conversation:";
    private static final String READ_MODEL_SUFFIX = ":summary";

    private final RedisTemplate<String, String> redisTemplate;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public ConversationReadModelConsumer(
            RedisTemplate<String, String> redisTemplate,
            MessageRepository messageRepository,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Updates the denormalized read model in Redis whenever a chat completes.
     * The history is rebuilt cleanly from Postgres, ensuring Redis is always warm
     * and strictly consistent with the durable source of truth.
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
            // Read canonical history from Postgres
            List<Message> dbMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(event.conversationId());
            
            if (dbMessages.isEmpty()) {
                log.warn("No messages found in Postgres for conversation {}, skipping read model update", event.conversationId());
                return;
            }

            List<Map<String, String>> mappedMessages = dbMessages.stream()
                    .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                    .collect(Collectors.toList());

            // Build new read model
            Map<String, Object> readModel = Map.of(
                    "conversation_id", event.conversationId(),
                    "messages", mappedMessages
            );

            String updatedJson = objectMapper.writeValueAsString(readModel);
            redisTemplate.opsForValue().set(readModelKey, updatedJson);

            log.info("Successfully updated CQRS read model for conversation {} from Postgres (total messages: {})",
                    event.conversationId(), mappedMessages.size());
        } catch (Exception e) {
            log.error("Failed to update read model for conversation {}: {}", event.conversationId(), e.getMessage());
        }
    }
}
