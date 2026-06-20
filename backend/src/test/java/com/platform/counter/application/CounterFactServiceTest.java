package com.platform.counter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.event.CounterEventPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Pure unit test for {@link CounterFactService}. The service is constructed directly with an
 * in-memory {@link FakeCounterStore} (so we can drive the bitmap-transition return values), a
 * Mockito-mocked {@link KafkaTemplate} (Spring Kafka 3.x {@code send} returns a
 * {@link CompletableFuture}), a real {@link ObjectMapper#findAndRegisterModules()}, and a
 * hand-built {@link CounterProperties}.
 *
 * <p>This mirrors {@code RelationFollowerProjectorConsumerTest} and the relation/content
 * command-service tests: {@link CounterFactService} is {@code @Profile("!test")} (it depends on the
 * {@code @Profile("!test")} {@link CounterStore}), so it is excluded from the Spring {@code test}
 * context and must be built by hand to keep {@code PlatformApplicationTests.contextLoads} green.
 */
class CounterFactServiceTest {

    private static final String EVENTS_TOPIC = "counter-events";
    private static final CounterEntityType ETYPE = CounterEntityType.ARTICLE;
    private static final Long EID = 123L;
    private static final long USER = 7L;
    private static final Long AUTHOR = 42L;

    private FakeCounterStore store;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private CounterFactService service;

    @BeforeEach
    void setUp() {
        store = new FakeCounterStore();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        CounterProperties properties = new CounterProperties(
                new CounterProperties.Kafka(
                        EVENTS_TOPIC, "counter-retry", "counter-dlq", "cg", "rcg", "crg", "content-events", "ccg"),
                new CounterProperties.Flush("adaptive", 1000L, 500L, 5000L, 1000));
        service = new CounterFactService(store, kafkaTemplate, objectMapper, properties);
    }

    // --- like / unlike --------------------------------------------------------

    @Test
    void likeOnNewArticleSetsBitAndEmitsIncrement() {
        // FakeCounterStore.setBitIfAbsent defaults to returning true (0→1 transition).
        boolean changed = service.like(USER, ETYPE, EID, AUTHOR);

        assertThat(changed).isTrue();
        assertThat(store.likeSet).contains(USER);

        CounterEventPayload sent = captureSingleSentEvent();
        assertThat(sent.etype()).isEqualTo(ETYPE);
        assertThat(sent.eid()).isEqualTo(EID);
        assertThat(sent.metric()).isEqualTo(CounterMetric.LIKE);
        assertThat(sent.delta()).isEqualTo(+1L);
        assertThat(sent.authorId()).isEqualTo(AUTHOR);
        assertThat(sent.eventId()).isNotBlank();
        assertThat(sent.occurredAt()).isNotNull();
    }

    @Test
    void likeWhenAlreadyLikedIsIdempotentNoEmit() {
        // Simulate "bit already set": setBitIfAbsent returns false.
        store.setBitReturns = false;

        boolean changed = service.like(USER, ETYPE, EID, AUTHOR);

        assertThat(changed).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void unlikeWhenLikedClearsAndEmitsDecrement() {
        // clearBitIfPresent defaults to true (1→0 transition).
        boolean changed = service.unlike(USER, ETYPE, EID, AUTHOR);

        assertThat(changed).isTrue();
        assertThat(store.unlikeCleared).contains(USER);

        CounterEventPayload sent = captureSingleSentEvent();
        assertThat(sent.metric()).isEqualTo(CounterMetric.LIKE);
        assertThat(sent.delta()).isEqualTo(-1L);
    }

    @Test
    void unlikeWhenNotLikedIsIdempotentNoEmit() {
        // Simulate "bit already clear": clearBitIfPresent returns false.
        store.clearBitReturns = false;

        boolean changed = service.unlike(USER, ETYPE, EID, AUTHOR);

        assertThat(changed).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    // --- fav / unfav ----------------------------------------------------------

    @Test
    void favAndUnfavBehaveLikeLikeUnlike() {
        // First fav → transition, +1 FAV.
        boolean favChanged = service.fav(USER, ETYPE, EID, AUTHOR);
        assertThat(favChanged).isTrue();
        assertThat(store.favSet).contains(USER);
        CounterEventPayload favEvent = captureSingleSentEvent();
        assertThat(favEvent.metric()).isEqualTo(CounterMetric.FAV);
        assertThat(favEvent.delta()).isEqualTo(+1L);

        // Now unfav → transition, -1 FAV.
        boolean unfavChanged = service.unfav(USER, ETYPE, EID, AUTHOR);
        assertThat(unfavChanged).isTrue();
        assertThat(store.unfavCleared).contains(USER);
        CounterEventPayload unfavEvent = captureSingleSentEvent();
        assertThat(unfavEvent.metric()).isEqualTo(CounterMetric.FAV);
        assertThat(unfavEvent.delta()).isEqualTo(-1L);

        // Idempotent fav when already fav'd → no emit. Clear the prior (legitimate) sends first so
        // the never() assertion below is scoped to JUST this third call, not the whole test.
        store.setBitReturns = false;
        clearInvocations(kafkaTemplate);
        boolean favAgain = service.fav(USER, ETYPE, EID, AUTHOR);
        assertThat(favAgain).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    // --- view / share ---------------------------------------------------------

    @Test
    void viewEmitsWithIdempotencyKeyAsEventId() {
        String idempotencyKey = "view-key-abc-123";

        service.view(USER, ETYPE, EID, AUTHOR, idempotencyKey);

        CounterEventPayload sent = captureSingleSentEvent();
        assertThat(sent.metric()).isEqualTo(CounterMetric.VIEW);
        assertThat(sent.delta()).isEqualTo(+1L);
        // The idempotency key IS the event id — the aggregator dedupes by it.
        assertThat(sent.eventId()).isEqualTo(idempotencyKey);
        assertThat(sent.authorId()).isEqualTo(AUTHOR);
    }

    @Test
    void shareEmitsWithIdempotencyKeyAsEventId() {
        String idempotencyKey = "share-key-xyz";

        service.share(USER, ETYPE, EID, AUTHOR, idempotencyKey);

        CounterEventPayload sent = captureSingleSentEvent();
        assertThat(sent.metric()).isEqualTo(CounterMetric.SHARE);
        assertThat(sent.delta()).isEqualTo(+1L);
        assertThat(sent.eventId()).isEqualTo(idempotencyKey);
    }

    @Test
    void viewRejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> service.view(USER, ETYPE, EID, AUTHOR, ""))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.COUNTER_EVENT_INVALID);
        assertThatThrownBy(() -> service.view(USER, ETYPE, EID, AUTHOR, "   "))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.COUNTER_EVENT_INVALID);
        assertThatThrownBy(() -> service.view(USER, ETYPE, EID, AUTHOR, null))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.COUNTER_EVENT_INVALID);

        // share too.
        assertThatThrownBy(() -> service.share(USER, ETYPE, EID, AUTHOR, ""))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.COUNTER_EVENT_INVALID);

        // No send ever attempted.
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void likeEventCarriesAuthorIdForFanOut() {
        service.like(USER, ETYPE, EID, AUTHOR);

        // Kafka key pins the event to the entity's partition ("ARTICLE:123"); the authorId is
        // carried INSIDE the JSON so the relation-side consumer can fan it out to the author's
        // likes_received counter.
        verify(kafkaTemplate).send(eq(EVENTS_TOPIC), eq("ARTICLE:123"),
                contains("\"authorId\":42"));
        verify(kafkaTemplate).send(eq(EVENTS_TOPIC), anyString(), contains("\"metric\":\"LIKE\""));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Captures the most recent sent event's JSON, parses it back, and returns the payload. Uses
     * {@link ArgumentCaptor#getAllValues} so it tolerates a test that emits more than once
     * (e.g. {@link #favAndUnfavBehaveLikeLikeUnlike}); the LAST send is the relevant one. Asserts
     * at least one send happened so single-emit tests still fail loudly on a missed emit.
     */
    private CounterEventPayload captureSingleSentEvent() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeast(1)).send(eq(EVENTS_TOPIC), anyString(), captor.capture());
        assertThat(captor.getAllValues())
                .as("expected at least one counter event send")
                .isNotEmpty();
        String json = captor.getAllValues().get(captor.getAllValues().size() - 1);
        try {
            return objectMapper.readValue(json, CounterEventPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("failed to parse emitted JSON: " + json, e);
        }
    }

    /**
     * Minimal fake {@link CounterStore} for the fact service. Only {@code setBitIfAbsent} and
     * {@code clearBitIfPresent} are exercised by {@link CounterFactService}; the others return benign
     * defaults. The transition return values are configurable ({@link #setBitReturns},
     * {@link #clearBitReturns}) so individual tests can simulate "already liked" / "not liked".
     */
    private static final class FakeCounterStore implements CounterStore {
        final List<Long> likeSet = new ArrayList<>();
        final List<Long> favSet = new ArrayList<>();
        final List<Long> unlikeCleared = new ArrayList<>();
        final List<Long> unfavCleared = new ArrayList<>();
        boolean setBitReturns = true;   // 0→1 transition by default
        boolean clearBitReturns = true; // 1→0 transition by default

        @Override
        public boolean setBitIfAbsent(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            if (metric == CounterMetric.LIKE) {
                likeSet.add(userId);
            } else if (metric == CounterMetric.FAV) {
                favSet.add(userId);
            }
            return setBitReturns;
        }

        @Override
        public boolean clearBitIfPresent(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            if (metric == CounterMetric.LIKE) {
                unlikeCleared.add(userId);
            } else if (metric == CounterMetric.FAV) {
                unfavCleared.add(userId);
            }
            return clearBitReturns;
        }

        @Override
        public long readCount(CounterEntityType et, Long eid, CounterMetric metric) {
            return 0L;
        }

        @Override
        public Map<CounterMetric, Long> readCounts(CounterEntityType et, Long eid) {
            return Map.of();
        }

        @Override
        public boolean hasActed(CounterEntityType et, Long eid, CounterMetric metric, long userId) {
            return false;
        }

        @Override
        public void addToAggregate(CounterEntityType et, Long eid, CounterMetric metric, long delta) {
        }

        @Override
        public List<String> drainPendingBatch(int n) {
            return List.of();
        }

        @Override
        public void flushOne(CounterEntityType et, Long eid) {
        }

        @Override
        public long pendingCount() {
            return 0L;
        }
    }
}
