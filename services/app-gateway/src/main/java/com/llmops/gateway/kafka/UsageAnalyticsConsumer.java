package com.llmops.gateway.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer that listens to the {@code chat-completed} topic and
 * records usage analytics (query count per conversation/user).
 *
 * <p>Independent consumer group ({@code usage-analytics-group}) — killing this
 * service does NOT affect {@link CacheUpdateConsumer} or the chat request path.</p>
 */
@Service
public class UsageAnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageAnalyticsConsumer.class);

    private static final String USAGE_KEY_PREFIX = "usage:conversations:";

    private final RedisTemplate<String, String> redisTemplate;

    public UsageAnalyticsConsumer(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Increments the usage counter for the conversation in Redis.
     * Key format: {@code usage:conversations:<conversationId>}
     */
    @KafkaListener(
            topics = ChatEventProducer.TOPIC,
            groupId = "usage-analytics-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ChatCompletedEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("UsageAnalyticsConsumer received chat-completed event: conversationId={}, " +
                        "partition={}, offset={}",
                event.conversationId(), partition, offset);
        try {
            String key = USAGE_KEY_PREFIX + event.conversationId();
            Long count = redisTemplate.opsForValue().increment(key);
            log.info("Usage counter for conversation {} incremented to {}", event.conversationId(), count);
        } catch (Exception e) {
            log.error("Failed to record usage for conversation {}: {}",
                    event.conversationId(), e.getMessage());
            // Do NOT rethrow — this consumer failing should never affect the chat path
        }
    }
}
