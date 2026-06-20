package com.platform.counter.application;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.dto.ArticleCountersResponse;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Read side of the counter module: per-entity counter projections and the "has this user acted"
 * bitmap lookup.
 *
 * <p>{@code @Profile("!test")} mirrors {@link CounterFactService}: it depends on the
 * {@code @Profile("!test")} {@link CounterStore} (Redis impl), so it must be excluded under the
 * {@code test} profile to keep {@code PlatformApplicationTests.contextLoads} green. The unit test
 * constructs it directly with a fake {@code CounterStore}.
 */
@Service
@Profile("!test")
public class CounterReadService {

    private final CounterStore store;

    public CounterReadService(CounterStore store) {
        this.store = store;
    }

    /**
     * Reads the five article interaction counters (like / fav / view / comment / share) for one post.
     * Metrics absent from the underlying {@link CounterStore#readCounts} map default to {@code 0} —
     * an entity that has never been interacted with returns all zeros rather than failing.
     *
     * <p><b>Staleness note:</b> counts reflect the last flush; up to one flush interval
     * ({@code platform.counter.flush.max-interval-ms}) of in-flight deltas may not yet be reflected.
     * This is the accepted eventual-consistency window; periodic reconciliation recalibrates from the
     * fact layer. By contrast, the bitmap-based {@link #hasActed} reads ARE immediate (strong
     * consistency) — they read the live bitmap, not the flushed count blob.
     */
    public ArticleCountersResponse getArticleCounters(Long postId) {
        Map<CounterMetric, Long> counts = store.readCounts(CounterEntityType.ARTICLE, postId);
        return new ArticleCountersResponse(
                postId,
                get(counts, CounterMetric.LIKE),
                get(counts, CounterMetric.FAV),
                get(counts, CounterMetric.VIEW),
                get(counts, CounterMetric.COMMENT),
                get(counts, CounterMetric.SHARE));
    }

    /**
     * Returns whether {@code userId} currently holds the given {@code metric} bit on the entity
     * (e.g. has-liked, has-fav'd). Delegates directly to {@link CounterStore#hasActed}; the bitmap is
     * the single source of truth for "has this user acted on this entity".
     */
    public boolean hasActed(long userId, CounterEntityType etype, Long eid, CounterMetric metric) {
        return store.hasActed(etype, eid, metric, userId);
    }

    private static long get(Map<CounterMetric, Long> counts, CounterMetric metric) {
        Long value = counts.get(metric);
        return value == null ? 0L : value;
    }
}
