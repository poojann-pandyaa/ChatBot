package com.llmops.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.Conversation;
import com.llmops.gateway.entity.OutboxEvent;
import com.llmops.gateway.kafka.ChatCompletedEvent;
import com.llmops.gateway.repository.ConversationRepository;
import com.llmops.gateway.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Handles write operations for conversations.
 *
 * <p>Implements the Transactional Outbox pattern: both the conversation record
 * and the outbox event are saved within a single database transaction,
 * guaranteeing consistency between the DB and Kafka.</p>
 */
@Service
public class ConversationCommandService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCommandService.class);

    private final ConversationRepository conversationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final com.llmops.gateway.sharding.ShardRouter shardRouter;

    public ConversationCommandService(
            ConversationRepository conversationRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            com.llmops.gateway.sharding.ShardRouter shardRouter) {
        this.conversationRepository = conversationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.shardRouter = shardRouter;
    }

    /**
     * Atomically saves/updates the Conversation and inserts an Outbox event for Kafka publishing.
     * Both writes happen in one transaction — failure in either rolls back both.
     */
    @Transactional
    public void saveConversationAndEvent(String conversationId, String title, String userId,
                                          String query, String answer, String reasoningType) {
        shardRouter.bindWriteRoute(userId);
        log.info("Saving conversation {} on shard key {} and scheduling outbox event in transaction...",
                conversationId, userId);

        // Upsert the conversation record
        Conversation conversation = conversationRepository.findById(conversationId)
                .map(existing -> {
                    existing.setTitle(title);
                    return existing;
                })
                .orElseGet(() -> new Conversation(conversationId, LocalDateTime.now(), title, userId));
        conversationRepository.save(conversation);

        // Serialize and write the outbox event
        ChatCompletedEvent event = ChatCompletedEvent.of(conversationId, userId, query, answer, reasoningType);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize ChatCompletedEvent for outbox: {}", e.getMessage());
            throw new RuntimeException("Serialization failure during outbox save", e);
        }

        outboxEventRepository.save(new OutboxEvent(conversationId, "chat-completed", payloadJson));
        log.info("Transaction commit succeeded for conversation {} and outbox event.", conversationId);
    }
}
