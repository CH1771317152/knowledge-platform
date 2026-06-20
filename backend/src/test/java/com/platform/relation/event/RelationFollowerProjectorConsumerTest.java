package com.platform.relation.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.repository.RelationRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Pure unit tests for {@link RelationFollowerProjectorConsumer} and the DLQ routing of
 * {@link RelationFollowerRetryConsumer}. Both consumers are {@code @Profile("!test & !integration")}
 * so they never start as live {@code @KafkaListener} containers in any automated test; the tests
 * build them directly with a fake {@link RelationRepository}, a real
 * {@link ObjectMapper#findAndRegisterModules()}, and a Mockito-mocked {@link KafkaTemplate}.
 */
class RelationFollowerProjectorConsumerTest {

    private static final String RETRY_TOPIC = RelationEventTopics.FOLLOWER_RETRY;
    private static final String DLQ_TOPIC = RelationEventTopics.FOLLOWER_DLQ;
    private static final String CONSUMER_GROUP = RelationEventTopics.FOLLOWER_CONSUMER_GROUP;
    private static final String RETRY_CONSUMER_GROUP = RelationEventTopics.FOLLOWER_RETRY_CONSUMER_GROUP;

    private static final Long FOLLOWER = 7L;
    private static final Long FOLLOWING = 42L;

    private FakeRelationRepository repository;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private Acknowledgment ack;

    private RelationFollowerProjectorConsumer consumer;
    private RelationFollowerRetryConsumer retryConsumer;

    @BeforeEach
    void setUp() {
        repository = new FakeRelationRepository();
        ack = mock(Acknowledgment.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        when(kafkaTemplate.send(any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(any(String.class), any(), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        consumer = new RelationFollowerProjectorConsumer(
                repository, objectMapper, kafkaTemplate, RETRY_TOPIC, CONSUMER_GROUP);
        retryConsumer = new RelationFollowerRetryConsumer(
                repository, objectMapper, kafkaTemplate, DLQ_TOPIC, RETRY_CONSUMER_GROUP);
    }

    // --- projector: happy paths -----------------------------------------------

    @Test
    void parsesCanalMessageAndUpsertsActiveProjection() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-1"));

        consumer.onEvent(value, ack);

        assertThatUpsert("evt-1", FollowStatus.ACTIVE);
        verify(ack, times(1)).acknowledge();
        verifyNoRetrySend();
    }

    @Test
    void parsesUnfollowEventAndUpsertsCanceledProjection() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_UNFOLLOWED, "evt-2"));

        consumer.onEvent(value, ack);

        assertThatUpsert("evt-2", FollowStatus.CANCELED);
        verify(ack, times(1)).acknowledge();
        verifyNoRetrySend();
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutSecondUpsert() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-dup"));
        repository.markConsumedFirstReturn = false; // simulate already-consumed for this group

        consumer.onEvent(value, ack);

        assertThat(repository.upserts).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetrySend();
    }

    // --- projector: ignored (ack + skip) --------------------------------------

    @Test
    void ignoredForNonOutboxTable() {
        String value = canalMessage("relation_following", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-x"));

        consumer.onEvent(value, ack);

        assertThat(repository.upserts).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetrySend();
    }

    @Test
    void ignoredForNonInsertType() {
        String value = canalMessage("relation_outbox", "UPDATE",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-u"));

        consumer.onEvent(value, ack);

        assertThat(repository.upserts).isEmpty();
        verify(ack, times(1)).acknowledge();
        verifyNoRetrySend();
    }

    // --- projector: failure routes to retry -----------------------------------

    @Test
    void malformedPayloadRoutesToRetry() {
        String value = canalMessage("relation_outbox", "INSERT", "{not valid json}");

        consumer.onEvent(value, ack);

        assertThat(repository.upserts).isEmpty();
        verify(kafkaTemplate, times(1)).send(eq(RETRY_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void projectionFailureRoutesToRetry() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-fail"));
        repository.upsertThrow = new RuntimeException("projection blew up");

        consumer.onEvent(value, ack);

        verify(kafkaTemplate, times(1)).send(eq(RETRY_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    // --- retry consumer: success + DLQ routing --------------------------------

    @Test
    void retryConsumerProjectsAndAcksOnSuccess() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-retry-ok"));

        retryConsumer.onRetry(value, ack);

        assertThatUpsert("evt-retry-ok", FollowStatus.ACTIVE);
        verify(ack, times(1)).acknowledge();
        verifyNoDlqSend();
    }

    @Test
    void retryConsumerRoutesToDlqOnFailure() {
        String value = canalMessage("relation_outbox", "INSERT",
                payloadJson(RelationEventType.USER_FOLLOWED, "evt-retry-fail"));
        repository.upsertThrow = new RuntimeException("still failing");

        retryConsumer.onRetry(value, ack);

        verify(kafkaTemplate, times(1)).send(eq(DLQ_TOPIC), eq(value));
        verify(ack, times(1)).acknowledge();
    }

    // --- helpers / assertions -------------------------------------------------

    private void assertThatUpsert(String eventId, FollowStatus status) {
        assertThat(repository.upserts).as("expected an upsert").hasSize(1);
        UpsertCall call = repository.upserts.get(0);
        assertThat(call.status()).isEqualTo(status);
        assertThat(call.event().eventId()).isEqualTo(eventId);
        assertThat(call.event().followerId()).isEqualTo(FOLLOWER);
        assertThat(call.event().followingId()).isEqualTo(FOLLOWING);
    }

    private void verifyNoRetrySend() {
        verify(kafkaTemplate, never()).send(eq(RETRY_TOPIC), any(String.class));
        verify(kafkaTemplate, never()).send(eq(RETRY_TOPIC), any(), any(String.class));
    }

    private void verifyNoDlqSend() {
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), any(String.class));
        verify(kafkaTemplate, never()).send(eq(DLQ_TOPIC), any(), any(String.class));
    }

    /** Builds the {@code payload_json} content (a {@link RelationEventPayload} as JSON). */
    private static String payloadJson(RelationEventType type, String eventId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RelationEventPayload payload = new RelationEventPayload(
                eventId, type, "FOLLOW", "FOLLOW:" + FOLLOWER + ":" + FOLLOWING,
                FOLLOWER, FOLLOWING, LocalDateTime.of(2026, 6, 19, 9, 0));
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds a Canal flat message JSON string. The {@code payloadJson} is embedded as a JSON-string
     * value inside the data map's {@code payload_json} column (properly escaped).
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

    /** Records a single upsert call for assertion. */
    private record UpsertCall(RelationEventPayload event, FollowStatus status) {}

    /**
     * In-memory {@link RelationRepository} for these consumer tests. The consumers now call
     * {@link #projectIfFirstTime(RelationEventPayload, String)}, which must behave like the real
     * adapter: on a duplicate for the group it returns {@code false} (skip); otherwise it records
     * the consumed marker and applies the projection (or throws if {@code upsertThrow} is set).
     *
     * <p>The {@code markConsumedFirstReturn} knob drives the duplicate/skip path of
     * {@code projectIfFirstTime}; {@code upsertThrow} drives the projection-failure path (the fake
     * throws AFTER the marker would be recorded, mirroring the real rollback trigger).
     */
    private static final class FakeRelationRepository implements RelationRepository {
        final List<UpsertCall> upserts = new ArrayList<>();
        boolean markConsumedFirstReturn = true;
        RuntimeException upsertThrow;

        @Override
        public Optional<com.platform.relation.domain.UserFollowing> findFollowing(Long followerId, Long followingId) {
            return Optional.empty();
        }

        @Override
        public void insertFollowing(Long followerId, Long followingId, FollowStatus status, LocalDateTime followedAt) {
        }

        @Override
        public void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt) {
        }

        @Override
        public void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt) {
        }

        @Override
        public void insertOutbox(com.platform.relation.domain.RelationOutboxEvent event) {
        }

        @Override
        public List<com.platform.relation.domain.UserFollowing> findFollowingList(Long followerId, int limit, long offset) {
            return List.of();
        }

        @Override
        public List<com.platform.relation.domain.UserFollower> findFollowerList(Long userId, int limit, long offset) {
            return List.of();
        }

        @Override
        public void upsertFollowerProjection(RelationEventPayload event, FollowStatus status) {
            if (upsertThrow != null) {
                throw upsertThrow;
            }
            upserts.add(new UpsertCall(event, status));
        }

        @Override
        public boolean markConsumed(String eventId, String consumerGroup) {
            return markConsumedFirstReturn;
        }

        @Override
        public boolean projectIfFirstTime(RelationEventPayload event, String consumerGroup) {
            // Step 1: dedup marker. Duplicate for this group → skip (return false).
            if (!markConsumed(event.eventId(), consumerGroup)) {
                return false;
            }
            // Step 2: apply the projection in the "same transaction". If upsertThrow is set the
            // real adapter would roll back the marker; here we just propagate the throw so the
            // consumer routes to retry / DLQ.
            FollowStatus status = event.eventType() == RelationEventType.USER_FOLLOWED
                    ? FollowStatus.ACTIVE
                    : FollowStatus.CANCELED;
            upsertFollowerProjection(event, status);
            return true;
        }
    }
}
