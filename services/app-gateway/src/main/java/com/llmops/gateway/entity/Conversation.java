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

    public Conversation() {}

    public Conversation(String id, LocalDateTime createdAt, String title) {
        this.id = id;
        this.createdAt = createdAt;
        this.title = title;
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
}
