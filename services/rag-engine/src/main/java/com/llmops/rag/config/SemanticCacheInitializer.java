package com.llmops.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@Component
public class SemanticCacheInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheInitializer.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    public SemanticCacheInitializer(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) {
        log.info("Checking Redis Semantic Cache Index...");
        RedisCommandExecutor.execute(redisTemplate, "FT.INFO", "idx:semantic_cache".getBytes(StandardCharsets.UTF_8))
                .next()
                .flatMap(res -> {
                    log.info("Semantic cache index already exists.");
                    return Mono.empty();
                })
                .onErrorResume(err -> {
                    log.info("Semantic cache index not found, creating it: {}", err.getMessage());
                    return createIndex();
                })
                .subscribe();
    }

    private Mono<Void> createIndex() {
        return RedisCommandExecutor.execute(redisTemplate, "FT.CREATE",
                "idx:semantic_cache".getBytes(StandardCharsets.UTF_8),
                "ON".getBytes(StandardCharsets.UTF_8),
                "HASH".getBytes(StandardCharsets.UTF_8),
                "PREFIX".getBytes(StandardCharsets.UTF_8),
                "1".getBytes(StandardCharsets.UTF_8),
                "cache:".getBytes(StandardCharsets.UTF_8),
                "SCHEMA".getBytes(StandardCharsets.UTF_8),
                "embedding".getBytes(StandardCharsets.UTF_8),
                "VECTOR".getBytes(StandardCharsets.UTF_8),
                "HNSW".getBytes(StandardCharsets.UTF_8),
                "6".getBytes(StandardCharsets.UTF_8),
                "TYPE".getBytes(StandardCharsets.UTF_8),
                "FLOAT32".getBytes(StandardCharsets.UTF_8),
                "DIM".getBytes(StandardCharsets.UTF_8),
                "768".getBytes(StandardCharsets.UTF_8),
                "DISTANCE_METRIC".getBytes(StandardCharsets.UTF_8),
                "COSINE".getBytes(StandardCharsets.UTF_8),
                "query".getBytes(StandardCharsets.UTF_8),
                "TEXT".getBytes(StandardCharsets.UTF_8),
                "answer".getBytes(StandardCharsets.UTF_8),
                "TEXT".getBytes(StandardCharsets.UTF_8),
                "reasoning_type".getBytes(StandardCharsets.UTF_8),
                "TEXT".getBytes(StandardCharsets.UTF_8),
                "sources".getBytes(StandardCharsets.UTF_8),
                "TEXT".getBytes(StandardCharsets.UTF_8)
        ).then()
        .doOnSuccess(v -> log.info("Semantic cache index created successfully."))
        .doOnError(err -> log.error("Failed to create Redis Search index: {}", err.getMessage()));
    }
}
