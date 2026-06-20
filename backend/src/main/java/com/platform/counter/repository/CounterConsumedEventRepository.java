package com.platform.counter.repository;

/**
 * Persistence contract for consumer idempotency dedup on counter events.
 *
 * <p>Each consumer group records the events it has consumed; the {@code (event_id, consumer_group)}
 * unique key guarantees first-consumer-wins semantics so a redelivered event is projected only once
 * per group. Mirrors the relation module's
 * {@code com.platform.relation.repository.RelationRepository#markConsumed} contract.
 */
public interface CounterConsumedEventRepository {

    /**
     * Records that {@code consumerGroup} has consumed {@code eventId}.
     *
     * @return {@code true} if this call was the first to record it; {@code false} if it was already
     *         present (idempotent dedup — first consumer wins).
     */
    boolean markConsumed(String eventId, String consumerGroup);
}
