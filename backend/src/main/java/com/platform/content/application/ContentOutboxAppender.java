package com.platform.content.application;

import com.platform.content.domain.ContentPost;
import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.event.ContentPostEventType;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ContentOutboxAppender {

    private final ContentOutboxRepository repository;

    public ContentOutboxAppender(ContentOutboxRepository repository) {
        this.repository = repository;
    }

    public void append(ContentPost post, ContentPostEventType eventType, LocalDateTime occurredAt) {
        repository.append(ContentOutboxEvent.postLifecycle(
                UUID.randomUUID().toString(),
                eventType.name(),
                post.id(),
                post.authorId(),
                post.status().name(),
                post.visibility().name(),
                post.sourceVersion(),
                occurredAt));
    }
}
