package com.platform.content.dto;

import com.platform.content.domain.PostFileUsageType;

/**
 * A file attached to a post in a detail response (cover / inline image / attachment).
 *
 * @param objectKey   storage object key
 * @param usageType   how the file is used within the post
 * @param contentType MIME content type, may be null if not recorded
 * @param sizeBytes   object size in bytes, may be null if not recorded
 * @param sortOrder   client display order
 */
public record PostFileResponse(
        String objectKey,
        PostFileUsageType usageType,
        String contentType,
        Long sizeBytes,
        int sortOrder
) {}
