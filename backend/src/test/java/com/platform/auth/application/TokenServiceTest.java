package com.platform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.AuthRefreshToken;
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
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link TokenService}. Builds the service directly with fakes (in-memory
 * refresh-token repo, in-memory blacklist, stubbed {@link UserQueryService}) plus a real
 * {@link JwtTokenProvider} and {@link AuthProperties} — so it runs under the {@code test} profile
 * where the MySQL/Redis-backed beans are absent.
 */
class TokenServiceTest {

    private static final String ISSUER = "knowledge-platform";
    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789abcdef";
    private static final long REFRESH_TTL_SECONDS = 1209600L;

    private static final Long USER_ID = 101L;
    private static final String USERNAME = "alice";
    private static final UserRole ROLE = UserRole.USER;

    private final AuthProperties authProperties = new AuthProperties(
            new AuthProperties.Jwt(ISSUER, SECRET, 900L),
            new AuthProperties.RefreshToken(REFRESH_TTL_SECONDS, 48),
            new AuthProperties.Verification(300L, 60L, 5, 5, 6),
            new AuthProperties.Sender("logging", false)
    );

    private FakeRefreshTokenRepository refreshTokenRepository;
    private InMemoryTokenBlacklist tokenBlacklist;
    private TokenService tokenService;

    /**
     * When non-null, {@link FakeRefreshTokenRepository#claimForRotation} returns this value instead
     * of doing the real NULL-guarded flip. Lets a test force the concurrent-rotation-loser branch
     * (affected == 0) without first revoking the token (which would take the earlier reuse branch).
     */
    private Integer claimForRotationOverride;

    @BeforeEach
    void setUp() {
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(authProperties);
        refreshTokenRepository = new FakeRefreshTokenRepository();
        tokenBlacklist = new InMemoryTokenBlacklist();
        UserQueryService userQueryService = stubUserQueryService();
        tokenService = new TokenService(
                jwtTokenProvider,
                refreshTokenRepository,
                tokenBlacklist,
                authProperties,
                userQueryService
        );
    }

    @Test
    void issuesTokenPair() {
        TokenPair pair = tokenService.issue(USER_ID, USERNAME, ROLE);

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.accessTokenExpiresInSeconds()).isEqualTo(900L);
        assertThat(pair.refreshTokenExpiresAt()).isNotNull();

        assertThat(refreshTokenRepository.savedTokens).hasSize(1);
        AuthRefreshToken stored = refreshTokenRepository.savedTokens.values().iterator().next();
        // The plaintext is never persisted; only its hash.
        assertThat(stored.tokenHash()).isEqualTo(TokenService.sha256Hex(pair.refreshToken()));
        assertThat(stored.tokenHash()).isNotEqualTo(pair.refreshToken());
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.isRevoked()).isFalse();
    }

    @Test
    void refreshesByRotatingRefreshToken() {
        TokenPair initial = tokenService.issue(USER_ID, USERNAME, ROLE);

        TokenPair rotated = tokenService.refresh(initial.refreshToken());

        assertThat(rotated.accessToken()).isNotEqualTo(initial.accessToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());

        // The old token is revoked and links to its replacement.
        AuthRefreshToken oldToken = findByHash(initial.refreshToken());
        AuthRefreshToken newToken = findByHash(rotated.refreshToken());
        assertThat(oldToken.isRevoked()).isTrue();
        assertThat(oldToken.replacedByTokenId()).isEqualTo(newToken.id());
        assertThat(newToken.isRevoked()).isFalse();
        assertThat(newToken.isActive(LocalDateTime.now())).isTrue();
    }

    @Test
    void rejectsRevokedRefreshTokenAndRevokesAllUserTokens() {
        TokenPair initial = tokenService.issue(USER_ID, USERNAME, ROLE);
        // Rotate once (revokes the initial token), then attempt to reuse the now-revoked initial token.
        tokenService.refresh(initial.refreshToken());

        assertThatThrownBy(() -> tokenService.refresh(initial.refreshToken()))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_REFRESH_TOKEN_REUSED);

        // Reuse detection burns the whole family for the user.
        assertThat(refreshTokenRepository.revokeAllCalls).contains(USER_ID);
        assertThat(refreshTokenRepository.savedTokens.values())
                .allSatisfy(t -> assertThat(t.isRevoked()).isTrue());
    }

    @Test
    void rejectsUnknownRefreshToken() {
        assertThatThrownBy(() -> tokenService.refresh("not-a-real-token"))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void logsOutByRevokingRefreshTokenAndBlacklistingAccessJti() {
        TokenPair pair = tokenService.issue(USER_ID, USERNAME, ROLE);
        JwtTokenProvider provider = new JwtTokenProvider(authProperties);
        AuthenticatedPrincipal principal = provider.parseAccessToken(pair.accessToken());

        tokenService.logout(pair.refreshToken(), principal);

        AuthRefreshToken stored = findByHash(pair.refreshToken());
        assertThat(stored.isRevoked()).isTrue();
        assertThat(tokenBlacklist.blacklistedJtis).contains(principal.jti());
    }

    @Test
    void revokeAllForUserRevokesEveryToken() {
        tokenService.issue(USER_ID, USERNAME, ROLE);
        tokenService.issue(USER_ID, USERNAME, ROLE);

        tokenService.revokeAllForUser(USER_ID);

        assertThat(refreshTokenRepository.revokeAllCalls).contains(USER_ID);
        assertThat(refreshTokenRepository.savedTokens.values())
                .allSatisfy(t -> assertThat(t.isRevoked()).isTrue());
    }

    /**
     * Simulates the refresh-rotation concurrency race: a second caller's {@code claimForRotation}
     * loses to a concurrent winner (affected == 0) even though {@code findByTokenHash} still saw the
     * token as active. The loser must trigger the reuse cascade — burn the whole family and throw
     * {@link ErrorCode#AUTH_REFRESH_TOKEN_REUSED} — rather than issuing a second orphaned valid token.
     */
    @Test
    void concurrentRotationLoserTriggersReuseCascade() {
        TokenPair initial = tokenService.issue(USER_ID, USERNAME, ROLE);

        // Force the new affected==0 branch distinctly from the earlier revoked-token reuse branch:
        // the token is still active (findByTokenHash returns it unrevoked), but claimForRotation
        // reports it was already claimed by a concurrent winner.
        claimForRotationOverride = 0;

        assertThatThrownBy(() -> tokenService.refresh(initial.refreshToken()))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_REFRESH_TOKEN_REUSED);

        // The loser burned the whole family for this user.
        assertThat(refreshTokenRepository.revokeAllCalls).contains(USER_ID);
        assertThat(refreshTokenRepository.savedTokens.values())
                .allSatisfy(t -> assertThat(t.isRevoked()).isTrue());

        // Reset so other tests in this class aren't affected (defensive — @BeforeEach resets state,
        // but the override lives on the test instance, not the fake).
        claimForRotationOverride = null;
    }

    // --- helpers ------------------------------------------------------------------

    private AuthRefreshToken findByHash(String rawRefreshToken) {
        String hash = TokenService.sha256Hex(rawRefreshToken);
        return refreshTokenRepository.savedTokens.values().stream()
                .filter(t -> t.tokenHash().equals(hash))
                .findFirst()
                .orElseThrow();
    }

    private UserQueryService stubUserQueryService() {
        // Subclass UserQueryService and override the single method TokenService calls, so the
        // production type stays the injected dependency (no new interface needed). userRepository
        // is unused on this path, so null is safe.
        return new UserQueryService((UserRepository) null) {
            @Override
            public UserAccount findAccountById(Long userId) {
                return new UserAccount(
                        userId, USERNAME, "alice@example.com", null,
                        "hash", UserStatus.ACTIVE, ROLE, true, false,
                        null, null, null
                );
            }
        };
    }

    // Non-static so it can read the outer TokenServiceTest.claimForRotationOverride test seam.
    private final class FakeRefreshTokenRepository implements AuthRefreshTokenRepository {
        private final Map<Long, AuthRefreshToken> savedTokens = new ConcurrentHashMap<>();
        private final java.util.List<Long> revokeAllCalls = new java.util.ArrayList<>();
        private final AtomicLong idSeq = new AtomicLong(0L);

        @Override
        public AuthRefreshToken save(AuthRefreshToken token) {
            Long id = token.id() != null ? token.id() : idSeq.incrementAndGet();
            AuthRefreshToken persisted = new AuthRefreshToken(
                    id, token.userId(), token.tokenHash(), token.tokenJti(),
                    token.deviceId(), token.userAgent(), token.ipAddress(),
                    token.expiresAt(), token.revokedAt(), token.replacedByTokenId(),
                    token.createdAt(), token.updatedAt()
            );
            savedTokens.put(id, persisted);
            return persisted;
        }

        @Override
        public Optional<AuthRefreshToken> findByTokenHash(String tokenHash) {
            return savedTokens.values().stream()
                    .filter(t -> t.tokenHash().equals(tokenHash))
                    .findFirst();
        }

        @Override
        public void revoke(Long tokenId, LocalDateTime revokedAt, Long replacedByTokenId) {
            AuthRefreshToken existing = savedTokens.get(tokenId);
            if (existing == null) {
                return;
            }
            savedTokens.put(tokenId, new AuthRefreshToken(
                    existing.id(), existing.userId(), existing.tokenHash(), existing.tokenJti(),
                    existing.deviceId(), existing.userAgent(), existing.ipAddress(),
                    existing.expiresAt(), revokedAt, replacedByTokenId,
                    existing.createdAt(), existing.updatedAt()
            ));
        }

        @Override
        public void revokeAllByUserId(Long userId, LocalDateTime revokedAt) {
            revokeAllCalls.add(userId);
            for (AuthRefreshToken t : savedTokens.values()) {
                if (t.userId().equals(userId) && !t.isRevoked()) {
                    revoke(t.id(), revokedAt, t.replacedByTokenId());
                }
            }
        }

        @Override
        public int claimForRotation(Long tokenId, LocalDateTime revokedAt) {
            // Test seam: a test may force the concurrent-loser branch (affected == 0) for a token
            // that the earlier findByTokenHash returned as active.
            if (claimForRotationOverride != null) {
                return claimForRotationOverride;
            }
            AuthRefreshToken existing = savedTokens.get(tokenId);
            if (existing == null || existing.isRevoked()) {
                return 0;
            }
            revoke(tokenId, revokedAt, existing.replacedByTokenId());
            return 1;
        }

        @Override
        public void linkReplacement(Long oldTokenId, Long newTokenId) {
            AuthRefreshToken existing = savedTokens.get(oldTokenId);
            if (existing == null) {
                return;
            }
            savedTokens.put(oldTokenId, new AuthRefreshToken(
                    existing.id(), existing.userId(), existing.tokenHash(), existing.tokenJti(),
                    existing.deviceId(), existing.userAgent(), existing.ipAddress(),
                    existing.expiresAt(), existing.revokedAt(), newTokenId,
                    existing.createdAt(), existing.updatedAt()
            ));
        }
    }

    private static final class InMemoryTokenBlacklist extends RedisTokenBlacklist {
        final Set<String> blacklistedJtis = new HashSet<>();

        InMemoryTokenBlacklist() {
            super(null); // StringRedisTemplate unused; both methods overridden.
        }

        @Override
        public void blacklist(String jti, long ttlSeconds) {
            if (jti != null && !jti.isBlank()) {
                blacklistedJtis.add(jti);
            }
        }

        @Override
        public boolean isBlacklisted(String jti) {
            return jti != null && blacklistedJtis.contains(jti);
        }
    }
}
