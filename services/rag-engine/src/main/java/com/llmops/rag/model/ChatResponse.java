package com.llmops.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ChatResponse(
    String answer,
    @JsonProperty("reasoning_type") String reasoningType,
    List<SourceMetadata> sources,
    Map<String, Object> trace
) {}
