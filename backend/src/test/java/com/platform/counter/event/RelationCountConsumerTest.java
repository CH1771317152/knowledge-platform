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
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.event.CanalFlatMessage;
import com.platform.relation.event.RelationEventPayload;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pure unit tests for {@link RelationCountConsumer}. The consumer is
 * {@code @Profile("!test & !integration")} so it never starts as a live {@code @KafkaListener}
 * container in any automated test; the tests build it directly with a fake
 * {@link CounterConsumedEventRepository}, a fake {@link CounterStore}, a Mockito-mocked
 * {@link KafkaTemplate}, and a real {@link ObjectMapper#findAndRegisterModules()}.
 */
class RelationCountConsumerTest {

    private static final String EVENTS_TOPIC = CounterEventTopics.EVENTS;
    private static final String RETRY_TOPIC = CounterEventTopics.RETRY;
    private static final String DLQ_TOPIC = CounterEventTopics.DLQ;
    private static final String CONSUMER_GROUP = CounterEventTopics.CONSUMER_GROUP;
    private static final String RETRY_CONSUMER_GROUP = CounterEventTopics.RETRY_CONSUMER_GROUP;
    private static final String RELATION_CONSUMER_GROUP = CounterEventTopics.RELATION_CONSUMER_GROUP;

    private static final Long FOLLOWER = 7L;
    private static final Long FOLLOWING = 42L;

    private FakeConsumedRepo consumedRepo;
    private FakeCounterStore store;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private Acknowledgment ack;
    private ObjectMapper objectMapper;

    private RelationCountConsumer consumer;

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
                        RELATION_CONSUMER_GROUP, "content-events",
                        CounterEventTopics.CONTENT_CONSUMER_GROUP,
                        "counter-snapshot-events", "counter-snapshot-relay-group"),
                new CounterProperties.Flush("adaptive", 1000L, 500L, 5000L, 1000));
        consumer = new RelationCountConsumer(
                consumedRepo, store, kafkaTemplate, objectMapper, properties);
    }

    // --- happy paths ----------------------------------------------------------

    @Test
    void followedIncrementsBothFollowingAndFollowers() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-follow"));

        consumer.onRelationEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(2);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, FOLLOWER,
                        CounterMetric.FOLLOWING, +1L));
        assertThat(store.aggregateCalls.get(1))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, FOLLOWING,
                        CounterMetric.FOLLOWERS, +1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void unfollowDecrementsBoth() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_UNFOLLOWED, "evt-unfollow"));

        consumer.onRelationEvent(value, ack);

        assertThat(store.aggregateCalls).hasSize(2);
        assertThat(store.aggregateCalls.get(0))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, FOLLOWER,
                        CounterMetric.FOLLOWING, -1L));
        assertThat(store.aggregateCalls.get(1))
                .isEqualTo(new AggregateCall(CounterEntityType.USER, FOLLOWING,
                        CounterMetric.FOLLOWERS, -1L));
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void duplicateEventAcksWithoutSecondAggregate() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-dup"));
        consumedRepo.markConsumedFirstReturn = false; // already-consumed for this group

        consumer.onRelationEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void nonOutboxCanalMessageIsIgnored() {
        // Canal message for a different table -> parser returns null -> ack, no aggregate.
        String value = canalMessage("relation_following", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-other"));

        consumer.onRelationEvent(value, ack);

        assertThat(store.aggregateCalls).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetryOrDlqSend();
    }

    @Test
    void malformedRoutesToDlqAndAcks() {
        // Canal envelope is well-formed but payload_json is garbage -> parser throws -> DLQ route.
        String value = canalMessage("relation_outbox", "INSERT", "{not valid json}");

        consumer.onRelationEvent(value, ack);

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

    /** Builds the {@code payload_json} content (a {@link RelationEventPayload} as JSON). */
    private static String payloadJson(RelationEventType type, String eventId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RelationEventPayload payload = new RelationEventPayload(
                eventId, type, "FOLLOW", "FOLLOW:" + FOLLOWER + ":" + FOLLOWING,
                FOLLOWER, FOLLOWING, LocalDateTime.of(2026, 6, 21, 9, 0));
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds a Canal flat message JSON string. The {@code payloadJson} is embedded as a JSON-string
     * value inside the data map's {@code payload_json} column (properly escaped), mirroring
     * {@code RelationFollowerProjectorConsumerTest}'s helper.
     */
    private static String canalMessage(String table, String type, String payloadJson) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, String> row = new HashMap<>();
        row.put("payload_json", payloadJson);
        CanalFlatMessage message = new CanalFlatMessage(
                "knowledge_platform", table, type, List.of(row));
        try {
            return mapper.writeValueAsString(message);
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
