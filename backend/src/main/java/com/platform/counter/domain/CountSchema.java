package com.platform.counter.domain;

import java.util.List;
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

    /**
     * The metrics that have a defined offset for {@code type}, in {@link CounterMetric#values()}
     * declaration order. Cached as an immutable list at class load so callers on the hot read/flush
     * path do not re-iterate the enum or rely on exception-as-control-flow.
     */
    private static final Map<CounterEntityType, List<CounterMetric>> METRICS_FOR = buildMetricsFor();

    private CountSchema() {}

    public static int offset(CounterEntityType type, CounterMetric metric) {
        Integer off = OFFSETS.getOrDefault(type, Map.of()).get(metric);
        if (off == null) {
            throw new IllegalArgumentException("no offset for " + type + "/" + metric);
        }
        return off;
    }

    /**
     * Returns the valid metrics for {@code type} (canonical, cached view of {@link #OFFSETS}).
     * Iterates {@link CounterMetric#values()} once at class init in declaration order, so the result
     * is stable and matches the historical exception-filtered iteration in
     * {@code RedisCounterStore.metricsFor}. Empty list for an entity type with no defined metrics.
     */
    public static List<CounterMetric> metricsFor(CounterEntityType type) {
        return METRICS_FOR.getOrDefault(type, List.of());
    }

    private static Map<CounterEntityType, List<CounterMetric>> buildMetricsFor() {
        Map<CounterEntityType, List<CounterMetric>> out = new java.util.EnumMap<>(CounterEntityType.class);
        for (CounterEntityType type : CounterEntityType.values()) {
            Map<CounterMetric, Integer> slots = OFFSETS.getOrDefault(type, Map.of());
            // Preserve CounterMetric.values() declaration order — the order the old
            // exception-as-control-flow loop in RedisCounterStore.metricsFor produced.
            java.util.List<CounterMetric> list = new java.util.ArrayList<>();
            for (CounterMetric m : CounterMetric.values()) {
                if (slots.containsKey(m)) {
                    list.add(m);
                }
            }
            out.put(type, List.copyOf(list));
        }
        return Map.copyOf(out);
    }
}
