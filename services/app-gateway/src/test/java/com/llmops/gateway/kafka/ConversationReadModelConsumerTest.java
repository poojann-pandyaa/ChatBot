package com.llmops.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.repository.MessageRepository;
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
    private MessageRepository messageRepository;
    private ObjectMapper objectMapper;
    private ConversationReadModelConsumer consumer;
    private com.llmops.gateway.sharding.ShardRouter shardRouter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        messageRepository = mock(MessageRepository.class);
        objectMapper = new ObjectMapper();
        shardRouter = mock(com.llmops.gateway.sharding.ShardRouter.class);
        consumer = new ConversationReadModelConsumer(redisTemplate, messageRepository, objectMapper, shardRouter);
    }

    @Test
    public void testConsume_FirstMessage_UpdatesReadModel() throws Exception {
        // Arrange
        String convId = "conv-123";
        ChatCompletedEvent event = new ChatCompletedEvent(convId, "user-456", "What is LoRA?", "LoRA is low-rank adaptation.", "adaptive", Instant.now());
        
        com.llmops.gateway.entity.Message userMsg = new com.llmops.gateway.entity.Message();
        userMsg.setRole("user");
        userMsg.setContent("What is LoRA?");
        
        com.llmops.gateway.entity.Message asstMsg = new com.llmops.gateway.entity.Message();
        asstMsg.setRole("assistant");
        asstMsg.setContent("LoRA is low-rank adaptation.");
        
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(convId))
                .thenReturn(List.of(userMsg, asstMsg));

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

}
