package com.platform.search.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.search.application.SearchPostDocumentBuilder;
import com.platform.search.config.SearchProperties;
import com.platform.search.domain.SearchPostDocument;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The consumer is the search module's authoritative write path. These tests pin the four routing
 * outcomes that the manual-ack error model depends on:
 *
 * <ul>
 *   <li><b>published</b> &rarr; builder resolves a document &rarr; upsert.</li>
 *   <li><b>private / draft / deleted</b> &rarr; builder returns empty &rarr; delete (delete-missing is success).</li>
 *   <li><b>malformed JSON</b> &rarr; irrecoverable &rarr; DLQ (never retry, never upsert/delete).</li>
 *   <li><b>builder failure</b> &rarr; recoverable &rarr; retry topic (never DLQ).</li>
 * </ul>
 *
 * In every case the offset is acknowledged so a poison message cannot stall the partition.
 */
class SearchContentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecordingIndex index;
    private SearchPostDocumentBuilder builder;
    private KafkaTemplate<String, String> kafka;
    private Acknowledgment ack;
    private SearchContentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        index = new RecordingIndex();
        builder = mock(SearchPostDocumentBuilder.class);
        kafka = mock(KafkaTemplate.class);
        ack = mock(Acknowledgment.class);
        consumer = new SearchContentEventConsumer(objectMapper, builder, index, kafka, props());
    }

    @Test
    void publishedEventBuildsAndUpserts() {
        when(builder.build(100L)).thenReturn(Optional.of(doc(100L)));

        consumer.onContentEvent(
                "{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":100}", ack);

        assertThat(index.upserts).extracting(SearchPostDocument::postId).containsExactly(100L);
        assertThat(index.deletedIds).isEmpty();
        verify(ack).acknowledge();
        verifyNoInteractions(kafka);
    }

    @Test
    void privatePostDeletesIndex() {
        // The event payload says POST_PUBLISHED, but the builder re-checks the source of truth and finds
        // the post is now private (or deleted). The event type is ignored — the empty Optional wins.
        when(builder.build(100L)).thenReturn(Optional.empty());

        consumer.onContentEvent(
                "{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":100}", ack);

        assertThat(index.deletedIds).containsExactly(100L);
        assertThat(index.upserts).isEmpty();
        verify(ack).acknowledge();
        verifyNoInteractions(kafka);
    }

    @Test
    void malformedRoutesToDlq() {
        // Unparseable JSON is irrecoverable: retry will never fix it. Route straight to DLQ and ack so
        // the partition is not stalled. No upsert/delete must happen.
        consumer.onContentEvent("{not valid json", ack);

        verify(kafka).send(eq("search-content-dlq"), anyString());
        verify(kafka, never()).send(eq("search-content-retry"), anyString());
        verify(ack).acknowledge();
        assertThat(index.upserts).isEmpty();
        assertThat(index.deletedIds).isEmpty();
    }

    @Test
    void missingPostIdRoutesToDlq() {
        // A structurally-valid JSON document that lacks a postId is just as irrecoverable: we cannot
        // identify the aggregate to rebuild. DLQ + ack.
        consumer.onContentEvent("{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\"}", ack);

        verify(kafka).send(eq("search-content-dlq"), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void builderFailureRoutesToRetry() {
        // A transient builder failure (e.g. OSS body read threw IllegalStateException) is recoverable —
        // a later retry will likely succeed once OSS recovers. Route to retry, never DLQ, and ack.
        when(builder.build(100L)).thenThrow(new IllegalStateException("oss read failed"));

        consumer.onContentEvent(
                "{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":100}", ack);

        verify(kafka).send(eq("search-content-retry"), anyString());
        verify(kafka, never()).send(eq("search-content-dlq"), anyString());
        verify(ack).acknowledge();
        assertThat(index.upserts).isEmpty();
        assertThat(index.deletedIds).isEmpty();
    }

    @Test
    void alwaysAcksEvenOnUnexpectedError() {
        // Defense-in-depth: an unexpected runtime error from the index repository must still ack to
        // avoid a partition stall. The message goes to retry (recoverable bucket) for another attempt.
        when(builder.build(100L)).thenReturn(Optional.of(doc(100L)));
        index.throwOnNextUpsert = true;

        consumer.onContentEvent(
                "{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":100}", ack);

        verify(kafka).send(eq("search-content-retry"), anyString());
        verify(ack, times(1)).acknowledge();
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

    private static SearchPostDocument doc(long postId) {
        return new SearchPostDocument(
                postId, "ARTICLE", "PUBLISHED", "PUBLIC", 2L, "作者", "avatar",
                "title", "desc", "body", "cover.jpg", List.of("java"),
                java.util.Map.of("java", true), java.time.Instant.now(), java.time.Instant.now(),
                1L, 2L, 3L, 4L, 5L, 1L, java.time.Instant.now());
    }

    /** In-memory index that records upserts/deletes; can be primed to throw on the next upsert. */
    private static final class RecordingIndex extends SearchPostIndexRepository {
        final List<SearchPostDocument> upserts = new ArrayList<>();
        final List<Long> deletedIds = new ArrayList<>();
        boolean throwOnNextUpsert;

        RecordingIndex() {
            super(null, props());
        }

        @Override
        public void upsert(SearchPostDocument document) {
            if (throwOnNextUpsert) {
                throwOnNextUpsert = false;
                throw new IllegalStateException("index write failed");
            }
            upserts.add(document);
        }

        @Override
        public void delete(Long postId) {
            deletedIds.add(postId);
        }
    }
}
