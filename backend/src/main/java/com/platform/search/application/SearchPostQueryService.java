package com.platform.search.application;

import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.search.domain.SearchPostQuery;
import com.platform.search.dto.SearchPostPageResponse;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository.SearchResultPage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Read-side orchestrator for {@code GET /api/search/posts}. Builds a {@link SearchPostQuery} from the
 * raw request parameters, delegates the Elasticsearch read to {@link SearchPostIndexRepository}, overlays
 * the per-request personalization ({@code likedByMe} / {@code favedByMe}) for authenticated readers, and
 * encodes the next-page cursor.
 *
 * <p><b>Anonymous vs authenticated:</b> the controller resolves the requester id optionally (anonymous
 * readers get {@code null}). A {@code null} requester id short-circuits the overlay — the returned items
 * keep {@code likedByMe}/{@code favedByMe} as {@code null}, the sentinel that signals "no overlay
 * applied". This matches the feed read path ({@link com.platform.cache.feed.application.FeedOverlayService}).
 *
 * <p><b>Cursor:</b> the cursor is null/blank on the first page; subsequent pages carry a signed,
 * query-hash-bound token (see {@link SearchCursorCodec}). A token minted for one query will not decode
 * for another, so a stale bookmarked cursor cannot leak results from a different query.
 *
 * <p><b>Size clamping:</b> the requested size is clamped to {@code [1, 50]} before being passed to the
 * index — defensive against an abusive {@code size=10000}.
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} mirrors the index repository and counter read service;
 * {@code @ConditionalOnProperty(platform.search.enabled=true)} keeps the bean absent when search is
 * disabled so the app starts without Elasticsearch.
 */
@Service
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchPostQueryService {

    static final int MIN_SIZE = 1;
    static final int MAX_SIZE = 50;

    private final SearchPostIndexRepository indexRepository;
    private final CounterReadService counterReadService;
    private final SearchCursorCodec cursorCodec;

    public SearchPostQueryService(SearchPostIndexRepository indexRepository,
                                  CounterReadService counterReadService,
                                  SearchCursorCodec cursorCodec) {
        this.indexRepository = indexRepository;
        this.counterReadService = counterReadService;
        this.cursorCodec = cursorCodec;
    }

    /**
     * @param keyword           the search keyword, may be {@code null}/blank (tag-only search).
     * @param tag               the tag filter, may be {@code null}/blank.
     * @param contentType       the content-type filter (e.g. {@code ARTICLE}); forwarded to ES as a term filter.
     * @param cursorToken       the signed cursor from the previous page, or {@code null}/blank on the first page.
     * @param requestedSize     the requested page size; clamped to {@code [1, 50]}.
     * @param requesterIdOrNull the authenticated reader id, or {@code null} for anonymous (overlay skipped).
     * @return the page of feed-style items with {@code hasMore} and the next cursor (null on the last page).
     * @throws IllegalArgumentException if the cursor token is missing, tampered, expired, or bound to a
     *                                  different query (the controller maps this to a 4xx).
     */
    public SearchPostPageResponse search(String keyword,
                                         String tag,
                                         String contentType,
                                         String cursorToken,
                                         int requestedSize,
                                         Long requesterIdOrNull) {
        int size = clampSize(requestedSize);
        String queryHash = SearchQueryHasher.hash(keyword, tag, contentType, size);
        Instant rankNow = Instant.now();

        com.platform.search.domain.SearchCursor cursor = (cursorToken == null || cursorToken.isBlank())
                ? null
                : cursorCodec.decode(cursorToken, queryHash);

        // rankNow is the score-normalization anchor. On the first page it is "now"; on subsequent pages
        // we carry the first page's anchor forward so scores stay comparable across the whole result set.
        Instant anchorRankNow = (cursor == null) ? rankNow : cursor.rankNow();

        SearchPostQuery query = new SearchPostQuery(keyword, tag, contentType, size, cursor, anchorRankNow, queryHash);
        SearchResultPage page = indexRepository.search(query);

        List<FeedItemResponse> items = (requesterIdOrNull == null)
                ? page.items()
                : overlay(page.items(), requesterIdOrNull);

        String nextCursor = (page.nextSortValues() == null)
                ? null
                : cursorCodec.encode(cursorCodec.newCursor(queryHash, anchorRankNow, page.nextSortValues()));

        return new SearchPostPageResponse(items, page.hasMore(), nextCursor);
    }

    /**
     * Stamps {@code likedByMe} / {@code favedByMe} on each item via a single batched
     * {@link CounterReadService#hasActedBatch} call. Mirrors the feed overlay: a missing bit defaults to
     * {@code false} (the user has not acted). The overlay never leaves a field {@code null} for an
     * authenticated reader.
     */
    private List<FeedItemResponse> overlay(List<FeedItemResponse> items, Long requesterId) {
        List<Long> postIds = items.stream().map(FeedItemResponse::postId).toList();
        if (postIds.isEmpty()) {
            return items;
        }
        Map<CounterMetric, Map<Long, Boolean>> acted = counterReadService.hasActedBatch(
                requesterId, CounterEntityType.ARTICLE, postIds, List.of(CounterMetric.LIKE, CounterMetric.FAV));
        Map<Long, Boolean> liked = acted.getOrDefault(CounterMetric.LIKE, Map.of());
        Map<Long, Boolean> faved = acted.getOrDefault(CounterMetric.FAV, Map.of());
        return items.stream()
                .map(item -> new FeedItemResponse(
                        item.postId(), item.authorId(), item.authorName(),
                        item.cover(), item.title(), item.summary(), item.publishedAt(),
                        item.likeCount(), item.favCount(), item.viewCount(),
                        item.commentCount(), item.shareCount(),
                        liked.getOrDefault(item.postId(), false),
                        faved.getOrDefault(item.postId(), false)))
                .toList();
    }

    private static int clampSize(int requested) {
        if (requested < MIN_SIZE) {
            return MIN_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }
}
