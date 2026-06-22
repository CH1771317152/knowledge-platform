# Feed Hot Key Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This project currently does not use a Git workflow, so commit steps are intentionally omitted.

**Goal:** Add local Feed hot key detection and dynamic Redis TTL calculation for L1 skeletons and L0 fragments.

**Architecture:** A local `FeedHotKeyDetector` tracks only Feed `pageKey` access counts in a global time-sliced sliding window. L1 skeleton TTL is computed from the pageKey heat, and L0 fragment TTL inherits the current pageKey heat when fragments are backfilled.

**Tech Stack:** Java 21, Spring Boot, Caffeine local cache already present, Redis via `StringRedisTemplate`, JUnit 5, Mockito, AssertJ.

---

## File Structure

- Create `backend/src/main/java/com/platform/cache/feed/hotkey/FeedHotKeyDetector.java`  
  Owns local sliding-window counters, rotation, heat calculation, TTL calculation, and reset.

- Create `backend/src/test/java/com/platform/cache/feed/hotkey/FeedHotKeyDetectorTest.java`  
  Pure unit tests for the hot key detector; no Spring context and no Redis.

- Modify `backend/src/main/java/com/platform/cache/feed/config/FeedCacheProperties.java`  
  Add nested `HotKey` config record and validation.

- Modify `backend/src/main/resources/application.yml`  
  Add `platform.cache.feed.hot-key` defaults.

- Modify `backend/src/main/java/com/platform/cache/feed/application/FeedReadService.java`  
  Record pageKey at read entry and compute L0 fragment TTL from current pageKey heat.

- Modify `backend/src/test/java/com/platform/cache/feed/application/FeedReadServiceTest.java`  
  Verify record calls and fragment TTL inheritance.

- Modify `backend/src/main/java/com/platform/cache/feed/infrastructure/redis/SkeletonStore.java`  
  Use `FeedHotKeyDetector.ttlFor(pageKey, ttlSeconds)` before writing L1 skeleton. When hot key detection is enabled, the detector uses `platform.cache.feed.hot-key.base-ttl-seconds` as the dynamic base TTL; when disabled, it falls back to the passed `ttlSeconds`.

- Modify `backend/src/test/java/com/platform/cache/feed/infrastructure/redis/SkeletonStoreIntegrationTest.java`  
  Verify hot pageKey gets longer Redis TTL than cold pageKey.

- Modify `backend/src/main/java/com/platform/cache/feed/event/FeedInvalidationConsumer.java`  
  Reset head pageKey heat when head skeletons are invalidated.

- Modify `backend/src/test/java/com/platform/cache/feed/event/FeedInvalidationConsumerTest.java`  
  Verify reset calls for publish/delete/unpublish/visibility events.

- Modify `backend/docs/modules/cache.md`  
  Document hot key detection in the cache module documentation.

---

## Task 1: Hot Key Configuration

**Files:**
- Modify: `backend/src/main/java/com/platform/cache/feed/config/FeedCacheProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/platform/cache/feed/config/FeedCachePropertiesTest.java`

- [ ] **Step 1: Extend `FeedCacheProperties`**

Add a nested `HotKey` record:

```java
@Validated
@ConfigurationProperties(prefix = "platform.cache.feed")
public record FeedCacheProperties(
        L2 l2, L1 l1, L0 l0,
        double jitterRatio,
        long reconciliationIntervalMs,
        int singleFlightLockWaitMs,
        int singleFlightLockTtlSeconds,
        HotKey hotKey
) {
    public FeedCacheProperties {
        if (hotKey == null) {
            hotKey = HotKey.defaults();
        }
        hotKey.validate();
    }

    public record L2(int headTtlSeconds, int cursorTtlSeconds, int maxSize) {}
    public record L1(int headTtlSeconds, int cursorTtlSeconds) {}
    public record L0(int ttlSeconds) {}

    public record HotKey(
            boolean enabled,
            int windowSeconds,
            int sliceSeconds,
            int maxTrackedKeys,
            double coldThresholdRatio,
            int lowThreshold,
            int highThreshold,
            int baseTtlSeconds,
            int mediumExtraTtlSeconds,
            int highExtraTtlSeconds,
            int jitterMinSeconds,
            int jitterMaxSeconds
    ) {
        public static HotKey defaults() {
            return new HotKey(true, 60, 10, 50_000, 0.5,
                    50, 200, 30, 60, 120, 5, 10);
        }

        public int bucketCount() {
            return windowSeconds / sliceSeconds;
        }

        public int coldThresholdTicks() {
            return Math.max(1, (int) Math.ceil(bucketCount() * coldThresholdRatio));
        }

        private void validate() {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.window-seconds must be > 0");
            }
            if (sliceSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.slice-seconds must be > 0");
            }
            if (windowSeconds % sliceSeconds != 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.window-seconds must be divisible by slice-seconds");
            }
            int buckets = bucketCount();
            if (buckets < 6 || buckets > 12) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key bucket count must be in [6, 12]");
            }
            if (maxTrackedKeys <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.max-tracked-keys must be > 0");
            }
            if (coldThresholdRatio <= 0.0 || coldThresholdRatio > 1.0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.cold-threshold-ratio must be in (0, 1]");
            }
            if (highThreshold <= lowThreshold) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.high-threshold must be greater than low-threshold");
            }
            if (baseTtlSeconds <= 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key.base-ttl-seconds must be > 0");
            }
            if (mediumExtraTtlSeconds < 0 || highExtraTtlSeconds < 0) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key extra TTL seconds must be >= 0");
            }
            if (jitterMinSeconds < 0 || jitterMaxSeconds < jitterMinSeconds) {
                throw new IllegalArgumentException("platform.cache.feed.hot-key jitter range is invalid");
            }
        }
    }
}
```

- [ ] **Step 2: Add defaults to `application.yml`**

Add under `platform.cache.feed`:

```yaml
      hot-key:
        enabled: ${FEED_HOT_KEY_ENABLED:true}
        window-seconds: ${FEED_HOT_KEY_WINDOW_SECONDS:60}
        slice-seconds: ${FEED_HOT_KEY_SLICE_SECONDS:10}
        max-tracked-keys: ${FEED_HOT_KEY_MAX_TRACKED_KEYS:50000}
        cold-threshold-ratio: ${FEED_HOT_KEY_COLD_THRESHOLD_RATIO:0.5}
        low-threshold: ${FEED_HOT_KEY_LOW_THRESHOLD:50}
        high-threshold: ${FEED_HOT_KEY_HIGH_THRESHOLD:200}
        base-ttl-seconds: ${FEED_HOT_KEY_BASE_TTL_SECONDS:30}
        medium-extra-ttl-seconds: ${FEED_HOT_KEY_MEDIUM_EXTRA_TTL_SECONDS:60}
        high-extra-ttl-seconds: ${FEED_HOT_KEY_HIGH_EXTRA_TTL_SECONDS:120}
        jitter-min-seconds: ${FEED_HOT_KEY_JITTER_MIN_SECONDS:5}
        jitter-max-seconds: ${FEED_HOT_KEY_JITTER_MAX_SECONDS:10}
```

- [ ] **Step 3: Extend `FeedCachePropertiesTest`**

Add tests:

```java
@Test
void hotKeyBucketCountAndColdThresholdDerivedFromWindow() {
    FeedCacheProperties.HotKey hotKey = FeedCacheProperties.HotKey.defaults();

    assertThat(hotKey.bucketCount()).isEqualTo(6);
    assertThat(hotKey.coldThresholdTicks()).isEqualTo(3);
}

@Test
void invalidHotKeyBucketCountRejected() {
    assertThatThrownBy(() -> new FeedCacheProperties(
            new FeedCacheProperties.L2(5, 60, 10_000),
            new FeedCacheProperties.L1(4, 120),
            new FeedCacheProperties.L0(300),
            0.3, 30_000L, 200, 10,
            new FeedCacheProperties.HotKey(true, 50, 10, 50_000, 0.5,
                    50, 200, 30, 60, 120, 5, 10)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bucket count");
}
```

- [ ] **Step 4: Update existing direct `FeedCacheProperties` constructors**

Update every test that directly calls `new FeedCacheProperties(...)` to pass `FeedCacheProperties.HotKey.defaults()` as the final argument:

```text
backend/src/test/java/com/platform/cache/feed/application/FeedReadServiceTest.java
backend/src/test/java/com/platform/cache/feed/application/FeedReconciliationSchedulerTest.java
backend/src/test/java/com/platform/cache/feed/application/FeedSingleFlightTest.java
```

Example:

```java
props = new FeedCacheProperties(
        new FeedCacheProperties.L2(5, 60, 10_000),
        new FeedCacheProperties.L1(4, 120),
        new FeedCacheProperties.L0(300),
        0.3, 30_000L, 200, 10,
        FeedCacheProperties.HotKey.defaults());
```

- [ ] **Step 5: Run config tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test' '-Dtest=FeedCachePropertiesTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 2: FeedHotKeyDetector

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/hotkey/FeedHotKeyDetector.java`
- Test: `backend/src/test/java/com/platform/cache/feed/hotkey/FeedHotKeyDetectorTest.java`

- [ ] **Step 1: Write failing detector tests**

Create `FeedHotKeyDetectorTest` with:

```java
package com.platform.cache.feed.hotkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.cache.feed.config.FeedCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedHotKeyDetectorTest {

    private FeedHotKeyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FeedHotKeyDetector(props(
                new FeedCacheProperties.HotKey(true, 60, 10, 3, 0.5,
                        2, 5, 30, 60, 120, 5, 5)));
    }

    @Test
    void recordCreatesCounterAndHeatSumsCurrentSlot() {
        detector.record("feed:public:head:sz20");
        detector.record("feed:public:head:sz20");

        assertThat(detector.heat("feed:public:head:sz20")).isEqualTo(2);
    }

    @Test
    void rotateClearsReusedSlotAndKeepsRecentKey() {
        String key = "feed:public:head:sz20";
        detector.record(key);
        detector.rotateForTesting();
        detector.record(key);

        assertThat(detector.heat(key)).isEqualTo(2);
    }

    @Test
    void coldKeyRemovedAfterColdThresholdTicks() {
        String key = "feed:public:head:sz20";
        detector.record(key);

        detector.rotateForTesting();
        detector.rotateForTesting();
        detector.rotateForTesting();

        assertThat(detector.heat(key)).isZero();
        assertThat(detector.trackedKeyCountForTesting()).isZero();
    }

    @Test
    void maxTrackedKeysIgnoresNewKeys() {
        detector.record("k1");
        detector.record("k2");
        detector.record("k3");
        detector.record("k4");

        assertThat(detector.trackedKeyCountForTesting()).isEqualTo(3);
        assertThat(detector.heat("k4")).isZero();
    }

    @Test
    void ttlForUsesLowMediumHighHeatLevels() {
        String key = "feed:public:head:sz20";

        assertThat(detector.ttlFor(key, 30)).isEqualTo(35);

        detector.record(key);
        detector.record(key);
        assertThat(detector.ttlFor(key, 30)).isEqualTo(95);

        detector.record(key);
        detector.record(key);
        detector.record(key);
        detector.record(key);
        assertThat(detector.ttlFor(key, 30)).isEqualTo(155);
    }

    @Test
    void resetDeletesCounter() {
        String key = "feed:public:head:sz20";
        detector.record(key);

        detector.reset(key);

        assertThat(detector.heat(key)).isZero();
        assertThat(detector.trackedKeyCountForTesting()).isZero();
    }

    @Test
    void disabledDetectorDoesNotTrackAndReturnsBaseTtlWithJitter() {
        FeedHotKeyDetector disabled = new FeedHotKeyDetector(props(
                new FeedCacheProperties.HotKey(false, 60, 10, 3, 0.5,
                        2, 5, 30, 60, 120, 5, 5)));

        disabled.record("k1");

        assertThat(disabled.heat("k1")).isZero();
        assertThat(disabled.ttlFor("k1", 120)).isEqualTo(125);
        assertThat(disabled.trackedKeyCountForTesting()).isZero();
    }

    private static FeedCacheProperties props(FeedCacheProperties.HotKey hotKey) {
        return new FeedCacheProperties(
                new FeedCacheProperties.L2(5, 60, 10_000),
                new FeedCacheProperties.L1(4, 120),
                new FeedCacheProperties.L0(300),
                0.3, 30_000L, 200, 10, hotKey);
    }
}
```

- [ ] **Step 2: Run detector test and verify it fails**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test' '-Dtest=FeedHotKeyDetectorTest'
```

Expected: compile failure because `FeedHotKeyDetector` does not exist.

- [ ] **Step 3: Implement `FeedHotKeyDetector`**

Create:

```java
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
```

- [ ] **Step 4: Run detector test**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test' '-Dtest=FeedHotKeyDetectorTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 3: L1 Skeleton Dynamic TTL

**Files:**
- Modify: `backend/src/main/java/com/platform/cache/feed/infrastructure/redis/SkeletonStore.java`
- Test: `backend/src/test/java/com/platform/cache/feed/infrastructure/redis/SkeletonStoreIntegrationTest.java`

- [ ] **Step 1: Add `FeedHotKeyDetector` dependency**

Modify constructor:

```java
private final FeedHotKeyDetector hotKeyDetector;

public SkeletonStore(StringRedisTemplate template,
                     ObjectMapper objectMapper,
                     FeedCacheProperties props,
                     FeedHotKeyDetector hotKeyDetector) {
    this.template = template;
    this.objectMapper = objectMapper;
    this.props = props;
    this.hotKeyDetector = hotKeyDetector;
}
```

- [ ] **Step 2: Use dynamic TTL in `put`**

Replace:

```java
int ttlWithJitter = applyJitter(ttlSeconds);
```

with:

```java
int ttlWithJitter = hotKeyDetector.ttlFor(pageKey, ttlSeconds);
```

Keep `applyJitter` only if still used elsewhere; otherwise remove it and its `ThreadLocalRandom` import.

- [ ] **Step 3: Update integration test constructor expectations**

In `SkeletonStoreIntegrationTest`, add an assertion that a hot key gets a longer TTL:

```java
@Autowired
private FeedHotKeyDetector hotKeyDetector;

@Test
void hotPageKeyGetsLongerSkeletonTtl() {
    String cold = uniquePageKey();
    String hot = uniquePageKey();

    for (int i = 0; i < 250; i++) {
        hotKeyDetector.record(hot);
    }

    store.put(cold, sample(), props.l1().headTtlSeconds());
    store.put(hot, sample(), props.l1().headTtlSeconds());

    Long coldTtl = template.getExpire(FeedRedisKeys.skeleton(cold));
    Long hotTtl = template.getExpire(FeedRedisKeys.skeleton(hot));

    assertThat(hotTtl).isGreaterThan(coldTtl + 60);
}
```

- [ ] **Step 4: Run SkeletonStore integration tests**

Run:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration' '-Dtest=SkeletonStoreIntegrationTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 4: Read Path Recording and L0 TTL Inheritance

**Files:**
- Modify: `backend/src/main/java/com/platform/cache/feed/application/FeedReadService.java`
- Test: `backend/src/test/java/com/platform/cache/feed/application/FeedReadServiceTest.java`

- [ ] **Step 1: Inject `FeedHotKeyDetector`**

Add field:

```java
private final FeedHotKeyDetector hotKeyDetector;
```

Add constructor parameter after `FeedSingleFlight singleFlight`:

```java
FeedHotKeyDetector hotKeyDetector,
```

Assign:

```java
this.hotKeyDetector = hotKeyDetector;
```

- [ ] **Step 2: Record pageKey in `readPage`**

At the top of `readPage`:

```java
hotKeyDetector.record(pageKey);
```

Place it before L2 read so L2 hits also count as heat.

- [ ] **Step 3: Pass pageKey into missing-fragment backfill**

Change:

```java
fragments = backfillMissing(page.ids(), fragments);
```

to:

```java
fragments = backfillMissing(pageKey, page.ids(), fragments);
```

Change method signature:

```java
private Map<Long, PostFragment> backfillMissing(String pageKey, List<Long> ids,
                                                Map<Long, PostFragment> present)
```

Inside the method replace:

```java
int l0Ttl = props.l0().ttlSeconds();
```

with:

```java
int l0Ttl = hotKeyDetector.ttlFor(pageKey, props.l0().ttlSeconds());
```

- [ ] **Step 4: Update `FeedReadServiceTest` setup**

Create a mock:

```java
private FeedHotKeyDetector hotKeyDetector;
```

Initialize:

```java
hotKeyDetector = mock(FeedHotKeyDetector.class);
lenient().when(hotKeyDetector.ttlFor(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
```

Pass it to the `FeedReadService` constructor.

- [ ] **Step 5: Add record assertion**

Add test:

```java
@Test
void readPageRecordsPageKeyEvenOnL2Hit() {
    PostFragment frag = fragment(1L);
    FeedPageResponse cached = new FeedPageResponse(
            List.of(item(frag)), true, new Cursor(PUB_AT, 1L));
    l2CacheManager.getCache("feed-public-head").put(FeedRedisPageKeys.publicHead(20), cached);

    service.readPublicFeed(null, 20, null);

    verify(hotKeyDetector).record(FeedRedisPageKeys.publicHead(20));
}
```

- [ ] **Step 6: Add L0 TTL inheritance assertion**

In `missingFragmentsBatchBackfilledFromContentCounterUser`, add:

```java
when(hotKeyDetector.ttlFor(FeedRedisPageKeys.publicHead(20), 300)).thenReturn(155);
```

Change assertion:

```java
verify(fragmentStore, times(1)).put(any(PostFragment.class), eq(155));
```

- [ ] **Step 7: Run FeedReadService tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test' '-Dtest=FeedReadServiceTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 5: Invalidation Reset

**Files:**
- Modify: `backend/src/main/java/com/platform/cache/feed/event/FeedInvalidationConsumer.java`
- Test: `backend/src/test/java/com/platform/cache/feed/event/FeedInvalidationConsumerTest.java`

- [ ] **Step 1: Inject `FeedHotKeyDetector`**

Add field:

```java
private final FeedHotKeyDetector hotKeyDetector;
```

Constructor:

```java
public FeedInvalidationConsumer(SkeletonStore skeletonStore,
                                FragmentStore fragmentStore,
                                ObjectMapper objectMapper,
                                FeedHotKeyDetector hotKeyDetector) {
    this.skeletonStore = skeletonStore;
    this.fragmentStore = fragmentStore;
    this.objectMapper = objectMapper;
    this.hotKeyDetector = hotKeyDetector;
    ...
}
```

- [ ] **Step 2: Reset in `deleteHeadPages`**

After:

```java
skeletonStore.delete(key);
```

add:

```java
hotKeyDetector.reset(key);
```

This resets public/user head pageKey heat for synchronous deletes and delayed double-delete. Duplicate reset is harmless.

- [ ] **Step 3: Update tests**

In setup:

```java
private FeedHotKeyDetector hotKeyDetector;
...
hotKeyDetector = mock(FeedHotKeyDetector.class);
consumer = new FeedInvalidationConsumer(skeletonStore, fragmentStore, objectMapper, hotKeyDetector);
```

In `publishedDeletesPublicAndUserHead`, assert:

```java
verify(hotKeyDetector, times(1)).reset(FeedRedisKeys.publicHead(20));
verify(hotKeyDetector, times(1)).reset(FeedRedisKeys.userHead(AUTHOR_ID, 20));
```

In `editedDeletesFragmentOnly`, assert:

```java
verifyNoInteractions(hotKeyDetector);
```

In `visibilityChangedDeletesFragmentAndPublicHead`, assert:

```java
verify(hotKeyDetector, times(1)).reset(FeedRedisKeys.publicHead(20));
verify(hotKeyDetector, never()).reset(FeedRedisKeys.userHead(AUTHOR_ID, 20));
```

- [ ] **Step 4: Run invalidation tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test' '-Dtest=FeedInvalidationConsumerTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 6: L0 Fragment Integration Verification

**Files:**
- Modify: `backend/src/test/java/com/platform/cache/feed/infrastructure/redis/FragmentStoreIntegrationTest.java`

- [ ] **Step 1: Add a FeedReadService-level L0 TTL test if integration fixture supports it**

Prefer testing L0 inheritance through `FeedReadServiceTest` from Task 4 because `FragmentStore` intentionally does not know pageKey.

Keep `FragmentStoreIntegrationTest` focused on:

```text
put(fragment, ttlSeconds) writes the TTL passed by caller
```

Add:

```java
@Test
void putUsesCallerProvidedTtl() {
    long pid = uniquePostId();

    store.put(sample(pid), 155);

    Long ttlSeconds = template.getExpire(FeedRedisKeys.fragment(pid));
    assertThat(ttlSeconds).isPositive();
    assertThat(ttlSeconds).isLessThanOrEqualTo(155L + 5L);
}
```

- [ ] **Step 2: Run FragmentStore integration tests**

Run:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration' '-Dtest=FragmentStoreIntegrationTest'
```

Expected: `BUILD SUCCESS`.

---

## Task 7: Documentation

**Files:**
- Modify: `backend/docs/modules/cache.md`

- [ ] **Step 1: Add hot key detection section**

Add after "防穿透 / 击穿 / 雪崩":

```markdown
## 热 key 探测与动态 TTL

Feed 缓存支持本地热 key 探测，用最近一个滑动窗口内的 pageKey 访问次数动态调整 Redis 写入 TTL。

- 只统计 pageKey，不统计 `frag:post:{postId}`。
- L1 skeleton 使用自己的 pageKey 热度计算 TTL。
- L0 fragment 在回填时继承当前 pageKey 热度。
- 读请求只更新本地热度计数，不刷新 Redis TTL。
- 业务失效时 reset 对应 pageKey；自然 TTL 过期不 reset。

默认窗口为 60 秒、每 10 秒一个时间片。每个 pageKey 维护一个 `int[] counts` 和 `lastAccessTick`。时间片轮转时清空新槽位，并删除连续半个窗口未访问的冷 key。

热度等级默认：

| heat | 等级 | TTL |
| --- | --- | --- |
| `< 50` | 低热度 | `base + jitter` |
| `50-200` | 中热度 | `base + 60s + jitter` |
| `> 200` | 高热度 | `base + 120s + jitter` |

该机制是本 JVM 本地优化，不保证多实例热度一致；它只影响缓存命中率，不影响业务正确性。
```

- [ ] **Step 2: Update TTL table**

In the "三级职责" table, change L1/L0 TTL descriptions:

```text
L1: 动态 TTL，默认 base 30s，根据 pageKey 热度增加 0/60/120s + jitter
L0: 动态 TTL，继承当前 pageKey 热度
```

- [ ] **Step 3: Run docs grep**

Run:

```powershell
rg -n "热 key|hot key|动态 TTL|pageKey 热度" backend/docs/modules/cache.md
```

Expected: new documentation lines are found.

---

## Task 8: Full Verification

**Files:**
- All modified source and tests.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run integration tests**

Run:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Manual Redis TTL smoke**

With MySQL/Redis running, start backend and hit:

```powershell
Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8080/api/feed/public?size=20"
```

Then inspect Redis TTL:

```powershell
docker compose -f backend/deploy/docker-compose.yml exec -T redis redis-cli TTL "skel:feed:public:head:sz20"
```

Expected:

```text
TTL is positive and varies by hotness level after repeated requests.
```

---

## Self-Review

**Spec coverage:**

- Local pageKey-only hot detection -> Task 2 and Task 4.
- L1 dynamic TTL -> Task 3.
- L0 inherits pageKey heat -> Task 4 and Task 6.
- Cold key eviction by tick gap -> Task 2.
- max tracked key guard -> Task 2.
- Business reset -> Task 5.
- Configuration -> Task 1.
- Documentation -> Task 7.
- Verification -> Task 8.

**Placeholder scan:** No placeholder tasks remain. Every task includes target files, expected changes, and verification commands.

**Type consistency:** `FeedHotKeyDetector`, `FeedCacheProperties.HotKey`, `ttlFor`, `record`, `heat`, and `reset` are named consistently across tasks.
