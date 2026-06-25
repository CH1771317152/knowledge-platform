package com.platform.counter.repository;

import com.platform.counter.event.CounterSnapshotEvent;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reliable outbox for counter snapshots. Mirrors {@code ContentOutboxRepository}: the flush scheduler
 * appends a snapshot row in the same write path as the {@code flushOne} that produced it, and an
 * independent relay drains unpublished rows, publishes each to Kafka, and marks it published.
 *
 * <p>Each unpublished row is returned as a {@link OutboxRow} carrying the row id, the Kafka key
 * ({@code ARTICLE:{postId}}), and the exact payload JSON the relay forwards verbatim. Repeated relay
 * passes are harmless: once a row is marked published it is no longer selected by findUnpublished.
 */
public interface CounterSnapshotOutboxRepository {

    /** Appends a new snapshot row. The payload JSON is derived from the event by the adapter. */
    void append(CounterSnapshotEvent event);

    /** Returns up to {@code limit} unpublished rows, ordered by ascending id. */
    List<OutboxRow> findUnpublished(int limit);

    /** Marks the row published; idempotent (the WHERE clause ignores already-published rows). */
    void markPublished(Long id, LocalDateTime publishedAt);

    /**
     * A pending outbox row as the relay consumes it: the monotonic id (for markPublished), the entity
     * coordinates (which determine the Kafka partition key), and the payload JSON to forward verbatim
     * to {@code counter-snapshot-events}.
     */
    record OutboxRow(Long id, String entityType, Long entityId, String payloadJson) {

        /** Kafka partitioning key — keeps one entity's snapshots ordered within a partition. */
        public String kafkaKey() {
            return entityType + ":" + entityId;
        }
    }
}
