package com.platform.cache.feed.infrastructure.redis;

import java.time.LocalDateTime;

/**
 * Centralized Redis key builders for the three-tier feed cache. Keeping every key shape here makes
 * the invalidation consumer (Task 10) and the reconciliation scheduler (Task 11) easy to audit, and
 * keeps the {@code frag:}/{@code skel:}/{@code null:}/{@code lock:} namespaces from drifting.
 *
 * <p>Key namespaces:
 * <ul>
 *   <li>{@code frag:post:{postId}} — L0 per-post fragment JSON (or tombstone marker).</li>
 *   <li>{@code skel:{pageKey}} — L1 page skeleton (id list + hasMore + cursor).</li>
 *   <li>{@code null:{pageKey}} — L1 NULL sentinel (page is provably empty for a short window).</li>
 *   <li>{@code lock:{key}} — single-flight distributed lock token.</li>
 *   <li>{@code feed:public:...}/{@code feed:user:{uid}:...} — the logical {@code pageKey} passed to
 *       {@link #skeleton(String)} / {@link #nullSentinel(String)}, scoped by feed type, cursor, size.</li>
 * </ul>
 */
public final class FeedRedisKeys {

    private FeedRedisKeys() {}

    /** L0 fragment key for a single post. */
    public static String fragment(Long postId) {
        return "frag:post:" + postId;
    }

    /** L1 skeleton key for a logical page identifier. */
    public static String skeleton(String pageKey) {
        return "skel:" + pageKey;
    }

    /** L1 NULL-sentinel key for a logical page identifier. */
    public static String nullSentinel(String pageKey) {
        return "null:" + pageKey;
    }

    /** Distributed single-flight lock key wrapping any other cache key. */
    public static String lock(String key) {
        return "lock:" + key;
    }

    // ---- logical page key builders (the value passed to skeleton/nullSentinel) ----

    /** Public feed head page key for a given page size. */
    public static String publicHead(int size) {
        return "feed:public:head:sz" + size;
    }

    /** Public feed cursor page key for a given {@code (at, id)} cursor and page size. */
    public static String publicAfter(LocalDateTime at, Long id, int size) {
        return "feed:public:after:" + at + ":" + id + ":sz" + size;
    }

    /** User feed head page key for a given author id and page size. */
    public static String userHead(Long uid, int size) {
        return "feed:user:" + uid + ":head:sz" + size;
    }

    /** User feed cursor page key for a given author id, {@code (at, id)} cursor, and page size. */
    public static String userAfter(Long uid, LocalDateTime at, Long id, int size) {
        return "feed:user:" + uid + ":after:" + at + ":" + id + ":sz" + size;
    }
}
