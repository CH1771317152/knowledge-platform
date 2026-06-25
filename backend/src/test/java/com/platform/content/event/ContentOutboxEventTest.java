package com.platform.content.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ContentOutboxEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadIsNakedJsonContentEvent() throws Exception {
        ContentOutboxEvent event = ContentOutboxEvent.postLifecycle(
                "evt-1",
                "POST_PUBLISHED",
                101L,
                7L,
                "PUBLISHED",
                "PUBLIC",
                3L,
                LocalDateTime.parse("2026-06-25T12:00:00"));

        JsonNode node = objectMapper.readTree(event.payloadJson());

        assertThat(event.aggregateType()).isEqualTo("POST");
        assertThat(event.aggregateId()).isEqualTo(101L);
        assertThat(event.kafkaKey()).isEqualTo("POST:101");
        assertThat(node.path("eventId").asText()).isEqualTo("evt-1");
        assertThat(node.path("eventType").asText()).isEqualTo("POST_PUBLISHED");
        assertThat(node.path("postId").asLong()).isEqualTo(101L);
        assertThat(node.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(node.path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(node.path("sourceVersion").asLong()).isEqualTo(3L);
    }
}
