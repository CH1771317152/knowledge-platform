package com.platform.cache.feed.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit test for {@link FeedReconciliationScheduler}. The scheduler is constructed directly with a
 * Mockito-mocked {@link FeedSourceQuery} and {@link SkeletonStore} plus a real
 * {@link FeedCacheProperties}; {@link #reconcile()} is invoked by hand (the {@code @Scheduled}
 * annotation is intentionally inert outside a Spring context). Mirrors the construct-directly pattern
 * of {@code FeedReadServiceTest}.
 *
 * <p>Covers the three reconciliation branches per the plan:
 * <ul>
 *   <li>{@code driftedSkeletonIsRebuilt} — source ids differ from cached ids → {@code put} called.</li>
 *   <li>{@code freshSkeletonIsNotRebuilt} — source ids equal cached ids → no {@code put}.</li>
 *   <li>{@code missingSkeletonIsRebuilt} — cached empty → {@code put} called.</li>
 * </ul>
 */
class FeedReconciliationSchedulerTest {

    private static final int HEAD_TTL = 4;
    private static final LocalDateTime TS = LocalDateTime.of(2026, 6, 21, 10, 0);

    private FeedSourceQuery sourceQuery;
    private SkeletonStore skeletonStore;
    private FeedCacheProperties props;

    private FeedReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        sourceQuery = mock(FeedSourceQuery.class);
        skeletonStore = mock(SkeletonStore.class);
        props = new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(HEAD_TTL, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 200, 10,
                FeedCacheProperties.HotKey.defaults());
        scheduler = new FeedReconciliationScheduler(sourceQuery, skeletonStore, props);
    }

    @Test
    void driftedSkeletonIsRebuilt() {
        // Size 10 cached ids are stale; size 20 matches. Only size 10 should be rewritten.
        when(sourceQuery.findPublicFeedHead(10))
                .thenReturn(new FeedPage(List.of(3L, 2L, 1L), true, new Cursor(TS, 1L)));
        when(sourceQuery.findPublicFeedHead(20))
                .thenReturn(new FeedPage(List.of(30L, 20L, 10L), true, new Cursor(TS, 10L)));
        when(skeletonStore.get(FeedRedisKeys.publicHead(10)))
                .thenReturn(Optional.of(new FeedPage(List.of(99L, 88L), true, new Cursor(TS, 88L))));
        when(skeletonStore.get(FeedRedisKeys.publicHead(20)))
                .thenReturn(Optional.of(new FeedPage(List.of(30L, 20L, 10L), true, new Cursor(TS, 10L))));

        scheduler.reconcile();

        // Drifted size-10 skeleton is rewritten with the fresh page and the configured head TTL.
        ArgumentCaptor<FeedPage> pageCaptor = ArgumentCaptor.forClass(FeedPage.class);
        verify(skeletonStore).put(eq(FeedRedisKeys.publicHead(10)), pageCaptor.capture(), eq(HEAD_TTL));
        org.assertj.core.api.Assertions.assertThat(pageCaptor.getValue().ids()).containsExactly(3L, 2L, 1L);
        // Size 20 matches — no put.
        verify(skeletonStore, never()).put(eq(FeedRedisKeys.publicHead(20)), any(), anyInt());
    }

    @Test
    void freshSkeletonIsNotRebuilt() {
        // Both sizes match their cached skeletons exactly — neither should be rewritten.
        FeedPage fresh10 = new FeedPage(List.of(1L, 2L), true, new Cursor(TS, 2L));
        FeedPage fresh20 = new FeedPage(List.of(1L, 2L, 3L, 4L), true, new Cursor(TS, 4L));
        when(sourceQuery.findPublicFeedHead(10)).thenReturn(fresh10);
        when(sourceQuery.findPublicFeedHead(20)).thenReturn(fresh20);
        when(skeletonStore.get(FeedRedisKeys.publicHead(10))).thenReturn(Optional.of(fresh10));
        when(skeletonStore.get(FeedRedisKeys.publicHead(20))).thenReturn(Optional.of(fresh20));

        scheduler.reconcile();

        verify(skeletonStore, never()).put(any(), any(), anyInt());
    }

    @Test
    void missingSkeletonIsRebuilt() {
        // Both skeletons absent in the cache — both should be rebuilt from source.
        when(sourceQuery.findPublicFeedHead(10))
                .thenReturn(new FeedPage(List.of(1L), true, new Cursor(TS, 1L)));
        when(sourceQuery.findPublicFeedHead(20))
                .thenReturn(new FeedPage(List.of(1L, 2L), true, new Cursor(TS, 2L)));
        when(skeletonStore.get(FeedRedisKeys.publicHead(10))).thenReturn(Optional.empty());
        when(skeletonStore.get(FeedRedisKeys.publicHead(20))).thenReturn(Optional.empty());

        scheduler.reconcile();

        verify(skeletonStore, times(1)).put(eq(FeedRedisKeys.publicHead(10)), any(), eq(HEAD_TTL));
        verify(skeletonStore, times(1)).put(eq(FeedRedisKeys.publicHead(20)), any(), eq(HEAD_TTL));
    }

    @Test
    void emptySourceHeadIsNotCached() {
        // Source reports an empty head page — reconciliation should NOT install an empty skeleton (the
        // read path owns NULL-sentinel installation). No put at all.
        when(sourceQuery.findPublicFeedHead(10)).thenReturn(new FeedPage(List.of(), false, null));
        when(sourceQuery.findPublicFeedHead(20)).thenReturn(new FeedPage(List.of(), false, null));

        scheduler.reconcile();

        verify(skeletonStore, never()).put(any(), any(), anyInt());
        // The skeleton lookup is short-circuited for empty source pages, so no get either.
        verify(skeletonStore, never()).get(any());
    }

    @Test
    void failureForOneSizeDoesNotAbortTheSweep() {
        // Size 10 source query throws; size 20 succeeds. The sweep should swallow the failure and still
        // rebuild size 20 (mirrors the swallow-and-continue posture of CounterFlushScheduler).
        when(sourceQuery.findPublicFeedHead(10)).thenThrow(new RuntimeException("source unavailable"));
        when(sourceQuery.findPublicFeedHead(20))
                .thenReturn(new FeedPage(List.of(5L), true, new Cursor(TS, 5L)));
        when(skeletonStore.get(FeedRedisKeys.publicHead(20))).thenReturn(Optional.empty());

        scheduler.reconcile();

        // Size 20 was still reconciled despite the size-10 failure.
        verify(skeletonStore, times(1)).put(eq(FeedRedisKeys.publicHead(20)), any(), eq(HEAD_TTL));
    }

    @Test
    void nullSourceHeadIsTreatedAsEmpty() {
        // Defensive: a null FeedPage from source is treated as "nothing to cache" rather than NPE-ing.
        when(sourceQuery.findPublicFeedHead(10)).thenReturn(null);
        when(sourceQuery.findPublicFeedHead(20))
                .thenReturn(new FeedPage(List.of(1L), true, new Cursor(TS, 1L)));
        when(skeletonStore.get(FeedRedisKeys.publicHead(20))).thenReturn(Optional.empty());

        scheduler.reconcile();

        verify(skeletonStore, never()).put(eq(FeedRedisKeys.publicHead(10)), any(), anyInt());
        verify(skeletonStore, times(1)).put(eq(FeedRedisKeys.publicHead(20)), any(), eq(HEAD_TTL));
        // No interactions against size-10 skeleton (null short-circuits before the get).
        verifyNoInteractionsForSize10Skeleton();
    }

    private void verifyNoInteractionsForSize10Skeleton() {
        // Sanity: size 10 must not have triggered a skeletonStore.get (the null/empty guard returns
        // before the lookup). Using verify with never() on the specific key.
        verify(skeletonStore, never()).get(eq(FeedRedisKeys.publicHead(10)));
    }
}
