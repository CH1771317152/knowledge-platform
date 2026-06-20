package com.platform.relation.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.relation.repository.RelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Primary follower-projector consumer. Reads {@code relation-events} (Canal flat messages from the
 * {@code relation_outbox} table), parses out the {@link RelationEventPayload}, and atomically
 * dedups + upserts the {@code relation_follower} read model via
 * {@link RelationRepository#projectIfFirstTime(RelationEventPayload, String)} (the consumed marker
 * and the projection share one transaction, so a crash or throw between them can no longer lose a
 * projection — C1 fix). Any failure (malformed payload, projection error) routes the <em>original</em>
 * raw record value to {@code follower-retry-topic} and then acks the original so the partition keeps
 * moving; one retry attempt happens in {@link RelationFollowerRetryConsumer} before the message lands
 * in the DLQ.
 *
 * <p>Gated with {@code @Profile("!test & !integration")} so no live {@code @KafkaListener} container
 * ever starts in automated tests: the {@code test} profile has no {@code KafkaAutoConfiguration}, and
 * the {@code integration} profile must not spin up live containers that would make the suite
 * non-deterministic. The unit tests construct this consumer directly with a fake repository and a
 * mocked {@link KafkaTemplate}.
 */
@Component
@Profile("!test & !integration")
public class RelationFollowerProjectorConsumer {

    private static final Logger log = LoggerFactory.getLogger(RelationFollowerProjectorConsumer.class);

    private final RelationRepository relationRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String retryTopic;
    private final String consumerGroup;

    public RelationFollowerProjectorConsumer(
            RelationRepository relationRepository,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${platform.relation.kafka.follower-retry-topic}") String retryTopic,
            @Value("${platform.relation.kafka.follower-consumer-group}") String consumerGroup) {
        this.relationRepository = relationRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.retryTopic = retryTopic;
        this.consumerGroup = consumerGroup;
    }

    @KafkaListener(
            topics = "${platform.relation.kafka.events-topic}",
            groupId = "${platform.relation.kafka.follower-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onEvent(String value, Acknowledgment ack) {
        try {
            RelationEventPayload payload = RelationEventParser.parse(objectMapper, value);
            if (payload == null) {
                // Ignored (wrong table / non-insert) — legitimately not our concern.
                ack.acknowledge();
                return;
            }
            // Atomic dedup + projection (one transaction). Returns false on a duplicate for this
            // group (already projected) — that is a clean skip, not an error.
            relationRepository.projectIfFirstTime(payload, consumerGroup);
            ack.acknowledge();
        } catch (Exception failure) {
            // Parse failure (RELATION_EVENT_INVALID) or projection error → route to retry.
            routeToRetry(value, failure);
            ack.acknowledge();
        }
    }

    /**
     * Sends the original raw value to the retry topic. If the retry send itself fails we log and
     * swallow the error so the original partition is not blocked (avoids a poison-loop); the
     * message is effectively lost-to-DLQ-via-logging, which is acceptable for v1.
     */
    private void routeToRetry(String value, Exception cause) {
        log.warn("Follower projection failed; routing raw value to retry topic {}: {}",
                retryTopic, cause.getMessage());
        try {
            kafkaTemplate.send(retryTopic, value);
        } catch (Exception sendFailure) {
            log.error("Retry topic send also failed for value; message will be dropped (DLQ via logging): {}",
                    sendFailure.getMessage());
        }
    }
}
