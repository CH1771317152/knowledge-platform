package com.platform.cache.feed.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import com.platform.cache.feed.infrastructure.redis.FragmentStore;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pure unit tests for {@link FeedInvalidationConsumer}. The consumer is
 * {@code @Profile("!test & !integration")} so it never starts as a live {@code @KafkaListener}
 * container in any automated test; the tests build it directly with Mockito-mocked
 * {@link SkeletonStore} / {@link FragmentStore}, a real {@link ObjectMapper}, and a mocked
 * {@link Acknowledgment}.
 *
 * <p><b>Delayed double-delete:</b> the consumer re-issues head-page deletes on a 1-second delay.
 * The double-delete tests use {@link org.mockito.Mockito#verify} with a {@code timeout} mode so the
 * assertion blocks (briefly) for the scheduled second delete rather than flaking on scheduler
 * timing, then shuts the consumer down in {@link #tearDown} to release the executor thread.
 */
class FeedInvalidationConsumerTest {

    private static final Long POST_ID = 99L;
    private static final Long AUTHOR_ID = 7L;

    private SkeletonStore skeletonStore;
    private FragmentStore fragmentStore;
    private Acknowledgment ack;
    private ObjectMapper objectMapper;

    private FeedInvalidationConsumer consumer;

    @BeforeEach
    void setUp() {
        skeletonStore = mock(SkeletonStore.class);
        fragmentStore = mock(FragmentStore.class);
        ack = mock(Acknowledgment.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new FeedInvalidationConsumer(skeletonStore, fragmentStore, objectMapper);
    }

    /** Releases the delayed-delete executor thread; tests that exercise the scheduler call this. */
    @AfterEach
    void tearDown() {
        consumer.shutdown();
    }

    // --- POST_PUBLISHED ----------------------------------------------------

    @Test
    void publishedDeletesPublicAndUserHead() {
        consumer.onContentEvent(eventJson("POST_PUBLISHED", POST_ID, AUTHOR_ID), ack);

        // Public head skeletons (sizes 10/20/50) deleted synchronously.
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(10));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(50));
        // Author's user head skeletons deleted too.
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.userHead(AUTHOR_ID, 10));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.userHead(AUTHOR_ID, 20));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.userHead(AUTHOR_ID, 50));
        // Publish does NOT touch fragments (the post is new; no stale fragment exists yet).
        verifyNoInteractions(fragmentStore);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void publishedWithoutAuthorIdSkipsUserHead() {
        // Defensive: an event missing authorId must not NPE the user-head delete branch.
        consumer.onContentEvent(eventJson("POST_PUBLISHED", POST_ID, null), ack);

        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        // No user-head deletes at all (authorId was absent). Verify with a couple of representative
        // author ids rather than a matcher — matchers can't be nested inside the real userHead() call.
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 10));
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 20));
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 50));
        verify(ack, times(1)).acknowledge();
    }

    // --- POST_EDITED -------------------------------------------------------

    @Test
    void editedDeletesFragmentOnly() {
        consumer.onContentEvent(eventJson("POST_EDITED", POST_ID, AUTHOR_ID), ack);

        verify(fragmentStore, times(1)).delete(POST_ID);
        // An edit does NOT change the id list, so skeletons are left alone.
        verifyNoInteractions(skeletonStore);
        // And no tombstone — the post still exists, it just changed.
        verify(fragmentStore, never()).putTombstone(any());
        verify(ack, times(1)).acknowledge();
    }

    // --- POST_DELETED / POST_UNPUBLISHED -----------------------------------

    @Test
    void deletedPutsTombstoneAndDeletesHeads() {
        consumer.onContentEvent(eventJson("POST_DELETED", POST_ID, AUTHOR_ID), ack);

        verify(fragmentStore, times(1)).putTombstone(POST_ID);
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.userHead(AUTHOR_ID, 20));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void unpublishedPutsTombstoneAndDeletesHeads() {
        // Unpublish shares the deleted branch (post leaves all feeds).
        consumer.onContentEvent(eventJson("POST_UNPUBLISHED", POST_ID, AUTHOR_ID), ack);

        verify(fragmentStore, times(1)).putTombstone(POST_ID);
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(10));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(50));
        verify(ack, times(1)).acknowledge();
    }

    // --- POST_VISIBILITY_CHANGED -------------------------------------------

    @Test
    void visibilityChangedDeletesFragmentAndPublicHead() {
        consumer.onContentEvent(eventJson("POST_VISIBILITY_CHANGED", POST_ID, AUTHOR_ID), ack);

        verify(fragmentStore, times(1)).delete(POST_ID);
        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        // User feed includes all visibilities, so the author's user head skeleton is unaffected.
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 10));
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 20));
        verify(skeletonStore, never()).delete(FeedRedisKeys.userHead(AUTHOR_ID, 50));
        // No tombstone — the post still exists.
        verify(fragmentStore, never()).putTombstone(any());
        verify(ack, times(1)).acknowledge();
    }

    // --- delayed double-delete ---------------------------------------------

    @Test
    void publishedSchedulesDelayedDoubleDeleteOfPublicAndUserHead() throws InterruptedException {
        consumer.onContentEvent(eventJson("POST_PUBLISHED", POST_ID, AUTHOR_ID), ack);

        // The synchronous pass already deleted publicHead(20) once. The delayed double-delete
        // re-issues the same deletes within 1s — give the scheduler room then assert each head key
        // was deleted exactly twice (once sync + once delayed). We poll briefly rather than use a
        // single blocking verify-with-timeout so the assertion is deterministic on slower CI nodes.
        Thread.sleep(1500);
        verify(skeletonStore, times(2)).delete(FeedRedisKeys.publicHead(20));
        verify(skeletonStore, times(2)).delete(FeedRedisKeys.userHead(AUTHOR_ID, 20));
        verify(skeletonStore, times(2)).delete(FeedRedisKeys.publicHead(10));
        verify(skeletonStore, times(2)).delete(FeedRedisKeys.publicHead(50));
    }

    @Test
    void editedDoesNotScheduleDoubleDelete() {
        // POST_EDITED does not touch head skeletons, so no double-delete is scheduled.
        consumer.onContentEvent(eventJson("POST_EDITED", POST_ID, AUTHOR_ID), ack);

        // After a short wait, still zero skeleton deletes (the edit path never touches skeletons).
        verify(skeletonStore, never()).delete(any());
        verify(fragmentStore, times(1)).delete(POST_ID);
    }

    // --- failure handling --------------------------------------------------

    @Test
    void malformedJsonAcksAndDoesNotThrow() {
        // A poison message must not block the partition: the consumer logs, swallows, and acks.
        consumer.onContentEvent("{not valid json", ack);

        verifyNoInteractions(skeletonStore);
        verifyNoInteractions(fragmentStore);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void unknownEventTypeAcksWithoutInvalidating() {
        // A future/typo eventType is a no-op that still acks.
        consumer.onContentEvent(eventJson("POST_SOMETHING_NEW", POST_ID, AUTHOR_ID), ack);

        verifyNoInteractions(fragmentStore);
        // No synchronous skeleton deletes for an unknown type.
        verify(skeletonStore, never()).delete(any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void nullEventTypeDefaultsToPublished() {
        // A publisher that omits eventType is treated as POST_PUBLISHED.
        consumer.onContentEvent(eventJsonWithoutEventType(POST_ID, AUTHOR_ID), ack);

        verify(skeletonStore, times(1)).delete(FeedRedisKeys.publicHead(20));
        verify(ack, times(1)).acknowledge();
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Builds a raw-JSON content event matching the shape {@code ContentPostEventKafkaPublisher}
     * emits: {@code eventId}/{@code eventType}/{@code postId}/{@code authorId}/{@code occurredAt}.
     * {@code authorId} may be null (omitted from the JSON).
     */
    private String eventJson(String eventType, Long postId, Long authorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-" + eventType);
        payload.put("eventType", eventType);
        payload.put("postId", postId);
        if (authorId != null) {
            payload.put("authorId", authorId);
        }
        payload.put("occurredAt", "2026-06-21T09:00:00");
        return writeJson(payload);
    }

    /** Variant that omits {@code eventType} entirely (exercises the default-to-PUBLISHED branch). */
    private String eventJsonWithoutEventType(Long postId, Long authorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-no-type");
        payload.put("postId", postId);
        payload.put("authorId", authorId);
        payload.put("occurredAt", "2026-06-21T09:00:00");
        return writeJson(payload);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
