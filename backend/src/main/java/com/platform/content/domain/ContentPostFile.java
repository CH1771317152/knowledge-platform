package com.platform.content.domain;

import java.time.LocalDateTime;

public record ContentPostFile(
        Long postId,
        String objectKey,
        PostFileUsageType usageType,
        String contentType,
        Long sizeBytes,
        int sortOrder,
        LocalDateTime createdAt
) {}
