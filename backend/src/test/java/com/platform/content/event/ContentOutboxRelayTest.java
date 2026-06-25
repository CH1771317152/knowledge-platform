package com.platform.content.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class ContentOutboxRelayTest {

    @Test
    void publishesUnpublishedEventsAndMarksPublished() {
        ContentOutboxRepository repo = mock(ContentOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ContentOutboxEvent event = new ContentOutboxEvent(
                1L, "evt-1", "POST", 99L, "POST_PUBLISHED", 1,
                "{\"eventId\":\"evt-1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":99}",
                1L, LocalDateTime.now(), LocalDateTime.now(), null);
        when(repo.findUnpublished(100)).thenReturn(List.of(event));

        ContentOutboxRelay relay = new ContentOutboxRelay(repo, kafka, "content-events", 100);
        relay.flushOnce();

        verify(kafka).send("content-events", "POST:99", event.payloadJson());
        verify(repo).markPublished(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }
}
