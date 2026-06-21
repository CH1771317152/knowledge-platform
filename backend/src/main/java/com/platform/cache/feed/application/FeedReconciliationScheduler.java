package com.platform.cache.feed.application;

import com.platform.cache.feed.config.FeedCacheProperties;
import com.platform.cache.feed.domain.FeedPage;
import com.platform.cache.feed.domain.FeedSourceQuery;
import com.platform.cache.feed.infrastructure.redis.FeedRedisKeys;
import com.platform.cache.feed.infrastructure.redis.SkeletonStore;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feed-cache reconciliation scheduler (Task 11). Periodically rebuilds drifted L1 skeletons for the
 * hot public head pages so the cache self-heals without waiting for a TTL expiry or an invalidation
 * event.
 *
 * <p><b>What it does.</b> For each common head page size, it asks {@link FeedSourceQuery} for the
 * fresh id list and compares it to the cached skeleton. If the skeleton is missing or its id list has
 * drifted (a new publish that the invalidation consumer missed, an evicted skeleton, a tombstoned
 * rebuild that left the page stale), it rewrites the skeleton with a fresh L1 TTL. A skeleton whose id
 * list still matches the source is left untouched (no churn, no TTL reset).
 *
 * <p><b>Why only head skeletons.</b> Head pages are the hottest read path and the only ones worth the
 * cost of a periodic re-query. Cursor pages are keyed by their keyset cursor and bounded by their TTL;
 * fragment (L0) reconciliation is unnecessary because {@code FeedReadService} backfills missing /
 * tombstoned fragments lazily on every read. Fragment-count refresh could be layered on later; for v1
 * the skeleton refresh is the main value.
 *
 * <p><b>Failure handling.</b> A per-size failure (source query throws, Redis down) is logged and
 * swallowed so one bad size does not abort the rest of the sweep — a failed sweep degrades to a stale
 * read until the next tick, which is safe and bounded. This mirrors the swallow-and-continue posture
 * of {@code CounterFlushScheduler} and {@code FeedInvalidationConsumer}.
 *
 * <p><b>Profile.</b> {@code @Profile("!test & !integration")} keeps the live {@code @Scheduled} task
 * out of both automated test suites (mirrors {@code CounterFlushScheduler} and
 * {@code FeedInvalidationConsumer}). Unit tests construct the scheduler directly and call
 * {@link #reconcile()} without involving the Spring container.
 */
@Component
@Profile("!test & !integration")
public class FeedReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedReconciliationScheduler.class);

    /**
     * Page sizes whose public head skeletons are reconciled each tick. Matches the controller's
     * common page-size clamps; size 50 is omitted (the head page is rarely read at the max size and
     * the extra query cost is not justified for v1).
     */
    private static final List<Integer> RECONCILE_SIZES = List.of(10, 20);

    private final FeedSourceQuery sourceQuery;
    private final SkeletonStore skeletonStore;
    private final FeedCacheProperties props;

    public FeedReconciliationScheduler(FeedSourceQuery sourceQuery,
                                       SkeletonStore skeletonStore,
                                       FeedCacheProperties props) {
        this.sourceQuery = sourceQuery;
        this.skeletonStore = skeletonStore;
        this.props = props;
    }

    /**
     * Reconcile hot public head skeletons. Runs on a fixed rate driven by
     * {@code platform.cache.feed.reconciliation-interval-ms} (default 30 s). The fixed rate (rather
     * than fixed delay) means the tick is independent of the previous run's duration, so a slow source
     * query does not push later sweeps further out — a stale skeleton is corrected as soon as possible.
     */
    @Scheduled(fixedRateString = "${platform.cache.feed.reconciliation-interval-ms:30000}")
    public void reconcile() {
        for (int size : RECONCILE_SIZES) {
            try {
                reconcilePublicHead(size);
            } catch (RuntimeException e) {
                // A single failed size must not abort the sweep. A missed reconciliation degrades to a
                // stale read until the next tick — safe and bounded.
                log.warn("Feed reconciliation failed for public head size {} (will retry next tick): {}",
                        size, e.getMessage());
            }
        }
    }

    /**
     * Reconcile one public head skeleton. Fetches the fresh id list from source, compares it to the
     * cached skeleton, and rewrites the skeleton only when the ids differ or the skeleton is missing.
     * Distinguishing "drifted" from "missing" is just for log clarity; both paths call
     * {@link SkeletonStore#put(String, FeedPage, int)}.
     */
    private void reconcilePublicHead(int size) {
        String key = FeedRedisKeys.publicHead(size);
        FeedPage fresh = sourceQuery.findPublicFeedHead(size);
        if (fresh == null || fresh.ids() == null || fresh.ids().isEmpty()) {
            // Source reports an empty head page. Leave any cached skeleton to age out via TTL and let
            // the read path install a NULL sentinel on the next read; do not proactively cache an
            // empty skeleton here (reconciliation's job is to fix drifted non-empty pages).
            return;
        }
        Optional<FeedPage> cached = skeletonStore.get(key);
        if (cached.isEmpty()) {
            skeletonStore.put(key, fresh, props.l1().headTtlSeconds());
            log.debug("Feed reconciliation rebuilt missing public head skeleton (size={})", size);
            return;
        }
        if (!cached.get().ids().equals(fresh.ids())) {
            skeletonStore.put(key, fresh, props.l1().headTtlSeconds());
            log.debug("Feed reconciliation rebuilt drifted public head skeleton (size={})", size);
        }
        // else: cached ids match fresh — leave the skeleton untouched (no churn, no TTL reset).
    }
}
