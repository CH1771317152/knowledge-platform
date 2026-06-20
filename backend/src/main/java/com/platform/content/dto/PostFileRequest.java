package com.platform.content.dto;

import com.platform.content.domain.PostFileUsageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A file attached to a post (cover / inline image / attachment). The {@code objectKey} must point
 * at an object the author previously uploaded under their own {@code users/{authorId}/} prefix.
 */
public record PostFileRequest(
        @NotBlank String objectKey,
        @NotNull PostFileUsageType usageType,
        String contentType,
        Long sizeBytes,
        int sortOrder
) {}
