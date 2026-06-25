package com.platform.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SearchPropertiesTest {

    @Test
    void bindsSearchProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("platform.search.enabled", "true");
        source.put("platform.search.index.read-alias", "knowledge-posts-read");
        source.put("platform.search.index.write-alias", "knowledge-posts-write");
        source.put("platform.search.index.body-max-chars", "50000");
        source.put("platform.search.cursor.secret", "test-secret-test-secret");
        source.put("platform.search.cursor.ttl-seconds", "600");
        source.put("platform.search.rank.title-boost", "5.0");
        source.put("platform.search.rank.description-boost", "2.0");
        source.put("platform.search.rank.body-boost", "1.0");
        source.put("platform.search.rank.favorite-weight", "3.0");
        source.put("platform.search.rank.like-weight", "2.0");
        source.put("platform.search.rank.view-weight", "0.2");
        source.put("platform.search.rank.recency-weight", "1.0");

        SearchProperties props = new Binder(source)
                .bind("platform.search", Bindable.of(SearchProperties.class))
                .orElseThrow(() -> new AssertionError("platform.search did not bind"));

        assertThat(props.enabled()).isTrue();
        assertThat(props.index().readAlias()).isEqualTo("knowledge-posts-read");
        assertThat(props.index().writeAlias()).isEqualTo("knowledge-posts-write");
        assertThat(props.index().bodyMaxChars()).isEqualTo(50_000);
        assertThat(props.cursor().ttl()).isEqualTo(Duration.ofSeconds(600));
        assertThat(props.rank().titleBoost()).isEqualTo(5.0);
    }
}
