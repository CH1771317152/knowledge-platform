package com.platform.cache.feed.domain;

import java.time.LocalDateTime;

/**
 * L0 cache fragment: a denormalized, read-optimized projection of one post used to assemble feed
 * pages without touching MySQL. Mirrors the public fields of {@code FeedItemResponse} (Task 7) minus
 * the personalization overlay ({@code likedByMe}/{@code favedByMe}), which is applied at read time
 * and never written into the fragment.
 *
 * <p>Counts are point-in-time snapshots copied from the counter module at backfill time. They are
 * eventually consistent: a fragment may lag up to one flush interval behind the live counter, and
 * the reconciliation scheduler (Task 11) periodically refreshes hot fragments to bound the drift.
 *
 * <p><b>Tombstone marker:</b> when a post is unpublished or deleted, the invalidation consumer
 * (Task 10) overwrites the fragment value with the {@link #TOMBSTONE} sentinel string
 * ({@code {"deleted":true}}) so any cached skeleton still pointing at it is detected as stale and
 * triggers a page rebuild. {@link com.platform.cache.feed.infrastructure.redis.FragmentStore#get}
 * treats a tombstone as missing (returns empty); {@code FragmentStore.isTombstone} is the explicit
 * probe the read orchestrator uses to decide whether to rebuild the skeleton.
 */
public record PostFragment(
        Long postId,
        Long authorId,
        String authorName,
        String cover,
        String title,
        String summary,
        LocalDateTime publishedAt,
        long likeCount,
        long favCount,
        long viewCount,
        long commentCount,
        long shareCount) {

    /** Sentinel value written into Redis in place of a fragment to mark a post as deleted. */
    public static final String TOMBSTONE = "{\"deleted\":true}";
}
