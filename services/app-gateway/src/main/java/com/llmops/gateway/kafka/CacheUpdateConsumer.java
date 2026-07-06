package com.llmops.gateway.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer that listens to the {@code chat-completed} topic and
 * updates the Redis semantic cache with a usage record for the completed chat.
 *
 * <p>Runs in a separate consumer group from {@link UsageAnalyticsConsumer} so
 * that killing one does not affect the other. Both consumers subscribe to the
 * same topic but process independently — demonstrating event fan-out decoupling.</p>
 */
@Service
public class CacheUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CacheUpdateConsumer.class);

    /**
     * Triggered for every message on the {@code chat-completed} topic.
     *
     * <p>In Phase 4 this logs the event and flags the semantic cache
     * for a potential update. The actual RediSearch write is already handled
     * by {@code RouterService.saveToCache} in rag-engine; this consumer
     * provides the asynchronous, decoupled signal path for future extension
     * (e.g. cache invalidation across replicas).</p>
     */
    @KafkaListener(
            topics = ChatEventProducer.TOPIC,
            groupId = "cache-update-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ChatCompletedEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("CacheUpdateConsumer received chat-completed event: conversationId={}, " +
                        "reasoningType={}, partition={}, offset={}",
                event.conversationId(), event.reasoningType(), partition, offset);
        // Semantic cache write is handled by rag-engine's RouterService.saveToCache()
        // during the streaming pipeline. This consumer is the extension point for:
        //   - Cross-replica cache invalidation signals
        //   - Cache TTL refresh triggers
        //   - Metrics emission for cache coverage
        log.debug("Cache update signal processed for conversation {}", event.conversationId());
    }
}
