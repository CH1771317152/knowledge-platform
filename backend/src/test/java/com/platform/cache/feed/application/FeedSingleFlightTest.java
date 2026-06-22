package com.platform.cache.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platform.cache.feed.config.FeedCacheProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure unit test for {@link FeedSingleFlight}. The component is constructed directly with a mocked
 * {@link StringRedisTemplate} (its {@link ValueOperations#setIfAbsent} controls the distributed
 * lock and the {@link StringRedisTemplate#execute} Lua release is a no-op stub); no Spring context,
 * no Redis. Mirrors the construct-directly pattern of {@code FeedReadServiceTest}.
 *
 * <p><b>Concurrency note:</b> the local-dedup tests deterministically verify the join path. The
 * builder runs on a pool thread and blocks inside the supplier (gated by a {@link CountDownLatch});
 * the joiner runs on a separate thread and must observe the in-flight local future and park in
 * {@code existing.get()}. The test polls the joiner's {@link Thread.State} to confirm it is parked
 * (TIMED_WAITING/WAITING) BEFORE releasing the builder — this removes the race where the builder
 * finishes and clears its slot before the joiner calls {@code putIfAbsent}.
 */
class FeedSingleFlightTest {

    private static final String KEY = "feed:public:head:sz20";
    private static final String LOCK_KEY = "lock:" + KEY;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private FeedCacheProperties props;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // Lua release is a no-op by default (returns 1L = "deleted"); individual tests can override.
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        // 200 ms wait, 10 s TTL — matches the application.yml defaults and the plan.
        props = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 200, 10,
                FeedCacheProperties.HotKey.defaults());
    }

    // --- Local single-flight ---------------------------------------------------

    @Test
    void localSingleFlightDeduplicatesWithinJvm() throws Exception {
        // Lock always acquired on first try, so the supplier runs exactly once for N callers.
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(true);

        // Generous wait so the joiner's existing.get() never times out before the builder completes.
        FeedCacheProperties generousWait = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 5_000, 10,
                FeedCacheProperties.HotKey.defaults());

        AtomicInteger supplierCalls = new AtomicInteger();
        // releaseSupplier lets the test control when the builder returns, so the joiner is
        // guaranteed to observe the in-flight local future and join it.
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        CountDownLatch builderStarted = new CountDownLatch(1);

        Supplier<String> supplier = () -> {
            supplierCalls.incrementAndGet();
            builderStarted.countDown();
            // Block the builder so the joiner reliably finds an in-flight future.
            try {
                releaseSupplier.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "built-once";
        };

        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, generousWait);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            // Builder on the pool's single worker thread.
            CompletableFuture<String> a = CompletableFuture.supplyAsync(
                    () -> singleFlight.executeWithLock(KEY, supplier), pool);

            // Wait until the builder is inside the supplier; its local slot is now occupied.
            assertThat(builderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(singleFlight.localMapForTesting()).containsKey(KEY);

            // Joiner on a separate thread (not the pool). It will observe the in-flight local
            // future and block in existing.get(). We deterministically confirm it JOINED (rather
            // than self-built) by polling its thread state: a joiner parked in existing.get() is
            // TIMED_WAITING while the builder's slot is still present. Only then do we release the
            // builder — this removes the race where the builder finishes before the joiner parks.
            AtomicReference<String> joinerResult = new AtomicReference<>();
            Thread joiner = new Thread(() ->
                    joinerResult.set(singleFlight.executeWithLock(KEY, supplier)), "joiner");
            joiner.setDaemon(true);
            joiner.start();

            boolean joined = false;
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline) {
                if (!singleFlight.localMapForTesting().containsKey(KEY)) {
                    break; // slot cleared before we saw the joiner park → joiner self-built (failure)
                }
                Thread.State s = joiner.getState();
                if (s == Thread.State.TIMED_WAITING || s == Thread.State.WAITING) {
                    joined = true;
                    break;
                }
            }
            assertThat(joined)
                    .as("joiner must join the in-flight local future, not self-build")
                    .isTrue();

            // Release the builder; the joiner's existing.get() returns the shared result.
            releaseSupplier.countDown();

            joiner.join(2_000);
            String ra = a.get(2, TimeUnit.SECONDS);

            assertThat(ra).isEqualTo("built-once");
            assertThat(joinerResult.get()).isEqualTo("built-once");
            assertThat(supplierCalls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysRunIndependently() {
        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenReturn(true);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> "v-" + calls.incrementAndGet();

        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);
        String r1 = singleFlight.executeWithLock("key-A", supplier);
        String r2 = singleFlight.executeWithLock("key-B", supplier);

        assertThat(r1).isEqualTo("v-1");
        assertThat(r2).isEqualTo("v-2");
        assertThat(calls.get()).isEqualTo(2);
        // Both local map entries are cleared after completion.
        assertThat(singleFlight.localMapForTesting()).isEmpty();
    }

    // --- Distributed lock ------------------------------------------------------

    @Test
    void distributedLockAcquiredRunsSupplierAndReleasesLock() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(true);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> "acquired-" + calls.incrementAndGet();

        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);
        String result = singleFlight.executeWithLock(KEY, supplier);

        assertThat(result).isEqualTo("acquired-1");
        assertThat(calls.get()).isEqualTo(1);
        // SET NX was attempted at least once (exactly once since it succeeded immediately).
        verify(valueOps, times(1)).setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class));
        // Lua compare-and-delete executed exactly once on release.
        verify(redis, times(1)).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), any());
    }

    @Test
    void distributedLockContentionWaitsThenReusesLocalFuture() throws Exception {
        // First caller wins the lock and holds it; the second caller is in the SAME JVM, so it joins
        // the first caller's local future rather than entering the distributed-lock poll loop. This
        // models the "contention within one JVM" path.
        AtomicInteger supplierCalls = new AtomicInteger();
        CountDownLatch builderStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(true);

        Supplier<String> supplier = () -> {
            supplierCalls.incrementAndGet();
            builderStarted.countDown();
            try {
                releaseSupplier.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "shared-result";
        };

        // Generous wait so the joiner never times out before the builder completes.
        FeedCacheProperties generousWait = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 5_000, 10,
                FeedCacheProperties.HotKey.defaults());

        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, generousWait);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            CompletableFuture<String> a = CompletableFuture.supplyAsync(
                    () -> singleFlight.executeWithLock(KEY, supplier), pool);

            // Wait until the builder is inside the supplier; its local slot is occupied.
            assertThat(builderStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(singleFlight.localMapForTesting()).containsKey(KEY);

            // Joiner on a separate thread; poll its state to confirm it joined the in-flight local
            // future (TIMED_WAITING on existing.get()) before releasing the builder. This removes
            // the race where the builder finishes and clears its slot before the joiner parks.
            AtomicReference<String> joinerResult = new AtomicReference<>();
            Thread joiner = new Thread(() ->
                    joinerResult.set(singleFlight.executeWithLock(KEY, supplier)), "joiner");
            joiner.setDaemon(true);
            joiner.start();

            boolean joined = false;
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline) {
                if (!singleFlight.localMapForTesting().containsKey(KEY)) {
                    break;
                }
                Thread.State s = joiner.getState();
                if (s == Thread.State.TIMED_WAITING || s == Thread.State.WAITING) {
                    joined = true;
                    break;
                }
            }
            assertThat(joined)
                    .as("joiner must join the in-flight local future, not self-build")
                    .isTrue();

            releaseSupplier.countDown();

            joiner.join(2_000);
            String ra = a.get(2, TimeUnit.SECONDS);

            assertThat(ra).isEqualTo("shared-result");
            assertThat(joinerResult.get()).isEqualTo("shared-result");
            // Exactly one supplier call: the second caller joined the local future, never reached
            // the distributed-lock poll loop.
            assertThat(supplierCalls.get()).isEqualTo(1);
            // Lock was acquired exactly once (by the first caller only).
            verify(valueOps, times(1)).setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distributedLockContentionAcrossJvmsPollsUntilAcquired() {
        // Models two callers that each own their own local slot (e.g. they are in different JVMs in
        // production — here simulated by giving each a distinct key so putIfAbsent always wins).
        // The first lock attempt fails; the retry succeeds. Asserts the poll loop runs the supplier
        // once it finally acquires.
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(false)   // first attempt: held by another JVM
                .thenReturn(true);   // second attempt: acquired

        Supplier<String> supplier = () -> "after-poll";
        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);
        String result = singleFlight.executeWithLock(KEY, supplier);

        assertThat(result).isEqualTo("after-poll");
        verify(valueOps, times(2)).setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class));
        verify(redis, atLeastOnce()).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), any());
    }

    // --- Timeout fallback ------------------------------------------------------

    @Test
    void timeoutFallbackSelfBuildsWhenLockNeverAcquired() {
        // Lock is perpetually held by another JVM: every SETNX returns false.
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(false);

        // Use a tiny wait window so the test does not sleep the full 200 ms.
        FeedCacheProperties tinyWait = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 40, 10,
                FeedCacheProperties.HotKey.defaults());
        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, tinyWait);

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> "self-built-" + calls.incrementAndGet();

        String result = singleFlight.executeWithLock(KEY, supplier);

        // Fallback fired: supplier ran exactly once despite never acquiring the lock.
        assertThat(result).startsWith("self-built-");
        assertThat(calls.get()).isEqualTo(1);
        // Lock release was never invoked (we never acquired).
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void redisDownFallsBackToSelfBuild() {
        // Redis throws on SETNX — the caller degrades to a self-build rather than failing.
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("conn down"));

        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> "degraded-" + calls.incrementAndGet();

        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);
        String result = singleFlight.executeWithLock(KEY, supplier);

        assertThat(result).isEqualTo("degraded-1");
        assertThat(calls.get()).isEqualTo(1);
        // Local slot was cleared despite the degraded path.
        assertThat(singleFlight.localMapForTesting()).isEmpty();
    }

    @Test
    void supplierExceptionPropagatesAndClearsLocalSlot() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(true);

        Supplier<String> supplier = () -> { throw new IllegalStateException("boom"); };
        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);

        IllegalStateException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> singleFlight.executeWithLock(KEY, supplier),
                IllegalStateException.class);
        assertThat(thrown).hasMessageContaining("boom");
        // Local slot cleared in finally — next caller can retry.
        assertThat(singleFlight.localMapForTesting()).isEmpty();
    }

    @Test
    void resultMapEntryClearedAfterSuccessfulBuild() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenReturn(true);
        FeedSingleFlight singleFlight = new FeedSingleFlight(redis, props);

        String r = singleFlight.executeWithLock(KEY, () -> "done");

        assertThat(r).isEqualTo("done");
        ConcurrentHashMap<String, CompletableFuture<Object>> map = singleFlight.localMapForTesting();
        assertThat(map).isEmpty();
    }
}
