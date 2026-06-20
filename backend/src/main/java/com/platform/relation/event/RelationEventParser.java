package com.platform.relation.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;

/**
 * Shared Canal-flat-message parser for the relation follower consumers. Both
 * {@link RelationFollowerProjectorConsumer} and {@link RelationFollowerRetryConsumer} use this so the
 * parse logic is defined exactly once.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Wrong table ({@code != relation_outbox}) or non-{@code INSERT} type &#8594; returns
 *       {@code null} (the caller acks and skips — the message is legitimately not ours).</li>
 *   <li>Malformed Canal JSON, empty message, no data rows, missing/blank {@code payload_json}, or
 *       a malformed {@link RelationEventPayload} &#8594; throws
 *       {@link PlatformException} with {@link ErrorCode#RELATION_EVENT_INVALID} (the caller routes
 *       to retry / DLQ and acks).</li>
 * </ul>
 */
final class RelationEventParser {

    static final String OUTBOX_TABLE = "relation_outbox";
    static final String INSERT_TYPE = "INSERT";

    private RelationEventParser() {}

    /**
     * Parse a Canal flat-message JSON value into the business event.
     *
     * @return the parsed payload, or {@code null} if the message is for a different table or a
     *         non-INSERT (ignore + ack).
     * @throws PlatformException with {@link ErrorCode#RELATION_EVENT_INVALID} on a malformed or
     *         missing payload (route to retry / DLQ).
     */
    static RelationEventPayload parse(ObjectMapper objectMapper, String value) {
        CanalFlatMessage message;
        try {
            message = objectMapper.readValue(value, CanalFlatMessage.class);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID,
                    "Malformed Canal flat message: " + e.getOriginalMessage());
        }
        if (message == null) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID, "Empty Canal flat message");
        }
        // Wrong table / non-insert → ignore (not a new outbox event).
        if (!OUTBOX_TABLE.equals(message.table())) {
            return null;
        }
        if (message.type() == null || !INSERT_TYPE.equals(message.type())) {
            return null;
        }
        if (message.data() == null || message.data().isEmpty()) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID, "Canal message has no data rows");
        }
        String payloadJson = message.data().get(0).get("payload_json");
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID, "Missing payload_json");
        }
        try {
            return objectMapper.readValue(payloadJson, RelationEventPayload.class);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID,
                    "Malformed payload_json: " + e.getOriginalMessage());
        }
    }
}
