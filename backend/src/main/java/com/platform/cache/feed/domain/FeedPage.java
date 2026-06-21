package com.platform.cache.feed.domain;

import java.util.List;

/**
 * One page of feed post ids. {@code ids} are ordered newest-first, {@code hasMore} signals
 * whether at least one more row exists beyond this page, and {@code nextCursor} is the keyset
 * cursor derived from the LAST id in this page (null when {@code hasMore} is false).
 */
public record FeedPage(List<Long> ids, boolean hasMore, Cursor nextCursor) {}
