package com.platform.search.dto;

import com.platform.cache.feed.dto.FeedItemResponse;
import java.util.List;

public record SearchPostPageResponse(
        List<FeedItemResponse> items,
        boolean hasMore,
        String nextCursor) {}
