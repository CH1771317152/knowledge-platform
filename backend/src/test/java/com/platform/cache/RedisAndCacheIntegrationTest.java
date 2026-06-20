package com.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class RedisAndCacheIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void connectsToRedisAndUsesLocalCaffeineCache() {
        String key = "integration:test:" + UUID.randomUUID();

        redisTemplate.opsForValue().set(key, "ok");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ok");
        assertThat(redisTemplate.delete(key)).isTrue();

        Cache userProfileCache = cacheManager.getCache(LocalCacheNames.USER_PROFILE_DETAIL);
        assertThat(userProfileCache).isNotNull();

        userProfileCache.put("user:1", "cached-profile");

        assertThat(userProfileCache.get("user:1", String.class)).isEqualTo("cached-profile");
    }
}
