package com.platform.counter.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.counter.repository.CounterConsumedEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link MysqlCounterConsumedEventRepository} against the real MySQL schema
 * (table {@code counter_consumed_event}, V3 migration). Mirrors the boot pattern of
 * {@code MysqlRelationRepositoryIntegrationTest}: {@code @SpringBootTest @ActiveProfiles("integration")}
 * with {@code @Transactional} rollback for cleanup.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class MysqlCounterConsumedEventRepositoryIntegrationTest {

    @Autowired
    private CounterConsumedEventRepository repository;

    @Test
    void markConsumedReturnsTrueThenFalse() {
        // Unique per run so the test is robust even if rollback is ever disabled.
        String eventId = "evt-" + UUID.randomUUID();
        String groupA = "group-A";
        String groupB = "group-B";

        // First consumer in group-A wins.
        assertThat(repository.markConsumed(eventId, groupA)).isTrue();
        // Same (eventId, group-A) is a duplicate — idempotent dedup, first consumer wins.
        assertThat(repository.markConsumed(eventId, groupA)).isFalse();
        // A different consumer group is NOT deduped against group-A — UK is (event_id, consumer_group).
        assertThat(repository.markConsumed(eventId, groupB)).isTrue();
    }
}
