package com.platform.counter.infrastructure.redis;

import com.platform.counter.application.CounterStore;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.domain.CountIntCodec;
import com.platform.counter.domain.CountSchema;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

/**
 * Redis-backed implementation of {@link CounterStore}.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code cnt:{etype}:{eid}} — CountInt blob: fixed 8-byte little-endian int64 per metric.</li>
 *   <li>{@code bm:{metric}:{etype}:{eid}:{shard}} — sharded bitmap (32KB = 262144 bits per shard).</li>
 *   <li>{@code agg:{etype}:{eid}} — staging hash for HINCRBY before flush.</li>
 *   <li>{@code counter:flush:pending} — set of pending {@code {etype}:{eid}} tags.</li>
 * </ul>
 *
 * <p>Profile-gated with {@code !test} so unit tests in Tasks 5/6 can substitute a fake
 * {@link CounterStore} without bringing up Redis.
 */
@Repository
@Profile("!test")
public class RedisCounterStore implements CounterStore {

    private static final String INCR_SCRIPT_LOCATION = "redis/counter-incr-at-offset.lua";
    private static final String FLUSH_SCRIPT_LOCATION = "redis/flush-drain.lua";

    /** 32KB per bitmap shard = 262144 bits. */
    private static final long BITS_PER_CHUNK = 262144L;

    private static final String PENDING_SET = "counter:flush:pending";

    private final StringRedisTemplate template;

    private final DefaultRedisScript<Long> incrScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> flushScript = new DefaultRedisScript<>();

    public RedisCounterStore(StringRedisTemplate template) {
        this.template = template;
    }

    @PostConstruct
    void initScripts() {
        // Both scripts `return 1` (a Redis integer reply), so the result type MUST be Long —
        // a String/ValueOutput in Lettuce cannot accept an integer reply and throws
        // UnsupportedOperationException("ValueOutput does not support set(long)").
        incrScript.setResultType(Long.class);
        incrScript.setScriptText(loadScript(INCR_SCRIPT_LOCATION));
        flushScript.setResultType(Long.class);
        flushScript.setScriptText(loadScript(FLUSH_SCRIPT_LOCATION));
    }

    private static String loadScript(String location) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load redis script: " + location, e);
        }
    }

    // ---- key builders ----

    private static String cntKey(CounterEntityType t, Long eid) {
        return "cnt:" + t + ":" + eid;
    }

    private static String bmKey(CounterEntityType t, Long eid, CounterMetric m, long userId) {
        return "bm:" + m.name().toLowerCase() + ":" + t + ":" + eid + ":" + (userId / BITS_PER_CHUNK);
    }

    private static String aggKey(CounterEntityType t, Long eid) {
        return "agg:" + t + ":" + eid;
    }

    private static String aggTag(CounterEntityType t, Long eid) {
        return t + ":" + eid;
    }

    private static long bitIndex(long userId) {
        return userId % BITS_PER_CHUNK;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- CounterStore ----

    @Override
    public long readCount(CounterEntityType etype, Long eid, CounterMetric metric) {
        int off = CountSchema.offset(etype, metric);
        // StringRedisTemplate's ValueOperations interface does not expose GETRANGE, so drop to the
        // connection level. StringRedisTemplate serializes keys/values as ISO-8859-1, so a GETRANGE
        // result byte[] round-trips exactly to the stored binary bytes.
        byte[] keyBytes = utf8(cntKey(etype, eid));
        byte[] raw = template.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().getRange(keyBytes, off, off + 7));
        return CountIntCodec.decodeLong(raw == null ? new byte[0] : raw, 0);
    }

    @Override
    public Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid) {
        // One GETRANGE per metric. The blob is tiny (40 bytes for 5 counters), but per-metric
        // GETRANGE keeps the contract explicit against CountSchema and avoids decoding metrics
        // that have no offset for this etype.
        Map<CounterMetric, Long> out = new LinkedHashMap<>();
        for (CounterMetric m : metricsFor(etype)) {
            out.put(m, readCount(etype, eid, m));
        }
        return out;
    }

    @Override
    public boolean hasActed(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
        Boolean b = template.opsForValue().getBit(bmKey(etype, eid, metric, userId), bitIndex(userId));
        return Boolean.TRUE.equals(b);
    }

    /**
     * Pipelined batched variant of {@link #hasActed}: issues one Redis round-trip containing all
     * {@code eids.size() * metrics.size()} {@code GETBIT} calls. Each (eid, metric) resolves to its
     * own bitmap key ({@code eid} is part of the key), while the bit offset is constant (derived
     * from {@code userId}).
     */
    @Override
    public Map<CounterMetric, Map<Long, Boolean>> hasActedBatch(
            long userId,
            CounterEntityType etype,
            List<Long> eids,
            List<CounterMetric> metrics) {
        long bitOffset = bitIndex(userId);
        int total = eids.size() * metrics.size();
        // (metric, eid) pairs in submission order — metric-major, eid-minor — so we can zip the
        // pipeline results back to their inputs below.
        record Cell(CounterMetric metric, Long eid) {}
        List<Cell> cells = new ArrayList<>(total);
        // Pre-encode keys once per (eid, metric); the bit offset is identical across the batch.
        List<byte[]> keys = new ArrayList<>(total);
        for (CounterMetric m : metrics) {
            for (Long eid : eids) {
                cells.add(new Cell(m, eid));
                keys.add(utf8(bmKey(etype, eid, m, userId)));
            }
        }
        List<Object> raw = template.executePipelined((RedisCallback<Object>) conn -> {
            for (byte[] key : keys) {
                conn.stringCommands().getBit(key, bitOffset);
            }
            return null;
        });
        // executePipelined collects each GETBIT result as a Boolean via the default result converter.
        Map<CounterMetric, Map<Long, Boolean>> out = new LinkedHashMap<>();
        for (CounterMetric m : metrics) {
            out.put(m, new LinkedHashMap<>());
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            Object result = i < raw.size() ? raw.get(i) : null;
            boolean acted = result instanceof Boolean b ? b : Boolean.TRUE.equals(result);
            out.get(cell.metric()).put(cell.eid(), acted);
        }
        return out;
    }

    @Override
    public boolean setBitIfAbsent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
        String key = bmKey(etype, eid, metric, userId);
        // setBit(key, offset, true) sets the bit to 1 and returns the OLD value.
        Boolean old = template.opsForValue().setBit(key, bitIndex(userId), true);
        // Transition: old was 0 -> now 1 (a real new action).
        return !Boolean.TRUE.equals(old);
    }

    @Override
    public boolean clearBitIfPresent(CounterEntityType etype, Long eid, CounterMetric metric, long userId) {
        String key = bmKey(etype, eid, metric, userId);
        // setBit(key, offset, false) emits Redis `SETBIT key offset 0` and returns the old bit.
        Boolean old = template.opsForValue().setBit(key, bitIndex(userId), false);
        // Transition: old was 1 -> now 0 (a real clear).
        return Boolean.TRUE.equals(old);
    }

    @Override
    public void addToAggregate(CounterEntityType etype, Long eid, CounterMetric metric, long delta) {
        template.opsForHash().increment(aggKey(etype, eid), metric.name(), delta);
        template.opsForSet().add(PENDING_SET, aggTag(etype, eid));
    }

    @Override
    public List<String> drainPendingBatch(int n) {
        if (n <= 0) {
            return List.of();
        }
        // SPOP with count exists only at the connection level; the high-level SetOperations
        // interface only exposes single-element pop(key). Drain via connection.stringCommands ->
        // setCommands().sPop(key, count) returns a List<byte[]>.
        byte[] keyBytes = utf8(PENDING_SET);
        List<byte[]> popped = template.execute((RedisCallback<List<byte[]>>) conn ->
                conn.setCommands().sPop(keyBytes, (long) n));
        if (popped == null || popped.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(popped.size());
        for (byte[] b : popped) {
            out.add(new String(b, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override
    public void flushOne(CounterEntityType etype, Long eid) {
        List<String> keys = List.of(aggKey(etype, eid), cntKey(etype, eid));
        List<String> argv = new ArrayList<>();
        for (CounterMetric m : metricsFor(etype)) {
            argv.add(m.name());
            argv.add(Integer.toString(CountSchema.offset(etype, m)));
        }
        template.execute(flushScript, keys, argv.toArray());
    }

    @Override
    public long pendingCount() {
        Long size = template.opsForSet().size(PENDING_SET);
        return size == null ? 0L : size;
    }

    // ---- test-visible hook (exercises counter-incr-at-offset.lua against real Redis) ----

    /**
     * Atomically increment a single CountInt counter at its schema offset. Package-private:
     * intended for the integration test to exercise {@code counter-incr-at-offset.lua} directly.
     * Production flush path goes through {@link #flushOne}.
     */
    void incrAtOffset(CounterEntityType etype, Long eid, CounterMetric metric, long delta) {
        List<String> keys = List.of(cntKey(etype, eid));
        Object[] argv = new Object[] {
                Integer.toString(CountSchema.offset(etype, metric)),
                Long.toString(delta)
        };
        template.execute(incrScript, keys, argv);
    }

    /** The metrics that have a defined offset for this entity type. */
    private static List<CounterMetric> metricsFor(CounterEntityType etype) {
        List<CounterMetric> list = new ArrayList<>();
        for (CounterMetric m : CounterMetric.values()) {
            try {
                CountSchema.offset(etype, m);
                list.add(m);
            } catch (IllegalArgumentException ignored) {
                // metric has no slot for this etype
            }
        }
        return list;
    }
}
