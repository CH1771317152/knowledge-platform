package com.platform.auth.infrastructure.persistence;

import com.platform.auth.domain.AuthRefreshToken;
import java.time.LocalDateTime;

public class AuthRefreshTokenRow {
    private Long id;
    private Long userId;
    private String tokenHash;
    private String tokenJti;
    private String deviceId;
    private String userAgent;
    private String ipAddress;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long replacedByTokenId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AuthRefreshTokenRow fromDomain(AuthRefreshToken token) {
        AuthRefreshTokenRow row = new AuthRefreshTokenRow();
        row.setId(token.id());
        row.setUserId(token.userId());
        row.setTokenHash(token.tokenHash());
        row.setTokenJti(token.tokenJti());
        row.setDeviceId(token.deviceId());
        row.setUserAgent(token.userAgent());
        row.setIpAddress(token.ipAddress());
        row.setExpiresAt(token.expiresAt());
        row.setRevokedAt(token.revokedAt());
        row.setReplacedByTokenId(token.replacedByTokenId());
        row.setCreatedAt(token.createdAt());
        row.setUpdatedAt(token.updatedAt());
        return row;
    }

    public AuthRefreshToken toDomain() {
        return new AuthRefreshToken(
                id,
                userId,
                tokenHash,
                tokenJti,
                deviceId,
                userAgent,
                ipAddress,
                expiresAt,
                revokedAt,
                replacedByTokenId,
                createdAt,
                updatedAt
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getTokenJti() {
        return tokenJti;
    }

    public void setTokenJti(String tokenJti) {
        this.tokenJti = tokenJti;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getReplacedByTokenId() {
        return replacedByTokenId;
    }

    public void setReplacedByTokenId(Long replacedByTokenId) {
        this.replacedByTokenId = replacedByTokenId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
