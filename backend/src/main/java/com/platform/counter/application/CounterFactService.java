package com.platform.counter.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.event.CounterEventPayload;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Write side of the counter module: like / unlike / fav / unfav (bitmap transition + emit) and
 * view / share (Idempotency-Key + emit).
 *
 * <p><b>Idempotency model (two distinct mechanisms, one per interaction shape):</b>
 *
 * <ul>
 *   <li><b>like / unlike / fav / unfav</b> are <i>stateful</i> interactions. A user may hold at most
 *       one LIKE bit (and one FAV bit) per entity, so these are made idempotent by the bitmap itself:
 *       {@link CounterStore#setBitIfAbsent} returns {@code true} iff the bit was previously 0, and
 *       {@link CounterStore#clearBitIfPresent} returns {@code true} iff the bit was previously 1. An
 *       event is emitted <b>only on a real state transition</b> — a repeat like on an already-liked
 *       entity is a silent no-op. The event id is a fresh {@link UUID#randomUUID()} per transition;
 *       these are inherently non-idempotent at the consumer, but they fire at most once per user per
 *       side because the bitmap guards them.</li>
 *
 *   <li><b>view / share</b> are <i>stateless</i> interactions — every action legitimately counts, so
 *       there is no bitmap to gate on. They are made exactly-once at the consumer by carrying a
 *       client-supplied idempotency key, which becomes BOTH the Kafka event id (so the consumer can
 *       dedupe via the consumed-event ledger) and the implicit precondition: a blank key is rejected
 *       with {@link ErrorCode#COUNTER_EVENT_INVALID}. Every view/share emits unconditionally.</li>
 * </ul>
 *
 * <p><b>Partitioning / local order:</b> each emitted event uses the Kafka key {@code "ETYPE:EID"}
 * (e.g. {@code "ARTICLE:123"}), so all increments for one entity land on one partition. This keeps
 * per-entity events in arrival order on the aggregator, which (together with HINCRBY atomicity in
 * Redis) is what makes the aggregated delta correct.
 *
 * <p><b>Fire-and-forget send:</b> {@link KafkaTemplate#send} returns a {@code CompletableFuture}
 * which is intentionally <i>not</i> awaited — emitting is a non-blocking, best-effort step. A failed
 * send (broker down) means that one increment is lost; counter reconciliation (TODO) recovers it from
 * the bitmap for like/fav and from the consumed-event ledger for view/share. Blocking here would tie
 * the request's latency to broker availability.
 *
 * <p><b>Profile discipline:</b> this bean depends on the {@code @Profile("!test")}
 * {@link CounterStore} (Redis impl), so it is itself {@code @Profile("!test")} to keep
 * {@code PlatformApplicationTests.contextLoads} green. The unit test constructs it directly with a
 * fake {@code CounterStore}, a Mockito-mocked {@code KafkaTemplate}, and a real
 * {@code ObjectMapper} — mirroring {@code RelationCommandService} /
 * {@code RelationFollowerProjectorConsumerTest}.
 */
@Service
@Profile("!test")
public class CounterFactService {

    private final CounterStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CounterProperties properties;

    public CounterFactService(CounterStore store,
                              KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              CounterProperties properties) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Records a like. Emits a {@code LIKE +1} event iff this user had not already liked the entity
     * (bitmap 0→1 transition). Returns {@code true} on the transition, {@code false} if already
     * liked (idempotent no-op).
     */
    public boolean like(long userId, CounterEntityType etype, Long eid, Long authorId) {
        boolean transition = store.setBitIfAbsent(etype, eid, CounterMetric.LIKE, userId);
        if (transition) {
            emit(etype, eid, CounterMetric.LIKE, +1, authorId, UUID.randomUUID().toString());
        }
        return transition;
    }

    /**
     * Records an unlike. Emits a {@code LIKE -1} event iff this user had previously liked the entity
     * (bitmap 1→0 transition). Returns {@code true} on the transition, {@code false} if not liked
     * (idempotent no-op).
     */
    public boolean unlike(long userId, CounterEntityType etype, Long eid, Long authorId) {
        boolean transition = store.clearBitIfPresent(etype, eid, CounterMetric.LIKE, userId);
        if (transition) {
            emit(etype, eid, CounterMetric.LIKE, -1, authorId, UUID.randomUUID().toString());
        }
        return transition;
    }

    /** Fav counterpart to {@link #like}; emits {@code FAV +1} on the 0→1 transition. */
    public boolean fav(long userId, CounterEntityType etype, Long eid, Long authorId) {
        boolean transition = store.setBitIfAbsent(etype, eid, CounterMetric.FAV, userId);
        if (transition) {
            emit(etype, eid, CounterMetric.FAV, +1, authorId, UUID.randomUUID().toString());
        }
        return transition;
    }

    /** Unfav counterpart to {@link #unlike}; emits {@code FAV -1} on the 1→0 transition. */
    public boolean unfav(long userId, CounterEntityType etype, Long eid, Long authorId) {
        boolean transition = store.clearBitIfPresent(etype, eid, CounterMetric.FAV, userId);
        if (transition) {
            emit(etype, eid, CounterMetric.FAV, -1, authorId, UUID.randomUUID().toString());
        }
        return transition;
    }

    /**
     * Records a view. <b>Always emits</b> a {@code VIEW +1} event — views are stateless and every
     * action counts; dedupe happens at the consumer via the idempotency key (which becomes the event
     * id). A blank idempotency key is rejected because there is no other way to dedupe a view.
     */
    public void view(long userId, CounterEntityType etype, Long eid, Long authorId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        emit(etype, eid, CounterMetric.VIEW, +1, authorId, idempotencyKey);
    }

    /** Share counterpart to {@link #view}; always emits {@code SHARE +1}, event id = idempotency key. */
    public void share(long userId, CounterEntityType etype, Long eid, Long authorId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        emit(etype, eid, CounterMetric.SHARE, +1, authorId, idempotencyKey);
    }

    // --- helpers --------------------------------------------------------------

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PlatformException(ErrorCode.COUNTER_EVENT_INVALID,
                    "idempotency key required");
        }
    }

    /**
     * Builds the {@link CounterEventPayload} for the given mutation and sends its JSON to the counter
     * events topic, keyed by {@code "ETYPE:EID"} for per-entity partition affinity. The Kafka key is
     * derived from {@code etype}/{@code eid} (not the event id) so all increments for one entity
     * land on one partition, preserving local order on the aggregator.
     *
     * <p>A {@link JsonProcessingException} is wrapped as {@link ErrorCode#COUNTER_EVENT_INVALID} —
     * the payload is a simple record over enums and primitives, so a serialization failure is a
     * programmer/config error rather than a transient broker issue.
     *
     * <p>The returned {@code CompletableFuture} from {@link KafkaTemplate#send} is intentionally
     * dropped (fire-and-forget); see the class Javadoc.
     */
    private void emit(CounterEntityType etype, Long eid, CounterMetric metric, long delta,
                      Long authorId, String eventId) {
        CounterEventPayload payload = new CounterEventPayload(
                eventId, etype, metric, eid, delta, authorId, LocalDateTime.now());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.COUNTER_EVENT_INVALID,
                    "Failed to serialize counter event payload");
        }
        kafkaTemplate.send(properties.kafka().eventsTopic(), etype + ":" + eid, json);
    }
}
