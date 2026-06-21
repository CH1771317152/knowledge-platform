package com.platform.cache.feed.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.FeedPage;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * L1 page skeleton store: caches {@link FeedPage} (the id list + hasMore + nextCursor for one page)
 * in Redis under {@code skel:{pageKey}}. The read orchestrator (Task 7) fetches a skeleton, then
 * batch-fetches the matching L0 fragments and assembles the response; a skeleton hit avoids the
 * database keyset query entirely on the hot path.
 *
 * <p><b>NULL sentinel:</b> when a page is provably empty (zero rows from the database), we cache
 * that fact under {@code null:{pageKey}} for a short window ({@link #NULL_SENTINEL_TTL_SECONDS}) so
 * repeated empty-page reads skip the DB too. {@link #get(String)} treats a present sentinel the
 * same as an absent skeleton (returns {@link Optional#empty()}); the orchestrator uses
 * {@link #isNullSentinel(String)} to distinguish "cached empty" from "not cached".
 *
 * <p><b>TTL jitter:</b> {@link #put(String, FeedPage, int)} applies TTL-relative jitter
 * {@code ttl * (1 + rand(0..jitterRatio))} so skeletons do not all expire simultaneously after a
 * bulk rebuild (anti-stampede). The {@code jitterRatio} comes from {@link FeedCacheProperties#jitterRatio()}.
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} — the unit tests in Tasks 7+ substitute a fake
 * SkeletonStore, so the Redis-backed bean must stay out of the {@code test} profile to keep
 * {@code contextLoads} green (no live Redis under unit tests). Mirrors {@link FragmentStore}.
 */
@Repository
@Profile("!test")
public class SkeletonStore {

    /** TTL for a NULL-sentinel marker. Short: only long enough to ride out the empty-page window. */
    private static final int NULL_SENTINEL_TTL_SECONDS = 30;

    /** Marker value stored under the null-sentinel key; presence (not value) is what matters. */
    private static final String NULL_SENTINEL_VALUE = "1";

    private final StringRedisTemplate template;
    private final ObjectMapper objectMapper;
    private final FeedCacheProperties props;

    public SkeletonStore(StringRedisTemplate template,
                         ObjectMapper objectMapper,
                         FeedCacheProperties props) {
        this.template = template;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Reads the skeleton for {@code pageKey}. Returns empty in three cases:
     * <ul>
     *   <li>no skeleton and no sentinel (page simply not cached),</li>
     *   <li>sentinel present (page is provably empty — caller checks
     *       {@link #isNullSentinel(String)} to tell this apart),</li>
     *   <li>corrupt skeleton JSON (treated as a cache miss, surfaced via IllegalStateException).</li>
     * </ul>
     */
    public Optional<FeedPage> get(String pageKey) {
        String raw = template.opsForValue().get(FeedRedisKeys.skeleton(pageKey));
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw, pageKey));
    }

    /**
     * Explicit NULL-sentinel probe. {@code true} means the page is cached as provably empty; the
     * orchestrator should serve an empty page without hitting the database. Mirrors the
     * tombstone-vs-missing split in {@link FragmentStore#isTombstone(Long)}.
     */
    public boolean isNullSentinel(String pageKey) {
        Boolean exists = template.hasKey(FeedRedisKeys.nullSentinel(pageKey));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Writes {@code page} with {@code ttlSeconds} + relative jitter. Pass the base L1 TTL
     * ({@code props.l1().headTtlSeconds()} / {@code props.l1().cursorTtlSeconds()}) for normal
     * skeleton caching.
     */
    public void put(String pageKey, FeedPage page, int ttlSeconds) {
        String json = serialize(page);
        int ttlWithJitter = applyJitter(ttlSeconds);
        template.opsForValue().set(FeedRedisKeys.skeleton(pageKey), json,
                Duration.ofSeconds(ttlWithJitter));
    }

    /**
     * Writes the NULL sentinel under a short fixed TTL. A sentinel signals "provably empty page" to
     * the read path — repeated empty-page reads skip the DB for the sentinel's lifetime.
     */
    public void putNullSentinel(String pageKey) {
        template.opsForValue().set(FeedRedisKeys.nullSentinel(pageKey), NULL_SENTINEL_VALUE,
                Duration.ofSeconds(NULL_SENTINEL_TTL_SECONDS));
    }

    /** Removes the skeleton and any NULL sentinel for {@code pageKey}. Used on invalidation. */
    public void delete(String pageKey) {
        template.delete(FeedRedisKeys.skeleton(pageKey));
        template.delete(FeedRedisKeys.nullSentinel(pageKey));
    }

    // ---- helpers ----------------------------------------------------------

    private FeedPage deserialize(String json, String pageKeyForError) {
        try {
            return objectMapper.readValue(json, FeedPage.class);
        } catch (JsonProcessingException e) {
            // A corrupt skeleton blob is a server-side data issue. Surface it so the orchestrator
            // can treat the page as a cache miss and rebuild it. (Read-orchestrator handling lands
            // in Task 7; for now let it propagate.)
            throw new IllegalStateException("Corrupt skeleton JSON for page " + pageKeyForError, e);
        }
    }

    private String serialize(FeedPage page) {
        try {
            return objectMapper.writeValueAsString(page);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize skeleton for page", e);
        }
    }

    /**
     * TTL-relative jitter: {@code ttl * (1 + rand(0..jitterRatio))}. With {@code jitterRatio=0.3}
     * and {@code ttl=300}, the effective TTL falls in [300, 390]. Same formula as
     * {@link FragmentStore#applyJitter(int)}; kept duplicated rather than shared to keep each store
     * self-contained and the redis package free of an extra helper bean.
     */
    private int applyJitter(int ttlSeconds) {
        double ratio = props.jitterRatio();
        if (ratio <= 0.0) {
            return ttlSeconds;
        }
        double jitter = ThreadLocalRandom.current().nextDouble(ratio);
        return ttlSeconds + (int) Math.round(ttlSeconds * jitter);
    }
}
