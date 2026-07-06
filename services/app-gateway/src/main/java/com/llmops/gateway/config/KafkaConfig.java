package com.llmops.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka configuration class.
 *
 * <p>Configures a DefaultErrorHandler to retry failed message deliveries 3 times
 * with a 2-second delay, and then route them to the Dead Letter Queue (DLQ)
 * topic (defaulting to topicName.DLT) using DeadLetterPublishingRecoverer.</p>
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

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
