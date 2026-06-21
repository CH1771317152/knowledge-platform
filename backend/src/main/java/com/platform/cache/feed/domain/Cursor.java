package com.platform.cache.feed.domain;

import java.time.LocalDateTime;

/**
 * Keyset pagination cursor. The {@code timestamp} is the column the feed is ordered on
 * ({@code published_at} for public feed, {@code created_at} for user feed) and {@code id}
 * is the tiebreaker. Together they form a strict total order so pagination is stable.
 */
public record Cursor(LocalDateTime timestamp, Long id) {}
