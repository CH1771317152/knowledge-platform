package com.platform.counter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Light unit tests for {@link CounterFlushScheduler}. The scheduler is
 * {@code @Profile("!test & !integration")} so no live {@code @Scheduled} task fires; these tests
 * exercise the flush logic directly via {@link CounterFlushScheduler#flushPendingBatch()} and the
 * adaptive interval math via {@link CounterFlushScheduler#effectiveIntervalMs()}.
 */
class CounterFlushSchedulerTest {

    private static CounterProperties properties(long minMs, long maxMs, int batchSize) {
        return new CounterProperties(
                new CounterProperties.Kafka(
                        "counter-events", "counter-retry", "counter-dlq",
                        "counter-aggregate-group", "counter-aggregate-retry-group",
                        "counter-relation-group", "content-events", "counter-content-group"),
                new CounterProperties.Flush("adaptive", 1000L, minMs, maxMs, batchSize));
    }

    @Test
    void flushPendingBatchDrainsAndFlushesEachTag() {
        FakeCounterStore store = new FakeCounterStore(
                Arrays.asList("ARTICLE:1", "USER:2"), 0L);
        CounterFlushScheduler scheduler = new CounterFlushScheduler(
                store, properties(500L, 5000L, 1000));

        scheduler.flushPendingBatch();

        assertThat(store.flushed).hasSize(2);
        assertThat(store.flushed.get(0)).isEqualTo(new FlushCall(CounterEntityType.ARTICLE, 1L));
        assertThat(store.flushed.get(1)).isEqualTo(new FlushCall(CounterEntityType.USER, 2L));
    }

    @Test
    void flushPendingBatchSkipsMalformedTagAndContinues() {
        // A bad tag in the middle; the surrounding tags must still flush.
        FakeCounterStore store = new FakeCounterStore(
                Arrays.asList("ARTICLE:1", "not-a-valid-tag", "USER:2"), 0L);
        CounterFlushScheduler scheduler = new CounterFlushScheduler(
                store, properties(500L, 5000L, 1000));

        scheduler.flushPendingBatch();

        // The malformed middle tag is skipped; the two well-formed tags still flush.
        assertThat(store.flushed).hasSize(2);
        assertThat(store.flushed.get(0)).isEqualTo(new FlushCall(CounterEntityType.ARTICLE, 1L));
        assertThat(store.flushed.get(1)).isEqualTo(new FlushCall(CounterEntityType.USER, 2L));
    }

    @Test
    void adaptiveIntervalClampsToMinAndMax() {
        long minMs = 500L;
        long maxMs = 5000L;

        // Huge pending load -> clamps to min interval.
        FakeCounterStore high = new FakeCounterStore(List.of(), 10_000L);
        CounterFlushScheduler highScheduler = new CounterFlushScheduler(
                high, properties(minMs, maxMs, 1000));
        assertThat(highScheduler.effectiveIntervalMs()).isEqualTo(minMs);

        // Zero pending load -> clamps to max interval.
        FakeCounterStore low = new FakeCounterStore(List.of(), 0L);
        CounterFlushScheduler lowScheduler = new CounterFlushScheduler(
                low, properties(minMs, maxMs, 1000));
        assertThat(lowScheduler.effectiveIntervalMs()).isEqualTo(maxMs);
    }

    @Test
    void fixedModeReturnsFixedInterval() {
        FakeCounterStore store = new FakeCounterStore(List.of(), 10_000L);
        CounterProperties fixed = new CounterProperties(
                new CounterProperties.Kafka(
                        "counter-events", "counter-retry", "counter-dlq",
                        "counter-aggregate-group", "counter-aggregate-retry-group",
                        "counter-relation-group", "content-events", "counter-content-group"),
                new CounterProperties.Flush("fixed", 1234L, 500L, 5000L, 1000));
        CounterFlushScheduler scheduler = new CounterFlushScheduler(store, fixed);

        assertThat(scheduler.effectiveIntervalMs()).isEqualTo(1234L);
    }

    // --- helpers --------------------------------------------------------------

    private record FlushCall(CounterEntityType etype, Long eid) {}

    /**
     * Fake {@link CounterStore} with a configurable pending batch to drain (consumed once), a
     * configurable {@code pendingCount} for the adaptive interval math, and a record of every
     * {@code flushOne} call.
     */
    private static final class FakeCounterStore implements CounterStore {
        private final List<String> pendingBatch;
        private final AtomicLong pending;
        final List<FlushCall> flushed = new ArrayList<>();

        FakeCounterStore(List<String> pendingBatch, long pendingCount) {
            this.pendingBatch = new ArrayList<>(pendingBatch);
            this.pending = new AtomicLong(pendingCount);
        }

        @Override
        public long readCount(CounterEntityType etype, Long eid, CounterMetric metric) {
            return 0;
        }

        @Override
        public Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid) {
            return Map.of();
        }

        @Override
        public boolean hasActed(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public boolean setBitIfAbsent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return true;
        }

        @Override
        public boolean clearBitIfPresent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public void addToAggregate(CounterEntityType etype, Long eid, CounterMetric metric, long delta) {
        }

        @Override
        public List<String> drainPendingBatch(int n) {
            // Hand back the configured batch once; decrement pending accordingly.
            List<String> drained = new ArrayList<>(pendingBatch);
            pendingBatch.clear();
            pending.set(0L);
            return drained;
        }

        @Override
        public void flushOne(CounterEntityType etype, Long eid) {
            flushed.add(new FlushCall(etype, eid));
        }

        @Override
        public long pendingCount() {
            return pending.get();
        }
    }
}
