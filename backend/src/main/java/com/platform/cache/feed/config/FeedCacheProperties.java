package com.platform.cache.feed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.cache.feed")
public record FeedCacheProperties(
        L2 l2, L1 l1, L0 l0,
        double jitterRatio,
        long reconciliationIntervalMs,
        int singleFlightLockWaitMs,
        int singleFlightLockTtlSeconds
) {
    public record L2(int headTtlSeconds, int cursorTtlSeconds, int maxSize) {}
    public record L1(int headTtlSeconds, int cursorTtlSeconds) {}
    public record L0(int ttlSeconds) {}
}
