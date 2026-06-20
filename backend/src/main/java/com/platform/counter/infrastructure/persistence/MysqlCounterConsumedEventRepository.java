package com.platform.counter.infrastructure.persistence;

import com.platform.counter.repository.CounterConsumedEventRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * MySQL adapter for {@link CounterConsumedEventRepository}. Mirrors the
 * {@code @Repository @Profile("!test")} discipline used by the relation module's
 * {@code MysqlRelationRepository}: under the {@code test} profile this bean is absent so unit tests
 * that do not wire a real database are unaffected.
 *
 * <p>Idempotent dedup is delegated to the {@code uk_counter_consumed_event (event_id, consumer_group)}
 * unique key — the first consumer to insert wins; any duplicate insert is caught as a
 * {@link DuplicateKeyException} and translated to a {@code false} return.
 */
@Repository
@Profile("!test")
public class MysqlCounterConsumedEventRepository implements CounterConsumedEventRepository {

    private final CounterConsumedEventMapper mapper;

    public MysqlCounterConsumedEventRepository(CounterConsumedEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean markConsumed(String eventId, String consumerGroup) {
        try {
            mapper.insert(eventId, consumerGroup);
            return true;
        } catch (DuplicateKeyException duplicate) {
            // uk_counter_consumed_event (event_id, consumer_group) already present — first consumer
            // wins, this is a duplicate (idempotent) ack.
            return false;
        }
    }
}
