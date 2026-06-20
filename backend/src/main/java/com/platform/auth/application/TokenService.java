package com.platform.auth.application;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.AuthRefreshToken;
import com.platform.auth.domain.IssuedRefreshToken;
import com.platform.auth.domain.TokenPair;
import com.platform.auth.infrastructure.jwt.JwtTokenProvider;
import com.platform.auth.infrastructure.redis.RedisTokenBlacklist;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.auth.repository.AuthRefreshTokenRepository;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, and revokes access + refresh tokens.
 *
 * <p>Refresh-token rotation: on every {@link #refresh(String)} the presented refresh token is
 * revoked and a brand-new pair is issued. Reuse detection fires when a refresh token that is already
 * revoked is presented again — that means an attacker (or a buggy client) replayed an old token, so
 * <em>all</em> of the user's refresh tokens are revoked and {@link ErrorCode#AUTH_REFRESH_TOKEN_REUSED}
 * is thrown, forcing a full re-login.
 *
 * <p>{@code @Profile("!test")} mirrors {@link RedisTokenBlacklist} / {@code VerificationService}:
 * its repository + Redis dependencies are absent under the {@code test} profile. The accompanying
 * unit test constructs it directly with fakes.
 */
@Service
@Profile("!test")
public class TokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final RedisTokenBlacklist tokenBlacklist;
    private final AuthProperties authProperties;
    private final UserQueryService userQueryService;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtTokenProvider jwtTokenProvider,
                        AuthRefreshTokenRepository refreshTokenRepository,
                        RedisTokenBlacklist tokenBlacklist,
                        AuthProperties authProperties,
                        UserQueryService userQueryService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenBlacklist = tokenBlacklist;
        this.authProperties = authProperties;
        this.userQueryService = userQueryService;
    }

    public TokenPair issue(Long userId, String username, UserRole role) {
        return issuePair(userId, username, role);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Missing refresh token");
        }
        String tokenHash = sha256Hex(rawRefreshToken);
        AuthRefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Unknown refresh token"));

        LocalDateTime now = LocalDateTime.now();

        if (stored.isRevoked()) {
            // Reuse: a revoked token should never be presented again. Burn the whole family.
            refreshTokenRepository.revokeAllByUserId(stored.userId(), now);
            throw new PlatformException(ErrorCode.AUTH_REFRESH_TOKEN_REUSED,
                    "Refresh token reuse detected; all sessions revoked");
        }

        if (stored.isExpired(now)) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_EXPIRED, "Refresh token has expired");
        }

        // Active token: claim it for rotation atomically with a single guarded UPDATE
        // (WHERE revoked_at IS NULL). This closes the concurrency race where two concurrent refresh
        // calls both passed the isRevoked() check above and each issued a fresh, 14-day-valid token.
        // claimForRotation returns 1 only for the caller that actually flipped revoked_at NULL→set;
        // the loser (who saw an active token in findByTokenHash but lost the UPDATE) gets 0.
        int affected = refreshTokenRepository.claimForRotation(stored.id(), now);
        if (affected == 0) {
            // The row was rotated out from under us by a concurrent rotation — that is exactly the
            // reuse signal. Per the design, concurrent reuse detection burns the whole family (safe,
            // spec-compliant direction): revoke every refresh token for this user.
            refreshTokenRepository.revokeAllByUserId(stored.userId(), now);
            throw new PlatformException(ErrorCode.AUTH_REFRESH_TOKEN_REUSED,
                    "Refresh token reuse detected; all sessions revoked");
        }

        // We won the rotation: THIS caller now owns the old row (revoked_at is set to `now`).
        // Resolve the user to mint a fresh access token, save the NEW refresh token, and link the
        // (already-revoked) old row to its replacement to preserve the rotation chain.
        UserAccount account = userQueryService.findAccountById(stored.userId());

        IssuedRefreshToken issued = generateRefreshToken(stored.userId(), now);
        AuthRefreshToken newToken = new AuthRefreshToken(
                null,
                stored.userId(),
                issued.tokenHash(),
                issued.tokenJti(),
                stored.deviceId(),
                stored.userAgent(),
                stored.ipAddress(),
                now.plusSeconds(authProperties.refreshToken().ttlSeconds()),
                null,
                null,
                now,
                now
        );
        AuthRefreshToken saved = refreshTokenRepository.save(newToken);

        // No revoked_at guard here — we already claimed the row, so we own it.
        refreshTokenRepository.linkReplacement(stored.id(), saved.id());

        String accessToken = jwtTokenProvider.createAccessToken(
                account.id(), account.username(), account.role(), UUID.randomUUID().toString());

        return new TokenPair(
                accessToken,
                issued.rawToken(),
                authProperties.jwt().accessTokenTtlSeconds(),
                saved.expiresAt()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken, AuthenticatedPrincipal principal) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(stored -> {
                if (!stored.isRevoked()) {
                    refreshTokenRepository.revoke(stored.id(), LocalDateTime.now(), null);
                }
            });
        }
        if (principal != null && principal.jti() != null) {
            // The caller only carries the access token's jti (not the raw token) at logout, so we
            // can't compute its exact remaining TTL. Blacklist for the full access-token TTL as a
            // safe upper bound — the key self-expires no later than the token would have anyway.
            tokenBlacklist.blacklist(principal.jti(), authProperties.jwt().accessTokenTtlSeconds());
        }
    }

    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
    }

    private TokenPair issuePair(Long userId, String username, UserRole role) {
        LocalDateTime now = LocalDateTime.now();

        IssuedRefreshToken issued = generateRefreshToken(userId, now);
        AuthRefreshToken refreshToken = new AuthRefreshToken(
                null,
                userId,
                issued.tokenHash(),
                issued.tokenJti(),
                null,
                null,
                null,
                now.plusSeconds(authProperties.refreshToken().ttlSeconds()),
                null,
                null,
                now,
                now
        );
        AuthRefreshToken saved = refreshTokenRepository.save(refreshToken);

        String accessToken = jwtTokenProvider.createAccessToken(
                userId, username, role, UUID.randomUUID().toString());

        return new TokenPair(
                accessToken,
                issued.rawToken(),
                authProperties.jwt().accessTokenTtlSeconds(),
                saved.expiresAt()
        );
    }

    private IssuedRefreshToken generateRefreshToken(Long userId, LocalDateTime now) {
        byte[] bytes = new byte[authProperties.refreshToken().bytes()];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(raw);
        String jti = UUID.randomUUID().toString();
        return new IssuedRefreshToken(raw, hash, jti);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
