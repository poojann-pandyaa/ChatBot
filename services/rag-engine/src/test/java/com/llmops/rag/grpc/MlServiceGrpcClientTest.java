package com.llmops.rag.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MlServiceGrpcClientTest {

    private MlServiceGrpcClient client;

    @BeforeEach
    public void setup() {
        io.micrometer.core.instrument.MeterRegistry meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        client = new MlServiceGrpcClient(meterRegistry);
        ReflectionTestUtils.setField(client, "mlServiceHost", "localhost");
        ReflectionTestUtils.setField(client, "mlServicePort", 50051);
        client.init(); // Loads the JSON configs
    }

    @Test
    public void testKeywordFallback() throws Exception {
        File casesFile = new File("../../configs/classifier_test_cases.json");
        if (!casesFile.exists()) {
            casesFile = new File("configs/classifier_test_cases.json"); // When running from root maybe
        }
        
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(casesFile);
        
        for (JsonNode testCase : cases) {
            String query = testCase.get("query").asText();
            String expected = testCase.get("expected").asText();
            
            // We use reflection to call the private method
            String result = ReflectionTestUtils.invokeMethod(client, "keywordFallback", query);
            
            assertEquals(expected, result, "Failed for query: " + query);
        }
    }

    @Test
    public void testExplicitAdaptiveCombinations() {
        // a) ONLY explain signal -> "commonsense"
        // "what is" is an explain signal. No usage signal present.
        String explainOnly = ReflectionTestUtils.invokeMethod(client, "keywordFallback", "what is docker?");
        assertEquals("commonsense", explainOnly, "ONLY explain signal should be commonsense");

        // b) ONLY usage signal -> "commonsense"
        // "how to implement" is a usage signal. No explain signal present.
        String usageOnly = ReflectionTestUtils.invokeMethod(client, "keywordFallback", "how to implement rest in java");
        assertEquals("commonsense", usageOnly, "ONLY usage signal should be commonsense");

        // c) BOTH signals -> "adaptive"
        // "what is" (explain) + "when should I use it" (usage)
        String bothSignals = ReflectionTestUtils.invokeMethod(client, "keywordFallback", "what is docker and when should I use it");
        assertEquals("adaptive", bothSignals, "BOTH signals should be adaptive");
    }
}
