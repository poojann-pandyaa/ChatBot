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
 * <p>Receives messages that failed to be processed by all other consumers after
 * maximum retry attempts. Acts as the resilience fall-through to prevent silent
 * message drops.</p>
 *
 * <p><b>DLQ Behavior:</b></p>
 * <ul>
 *   <li><b>Logging</b> — every poison-pill message is logged at ERROR level with
 *       the original exception, partition, offset and raw payload. This ensures
 *       operational visibility via any log aggregation stack (ELK, CloudWatch, etc.).</li>
 *   <li><b>No silent drop</b> — the message is durably retained in the DLQ topic
 *       subject to the topic's configured retention (default: 7 days). It can be
 *       replayed at any time using standard Kafka tooling.</li>
 *   <li><b>Manual replay</b> — to re-process, reset the consumer group offset for
 *       {@code dlq-monitoring-group} to the earliest offset, or use
 *       {@code rpk topic consume chat-completed.DLT | rpk topic produce chat-completed}
 *       for a targeted replay.</li>
 *   <li><b>Extension point</b> — the comment below marks where a Slack/PagerDuty
 *       alert or a long-term archive write (e.g. S3 / BigQuery) can be added.</li>
 * </ul>
 */
@Service
public class DLQConsumer {

    private static final Logger log = LoggerFactory.getLogger(DLQConsumer.class);

    @KafkaListener(
            topics = "chat-completed.DLT",
            groupId = "dlq-monitoring-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String rawPayload,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset,
                        @Header(name = "x-exception-message", required = false) String exceptionMessage) {
        log.error(
                "POISON PILL in DLQ [topic=chat-completed.DLT, partition={}, offset={}] " +
                "exception='{}' payload={}",
                partition, offset, exceptionMessage, rawPayload);

        // Extension point:
        // alertService.sendSlackAlert("DLQ event received", rawPayload);
        // archiveService.writeToS3("dlq-archive", rawPayload);
    }
}
