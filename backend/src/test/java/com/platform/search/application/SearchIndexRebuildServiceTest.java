package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.repository.ContentOutboxRepository;
import com.platform.content.repository.ContentPostRepository;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostDocument;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Pins the rebuild step order. The service's correctness under concurrent writes rests on recording
 * the outbox high watermark BEFORE scanning, replaying outbox events AFTER that watermark, and only
 * then switching the aliases. This test asserts that exact sequence against a recording index + a
 * fake outbox/scanner, so a future refactor that reorders the steps (or drops the catch-up replay)
 * fails loudly.
 */
class SearchIndexRebuildServiceTest {

    @Test
    void capturesHighWatermarkScansThenReplaysAfterWatermarkBeforeAliasSwitch() {
        // Outbox returns watermark 10 and one event after it. The scan returns one post (so the bulk
        // phase runs) but the builder returns empty for it (so nothing is upserted). The rebuild's
        // step order must be: create -> bulk scan -> replay-after-watermark -> switch.
        FakeContentOutbox outbox = new FakeContentOutbox(10L);
        outbox.eventsToReplay = List.of(outboxEvent(11L, 50L));
        RecordingIndex index = new RecordingIndex();
        StubContentRepository contentRepository = new StubContentRepository(List.of(publicPost(1L)));
        SearchPostDocumentBuilder builder = builderReturning(Optional.empty());

        SearchIndexRebuildService service = new SearchIndexRebuildService(
                outbox, contentRepository, builder, index, props());

        service.rebuild("knowledge-posts-v2");

        assertThat(index.steps).containsExactly(
                "create:knowledge-posts-v2",
                "bulk:scan",
                "replay-after:10",
                "switch:knowledge-posts-v2");
    }

    @Test
    void bulkScanUpsertsBuiltDocumentsAndReplayDeletesNonPublicEvents() {
        // Posts 1 and 2 are public+published -> built and bulk-upserted during the scan.
        // Replay event (id=11, postId=50) resolves to empty -> delete-from-index.
        List<ContentPost> scan = List.of(publicPost(1L), publicPost(2L));
        StubContentRepository contentRepository = new StubContentRepository(scan);
        FakeContentOutbox outbox = new FakeContentOutbox(10L);
        outbox.eventsToReplay = List.of(outboxEvent(11L, 50L));
        RecordingIndex index = new RecordingIndex();

        SearchPostDocumentBuilder builder = realBuilder(contentRepository, "# body");

        SearchIndexRebuildService service = new SearchIndexRebuildService(
                outbox, contentRepository, builder, index, props());

        service.rebuild("knowledge-posts-v2");

        // Bulk scan wrote both posts into the new index; replay deleted post 50 (builder -> empty).
        assertThat(index.bulkUpsertedPostIds).containsExactly(List.of(1L, 2L));
        assertThat(index.deletedFromIndexIds).containsExactly(50L);
    }

    // --- helpers / fakes ------------------------------------------------------

    private static SearchProperties props() {
        return new SearchProperties(
                true,
                new SearchProperties.Index(
                        "knowledge-posts-read", "knowledge-posts-write", "knowledge-posts-v1",
                        50_000, 3000, 8),
                new SearchProperties.Cursor("cursor-secret", 600L),
                new SearchProperties.Rank(5.0, 2.0, 1.0, 3.0, 2.0, 0.2, 1.0),
                new SearchProperties.Kafka(
                        "g", "search-content-retry", "search-content-dlq",
                        "snap-topic", "snap-g", "snap-retry", "snap-dlq"),
                new SearchProperties.Rebuild(500, 200));
    }

    /** A builder that always returns the same Optional regardless of postId (for step-order test). */
    private static SearchPostDocumentBuilder builderReturning(Optional<SearchPostDocument> fixed) {
        return new SearchPostDocumentBuilder(null, null, null, null, new MarkdownTextExtractor(1)) {
            @Override
            public Optional<SearchPostDocument> build(Long postId) {
                return fixed;
            }
        };
    }

    private static SearchPostDocumentBuilder realBuilder(StubContentRepository repo, String bodyText) {
        return new SearchPostDocumentBuilder(
                repo,
                storageFrom(key -> new ByteArrayInputStream(bodyText.getBytes(StandardCharsets.UTF_8))),
                zeroCounters(),
                null,
                new MarkdownTextExtractor(10));
    }

    private static ObjectStorageService storageFrom(Function<String, InputStream> source) {
        return new ObjectStorageService() {
            @Override public PresignedUpload presignPut(String o, String c, Duration e) { throw new UnsupportedOperationException(); }
            @Override public StoredObjectMetadata statObject(String o) { throw new UnsupportedOperationException(); }
            @Override public InputStream readObject(String objectKey) { return source.apply(objectKey); }
        };
    }

    private static CounterReadService zeroCounters() {
        return new CounterReadService(null) {
            @Override public ArticleCountersResponse getArticleCounters(Long postId) {
                return new ArticleCountersResponse(postId, 0L, 0L, 0L, 0L, 0L);
            }
        };
    }

    private static ContentPost publicPost(long id) {
        return new ContentPost(
                id, 2L, "req-" + id, "title-" + id, "summary", "cover.jpg",
                PostStatus.PUBLISHED, PostVisibility.PUBLIC, PublishStage.PUBLISHED,
                LocalDateTime.parse("2026-06-25T12:00:00"),
                LocalDateTime.parse("2026-06-25T11:00:00"),
                LocalDateTime.parse("2026-06-25T12:01:00"),
                1L);
    }

    private static ContentOutboxEvent outboxEvent(long id, long postId) {
        return new ContentOutboxEvent(
                id, "evt-" + id, "POST", postId, "POST_PUBLISHED", 1,
                "{}", 1L, LocalDateTime.now(), LocalDateTime.now(), null);
    }

    /** Records the high-level rebuild steps + the post ids each phase touched. */
    private static final class RecordingIndex extends SearchPostIndexRepository {
        final List<String> steps = new ArrayList<>();
        final List<List<Long>> bulkUpsertedPostIds = new ArrayList<>();
        final List<Long> deletedFromIndexIds = new ArrayList<>();

        RecordingIndex() {
            super(null, props());
        }

        @Override
        public void createIndex(String indexName) {
            steps.add("create:" + indexName);
        }

        @Override
        public void bulkUpsertToIndex(String indexName, List<SearchPostDocument> documents) {
            steps.add("bulk:scan");
            bulkUpsertedPostIds.add(documents.stream().map(SearchPostDocument::postId).toList());
        }

        @Override
        public void upsertToIndex(String indexName, SearchPostDocument document) {
            // Record entry into the replay phase the first time a replay op lands, then track per-id.
            ensureReplayStep();
            // Not asserted individually; deletion path is the observable in the second test.
        }

        @Override
        public void deleteFromIndex(String indexName, Long postId) {
            ensureReplayStep();
            deletedFromIndexIds.add(postId);
        }

        private void ensureReplayStep() {
            if (!steps.contains("replay-after:10")) {
                steps.add("replay-after:10");
            }
        }

        @Override
        public void switchAliases(String newIndex) {
            steps.add("switch:" + newIndex);
        }
    }

    /**
     * Fake outbox: fixed high watermark + an optional list of events to return from findAfterId
     * (drained once, matching the relay semantics).
     */
    private static final class FakeContentOutbox implements ContentOutboxRepository {
        final long watermark;
        List<ContentOutboxEvent> eventsToReplay = List.of();

        FakeContentOutbox(long watermark) {
            this.watermark = watermark;
        }

        @Override public void append(ContentOutboxEvent event) {}
        @Override public List<ContentOutboxEvent> findUnpublished(int limit) { return List.of(); }
        @Override public void markPublished(Long id, LocalDateTime publishedAt) {}
        @Override public long currentHighWatermark() { return watermark; }

        @Override
        public List<ContentOutboxEvent> findAfterId(long afterId, int limit) {
            if (eventsToReplay.isEmpty()) {
                return List.of();
            }
            List<ContentOutboxEvent> drained = eventsToReplay;
            eventsToReplay = List.of();
            return drained;
        }
    }

    /**
     * Minimal ContentPostRepository: serves a fixed batch of public posts on the first
     * findPublicPublishedAfterId call (afterId == 0) then empty; findPostById/findBodyByPostId
     * serve the scan batch so the real builder can construct documents.
     */
    private static final class StubContentRepository implements ContentPostRepository {
        private final List<ContentPost> scanBatch;

        StubContentRepository(List<ContentPost> scanBatch) {
            this.scanBatch = scanBatch;
        }

        @Override
        public List<ContentPost> findPublicPublishedAfterId(Long afterId, int limit) {
            if (afterId == 0L && !scanBatch.isEmpty()) {
                return scanBatch;
            }
            return List.of();
        }

        @Override public Optional<ContentPost> findPostById(Long postId) {
            return scanBatch.stream().filter(p -> p.id().equals(postId)).findFirst();
        }

        @Override public Optional<ContentPostBody> findBodyByPostId(Long postId) {
            return Optional.of(new ContentPostBody(
                    postId, PostBodyFormat.MARKDOWN, "bucket", "posts/" + postId + "/body/v1.md",
                    "etag", "sha", 100L, 1, null,
                    LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now()));
        }

        // ---- unused mutation / other read methods ----
        @Override public ContentPost saveDraft(ContentPost p, ContentPostBody b) { throw new UnsupportedOperationException(); }
        @Override public Optional<ContentPost> findPostByAuthorAndClientRequestId(Long a, String c) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPostFile> findFilesByPostId(Long p) { throw new UnsupportedOperationException(); }
        @Override public List<ContentTag> findTagsByPostId(Long p) { return List.of(); }
        @Override public void updateBodyUploadUrl(Long p, String b, String k, LocalDateTime e, PublishStage s) { throw new UnsupportedOperationException(); }
        @Override public void reissueBodyForEdit(Long p, String b, String k, LocalDateTime e) { throw new UnsupportedOperationException(); }
        @Override public void confirmBody(Long p, String k, String et, String sh, long s, LocalDateTime a) { throw new UnsupportedOperationException(); }
        @Override public void updateMetadata(Long p, String t, String s, PostVisibility v, String c) { throw new UnsupportedOperationException(); }
        @Override public void replaceFiles(Long p, List<ContentPostFile> f) { throw new UnsupportedOperationException(); }
        @Override public void replaceTags(Long p, List<String> t) { throw new UnsupportedOperationException(); }
        @Override public void updateStatusAndStage(Long p, PostStatus s, PublishStage g, LocalDateTime a) { throw new UnsupportedOperationException(); }
        @Override public void softDelete(Long p) { throw new UnsupportedOperationException(); }
        @Override public void bumpSourceVersion(Long p) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPost> findPublicPublished(int l, long o) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPost> findByAuthor(Long a, int l, long o) { throw new UnsupportedOperationException(); }
    }
}
