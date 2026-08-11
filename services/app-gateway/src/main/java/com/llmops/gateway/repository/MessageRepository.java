package com.llmops.gateway.repository;

import com.llmops.gateway.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for conversation messages.
 * Postgres is the single durable source of truth.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Retrieves all messages for a specific conversation in chronological order.
     * Used for full history rebuilds and cache warming.
     */
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /**
     * Retrieves the most recent N messages for a conversation, ordered from newest to oldest.
     * Note: the results should be reversed before being sent to the LLM context window.
     */
    List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(String conversationId);

    /**
     * Deletes all messages for a given conversation.
     * Called as part of the deleteConversation transaction.
     */
    void deleteByConversationId(String conversationId);
}
