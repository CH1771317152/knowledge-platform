package com.platform.search.domain;

import java.time.Instant;

public record SearchPostQuery(
        String keyword,
        String tag,
        String contentType,
        int size,
        SearchCursor cursor,
        Instant rankNow,
        String queryHash) {}
