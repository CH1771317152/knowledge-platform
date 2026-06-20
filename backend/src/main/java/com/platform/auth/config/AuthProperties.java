package com.platform.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.auth")
public record AuthProperties(
        Jwt jwt,
        RefreshToken refreshToken,
        Verification verification,
        Sender sender
) {
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String secret,
            @Min(60) long accessTokenTtlSeconds
    ) {}

    public record RefreshToken(
            @Min(300) long ttlSeconds,
            @Min(32) int bytes
    ) {}

    public record Verification(
            @Min(60) long codeTtlSeconds,
            @Min(1) long resendIntervalSeconds,
            @Min(1) int hourlySendLimit,
            @Min(1) int maxFailedAttempts,
            @Min(4) int codeLength
    ) {}

    public record Sender(String mode, boolean exposeCodeInLogs) {}
}
