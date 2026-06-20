package com.platform.content.domain;

import java.time.LocalDateTime;

public record ContentPost(
        Long id,
        Long authorId,
        String clientRequestId,
        String title,
        String summary,
        String coverObjectKey,
        PostStatus status,
        PostVisibility visibility,
        PublishStage publishStage,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
