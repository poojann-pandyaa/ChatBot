package com.llmops.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload sent by the frontend client.
 */
public record UserChatRequest(
    String prompt,
    @JsonProperty("conversation_id") String conversationId,
    boolean debug,
    boolean stream,
    @JsonProperty("user_id") String userId
) {
    // Custom getter to guarantee a fallback default_user for backward compatibility
    @Override
    public String userId() {
        return userId != null ? userId : "default_user";
    }
}
