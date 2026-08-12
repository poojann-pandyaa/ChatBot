package com.llmops.gateway.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** Max time to wait for broker ack before treating the publish as failed. */
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, ChatCompletedEvent> kafkaTemplate;

    public ChatEventProducer(KafkaTemplate<String, ChatCompletedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a {@link ChatCompletedEvent} synchronously (blocking until broker ack).
     *
     * <p><strong>Used by {@link com.llmops.gateway.outbox.OutboxPoller}</strong>.
     * The poller must only mark {@code published=true} <em>after</em> Kafka has
     * confirmed delivery — calling this method ensures that guarantee.
     * Throws {@link RuntimeException} if Kafka is unreachable or times out so the
     * poller's catch block leaves the row {@code published=false} for retry.</p>
     */
    public void publishSync(ChatCompletedEvent event) {
        try {
            SendResult<String, ChatCompletedEvent> result = kafkaTemplate
                    .send(TOPIC, event.conversationId(), event)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Published chat-completed event for conversation {} at offset {}",
                    event.conversationId(), result.getRecordMetadata().offset());
        } catch (TimeoutException e) {
            throw new RuntimeException("Kafka publish timed out after " + PUBLISH_TIMEOUT_SECONDS
                    + "s for conversation " + event.conversationId(), e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Kafka publish failed for conversation "
                    + event.conversationId() + ": " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Kafka publish interrupted for conversation " + event.conversationId(), e);
        }
    }

    /**
     * Fire-and-forget publish. Does NOT block on broker ack.
     * Do NOT use this from the OutboxPoller — use {@link #publishSync} instead.
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
