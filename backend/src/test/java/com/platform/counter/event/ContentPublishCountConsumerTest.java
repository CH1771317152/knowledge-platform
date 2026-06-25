package com.platform.counter.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.application.CounterStore;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.repository.CounterConsumedEventRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pure unit tests for {@link ContentPublishCountConsumer}. The consumer is
 * {@code @Profile("!test & !integration")} so it never starts as a live {@code @KafkaListener}
 * container in any automated test; the tests build it directly with a fake
 * {@link CounterConsumedEventRepository}, a fake {@link CounterStore}, a Mockito-mocked
 * {@link KafkaTemplate}, and a real {@link ObjectMapper#findAndRegisterModules()}.
 */
class ContentPublishCountConsumerTest {

    private static final String EVENTS_TOPIC = CounterEventTopics.EVENTS;
    private static final String RETRY_TOPIC = CounterEventTopics.RETRY;
    private static final String DLQ_TOPIC = CounterEventTopics.DLQ;
    private static final String CONSUMER_GROUP = CounterEventTopics.CONSUMER_GROUP;
    private static final String RETRY_CONSUMER_GROUP = CounterEventTopics.RETRY_CONSUMER_GROUP;
    private static final String CONTENT_CONSUMER_GROUP = CounterEventTopics.CONTENT_CONSUMER_GROUP;

    private static final Long AUTHOR_ID = 7L;
    private static final Long POST_ID = 99L;

    private FakeConsumedRepo consumedRepo;
    private FakeCounterStore store;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private Acknowledgment ack;
    private ObjectMapper objectMapper;

    private ContentPublishCountConsumer consumer;

    @BeforeEach
    void setUp() {
        consumedRepo = new FakeConsumedRepo();
        store = new FakeCounterStore();
        ack = mock(Acknowledgment.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(kafkaTemplate.send(any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(any(String.class), any(), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        CounterProperties properties = new CounterProperties(
                new CounterProperties.Kafka(
                        EVENTS_TOPIC, RETRY_TOPIC, DLQ_TOPIC,
                        CONSUMER_GROUP, RETRY_CONSUMER_GROUP,
                        CounterEventTopics.RELATION_CONSUMER_GROUP, "content-events",
                        CONTENT_CONSUMER_GROUP,
                        "counter-snapshot-events", "counter-snapshot-relay-group"),
                new CounterProperties.Flush("adaptive", 1000L, 500L, 5000L, 1000));
        consumer = new ContentPublishCountConsumer(
                consumedRepo, store, kafkaTemplate, objectMapper, properties);
    }

    // --- happy paths ----------------------------------------------------------

    @Test
    void publishIncrementsAuthorPostsCount() {
        String value = publishPayloadJson("evt-publish", "POST_PUBLISHED");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.POSTS, +1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void unpublishEventDecrementsPostsCount() {
        String value = publishPayloadJson("evt-unpublish", "POST_UNPUBLISHED");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.POSTS, -1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void deletedEventDecrementsPostsCount() {
        String value = publishPayloadJson("evt-delete", "POST_DELETED");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.POSTS, -1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void editedEventIsIgnored() {
        String value = publishPayloadJson("evt-edit", "POST_EDITED");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void visibilityChangedEventIsIgnored() {
        // Defensive: POST_VISIBILITY_CHANGED is the other "no posts_count delta" event.
        String value = publishPayloadJson("evt-visibility", "POST_VISIBILITY_CHANGED");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void nullEventTypeDefaultsToPublished() {
        // A publisher that omits eventType (backward compat) is treated as POST_PUBLISHED.
        String value = publishPayloadJsonWithoutEventType("evt-null-etype");

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.POSTS, +1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void duplicatePublishAcksWithoutSecondAggregate() {
        String value = publishPayloadJson("evt-dup");
        consumedRepo.markConsumedFirstReturn = false; // already-consumed for this group

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    // --- failure routing ------------------------------------------------------

    @Test
    void malformedRoutesToDlqAndAcks() {
        String value = "{not valid json}";

        consumer.onPublishEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(kafkaTemplate, times(1)).send(eq(DLQ_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    // --- helpers / assertions -------------------------------------------------

    private void verifyNoRetryOrDlqSend() {
        verify(kafkaTemplate, never()).send(eq(RETRY_TOPIC), any(String.class));
        verify(kafkaTemplate, never()).send(eq(RETRY_TOPIC), any(), any(String.class));
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), any(String.class));
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), any(), any(String.class));
    }

    /**
     * Builds a raw-JSON {@link ContentPublishedPayload} matching what content's
     * {@code ContentPostEventKafkaPublisher} emits (NOT a Canal flat message), with the given
     * {@code eventType}.
     */
    private static String publishPayloadJson(String eventId, String eventType) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ContentPublishedPayload payload = new ContentPublishedPayload(
                eventId, POST_ID, AUTHOR_ID, eventType, "2026-06-21T09:00:00");
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Convenience for callers that still pass only an eventId (defaults to POST_PUBLISHED). */
    private static String publishPayloadJson(String eventId) {
        return publishPayloadJson(eventId, "POST_PUBLISHED");
    }

    /**
     * Builds a payload that omits {@code eventType} entirely — exercises the consumer's
     * null-defaults-to-{@code POST_PUBLISHED} branch.
     */
    private static String publishPayloadJsonWithoutEventType(String eventId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        // Build the JSON manually so eventType is truly absent (not just null), matching a legacy
        // publisher that predates the eventType field.
        String json = "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"postId\":" + POST_ID + ","
                + "\"authorId\":" + AUTHOR_ID + ","
                + "\"occurredAt\":\"2026-06-21T09:00:00\""
                + "}";
        // Verify it deserializes via Jackson's unknown-property-tolerant path into the record.
        try {
            return mapper.writeValueAsString(
                    mapper.readValue(json, ContentPublishedPayload.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Records one {@code addToAggregate} invocation for assertion. */
    private record AggregateCall(CounterEntityType etype, Long eid, CounterMetric metric, long delta) {}

    /** Fake repo whose {@code markConsumed} return is knob-driven (default: first-consumer-wins). */
    private static final class FakeConsumedRepo implements CounterConsumedEventRepository {
        boolean markConsumedFirstReturn = true;

        @Override
        public boolean markConsumed(String eventId, String consumerGroup) {
            return markConsumedFirstReturn;
        }
    }

    /**
     * In-memory {@link CounterStore}. Records every {@code addToAggregate} call.
     */
    private static final class FakeCounterStore implements CounterStore {
        final List<AggregateCall> aggregateCalls = new ArrayList<>();

        @Override
        public long readCount(CounterEntityType etype, Long eid, CounterMetric metric) {
            return 0;
        }

        @Override
        public Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid) {
            return Map.of();
        }

        @Override
        public boolean hasActed(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public boolean setBitIfAbsent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return true;
        }

        @Override
        public boolean clearBitIfPresent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public void addToAggregate(CounterEntityType etype, Long eid, CounterMetric metric, long delta) {
            aggregateCalls.add(new AggregateCall(etype, eid, metric, delta));
        }

        @Override
        public List<String> drainPendingBatch(int n) {
            return List.of();
        }

        @Override
        public void flushOne(CounterEntityType etype, Long eid) {
        }

        @Override
        public long pendingCount() {
            return 0;
        }
    }
}
