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
