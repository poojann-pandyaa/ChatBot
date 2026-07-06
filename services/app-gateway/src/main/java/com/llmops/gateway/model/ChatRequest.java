package com.llmops.gateway.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRequest(
    String prompt,
    List<ChatMessage> history,
    @JsonProperty("include_trace") boolean includeTrace,
    boolean stream
) {}
