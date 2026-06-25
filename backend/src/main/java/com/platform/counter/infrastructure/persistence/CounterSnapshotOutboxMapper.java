package com.platform.counter.infrastructure.persistence;

import com.platform.counter.repository.CounterSnapshotOutboxRepository.OutboxRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for the {@code counter_snapshot_outbox} table (V5 migration).
 *
 * <p>The table carries the already-serialized payload JSON; the relay forwards it verbatim to Kafka,
 * so the mapper never needs to reconstruct a {@code CounterSnapshotEvent} domain object — it returns
 * lightweight {@link OutboxRow}s holding the id, Kafka key, and payload string.
 */
@Mapper
public interface CounterSnapshotOutboxMapper {

    @Insert("""
            INSERT INTO counter_snapshot_outbox (event_id, entity_type, entity_id, payload_json)
            VALUES (#{eventId}, #{entityType}, #{entityId}, CAST(#{payloadJson} AS JSON))
            """)
    void insert(@Param("eventId") String eventId,
                @Param("entityType") String entityType,
                @Param("entityId") Long entityId,
                @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT id, entity_type, entity_id, payload_json
            FROM counter_snapshot_outbox
            WHERE published_at IS NULL
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "entity_type", javaType = String.class),
            @Arg(column = "entity_id", javaType = Long.class),
            @Arg(column = "payload_json", javaType = String.class)
    })
    List<OutboxRow> findUnpublished(@Param("limit") int limit);

    @Update("UPDATE counter_snapshot_outbox SET published_at = #{publishedAt} "
            + "WHERE id = #{id} AND published_at IS NULL")
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);
}
