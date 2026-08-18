package com.llmops.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "rag.cache")
public class RagCacheProperties {
    
    private Map<String, Double> readThresholds = Map.of(
            "commonsense", 0.05,
            "adaptive", 0.08,
            "strategic", 0.08,
            "unknown", 0.05
    );

    private Map<String, Long> ttlSeconds = Map.of(
            "commonsense", 86400L,
            "adaptive", 43200L,
            "strategic", 3600L,
            "unknown", 86400L
    );

    public Map<String, Double> getReadThresholds() {
        return readThresholds;
    }

    public void setReadThresholds(Map<String, Double> readThresholds) {
        this.readThresholds = readThresholds;
    }

    public Map<String, Long> getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Map<String, Long> ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
