package com.llmops.rag.service;

import com.llmops.rag.client.ElasticsearchClient;
import com.llmops.rag.client.MlServiceClient;
import com.llmops.rag.client.QdrantClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrieverService {

    private final MlServiceClient mlServiceClient;
    private final QdrantClient qdrantClient;
    private final ElasticsearchClient elasticsearchClient;

    @Autowired
    public RetrieverService(
            MlServiceClient mlServiceClient,
            QdrantClient qdrantClient,
            ElasticsearchClient elasticsearchClient) {
        this.mlServiceClient = mlServiceClient;
        this.qdrantClient = qdrantClient;
        this.elasticsearchClient = elasticsearchClient;
    }

    public Mono<List<Map<String, Object>>> retrieve(String query, int topK) {
        return mlServiceClient.embed(query)
                .flatMap(vector -> {
                    Mono<List<Map<String, Object>>> denseSearch = qdrantClient.search(vector, topK);
                    Mono<List<Map<String, Object>>> sparseSearch = elasticsearchClient.search(query, topK);

                    return Mono.zip(denseSearch, sparseSearch)
                            .map(tuple -> {
                                List<Map<String, Object>> denseResults = tuple.getT1();
                                List<Map<String, Object>> sparseResults = tuple.getT2();

                                Map<Integer, Double> rrfScores = new HashMap<>();
                                Map<Integer, Map<String, Object>> chunkPayloads = new HashMap<>();

                                for (int rank = 0; rank < denseResults.size(); rank++) {
                                    Map<String, Object> hit = denseResults.get(rank);
                                    int cid = (Integer) hit.get("chunk_id");
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> payload = (Map<String, Object>) hit.get("payload");
                                    chunkPayloads.put(cid, payload);
                                    rrfScores.put(cid, rrfScores.getOrDefault(cid, 0.0) + 1.0 / (60.0 + rank + 1.0));
                                }

                                for (int rank = 0; rank < sparseResults.size(); rank++) {
                                    Map<String, Object> hit = sparseResults.get(rank);
                                    int cid = (Integer) hit.get("chunk_id");
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> payload = (Map<String, Object>) hit.get("payload");
                                    chunkPayloads.put(cid, payload);
                                    rrfScores.put(cid, rrfScores.getOrDefault(cid, 0.0) + 1.0 / (60.0 + rank + 1.0));
                                }

                                List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
                                sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                                List<Map<String, Object>> results = new ArrayList<>();
                                int count = Math.min(topK, sorted.size());
                                for (int i = 0; i < count; i++) {
                                    Map.Entry<Integer, Double> entry = sorted.get(i);
                                    int cid = entry.getKey();
                                    results.add(Map.of(
                                            "chunk_id", cid,
                                            "score", entry.getValue(),
                                            "metadata", chunkPayloads.get(cid)
                                    ));
                                }
                                return results;
                            });
                });
    }
}
