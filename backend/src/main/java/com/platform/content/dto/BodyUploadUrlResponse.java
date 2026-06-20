package com.platform.content.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stage-2 response: a presigned PUT URL the client uploads the post body markdown to. The
 * {@code objectKey} and {@code bodyVersion} are stable across repeated calls (idempotent), while
 * {@code putUrl} / {@code expiresAt} are refreshed each time.
 */
public record BodyUploadUrlResponse(
        Long postId,
        int bodyVersion,
        String bucket,
        String objectKey,
        String putUrl,
        Map<String, String> headers,
        LocalDateTime expiresAt
) {}
