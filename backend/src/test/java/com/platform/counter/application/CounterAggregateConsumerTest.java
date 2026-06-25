package com.platform.counter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.event.CounterEventPayload;
import com.platform.counter.event.CounterEventTopics;
import com.platform.counter.repository.CounterConsumedEventRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pure unit tests for {@link CounterAggregateConsumer}. The consumer is
 * {@code @Profile("!test & !integration")} so it never starts as a live {@code @KafkaListener}
 * container in any automated test; the tests build it directly with a fake
 * {@link CounterConsumedEventRepository}, a fake {@link CounterStore}, a Mockito-mocked
 * {@link KafkaTemplate}, and a real {@link ObjectMapper#findAndRegisterModules()}.
 */
class CounterAggregateConsumerTest {

    private static final String EVENTS_TOPIC = CounterEventTopics.EVENTS;
    private static final String RETRY_TOPIC = CounterEventTopics.RETRY;
    private static final String DLQ_TOPIC = CounterEventTopics.DLQ;
    private static final String CONSUMER_GROUP = CounterEventTopics.CONSUMER_GROUP;
    private static final String RETRY_CONSUMER_GROUP = CounterEventTopics.RETRY_CONSUMER_GROUP;

    private static final Long ARTICLE_ID = 42L;
    private static final Long AUTHOR_ID = 7L;

    private FakeConsumedRepo consumedRepo;
    private FakeCounterStore store;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private Acknowledgment ack;
    private ObjectMapper objectMapper;

    private CounterAggregateConsumer consumer;

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
                        CounterEventTopics.CONTENT_CONSUMER_GROUP,
                        "counter-snapshot-events", "counter-snapshot-relay-group"),
                new CounterProperties.Flush("adaptive", 1000L, 500L, 5000L, 1000));
        consumer = new CounterAggregateConsumer(
                consumedRepo, store, kafkaTemplate, objectMapper, properties);
    }

    // --- happy paths ----------------------------------------------------------

    @Test
    void parsesEventAggregatesAndAcks() {
        String value = payloadJson("evt-1", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.VIEW, 1L, null);

        consumer.onEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        AggregateCall call = store.aggregateCalls.get(0);
        assertThat(call.etype()).isEqualTo(CounterEntityType.ARTICLE);
        assertThat(call.eid()).isEqualTo(ARTICLE_ID);
        assertThat(call.metric()).isEqualTo(CounterMetric.VIEW);
        assertThat(call.delta()).isEqualTo(1L);
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void duplicateEventAcksWithoutSecondAggregate() {
        String value = payloadJson("evt-dup", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.VIEW, 1L, null);
        consumedRepo.markConsumedFirstReturn = false; // already-consumed for this group

        consumer.onEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    // --- fan-out --------------------------------------------------------------

    @Test
    void likeEventFanOutsToAuthorReceived() {
        String value = payloadJson("evt-like", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.LIKE, 1L, AUTHOR_ID);

        consumer.onEvent(value, ack);

        // Primary + author fan-out (in that order).
        assertThat(store.aggregateCalls).hasSize(2);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.ARTICLE, ARTICLE_ID, CounterMetric.LIKE, 1L));
        assertThat(store.aggregateCalls.get(1))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.LIKES_RECEIVED, 1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void favEventFanOutsToAuthorFavReceived() {
        String value = payloadJson("evt-fav", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.FAV, 1L, AUTHOR_ID);

        consumer.onEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(2);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.ARTICLE, ARTICLE_ID, CounterMetric.FAV, 1L));
        assertThat(store.aggregateCalls.get(1))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, AUTHOR_ID,
                        CounterMetric.FAVS_RECEIVED, 1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void viewEventDoesNotFanOut() {
        String value = payloadJson("evt-view", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.VIEW, 1L, AUTHOR_ID);

        consumer.onEvent(value, ack);

        // Only the primary VIEW aggregate — no author fan-out.
        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0).metric()).isEqualTo(CounterMetric.VIEW);
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void likeEventWithoutAuthorDoesNotFanOut() {
        String value = payloadJson("evt-like-noauthor", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.LIKE, 1L, null);

        consumer.onEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(1);
        assertThat(store.aggregateCalls.get(0).metric()).isEqualTo(CounterMetric.LIKE);
        verify(ack, times(1)).acknowledge();
    }

    // --- failure routing ------------------------------------------------------

    @Test
    void malformedEventRoutesToRetryAndAcks() {
        String value = "{not valid json}";

        consumer.onEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(kafkaTemplate, times(1)).send(eq(RETRY_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void aggregateFailureRoutesToRetryAndAcks() {
        String value = payloadJson("evt-fail", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.LIKE, 1L, AUTHOR_ID);
        store.throwOnMetric = CounterMetric.LIKE;

        consumer.onEvent(value, ack);

        verify(kafkaTemplate, times(1)).send(eq(RETRY_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void retryFailureRoutesToDlqAndAcks() {
        String value = payloadJson("evt-retry-fail", CounterEntityType.ARTICLE, ARTICLE_ID,
                CounterMetric.LIKE, 1L, AUTHOR_ID);
        store.throwOnMetric = CounterMetric.LIKE;

        consumer.onRetry(value, ack);

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

    private static String payloadJson(String eventId, CounterEntityType etype, Long eid,
                                      CounterMetric metric, long delta, Long authorId) {
        try {
            CounterEventPayload payload = new CounterEventPayload(
                    eventId, etype, metric, eid, delta, authorId,
                    LocalDateTime.of(2026, 6, 21, 9, 0));
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(payload);
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
     * In-memory {@link CounterStore}. Records every {@code addToAggregate} call; optionally throws
     * when a matching metric is aggregated (to simulate a downstream aggregate failure).
     */
    private static final class FakeCounterStore implements CounterStore {
        final List<AggregateCall> aggregateCalls = new ArrayList<>();
        CounterMetric throwOnMetric;

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
            if (throwOnMetric != null && throwOnMetric == metric) {
                throw new RuntimeException("aggregate blew up for " + metric);
            }
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
