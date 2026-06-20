package com.platform.auth.domain;

import java.time.LocalDateTime;

/**
 * Immutable access/refresh token pair returned by {@code TokenService} on login/refresh.
 *
 * @param accessToken                signed JWT (typ=access)
 * @param refreshToken               opaque refresh token; only ever returned to the client in plaintext here
 * @param accessTokenExpiresInSeconds TTL of the access token, in seconds, for the client's clock
 * @param refreshTokenExpiresAt      absolute expiry timestamp of the refresh token
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds,
        LocalDateTime refreshTokenExpiresAt
) {
}
