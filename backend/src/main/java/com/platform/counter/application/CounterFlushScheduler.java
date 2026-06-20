package com.platform.counter.application;

import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dual-trigger flush scheduler. A fixed-rate {@code @Scheduled} tick drains pending agg tags from
 * the Redis agg set (via {@link CounterStore#drainPendingBatch(int)}) and flushes each into its
 * CountInt blob atomically via {@link CounterStore#flushOne}. The fixed rate is just a wake-up —
 * the real cadence is governed by {@code effectiveIntervalMs()}, which is either the configured
 * fixed interval or an adaptive interval interpolated from the pending-agg load (v1: linear between
 * {@code lo}/{@code hi} pending thresholds mapping to {@code maxIntervalMs}/{@code minIntervalMs}).
 *
 * <p>Gated with {@code @Profile("!test & !integration")} so no live {@code @Scheduled} task ever
 * fires in automated tests; unit tests construct the scheduler directly with a fake
 * {@link CounterStore} and call {@link #flushPendingBatch()} / {@link #effectiveIntervalMs()}.
 */
@Component
@Profile("!test & !integration")
public class CounterFlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(CounterFlushScheduler.class);

    /** Pending-agg thresholds for the v1 adaptive interval (tuning is TODO). */
    static final long HIGH_PENDING = 5000L;
    static final long LOW_PENDING = 500L;

    private final CounterStore store;
    private final CounterProperties properties;

    private volatile long lastFlush = 0L;

    public CounterFlushScheduler(CounterStore store, CounterProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Fixed-rate wake-up. Returns immediately if not enough time has elapsed since the last flush;
     * otherwise stamps {@code lastFlush} and drains a batch.
     */
    @Scheduled(fixedRateString = "${platform.counter.flush.min-interval-ms:500}")
    public void tick() {
        long now = System.currentTimeMillis();
        long effective = effectiveIntervalMs();
        if (now - lastFlush < effective) {
            return;
        }
        lastFlush = now;
        flushPendingBatch();
    }

    /**
     * Effective flush interval. In {@code fixed} mode this is just the configured fixed interval.
     * In {@code adaptive} mode it interpolates linearly from {@code maxIntervalMs} (low load) to
     * {@code minIntervalMs} (high load) across the {@code LOW_PENDING}..{@code HIGH_PENDING} range.
     */
    long effectiveIntervalMs() {
        if ("fixed".equalsIgnoreCase(properties.flush().mode())) {
            return properties.flush().fixedIntervalMs();
        }
        long pending = store.pendingCount();
        long min = properties.flush().minIntervalMs();
        long max = properties.flush().maxIntervalMs();
        if (pending >= HIGH_PENDING) {
            return min;
        }
        if (pending <= LOW_PENDING) {
            return max;
        }
        double ratio = (double) (pending - LOW_PENDING) / (HIGH_PENDING - LOW_PENDING);
        return Math.round(max - ratio * (max - min));
    }

    /**
     * Drains up to {@code batchSize} pending agg tags (e.g. {@code "ARTICLE:123"}) and flushes each
     * into its CountInt. A single malformed tag is logged and skipped — the tag is already drained
     * from the agg set, so reconciliation (TODO) recovers it; the rest of the batch still flushes.
     */
    void flushPendingBatch() {
        List<String> tags = store.drainPendingBatch(properties.flush().batchSize());
        for (String tag : tags) {
            try {
                int sep = tag.indexOf(':');
                CounterEntityType etype = CounterEntityType.valueOf(tag.substring(0, sep));
                Long eid = Long.valueOf(tag.substring(sep + 1));
                store.flushOne(etype, eid);
            } catch (Exception e) {
                log.warn("Skipping malformed counter agg tag during flush: '{}' ({})", tag, e.getMessage());
            }
        }
    }
}
