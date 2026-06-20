package com.platform.content.domain;

import java.time.LocalDateTime;

public record ContentPostBody(
        Long postId,
        PostBodyFormat bodyFormat,
        String bodyBucket,
        String bodyObjectKey,
        String bodyEtag,
        String bodySha256,
        Long bodySizeBytes,
        int bodyVersion,
        LocalDateTime uploadUrlExpiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
