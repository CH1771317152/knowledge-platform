package com.platform.auth.infrastructure.redis;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Revocation list for access-token JTIs. Logout writes the caller's current access-token jti here
 * for the remainder of its TTL so the {@code JwtAuthenticationFilter} can reject it even though JWTs
 * are otherwise stateless.
 *
 * <p>Backed by {@code StringRedisTemplate}. Keyed {@code auth:jwt:blacklist:{jti}}. A present key
 * means "blacklisted"; the value is a sentinel {@code "1"} and the key self-expires at the access
 * token's natural expiry, so the set never grows beyond live token lifetimes.
 *
 * <p>{@code @Profile("!test")} because the {@code test} profile excludes Redis autoconfiguration;
 * {@code TokenService} (also {@code !test}) is the only production consumer, and the JWT filter
 * receives it via an {@code ObjectProvider} so the filter still loads under {@code test}.
 */
@Repository
@Profile("!test")
public class RedisTokenBlacklist {

    private static final String KEY_PREFIX = "auth:jwt:blacklist:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long ttl = Math.max(0L, ttlSeconds);
        redisTemplate.opsForValue().set(key(jti), VALUE, Duration.ofSeconds(ttl));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Boolean present = redisTemplate.hasKey(key(jti));
        return Boolean.TRUE.equals(present);
    }

    private static String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
