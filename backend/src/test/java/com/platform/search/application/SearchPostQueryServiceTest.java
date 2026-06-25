package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostQuery;
import com.platform.search.dto.SearchPostPageResponse;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository.SearchResultPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The query service is the read-side orchestrator. These tests pin the three contracts the controller
 * and the front-end rely on:
 *
 * <ul>
 *   <li><b>anonymous</b> &rarr; no overlay call, items keep {@code likedByMe}/{@code favedByMe} null.</li>
 *   <li><b>authenticated</b> &rarr; one batched overlay call, items stamped with real booleans.</li>
 *   <li><b>cursor</b> &rarr; a token minted for one query is rejected for another, and a tampered token
 *       is rejected outright.</li>
 * </ul>
 *
 * The index repository is faked by subclassing (its public constructor accepts {@code (null, props)});
 * the counter read service is faked by subclassing the concrete class and overriding the one method.
 */
class SearchPostQueryServiceTest {

    private static final String CURSOR_SECRET = "cursor-secret-for-tests";

    private final SearchCursorCodec codec = new SearchCursorCodec(CURSOR_SECRET, 600L);

    @Test
    void anonymousSearchLeavesUserStateNull() {
        RecordingIndex index = new RecordingIndex(List.of(item(1L), item(2L)), true, nextSort());
        // A counter service that FAILS if invoked proves the anonymous path short-circuits the overlay.
        CounterReadService counters = throwingCounterService();

        SearchPostQueryService service = new SearchPostQueryService(index, counters, codec);

        SearchPostPageResponse response = service.search(
                "java", null, "ARTICLE", null, 20, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.likedByMe()).isNull();
            assertThat(item.favedByMe()).isNull();
        });
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
    }

    @Test
    void authenticatedSearchOverlaysLikedAndFaved() {
        RecordingIndex index = new RecordingIndex(List.of(item(1L), item(2L)), false, null);
        // User liked post 1, faved nothing. Missing bits default to false.
        CounterReadService counters = new CounterReadService(null) {
            @Override
            public Map<CounterMetric, Map<Long, Boolean>> hasActedBatch(
                    long userId, CounterEntityType etype, List<Long> eids, List<CounterMetric> metrics) {
                Map<CounterMetric, Map<Long, Boolean>> out = new LinkedHashMap<>();
                Map<Long, Boolean> liked = new LinkedHashMap<>();
                liked.put(1L, true);
                liked.put(2L, false);
                Map<Long, Boolean> faved = new LinkedHashMap<>();
                faved.put(1L, false);
                faved.put(2L, false);
                out.put(CounterMetric.LIKE, liked);
                out.put(CounterMetric.FAV, faved);
                return out;
            }
        };

        SearchPostQueryService service = new SearchPostQueryService(index, counters, codec);

        SearchPostPageResponse response = service.search(
                "java", null, "ARTICLE", null, 20, 42L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).satisfiesExactly(
                item -> {
                    assertThat(item.likedByMe()).isTrue();
                    assertThat(item.favedByMe()).isFalse();
                },
                item -> {
                    assertThat(item.likedByMe()).isFalse();
                    assertThat(item.favedByMe()).isFalse();
                });
        // No next page on the last page.
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void invalidCursorIsRejected() {
        RecordingIndex index = new RecordingIndex(List.of(), false, null);
        SearchPostQueryService service = new SearchPostQueryService(index, throwingCounterService(), codec);

        // A tampered cursor (bad signature) is rejected outright.
        assertThatThrownBy(() -> service.search(
                "java", null, "ARTICLE", "bogus.token", 20, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");

        // A valid cursor minted for ONE query must not decode for a DIFFERENT query (different keyword
        // → different query hash → "cursor query mismatch").
        String token = codec.encode(codec.newCursor(
                SearchQueryHasher.hash("java", null, "ARTICLE", 20), Instant.now(), List.of("x")));
        assertThatThrownBy(() -> service.search(
                "python", null, "ARTICLE", token, 20, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor query mismatch");
    }

    @Test
    void sizeIsClampedToRange() {
        RecordingIndex index = new RecordingIndex(List.of(), false, null);
        SearchPostQueryService service = new SearchPostQueryService(index, throwingCounterService(), codec);

        service.search("java", null, "ARTICLE", null, 0, null);   // below min → 1
        service.search("java", null, "ARTICLE", null, 9999, null); // above max → 50

        // The clamped size is what reaches the index repository.
        assertThat(index.recordedSizes).containsExactly(1, 50);
    }

    // --- helpers / fakes ------------------------------------------------------

    private static FeedItemResponse item(long postId) {
        return new FeedItemResponse(
                postId, 2L, "作者", "cover.jpg", "title-" + postId, "summary",
                java.time.LocalDateTime.now(), 10L, 1L, 100L, 0L, 0L, null, null);
    }

    private static List<Object> nextSort() {
        return List.of("2026-06-25T12:00:00Z", 1L);
    }

    private static CounterReadService throwingCounterService() {
        return new CounterReadService(null) {
            @Override
            public Map<CounterMetric, Map<Long, Boolean>> hasActedBatch(
                    long userId, CounterEntityType etype, List<Long> eids, List<CounterMetric> metrics) {
                throw new AssertionError("overlay must not be called for anonymous reader");
            }
        };
    }

    private static SearchProperties props() {
        return new SearchProperties(
                true,
                new SearchProperties.Index(
                        "knowledge-posts-read", "knowledge-posts-write", "knowledge-posts-v1",
                        50_000, 3000, 8),
                new SearchProperties.Cursor(CURSOR_SECRET, 600L),
                new SearchProperties.Rank(5.0, 2.0, 1.0, 3.0, 2.0, 0.2, 1.0),
                new SearchProperties.Kafka(
                        "g", "retry", "dlq", "snap-topic", "snap-g", "snap-retry", "snap-dlq"),
                new SearchProperties.Rebuild(500, 200));
    }

    /** Index fake: returns a canned page and records the size each search was called with. */
    private static final class RecordingIndex extends SearchPostIndexRepository {
        private final List<FeedItemResponse> items;
        private final boolean hasMore;
        private final List<Object> nextSortValues;
        final List<Integer> recordedSizes = new ArrayList<>();

        RecordingIndex(List<FeedItemResponse> items, boolean hasMore, List<Object> nextSortValues) {
            super(null, props());
            this.items = items;
            this.hasMore = hasMore;
            this.nextSortValues = nextSortValues;
        }

        @Override
        public SearchResultPage search(SearchPostQuery query) {
            recordedSizes.add(query.size());
            return new SearchResultPage(items, hasMore, nextSortValues);
        }
    }
}
