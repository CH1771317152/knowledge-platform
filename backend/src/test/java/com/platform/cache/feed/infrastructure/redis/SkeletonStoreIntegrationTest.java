package com.platform.cache.feed.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.domain.FeedPage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration coverage for {@link SkeletonStore} against a real Redis (localhost:6379) under the
 * {@code integration} profile. Each test uses a globally-unique pageKey so skeleton/sentinel keys
 * never collide, and {@link #cleanup()} drops every key touched at the end of each test. Mirrors
 * the layout of {@link FragmentStoreIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("integration")
class SkeletonStoreIntegrationTest {

    @Autowired
    private SkeletonStore store;

    @Autowired
    private StringRedisTemplate template;

    @Autowired
    private FeedCacheProperties props;

    private final List<String> pageKeys = new ArrayList<>();

    /** A unique pageKey so skeleton/sentinel keys never collide across tests. */
    private String uniquePageKey() {
        String key = "feed:test:" + UUID.randomUUID();
        pageKeys.add(key);
        return key;
    }

    private static FeedPage sample() {
        return new FeedPage(
                List.of(101L, 102L, 103L),
                true,
                new Cursor(LocalDateTime.of(2026, 6, 21, 10, 30, 0), 103L));
    }

    @AfterEach
    void cleanup() {
        if (!pageKeys.isEmpty()) {
            List<String> keys = new ArrayList<>(pageKeys.size() * 2);
            for (String pk : pageKeys) {
                keys.add(FeedRedisKeys.skeleton(pk));
                keys.add(FeedRedisKeys.nullSentinel(pk));
            }
            template.delete(keys);
            pageKeys.clear();
        }
    }

    @Test
    void putAndGetRoundTripsAllFields() {
        String pageKey = uniquePageKey();
        FeedPage original = sample();

        store.put(pageKey, original, props.l1().headTtlSeconds());

        Optional<FeedPage> read = store.get(pageKey);
        assertThat(read).isPresent();
        FeedPage page = read.get();
        assertThat(page.ids()).isEqualTo(original.ids());
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
        assertThat(page.nextCursor().timestamp()).isEqualTo(original.nextCursor().timestamp());
        assertThat(page.nextCursor().id()).isEqualTo(original.nextCursor().id());
    }

    @Test
    void getReturnsEmptyForMissingPage() {
        String pageKey = uniquePageKey();
        assertThat(store.get(pageKey)).isEmpty();
        assertThat(store.isNullSentinel(pageKey)).isFalse();
    }

    @Test
    void putNullSentinelMakesGetStillEmptyButSentinelTrue() {
        String pageKey = uniquePageKey();

        store.putNullSentinel(pageKey);

        // A NULL sentinel means "page is provably empty" — get returns empty...
        assertThat(store.get(pageKey)).isEmpty();
        // ...but the explicit probe distinguishes it from a plain cache miss.
        assertThat(store.isNullSentinel(pageKey)).isTrue();
    }

    @Test
    void isNullSentinelFalseForMissingAndForLiveSkeleton() {
        String missing = uniquePageKey();
        String live = uniquePageKey();
        store.put(live, sample(), props.l1().headTtlSeconds());

        assertThat(store.isNullSentinel(missing)).isFalse();
        assertThat(store.isNullSentinel(live)).isFalse();
    }

    @Test
    void deleteClearsSkeleton() {
        String pageKey = uniquePageKey();
        store.put(pageKey, sample(), props.l1().headTtlSeconds());
        assertThat(store.get(pageKey)).isPresent();

        store.delete(pageKey);

        assertThat(store.get(pageKey)).isEmpty();
        assertThat(template.opsForValue().get(FeedRedisKeys.skeleton(pageKey))).isNull();
    }

    @Test
    void deleteAlsoClearsNullSentinel() {
        String pageKey = uniquePageKey();
        store.putNullSentinel(pageKey);
        assertThat(store.isNullSentinel(pageKey)).isTrue();

        store.delete(pageKey);

        assertThat(store.isNullSentinel(pageKey)).isFalse();
        assertThat(template.opsForValue().get(FeedRedisKeys.nullSentinel(pageKey))).isNull();
    }

    @Test
    void putAppliesPositiveTtl() {
        String pageKey = uniquePageKey();
        int baseTtl = props.l1().headTtlSeconds();

        store.put(pageKey, sample(), baseTtl);

        Long ttlSeconds = template.getExpire(FeedRedisKeys.skeleton(pageKey));
        assertThat(ttlSeconds).as("skeleton must have a positive TTL").isPositive();
    }

    @Test
    void putAppliesTtlJitterAboveBase() {
        // The jitter formula is ttl * (1 + rand(0..jitterRatio)); jitter can be 0, so loop a few
        // times to make it overwhelmingly likely we observe a jittered (above-base) TTL.
        String pageKey = uniquePageKey();
        int baseTtl = props.l1().headTtlSeconds();

        boolean observedJittered = false;
        for (int i = 0; i < 8 && !observedJittered; i++) {
            String pk = uniquePageKey();
            store.put(pk, sample(), baseTtl);
            Long ttlSeconds = template.getExpire(FeedRedisKeys.skeleton(pk));
            assertThat(ttlSeconds).as("skeleton must have a positive TTL").isPositive();
            // The TTL can fall at most a couple of seconds below baseTtl due to SET-to-getExpire
            // latency; jittered values sit above baseTtl. Allow a small slack on the lower bound.
            long lowerBound = baseTtl - 3L;
            assertThat(ttlSeconds).as("TTL must be >= base-slack")
                    .isGreaterThanOrEqualTo(lowerBound);
            if (ttlSeconds > baseTtl) {
                observedJittered = true;
            }
        }
        // Allow the upper-bound check to fail the test only when we did observe a jittered value.
        long upperBound = (long) Math.ceil(baseTtl * (1.0 + props.jitterRatio())) + 5L;
        Long ttlSeconds = template.getExpire(FeedRedisKeys.skeleton(pageKey));
        if (ttlSeconds != null && ttlSeconds > baseTtl) {
            assertThat(ttlSeconds).as("TTL with jitter must not exceed base*(1+jitter)+slack")
                    .isLessThanOrEqualTo(upperBound);
        }
        // Even if every iteration happened to draw jitter==0, at least assert we observed a
        // jittered value across the retries (the formula's randomness makes this astronomically
        // unlikely to fail).
        assertThat(observedJittered).as("expected to observe at least one TTL above base (jitter)")
                .isTrue();
    }

    @Test
    void putNullSentinelAppliesShortFixedTtl() {
        String pageKey = uniquePageKey();
        store.putNullSentinel(pageKey);

        Long ttlSeconds = template.getExpire(FeedRedisKeys.nullSentinel(pageKey));
        assertThat(ttlSeconds).as("null sentinel must have a positive TTL").isPositive();
        // Sentinel TTL is a fixed 30s window; allow small slack for SET-to-getExpire latency.
        assertThat(ttlSeconds).as("sentinel TTL must be within the 30s window + slack")
                .isLessThanOrEqualTo(30L + 5L);
    }
}
