package com.platform.cache.feed.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.domain.PostFragment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * L0 fragment store: per-post JSON fragments in Redis. The read path assembles feed pages by
 * {@link #multiGet(List) batch-fetching} the fragments for the ids in a cached skeleton; missing or
 * tombstoned fragments trigger either a batch backfill (missing) or a page rebuild (tombstone).
 *
 * <p><b>Tombstone semantics:</b> {@link #putTombstone(Long)} overwrites the fragment value with the
 * {@link PostFragment#TOMBSTONE} sentinel under a short TTL. During assembly a tombstone is treated
 * as "stale" rather than "missing":
 * <ul>
 *   <li>{@link #get(Long)} returns {@link Optional#empty()} for both null <em>and</em> tombstone
 *       values — assembly cannot reconstruct a deleted post either way.</li>
 *   <li>{@link #multiGet(List)} omits tombstones from the result map (they are "missing"), so the
 *       orchestrator's missing-fragment detection treats them the same as absent.</li>
 *   <li>{@link #isTombstone(Long)} / {@link #anyTombstone(List)} are the explicit probes the read
 *       orchestrator (Task 7) uses to distinguish "page references a tombstoned post → rebuild the
 *       skeleton" from "fragment simply expired → backfill and retry".</li>
 * </ul>
 *
 * <p><b>TTL:</b> {@link #put(PostFragment, int)} writes exactly the TTL handed in by the caller —
 * it does <em>not</em> know the pageKey and therefore cannot compute heat-based TTL itself. The
 * caller ({@code FeedReadService.backfillMissing}) computes the TTL via
 * {@link com.platform.cache.feed.hotkey.FeedHotKeyDetector#ttlFor(String, int)
 * FeedHotKeyDetector.ttlFor(pageKey, baseTtl)}, which already folds in heat-based extra seconds and
 * anti-stampede jitter. Applying a second layer of jitter here would double-count and is intentionally
 * avoided. {@link #putTombstone(Long)} still uses a short fixed TTL because tombstones ride out the
 * rebuild window and do not participate in heat-based TTL.
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} — the unit tests in Tasks 7+ substitute a fake
 * FragmentStore, so the Redis-backed bean must stay out of the {@code test} profile to keep
 * {@code contextLoads} green (no live Redis under unit tests).
 */
@Repository
@Profile("!test")
public class FragmentStore {

    /** TTL for a tombstone marker. Short: only long enough to ride out the page rebuild window. */
    private static final int TOMBSTONE_TTL_SECONDS = 30;

    private final StringRedisTemplate template;
    private final ObjectMapper objectMapper;

    public FragmentStore(StringRedisTemplate template,
                         ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the fragment for {@code postId}. Returns empty for both missing and tombstoned posts;
     * the caller cannot distinguish them via this method (use {@link #isTombstone(Long)} for that).
     */
    public Optional<PostFragment> get(Long postId) {
        String raw = template.opsForValue().get(FeedRedisKeys.fragment(postId));
        if (raw == null || PostFragment.TOMBSTONE.equals(raw)) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw, postId));
    }

    /**
     * Batch fetch. Issues one Redis {@code MGET}; present, non-tombstone values are deserialized
     * into the result map keyed by postId. Missing and tombstoned entries are omitted (the caller
     * treats both as "not assembled" — {@link #anyTombstone(List)} is the separate probe that
     * distinguishes the rebuild case).
     */
    public Map<Long, PostFragment> multiGet(List<Long> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(postIds.size());
        for (Long pid : postIds) {
            keys.add(FeedRedisKeys.fragment(pid));
        }
        List<String> values;
        try {
            values = template.opsForValue().multiGet(keys);
        } catch (DataAccessException e) {
            throw e;
        }
        if (values == null) {
            return Map.of();
        }
        Map<Long, PostFragment> out = new LinkedHashMap<>(postIds.size());
        for (int i = 0; i < postIds.size(); i++) {
            String raw = i < values.size() ? values.get(i) : null;
            if (raw == null || PostFragment.TOMBSTONE.equals(raw)) {
                continue;
            }
            out.put(postIds.get(i), deserialize(raw, postIds.get(i)));
        }
        return out;
    }

    /** Explicit tombstone probe for a single post (used by the read orchestrator's rebuild check). */
    public boolean isTombstone(Long postId) {
        String raw = template.opsForValue().get(FeedRedisKeys.fragment(postId));
        return PostFragment.TOMBSTONE.equals(raw);
    }

    /**
     * Returns {@code true} if any of {@code postIds} currently holds a tombstone marker. Used by the
     * read orchestrator to decide whether a cached skeleton is stale (one of its posts was deleted)
     * and must be rebuilt from source before assembly.
     */
    public boolean anyTombstone(List<Long> postIds) {
        if (postIds.isEmpty()) {
            return false;
        }
        List<String> keys = new ArrayList<>(postIds.size());
        for (Long pid : postIds) {
            keys.add(FeedRedisKeys.fragment(pid));
        }
        List<String> values = template.opsForValue().multiGet(keys);
        if (values == null) {
            return false;
        }
        for (String raw : values) {
            if (PostFragment.TOMBSTONE.equals(raw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes {@code fragment} with <em>exactly</em> {@code ttlSeconds} — no jitter is applied here.
     * The caller is expected to compute {@code ttlSeconds} via
     * {@link com.platform.cache.feed.hotkey.FeedHotKeyDetector#ttlFor(String, int)
     * FeedHotKeyDetector.ttlFor(pageKey, baseTtl)}, which adds heat-based extra seconds and jitter.
     * Keeping this method a thin writer means FragmentStore does not need to know the pageKey.
     */
    public void put(PostFragment fragment, int ttlSeconds) {
        String json = serialize(fragment);
        template.opsForValue().set(FeedRedisKeys.fragment(fragment.postId()), json,
                Duration.ofSeconds(ttlSeconds));
    }

    /**
     * Overwrites the fragment with the tombstone sentinel under a short fixed TTL. A tombstone
     * signals "deleted" (not merely expired) to the read path — any cached skeleton still pointing
     * at this post is stale and must be rebuilt.
     */
    public void putTombstone(Long postId) {
        template.opsForValue().set(FeedRedisKeys.fragment(postId), PostFragment.TOMBSTONE,
                Duration.ofSeconds(TOMBSTONE_TTL_SECONDS));
    }

    /** Removes the fragment (or tombstone) entirely. Used on edits to force a backfill on next read. */
    public void delete(Long postId) {
        template.delete(FeedRedisKeys.fragment(postId));
    }

    // ---- helpers ----------------------------------------------------------

    private PostFragment deserialize(String json, Long postIdForError) {
        try {
            return objectMapper.readValue(json, PostFragment.class);
        } catch (JsonProcessingException e) {
            // A corrupt fragment blob is a server-side data issue. Rather than fail the whole feed
            // read, surface it as an IllegalStateException so the orchestrator can treat this post
            // as missing and backfill it. (For now: let it propagate — the orchestrator's missing-
            // fragment handling is added in Task 7.)
            throw new IllegalStateException("Corrupt fragment JSON for post " + postIdForError, e);
        }
    }

    private String serialize(PostFragment fragment) {
        try {
            return objectMapper.writeValueAsString(fragment);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize fragment for post " + fragment.postId(), e);
        }
    }
}
