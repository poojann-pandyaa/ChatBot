package com.llmops.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload sent by the frontend client.
 */
public record UserChatRequest(
    @NotBlank(message = "Prompt cannot be empty")
    @Size(max = 2000, message = "Prompt exceeds maximum length of 2000 characters")
    String prompt,

    @NotBlank(message = "Conversation ID cannot be empty")
    @Size(max = 255, message = "Conversation ID exceeds maximum length of 255 characters")
    @JsonProperty("conversation_id") 
    String conversationId,
    
    boolean debug,
    boolean stream,
    
    @Size(max = 255, message = "User ID exceeds maximum length of 255 characters")
    @JsonProperty("user_id") 
    String userId
) {
    // Custom getter to guarantee a fallback default_user for backward compatibility
    @Override
    public String userId() {
        return userId != null ? userId : "default_user";
    }
}
