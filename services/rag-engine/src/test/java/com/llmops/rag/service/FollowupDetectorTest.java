package com.llmops.rag.service;

import com.llmops.rag.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowupDetectorTest {

    private FollowupDetector detector;
    private List<ChatMessage> history;

    @BeforeEach
    void setUp() {
        detector = new FollowupDetector();
        history = List.of(new ChatMessage("user", "Hello"));
    }

    @Test
    void testEmptyHistory_ReturnsFalse() {
        assertFalse(detector.isFollowup("socket programming", List.of()));
        assertFalse(detector.isFollowup("socket programming", null));
    }

    @Test
    void testNotFollowup_SocketProgramming() {
        assertFalse(detector.isFollowup("socket programming", history));
    }

    @Test
    void testNotFollowup_GarbageCollection() {
        assertFalse(detector.isFollowup("how does garbage collection work in java", history));
    }

    @Test
    void testNotFollowup_ExplainOOPS() {
        assertFalse(detector.isFollowup("Explain OOPS", history));
    }

    @Test
    void testFollowup_Pronoun() {
        assertTrue(detector.isFollowup("what about it", history));
        assertTrue(detector.isFollowup("why is that slow", history));
        assertTrue(detector.isFollowup("can you explain this further", history));
    }

    @Test
    void testFollowup_Conjunction() {
        assertTrue(detector.isFollowup("and how do I use it", history));
        assertTrue(detector.isFollowup("but why?", history));
        assertTrue(detector.isFollowup("also what about memory", history));
    }
}
