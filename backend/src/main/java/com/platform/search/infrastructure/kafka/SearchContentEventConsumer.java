package com.platform.search.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.search.application.SearchPostDocumentBuilder;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes naked-JSON {@code content-events} and projects the current post state into the search
 * index. This is the search module's authoritative write path: every lifecycle mutation
 * (publish / edit / visibility / unpublish / delete) flows through here.
 *
 * <p><b>The C3 "don't trust the payload" pattern:</b> the event value is parsed only to extract the
 * {@code postId}; the event type and other fields are ignored. The current document is then rebuilt
 * from the Content / Storage / User / Counter source-of-truth modules via
 * {@link SearchPostDocumentBuilder#build(Long)}. A public+published post resolves to a document that is
 * upserted; a draft / private / soft-deleted post resolves to {@link java.util.Optional#empty()} and the
 * stale index entry (if any) is deleted. This keeps the index an accurate read model even when events
 * arrive out of order or are redelivered.
 *
 * <p><b>Error routing (manual ack):</b> every outcome acknowledges the offset so a poison message can
 * never block the partition.
 * <ul>
 *   <li><b>Malformed JSON</b> (unparseable, or no {@code postId} field) &rarr; the message is
 *       irrecoverably bad; routed to {@code content-dlq}.</li>
 *   <li><b>Builder failure</b> (e.g. OSS body read failed transiently) &rarr; the message is
 *       recoverable; routed to {@code content-retry} for a backoff-and-retry pass.</li>
 *   <li><b>Success</b> &rarr; upsert or delete applied in place.</li>
 * </ul>
 *
 * <p><b>Profile:</b> {@code @Profile("!test & !integration")} mirrors the other Kafka listeners —
 * neither the unit nor the integration profile wires a live broker, so the listener must not start.
 */
@Component
@Profile("!test & !integration")
public class SearchContentEventConsumer {

    private final ObjectMapper objectMapper;
    private final SearchPostDocumentBuilder builder;
    private final SearchPostIndexRepository indexRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SearchProperties properties;

    public SearchContentEventConsumer(ObjectMapper objectMapper,
                                      SearchPostDocumentBuilder builder,
                                      SearchPostIndexRepository indexRepository,
                                      KafkaTemplate<String, String> kafkaTemplate,
                                      SearchProperties properties) {
        this.objectMapper = objectMapper;
        this.builder = builder;
        this.indexRepository = indexRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${platform.content.kafka.events-topic}",
            groupId = "${platform.search.kafka.content-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onContentEvent(String value, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.hasNonNull("postId")) {
                // No postId ⇒ we cannot even identify the aggregate. This is irrecoverable.
                throw new IllegalArgumentException("content event missing postId");
            }
            long postId = node.path("postId").asLong();
            // Re-check the source of truth and route by current visibility/status. The event type is
            // deliberately ignored: build() returns empty for any non-public-published state, which
            // covers publish / unpublish / visibility-change / delete uniformly.
            builder.build(postId).ifPresentOrElse(indexRepository::upsert, () -> indexRepository.delete(postId));
        } catch (JsonProcessingException badJson) {
            // Malformed JSON — no amount of retrying will make it parseable. Route to DLQ.
            kafkaTemplate.send(properties.kafka().contentDlqTopic(), value);
        } catch (IllegalArgumentException badMessage) {
            // Structurally-valid JSON but no postId — we cannot identify the aggregate to rebuild.
            // Also irrecoverable; route to DLQ.
            kafkaTemplate.send(properties.kafka().contentDlqTopic(), value);
        } catch (Exception recoverable) {
            // Builder failure (e.g. OSS read) or index write failure — retry with backoff.
            kafkaTemplate.send(properties.kafka().contentRetryTopic(), value);
        } finally {
            // Manual ack: a poison message must never block the partition. DLQ/retry is the escape hatch.
            ack.acknowledge();
        }
    }
}
