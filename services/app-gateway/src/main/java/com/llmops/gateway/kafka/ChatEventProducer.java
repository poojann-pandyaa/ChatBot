package com.llmops.gateway.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes {@link ChatCompletedEvent} to the {@code chat-completed} Kafka topic.
 *
 * <p>Called by the Outbox poller (Phase 5) — NOT called directly from the
 * controller after Phase 5 is complete. During Phase 4 it is called directly
 * as a transitional step, then replaced by the Outbox flow in Phase 5.</p>
 */
@Service
public class ChatEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ChatEventProducer.class);
    static final String TOPIC = "chat-completed";

    private final KafkaTemplate<String, ChatCompletedEvent> kafkaTemplate;

    public ChatEventProducer(KafkaTemplate<String, ChatCompletedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a {@link ChatCompletedEvent} to the {@code chat-completed} topic.
     * The message key is {@code conversationId} to ensure ordering within a conversation.
     */
    public void publish(ChatCompletedEvent event) {
        kafkaTemplate.send(TOPIC, event.conversationId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish chat-completed event for conversation {}: {}",
                                event.conversationId(), ex.getMessage());
                    } else {
                        log.info("Published chat-completed event for conversation {} at offset {}",
                                event.conversationId(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
