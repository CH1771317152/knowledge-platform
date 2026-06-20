package com.platform.counter.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for the {@code counter_consumed_event} table. The {@code (event_id, consumer_group)}
 * unique key is enforced by {@code uk_counter_consumed_event} (V3 migration); a duplicate insert
 * surfaces as a {@link org.springframework.dao.DuplicateKeyException} in the calling repository,
 * which translates it into an idempotent {@code false} return.
 */
@Mapper
public interface CounterConsumedEventMapper {

    @Insert("INSERT INTO counter_consumed_event (event_id, consumer_group) "
            + "VALUES (#{eventId}, #{consumerGroup})")
    int insert(@Param("eventId") String eventId, @Param("consumerGroup") String consumerGroup);
}
