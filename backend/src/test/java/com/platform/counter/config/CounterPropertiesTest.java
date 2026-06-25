package com.platform.counter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CounterPropertiesTest {

    @Autowired
    private CounterProperties counterProperties;

    @MockBean
    private UserRepository userRepository;

    @Test
    void bindsDefaults() {
        assertThat(counterProperties.kafka().eventsTopic()).isEqualTo("counter-events");
        assertThat(counterProperties.kafka().relationConsumerGroup()).isEqualTo("counter-relation-group");
        assertThat(counterProperties.kafka().contentEventsTopic()).isEqualTo("content-events");
        assertThat(counterProperties.kafka().contentConsumerGroup()).isEqualTo("counter-content-group");
        assertThat(counterProperties.kafka().snapshotTopic()).isEqualTo("counter-snapshot-events");
        assertThat(counterProperties.kafka().snapshotConsumerGroup()).isEqualTo("counter-snapshot-relay-group");
        assertThat(counterProperties.flush().mode()).isEqualTo("adaptive");
        assertThat(counterProperties.flush().minIntervalMs()).isEqualTo(500);
        assertThat(counterProperties.flush().batchSize()).isEqualTo(1000);
    }
}
