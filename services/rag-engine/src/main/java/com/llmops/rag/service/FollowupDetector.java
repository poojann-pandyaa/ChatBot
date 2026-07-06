package com.llmops.rag.service;

import com.llmops.rag.model.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class FollowupDetector {

    private static final List<String> PRONOUN_PATTERNS = List.of(
            "it", "this", "that", "them", "these", "those",
            "its", "the same", "above", "previous", "earlier",
            "in java", "in python", "in c++", "in javascript", "in go", "in rust"
    );

    public boolean isFollowup(String query, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }

        String q = query.toLowerCase().trim();

        // Short queries with history are likely follow-ups
        String[] words = q.split("\\s+");
        if (words.length <= 5) {
            return true;
        }

        // Contains pronouns referencing previous context
        for (String pronoun : PRONOUN_PATTERNS) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(pronoun) + "\\b");
            if (pattern.matcher(q).find()) {
                return true;
            }
        }

        // Starts with conjunctions suggesting continuation
        if (q.startsWith("and ") || q.startsWith("but ") || q.startsWith("also ") || 
            q.startsWith("what about ") || q.startsWith("how about ") || 
            q.startsWith("why ") || q.startsWith("how to ")) {
            if (words.length <= 6) {
                return true;
            }
        }

        return false;
    }
}
