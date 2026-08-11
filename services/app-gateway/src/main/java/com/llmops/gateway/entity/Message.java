package com.llmops.gateway.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity representing a single chat message (user or assistant turn).
 *
 * <p>Postgres is the canonical, durable store for all conversation messages.
 * Each call to {@code POST /api/chat} results in exactly two {@code Message} rows
 * (role=user and role=assistant) written atomically inside the same transaction
 * as the parent {@code Conversation} upsert and the {@code OutboxEvent} insert.</p>
 *
 * <p>Redis holds a denormalized summary of these rows as a read-optimised cache
 * and can be fully rebuilt from this table at any time.</p>
 */
@Entity
@Table(
    name = "messages",
    indexes = @Index(
        name = "idx_messages_conversation_id_created_at",
        columnList = "conversation_id, created_at"
    )
)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code conversations.id}. Never null. */
    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    /** "user" or "assistant". Enforced by DB CHECK constraint. */
    @Column(nullable = false, length = 20)
    private String role;

    /** Full message text. Stored as TEXT (unbounded). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** RAG routing label from the backend (e.g. "commonsense", "retrieval"). Nullable. */
    @Column(name = "reasoning_type", length = 50)
    private String reasoningType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Message() {}

    public Message(String conversationId, String role, String content, String reasoningType) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.reasoningType = reasoningType;
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getReasoningType() { return reasoningType; }
    public void setReasoningType(String reasoningType) { this.reasoningType = reasoningType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
