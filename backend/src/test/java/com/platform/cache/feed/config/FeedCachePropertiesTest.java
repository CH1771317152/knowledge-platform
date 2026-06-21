package com.platform.cache.feed.config;

import static org.assertj.core.api.Assertions.assertThat;
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
}
