package com.llmops.gateway.kafka;

import java.time.Instant;

/**
 * Event payload published to the {@code chat-completed} Kafka topic after
 * every successful assistant response.
 *
 * <p>This record is serialized as JSON by Spring Kafka's JsonSerializer and
 * deserialized by both consumers (CacheUpdateConsumer, UsageAnalyticsConsumer).</p>
 */
public record ChatCompletedEvent(
        String conversationId,
        String userId,
        String query,
        String answer,
        String reasoningType,
        Instant timestamp
) {
    /** Convenience factory used by ChatController/ConversationCommandService. */
    public static ChatCompletedEvent of(String conversationId, String userId,
                                        String query, String answer, String reasoningType) {
        return new ChatCompletedEvent(conversationId, userId, query, answer,
                reasoningType, Instant.now());
    }
}
