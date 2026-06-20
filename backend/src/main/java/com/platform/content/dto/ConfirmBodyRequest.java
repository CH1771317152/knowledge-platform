package com.platform.content.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Stage-3 request: the client confirms it uploaded the body object and reports its size, etag and
 * SHA-256. The service re-reads the object from storage, recomputes the SHA-256 and compares all
 * three fields before advancing the publish stage.
 */
public record ConfirmBodyRequest(
        @NotBlank String objectKey,
        @NotBlank String etag,
        @Min(0) long sizeBytes,
        @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256
) {}
