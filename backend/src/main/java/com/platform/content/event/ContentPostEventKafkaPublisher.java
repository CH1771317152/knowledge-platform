package com.platform.content.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards each in-process {@link ContentPostEvent} (any {@link ContentPostEventType}) to the
 * {@code content-events} Kafka topic, after the publishing transaction has committed.
 *
 * <p><b>Design (lightweight, best-effort):</b> content mutations are low-frequency, so — unlike
 * relation's outbox+Canal pipeline — we publish directly to Kafka from an
 * {@code AFTER_COMMIT} {@link TransactionalEventListener} rather than going through a
 * content_outbox table + Canal. AFTER_COMMIT guarantees we only emit after the DB write is durable;
 * if the Kafka send then fails, the (already-committed) request is NOT failed — a missed event
 * means downstream aggregates (author {@code posts_count}, feed) may lag until counter
 * reconciliation (TODO) recovers. This is the same best-effort posture the counter→relation
 * reconciliation compensates for on the relation side.
 *
 * <p><b>Envelope:</b> the Kafka value is a plain JSON object (not a Canal flat message — content
 * publishes directly, unlike relation's Canal-wrapped {@code relation_outbox}). The
 * {@code eventType} field ({@link ContentPostEventType#name()}) discriminates the lifecycle
 * transition; consumers parse with Jackson. {@code changes} is included only when non-empty
 * (currently only {@link ContentPostEventType#POST_VISIBILITY_CHANGED}, carrying
 * {@code oldVisibility}/{@code newVisibility}).
 *
 * <p><b>Test gating:</b> {@code @Profile("!test & !integration")} keeps this bean out of BOTH the
 * unit ({@code test}) and integration ({@code integration}) profiles, so neither suite ever
 * attempts a Kafka send. The content→Kafka→counter chain is verified by unit tests on the event
 * emission plus a manual smoke, mirroring how relation's Canal path is kept out of automated tests.
 */
@Component
@Profile("!test & !integration")
public class ContentPostEventKafkaPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String eventsTopic;

    public ContentPostEventKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                          ObjectMapper objectMapper,
                                          @Value("${platform.content.kafka.events-topic}") String eventsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventsTopic = eventsTopic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPostEvent(ContentPostEvent event) {
        try {
            // Use a LinkedHashMap (not Map.of) so the serialized JSON has a stable, readable key
            // order — matches relation's payload-shape discipline even though content is best-effort.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", event.eventId());
            payload.put("eventType", event.eventType().name());
            payload.put("postId", event.postId());
            payload.put("authorId", event.authorId());
            payload.put("occurredAt", event.occurredAt().toString());
            if (!event.changes().isEmpty()) {
                payload.put("changes", event.changes());
            }
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(eventsTopic, "POST:" + event.postId(), json);
        } catch (Exception e) {
            // Best-effort: a missed event means downstream aggregates (posts_count, feed) may lag;
            // counter reconciliation (TODO) recovers. Do not fail the (already-committed) request.
        }
    }
}
