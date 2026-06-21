package com.platform.cache.feed.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.cache.feed.domain.PostFragment;
import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.cache.feed.dto.FeedPageResponse;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import com.platform.cache.feed.infrastructure.redis.FragmentStore;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import com.platform.content.application.ContentQueryService;
import com.platform.content.domain.ContentPost;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Three-tier read orchestrator for the feed cache. Coordinates the read path:
 * <pre>
 *   L2 (Caffeine local)  ──hit──▶  overlay ──▶  return
 *      │ miss
 *      ▼
 *   NULL sentinel?  ──yes──▶  empty response
 *      │ no
 *      ▼
 *   L1 (Redis skeleton)  ──miss──▶  source backfill ──▶  write L1 (or NULL sentinel if empty)
 *      │ hit                                                       │
 *      ▼                                                           ▼
 *   L0 (Redis fragments) multiGet                                  L0 multiGet
 *      │                                                           │
 *      ▼                                                           ▼
 *   tombstone?  ──yes──▶  rebuild page from source, update L1 ────▶ L0 multiGet
 *      │
 *      ▼
 *   missing fragments?  ──yes──▶  batch backfill (content + counter + user) ──▶ write L0
 *      │
 *      ▼
 *   assemble ──▶ write L2 ──▶ overlay ──▶ return
 * </pre>
 *
 * <p><b>Tier responsibilities:</b>
 * <ul>
 *   <li>L2 holds fully-assembled {@link FeedPageResponse}s local to the JVM (short TTL, separate
 *       {@code feedL2CacheManager}). A hit avoids all Redis and DB calls.</li>
 *   <li>L1 holds {@link FeedPage} skeletons (id lists) in Redis. A hit avoids the DB keyset query;
 *       the orchestrator still assembles from L0.</li>
 *   <li>L0 holds per-post {@link PostFragment}s in Redis. A skeleton hit batch-fetches these; missing
 *       fragments are backfilled from content/counter/user services, tombstoned fragments trigger a
 *       full page rebuild (the C3 fix).</li>
 * </ul>
 *
 * <p><b>Single-flight:</b> the source-backfill step (step 3 below) and the tombstone-triggered
 * rebuild (step 5) are wrapped in {@link FeedSingleFlight#executeWithLock} so concurrent cache
 * misses for the same page collapse onto a single source query both within this JVM (local
 * {@code CompletableFuture} dedup) and across the cluster (Redis {@code SET NX EX} lock with
 * poll-wait and bounded self-build fallback). The single-flight wraps <b>only</b> the source query
 * — the L1 write and L0 assembly are cheap and idempotent and run outside the lock.
 *
 * <p><b>Overlay:</b> {@code likedByMe} / {@code favedByMe} are a read-time, per-user concern (Task 9)
 * and never part of the cached page — the L2 entry has them {@code null} for every item. After a page
 * is assembled (or read from L2), {@link #overlay(FeedPageResponse, Long)} delegates to
 * {@link FeedOverlayService} for authenticated readers, which stamps both bits via one batched
 * {@code hasActedBatch} call; for anonymous readers ({@code requesterIdOrNull == null}) the overlay is
 * skipped and the bits stay {@code null} (the sentinel that signals "no overlay applied").
 *
 * <p><b>Profile:</b> {@code @Profile("!test")} — depends on the {@code @Profile("!test")}
 * {@link FragmentStore} / {@link SkeletonStore} / {@link ContentQueryService} /
 * {@link CounterReadService} / {@link FeedOverlayService}. The unit test constructs the service
 * directly with fakes/mocks.
 */
@Service
@Profile("!test")
public class FeedReadService {

    /** Hard clamp on page size. Matches the controller's clamp and {@code ContentQueryService}. */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MIN_PAGE_SIZE = 1;

    private final FragmentStore fragmentStore;
    private final SkeletonStore skeletonStore;
    private final FeedSourceQuery sourceQuery;
    private final CacheManager l2CacheManager;
    private final FeedCacheProperties props;
    private final CounterReadService counterReadService;
    private final ContentQueryService contentQueryService;
    private final UserQueryService userQueryService;
    private final FeedSingleFlight singleFlight;
    private final FeedOverlayService overlayService;
    /** Reserved for Task 10 (event payload decode); see plan. */
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public FeedReadService(FragmentStore fragmentStore,
                           SkeletonStore skeletonStore,
                           FeedSourceQuery sourceQuery,
                           @Qualifier("feedL2CacheManager") CacheManager l2CacheManager,
                           FeedCacheProperties props,
                           CounterReadService counterReadService,
                           ContentQueryService contentQueryService,
                           UserQueryService userQueryService,
                           FeedSingleFlight singleFlight,
                           FeedOverlayService overlayService,
                           ObjectMapper objectMapper) {
        this.fragmentStore = fragmentStore;
        this.skeletonStore = skeletonStore;
        this.sourceQuery = sourceQuery;
        this.l2CacheManager = l2CacheManager;
        this.props = props;
        this.counterReadService = counterReadService;
        this.contentQueryService = contentQueryService;
        this.userQueryService = userQueryService;
        this.singleFlight = singleFlight;
        this.overlayService = overlayService;
        this.objectMapper = objectMapper;
    }

    // ---- public API --------------------------------------------------------

    /**
     * Reads one page of the public feed. {@code cursor == null} selects the head page; a non-null
     * cursor selects the page strictly after it. {@code requesterIdOrNull} is the authenticated
     * user's id (for the personalization overlay) or null for an anonymous reader.
     */
    public FeedPageResponse readPublicFeed(Cursor cursor, int size, Long requesterIdOrNull) {
        int clamped = clampSize(size);
        boolean head = (cursor == null);
        String pageKey = head
                ? FeedRedisKeys.publicHead(clamped)
                : FeedRedisKeys.publicAfter(cursor.timestamp(), cursor.id(), clamped);
        String cacheName = head ? "feed-public-head" : "feed-public-cursor";
        int l1Ttl = head ? props.l1().headTtlSeconds() : props.l1().cursorTtlSeconds();

        FeedPageResponse assembled = readPage(
                cacheName, pageKey, l1Ttl,
                head ? () -> sourceQuery.findPublicFeedHead(clamped)
                     : () -> sourceQuery.findPublicFeedAfter(cursor, clamped));
        return overlay(assembled, requesterIdOrNull);
    }

    /**
     * Reads one page of the given user's own feed (drafts + published, all visibilities except
     * DELETED). Mirrors {@link #readPublicFeed}; the personalization overlay is not applied (the
     * "my posts" view is always for the authenticated author).
     */
    public FeedPageResponse readUserFeed(Long userId, Cursor cursor, int size) {
        int clamped = clampSize(size);
        boolean head = (cursor == null);
        String pageKey = head
                ? FeedRedisKeys.userHead(userId, clamped)
                : FeedRedisKeys.userAfter(userId, cursor.timestamp(), cursor.id(), clamped);
        String cacheName = head ? "feed-user-head" : "feed-user-cursor";
        int l1Ttl = head ? props.l1().headTtlSeconds() : props.l1().cursorTtlSeconds();

        FeedPageResponse assembled = readPage(
                cacheName, pageKey, l1Ttl,
                head ? () -> sourceQuery.findUserFeedHead(userId, clamped)
                     : () -> sourceQuery.findUserFeedAfter(userId, cursor, clamped));
        // The "my posts" view is inherently first-person; no overlay needed (return as-is).
        return assembled;
    }

    // ---- core orchestration ------------------------------------------------

    /**
     * The shared three-tier pipeline for one page. {@code sourceSupplier} is the feed-specific
     * backfill query (public head / public cursor / user head / user cursor); everything else is
     * feed-agnostic.
     */
    private FeedPageResponse readPage(String cacheName, String pageKey, int l1Ttl,
                                      BackfillQuery sourceSupplier) {
        // 1. L2 check — a hit avoids all Redis + DB work.
        Cache l2 = l2CacheManager.getCache(cacheName);
        FeedPageResponse cached = readL2(l2, pageKey);
        if (cached != null) {
            return cached;
        }

        // 2. NULL-sentinel check — page is provably empty for a short window; skip the DB.
        if (skeletonStore.isNullSentinel(pageKey)) {
            return FeedPageResponse.empty();
        }

        // 3. L1 skeleton check; on miss, backfill from source. Single-flight wraps ONLY the source
        //    query (the expensive DB call) — the L1 write below runs outside the lock and is cheap.
        FeedPage page = skeletonStore.get(pageKey).orElse(null);
        if (page == null) {
            page = singleFlight.executeWithLock(pageKey, sourceSupplier::get);
            if (page == null || page.ids() == null || page.ids().isEmpty()) {
                skeletonStore.putNullSentinel(pageKey);
                return FeedPageResponse.empty();
            }
            skeletonStore.put(pageKey, page, l1Ttl);
        }

        // 4. L0 fragment assembly.
        Map<Long, PostFragment> fragments = fragmentStore.multiGet(page.ids());

        // 5. Tombstone check → rebuild the page (C3 fix). A tombstone means one of the skeleton's
        //    posts was deleted; the skeleton is stale and must be re-derived from source.
        if (fragmentStore.anyTombstone(page.ids())) {
            FeedPage rebuilt = singleFlight.executeWithLock(pageKey, sourceSupplier::get);
            if (rebuilt == null || rebuilt.ids() == null || rebuilt.ids().isEmpty()) {
                // The page that previously had rows is now empty (e.g. all posts deleted). Cache the
                // empty fact and serve it.
                skeletonStore.putNullSentinel(pageKey);
                return FeedPageResponse.empty();
            }
            page = rebuilt;
            skeletonStore.put(pageKey, page, l1Ttl);
            fragments = fragmentStore.multiGet(page.ids());
        }

        // 6. Missing-fragment backfill. Tombstones detected here are NOT rebuilt again — by this
        //    point the page has just been re-derived, so any remaining tombstone is a concurrent
        //    delete racing this read; treat it as missing and backfill (the backfill will hit a
        //    DELETED post in the content repo and be skipped, leaving the id un-assembled).
        fragments = backfillMissing(page.ids(), fragments);

        // 7. Assemble + promote to L2.
        FeedPageResponse response = assemble(page, fragments);
        writeL2(l2, pageKey, response);
        return response;
    }

    // ---- assembly + backfill ----------------------------------------------

    /**
     * For every id in {@code ids} without a present fragment, batch-backfill from the content,
     * counter, and user services, write each into L0, and return the merged map. Ids whose post is
     * gone (soft-deleted, not found) are silently dropped — they will not appear in the assembled
     * page, which is the correct degradation for a stale skeleton whose rebuild already ran.
     */
    private Map<Long, PostFragment> backfillMissing(List<Long> ids, Map<Long, PostFragment> present) {
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!present.containsKey(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return present;
        }
        int l0Ttl = props.l0().ttlSeconds();
        Map<Long, PostFragment> merged = new LinkedHashMap<>(present);
        for (Long postId : missing) {
            Optional<PostFragment> built = buildFragment(postId);
            if (built.isPresent()) {
                PostFragment fragment = built.get();
                fragmentStore.put(fragment, l0Ttl);
                merged.put(postId, fragment);
            }
        }
        return merged;
    }

    /**
     * Builds one fragment for {@code postId} by joining post metadata (content), counters (counter),
     * and the author's display name (user). Returns empty if the post is absent or soft-deleted —
     * the caller treats that as "not assembled".
     *
     * <p><b>authorName resolution (design decision #1):</b> resolved via
     * {@link UserQueryService#findAccountById(Long)} → {@link UserAccount#username()}. The user
     * module exposes no dedicated "display name by id" batch lookup, so a per-post call is used;
     * backfill is bounded to the missing set only (typically zero on the hot path), and the result
     * is cached in L0 for the L0 TTL window.
     */
    private Optional<PostFragment> buildFragment(Long postId) {
        Optional<ContentPost> postOpt = contentQueryService.findPostById(postId);
        if (postOpt.isEmpty()) {
            return Optional.empty();
        }
        ContentPost post = postOpt.get();
        ArticleCountersResponse counters = counterReadService.getArticleCounters(postId);
        String authorName = resolveAuthorName(post.authorId());
        PostFragment fragment = new PostFragment(
                post.id(),
                post.authorId(),
                authorName,
                post.coverObjectKey(),
                post.title(),
                post.summary(),
                post.publishedAt(),
                counters.like(),
                counters.fav(),
                counters.view(),
                counters.comment(),
                counters.share());
        return Optional.of(fragment);
    }

    private String resolveAuthorName(Long authorId) {
        if (authorId == null) {
            return null;
        }
        try {
            return userQueryService.findAccountById(authorId).username();
        } catch (RuntimeException e) {
            // A missing user row should not break the whole feed read; degrade to null authorName.
            return null;
        }
    }

    /** Maps the skeleton + fragments into a {@link FeedPageResponse} in skeleton id order. */
    private FeedPageResponse assemble(FeedPage page, Map<Long, PostFragment> fragments) {
        List<FeedItemResponse> items = new ArrayList<>(page.ids().size());
        for (Long id : page.ids()) {
            PostFragment f = fragments.get(id);
            if (f == null) {
                // Skip ids we could not assemble (post deleted mid-read). The assembled page is
                // shorter than the skeleton; hasMore/nextCursor remain authoritative.
                continue;
            }
            items.add(new FeedItemResponse(
                    f.postId(),
                    f.authorId(),
                    f.authorName(),
                    f.cover(),
                    f.title(),
                    f.summary(),
                    f.publishedAt(),
                    f.likeCount(),
                    f.favCount(),
                    f.viewCount(),
                    f.commentCount(),
                    f.shareCount(),
                    null,   // likedByMe — overlay applies this at read time (Task 9)
                    null)); // favedByMe
        }
        return new FeedPageResponse(items, page.hasMore(), page.nextCursor());
    }

    // ---- L2 helpers --------------------------------------------------------

    private static FeedPageResponse readL2(Cache l2, String pageKey) {
        if (l2 == null) {
            return null;
        }
        Cache.ValueWrapper wrapped = l2.get(pageKey);
        if (wrapped == null) {
            return null;
        }
        Object value = wrapped.get();
        return (value instanceof FeedPageResponse response) ? response : null;
    }

    private static void writeL2(Cache l2, String pageKey, FeedPageResponse response) {
        if (l2 == null) {
            return;
        }
        l2.put(pageKey, response);
    }

    // ---- misc --------------------------------------------------------------

    /**
     * Personalization overlay. For anonymous readers ({@code requesterIdOrNull == null}) the page is
     * returned unchanged with {@code likedByMe}/{@code favedByMe} left {@code null} — that null is the
     * sentinel the controller/client uses to tell "no overlay applied" apart from "overlay applied,
     * user has not acted". For authenticated readers, {@link FeedOverlayService} stamps both bits via
     * a single batched {@code hasActedBatch} call.
     */
    private FeedPageResponse overlay(FeedPageResponse response, Long requesterIdOrNull) {
        if (requesterIdOrNull == null) {
            return response;
        }
        return overlayService.overlay(response, requesterIdOrNull);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE);
    }

    /** Feed-specific backfill query, supplied to {@link #readPage} per feed kind. */
    @FunctionalInterface
    private interface BackfillQuery {
        FeedPage get();
    }
}
