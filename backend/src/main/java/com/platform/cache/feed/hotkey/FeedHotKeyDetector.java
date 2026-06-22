package com.platform.cache.feed.hotkey;

import com.platform.cache.feed.config.FeedCacheProperties;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class FeedHotKeyDetector {

    private final FeedCacheProperties.HotKey props;
    private final ConcurrentHashMap<String, HotKeyCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong currentTick = new AtomicLong(0);
    private final AtomicInteger currentSlot = new AtomicInteger(0);

    public FeedHotKeyDetector(FeedCacheProperties properties) {
        this.props = properties.hotKey();
    }

    public void record(String pageKey) {
        if (!props.enabled() || pageKey == null || pageKey.isBlank()) {
            return;
        }
        HotKeyCounter counter = counters.get(pageKey);
        if (counter == null) {
            if (counters.size() >= props.maxTrackedKeys()) {
                return;
            }
            HotKeyCounter created = new HotKeyCounter(props.bucketCount());
            HotKeyCounter existing = counters.putIfAbsent(pageKey, created);
            counter = existing != null ? existing : created;
        }
        counter.record(currentSlot.get(), currentTick.get());
    }

    public int heat(String pageKey) {
        HotKeyCounter counter = counters.get(pageKey);
        return counter == null ? 0 : counter.sum();
    }

    public int ttlFor(String pageKey, int baseTtlSeconds) {
        int ttl;
        if (!props.enabled()) {
            ttl = baseTtlSeconds;
        } else {
            ttl = props.baseTtlSeconds();
            int heat = heat(pageKey);
            if (heat >= props.highThreshold()) {
                ttl += props.highExtraTtlSeconds();
            } else if (heat >= props.lowThreshold()) {
                ttl += props.mediumExtraTtlSeconds();
            }
        }
        return ttl + jitter();
    }

    public void reset(String pageKey) {
        if (pageKey != null) {
            counters.remove(pageKey);
        }
    }

    @Scheduled(fixedRateString = "#{${platform.cache.feed.hot-key.slice-seconds:10} * 1000}")
    public void rotate() {
        if (!props.enabled()) {
            counters.clear();
            return;
        }
        rotateInternal();
    }

    void rotateForTesting() {
        rotateInternal();
    }

    int trackedKeyCountForTesting() {
        return counters.size();
    }

    private void rotateInternal() {
        long tick = currentTick.incrementAndGet();
        int slot = (int) (tick % props.bucketCount());
        currentSlot.set(slot);

        Iterator<Map.Entry<String, HotKeyCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, HotKeyCounter> entry = iterator.next();
            HotKeyCounter counter = entry.getValue();
            counter.clearSlot(slot);
            if (tick - counter.lastAccessTick() >= props.coldThresholdTicks()) {
                iterator.remove();
            }
        }
    }

    private int jitter() {
        if (props.jitterMaxSeconds() <= 0) {
            return 0;
        }
        if (props.jitterMaxSeconds() == props.jitterMinSeconds()) {
            return props.jitterMaxSeconds();
        }
        return ThreadLocalRandom.current().nextInt(
                props.jitterMinSeconds(), props.jitterMaxSeconds() + 1);
    }

    private static final class HotKeyCounter {
        private final int[] counts;
        private long lastAccessTick;

        private HotKeyCounter(int bucketCount) {
            this.counts = new int[bucketCount];
        }

        synchronized void record(int slot, long tick) {
            counts[slot]++;
            lastAccessTick = tick;
        }

        synchronized void clearSlot(int slot) {
            counts[slot] = 0;
        }

        synchronized int sum() {
            int total = 0;
            for (int count : counts) {
                total += count;
            }
            return total;
        }

        synchronized long lastAccessTick() {
            return lastAccessTick;
        }
    }
}
