package com.llmops.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.Conversation;
import com.llmops.gateway.entity.Message;
import com.llmops.gateway.entity.OutboxEvent;
import com.llmops.gateway.kafka.ChatCompletedEvent;
import com.llmops.gateway.repository.ConversationRepository;
import com.llmops.gateway.repository.MessageRepository;
import com.llmops.gateway.repository.OutboxEventRepository;
import com.llmops.gateway.sharding.DataSourceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

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
    private final MessageRepository messageRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final com.llmops.gateway.sharding.ShardRouter shardRouter;
    private final TransactionTemplate transactionTemplate;

    public ConversationCommandService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            com.llmops.gateway.sharding.ShardRouter shardRouter,
            TransactionTemplate transactionTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.shardRouter = shardRouter;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Atomically saves/updates the Conversation and inserts an Outbox event for Kafka publishing.
     * Both writes happen in one transaction — failure in either rolls back both.
     */
    public void saveConversationAndEvent(String conversationId, String title, String userId,
                                          String query, String answer, String reasoningType) {
        shardRouter.bindWriteRoute(userId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
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

                // Insert individual messages
                Message userMessage = new Message(conversationId, "user", query, null);
                Message assistantMessage = new Message(conversationId, "assistant", answer, reasoningType);
                messageRepository.saveAll(java.util.List.of(userMessage, assistantMessage));

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
            });
        } finally {
            DataSourceContextHolder.clear(); // prevent ThreadLocal leak on reused scheduler threads
        }
    }

    /**
     * Deletes a single conversation and all its messages atomically.
     * The Redis summary key is evicted by the caller (ChatController).
     */
    public void deleteConversation(String conversationId, String userId) {
        shardRouter.bindWriteRoute(userId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                log.info("Deleting conversation {} and all its messages for user {}...", conversationId, userId);
                messageRepository.deleteByConversationId(conversationId);
                conversationRepository.deleteById(conversationId);
                log.info("Deleted conversation {}.", conversationId);
            });
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    /**
     * Deletes ALL conversations and messages for a given user atomically.
     */
    public void deleteAllConversations(String userId) {
        shardRouter.bindWriteRoute(userId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                log.info("Deleting all conversations for user {}...", userId);
                List<Conversation> userConversations = conversationRepository.findByUserIdOrderByCreatedAtDesc(userId);
                for (Conversation c : userConversations) {
                    messageRepository.deleteByConversationId(c.getId());
                }
                conversationRepository.deleteAll(userConversations);
                log.info("Deleted {} conversations for user {}.", userConversations.size(), userId);
            });
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
