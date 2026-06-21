package com.platform.cache.feed.application;

import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Single-flight deduplication for the feed cache's source-backfill step (Task 8). Wraps the
 * expensive DB keyset query ({@code FeedSourceQuery.findPublicFeedAfter} / {@code findUserFeedAfter}
 * and the head variants) so that concurrent cache misses on the same page collapse onto a single
 * source call rather than stampeding MySQL.
 *
 * <p><b>Two layers of dedup:</b>
 * <ol>
 *   <li><b>Local single-flight (within one JVM):</b> a
 *       {@link ConcurrentHashMap} keyed by page key maps to a {@link CompletableFuture}. The first
 *       caller for a key installs its own future and runs the supplier; concurrent callers find the
 *       existing future and {@link CompletableFuture#get(java.time.Duration)} join its result. This
 *       collapses the herd at the process boundary.</li>
 *   <li><b>Distributed lock (across JVMs):</b> a Redis {@code SET lock:{key} {token} EX {ttl} NX}
 *       ensures only one JVM builds a given page at a time. Other JVMs poll every 20 ms up to
 *       {@link FeedCacheProperties#singleFlightLockWaitMs()}; if the local future completes in that
 *       window (because the lock holder published the result via L1), the waiter reuses it.
 *       On {@code singleFlightLockWaitMs} timeout the waiter falls back to self-build — the bounded
 *       herd: at most the lock-TTL window of duplicate builds, never an unbounded stampede.</li>
 * </ol>
 *
 * <p><b>Lock release:</b> a Lua compare-and-delete ({@link #RELEASE_LOCK_SCRIPT}) deletes the lock
 * key only if the stored value still equals this caller's token. This prevents a slow holder from
 * deleting a lock that a faster successor already re-acquired after TTL expiry.
 *
 * <p><b>What is wrapped:</b> only the source query itself — the expensive DB call. The L1 write and
 * L0 assembly are cheap and idempotent, so they are allowed to run concurrently per caller outside
 * the single-flight boundary (the caller decides what to put inside the supplier).
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} — depends on {@link StringRedisTemplate}. The unit
 * test ({@code FeedSingleFlightTest}) constructs the component directly with a mocked
 * {@link StringRedisTemplate}; no Spring context, no Redis.
 *
 * <p><b>Failure modes:</b>
 * <ul>
 *   <li>Supplier throws → the exception propagates to the local caller; the future is completed
 *       exceptionally so any in-flight waiters also see it, and the local map entry is cleared in
 *       the {@code finally} block so the next caller can retry.</li>
 *   <li>Redis is down → {@code setIfAbsent} throws; the caller falls back to self-build via the
 *       timeout path (the {@code finally} still clears local state). The cache degrades to "no
 *       single-flight" rather than failing the read.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class FeedSingleFlight {

    /** Poll interval while waiting for another JVM to release the distributed lock. */
    private static final long LOCK_POLL_INTERVAL_MS = 20L;

    /**
     * Lua compare-and-delete: delete {@code KEYS[1]} only if its current value equals
     * {@code ARGV[1]} (this caller's token). Returns 1 on deletion, 0 if the token no longer
     * matches (lock expired and was re-acquired by someone else). Prevents deleting a successor's
     * lock after our TTL expired.
     */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ConcurrentHashMap<String, CompletableFuture<Object>> local = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final FeedCacheProperties props;

    public FeedSingleFlight(StringRedisTemplate redis, FeedCacheProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Runs {@code supplier} under single-flight semantics for {@code key}.
     *
     * <p>The first caller (within this JVM) for a given key runs the supplier; concurrent callers
     * for the same key join the first caller's in-flight result. The supplier is further guarded
     * by a distributed Redis lock so only one JVM runs it cluster-wide at a time, with a bounded
     * poll-wait and self-build fallback.
     *
     * @param key      the logical page key (e.g. {@code feed:public:head:sz20}); used for both the
     *                 local map and the Redis lock ({@code lock:{key}})
     * @param supplier the source query to run at most once per in-flight window
     * @param <T>      the result type (typically {@code FeedPage})
     * @return the supplier's result, shared across concurrent callers
     */
    @SuppressWarnings("unchecked")
    public <T> T executeWithLock(String key, Supplier<T> supplier) {
        // 1. Local single-flight: dedup within this JVM.
        CompletableFuture<Object> newFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = local.putIfAbsent(key, newFuture);
        if (existing != null) {
            // Another thread in this JVM is already building; join its result.
            try {
                return (T) existing.get(props.singleFlightLockWaitMs(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Timed out or failed waiting on the local in-flight build. Fall through to a
                // self-build: the local map may have been cleared (the builder finished/failed) or
                // we hit the wait deadline. Either way, we attempt our own build below.
            }
            // The local entry may have been removed by the prior builder; try to install ours.
            // putIfAbsent again so we don't clobber a fresh builder that raced in.
            newFuture = new CompletableFuture<>();
            existing = local.putIfAbsent(key, newFuture);
            if (existing != null) {
                try {
                    return (T) existing.get(props.singleFlightLockWaitMs(), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // Still stuck — fall through to self-build without owning a local slot.
                    return withDistributedLock(key, supplier);
                }
            }
        }
        // We own the local slot for this key.
        try {
            T result = withDistributedLock(key, supplier);
            newFuture.complete(result);
            return result;
        } catch (RuntimeException e) {
            // Propagate the failure to any waiters so they don't block on a never-completing future.
            newFuture.completeExceptionally(e);
            throw e;
        } finally {
            // Remove only our slot; a concurrent successor may have installed a different future
            // if we somehow already cleared it — remove(key, value) guards against that.
            local.remove(key, newFuture);
        }
    }

    /**
     * Acquires the distributed Redis lock for {@code key}; on success runs {@code supplier} and
     * releases the lock. On lock contention, polls every {@link #LOCK_POLL_INTERVAL_MS} until
     * {@link FeedCacheProperties#singleFlightLockWaitMs()} elapses; on timeout falls back to a
     * self-build (the bounded herd).
     */
    private <T> T withDistributedLock(String key, Supplier<T> supplier) {
        String lockKey = FeedRedisKeys.lock(key);
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(props.singleFlightLockTtlSeconds());
        long deadline = System.currentTimeMillis() + props.singleFlightLockWaitMs();

        while (System.currentTimeMillis() < deadline) {
            Boolean acquired;
            try {
                acquired = redis.opsForValue().setIfAbsent(lockKey, token, ttl);
            } catch (RuntimeException e) {
                // Redis unavailable — degrade to self-build. Better to serve a correct (if
                // duplicated) result than to fail the read entirely.
                return supplier.get();
            }
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    return supplier.get();
                } finally {
                    releaseLock(lockKey, token);
                }
            }
            // Someone else holds the lock; wait briefly and retry.
            sleep(LOCK_POLL_INTERVAL_MS);
        }
        // Deadline elapsed without acquiring — fall back to self-build. This is the bounded herd:
        // at most one extra build per waiter per wait window, never an unbounded stampede.
        return supplier.get();
    }

    /** Releases the lock via Lua compare-and-delete; failures are swallowed (the TTL is the backstop). */
    private void releaseLock(String lockKey, String token) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, java.util.List.of(lockKey), token);
        } catch (RuntimeException ignored) {
            // Release failure is non-fatal: the lock's TTL will reap it. Notably this covers the
            // case where our TTL already expired and a successor re-acquired — the script returns 0
            // (no deletion) and we must not have deleted their lock.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test-only accessor: exposes the in-flight local futures map so unit tests can assert local
     * dedup behavior without reflection. Not used by production code.
     */
    ConcurrentHashMap<String, CompletableFuture<Object>> localMapForTesting() {
        return local;
    }
}
