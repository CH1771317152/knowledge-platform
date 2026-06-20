package com.platform.storage.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.storage")
public record StorageProperties(
        @NotBlank String provider,
        @NotBlank String endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        String accessKeyId,
        String accessKeySecret,
        @Min(1) @Max(10) int presignExpireMinutes
) {}
