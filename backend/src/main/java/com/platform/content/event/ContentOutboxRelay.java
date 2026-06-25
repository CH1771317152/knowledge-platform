package com.platform.content.event;

import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !integration")
public class ContentOutboxRelay {

    private final ContentOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public ContentOutboxRelay(
            ContentOutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${platform.content.kafka.events-topic}") String topic,
            @Value("${platform.content.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.content.outbox.relay-interval-ms:500}")
    public void flushOnce() {
        for (ContentOutboxEvent event : repository.findUnpublished(batchSize)) {
            kafkaTemplate.send(topic, event.kafkaKey(), event.payloadJson());
            repository.markPublished(event.id(), LocalDateTime.now());
        }
    }
}
