package com.platform.cache.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.cache.feed.domain.PostFragment;
import com.platform.cache.feed.dto.FeedPageResponse;
import com.platform.cache.feed.hotkey.FeedHotKeyDetector;
import com.platform.cache.feed.infrastructure.redis.FragmentStore;
import com.platform.cache.feed.infrastructure.redis.FragmentStore.MultiGetResult;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import com.platform.content.application.ContentQueryService;
import com.platform.content.domain.ContentPost;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * Pure unit test for {@link FeedReadService}. The service is constructed directly with Mockito
 * mocks for the concrete stores ({@link FragmentStore} / {@link SkeletonStore}) and the
 * {@link FeedSourceQuery} interface, plus a real in-memory {@link CaffeineCacheManager} standing
 * in for the L2 — no Spring context, no Redis, no DB. Mirrors the construct-directly pattern of
 * {@code CounterReadServiceTest}.
 *
 * <p><b>Mocking strategy (design decision #2 + #3):</b>
 * <ul>
 *   <li>{@link FragmentStore} / {@link SkeletonStore} are concrete {@code @Repository} classes, not
 *       interfaces — but Mockito mocks concrete classes just fine via {@code mock(Class)}, so no
 *       interface extraction is needed. Their methods are not {@code final}.</li>
 *   <li>{@link FeedSourceQuery} is already an interface; a Mockito mock is the natural fit.</li>
 *   <li>The L2 {@link CacheManager} is a real {@link CaffeineCacheManager} (Caffeine is already a
 *       project dependency via Spring Boot's cache starter). {@link FeedReadService} resolves the
 *       named caches lazily via {@code getCache(name)}, so we pre-register the four cache names it
 *       uses. This exercises the real {@code Cache.ValueWrapper} path the production code reads.</li>
 * </ul>
 */
class FeedReadServiceTest {

    private static final Long AUTHOR = 7L;
    private static final String AUTHOR_NAME = "alice";
    private static final LocalDateTime PUB_AT = LocalDateTime.of(2026, 6, 20, 12, 0);

    private FragmentStore fragmentStore;
    private SkeletonStore skeletonStore;
    private FeedSourceQuery sourceQuery;
    private CacheManager l2CacheManager;
    private FeedCacheProperties props;
    private CounterReadService counterReadService;
    private ContentQueryService contentQueryService;
    private UserQueryService userQueryService;
    private FeedSingleFlight singleFlight;
    private FeedOverlayService overlayService;
    private FeedHotKeyDetector hotKeyDetector;

    private FeedReadService service;

    @BeforeEach
    void setUp() {
        fragmentStore = mock(FragmentStore.class);
        skeletonStore = mock(SkeletonStore.class);
        sourceQuery = mock(FeedSourceQuery.class);
        counterReadService = mock(CounterReadService.class);
        contentQueryService = mock(ContentQueryService.class);
        userQueryService = mock(UserQueryService.class);

        // Real in-memory Caffeine L2 with the four named caches the orchestrator uses.
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                "feed-public-head", "feed-public-cursor",
                "feed-user-head", "feed-user-cursor");
        l2CacheManager = mgr;

        props = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 200, 10,
                FeedCacheProperties.HotKey.defaults());

        // Pass-through single-flight: runs the supplier directly with no local/distributed dedup.
        // The orchestrator's source-backfill wiring is exercised here; the single-flight's own
        // dedup behavior has its own dedicated unit test (FeedSingleFlightTest). A null
        // StringRedisTemplate is safe because the override never reaches Redis.
        singleFlight = new FeedSingleFlight(null, props) {
            @Override
            public <T> T executeWithLock(String key, java.util.function.Supplier<T> supplier) {
                return supplier.get();
            }
        };

        // Real overlay service wrapping the mocked CounterReadService — exercises the real
        // hasActedBatch → likedByMe/favedByMe wiring; individual tests stub the batch call.
        overlayService = new FeedOverlayService(counterReadService);

        // Mocked hot-key detector. Default: ttlFor is a pass-through (returns the base TTL) so the
        // existing TTL assertions stay intact; individual tests override ttlFor to assert inheritance.
        hotKeyDetector = mock(FeedHotKeyDetector.class);
        lenient().when(hotKeyDetector.ttlFor(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        service = new FeedReadService(
                fragmentStore, skeletonStore, sourceQuery, l2CacheManager, props,
                counterReadService, contentQueryService, userQueryService, singleFlight,
                overlayService, hotKeyDetector, new ObjectMapper());

        // Default: NULL-sentinel never set; skeleton empty. Individual tests override.
        lenient().when(skeletonStore.isNullSentinel(any())).thenReturn(false);
    }

    // --- L2 -----------------------------------------------------------------

    @Test
    void l2HitReturnsDirectlyWithoutTouchingLowerTiers() {
        // Seed the L2 cache directly with a fully-assembled page.
        PostFragment frag = fragment(1L);
        FeedPageResponse cached = new FeedPageResponse(
                List.of(item(frag)), true, new Cursor(PUB_AT, 1L));
        l2CacheManager.getCache("feed-public-head").put(FeedRedisPageKeys.publicHead(20), cached);

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result).isEqualTo(cached);
        assertThat(result.items()).hasSize(1);
        // No skeleton / source / fragment work at all.
        verifyNoInteractions(skeletonStore);
        verifyNoInteractions(sourceQuery);
        verifyNoInteractions(fragmentStore);
    }

    @Test
    void readPageRecordsPageKeyEvenOnL2Hit() {
        // Heat is recorded at the very top of readPage, BEFORE the L2 check — so an L2 hit still
        // counts toward the pageKey's heat. This keeps a page that is well-served by L2 visible to
        // the hot-key detector (so its L1/L0 TTLs still get extended on rebuild).
        PostFragment frag = fragment(1L);
        FeedPageResponse cached = new FeedPageResponse(
                List.of(item(frag)), true, new Cursor(PUB_AT, 1L));
        l2CacheManager.getCache("feed-public-head").put(FeedRedisPageKeys.publicHead(20), cached);

        service.readPublicFeed(null, 20, null);

        verify(hotKeyDetector, times(1)).record(FeedRedisPageKeys.publicHead(20));
    }

    // --- L1 hit ----------------------------------------------------------

    @Test
    void l2MissL1HitAssemblesFromL0Fragments() {
        List<Long> ids = List.of(1L, 2L);
        FeedPage skeleton = new FeedPage(ids, true, new Cursor(PUB_AT.minusMinutes(1), 2L));
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(skeleton));
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(
                new MultiGetResult(
                        Map.of(1L, fragment(1L), 2L, fragment(2L)), false));

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).postId()).isEqualTo(1L);
        assertThat(result.items().get(1).postId()).isEqualTo(2L);
        assertThat(result.hasMore()).isTrue();
        // L1 hit → no source query.
        verifyNoInteractions(sourceQuery);
        verify(fragmentStore, never()).put(any(), anyInt());
        // Skeleton was read, never written.
        verify(skeletonStore, never()).put(any(), any(), anyInt());
    }

    // --- L1 miss → source backfill ------------------------------------------

    @Test
    void l2L1MissBackfillsFromSourceAndWritesL1() {
        List<Long> ids = List.of(10L, 11L);
        FeedPage fresh = new FeedPage(ids, false, null);
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.empty());
        when(sourceQuery.findPublicFeedHead(20)).thenReturn(fresh);
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(
                new MultiGetResult(Map.of(10L, fragment(10L), 11L, fragment(11L)), false));

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).extracting("postId").containsExactly(10L, 11L);
        assertThat(result.hasMore()).isFalse();
        // Source was queried once; skeleton written once with the head TTL + jitter base.
        verify(sourceQuery, times(1)).findPublicFeedHead(20);
        verify(skeletonStore, times(1)).put(eq(FeedRedisPageKeys.publicHead(20)), eq(fresh), eq(4));
        // Promoted to L2.
        Object promoted = l2CacheManager.getCache("feed-public-head")
                .get(FeedRedisPageKeys.publicHead(20)).get();
        assertThat(promoted).isEqualTo(result);
    }

    @Test
    void emptySourceWritesNullSentinelAndReturnsEmpty() {
        FeedPage empty = new FeedPage(List.of(), false, null);
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.empty());
        when(sourceQuery.findPublicFeedHead(20)).thenReturn(empty);

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(skeletonStore, times(1)).putNullSentinel(FeedRedisPageKeys.publicHead(20));
        // No skeleton write, no fragment fetch, no L2 promotion of a non-empty page.
        verify(skeletonStore, never()).put(any(), any(), anyInt());
        verifyNoInteractions(fragmentStore);
    }

    // --- Tombstone → rebuild ----------------------------------------------

    @Test
    void tombstoneTriggersPageRebuildFromSource() {
        // First skeleton contains id 1; id 1 is now tombstoned → rebuild fetches id 2 only.
        List<Long> staleIds = List.of(1L);
        List<Long> rebuiltIds = List.of(2L);
        FeedPage stale = new FeedPage(staleIds, true, new Cursor(PUB_AT, 1L));
        FeedPage rebuilt = new FeedPage(rebuiltIds, false, null);

        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(stale));
        when(sourceQuery.findPublicFeedHead(20)).thenReturn(rebuilt);
        // Stale skeleton: the single combined MGET reports a tombstone (triggers rebuild).
        when(fragmentStore.multiGetWithTombstones(staleIds)).thenReturn(
                new MultiGetResult(Map.of(), true));
        // After rebuild: plain multiGet assembles the fresh skeleton (no tombstone re-check needed).
        when(fragmentStore.multiGet(rebuiltIds)).thenReturn(Map.of(2L, fragment(2L)));

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).extracting("postId").containsExactly(2L);
        verify(sourceQuery, times(1)).findPublicFeedHead(20); // rebuilt once
        verify(skeletonStore, times(1)).put(eq(FeedRedisPageKeys.publicHead(20)), eq(rebuilt), eq(4));
    }

    // --- Missing-fragment backfill -----------------------------------------

    @Test
    void missingFragmentsBatchBackfilledFromContentCounterUser() {
        List<Long> ids = List.of(100L, 101L);
        FeedPage skeleton = new FeedPage(ids, true, new Cursor(PUB_AT, 101L));
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(skeleton));

        // 100L present; 101L missing → must be backfilled.
        when(fragmentStore.multiGetWithTombstones(ids)).thenAnswer(inv -> {
            List<Long> asked = inv.getArgument(0);
            Map<Long, PostFragment> out = new java.util.LinkedHashMap<>();
            for (Long id : asked) {
                if (id == 100L) {
                    out.put(id, fragment(100L));
                }
            }
            return new MultiGetResult(out, false);
        });

        // Content / counter / user return the metadata for the missing post.
        when(contentQueryService.findPostById(101L)).thenReturn(Optional.of(contentPost(101L)));
        when(counterReadService.getArticleCounters(101L)).thenReturn(counters(101L));
        when(userQueryService.findAccountById(AUTHOR)).thenReturn(account());

        // L0 TTL inherits the pageKey's current heat: the detector maps base 300 → 155 here.
        when(hotKeyDetector.ttlFor(FeedRedisPageKeys.publicHead(20), 300)).thenReturn(155);

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).extracting("postId").containsExactly(100L, 101L);
        // Backfilled fragment written to L0 once with the heat-inherited TTL (155, not the base 300).
        verify(fragmentStore, times(1)).put(any(PostFragment.class), eq(155));
        // The backfilled item carries the metadata + counts sourced from the three services.
        var backfilled = result.items().get(1);
        assertThat(backfilled.title()).isEqualTo("title-101");
        assertThat(backfilled.authorName()).isEqualTo(AUTHOR_NAME);
        assertThat(backfilled.likeCount()).isEqualTo(42L);
        assertThat(backfilled.viewCount()).isEqualTo(500L);
    }

    @Test
    void missingFragmentForDeletedPostIsSkippedNotAssembled() {
        List<Long> ids = List.of(200L);
        FeedPage skeleton = new FeedPage(ids, true, new Cursor(PUB_AT, 200L));
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(skeleton));
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(new MultiGetResult(Map.of(), false));
        when(contentQueryService.findPostById(200L)).thenReturn(Optional.empty()); // deleted

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).isEmpty();
        verify(fragmentStore, never()).put(any(), anyInt());
    }

    // --- NULL sentinel short-circuit --------------------------------------

    @Test
    void nullSentinelReturnsEmptyWithoutSourceOrFragments() {
        when(skeletonStore.isNullSentinel(FeedRedisPageKeys.publicHead(20))).thenReturn(true);

        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items()).isEmpty();
        verifyNoInteractions(sourceQuery);
        verifyNoInteractions(fragmentStore);
        // Skeleton.get is NOT consulted either — sentinel is checked first.
        verify(skeletonStore, never()).get(any());
    }

    // --- size clamping -----------------------------------------------------

    @Test
    void sizeClampedIntoRange() {
        FeedPage empty = new FeedPage(List.of(), false, null);
        when(sourceQuery.findPublicFeedHead(50)).thenReturn(empty);

        service.readPublicFeed(null, 9999, null); // clamped down to 50

        verify(sourceQuery).findPublicFeedHead(50);
    }

    @Test
    void zeroOrNegativeSizeClampedToOne() {
        FeedPage empty = new FeedPage(List.of(), false, null);
        when(sourceQuery.findPublicFeedHead(1)).thenReturn(empty);

        service.readPublicFeed(null, 0, null);

        verify(sourceQuery).findPublicFeedHead(1);
    }

    // --- user feed ---------------------------------------------------------

    @Test
    void readUserFeedUsesUserKeysAndSourceQueries() {
        List<Long> ids = List.of(5L);
        FeedPage skeleton = new FeedPage(ids, false, null);
        when(skeletonStore.get(FeedRedisPageKeys.userHead(AUTHOR, 10))).thenReturn(Optional.of(skeleton));
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(
                new MultiGetResult(Map.of(5L, fragment(5L)), false));

        FeedPageResponse result = service.readUserFeed(AUTHOR, null, 10);

        assertThat(result.items()).extracting("postId").containsExactly(5L);
        verify(sourceQuery, never()).findUserFeedHead(any(), anyInt()); // L1 hit → no source call
        verify(fragmentStore, times(1)).multiGetWithTombstones(ids);
    }

    // --- overlay (Task 9) --------------------------------------------------

    @Test
    void overlayDelegatesToOverlayServiceForAuthenticatedReader() {
        List<Long> ids = List.of(1L);
        FeedPage skeleton = new FeedPage(ids, false, null);
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(skeleton));
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(
                new MultiGetResult(Map.of(1L, fragment(1L)), false));
        // The overlay service's single batched call returns liked=true, faved=false for post 1.
        when(counterReadService.hasActedBatch(
                eq(42L), eq(CounterEntityType.ARTICLE), eq(ids),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV))))
                .thenReturn(Map.of(
                        CounterMetric.LIKE, Map.of(1L, true),
                        CounterMetric.FAV, Map.of(1L, false)));

        FeedPageResponse result = service.readPublicFeed(null, 20, 42L);

        assertThat(result.items().get(0).likedByMe()).isTrue();
        assertThat(result.items().get(0).favedByMe()).isFalse();
        verify(counterReadService, times(1)).hasActedBatch(
                eq(42L), eq(CounterEntityType.ARTICLE), eq(ids),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV)));
    }

    @Test
    void overlayIsSkippedForAnonymousReader() {
        List<Long> ids = List.of(1L);
        FeedPage skeleton = new FeedPage(ids, false, null);
        when(skeletonStore.get(FeedRedisPageKeys.publicHead(20))).thenReturn(Optional.of(skeleton));
        when(fragmentStore.multiGetWithTombstones(ids)).thenReturn(
                new MultiGetResult(Map.of(1L, fragment(1L)), false));

        // Anonymous reader (null requester) — overlay is skipped; bits stay null (the sentinel for
        // "no overlay applied").
        FeedPageResponse result = service.readPublicFeed(null, 20, null);

        assertThat(result.items().get(0).likedByMe()).isNull();
        assertThat(result.items().get(0).favedByMe()).isNull();
        verify(counterReadService, never()).hasActedBatch(
                anyLong(), any(), any(), any());
    }

    // --- helpers -----------------------------------------------------------

    /** The package-private page-key builders live on the production {@link FeedRedisKeys} type. */
    private static final class FeedRedisPageKeys {
        static String publicHead(int size) {
            return com.platform.cache.feed.infrastructure.redis.FeedRedisKeys.publicHead(size);
        }

        static String userHead(Long uid, int size) {
            return com.platform.cache.feed.infrastructure.redis.FeedRedisKeys.userHead(uid, size);
        }
    }

    private static PostFragment fragment(long postId) {
        return new PostFragment(
                postId, AUTHOR, AUTHOR_NAME, "cover-" + postId,
                "title-" + postId, "summary-" + postId, PUB_AT,
                10L * postId, 1L * postId, 100L * postId, 0L, 0L);
    }

    private static com.platform.cache.feed.dto.FeedItemResponse item(PostFragment f) {
        return new com.platform.cache.feed.dto.FeedItemResponse(
                f.postId(), f.authorId(), f.authorName(), f.cover(), f.title(), f.summary(),
                f.publishedAt(), f.likeCount(), f.favCount(), f.viewCount(),
                f.commentCount(), f.shareCount(), null, null);
    }

    private static ContentPost contentPost(long postId) {
        return new ContentPost(
                postId, AUTHOR, "req-" + postId, "title-" + postId, "summary-" + postId,
                "cover-" + postId,
                com.platform.content.domain.PostStatus.PUBLISHED,
                com.platform.content.domain.PostVisibility.PUBLIC,
                com.platform.content.domain.PublishStage.PUBLISHED,
                PUB_AT, PUB_AT.minusDays(1), PUB_AT.minusDays(1));
    }

    private static ArticleCountersResponse counters(long postId) {
        return new ArticleCountersResponse(postId, 42L, 3L, 500L, 7L, 2L);
    }

    private static UserAccount account() {
        return new UserAccount(
                AUTHOR, AUTHOR_NAME, "alice@example.com", null, "hash",
                com.platform.user.domain.UserStatus.ACTIVE,
                com.platform.user.domain.UserRole.USER,
                true, false, null, null, null);
    }
}
