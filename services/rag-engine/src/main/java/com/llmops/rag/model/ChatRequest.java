package com.llmops.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatRequest(
    String prompt,
    List<ChatMessage> history,
    @JsonProperty("include_trace") boolean includeTrace,
    boolean stream
) {}
