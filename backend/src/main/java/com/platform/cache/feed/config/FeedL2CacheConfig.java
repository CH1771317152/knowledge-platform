package com.platform.cache.feed.config;

import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
public class FeedL2CacheConfig {

    @Bean("feedL2CacheManager")
    public CacheManager feedL2CacheManager(FeedCacheProperties props) {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        Caffeine<Object, Object> headSpec = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.l2().headTtlSeconds()))
                .maximumSize(props.l2().maxSize());
        Caffeine<Object, Object> cursorSpec = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.l2().cursorTtlSeconds()))
                .maximumSize(props.l2().maxSize());
        mgr.registerCustomCache("feed-public-head", headSpec.build());
        mgr.registerCustomCache("feed-user-head", headSpec.build());
        mgr.registerCustomCache("feed-public-cursor", cursorSpec.build());
        mgr.registerCustomCache("feed-user-cursor", cursorSpec.build());
        return mgr;
    }
}
