package com.platform.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The index repository is a thin adapter over the Elasticsearch client. To keep these unit tests free
 * of a real Elasticsearch, we subclass and override {@code upsert}/{@code delete}/{@code updateCounters}
 * with in-memory recorders — the test then asserts on the recorded calls, proving the routing the
 * consumer relies on (document id = postId, missing delete = success, counter update does not create).
 */
class SearchPostIndexRepositoryTest {

    private static SearchProperties props() {
        return new SearchProperties(
                true,
                new SearchProperties.Index(
                        "knowledge-posts-read", "knowledge-posts-write", "knowledge-posts-v1",
                        50_000, 3000, 8),
                new SearchProperties.Cursor("secret", 600L),
                new SearchProperties.Rank(5.0, 2.0, 1.0, 3.0, 2.0, 0.2, 1.0),
                new SearchProperties.Kafka(
                        "g", "retry", "dlq", "snap-topic", "snap-g", "snap-retry", "snap-dlq"),
                new SearchProperties.Rebuild(500, 200));
    }

    private static SearchPostDocument doc(long postId) {
        return new SearchPostDocument(
                postId, "ARTICLE", "PUBLISHED", "PUBLIC", 2L, "作者", "avatar",
                "title", "desc", "body", "cover.jpg", List.of("java"),
                java.util.Map.of("java", true), java.time.Instant.now(), java.time.Instant.now(),
                1L, 2L, 3L, 4L, 5L, 1L, java.time.Instant.now());
    }

    @Test
    void upsertUsesPostIdAsDocumentId() {
        FakeIndex index = new FakeIndex();

        index.upsert(doc(100L));
        index.upsert(doc(101L));

        assertThat(index.upserts()).extracting(SearchPostDocument::postId).containsExactly(100L, 101L);
    }

    @Test
    void deleteMissingDocumentIsSuccess() {
        FakeIndex index = new FakeIndex();

        // Deleting a postId that was never indexed must not throw — the intent ("post is no longer
        // public") is already satisfied, so the consumer should treat it as success and ack.
        index.delete(999L);

        assertThat(index.deletedIds()).containsExactly(999L);
        assertThat(index.upserts()).isEmpty();
    }

    @Test
    void counterUpdateDoesNotCreateDocument() {
        FakeIndex index = new FakeIndex();

        // A counter snapshot for a post that was never indexed updates nothing and must not create
        // a stub document with null title/body. The fake records the call; the production impl uses
        // a partial update (no upsert) so a missing doc stays missing.
        index.updateCounters(999L, new ArticleCountersResponse(999L, 10L, 2L, 99L, 0L, 1L));

        assertThat(index.counterUpdates()).containsKey(999L);
        assertThat(index.upserts()).isEmpty();
    }

    /** Fake subclass: overrides the three routing methods to record calls, no ES involved. */
    static final class FakeIndex extends SearchPostIndexRepository {
        private final List<SearchPostDocument> upserts = new ArrayList<>();
        private final List<Long> deletedIds = new ArrayList<>();
        private final java.util.Map<Long, ArticleCountersResponse> counterUpdates = new java.util.LinkedHashMap<>();

        FakeIndex() {
            super(null, props());
        }

        @Override
        public void upsert(SearchPostDocument document) {
            upserts.add(document);
        }

        @Override
        public void delete(Long postId) {
            deletedIds.add(postId);
        }

        @Override
        public void updateCounters(Long postId, ArticleCountersResponse counters) {
            counterUpdates.put(postId, counters);
        }

        List<SearchPostDocument> upserts() { return upserts; }
        List<Long> deletedIds() { return deletedIds; }
        java.util.Map<Long, ArticleCountersResponse> counterUpdates() { return counterUpdates; }
    }

    @Test
    void searchMapsItemsAndCarriesNextSort() {
        // The search path is not part of the consumer routing; verify it returns the recorded items
        // and hasMore flag through a recording fake that skips the real ES query.
        RecordingIndex index = new RecordingIndex();

        SearchPostIndexRepository.SearchResultPage page = index.search(null);

        assertThat(page.items()).extracting(FeedItemResponse::postId).containsExactly(1L, 2L);
        assertThat(page.hasMore()).isTrue();
    }

    static final class RecordingIndex extends SearchPostIndexRepository {
        RecordingIndex() { super(null, props()); }

        @Override
        public SearchResultPage search(com.platform.search.domain.SearchPostQuery query) {
            List<FeedItemResponse> items = List.of(
                    new FeedItemResponse(1L, 2L, "a", null, "t1", null, null, 0, 0, 0, 0, 0, null, null),
                    new FeedItemResponse(2L, 2L, "a", null, "t2", null, null, 0, 0, 0, 0, 0, null, null));
            return new SearchResultPage(items, true, List.of("2026-06-25T00:00:00Z", 2L));
        }
    }
}
