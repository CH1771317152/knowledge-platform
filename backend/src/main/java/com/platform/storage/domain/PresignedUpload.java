package com.platform.storage.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record PresignedUpload(
        String bucket,
        String objectKey,
        String putUrl,
        Map<String, String> headers,
        LocalDateTime expiresAt
) {}
