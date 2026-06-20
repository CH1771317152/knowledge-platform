package com.platform.auth.repository;

import com.platform.auth.domain.AuthRefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthRefreshTokenRepository {
    AuthRefreshToken save(AuthRefreshToken token);

    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    void revoke(Long tokenId, LocalDateTime revokedAt, Long replacedByTokenId);

    void revokeAllByUserId(Long userId, LocalDateTime revokedAt);

    /**
     * Atomically claims a refresh-token row for rotation by setting {@code revoked_at} only if it is
     * still {@code NULL}. Returns 1 if THIS caller won the claim (the row was active and is now
     * revoked), or 0 if another caller already claimed/rotated it (concurrent reuse signal).
     *
     * @param tokenId    the row id of the refresh token being rotated
     * @param revokedAt  the rotation timestamp to stamp
     * @return affected rows (1 = winner, 0 = already claimed)
     */
    int claimForRotation(Long tokenId, LocalDateTime revokedAt);

    /**
     * Sets the rotation-chain link ({@code replaced_by_token_id}) on a row already claimed by THIS
     * caller. No {@code revoked_at} guard here — the caller owns the row because it won the prior
     * {@link #claimForRotation}.
     *
     * @param oldTokenId  the already-revoked row whose replacement to record
     * @param newTokenId  the id of the newly issued refresh token
     */
    void linkReplacement(Long oldTokenId, Long newTokenId);
}
