package com.platform.counter.infrastructure.persistence;

import com.platform.counter.event.CounterSnapshotEvent;
import com.platform.counter.repository.CounterSnapshotOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * MySQL adapter for {@code counter_snapshot_outbox}. The payload JSON is serialized here (flat object
 * of entity coordinates + the five counter values + occurredAt) so the relay forwards it verbatim —
 * the Search consumer deserializes it back into the same {@link CounterSnapshotEvent} shape.
 */
@Repository
@Profile("!test")
public class MysqlCounterSnapshotOutboxRepository implements CounterSnapshotOutboxRepository {

    private final CounterSnapshotOutboxMapper mapper;

    public MysqlCounterSnapshotOutboxRepository(CounterSnapshotOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(CounterSnapshotEvent event) {
        mapper.insert(event.eventId(), event.entityType(), event.entityId(), payloadJson(event));
    }

    @Override
    public List<OutboxRow> findUnpublished(int limit) {
        return mapper.findUnpublished(limit);
    }

    @Override
    public void markPublished(Long id, LocalDateTime publishedAt) {
        mapper.markPublished(id, publishedAt);
    }

    /**
     * Serializes the snapshot to the flat payload JSON the Search consumer expects. Only ids, enum
     * names, longs, and a timestamp appear, so string formatting is safe — no string escaping is
     * needed (no free-text fields). Switch to ObjectMapper if user-visible text is ever added.
     */
    private static String payloadJson(CounterSnapshotEvent e) {
        return """
                {"eventId":"%s","entityType":"%s","entityId":%d,"likeCount":%d,"favoriteCount":%d,"viewCount":%d,"commentCount":%d,"shareCount":%d,"occurredAt":"%s"}\
                """.formatted(e.eventId(), e.entityType(), e.entityId(),
                e.likeCount(), e.favoriteCount(), e.viewCount(), e.commentCount(), e.shareCount(),
                e.occurredAt());
    }
}
