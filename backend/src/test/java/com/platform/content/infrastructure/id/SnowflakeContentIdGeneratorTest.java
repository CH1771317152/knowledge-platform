package com.platform.content.infrastructure.id;

import com.platform.content.config.ContentIdProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeContentIdGeneratorTest {

    private static final int WORKER_ID = 5;
    private static final int DATACENTER_ID = 3;

    private SnowflakeContentIdGenerator newGenerator() {
        return new SnowflakeContentIdGenerator(
                new ContentIdProperties(WORKER_ID, DATACENTER_ID));
    }

    @Test
    void generatesIncreasingIds() {
        SnowflakeContentIdGenerator generator = newGenerator();

        List<Long> ids = LongStream.range(0, 10_000)
                .map(i -> generator.nextId())
                .boxed()
                .toList();

        assertThat(ids).isSorted();
    }

    @Test
    void generatesUniqueIdsAcrossManyCalls() {
        SnowflakeContentIdGenerator generator = newGenerator();

        List<Long> ids = LongStream.range(0, 10_000)
                .map(i -> generator.nextId())
                .boxed()
                .toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void embedsDatacenterAndWorkerWithinConfiguredBitRange() {
        SnowflakeContentIdGenerator generator = newGenerator();

        long id = generator.nextId();

        long datacenterBits = (id >> 17) & 0x1F;
        long workerBits = (id >> 12) & 0x1F;

        assertThat(datacenterBits).isEqualTo(DATACENTER_ID);
        assertThat(workerBits).isEqualTo(WORKER_ID);
    }

    @Test
    void timestampAdvancesWithEpochAnchor() {
        // Sanity: the highest bits should represent a positive delta from the 2026-01-01 epoch,
        // since the test runs at a real wall-clock after that epoch.
        SnowflakeContentIdGenerator generator = newGenerator();

        long id = generator.nextId();
        long timestampDeltaMillis = id >>> 22;

        assertThat(timestampDeltaMillis).isPositive();
    }

    /*
     * Clock-backwards test: SKIPPED.
     *
     * The clock-backwards branch throws PlatformException(COMMON_INTERNAL_ERROR, ...) and can
     * only be triggered when System.currentTimeMillis() returns a value strictly less than the
     * generator's internal lastTimestamp. nextId() reads the wall clock directly via
     * System.currentTimeMillis(), and the field tracking lastTimestamp is private and not
     * injectable. Testing it deterministically would require either manipulating the real
     * system clock (flaky, slow, environment-dependent) or restructuring the generator to
     * accept a Clock/Supplier dependency solely for testability.
     *
     * The task spec explicitly permits skipping this test ("you may SKIP it ... A simple
     * note/rationale is acceptable if you skip it"), so it is intentionally omitted to avoid
     * over-engineering the production class. The branch is small and reviewable.
     */

    /**
     * Prove the low 12 sequence bits are actually used: multiple IDs minted within the same
     * millisecond must differ in the sequence bits. A generator that IGNORED sequence bits (only
     * used the timestamp) would put every same-ms id at sequence 0 and this test would fail.
     */
    @Test
    void sequenceBitsAreUsedWithinSameMillisecond() {
        SnowflakeContentIdGenerator generator = newGenerator();

        // Generate ~3000 ids in a tight loop; on any realistic machine this spans only a few ms,
        // so multiple ids share a millisecond and must be disambiguated by the sequence bits.
        List<Long> ids = LongStream.range(0, 3_000)
                .map(i -> generator.nextId())
                .boxed()
                .toList();

        // Group ids by their timestamp-bits key (top 42 bits).
        Map<Long, List<Long>> byTimestampKey = new HashMap<>();
        for (long id : ids) {
            long tsKey = id >>> 22;
            byTimestampKey.computeIfAbsent(tsKey, k -> new ArrayList<>()).add(id);
        }

        // Find at least one ms that minted >= 2 ids.
        List<Map.Entry<Long, List<Long>>> groupsWithMultiple = byTimestampKey.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .toList();
        assertThat(groupsWithMultiple)
                .as("Expected at least one millisecond to produce multiple ids (3000 ids should span "
                        + "only a few ms); if this fails, increase the id count")
                .isNotEmpty();

        // For every such group, the low 12 sequence bits must be DISTINCT — proving the generator
        // increments the sequence within a ms rather than ignoring it.
        for (Map.Entry<Long, List<Long>> entry : groupsWithMultiple) {
            List<Long> sequences = entry.getValue().stream()
                    .map(id -> id & 0xFFF)
                    .toList();
            assertThat(sequences)
                    .as("sequence bits must be distinct within timestamp key " + entry.getKey())
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * Constructor range validation: workerId and datacenterId are 5 bits each, so 0..31 are valid
     * and 32 must be rejected. Covers both fields and confirms valid boundary values don't throw.
     */
    @Test
    void constructorRejectsOutOfRangeWorkerAndDatacenter() {
        // Valid boundary values (0 and 31) must NOT throw.
        assertThat(new SnowflakeContentIdGenerator(new ContentIdProperties(0, 0)))
                .isNotNull();
        assertThat(new SnowflakeContentIdGenerator(new ContentIdProperties(31, 31)))
                .isNotNull();

        // workerId out of range (datacenter valid).
        assertThatThrownBy(() -> new SnowflakeContentIdGenerator(new ContentIdProperties(32, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");

        // datacenterId out of range (worker valid).
        assertThatThrownBy(() -> new SnowflakeContentIdGenerator(new ContentIdProperties(1, 32)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datacenterId");
    }
}
