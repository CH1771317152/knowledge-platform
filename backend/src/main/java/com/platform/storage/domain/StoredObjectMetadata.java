package com.platform.storage.domain;

public record StoredObjectMetadata(
        String bucket,
        String objectKey,
        String etag,
        long sizeBytes,
        String contentType
) {}
