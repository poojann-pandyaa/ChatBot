package com.llmops.rag.service;

import com.llmops.rag.client.ElasticsearchClient;
import com.llmops.rag.client.QdrantClient;
import com.llmops.rag.grpc.MlServiceGrpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieves candidate chunks using Reciprocal Rank Fusion (RRF) over
 * dense (Qdrant vector search) and sparse (Elasticsearch BM25) results.
 */
@Service
public class RetrieverService {

    private final MlServiceGrpcClient mlServiceClient;
    private final QdrantClient qdrantClient;
    private final ElasticsearchClient elasticsearchClient;

    @Autowired
    public RetrieverService(
            MlServiceGrpcClient mlServiceClient,
            QdrantClient qdrantClient,
            ElasticsearchClient elasticsearchClient) {
        this.mlServiceClient = mlServiceClient;
        this.qdrantClient = qdrantClient;
        this.elasticsearchClient = elasticsearchClient;
    }

    /**
     * Embeds the query, runs dense + sparse search in parallel,
     * and fuses results with RRF (k=60).
     */
    public Mono<List<Map<String, Object>>> retrieve(String query, int topK) {
        return mlServiceClient.embed(query)
                .flatMap(vector -> {
                    Mono<List<Map<String, Object>>> denseSearch = qdrantClient.search(vector, topK);
                    Mono<List<Map<String, Object>>> sparseSearch = elasticsearchClient.search(query, topK);

                    return Mono.zip(denseSearch, sparseSearch)
                            .map(tuple -> fuse(tuple.getT1(), tuple.getT2(), topK));
                });
    }

    /** Reciprocal Rank Fusion over two ranked lists. */
    private List<Map<String, Object>> fuse(
            List<Map<String, Object>> denseResults,
            List<Map<String, Object>> sparseResults,
            int topK) {

        Map<Integer, Double> rrfScores = new HashMap<>();
        Map<Integer, Map<String, Object>> chunkPayloads = new HashMap<>();

        accumulateRrf(denseResults, rrfScores, chunkPayloads);
        accumulateRrf(sparseResults, rrfScores, chunkPayloads);

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
    }

    private void accumulateRrf(
            List<Map<String, Object>> hits,
            Map<Integer, Double> rrfScores,
            Map<Integer, Map<String, Object>> chunkPayloads) {
        for (int rank = 0; rank < hits.size(); rank++) {
            Map<String, Object> hit = hits.get(rank);
            int cid = (Integer) hit.get("chunk_id");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) hit.get("payload");
            chunkPayloads.put(cid, payload);
            rrfScores.merge(cid, 1.0 / (60.0 + rank + 1.0), Double::sum);
        }
    }
}
