package com.platform.auth.infrastructure.jwt;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Mints and parses short-lived access tokens (HS256).
 *
 * <p>Access-token claims: {@code sub}=userId, {@code username}, {@code role} (enum name),
 * {@code jti} (fresh per token), {@code typ}="access", {@code iat}, {@code exp}, {@code iss}
 * (from {@link AuthProperties.Jwt#issuer()}).
 *
 * <p>Parsing is strict: signature and expiry are verified, and the {@code typ} claim must equal
 * {@code "access"} so a refresh-typed token cannot be replayed against protected endpoints.
 */
@Component
public class JwtTokenProvider {

    static final String TYPE_ACCESS = "access";
    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_TYPE = "typ";

    /** HS256 requires at least 256 bits (32 bytes) of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties authProperties;
    private final SecretKey signingKey;

    public JwtTokenProvider(AuthProperties authProperties) {
        this.authProperties = authProperties;
        // Fail fast at startup: a too-short HS256 secret would otherwise only surface at signing
        // time (and JJWT would throw a WeakKeyException deep in the stack). Surface it here with a
        // clear message. This is the required guard from the Task 1 code-review follow-up.
        byte[] secretBytes = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "platform.auth.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256, but was " + secretBytes.length + " bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createAccessToken(Long userId, String username, UserRole role, String jti) {
        AuthProperties.Jwt jwt = authProperties.jwt();
        long ttlSeconds = jwt.accessTokenTtlSeconds();
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .issuer(jwt.issuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role.name())
                .id(jti)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public AuthenticatedPrincipal parseAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(authProperties.jwt().issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // Signature failure, malformed token, wrong issuer, missing claims, etc.
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid access token");
        }

        String typ = claims.get(CLAIM_TYPE, String.class);
        if (!TYPE_ACCESS.equals(typ)) {
            // Reject refresh-typed (or any non-access) tokens replayed as access tokens.
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Token is not an access token");
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException ex) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid access token subject");
        }
        UserRole role = parseRole(claims.get(CLAIM_ROLE, String.class));
        String username = claims.get(CLAIM_USERNAME, String.class);
        String jti = claims.getId();

        return new AuthenticatedPrincipal(userId, username, role, jti);
    }

    public LocalDateTime expiresAt(String token) {
        try {
            Date expiration = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getExpiration();
            return LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault());
        } catch (ExpiredJwtException ex) {
            // An expired token still carries a parseable expiry; surface it so callers (e.g. logout)
            // can compute remaining TTL = 0 instead of treating the token as malformed.
            return LocalDateTime.ofInstant(ex.getClaims().getExpiration().toInstant(), ZoneId.systemDefault());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid access token");
        }
    }

    private static UserRole parseRole(String roleName) {
        if (roleName == null) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Access token missing role");
        }
        try {
            return UserRole.valueOf(roleName);
        } catch (IllegalArgumentException ex) {
            throw new PlatformException(ErrorCode.AUTH_TOKEN_INVALID, "Unknown access-token role: " + roleName);
        }
    }
}
