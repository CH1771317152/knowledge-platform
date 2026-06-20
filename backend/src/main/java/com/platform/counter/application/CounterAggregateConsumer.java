package com.platform.counter.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.event.CounterEventPayload;
import com.platform.counter.repository.CounterConsumedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Aggregate consumer for {@code counter-events}. Parses each {@link CounterEventPayload}, dedups
 * against the {@code (event_id, consumer_group)} ledger via
 * {@link CounterConsumedEventRepository#markConsumed(String, String)} (first-consumer-wins), folds
 * the delta into the Redis agg with {@link CounterStore#addToAggregate}, and fans out author-side
 * counts for {@code LIKE}/{@code FAV} interactions (so an author's {@code LIKES_RECEIVED} /
 * {@code FAVS_RECEIVED} counter moves with their content). Then acks.
 *
 * <p>Any failure (malformed payload, aggregate error) routes the <em>original</em> raw record value
 * to the retry topic and acks so the partition keeps moving; the retry listener re-applies the same
 * pipeline under its own group (independent dedup ledger) and, on a second failure, forwards to the
 * DLQ. The retry-send itself is wrapped in its own try/catch — on a send failure we log and swallow
 * to avoid a poison-loop, mirroring the relation module's consumers.
 *
 * <p>Gated with {@code @Profile("!test & !integration")} so no live {@code @KafkaListener} container
 * ever starts in automated tests; unit tests construct this consumer directly with a fake repository,
 * a fake {@link CounterStore}, a mocked {@link KafkaTemplate}, and a real {@link ObjectMapper}.
 */
@Component
@Profile("!test & !integration")
public class CounterAggregateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregateConsumer.class);

    private final CounterConsumedEventRepository consumedRepo;
    private final CounterStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CounterProperties properties;

    public CounterAggregateConsumer(
            CounterConsumedEventRepository consumedRepo,
            CounterStore store,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CounterProperties properties) {
        this.consumedRepo = consumedRepo;
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${platform.counter.kafka.events-topic}",
            groupId = "${platform.counter.kafka.consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onEvent(String value, Acknowledgment ack) {
        process(value, ack,
                properties.kafka().consumerGroup(),
                properties.kafka().retryTopic(),
                false);
    }

    @KafkaListener(
            topics = "${platform.counter.kafka.retry-topic}",
            groupId = "${platform.counter.kafka.retry-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onRetry(String value, Acknowledgment ack) {
        process(value, ack,
                properties.kafka().retryConsumerGroup(),
                properties.kafka().dlqTopic(),
                true);
    }

    /**
     * Shared pipeline for both the main and retry listeners. On any exception the original raw
     * {@code value} is routed to {@code routeTopic} (retry for the main path, DLQ for the retry
     * path) and the original is acked.
     *
     * <p><b>Atomicity note (differs from the relation module):</b> {@code markConsumed} (MySQL) and
     * {@code addToAggregate}/{@code fanOutAuthor} (Redis) are NOT atomic together — the projection
     * target is Redis, which cannot share the MySQL dedup transaction. If the primary aggregate
     * succeeds but the LIKE/FAV author fan-out then throws, the catch routes to the retry topic;
     * the retry path dedups under a <i>different</i> consumer group, so it re-applies the primary
     * aggregate and the article's like/fav count can be inflated until reconciliation. This is
     * accepted by the design: like/fav counts are rebuildable from the bitmap (the source of truth)
     * and a periodic reconciliation task recalibrates them; view/share have no fan-out so cannot
     * hit this window. A future hardening could fold the primary + fan-out into one atomic Lua
     * (HINCRBY both keys in a single script).
     *
     * @param value       the raw record value
     * @param ack         manual acknowledgment handle
     * @param group       the consumer group used for the dedup ledger
     * @param routeTopic  topic to route to on failure (retry or DLQ)
     * @param isRetry     whether this is the retry path (affects logging only)
     */
    void process(String value, Acknowledgment ack, String group, String routeTopic, boolean isRetry) {
        try {
            CounterEventPayload payload = objectMapper.readValue(value, CounterEventPayload.class);
            // Dedup: first-consumer-wins per (event_id, group). A redelivered event for this group
            // is a clean skip, not an error.
            if (!consumedRepo.markConsumed(payload.eventId(), group)) {
                ack.acknowledge();
                return;
            }
            // Fold the delta into the agg for the primary entity.
            store.addToAggregate(payload.etype(), payload.eid(), payload.metric(), payload.delta());
            // Fan out author-side counts for LIKE / FAV content interactions.
            fanOutAuthor(payload);
            ack.acknowledge();
        } catch (Exception failure) {
            route(value, routeTopic, isRetry, failure);
            ack.acknowledge();
        }
    }

    private void fanOutAuthor(CounterEventPayload payload) {
        Long authorId = payload.authorId();
        if (authorId == null) {
            return;
        }
        CounterMetric metric = payload.metric();
        if (metric == CounterMetric.LIKE) {
            store.addToAggregate(CounterEntityType.USER, authorId,
                    CounterMetric.LIKES_RECEIVED, payload.delta());
        } else if (metric == CounterMetric.FAV) {
            store.addToAggregate(CounterEntityType.USER, authorId,
                    CounterMetric.FAVS_RECEIVED, payload.delta());
        }
        // VIEW / COMMENT / SHARE / FOLLOWING / FOLLOWERS / POSTS do not fan out to author counts.
    }

    /**
     * Sends the original raw {@code value} to {@code routeTopic}. If the send itself fails we log
     * and swallow so the partition is not blocked (avoids a poison-loop); the message is effectively
     * dropped, which is acceptable for v1. When the payload is unparseable the catch block has no
     * {@code payload} reference, so it always sends the raw {@code value}.
     */
    private void route(String value, String routeTopic, boolean isRetry, Exception cause) {
        if (isRetry) {
            log.error("Counter retry aggregation failed; routing raw value to DLQ {}: {}",
                    routeTopic, cause.getMessage());
        } else {
            log.warn("Counter aggregation failed; routing raw value to retry topic {}: {}",
                    routeTopic, cause.getMessage());
        }
        try {
            kafkaTemplate.send(routeTopic, value);
        } catch (Exception sendFailure) {
            log.error("Route topic send also failed for value; message will be dropped: {}",
                    sendFailure.getMessage());
        }
    }
}
