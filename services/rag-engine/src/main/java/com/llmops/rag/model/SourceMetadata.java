package com.llmops.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceMetadata(
    @JsonProperty("chunk_id") int chunkId,
    double score,
    @JsonProperty("question_id") String questionId,
    @JsonProperty("is_accepted") boolean isAccepted,
    String domain,
    @JsonProperty("chunk_text") String chunkText
) {}
