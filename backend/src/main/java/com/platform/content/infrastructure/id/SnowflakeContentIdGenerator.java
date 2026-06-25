package com.platform.content.infrastructure.id;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.content.config.ContentIdProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Snowflake-style ID generator used to mint {@code content_post.id}.
 *
 * <p>Bit layout (64 bits total):
 * <pre>
 *   1 sign bit | 41 timestamp bits | 5 datacenter bits | 5 worker bits | 12 sequence bits
 * </pre>
 * The id is {@code (timestamp << 22) | (datacenterId << 17) | (workerId << 12) | sequence},
 * where {@code timestamp} is milliseconds since the custom epoch {@code 2026-01-01T00:00:00Z}.
 */
@Component
public class SnowflakeContentIdGenerator implements ContentIdGenerator {

    /** Custom epoch: 2026-01-01T00:00:00Z. */
    private static final long CUSTOM_EPOCH_MILLIS =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;        // 31
    private static final long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1; // 31
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;          // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                    // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    /** Bounded wait for the next millisecond — give up after this many millis to avoid an infinite spin. */
    private static final long MAX_WAIT_NEXT_MILLIS = 5_000L;

    /**
     * Small backward-clock tolerance. NTP step adjustments and GC pauses routinely make
     * {@code System.currentTimeMillis()} report a reading a few ms BEHIND a previously observed
     * value on an otherwise healthy clock. We tolerate up to this many ms of backwards drift by
     * continuing at {@code lastTimestamp} rather than hard-failing, which would otherwise surface
     * as spurious 500s on a primary-key generator.
     */
    private static final long MAX_CLOCK_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeContentIdGenerator(ContentIdProperties properties) {
        long worker = properties.workerId();
        long datacenter = properties.datacenterId();
        if (worker < 0 || worker > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + ", but was " + worker);
        }
        if (datacenter < 0 || datacenter > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId must be between 0 and " + MAX_DATACENTER_ID + ", but was " + datacenter);
        }
        this.workerId = worker;
        this.datacenterId = datacenter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method is {@code synchronized}, and when sequence exhaustion occurs within a single
     * millisecond the {@code waitForNextMillis} wait happens INSIDE the lock. That means concurrent
     * callers block briefly until the next ms tick. At the configured 12 sequence bits this only
     * triggers at 4096 ids/ms/worker (~4M ids/sec); real content-post creation throughput is far
     * below that threshold, so the brief contention is an accepted trade-off for correctness.
     */
    @Override
    public synchronized long nextId() {
        long currentTimestamp = timestamp();

        if (currentTimestamp < lastTimestamp) {
            long backward = lastTimestamp - currentTimestamp;
            if (backward <= MAX_CLOCK_BACKWARD_MS) {
                // tolerate small NTP/GC jitter: continue as if still at lastTimestamp
                // so sequence bookkeeping never goes backwards
                currentTimestamp = lastTimestamp;
            } else {
                throw new PlatformException(
                        ErrorCode.COMMON_INTERNAL_ERROR,
                        "Clock moved backwards by " + backward + "ms");
            }
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // Sequence exhausted in this millisecond — block until the next one.
                currentTimestamp = waitForNextMillis(currentTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitForNextMillis(long lastTimestampParam) {
        long currentTimestamp = timestamp();
        long waited = 0L;
        while (currentTimestamp <= lastTimestampParam) {
            if (waited >= MAX_WAIT_NEXT_MILLIS) {
                throw new PlatformException(
                        ErrorCode.COMMON_INTERNAL_ERROR,
                        "Snowflake wait-for-next-millis exceeded " + MAX_WAIT_NEXT_MILLIS + "ms");
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlatformException(
                        ErrorCode.COMMON_INTERNAL_ERROR,
                        "Interrupted while waiting for next snowflake millisecond");
            }
            currentTimestamp = timestamp();
            waited++;
        }
        return currentTimestamp;
    }

    private long timestamp() {
        return System.currentTimeMillis();
    }
}
