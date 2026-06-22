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
        int singleFlightLockTtlSeconds,
        HotKey hotKey
) {
    public FeedCacheProperties {
        if (hotKey == null) {
            hotKey = HotKey.defaults();
        }
        hotKey.validate();
    }

    public record L2(int headTtlSeconds, int cursorTtlSeconds, int maxSize) {}
    public record L1(int headTtlSeconds, int cursorTtlSeconds) {}
    public record L0(int ttlSeconds) {}

    public record HotKey(
            boolean enabled,
            int windowSeconds,
            int sliceSeconds,
            int maxTrackedKeys,
            double coldThresholdRatio,
            int lowThreshold,
            int highThreshold,
            int baseTtlSeconds,
            int mediumExtraTtlSeconds,
            int highExtraTtlSeconds,
            int jitterMinSeconds,
            int jitterMaxSeconds
    ) {
        public static HotKey defaults() {
            return new HotKey(true, 60, 10, 50_000, 0.5,
                    50, 200, 30, 60, 120, 5, 10);
        }

        public int bucketCount() {
            return windowSeconds / sliceSeconds;
        }

        public int coldThresholdTicks() {
            return Math.max(1, (int) Math.ceil(bucketCount() * coldThresholdRatio));
        }

        private void validate() {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.window-seconds must be > 0");
            }
            if (sliceSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.slice-seconds must be > 0");
            }
            if (windowSeconds % sliceSeconds != 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.window-seconds must be divisible by slice-seconds");
            }
            int buckets = bucketCount();
            if (buckets < 6 || buckets > 12) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key bucket count must be in [6, 12]");
            }
            if (maxTrackedKeys <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.max-tracked-keys must be > 0");
            }
            if (coldThresholdRatio <= 0.0 || coldThresholdRatio > 1.0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.cold-threshold-ratio must be in (0, 1]");
            }
            if (highThreshold <= lowThreshold) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.high-threshold must be greater than low-threshold");
            }
            if (baseTtlSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.base-ttl-seconds must be > 0");
            }
            if (mediumExtraTtlSeconds < 0 || highExtraTtlSeconds < 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key extra TTL seconds must be >= 0");
            }
            if (jitterMinSeconds < 0 || jitterMaxSeconds < jitterMinSeconds) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key jitter range is invalid");
            }
        }
    }
}
