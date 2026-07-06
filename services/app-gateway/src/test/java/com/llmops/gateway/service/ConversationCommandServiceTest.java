package com.llmops.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.Conversation;
import com.llmops.gateway.entity.OutboxEvent;
import com.llmops.gateway.repository.ConversationRepository;
import com.llmops.gateway.repository.OutboxEventRepository;
import com.llmops.gateway.sharding.ShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ConversationCommandServiceTest {

    private ConversationRepository conversationRepository;
    private OutboxEventRepository outboxEventRepository;
    private ShardRouter shardRouter;
    private ConversationCommandService conversationCommandService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @BeforeEach
    public void setup() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        outboxEventRepository = Mockito.mock(OutboxEventRepository.class);
        shardRouter = Mockito.mock(ShardRouter.class);

        conversationCommandService = new ConversationCommandService(
                conversationRepository,
                outboxEventRepository,
                objectMapper,
                shardRouter
        );
    }

    @Test
    public void testSaveConversationAndEvent_Success() {
        // Arrange
        String convId = "session-123";
        String title = "Test prompt title";
        String userId = "user-789";
        String query = "Explain LoRA";
        String answer = "LoRA is Low-Rank Adaptation";
        String reasoningType = "adaptive";

        when(conversationRepository.findById(convId)).thenReturn(Optional.empty());

        // Act
        conversationCommandService.saveConversationAndEvent(convId, title, userId, query, answer, reasoningType);

        // Assert
        // Verify shard routing context is bound before performing writes
        verify(shardRouter).bindWriteRoute(userId);

        // Verify conversation is saved
        ArgumentCaptor<Conversation> convCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(convCaptor.capture());
        Conversation savedConv = convCaptor.getValue();
        assertEquals(convId, savedConv.getId());
        assertEquals(title, savedConv.getTitle());
        assertEquals(userId, savedConv.getUserId());

        // Verify outbox event is saved
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent savedEvent = outboxCaptor.getValue();
        assertEquals(convId, savedEvent.getAggregateId());
        assertEquals("chat-completed", savedEvent.getEventType());
        assertFalse(savedEvent.isPublished());
        assertTrue(savedEvent.getPayload().contains("adaptive"));
        assertTrue(savedEvent.getPayload().contains("user-789"));
    }
}
