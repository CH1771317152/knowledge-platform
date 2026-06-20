package com.platform.auth.infrastructure.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link JwtTokenProvider}. Constructs the provider directly with a real
 * {@link AuthProperties} (>=32-byte secret) — no Spring context, so it runs under the {@code test}
 * profile where Redis/DataSource autoconfiguration is excluded.
 */
class JwtTokenProviderTest {

    private static final String ISSUER = "knowledge-platform";
    // 64 chars -> 64 bytes, comfortably above the 32-byte HS256 minimum.
    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789abcdef";

    private AuthProperties properties(long accessTokenTtlSeconds) {
        return new AuthProperties(
                new AuthProperties.Jwt(ISSUER, SECRET, accessTokenTtlSeconds),
                new AuthProperties.RefreshToken(1209600L, 48),
                new AuthProperties.Verification(300L, 60L, 5, 5, 6),
                new AuthProperties.Sender("logging", false)
        );
    }

    @Test
    void createsAndParsesAccessToken() {
        JwtTokenProvider provider = new JwtTokenProvider(properties(900L));
        String jti = UUID.randomUUID().toString();

        String token = provider.createAccessToken(42L, "alice", UserRole.ADMIN, jti);

        AuthenticatedPrincipal principal = provider.parseAccessToken(token);
        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.role()).isEqualTo(UserRole.ADMIN);
        assertThat(principal.jti()).isEqualTo(jti);
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtTokenProvider provider = new JwtTokenProvider(properties(1L));
        String token = provider.createAccessToken(7L, "bob", UserRole.USER, UUID.randomUUID().toString());

        // TTL is 1s; wait past it so JJWT raises ExpiredJwtException -> AUTH_TOKEN_EXPIRED.
        Thread.sleep(Duration.ofMillis(1200).toMillis());

        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void rejectsRefreshTokenTypeAsAccessToken() {
        JwtTokenProvider provider = new JwtTokenProvider(properties(900L));
        // Mint a token with typ="refresh" using the same secret + issuer, as an attacker (or a
        // buggy caller) might try to replay a refresh token against a protected endpoint.
        Date now = new Date();
        String refreshTypedToken = Jwts.builder()
                .issuer(ISSUER)
                .subject("99")
                .id(UUID.randomUUID().toString())
                .claim(JwtTokenProvider.CLAIM_TYPE, "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.parseAccessToken(refreshTypedToken))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void rejectsTamperedSignature() {
        JwtTokenProvider provider = new JwtTokenProvider(properties(900L));
        String token = provider.createAccessToken(1L, "u", UserRole.USER, UUID.randomUUID().toString());
        // Tamper the FIRST character of the signature segment by flipping its low bit. The first
        // Base64URL char of a signature segment always carries 6 meaningful bits (no padding here),
        // so any bit flip is guaranteed to change the decoded signature bytes — unlike the last
        // char, whose 4 trailing padding bits make ~25% of single-char swaps decode to identical
        // bytes and make this test probabilistically flaky.
        String[] parts = token.split("\\.", 3);
        char first = parts[2].charAt(0);
        char tamperedFirst = (char) (first ^ 1);
        String tampered = parts[0] + "." + parts[1] + "." + tamperedFirst + parts[2].substring(1);

        assertThatThrownBy(() -> provider.parseAccessToken(tampered))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void failsFastWhenSecretTooShort() {
        AuthProperties bad = new AuthProperties(
                new AuthProperties.Jwt(ISSUER, "short", 900L),
                new AuthProperties.RefreshToken(1209600L, 48),
                new AuthProperties.Verification(300L, 60L, 5, 5, 6),
                new AuthProperties.Sender("logging", false)
        );

        assertThatThrownBy(() -> new JwtTokenProvider(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
