package com.llmops.gateway.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.OutboxEvent;
import com.llmops.gateway.kafka.ChatCompletedEvent;
import com.llmops.gateway.kafka.ChatEventProducer;
import com.llmops.gateway.repository.OutboxEventRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scheduled background worker that polls the {@code outbox_events} table
 * for unpublished events and publishes them to the Kafka cluster.
 *
 * <p>Ensures "at-least-once" delivery of events. If the application crashes
 * before updating the database flag but after publishing to Kafka, the message
 * will be re-sent upon restart. Consumer idempotency handles duplicates.</p>
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ChatEventProducer chatEventProducer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    public OutboxPoller(
            OutboxEventRepository outboxEventRepository,
            ChatEventProducer chatEventProducer,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.chatEventProducer = chatEventProducer;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.poller.delay-ms:2000}")
    public void pollAndPublish() {
        for (int shard = 0; shard < 2; shard++) {
            final int currentShard = shard;
            executorService.submit(() -> {
                com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey routeKey = (currentShard == 0)
                        ? com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey.SHARD_0_WRITE
                        : com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey.SHARD_1_WRITE;
                
                com.llmops.gateway.sharding.DataSourceContextHolder.setRoute(routeKey);
                try {
                    // Try to acquire advisory lock. 42000 is an arbitrary lock namespace, adding shard for uniqueness.
                    Boolean lockAcquired = jdbcTemplate.queryForObject(
                            "SELECT pg_try_advisory_lock(?)", Boolean.class, 42000 + currentShard);
                    if (Boolean.TRUE.equals(lockAcquired)) {
                        try {
                            pollAndPublishForCurrentRoute(currentShard);
                        } finally {
                            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, 42000 + currentShard);
                        }
                    } else {
                        log.debug("Could not acquire advisory lock for shard {}. Skipping cycle.", currentShard);
                    }
                } catch (Exception e) {
                    log.error("Error during poll cycle on shard {}", currentShard, e);
                } finally {
                    com.llmops.gateway.sharding.DataSourceContextHolder.clear();
                }
            });
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down OutboxPoller executor service...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Executor service did not terminate in the specified time.");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Executor service shutdown interrupted.");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void pollAndPublishForCurrentRoute(int shardIndex) {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop20ByPublishedFalseOrderByCreatedAtAsc();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to publish to Kafka on shard {}.", pendingEvents.size(), shardIndex);

        for (OutboxEvent event : pendingEvents) {
            try {
                // Deserialize payload to verify integrity
                ChatCompletedEvent payload = objectMapper.readValue(
                        event.getPayload(), ChatCompletedEvent.class);

                // Publish to Kafka synchronously — only mark published after broker ack
                chatEventProducer.publishSync(payload);

                // Mark as published in DB
                event.setPublished(true);
                outboxEventRepository.save(event);

                log.info("Outbox event {} successfully published and marked on shard {}.", event.getId(), shardIndex);
            } catch (Exception e) {
                log.error("Failed to process outbox event {} on shard {}: {}. Will retry in next poll.",
                        event.getId(), shardIndex, e.getMessage());
            }
        }
        
        // Log remaining backlog count
        long remaining = outboxEventRepository.countByPublishedFalse();
        log.info("Processed {} outbox events on shard {}. Remaining backlog: {}", pendingEvents.size(), shardIndex, remaining);
    }
}
