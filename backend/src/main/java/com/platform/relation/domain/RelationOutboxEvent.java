package com.platform.relation.domain;

import java.time.LocalDateTime;

/**
 * A row from {@code relation_outbox_event}: the transactional outbox for relationship domain
 * events. The command service inserts a row in the same transaction as the follow mutation; the
 * relay publishes it to Kafka and the follower projector consumes it.
 *
 * <p>{@code aggregateId} format: {@code FOLLOW:{followerId}:{followingId}} (built by the command
 * service). {@code payloadJson} is the serialized {@code RelationEventPayload}.
 *
 * @param id            row id
 * @param eventId       client/event-unique id (used as the Kafka message key prefix and for idempotency)
 * @param aggregateType always "FOLLOW" for this domain
 * @param aggregateId   FOLLOW:{followerId}:{followingId}
 * @param eventType     the {@link RelationEventType}
 * @param followerId    the actor / fan
 * @param followingId   the target being followed
 * @param payloadJson   serialized event payload
 * @param occurredAt    when the event logically occurred (inside the tx)
 * @param createdAt     row insertion timestamp
 */
public record RelationOutboxEvent(
        Long id,
        String eventId,
        String aggregateType,
        String aggregateId,
        RelationEventType eventType,
        Long followerId,
        Long followingId,
        String payloadJson,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {}
