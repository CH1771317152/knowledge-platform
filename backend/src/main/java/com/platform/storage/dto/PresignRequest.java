package com.platform.storage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PresignRequest(
        @NotBlank String objectKey,
        @NotBlank String contentType,
        @Min(1) @Max(10) int expiresMinutes
) {}
