package com.platform.counter.event;

import com.platform.counter.repository.CounterSnapshotOutboxRepository;
import com.platform.counter.repository.CounterSnapshotOutboxRepository.OutboxRow;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains unpublished {@code counter_snapshot_outbox} rows and forwards each payload verbatim to the
 * {@code counter-snapshot-events} Kafka topic, then marks the row published.
 *
 * <p>Mirrors {@code ContentOutboxRelay} exactly: findUnpublished → kafkaTemplate.send → markPublished.
 * Repeated passes are harmless because a published row is no longer selected. The Kafka key is
 * {@code ARTICLE:{postId}} (from {@link OutboxRow#kafkaKey()}) so all snapshots for one article land
 * in the same partition and apply in order.
 *
 * <p>{@code @Profile("!test & !integration")} matches the other scheduled relays and Kafka listeners —
 * no live {@code @Scheduled} task or broker wiring is active in automated tests; the unit test
 * constructs the relay directly and calls {@link #flushOnce()}.
 */
@Component
@Profile("!test & !integration")
public class CounterSnapshotRelay {

    private final CounterSnapshotOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public CounterSnapshotRelay(
            CounterSnapshotOutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${platform.counter.kafka.snapshot-topic}") String topic,
            @Value("${platform.counter.snapshot.relay-batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.counter.snapshot.relay-interval-ms:500}")
    public void flushOnce() {
        for (OutboxRow row : repository.findUnpublished(batchSize)) {
            kafkaTemplate.send(topic, row.kafkaKey(), row.payloadJson());
            repository.markPublished(row.id(), LocalDateTime.now());
        }
    }
}
