package com.platform.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.event.ContentPostEventType;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentOutboxAppenderTest {

    @Test
    void appendsNakedJsonEventWithCurrentPostState() {
        FakeOutboxRepository repo = new FakeOutboxRepository();
        ContentOutboxAppender appender = new ContentOutboxAppender(repo);
        ContentPost post = new ContentPost(
                10L, 2L, "req", "title", "summary", null,
                PostStatus.PUBLISHED, PostVisibility.PUBLIC, PublishStage.PUBLISHED,
                LocalDateTime.parse("2026-06-25T12:00:00"),
                LocalDateTime.parse("2026-06-25T11:00:00"),
                LocalDateTime.parse("2026-06-25T12:00:00"),
                4L);

        appender.append(post, ContentPostEventType.POST_PUBLISHED, LocalDateTime.parse("2026-06-25T12:00:01"));

        assertThat(repo.events).hasSize(1);
        ContentOutboxEvent event = repo.events.get(0);
        assertThat(event.aggregateId()).isEqualTo(10L);
        assertThat(event.eventType()).isEqualTo("POST_PUBLISHED");
        assertThat(event.sourceVersion()).isEqualTo(4L);
        assertThat(event.payloadJson()).contains("\"visibility\":\"PUBLIC\"");
    }

    static class FakeOutboxRepository implements ContentOutboxRepository {
        final List<ContentOutboxEvent> events = new ArrayList<>();
        public void append(ContentOutboxEvent event) { events.add(event); }
        public List<ContentOutboxEvent> findUnpublished(int limit) { return List.of(); }
        public void markPublished(Long id, LocalDateTime publishedAt) {}
        public long currentHighWatermark() { return 0; }
        public List<ContentOutboxEvent> findAfterId(long afterId, int limit) { return List.of(); }
    }
}
