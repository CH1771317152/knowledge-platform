package com.platform.counter.dto;

/**
 * Read-side projection of an article's five interaction counters. Every field is a non-null
 * {@code long}; absent metrics default to {@code 0} (see {@code CounterReadService#getArticleCounters}).
 */
public record ArticleCountersResponse(Long postId, long like, long fav, long view, long comment, long share) {}
