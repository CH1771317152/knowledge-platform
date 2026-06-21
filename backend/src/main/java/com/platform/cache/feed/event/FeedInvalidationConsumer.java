package com.platform.cache.feed.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import com.platform.cache.feed.infrastructure.redis.FragmentStore;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Feed-cache invalidation consumer (Task 10). Subscribes to the content module's
 * {@code content-events} topic (the same topic the counter module's
 * {@code ContentPublishCountConsumer} reads) and invalidates the three-tier feed cache in response
 * to each post lifecycle transition. Owns the {@code cache-feed-invalidation} consumer group so its
 * offsets are independent of the counter consumer's.
 *
 * <p><b>Dispatch by {@code eventType}</b> (mirrors the event vocabulary in
 * {@code ContentPostEventType}):
 * <ul>
 *   <li>{@code POST_PUBLISHED} — a new post entered a head page; delete the public head skeleton and
 *       the author's user head skeleton so the next read rebuilds them from source. (Sizes 10/20/50
 *       cover the common page-size clamps; cursor pages are not invalidated — they are bounded by
 *       their keyset cursor and age out via TTL.)</li>
 *   <li>{@code POST_EDITED} — the cached fragment is stale (title/summary/cover/counts changed);
 *       delete the L0 fragment so the next read backfills a fresh one. Skeletons are unaffected (the
 *       id list did not change).</li>
 *   <li>{@code POST_UNPUBLISHED} / {@code POST_DELETED} — the post is gone from all feeds: tombstone
 *       the L0 fragment (so any cached skeleton pointing at it triggers a rebuild) and delete the
 *       head skeletons (public + author's user).</li>
 *   <li>{@code POST_VISIBILITY_CHANGED} — the post may have entered or left the public feed: delete
 *       the fragment (the snapshot includes nothing visibility-specific, but the backfill will
 *       re-resolve it) and the public head skeleton. The author's user feed includes all
 *       visibilities, so its skeleton is unaffected.</li>
 * </ul>
 *
 * <p><b>Delayed double-delete (L1 skeletons):</b> a cache-aside race exists between this consumer's
 * delete and an in-flight read that repopulates the skeleton just after the delete. To close the
 * window, head-page deletes are re-issued on a 1-second delay. The first delete closes the door for
 * new reads; the second delete mops up any value a racing read wrote in the gap. This is the standard
 * cache-aside double-delete; 1 s is well within the L1 head TTL window.
 *
 * <p><b>Failure handling:</b> any exception (malformed JSON, missing fields, Redis down) is logged
 * and swallowed, then the offset is acked — a poison message must not block the partition. A missed
 * invalidation degrades to a stale read until the skeleton's TTL expires or the reconciliation
 * scheduler (Task 11) sweeps it; both are bounded and safe.
 *
 * <p><b>Profile:</b> {@code @Profile("!test & !integration")} keeps the live {@code @KafkaListener}
 * container out of both automated test suites (mirrors
 * {@code ContentPublishCountConsumer} and {@code ContentPostEventKafkaPublisher}). Unit tests
 * construct the consumer directly with Mockito-mocked stores and a real {@link ObjectMapper}.
 */
@Component
@Profile("!test & !integration")
public class FeedInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeedInvalidationConsumer.class);

    /**
     * Page sizes whose head skeletons are invalidated on publish/unpublish/delete. Covers the common
     * controller clamps; cursor pages are keyed by their keyset cursor and are left to age out via
     * TTL (invalidating them by prefix-scan would be far more expensive than the staleness window
     * costs).
     */
    private static final List<Integer> HEAD_PAGE_SIZES = List.of(10, 20, 50);

    /** Delay before the second delete in the cache-aside double-delete. */
    private static final long DOUBLE_DELETE_DELAY_SECONDS = 1;

    private final SkeletonStore skeletonStore;
    private final FragmentStore fragmentStore;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService delayedDelete;

    public FeedInvalidationConsumer(SkeletonStore skeletonStore,
                                    FragmentStore fragmentStore,
                                    ObjectMapper objectMapper) {
        this.skeletonStore = skeletonStore;
        this.fragmentStore = fragmentStore;
        this.objectMapper = objectMapper;
        this.delayedDelete = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "feed-invalidation-double-delete");
            t.setDaemon(true);
            return t;
        });
    }

    @KafkaListener(
            topics = "${platform.content.kafka.events-topic}",
            groupId = "cache-feed-invalidation",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onContentEvent(String value, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(value);
            String eventType = node.path("eventType").asText("POST_PUBLISHED");
            Long postId = node.path("postId").asLong();
            Long authorId = node.has("authorId") && !node.get("authorId").isNull()
                    ? node.get("authorId").asLong() : null;

            switch (eventType) {
                case "POST_PUBLISHED" -> {
                    deleteHeadPages("public", null);
                    if (authorId != null) {
                        deleteHeadPages("user", authorId);
                    }
                }
                case "POST_EDITED" -> {
                    if (postId != null && postId > 0) {
                        fragmentStore.delete(postId);
                    }
                }
                case "POST_UNPUBLISHED", "POST_DELETED" -> {
                    if (postId != null && postId > 0) {
                        fragmentStore.putTombstone(postId);
                    }
                    deleteHeadPages("public", null);
                    if (authorId != null) {
                        deleteHeadPages("user", authorId);
                    }
                }
                case "POST_VISIBILITY_CHANGED" -> {
                    if (postId != null && postId > 0) {
                        fragmentStore.delete(postId);
                    }
                    deleteHeadPages("public", null);
                }
                default -> {
                    // Unknown eventType — nothing to invalidate. Log at debug for observability.
                    log.debug("Feed invalidation consumer ignoring unknown eventType: {}", eventType);
                }
            }
            // Delayed double-delete for L1 skeletons (cache-aside race mop-up). Only the event types
            // that touch head skeletons need the second delete.
            scheduleDelayedDoubleDelete(eventType, authorId);
        } catch (Exception e) {
            // Swallow + ack: a poison message must not block the partition. A missed invalidation
            // degrades to a stale read until TTL expiry or the reconciliation sweep.
            log.warn("Feed invalidation consumer failed to process value (skipping): {}", e.getMessage());
        }
        ack.acknowledge();
    }

    /**
     * Deletes the head-page skeletons for the given feed scope. {@code scope="public"} deletes the
     * public head skeletons at each common page size; {@code scope="user"} deletes the given author's
     * user head skeletons.
     */
    private void deleteHeadPages(String scope, Long authorId) {
        for (int size : HEAD_PAGE_SIZES) {
            String key = "user".equals(scope)
                    ? FeedRedisKeys.userHead(authorId, size)
                    : FeedRedisKeys.publicHead(size);
            skeletonStore.delete(key);
        }
    }

    /**
     * Re-issues head-page deletes after {@link #DOUBLE_DELETE_DELAY_SECONDS} to close the cache-aside
     * race (a read that repopulated the skeleton in the gap between the first delete and now).
     */
    private void scheduleDelayedDoubleDelete(String eventType, Long authorId) {
        if (!isHeadInvalidating(eventType)) {
            return;
        }
        delayedDelete.schedule(() -> {
            try {
                deleteHeadPages("public", null);
                if (authorId != null) {
                    deleteHeadPages("user", authorId);
                }
            } catch (Exception e) {
                log.warn("Delayed double-delete failed (will rely on TTL/reconciliation): {}",
                        e.getMessage());
            }
        }, DOUBLE_DELETE_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static boolean isHeadInvalidating(String eventType) {
        return "POST_PUBLISHED".equals(eventType)
                || "POST_UNPUBLISHED".equals(eventType)
                || "POST_DELETED".equals(eventType)
                || "POST_VISIBILITY_CHANGED".equals(eventType);
    }

    /** Shuts down the delayed-delete executor on bean disposal (container shutdown). */
    @PreDestroy
    public void shutdown() {
        delayedDelete.shutdownNow();
    }
}
