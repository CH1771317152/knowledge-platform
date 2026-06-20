package com.platform.auth.domain;

import java.time.LocalDateTime;

public record AuthRefreshToken(
        Long id,
        Long userId,
        String tokenHash,
        String tokenJti,
        String deviceId,
        String userAgent,
        String ipAddress,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        Long replacedByTokenId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive(LocalDateTime now) {
        return !isRevoked() && !isExpired(now);
    }
}
