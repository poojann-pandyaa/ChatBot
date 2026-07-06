package com.llmops.gateway.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

/**
 * Consumer for the Dead Letter Queue (DLQ) topic: {@code chat-completed.DLT}.
 *
 * <p>Receives messages that failed to be processed by other consumers after
 * maximum retry attempts. Acts as the resilience fall-through to prevent silent
 * message drop.</p>
 */
@Service
public class DLQConsumer {

    private static final Logger log = LoggerFactory.getLogger(DLQConsumer.class);

    /**
     * Listens to the dead letter topic and logs the poison pill message.
     */
    @KafkaListener(
            topics = "chat-completed.DLT",
            groupId = "dlq-monitoring-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String rawPayload,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset,
                        @Header(name = "x-exception-message", required = false) String exceptionMessage) {
        log.error("POISON PILL RECEIVED IN DLQ (topic: chat-completed.DLT, partition: {}, offset: {}). " +
                        "Exception: {}. Raw Payload: {}",
                partition, offset, exceptionMessage, rawPayload);
        // extension point: notify slack/sentry, store in long-term archive
    }
}
