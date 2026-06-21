package com.platform.counter.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.dto.ArticleCountersResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link CounterReadService}. The service is constructed directly with an
 * in-memory {@link FakeCounterStore} that returns a fixed {@code readCounts} map and a fixed
 * {@code hasActed} result. Mirrors the {@code @Profile("!test")} / construct-directly pattern of
 * {@code CounterFactServiceTest}.
 */
class CounterReadServiceTest {

    private static final Long POST_ID = 123L;
    private static final long USER = 7L;

    private FakeCounterStore store;
    private CounterReadService service;

    @BeforeEach
    void setUp() {
        store = new FakeCounterStore();
        service = new CounterReadService(store);
    }

    @Test
    void getArticleCountersMapsPresentMetrics() {
        store.counts = Map.of(
                CounterMetric.LIKE, 10L,
                CounterMetric.FAV, 3L,
                CounterMetric.VIEW, 500L,
                CounterMetric.COMMENT, 7L,
                CounterMetric.SHARE, 2L);

        ArticleCountersResponse response = service.getArticleCounters(POST_ID);

        assertThat(response.postId()).isEqualTo(POST_ID);
        assertThat(response.like()).isEqualTo(10L);
        assertThat(response.fav()).isEqualTo(3L);
        assertThat(response.view()).isEqualTo(500L);
        assertThat(response.comment()).isEqualTo(7L);
        assertThat(response.share()).isEqualTo(2L);
    }

    @Test
    void getArticleCountersDefaultsAbsentMetricsToZero() {
        // An entity that has only been liked + viewed: fav/comment/share absent → 0.
        store.counts = Map.of(
                CounterMetric.LIKE, 4L,
                CounterMetric.VIEW, 99L);

        ArticleCountersResponse response = service.getArticleCounters(POST_ID);

        assertThat(response.like()).isEqualTo(4L);
        assertThat(response.fav()).isZero();
        assertThat(response.view()).isEqualTo(99L);
        assertThat(response.comment()).isZero();
        assertThat(response.share()).isZero();
    }

    @Test
    void getArticleCountersAllZeroWhenMapEmpty() {
        // A never-interacted entity returns an empty map from the store → all zeros, no NPE.
        store.counts = Map.of();

        ArticleCountersResponse response = service.getArticleCounters(POST_ID);

        assertThat(response.like()).isZero();
        assertThat(response.fav()).isZero();
        assertThat(response.view()).isZero();
        assertThat(response.comment()).isZero();
        assertThat(response.share()).isZero();
    }

    @Test
    void hasActedDelegatesToStore() {
        store.hasActedResult = true;

        boolean result = service.hasActed(USER, CounterEntityType.ARTICLE, POST_ID, CounterMetric.LIKE);

        assertThat(result).isTrue();
        assertThat(store.lastHasActed).isEqualTo(new HasActedCall(
                CounterEntityType.ARTICLE, POST_ID, CounterMetric.LIKE, USER));

        store.hasActedResult = false;
        assertThat(service.hasActed(USER, CounterEntityType.ARTICLE, POST_ID, CounterMetric.FAV)).isFalse();
    }

    @Test
    void hasActedBatchDelegatesToStoreForEachMetricAndEid() {
        // The default CounterStore#hasActedBatch fans out to one hasActed per (metric, eid);
        // FakeCounterStore.hasActed returns hasActedResult for every call, so the batch result
        // should be the full cartesian product, keyed metric -> eid -> boolean.
        store.hasActedResult = true;
        List<Long> eids = List.of(11L, 22L, 33L);
        List<CounterMetric> metrics = List.of(CounterMetric.LIKE, CounterMetric.FAV);

        Map<CounterMetric, Map<Long, Boolean>> result =
                service.hasActedBatch(USER, CounterEntityType.ARTICLE, eids, metrics);

        assertThat(result).hasSize(2).containsOnlyKeys(CounterMetric.LIKE, CounterMetric.FAV);
        for (CounterMetric m : metrics) {
            assertThat(result.get(m)).hasSize(3).containsOnlyKeys(11L, 22L, 33L);
            for (Long eid : eids) {
                assertThat(result.get(m).get(eid)).isTrue();
            }
        }
        // The default fan-out should have issued exactly eids*metrics hasActed calls.
        assertThat(store.hasActedCallCount).isEqualTo(eids.size() * metrics.size());
    }

    // --- helpers --------------------------------------------------------------

    /** Records the arguments of the most recent {@link CounterStore#hasActed} call. */
    private record HasActedCall(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {}

    /**
     * Minimal fake {@link CounterStore} for the read service: {@code readCounts} and
     * {@code hasActedResult} are settable per test; the write-side methods are inert.
     */
    private static final class FakeCounterStore implements CounterStore {
        Map<CounterMetric, Long> counts = Map.of();
        boolean hasActedResult = false;
        HasActedCall lastHasActed;
        int hasActedCallCount = 0;

        @Override
        public Map<CounterMetric, Long> readCounts(CounterEntityType et, Long eid) {
            return counts;
        }

        @Override
        public boolean hasActed(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            lastHasActed = new HasActedCall(et, eid, metric, userId);
            hasActedCallCount++;
            return hasActedResult;
        }

        @Override
        public long readCount(CounterEntityType et, Long eid, CounterMetric metric) {
            return counts.getOrDefault(metric, 0L);
        }

        @Override
        public boolean setBitIfAbsent(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public boolean clearBitIfPresent(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public void addToAggregate(CounterEntityType et, Long eid, CounterMetric metric, long delta) {
        }

        @Override
        public List<String> drainPendingBatch(int n) {
            return List.of();
        }

        @Override
        public void flushOne(CounterEntityType et, Long eid) {
        }

        @Override
        public long pendingCount() {
            return 0L;
        }
    }
}
