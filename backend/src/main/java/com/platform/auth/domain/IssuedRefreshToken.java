package com.platform.auth.domain;

/**
 * Result of generating a fresh refresh token inside {@code TokenService}. Carries both the plaintext
 * value handed to the client and the persisted artifacts (hash + jti).
 *
 * @param rawToken  plaintext refresh token returned to the client (never persisted)
 * @param tokenHash SHA-256 hex hash persisted in the refresh-token table
 * @param tokenJti  unique id for this refresh token, used for tracing the rotation chain
 */
public record IssuedRefreshToken(String rawToken, String tokenHash, String tokenJti) {
}
