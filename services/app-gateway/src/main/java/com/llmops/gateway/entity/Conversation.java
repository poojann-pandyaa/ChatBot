package com.llmops.gateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
public class Conversation {
    
    @Id
    private String id;
    
    private LocalDateTime createdAt;
    
    private String title;
    
    @jakarta.persistence.Column(name = "user_id")
    private String userId;

    public Conversation() {}

    public Conversation(String id, LocalDateTime createdAt, String title) {
        this(id, createdAt, title, "default_user");
    }

    public Conversation(String id, LocalDateTime createdAt, String title, String userId) {
        this.id = id;
        this.createdAt = createdAt;
        this.title = title;
        this.userId = userId != null ? userId : "default_user";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId != null ? userId : "default_user";
    }
}
