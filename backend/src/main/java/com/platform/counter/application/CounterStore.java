package com.platform.counter.application;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.util.List;
import java.util.Map;

/** Abstraction over the Redis counter store (CountInt blob + bitmap + agg). */
public interface CounterStore {

    long readCount(CounterEntityType etype, Long eid, CounterMetric metric);

    Map<CounterMetric, Long> readCounts(CounterEntityType etype, Long eid);

    boolean hasActed(CounterEntityType etype, Long eid, CounterMetric metric, long userId);

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
