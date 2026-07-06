package com.llmops.rag.service;

import com.llmops.rag.config.RedisConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

@SpringBootTest(classes = {RedisConfig.class})
@Testcontainers
@Disabled("Disabled because local Docker daemon is not active. Enable in environment with active Docker daemon.")
public class SemanticCacheIntegrationTest {

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:latest"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Test
    public void testRedisPing() {
        StepVerifier.create(redisTemplate.getConnectionFactory().getReactiveConnection().ping())
                .expectNext("PONG")
                .verifyComplete();
    }
}
