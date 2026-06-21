package com.platform.counter.application;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Abstraction over the Redis counter store (CountInt blob + bitmap + agg). */
public interface CounterStore {

    long readCount(CounterEntityType etype, Long eid, CounterMetric metric);

    Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid);

    boolean hasActed(CounterEntityType etype, Long eid, CounterMetric metric, long userId);

    /**
     * Batched has-acted lookup for the cache/feed overlay: for each {@code metric} in
     * {@code metrics} and each {@code eid} in {@code eids}, returns whether {@code userId} holds
     * that bit. Result shape: {@code metric -> (eid -> boolean)}.
     *
     * <p>The default implementation issues one {@link #hasActed} call per (eid, metric) pair;
     * the Redis override pipelines {@code GETBIT} across the whole cartesian product to keep this
     * O(1) round-trip regardless of batch size.
     */
    default Map<CounterMetric, Map<Long, Boolean>> hasActedBatch(
            long userId,
            CounterEntityType etype,
            List<Long> eids,
            List<CounterMetric> metrics) {
        Map<CounterMetric, Map<Long, Boolean>> out = new LinkedHashMap<>();
        for (CounterMetric m : metrics) {
            Map<Long, Boolean> perEid = new LinkedHashMap<>();
            for (Long eid : eids) {
                perEid.put(eid, hasActed(etype, eid, m, userId));
            }
            out.put(m, perEid);
        }
        return out;
    }

    /** SETBIT ... 1; returns true iff the bit WAS 0 (a real transition). */
    boolean setBitIfAbsent(CounterEntityType etype, Long eid, CounterMetric metric, long userId);

    /** SETBIT ... 0; returns true iff the bit WAS 1 (a real transition). */
    boolean clearBitIfPresent(CounterEntityType etype, Long eid, CounterMetric metric, long userId);

    /** HINCRBY agg + SADD to the flush-pending set. */
    void addToAggregate(CounterEntityType etype, Long eid, CounterMetric metric, long delta);

    /** Pop up to n pending agg tags (e.g. "ARTICLE:123"). */
    List<String> drainPendingBatch(int n);

    /** Atomically drain the agg key for (etype,eid) into its CountInt blob via flush-drain.lua. */
    void flushOne(CounterEntityType etype, Long eid);

    /** Number of pending agg entries (load signal for adaptive flush). */
    long pendingCount();
}
