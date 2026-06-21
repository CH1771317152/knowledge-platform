package com.platform.cache.feed.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.PostFragment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration coverage for {@link FragmentStore} against a real Redis (localhost:6379) under the
 * {@code integration} profile. Each test uses a globally-unique postId so fragment keys never
 * collide, and {@link #cleanup()} drops every key touched at the end of each test.
 */
@SpringBootTest
@ActiveProfiles("integration")
class FragmentStoreIntegrationTest {

    @Autowired
    private FragmentStore store;

    @Autowired
    private StringRedisTemplate template;

    @Autowired
    private FeedCacheProperties props;

    private final List<Long> postIds = new ArrayList<>();

    /** A unique, monotone-ish postId large enough to look real but never collide with content rows. */
    private long uniquePostId() {
        long id = 8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits());
        postIds.add(id);
        return id;
    }

    private static PostFragment sample(Long postId) {
        return new PostFragment(
                postId,
                42L,
                "Author Name",
                "cover-key-" + postId,
                "Title " + postId,
                "Summary for " + postId,
                LocalDateTime.of(2026, 6, 21, 10, 30, 0),
                11L, 12L, 13L, 14L, 15L);
    }

    @AfterEach
    void cleanup() {
        if (!postIds.isEmpty()) {
            List<String> keys = new ArrayList<>(postIds.size());
            for (Long pid : postIds) {
                keys.add(FeedRedisKeys.fragment(pid));
            }
            template.delete(keys);
            postIds.clear();
        }
    }

    @Test
    void putAndGetRoundTripsAllFields() {
        long pid = uniquePostId();
        PostFragment original = sample(pid);

        store.put(original, props.l0().ttlSeconds());

        Optional<PostFragment> read = store.get(pid);
        assertThat(read).isPresent();
        PostFragment fragment = read.get();
        assertThat(fragment.postId()).isEqualTo(original.postId());
        assertThat(fragment.authorId()).isEqualTo(original.authorId());
        assertThat(fragment.authorName()).isEqualTo(original.authorName());
        assertThat(fragment.cover()).isEqualTo(original.cover());
        assertThat(fragment.title()).isEqualTo(original.title());
        assertThat(fragment.summary()).isEqualTo(original.summary());
        assertThat(fragment.publishedAt()).isEqualTo(original.publishedAt());
        assertThat(fragment.likeCount()).isEqualTo(original.likeCount());
        assertThat(fragment.favCount()).isEqualTo(original.favCount());
        assertThat(fragment.viewCount()).isEqualTo(original.viewCount());
        assertThat(fragment.commentCount()).isEqualTo(original.commentCount());
        assertThat(fragment.shareCount()).isEqualTo(original.shareCount());
    }

    @Test
    void getReturnsEmptyForMissingPost() {
        long pid = uniquePostId();
        assertThat(store.get(pid)).isEmpty();
    }

    @Test
    void multiGetReturnsPresentAndOmitsMissing() {
        long present1 = uniquePostId();
        long present2 = uniquePostId();
        long missing = uniquePostId();

        store.put(sample(present1), props.l0().ttlSeconds());
        store.put(sample(present2), props.l0().ttlSeconds());

        Map<Long, PostFragment> result = store.multiGet(List.of(present1, missing, present2));
        assertThat(result).hasSize(2);
        assertThat(result).containsKeys(present1, present2);
        assertThat(result).doesNotContainKey(missing);
        assertThat(result.get(present1).title()).isEqualTo("Title " + present1);
        assertThat(result.get(present2).authorId()).isEqualTo(42L);
    }

    @Test
    void multiGetReturnsEmptyForEmptyInput() {
        assertThat(store.multiGet(List.of())).isEmpty();
    }

    @Test
    void putTombstoneIsDetectedAndGetReturnsEmpty() {
        long pid = uniquePostId();
        store.put(sample(pid), props.l0().ttlSeconds());
        assertThat(store.get(pid)).isPresent();

        store.putTombstone(pid);

        // The tombstone marker makes the post look absent to assembly...
        assertThat(store.get(pid)).isEmpty();
        // ...but the explicit probe distinguishes it from a plain missing fragment.
        assertThat(store.isTombstone(pid)).isTrue();
        assertThat(template.opsForValue().get(FeedRedisKeys.fragment(pid)))
                .isEqualTo(PostFragment.TOMBSTONE);
    }

    @Test
    void isTombstoneFalseForMissingAndForLiveFragment() {
        long missing = uniquePostId();
        long live = uniquePostId();
        store.put(sample(live), props.l0().ttlSeconds());

        assertThat(store.isTombstone(missing)).isFalse();
        assertThat(store.isTombstone(live)).isFalse();
    }

    @Test
    void multiGetOmitsTombstonesAsMissing() {
        long live = uniquePostId();
        long tombstoned = uniquePostId();

        store.put(sample(live), props.l0().ttlSeconds());
        store.putTombstone(tombstoned);

        Map<Long, PostFragment> result = store.multiGet(List.of(live, tombstoned));
        assertThat(result).containsOnlyKeys(live);
        assertThat(result).doesNotContainKey(tombstoned);
    }

    @Test
    void anyTombstoneDetectsMixedBatch() {
        long live1 = uniquePostId();
        long live2 = uniquePostId();
        long tomb = uniquePostId();

        store.put(sample(live1), props.l0().ttlSeconds());
        store.put(sample(live2), props.l0().ttlSeconds());
        store.putTombstone(tomb);

        // Mixed batch (some live, one tombstone) → true.
        assertThat(store.anyTombstone(List.of(live1, tomb, live2))).isTrue();
        // All-live batch → false.
        assertThat(store.anyTombstone(List.of(live1, live2))).isFalse();
        // All-missing batch → false (no tombstone present).
        long absent = uniquePostId();
        assertThat(store.anyTombstone(List.of(absent))).isFalse();
        // Empty input → false.
        assertThat(store.anyTombstone(List.of())).isFalse();
    }

    @Test
    void deleteRemovesFragment() {
        long pid = uniquePostId();
        store.put(sample(pid), props.l0().ttlSeconds());
        assertThat(store.get(pid)).isPresent();

        store.delete(pid);

        assertThat(store.get(pid)).isEmpty();
        assertThat(template.opsForValue().get(FeedRedisKeys.fragment(pid))).isNull();
    }

    @Test
    void deleteAlsoClearsTombstone() {
        long pid = uniquePostId();
        store.putTombstone(pid);
        assertThat(store.isTombstone(pid)).isTrue();

        store.delete(pid);

        assertThat(store.isTombstone(pid)).isFalse();
    }

    @Test
    void putAppliesPositiveTtlWithJitter() {
        long pid = uniquePostId();
        int baseTtl = props.l0().ttlSeconds();

        store.put(sample(pid), baseTtl);

        Long ttlSeconds = template.getExpire(FeedRedisKeys.fragment(pid));
        assertThat(ttlSeconds).as("fragment must have a positive TTL").isPositive();
        // Jitter extends the TTL by at most jitterRatio; allow a small slack for the time between
        // SET and getExpire.
        long upperBound = (long) Math.ceil(baseTtl * (1.0 + props.jitterRatio())) + 5L;
        assertThat(ttlSeconds).as("TTL with jitter must not exceed base*(1+jitter)+slack")
                .isLessThanOrEqualTo(upperBound);
    }

    @Test
    void putTombstoneAppliesShortFixedTtl() {
        long pid = uniquePostId();
        store.putTombstone(pid);

        Long ttlSeconds = template.getExpire(FeedRedisKeys.fragment(pid));
        assertThat(ttlSeconds).as("tombstone must have a positive TTL").isPositive();
        // Tombstone TTL is a fixed 30s window; allow small slack for SET-to-getExpire latency.
        assertThat(ttlSeconds).as("tombstone TTL must be within the 30s window + slack")
                .isLessThanOrEqualTo(30L + 5L);
    }
}
