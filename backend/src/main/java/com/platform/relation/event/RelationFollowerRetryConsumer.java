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
 * Retry consumer for the follower projector. Reads {@code follower-retry-topic} (messages the
 * {@link RelationFollowerProjectorConsumer} could not project on the first attempt) and re-applies
 * the same parse &#8594; atomic dedup + upsert pipeline. A single retry attempt: on success it acks;
 * on any failure it forwards the <em>original</em> raw value to {@code follower-dlq-topic} and acks,
 * so the retry partition is not blocked. This matches the v1 design ("第一版 retry topic 消费失败直接进入
 * DLQ").
 *
 * <p>Uses the <em>retry-consumer-group</em> for
 * {@link RelationRepository#projectIfFirstTime(RelationEventPayload, String)}, giving the retry path
 * its own independent dedup ledger separate from the main projector.
 *
 * <p>Gated with {@code @Profile("!test & !integration")} — never starts a live {@code @KafkaListener}
 * container in automated tests.
 */
@Component
@Profile("!test & !integration")
public class RelationFollowerRetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(RelationFollowerRetryConsumer.class);

    private final RelationRepository relationRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;
    private final String retryConsumerGroup;

    public RelationFollowerRetryConsumer(
            RelationRepository relationRepository,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${platform.relation.kafka.follower-dlq-topic}") String dlqTopic,
            @Value("${platform.relation.kafka.follower-retry-consumer-group}") String retryConsumerGroup) {
        this.relationRepository = relationRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
        this.retryConsumerGroup = retryConsumerGroup;
    }

    @KafkaListener(
            topics = "${platform.relation.kafka.follower-retry-topic}",
            groupId = "${platform.relation.kafka.follower-retry-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onRetry(String value, Acknowledgment ack) {
        try {
            RelationEventPayload payload = RelationEventParser.parse(objectMapper, value);
            if (payload == null) {
                // Wrong table / non-insert after all — nothing to retry-project.
                ack.acknowledge();
                return;
            }
            // Atomic dedup + projection under the retry group (own ledger).
            relationRepository.projectIfFirstTime(payload, retryConsumerGroup);
            ack.acknowledge();
        } catch (Exception failure) {
            routeToDlq(value, failure);
            ack.acknowledge();
        }
    }

    /**
     * Forwards the original raw value to the DLQ. If the DLQ send itself fails we log and swallow
     * so the retry partition is not blocked.
     */
    private void routeToDlq(String value, Exception cause) {
        log.error("Follower retry projection failed; routing raw value to DLQ {}: {}",
                dlqTopic, cause.getMessage());
        try {
            kafkaTemplate.send(dlqTopic, value);
        } catch (Exception sendFailure) {
            log.error("DLQ send also failed for value; message will be dropped: {}",
                    sendFailure.getMessage());
        }
    }
}
