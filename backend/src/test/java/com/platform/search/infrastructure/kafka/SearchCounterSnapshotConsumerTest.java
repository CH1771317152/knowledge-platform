package com.platform.search.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pins the three routing outcomes of the counter-snapshot consumer:
 *
 * <ul>
 *   <li><b>valid ARTICLE snapshot</b> &rarr; partial update of the five heat fields on ES.</li>
 *   <li><b>index write failure</b> &rarr; recoverable &rarr; retry topic (never DLQ).</li>
 *   <li><b>non-ARTICLE entity</b> &rarr; silently ignored (Search only ranks articles).</li>
 * </ul>
 *
 * In every case the offset is acknowledged so a poison message cannot stall the partition. The
 * underlying index update is idempotent (overwrite, never increment) and does NOT create a missing
 * document — that no-create behavior is the index repository's responsibility, exercised in its own
 * test; here we only assert the consumer calls updateCounters with the right entityId/values.
 */
class SearchCounterSnapshotConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private RecordingIndex index;
    private KafkaTemplate<String, String> kafka;
    private Acknowledgment ack;
    private SearchCounterSnapshotConsumer consumer;

    @BeforeEach
    void setUp() {
        index = new RecordingIndex();
        kafka = mock(KafkaTemplate.class);
        ack = mock(Acknowledgment.class);
        consumer = new SearchCounterSnapshotConsumer(objectMapper, index, kafka, props());
    }

    @Test
    void articleSnapshotAppliesPartialUpdate() {
        consumer.onSnapshot(snapshotJson("ARTICLE", 100L, 10L, 2L, 99L, 4L, 1L), ack);

        assertThat(index.updatedEntityIds).containsExactly(100L);
        ArticleCountersResponse applied = index.lastUpdate;
        assertThat(applied.postId()).isEqualTo(100L);
        assertThat(applied.like()).isEqualTo(10L);
        assertThat(applied.fav()).isEqualTo(2L);
        assertThat(applied.view()).isEqualTo(99L);
        assertThat(applied.comment()).isEqualTo(4L);
        assertThat(applied.share()).isEqualTo(1L);
        verify(ack).acknowledge();
        verifyNoInteractions(kafka);
    }

    @Test
    void nonArticleSnapshotIsIgnored() {
        // Only ARTICLE entities are part of the search ranking signal; USER/other snapshots are dropped.
        consumer.onSnapshot(snapshotJson("USER", 5L, 1L, 0L, 0L, 0L, 0L), ack);

        assertThat(index.updatedEntityIds).isEmpty();
        verify(ack).acknowledge();
        verifyNoInteractions(kafka);
    }

    @Test
    void indexFailureRoutesToRetryAndAcks() {
        // A transient ES failure is recoverable — route to retry, never DLQ, and ack to avoid a stall.
        index.throwOnNextUpdate = true;
        consumer.onSnapshot(snapshotJson("ARTICLE", 100L, 1L, 0L, 0L, 0L, 0L), ack);

        verify(kafka).send(eq("search-counter-retry"), anyString());
        verify(kafka, never()).send(eq("search-counter-dlq"), anyString());
        verify(ack).acknowledge();
    }

    @Test
    void malformedJsonRoutesToRetryAndAcks() {
        // Unparseable JSON: the consumer's single catch-all routes to retry (a retry topic backstop is
        // still progress; the producer is our own outbox, so malformed input is rare).
        consumer.onSnapshot("{not valid json", ack);

        verify(kafka).send(eq("search-counter-retry"), anyString());
        verify(ack).acknowledge();
        assertThat(index.updatedEntityIds).isEmpty();
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
                        "counter-snapshot-events", "snap-g", "search-counter-retry", "search-counter-dlq"),
                new SearchProperties.Rebuild(500, 200));
    }

    private static String snapshotJson(String entityType, Long entityId,
                                       long like, long fav, long view, long comment, long share) {
        String at = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return """
                {"eventId":"e1","entityType":"%s","entityId":%d,"likeCount":%d,"favoriteCount":%d,"viewCount":%d,"commentCount":%d,"shareCount":%d,"occurredAt":"%s"}\
                """.formatted(entityType, entityId, like, fav, view, comment, share, at);
    }

    /** In-memory index that records updateCounters calls; can be primed to throw on the next update. */
    private static final class RecordingIndex extends SearchPostIndexRepository {
        final java.util.List<Long> updatedEntityIds = new java.util.ArrayList<>();
        ArticleCountersResponse lastUpdate;
        boolean throwOnNextUpdate;

        RecordingIndex() {
            super(null, props());
        }

        @Override
        public void updateCounters(Long postId, ArticleCountersResponse counters) {
            if (throwOnNextUpdate) {
                throwOnNextUpdate = false;
                throw new IllegalStateException("index write failed");
            }
            updatedEntityIds.add(postId);
            lastUpdate = counters;
        }
    }
}
