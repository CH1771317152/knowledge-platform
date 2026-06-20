package com.platform.counter.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.counter.application.CounterStore;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link RedisCounterStore} against a real Redis (localhost:6379) under the
 * {@code integration} profile. Each test uses a unique eid so Redis keys never collide.
 */
@SpringBootTest
@ActiveProfiles("integration")
class RedisCounterStoreIntegrationTest {

    @Autowired
    private CounterStore store;

    @Autowired
    private RedisCounterStore redisStore;

    @Autowired
    private StringRedisTemplate template;

    private final List<String> keysToClean = new ArrayList<>();

    private long uniqueEid() {
        long eid = 9_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits());
        keysToClean.add("cnt:" + CounterEntityType.ARTICLE + ":" + eid);
        keysToClean.add("agg:" + CounterEntityType.ARTICLE + ":" + eid);
        keysToClean.add(CounterEntityType.ARTICLE + ":" + eid);
        return eid;
    }

    private void noteBitmapKey(CounterEntityType t, long eid, CounterMetric m, long userId) {
        keysToClean.add("bm:" + m.name().toLowerCase() + ":" + t + ":" + eid + ":" + (userId / 262144L));
    }

    @AfterEach
    void cleanup() {
        if (!keysToClean.isEmpty()) {
            template.delete(keysToClean);
            // Also drop the ARTICLE:eid tag from the pending set in case a test left it.
            template.opsForSet().remove("counter:flush:pending", keysToClean.toArray());
            keysToClean.clear();
        }
    }

    @Test
    void bitmapSetBitIsIdempotentAndClears() {
        long eid = uniqueEid();
        long user = 1L;
        noteBitmapKey(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user);

        // First set: bit WAS 0 -> transition (true).
        assertThat(store.setBitIfAbsent(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isTrue();
        // Second set: bit WAS 1 -> no transition (false). Idempotent.
        assertThat(store.setBitIfAbsent(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isFalse();
        assertThat(store.hasActed(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isTrue();

        // Clear: bit WAS 1 -> transition (true).
        assertThat(store.clearBitIfPresent(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isTrue();
        assertThat(store.hasActed(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isFalse();
        // Clear again: bit WAS 0 -> no transition (false). Idempotent.
        assertThat(store.clearBitIfPresent(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, user)).isFalse();
    }

    @Test
    void flushDrainAtomicallyAppliesAggAndClearsIt() {
        long eid = uniqueEid();

        store.addToAggregate(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, 5);
        store.addToAggregate(CounterEntityType.ARTICLE, eid, CounterMetric.VIEW, 3);
        assertThat(store.pendingCount()).isGreaterThanOrEqualTo(1L);

        store.flushOne(CounterEntityType.ARTICLE, eid);

        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isEqualTo(5L);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.VIEW)).isEqualTo(3L);

        // A second flush with no new agg must not double-count (the DEL inside the script drained it).
        store.flushOne(CounterEntityType.ARTICLE, eid);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isEqualTo(5L);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.VIEW)).isEqualTo(3L);

        // readCounts returns all metrics for the etype.
        assertThat(store.readCounts(CounterEntityType.ARTICLE, eid))
                .containsEntry(CounterMetric.LIKE, 5L)
                .containsEntry(CounterMetric.VIEW, 3L);
    }

    @Test
    void luaConcurrentIncrIsAtomic() throws InterruptedException {
        long eid = uniqueEid();
        int n = 100;

        // Exercise the real concurrency path: N threads each addToAggregate(LIKE,1) (atomic HINCRBY),
        // then a single flushOne applies the sum. The Lua flush is atomic, so the final count == N.
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        store.addToAggregate(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, 1L);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        store.flushOne(CounterEntityType.ARTICLE, eid);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isEqualTo((long) n);

        // Also exercise counter-incr-at-offset.lua directly via the package-private hook:
        // concurrent direct increments of the FAV counter at offset 8 must land exactly.
        ExecutorService pool2 = Executors.newFixedThreadPool(16);
        CountDownLatch done2 = new CountDownLatch(n);
        try {
            for (int i = 0; i < n; i++) {
                pool2.submit(() -> {
                    try {
                        redisStore.incrAtOffset(CounterEntityType.ARTICLE, eid, CounterMetric.FAV, 1L);
                    } finally {
                        done2.countDown();
                    }
                });
            }
            assertThat(done2.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool2.shutdownNow();
        }
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.FAV)).isEqualTo((long) n);
    }

    @Test
    void negativeDeltaClampsToZero() {
        long eid = uniqueEid();

        store.addToAggregate(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, 5);
        store.flushOne(CounterEntityType.ARTICLE, eid);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isEqualTo(5L);

        // A delta that would drive the counter below zero must clamp at 0 (inside flush-drain.lua).
        store.addToAggregate(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, -100);
        store.flushOne(CounterEntityType.ARTICLE, eid);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isZero();

        // Same clamp holds on the direct incr path.
        redisStore.incrAtOffset(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE, -50);
        assertThat(store.readCount(CounterEntityType.ARTICLE, eid, CounterMetric.LIKE)).isZero();
    }

    @Test
    void drainPendingBatchReturnsTags() {
        long eidA = uniqueEid();
        long eidB = uniqueEid();

        store.addToAggregate(CounterEntityType.ARTICLE, eidA, CounterMetric.VIEW, 1);
        store.addToAggregate(CounterEntityType.ARTICLE, eidB, CounterMetric.VIEW, 1);

        List<String> drained = store.drainPendingBatch(10);
        // At least the two tags we just added should be present (the shared set may carry leftovers
        // from other tests, but ours must be included).
        assertThat(drained).contains(
                CounterEntityType.ARTICLE + ":" + eidA,
                CounterEntityType.ARTICLE + ":" + eidB);

        // After draining, popping again yields nothing for these tags.
        List<String> again = store.drainPendingBatch(10);
        assertThat(again).doesNotContain(
                CounterEntityType.ARTICLE + ":" + eidA,
                CounterEntityType.ARTICLE + ":" + eidB);
    }
}
