package com.platform.content.infrastructure.persistence;

import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlContentOutboxRepository implements ContentOutboxRepository {

    private final ContentOutboxMapper mapper;

    public MysqlContentOutboxRepository(ContentOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(ContentOutboxEvent event) {
        mapper.insert(event);
    }

    @Override
    public List<ContentOutboxEvent> findUnpublished(int limit) {
        return mapper.findUnpublished(limit);
    }

    @Override
    public void markPublished(Long id, LocalDateTime publishedAt) {
        mapper.markPublished(id, publishedAt);
    }

    @Override
    public long currentHighWatermark() {
        return mapper.currentHighWatermark();
    }

    @Override
    public List<ContentOutboxEvent> findAfterId(long afterId, int limit) {
        return mapper.findAfterId(afterId, limit);
    }
}
