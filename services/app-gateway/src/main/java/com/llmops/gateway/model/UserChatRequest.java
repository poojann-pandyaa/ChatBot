package com.llmops.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserChatRequest(
    String prompt,
    @JsonProperty("conversation_id") String conversationId,
    boolean debug,
    boolean stream
) {}
