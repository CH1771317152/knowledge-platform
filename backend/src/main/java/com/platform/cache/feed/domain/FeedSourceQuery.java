package com.platform.cache.feed.domain;

/**
 * Read port the cache/feed module depends on to backfill feed pages from the content store.
 * Implementations return strictly-ordered post ids in keyset (cursor) pagination form.
 */
public interface FeedSourceQuery {

    /** Newest {@code size} ids from the public feed (PUBLISHED + PUBLIC), newest first. */
    FeedPage findPublicFeedHead(int size);

    /** Public feed ids strictly after {@code cursor} (published_at, id) descending. */
    FeedPage findPublicFeedAfter(Cursor cursor, int size);

    /** Newest {@code size} ids authored by {@code userId} (status &lt;&gt; DELETED), newest first. */
    FeedPage findUserFeedHead(Long userId, int size);

    /** User feed ids strictly after {@code cursor} (created_at, id) descending. */
    FeedPage findUserFeedAfter(Long userId, Cursor cursor, int size);
}
