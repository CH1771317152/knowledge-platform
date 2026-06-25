package com.platform.counter.event;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.dto.ArticleCountersResponse;
import java.time.LocalDateTime;

/**
 * A point-in-time snapshot of an entity's five interaction counters, carried on the
 * {@code counter-snapshot-events} topic to drive Search's ES partial updates.
 *
 * <p>The snapshot is produced by {@code CounterFlushScheduler} immediately AFTER a successful
 * {@code flushOne(ARTICLE, eid)} — so the numbers reflect the just-persisted CountInt state, not the
 * in-flight agg deltas. It is the reliable, idempotent channel for ranking signals: Search overlays
 * these values verbatim onto its documents (overwrite, never increment), and a duplicate snapshot is
 * harmless because the same value is written twice.
 *
 * <p>The payload JSON is a flat object of {@code eventType}/{@code entityId}/{@code *Count}/{@code occurredAt}.
 * The Kafka key is {@code ARTICLE:{postId}} so all snapshots for one article land in the same partition
 * (and thus apply in order).
 */
public record CounterSnapshotEvent(
        String eventId,
        String entityType,
        Long entityId,
        long likeCount,
        long favoriteCount,
        long viewCount,
        long commentCount,
        long shareCount,
        LocalDateTime occurredAt) {

    /** Factory for the only snapshot type currently produced: an ARTICLE counter snapshot. */
    public static CounterSnapshotEvent article(String eventId, ArticleCountersResponse counters, LocalDateTime at) {
        return new CounterSnapshotEvent(
                eventId,
                CounterEntityType.ARTICLE.name(),
                counters.postId(),
                counters.like(),
                counters.fav(),
                counters.view(),
                counters.comment(),
                counters.share(),
                at);
    }

    /** Kafka partitioning key — keeps one entity's snapshots ordered within a partition. */
    public String kafkaKey() {
        return entityType + ":" + entityId;
    }
}
