package com.platform.cache.feed.dto;

import com.platform.cache.feed.domain.Cursor;
import java.util.List;

/**
 * One page of assembled feed items. {@code items} is ordered newest-first (matching the underlying
 * skeleton's id order), {@code hasMore} signals whether at least one more row exists beyond this
 * page, and {@code nextCursor} is the keyset cursor the client should pass to fetch the next page
 * (null when {@code hasMore} is false — the client treats an absent/empty cursor as the head page).
 *
 * <p>This is the value type promoted into the L2 Caffeine cache and returned to the controller; the
 * per-user overlay is applied on top of it at read time, never cached.
 */
public record FeedPageResponse(List<FeedItemResponse> items, boolean hasMore, Cursor nextCursor) {

    /** Empty page sentinel: zero items, no more rows, no cursor. Used for NULL-sentinel responses. */
    public static FeedPageResponse empty() {
        return new FeedPageResponse(List.of(), false, null);
    }
}
