package com.llmops.rag.service;

import com.llmops.rag.client.OllamaClient;
import com.llmops.rag.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GeneratorServiceTest {

    private GeneratorService generatorService;
    private OllamaClient ollamaClient;

    @BeforeEach
    public void setup() {
        ollamaClient = mock(OllamaClient.class);
        generatorService = new GeneratorService(ollamaClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRewriteQueryUsesOptions() {
        when(ollamaClient.generate(anyString(), any(Map.class))).thenReturn(Mono.just("rewritten query"));

        List<ChatMessage> history = List.of(new ChatMessage("user", "Hello"));
        generatorService.rewriteQuery("follow-up", history).block();

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ollamaClient).generate(anyString(), optionsCaptor.capture());

        Map<String, Object> options = optionsCaptor.getValue();
        assertEquals(0.0, options.get("temperature"));
        assertEquals(32, options.get("num_predict"));
    }

    @Test
    public void testBuildPromptWithNullMetadata() {
        java.util.Map<String, Object> cand = new java.util.HashMap<>();
        cand.put("chunk_id", 123);
        cand.put("metadata", null);

        String prompt = generatorService.buildPrompt("test", List.of(cand), "commonsense", List.of(), List.of());
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("Source 1 | Score: 0.0000 | Accepted: false | Domain: unknown"));
    }
}
