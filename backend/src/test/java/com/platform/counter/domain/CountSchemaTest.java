package com.platform.counter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CountSchemaTest {
    @Test
    void articleOffsets() {
        assertThat(CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.LIKE)).isZero();
        assertThat(CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.SHARE)).isEqualTo(32);
    }
    @Test
    void userOffsets() {
        assertThat(CountSchema.offset(CounterEntityType.USER, CounterMetric.FOLLOWERS)).isEqualTo(8);
        assertThat(CountSchema.offset(CounterEntityType.USER, CounterMetric.POSTS)).isEqualTo(16);
    }
    @Test
    void unknownCombinationThrows() {
        assertThatThrownBy(() -> CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.FOLLOWERS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
