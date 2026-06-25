package com.platform.content.event;

import java.time.LocalDateTime;

public record ContentOutboxEvent(
        Long id,
        String eventId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        int payloadVersion,
        String payloadJson,
        long sourceVersion,
        LocalDateTime occurredAt,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {

    public static ContentOutboxEvent postLifecycle(
            String eventId,
            String eventType,
            Long postId,
            Long authorId,
            String status,
            String visibility,
            long sourceVersion,
            LocalDateTime occurredAt) {
        String payload = """
                {"eventId":"%s","eventType":"%s","postId":%d,"authorId":%d,"status":"%s","visibility":"%s","sourceVersion":%d,"occurredAt":"%s"}\
                """.formatted(eventId, eventType, postId, authorId, status, visibility, sourceVersion, occurredAt);
        return new ContentOutboxEvent(
                null,
                eventId,
                "POST",
                postId,
                eventType,
                1,
                payload,
                sourceVersion,
                occurredAt,
                null,
                null);
    }

    public String kafkaKey() {
        return aggregateType + ":" + aggregateId;
    }
}
