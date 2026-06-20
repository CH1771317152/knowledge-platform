package com.platform.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.application.CounterStore;
import com.platform.counter.config.CounterProperties;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.repository.CounterConsumedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * USER-side {@code posts_count} feeder. Consumes the content module's {@code content-events} topic,
 * which carries raw-JSON {@link ContentPublishedPayload} objects (content publishes directly from an
 * {@code AFTER_COMMIT} listener, NOT via Canal), and folds a {@code +1 POSTS} delta into the Redis
 * agg for the post's author.
 *
 * <p>Idempotency: dedups via {@link CounterConsumedEventRepository#markConsumed(String, String)}
 * under this consumer's own group ({@code counter-content-group}). Any failure (malformed JSON or an
 * aggregate error) routes the original raw record value to {@code ${platform.counter.kafka.dlq-topic}}
 * and acks.
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
public class ContentPublishCountConsumer {

    private static final Logger log = LoggerFactory.getLogger(ContentPublishCountConsumer.class);

    private final CounterConsumedEventRepository consumedRepo;
    private final CounterStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CounterProperties properties;

    public ContentPublishCountConsumer(
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
            topics = "${platform.counter.kafka.content-events-topic}",
            groupId = "${platform.counter.kafka.content-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onPublishEvent(String value, Acknowledgment ack) {
        try {
            ContentPublishedPayload payload = objectMapper.readValue(value, ContentPublishedPayload.class);
            // Dedup under this consumer's own group; first-consumer-wins.
            if (!consumedRepo.markConsumed(payload.eventId(), properties.kafka().contentConsumerGroup())) {
                ack.acknowledge();
                return;
            }
            store.addToAggregate(CounterEntityType.USER, payload.authorId(),
                    CounterMetric.POSTS, +1L);
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
        log.warn("Counter content publish aggregation failed; routing raw value to DLQ topic {}: {}",
                dlqTopic, cause.getMessage());
        try {
            kafkaTemplate.send(dlqTopic, value);
        } catch (Exception sendFailure) {
            log.error("Route topic send also failed for value; message will be dropped: {}",
                    sendFailure.getMessage());
        }
    }
}
