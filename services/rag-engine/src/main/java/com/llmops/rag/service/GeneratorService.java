package com.llmops.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.rag.client.OllamaClient;
import com.llmops.rag.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
public class GeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GeneratorService.class);
    private final OllamaClient ollamaClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${services.ollama.model:gemma2:2b}")
    private String modelName;

    @Autowired
    public GeneratorService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String buildPrompt(
            String query,
            List<Map<String, Object>> retrievedChunks,
            String reasoningType,
            List<String> subQuestions,
            List<ChatMessage> history) {

        List<String> contextParts = new ArrayList<>();
        int limit = retrievedChunks == null ? 0 : Math.min(3, retrievedChunks.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> cand = retrievedChunks.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) cand.get("metadata");
            
            Number scoreNum = (Number) meta.getOrDefault("score", 0.0);
            double score = scoreNum != null ? scoreNum.doubleValue() : 0.0;
            
            Boolean isAcc = (Boolean) meta.getOrDefault("is_accepted", false);
            boolean isAccepted = isAcc != null && isAcc;
            
            String chunkText = (String) meta.getOrDefault("chunk_text", "");
            if (chunkText.length() > 800) {
                chunkText = chunkText.substring(0, 800);
            }
            String domain = (String) meta.getOrDefault("domain", "unknown");

            contextParts.add(String.format(
                    "[Source %d | Score: %.4f | Accepted: %b | Domain: %s]\n%s",
                    i + 1, score, isAccepted, domain, chunkText
            ));
        }

        String context = String.join("\n\n", contextParts);

        Map<String, String> cotInstructions = Map.of(
                "commonsense", "Answer the question thoroughly and helpfully based on the sources above. " +
                        "Explain the concept, include code examples if the sources contain them, " +
                        "and cite which source(s) you used. Write at least 3-4 sentences.",
                "adaptive", "The question has multiple parts. " +
                        "First address each sub-question separately using the sources, " +
                        "then synthesise everything into a single unified answer. " +
                        "Be thorough -- include examples and code where relevant.",
                "strategic", "This is a complex comparative or architectural question. " +
                        "Step 1 - identify the main categories or dimensions relevant to the query. " +
                        "Step 2 - discuss each dimension in depth using evidence from the sources. " +
                        "Step 3 - write a final synthesised recommendation with reasoning."
        );
        String cotInstruction = cotInstructions.getOrDefault(reasoningType, cotInstructions.get("commonsense"));

        StringBuilder subQBlock = new StringBuilder();
        if (subQuestions != null && !subQuestions.isEmpty() && !(subQuestions.size() == 1 && subQuestions.get(0).equals(query))) {
            subQBlock.append("Sub-questions to address:\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                subQBlock.append(String.format("  %d. %s\n", i + 1, subQuestions.get(i)));
            }
            subQBlock.append("\n");
        }

        StringBuilder historyBlock = new StringBuilder();
        if (history != null) {
            for (ChatMessage msg : history) {
                String role = "user".equalsIgnoreCase(msg.role()) ? "user" : "model";
                historyBlock.append(String.format("<start_of_turn>%s\n%s\n<end_of_turn>\n", role, msg.content()));
            }
        }

        return historyBlock.toString() +
                "<start_of_turn>user\n" +
                "You are a senior software engineer answering Stack Overflow questions. " +
                "Use the retrieved evidence below as your primary source, but you may elaborate " +
                "on concepts, explain reasoning, and provide structure to make the answer clear.\n\n" +
                "RULES:\n" +
                "1. Base your answer on the Retrieved Evidence AND the conversation history.\n" +
                "2. If the user is asking a follow-up question about the previous conversation, prioritize the conversation history. You may ignore the retrieved evidence if it is not relevant to the follow-up.\n" +
                "3. Do not invent facts that contradict the sources or history.\n" +
                "4. If the retrieved sources do not contain enough information to answer the question, you should answer it using your general knowledge as a senior software engineer. In this case, begin your response by stating that you are answering using general knowledge because the retrieved sources were not relevant or sufficient.\n" +
                "5. You MAY explain, expand, and structure information from the sources -- " +
                "do not copy-paste raw source text verbatim.\n" +
                "6. Include code blocks using markdown (```language) if the sources contain code " +
                "or if a code example would make the answer significantly clearer.\n\n" +
                "Retrieved Evidence:\n" + context + "\n\n" +
                subQBlock.toString() +
                "Instruction: " + cotInstruction + "\n\n" +
                "Question: " + query + "\n" +
                "<end_of_turn>\n" +
                "<start_of_turn>model\n";
    }

    public Mono<String> rewriteQuery(String query, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return Mono.just(query);
        }

        StringBuilder historyText = new StringBuilder();
        for (ChatMessage msg : history) {
            String role = "user".equalsIgnoreCase(msg.role()) ? "User" : "Assistant";
            String contentSnippet = msg.content().replace("\n", " ");
            if (contentSnippet.length() > 300) {
                contentSnippet = contentSnippet.substring(0, 300);
            }
            historyText.append(String.format("%s: %s\n", role, contentSnippet));
        }

        String prompt = "<start_of_turn>user\n" +
                "You are a search query rewriter. Rewrite the follow-up user query into a single standalone query. " +
                "Resolve any pronouns (like 'it', 'this', 'that', 'them', 'these') or incomplete sentences using the conversation history.\n" +
                "If the follow-up query is already standalone or does not refer to the history, return it exactly as it is.\n" +
                "Do NOT include any explanations, labels, quotes, or introductory text. Return ONLY the rewritten query on a single line.\n\n" +
                "Conversation History:\n" +
                historyText.toString() +
                "Follow-up Query: " + query + "\n" +
                "<end_of_turn>\n" +
                "<start_of_turn>model\n";

        Map<String, Object> body = Map.of(
                "model", modelName,
                "prompt", prompt,
                "options", Map.of(
                        "temperature", 0.0,
                        "num_ctx", 2048,
                        "num_predict", 32,
                        "stop", List.of("\n", "<end_of_turn>")
                ),
                "stream", false
        );

        // Directly call the underlying Ollama generate method bypass circuit breaker for raw rewrite
        return ollamaClient.generate(prompt)
                .map(res -> {
                    String rewritten = res.replace("\"", "").replace("'", "").trim();
                    if (!rewritten.isEmpty()) {
                        log.info("Query Rewritten: '{}' -> '{}'", query, rewritten);
                        return rewritten;
                    }
                    return query;
                })
                .onErrorResume(e -> {
                    log.warn("Failed to rewrite query: {}", e.getMessage());
                    return Mono.just(query);
                });
    }

    private double scoreResponse(String response) {
        String[] tokens = response.split("\\s+");
        if (tokens.length == 0) {
            return 0.0;
        }
        long uniqueCount = Arrays.stream(tokens).distinct().count();
        return (double) uniqueCount / tokens.length;
    }

    public Mono<String> generateWithConsistency(String prompt, int n) {
        log.info("Applying self-consistency decoding (n={})", n);
        if (n <= 1) {
            return ollamaClient.generate(prompt);
        }

        // Sequential invocation
        List<Mono<String>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(ollamaClient.generate(prompt));
        }

        return Mono.zip(calls, results -> {
            String bestResponse = "";
            double maxScore = -1.0;
            for (Object resObj : results) {
                String response = (String) resObj;
                double score = scoreResponse(response);
                if (score > maxScore) {
                    maxScore = score;
                    bestResponse = response;
                }
            }
            return bestResponse;
        });
    }

    public Mono<String> generate(String prompt, String reasoningType) {
        if ("strategic".equalsIgnoreCase(reasoningType)) {
            return generateWithConsistency(prompt, 1);
        } else {
            return ollamaClient.generate(prompt);
        }
    }

    public Flux<String> generateStream(String prompt) {
        return ollamaClient.generateStream(prompt)
                .map(line -> {
                    try {
                        JsonNode node = mapper.readTree(line);
                        String token = node.get("response").asText();
                        return token;
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(token -> !token.isEmpty());
    }
}
