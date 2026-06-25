package com.platform.content.repository;

import com.platform.content.event.ContentOutboxEvent;
import java.time.LocalDateTime;
import java.util.List;

public interface ContentOutboxRepository {
    void append(ContentOutboxEvent event);
    List<ContentOutboxEvent> findUnpublished(int limit);
    void markPublished(Long id, LocalDateTime publishedAt);
    long currentHighWatermark();
    List<ContentOutboxEvent> findAfterId(long afterId, int limit);
}
