package com.llmops.gateway.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmops.gateway.entity.OutboxEvent;
import com.llmops.gateway.kafka.ChatCompletedEvent;
import com.llmops.gateway.kafka.ChatEventProducer;
import com.llmops.gateway.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public OutboxPoller(
            OutboxEventRepository outboxEventRepository,
            ChatEventProducer chatEventProducer,
            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.chatEventProducer = chatEventProducer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.poller.delay-ms:2000}")
    public void pollAndPublish() {
        for (int shard = 0; shard < 2; shard++) {
            com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey routeKey = (shard == 0)
                    ? com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey.SHARD_0_WRITE
                    : com.llmops.gateway.sharding.DataSourceContextHolder.RouteKey.SHARD_1_WRITE;
            
            com.llmops.gateway.sharding.DataSourceContextHolder.setRoute(routeKey);
            try {
                pollAndPublishForCurrentRoute(shard);
            } finally {
                com.llmops.gateway.sharding.DataSourceContextHolder.clear();
            }
        }
    }

    private void pollAndPublishForCurrentRoute(int shardIndex) {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to publish to Kafka on shard {}.", pendingEvents.size(), shardIndex);

        for (OutboxEvent event : pendingEvents) {
            try {
                // Deserialize payload to verify integrity
                ChatCompletedEvent payload = objectMapper.readValue(
                        event.getPayload(), ChatCompletedEvent.class);

                // Publish to Kafka via the producer
                chatEventProducer.publish(payload);

                // Mark as published in DB
                event.setPublished(true);
                outboxEventRepository.save(event);

                log.info("Outbox event {} successfully published and marked on shard {}.", event.getId(), shardIndex);
            } catch (Exception e) {
                log.error("Failed to process outbox event {} on shard {}: {}. Will retry in next poll.",
                        event.getId(), shardIndex, e.getMessage());
            }
        }
    }
}
