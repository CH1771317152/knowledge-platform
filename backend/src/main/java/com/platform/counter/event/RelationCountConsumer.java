package com.platform.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.application.CounterStore;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.repository.CounterConsumedEventRepository;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.event.RelationEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * USER-side {@code following}/{@code followers} count feeder. Consumes the relation module's
 * {@code relation-events} topic (Canal flat messages over {@code relation_outbox} INSERTs), reuses
 * {@link RelationEventParser} to unwrap the Canal envelope and recover the
 * {@link RelationEventPayload}, then folds the follow transition into the Redis agg:
 * <ul>
 *   <li>{@code USER_FOLLOWED} &#8594; {@code +1 FOLLOWING} for the follower and {@code +1 FOLLOWERS}
 *       for the target being followed.</li>
 *   <li>{@code USER_UNFOLLOWED} &#8594; {@code -1} on each.</li>
 * </ul>
 *
 * <p>Idempotency: dedups via {@link CounterConsumedEventRepository#markConsumed(String, String)}
 * under this consumer's own group ({@code counter-relation-group}), so the same event projected by
 * the relation module's follower projector and by this counter consumer never double-counts. Any
 * failure (a malformed Canal message the parser refuses, or an aggregate error) routes the original
 * raw record value to {@code ${platform.counter.kafka.dlq-topic}} and acks. Non-{@code relation_outbox}
 * / non-{@code INSERT} Canal messages yield {@code null} from the parser and are acked-and-skipped
 * (normal — counter only cares about relation outbox INSERTs).
 *
 * <p><b>Failure routing note:</b> failures route directly to {@code counter-dlq} (not
 * {@code counter-retry}, which is owned by the aggregate consumer for counter-events); these counts
 * are SQL-reconcilable via the future reconciliation task.
 *
 * <p>Gated with {@code @Profile("!test & !integration")} so no live {@code @KafkaListener} container
 * starts in automated tests; unit tests construct this consumer directly with a fake repository, a
 * fake {@link CounterStore}, a mocked {@link KafkaTemplate}, and a real {@link ObjectMapper}.
 */
@Component
@Profile("!test & !integration")
public class RelationCountConsumer {

    private static final Logger log = LoggerFactory.getLogger(RelationCountConsumer.class);

    private final CounterConsumedEventRepository consumedRepo;
    private final CounterStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CounterProperties properties;

    public RelationCountConsumer(
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
            topics = "${platform.relation.kafka.events-topic}",
            groupId = "${platform.counter.kafka.relation-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onRelationEvent(String value, Acknowledgment ack) {
        RelationEventPayload payload;
        try {
            payload = RelationEventParser.parse(objectMapper, value);
        } catch (Exception failure) {
            // Malformed Canal message or payload_json the parser refuses — route to counter DLQ.
            route(value, failure);
            ack.acknowledge();
            return;
        }
        // Wrong table / non-INSERT → not ours; ack and skip.
        if (payload == null) {
            ack.acknowledge();
            return;
        }
        try {
            // Dedup under this consumer's own group; first-consumer-wins.
            if (!consumedRepo.markConsumed(payload.eventId(), properties.kafka().relationConsumerGroup())) {
                ack.acknowledge();
                return;
            }
            long delta = payload.eventType() == RelationEventType.USER_FOLLOWED ? +1L : -1L;
            store.addToAggregate(CounterEntityType.USER, payload.followerId(),
                    CounterMetric.FOLLOWING, delta);
            store.addToAggregate(CounterEntityType.USER, payload.followingId(),
                    CounterMetric.FOLLOWERS, delta);
            ack.acknowledge();
        } catch (Exception failure) {
            route(value, failure);
            ack.acknowledge();
        }
    }

    /**
     * Sends the original raw {@code value} to the counter DLQ topic. If the send itself fails we
     * log and swallow so the partition is not blocked (avoids a poison-loop), mirroring
     * {@code CounterAggregateConsumer}.
     *
     * <p>Failures route directly to {@code counter-dlq} (not {@code counter-retry}, which is owned
     * by the aggregate consumer for counter-events); these counts are SQL-reconcilable via the future
     * reconciliation task.
     */
    private void route(String value, Exception cause) {
        String dlqTopic = properties.kafka().dlqTopic();
        log.warn("Counter relation aggregation failed; routing raw value to DLQ topic {}: {}",
                dlqTopic, cause.getMessage());
        try {
            kafkaTemplate.send(dlqTopic, value);
        } catch (Exception sendFailure) {
            log.error("Route topic send also failed for value; message will be dropped: {}",
                    sendFailure.getMessage());
        }
    }
}
