package com.platform.counter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.counter")
public record CounterProperties(Kafka kafka, Flush flush) {

    public record Kafka(
            String eventsTopic,
            String retryTopic,
            String dlqTopic,
            String consumerGroup,
            String retryConsumerGroup,
            String relationConsumerGroup,
            String contentEventsTopic,
            String contentConsumerGroup
    ) {}

    public record Flush(
            String mode,            // "fixed" | "adaptive"
            long fixedIntervalMs,
            long minIntervalMs,
            long maxIntervalMs,
            int batchSize
    ) {}
}
