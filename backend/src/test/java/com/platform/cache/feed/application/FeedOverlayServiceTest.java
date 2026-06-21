package com.platform.cache.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.cache.feed.dto.FeedPageResponse;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link FeedOverlayService}. Mocks {@link CounterReadService} and asserts the
 * service: (a) stamps {@code likedByMe}/{@code favedByMe} from a single batched
 * {@code hasActedBatch} call, (b) skips the batch call entirely for an empty page, and (c) defaults
 * missing bits to {@code false}. The "anonymous reader" case is the caller's responsibility
 * ({@code FeedReadService} short-circuits before delegating) and is covered by
 * {@code FeedReadServiceTest.overlayIsSkippedForAnonymousReader}.
 */
class FeedOverlayServiceTest {

    private static final LocalDateTime PUB_AT = LocalDateTime.of(2026, 6, 20, 12, 0);
    private static final Long USER = 42L;

    private CounterReadService counterReadService;
    private FeedOverlayService overlayService;

    @BeforeEach
    void setUp() {
        counterReadService = mock(CounterReadService.class);
        overlayService = new FeedOverlayService(counterReadService);
    }

    @Test
    void overlayStampsLikedAndFavedFromHasActedBatch() {
        List<Long> postIds = List.of(1L, 2L, 3L);
        FeedPageResponse page = page(postIds);
        // Post 1: liked+faved; post 2: liked only; post 3: neither. Exercises both true and false
        // branches plus the default-false path for an id absent from the returned map.
        when(counterReadService.hasActedBatch(
                eq(USER), eq(CounterEntityType.ARTICLE), eq(postIds),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV))))
                .thenReturn(Map.of(
                        CounterMetric.LIKE, Map.of(1L, true, 2L, true, 3L, false),
                        CounterMetric.FAV, Map.of(1L, true, 2L, false, 3L, false)));

        FeedPageResponse result = overlayService.overlay(page, USER);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).postId()).isEqualTo(1L);
        assertThat(result.items().get(0).likedByMe()).isTrue();
        assertThat(result.items().get(0).favedByMe()).isTrue();
        assertThat(result.items().get(1).likedByMe()).isTrue();
        assertThat(result.items().get(1).favedByMe()).isFalse();
        assertThat(result.items().get(2).likedByMe()).isFalse();
        assertThat(result.items().get(2).favedByMe()).isFalse();
        // hasMore / nextCursor are carried through unchanged.
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(page.nextCursor());
    }

    @Test
    void overlayDefaultsMissingBitsToFalse() {
        // The bitmap returns an empty per-metric map (user has acted on nothing). Every bit should
        // default to false rather than null — for an authenticated reader the overlay always resolves
        // a definite value.
        List<Long> postIds = List.of(10L);
        FeedPageResponse page = page(postIds);
        when(counterReadService.hasActedBatch(
                eq(USER), eq(CounterEntityType.ARTICLE), eq(postIds),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV))))
                .thenReturn(Map.of());

        FeedPageResponse result = overlayService.overlay(page, USER);

        assertThat(result.items().get(0).likedByMe()).isFalse();
        assertThat(result.items().get(0).favedByMe()).isFalse();
    }

    @Test
    void overlayDefaultsToOneFalseWhenMetricKeyAbsent() {
        // Defensive: if only LIKE is present in the returned map (FAV key missing entirely), the FAV
        // branch must still default to false via getOrDefault on the metric map.
        List<Long> postIds = List.of(10L);
        FeedPageResponse page = page(postIds);
        when(counterReadService.hasActedBatch(
                anyLong(), eq(CounterEntityType.ARTICLE), eq(postIds),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV))))
                .thenReturn(Map.of(CounterMetric.LIKE, Map.of(10L, true)));

        FeedPageResponse result = overlayService.overlay(page, USER);

        assertThat(result.items().get(0).likedByMe()).isTrue();
        assertThat(result.items().get(0).favedByMe()).isFalse();
    }

    @Test
    void emptyPageSkipsBatchCall() {
        // An empty page (e.g. NULL-sentinel response) must not issue a batch call at all.
        FeedPageResponse empty = FeedPageResponse.empty();

        FeedPageResponse result = overlayService.overlay(empty, USER);

        assertThat(result).isSameAs(empty);
        verifyNoInteractions(counterReadService);
    }

    @Test
    void overlayIssuesExactlyOneBatchedCallRegardlessOfPageSize() {
        // The whole point of the batch call: one round-trip for the whole page, not one per item.
        List<Long> postIds = List.of(1L, 2L, 3L, 4L, 5L);
        FeedPageResponse page = page(postIds);
        when(counterReadService.hasActedBatch(
                anyLong(), any(), any(), any())).thenReturn(Map.of());

        overlayService.overlay(page, USER);

        verify(counterReadService, org.mockito.Mockito.times(1)).hasActedBatch(
                eq(USER), eq(CounterEntityType.ARTICLE), eq(postIds),
                eq(List.of(CounterMetric.LIKE, CounterMetric.FAV)));
    }

    @Test
    void overlayDoesNotMutateOriginalPageItems() {
        // The input items keep their null overlay fields; the service builds a fresh list.
        List<Long> postIds = List.of(1L);
        FeedPageResponse page = page(postIds);
        when(counterReadService.hasActedBatch(
                anyLong(), any(), any(), any())).thenReturn(
                        Map.of(CounterMetric.LIKE, Map.of(1L, true),
                                CounterMetric.FAV, Map.of(1L, true)));

        FeedPageResponse result = overlayService.overlay(page, USER);

        // Original untouched.
        assertThat(page.items().get(0).likedByMe()).isNull();
        assertThat(page.items().get(0).favedByMe()).isNull();
        // Result stamped.
        assertThat(result.items().get(0).likedByMe()).isTrue();
        assertThat(result.items().get(0).favedByMe()).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    private static FeedPageResponse page(List<Long> postIds) {
        List<FeedItemResponse> items = postIds.stream()
                .map(FeedOverlayServiceTest::item)
                .toList();
        return new FeedPageResponse(items, true, new Cursor(PUB_AT, postIds.get(postIds.size() - 1)));
    }

    private static FeedItemResponse item(long postId) {
        return new FeedItemResponse(
                postId, 7L, "alice", "cover-" + postId, "title-" + postId, "summary-" + postId,
                PUB_AT, 10L * postId, 1L * postId, 100L * postId, 0L, 0L,
                null, null);
    }
}
