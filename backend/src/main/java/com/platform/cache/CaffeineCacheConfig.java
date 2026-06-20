package com.platform.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableCaching
@Configuration
public class CaffeineCacheConfig {

    @Bean
    @ConfigurationProperties(prefix = "platform.cache.caffeine")
    LocalCacheProperties localCacheProperties() {
        return new LocalCacheProperties();
    }

    @Bean
    CacheManager cacheManager(LocalCacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(
                LocalCacheNames.CONTENT_DETAIL,
                LocalCacheNames.USER_PROFILE_DETAIL,
                LocalCacheNames.COUNTER_SNAPSHOT
        ));
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.expireAfterWriteSeconds())));
        return manager;
    }

    public static class LocalCacheProperties {
        private long maximumSize = 10_000;
        private long expireAfterWriteSeconds = 300;

        public long maximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
        }

        public long expireAfterWriteSeconds() {
            return expireAfterWriteSeconds;
        }

        public void setExpireAfterWriteSeconds(long expireAfterWriteSeconds) {
            this.expireAfterWriteSeconds = expireAfterWriteSeconds;
        }
    }
}
