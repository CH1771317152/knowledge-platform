package com.platform.cache.feed.dto;

import java.time.LocalDateTime;

/**
 * One assembled feed item in a {@link FeedPageResponse}. Mirrors the denormalized fields stored in
 * the L0 {@link com.platform.cache.feed.domain.PostFragment} (post metadata + count snapshots),
 * plus the read-time personalization overlay ({@code likedByMe} / {@code favedByMe}).
 *
 * <p>{@code likedByMe} / {@code favedByMe} are nullable {@link Boolean}: {@code null} signals an
 * anonymous reader (no overlay was applied); a non-null value signals an authenticated reader for
 * whom the overlay (Task 9) resolved the bit. They are deliberately <em>not</em> part of the cached
 * fragment — the cache is user-agnostic, and the overlay is recomputed per request.
 */
public record FeedItemResponse(
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
        long shareCount,
        Boolean likedByMe,
        Boolean favedByMe) {}
