package com.llmops.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ConversationReadModelConsumerTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private ConversationReadModelConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();
        consumer = new ConversationReadModelConsumer(redisTemplate, objectMapper);
    }

    @Test
    public void testConsume_FirstMessage_UpdatesReadModel() throws Exception {
        // Arrange
        String convId = "conv-123";
        ChatCompletedEvent event = new ChatCompletedEvent(convId, "user-456", "What is LoRA?", "LoRA is low-rank adaptation.", "adaptive", Instant.now());
        when(valueOperations.get("conversation:" + convId + ":summary")).thenReturn(null);

        // Act
        consumer.consume(event, 0, 100L);

        // Assert
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture());

        assertEquals("conversation:conv-123:summary", keyCaptor.getValue());
        String savedJson = valueCaptor.getValue();
        assertNotNull(savedJson);

        Map<String, Object> data = objectMapper.readValue(savedJson, Map.class);
        assertEquals(convId, data.get("conversation_id"));
        List<Map<String, String>> messages = (List<Map<String, String>>) data.get("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("What is LoRA?", messages.get(0).get("content"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("LoRA is low-rank adaptation.", messages.get(1).get("content"));
    }

    @Test
    public void testConsume_DuplicateMessage_IsIdempotentAndSkipsUpdate() throws Exception {
        // Arrange
        String convId = "conv-123";
        ChatCompletedEvent event = new ChatCompletedEvent(convId, "user-456", "What is LoRA?", "LoRA is low-rank adaptation.", "adaptive", Instant.now());
        
        // Simulating that the message is already processed and stored in Redis
        Map<String, Object> existingReadModel = Map.of(
                "conversation_id", convId,
                "messages", List.of(
                        Map.of("role", "user", "content", "What is LoRA?"),
                        Map.of("role", "assistant", "content", "LoRA is low-rank adaptation.")
                )
        );
        String existingJson = objectMapper.writeValueAsString(existingReadModel);
        when(valueOperations.get("conversation:" + convId + ":summary")).thenReturn(existingJson);

        // Act
        consumer.consume(event, 0, 101L);

        // Assert
        // Verify we read from Redis
        verify(valueOperations).get("conversation:conv-123:summary");
        // Verify we DID NOT write back to Redis again (idempotent skip)
        verify(valueOperations, never()).set(anyString(), anyString());
    }
}
