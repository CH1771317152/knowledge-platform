package com.platform.content.infrastructure.persistence;

import com.platform.content.event.ContentOutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentOutboxMapper {

    @Insert("""
            INSERT INTO content_outbox (event_id, aggregate_type, aggregate_id, event_type,
                payload_version, payload_json, source_version, occurred_at)
            VALUES (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
                #{payloadVersion}, CAST(#{payloadJson} AS JSON), #{sourceVersion}, #{occurredAt})
            """)
    void insert(ContentOutboxEvent event);

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload_version,
                payload_json, source_version, occurred_at, created_at, published_at
            FROM content_outbox
            WHERE published_at IS NULL
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "aggregate_type", javaType = String.class),
            @Arg(column = "aggregate_id", javaType = Long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "payload_version", javaType = int.class),
            @Arg(column = "payload_json", javaType = String.class),
            @Arg(column = "source_version", javaType = long.class),
            @Arg(column = "occurred_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "published_at", javaType = LocalDateTime.class)
    })
    List<ContentOutboxEvent> findUnpublished(@Param("limit") int limit);

    @Update("UPDATE content_outbox SET published_at = #{publishedAt} WHERE id = #{id} AND published_at IS NULL")
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

    @Select("SELECT COALESCE(MAX(id), 0) FROM content_outbox")
    long currentHighWatermark();

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload_version,
                payload_json, source_version, occurred_at, created_at, published_at
            FROM content_outbox
            WHERE id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "aggregate_type", javaType = String.class),
            @Arg(column = "aggregate_id", javaType = Long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "payload_version", javaType = int.class),
            @Arg(column = "payload_json", javaType = String.class),
            @Arg(column = "source_version", javaType = long.class),
            @Arg(column = "occurred_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "published_at", javaType = LocalDateTime.class)
    })
    List<ContentOutboxEvent> findAfterId(@Param("afterId") long afterId, @Param("limit") int limit);
}
