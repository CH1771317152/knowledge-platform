package com.platform.cache.feed.application;

import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.cache.feed.dto.FeedPageResponse;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Read-time personalization overlay for the feed cache (Task 9). Stamps each assembled
 * {@link FeedItemResponse} with {@code likedByMe} / {@code favedByMe} by issuing a single batched
 * {@link CounterReadService#hasActedBatch} call against the counter module's Redis bitmaps.
 *
 * <p><b>Why a separate service:</b> the overlay is a per-request, per-user concern — the cached
 * {@link FeedPageResponse} in L2 is user-agnostic (every {@code likedByMe}/{@code favedByMe} field
 * is {@code null}), and the overlay is recomputed on every read. Pulling it out of
 * {@link FeedReadService} keeps the orchestrator focused on the three-tier read path, and gives the
 * overlay its own (mockable) seam for unit tests.
 *
 * <p><b>Empty/short-circuit:</b> an empty page ({@code items()} empty) skips the batch call entirely;
 * the caller also short-circuits for anonymous readers before reaching this service, so {@code userId}
 * here is always a real authenticated id.
 *
 * <p><b>Missing bits default to {@code false}:</b> if the counter bitmap has no entry for a given
 * (postId, LIKE/FAV) pair, the user has not acted — {@link Map#getOrDefault} returns {@code false}.
 * The overlay never leaves a field {@code null} for an authenticated reader (that sentinel is reserved
 * for anonymous readers, handled by the caller).
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} mirrors {@link CounterReadService} and the rest of the
 * feed module's Redis-backed wiring — it depends on the {@code @Profile("!test")} counter read
 * service. Unit tests construct it directly with a Mockito-mocked {@link CounterReadService}.
 */
@Service
@Profile("!test")
public class FeedOverlayService {

    private final CounterReadService counterReadService;

    public FeedOverlayService(CounterReadService counterReadService) {
        this.counterReadService = counterReadService;
    }

    /**
     * Returns a new {@link FeedPageResponse} with {@code likedByMe}/{@code favedByMe} stamped on each
     * item; {@code hasMore} and {@code nextCursor} are carried through unchanged. The input page is
     * not mutated (records are immutable; we rebuild the list).
     */
    public FeedPageResponse overlay(FeedPageResponse page, long userId) {
        List<Long> postIds = page.items().stream().map(FeedItemResponse::postId).toList();
        if (postIds.isEmpty()) {
            return page;
        }
        Map<CounterMetric, Map<Long, Boolean>> acted = counterReadService.hasActedBatch(
                userId, CounterEntityType.ARTICLE, postIds, List.of(CounterMetric.LIKE, CounterMetric.FAV));
        Map<Long, Boolean> liked = acted.getOrDefault(CounterMetric.LIKE, Map.of());
        Map<Long, Boolean> faved = acted.getOrDefault(CounterMetric.FAV, Map.of());
        List<FeedItemResponse> withOverlay = page.items().stream()
                .map(item -> new FeedItemResponse(
                        item.postId(), item.authorId(), item.authorName(),
                        item.cover(), item.title(), item.summary(), item.publishedAt(),
                        item.likeCount(), item.favCount(), item.viewCount(),
                        item.commentCount(), item.shareCount(),
                        liked.getOrDefault(item.postId(), false),
                        faved.getOrDefault(item.postId(), false)))
                .toList();
        return new FeedPageResponse(withOverlay, page.hasMore(), page.nextCursor());
    }
}
