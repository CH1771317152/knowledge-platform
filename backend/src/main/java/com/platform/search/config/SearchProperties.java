package com.platform.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.search")
public record SearchProperties(
        boolean enabled,
        Index index,
        Cursor cursor,
        Rank rank,
        Kafka kafka,
        Rebuild rebuild) {

    public record Index(
            String readAlias,
            String writeAlias,
            String initialIndex,
            int bodyMaxChars,
            int ossReadTimeoutMs,
            int ossMaxConcurrency) {}

    public record Cursor(String secret, long ttlSeconds) {
        public java.time.Duration ttl() {
            return java.time.Duration.ofSeconds(ttlSeconds);
        }
    }

    public record Rank(
            double titleBoost,
            double descriptionBoost,
            double bodyBoost,
            double favoriteWeight,
            double likeWeight,
            double viewWeight,
            double recencyWeight) {}

    public record Kafka(
            String contentConsumerGroup,
            String contentRetryTopic,
            String contentDlqTopic,
            String counterSnapshotTopic,
            String counterConsumerGroup,
            String counterRetryTopic,
            String counterDlqTopic) {}

    public record Rebuild(int scanBatchSize, int bulkBatchSize) {}
}
