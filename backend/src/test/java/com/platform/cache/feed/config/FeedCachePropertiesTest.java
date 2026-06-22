package com.platform.cache.feed.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FeedCachePropertiesTest {

    @Autowired
    FeedCacheProperties props;

    @MockBean
    UserRepository userRepository;

    @Test
    void bindsDefaults() {
        assertThat(props.l2().headTtlSeconds()).isEqualTo(5);
        assertThat(props.l2().cursorTtlSeconds()).isEqualTo(60);
        assertThat(props.l1().cursorTtlSeconds()).isEqualTo(120);
        assertThat(props.l0().ttlSeconds()).isEqualTo(300);
        assertThat(props.jitterRatio()).isEqualTo(0.3);
        assertThat(props.singleFlightLockWaitMs()).isEqualTo(200);
    }

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
}
