package com.platform.search.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SearchPostDocument(
        Long postId,
        String contentType,
        String status,
        String visibility,
        Long authorId,
        String authorName,
        String authorAvatar,
        String title,
        String description,
        String bodyText,
        String coverObjectKey,
        List<String> tags,
        Map<String, Object> tagsJson,
        Instant publishTime,
        Instant updateTime,
        long likeCount,
        long favoriteCount,
        long viewCount,
        long commentCount,
        long shareCount,
        long sourceVersion,
        Instant indexedAt) {}
