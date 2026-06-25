package com.platform.counter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.event.CounterSnapshotEvent;
import com.platform.counter.repository.CounterSnapshotOutboxRepository;
import java.time.LocalDateTime;
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
 *
 * <p>The scheduler now also appends a counter snapshot to the outbox after each successful ARTICLE
 * flush, so the tests inject a {@link RecordingSnapshotOutbox} to assert that snapshot behavior.
 */
class CounterFlushSchedulerTest {

    private static CounterProperties properties(long minMs, long maxMs, int batchSize) {
        return new CounterProperties(
                new CounterProperties.Kafka(
                        "counter-events", "counter-retry", "counter-dlq",
                        "counter-aggregate-group", "counter-aggregate-retry-group",
                        "counter-relation-group", "content-events", "counter-content-group",
                        "counter-snapshot-events", "counter-snapshot-relay-group"),
                new CounterProperties.Flush("adaptive", 1000L, minMs, maxMs, batchSize));
    }

    @Test
    void flushPendingBatchDrainsAndFlushesEachTag() {
        FakeCounterStore store = new FakeCounterStore(
                Arrays.asList("ARTICLE:1", "USER:2"), 0L);
        CounterFlushScheduler scheduler = newScheduler(store, properties(500L, 5000L, 1000));

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
        CounterFlushScheduler scheduler = newScheduler(store, properties(500L, 5000L, 1000));

        scheduler.flushPendingBatch();

        // The malformed middle tag is skipped; the two well-formed tags still flush.
        assertThat(store.flushed).hasSize(2);
        assertThat(store.flushed.get(0)).isEqualTo(new FlushCall(CounterEntityType.ARTICLE, 1L));
        assertThat(store.flushed.get(1)).isEqualTo(new FlushCall(CounterEntityType.USER, 2L));
    }

    @Test
    void appendsCounterSnapshotOnlyAfterArticleFlush() {
        // ARTICLE:1 and USER:2 both flush; only the article flush produces a snapshot (Search only
        // ranks articles). The snapshot reflects the counters read AFTER flushOne.
        FakeCounterStore store = new FakeCounterStore(
                Arrays.asList("ARTICLE:1", "USER:2"), 0L);
        RecordingSnapshotOutbox snapshotOutbox = new RecordingSnapshotOutbox();
        CounterReadService readService = new CounterReadService(store);
        CounterFlushScheduler scheduler = new CounterFlushScheduler(
                store, properties(500L, 5000L, 1000), readService, snapshotOutbox);

        scheduler.flushPendingBatch();

        assertThat(snapshotOutbox.appended).hasSize(1);
        CounterSnapshotEvent snapshot = snapshotOutbox.appended.get(0);
        assertThat(snapshot.entityType()).isEqualTo("ARTICLE");
        assertThat(snapshot.entityId()).isEqualTo(1L);
        // Counters come from FakeCounterStore.readCounts(ARTICLE, 1) = {LIKE:10, FAV:2, VIEW:99, ...}.
        assertThat(snapshot.likeCount()).isEqualTo(10L);
        assertThat(snapshot.favoriteCount()).isEqualTo(2L);
        assertThat(snapshot.viewCount()).isEqualTo(99L);
    }

    @Test
    void snapshotFailureDoesNotBreakFlushBatch() {
        // If the snapshot outbox throws, the flush of the remaining tags must still complete — the
        // flush itself already succeeded, and a snapshot miss is self-healing on the next flush.
        FakeCounterStore store = new FakeCounterStore(
                Arrays.asList("ARTICLE:1", "ARTICLE:2"), 0L);
        RecordingSnapshotOutbox snapshotOutbox = new RecordingSnapshotOutbox();
        snapshotOutbox.throwOnAppend = true;
        CounterFlushScheduler scheduler = newScheduler(store, snapshotOutbox, properties(500L, 5000L, 1000));

        scheduler.flushPendingBatch();

        // Both flushes still happened despite the snapshot outbox throwing on the first.
        assertThat(store.flushed).hasSize(2);
    }

    @Test
    void adaptiveIntervalClampsToMinAndMax() {
        long minMs = 500L;
        long maxMs = 5000L;

        // Huge pending load -> clamps to min interval.
        FakeCounterStore high = new FakeCounterStore(List.of(), 10_000L);
        CounterFlushScheduler highScheduler = newScheduler(high, properties(minMs, maxMs, 1000));
        assertThat(highScheduler.effectiveIntervalMs()).isEqualTo(minMs);

        // Zero pending load -> clamps to max interval.
        FakeCounterStore low = new FakeCounterStore(List.of(), 0L);
        CounterFlushScheduler lowScheduler = newScheduler(low, properties(minMs, maxMs, 1000));
        assertThat(lowScheduler.effectiveIntervalMs()).isEqualTo(maxMs);
    }

    @Test
    void fixedModeReturnsFixedInterval() {
        FakeCounterStore store = new FakeCounterStore(List.of(), 10_000L);
        CounterProperties fixed = new CounterProperties(
                new CounterProperties.Kafka(
                        "counter-events", "counter-retry", "counter-dlq",
                        "counter-aggregate-group", "counter-aggregate-retry-group",
                        "counter-relation-group", "content-events", "counter-content-group",
                        "counter-snapshot-events", "counter-snapshot-relay-group"),
                new CounterProperties.Flush("fixed", 1234L, 500L, 5000L, 1000));
        CounterFlushScheduler scheduler = newScheduler(store, fixed);

        assertThat(scheduler.effectiveIntervalMs()).isEqualTo(1234L);
    }

    // --- helpers --------------------------------------------------------------

    private static CounterFlushScheduler newScheduler(FakeCounterStore store, CounterProperties props) {
        return new CounterFlushScheduler(store, props, new CounterReadService(store), new RecordingSnapshotOutbox());
    }

    private static CounterFlushScheduler newScheduler(FakeCounterStore store,
                                                      RecordingSnapshotOutbox outbox,
                                                      CounterProperties props) {
        return new CounterFlushScheduler(store, props, new CounterReadService(store), outbox);
    }

    private record FlushCall(CounterEntityType etype, Long eid) {}

    /**
     * Fake {@link CounterStore} with a configurable pending batch to drain (consumed once), a
     * configurable {@code pendingCount} for the adaptive interval math, and a record of every
     * {@code flushOne} call. {@code readCounts} returns distinguishable ARTICLE counters so the
     * snapshot assertions are meaningful.
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
            return readCounts(etype, eid).getOrDefault(metric, 0L);
        }

        @Override
        public Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid) {
            if (etype == CounterEntityType.ARTICLE) {
                return Map.of(
                        CounterMetric.LIKE, 10L,
                        CounterMetric.FAV, 2L,
                        CounterMetric.VIEW, 99L,
                        CounterMetric.COMMENT, 4L,
                        CounterMetric.SHARE, 1L);
            }
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

    /** Records appended snapshots; can be primed to throw on append. */
    private static final class RecordingSnapshotOutbox implements CounterSnapshotOutboxRepository {
        final List<CounterSnapshotEvent> appended = new ArrayList<>();
        boolean throwOnAppend;

        @Override
        public void append(CounterSnapshotEvent event) {
            if (throwOnAppend) {
                throw new RuntimeException("outbox down");
            }
            appended.add(event);
        }

        @Override
        public List<OutboxRow> findUnpublished(int limit) {
            return List.of();
        }

        @Override
        public void markPublished(Long id, LocalDateTime publishedAt) {}
    }
}
