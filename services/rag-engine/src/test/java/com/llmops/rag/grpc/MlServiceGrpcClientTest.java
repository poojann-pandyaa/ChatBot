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
        client = new MlServiceGrpcClient();
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
}
