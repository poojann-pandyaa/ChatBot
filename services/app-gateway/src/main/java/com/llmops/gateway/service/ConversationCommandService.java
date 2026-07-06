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
 * Service to handle commands (writes) for conversations.
 *
 * <p>Implements the Outbox pattern: both the conversation record and the outbox
 * event are saved to PostgreSQL within a single database transaction, ensuring
 * consistency.</p>
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
     * Atomically saves or updates the Conversation, and inserts an Outbox event
     * to publish to Kafka.
     *
     * <p>Runs inside a write transaction. If either operation fails, the entire
     * transaction is rolled back, preventing orphaned messages or missing database state.</p>
     */
    @Transactional
    public void saveConversationAndEvent(String conversationId, String title, String userId,
                                          String query, String answer, String reasoningType) {
        shardRouter.bindWriteRoute(userId);
        log.info("Saving conversation {} on shard key {} and scheduling outbox event in transaction...",
                conversationId, userId);

        // 1. Save or update the Conversation metadata
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseGet(() -> new Conversation(conversationId, LocalDateTime.now(), title, userId));
        
        // If it exists, update title
        if (conversationRepository.existsById(conversationId)) {
            conversation.setTitle(title);
        }
        conversationRepository.save(conversation);

        // 2. Serialize event payload to JSON
        ChatCompletedEvent event = ChatCompletedEvent.of(conversationId, userId, query, answer, reasoningType);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize ChatCompletedEvent for outbox: {}", e.getMessage());
            throw new RuntimeException("Serialization failure during outbox save", e);
        }

        // 3. Write outbox event
        OutboxEvent outboxEvent = new OutboxEvent(conversationId, "chat-completed", payloadJson);
        outboxEventRepository.save(outboxEvent);

        log.info("Transaction commit succeeded for conversation {} and outbox event.", conversationId);
    }
}
