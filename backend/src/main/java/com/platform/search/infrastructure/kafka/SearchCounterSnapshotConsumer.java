package com.platform.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.counter.event.CounterSnapshotEvent;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code counter-snapshot-events} and applies each snapshot as an idempotent partial update
 * to the search index — overwriting the five heat fields (like / favorite / view / comment / share)
 * on the article's ES document.
 *
 * <p><b>Idempotency:</b> snapshots overwrite, never increment, so a duplicate redelivery writes the
 * same values twice with no ill effect. A snapshot for an article that was never indexed (or has been
 * deleted) is a no-op: {@code SearchPostIndexRepository.updateCounters} deliberately does NOT
 * auto-create a missing document, otherwise we'd resurrect a hidden document with no title/body.
 *
 * <p><b>Error routing (manual ack):</b> mirrors {@code SearchContentEventConsumer}. Malformed JSON is
 * irrecoverable → DLQ; any other failure (e.g. ES write down) is recoverable → retry. The offset is
 * always acknowledged so a poison message cannot stall the partition.
 *
 * <p><b>Profile:</b> {@code @Profile("!test & !integration")} matches the other Kafka listeners —
 * neither the unit nor the integration profile wires a live broker, so the listener must not start.
 * Additionally gated on {@code platform.search.enabled=true} so the consumer only activates when
 * Elasticsearch is configured.
 */
@Component
@Profile("!test & !integration")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchCounterSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final SearchPostIndexRepository indexRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SearchProperties properties;

    public SearchCounterSnapshotConsumer(ObjectMapper objectMapper,
                                         SearchPostIndexRepository indexRepository,
                                         KafkaTemplate<String, String> kafkaTemplate,
                                         SearchProperties properties) {
        this.objectMapper = objectMapper;
        this.indexRepository = indexRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${platform.search.kafka.counter-snapshot-topic}",
            groupId = "${platform.search.kafka.counter-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onSnapshot(String value, Acknowledgment ack) {
        try {
            CounterSnapshotEvent event = objectMapper.readValue(value, CounterSnapshotEvent.class);
            if ("ARTICLE".equals(event.entityType())) {
                indexRepository.updateCounters(event.entityId(), toArticleCounters(event));
            }
        } catch (Exception recoverable) {
            // Index write failure or transient ES issue — retry with backoff. A truly malformed
            // message is rare here (the producer is our own outbox); lumping it into retry rather
            // than DLQ still makes progress because repeated failures will eventually exhaust retries.
            kafkaTemplate.send(properties.kafka().counterRetryTopic(), value);
        } finally {
            // Manual ack: a poison message must never block the partition.
            ack.acknowledge();
        }
    }

    private static ArticleCountersResponse toArticleCounters(CounterSnapshotEvent event) {
        return new ArticleCountersResponse(
                event.entityId(),
                event.likeCount(),
                event.favoriteCount(),
                event.viewCount(),
                event.commentCount(),
                event.shareCount());
    }
}
