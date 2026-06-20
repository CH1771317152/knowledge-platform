package com.platform.relation.event;

import com.platform.relation.domain.RelationEventType;
import java.time.LocalDateTime;

/**
 * The canonical event payload for a relationship domain event. Serialized to JSON as
 * {@code payloadJson} on the {@code RelationOutboxEvent} row and published to Kafka. The follower
 * projector consumes it to maintain the {@code relation_follower} read model.
 *
 * @param eventId       event-unique id (matches {@code RelationOutboxEvent.eventId})
 * @param eventType     the {@link RelationEventType}
 * @param aggregateType always "FOLLOW"
 * @param aggregateId   FOLLOW:{followerId}:{followingId}
 * @param followerId    the actor / fan
 * @param followingId   the target being followed
 * @param occurredAt    when the event logically occurred
 */
public record RelationEventPayload(
        String eventId,
        RelationEventType eventType,
        String aggregateType,
        String aggregateId,
        Long followerId,
        Long followingId,
        LocalDateTime occurredAt
) {}
