package com.llmops.gateway.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Spring Kafka configuration class.
 *
 * <p>Configures a DefaultErrorHandler to retry failed message deliveries 3 times
 * with a 2-second delay, and then route them to the Dead Letter Queue (DLQ)
 * topic (defaulting to topicName.DLT) using DeadLetterPublishingRecoverer.</p>
 *
 * <p>Also explicitly defines the topic topology:
 * <ul>
 *   <li><b>chat-completed</b>: 3 partitions (enables load-balancing across consumer group),
 *       1 replication factor (for local/minikube environment), 7-day retention.</li>
 *   <li><b>chat-completed.DLT</b>: 1 partition, 1 replication factor, 14-day retention.</li>
 * </ul>
 * </p>
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public NewTopic chatCompletedTopic() {
        log.info("Creating chat-completed topic: 3 partitions, 7 days retention...");
        return TopicBuilder.name("chat-completed")
                .partitions(3)
                .replicas(1)
                .configs(Map.of(
                        "retention.ms", "604800000" // 7 days in milliseconds
                ))
                .build();
    }

    @Bean
    public NewTopic chatCompletedDltTopic() {
        log.info("Creating chat-completed.DLT topic: 1 partition, 14 days retention...");
        return TopicBuilder.name("chat-completed.DLT")
                .partitions(1)
                .replicas(1)
                .configs(Map.of(
                        "retention.ms", "1209600000" // 14 days in milliseconds
                ))
                .build();
    }

    @Bean
    public CommonErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
        log.info("Registering resilient Kafka DefaultErrorHandler with DeadLetterPublishingRecoverer...");

        // Recoverer publishes failed events to the <topic>.DLT topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        // Fixed back-off: 3 retry attempts, 2-second interval
        FixedBackOff backOff = new FixedBackOff(2000L, 3);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        
        // Log exceptions when they occur
        errorHandler.setCommitRecovered(true);
        
        return errorHandler;
    }
}
