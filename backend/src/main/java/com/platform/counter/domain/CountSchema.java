package com.platform.counter.domain;

import java.util.Map;

/** Fixed byte offsets of each counter within a CountInt blob (8-byte little-endian int64 each). */
public final class CountSchema {

    public static final int BYTES_PER_COUNTER = 8;

    private static final Map<CounterEntityType, Map<CounterMetric, Integer>> OFFSETS = Map.of(
            CounterEntityType.ARTICLE, Map.of(
                    CounterMetric.LIKE, 0,
                    CounterMetric.FAV, 8,
                    CounterMetric.VIEW, 16,
                    CounterMetric.COMMENT, 24,
                    CounterMetric.SHARE, 32),
            CounterEntityType.USER, Map.of(
                    CounterMetric.FOLLOWING, 0,
                    CounterMetric.FOLLOWERS, 8,
                    CounterMetric.POSTS, 16,
                    CounterMetric.LIKES_RECEIVED, 24,
                    CounterMetric.FAVS_RECEIVED, 32));

    private CountSchema() {}

    public static int offset(CounterEntityType type, CounterMetric metric) {
        Integer off = OFFSETS.getOrDefault(type, Map.of()).get(metric);
        if (off == null) {
            throw new IllegalArgumentException("no offset for " + type + "/" + metric);
        }
        return off;
    }
}
