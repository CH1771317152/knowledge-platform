package com.platform.content.infrastructure.persistence;

import com.platform.content.domain.ContentTag;
import java.time.LocalDateTime;

public class ContentTagRow {
    private Long id;
    private String name;
    private LocalDateTime createdAt;

    public static ContentTagRow fromDomain(ContentTag tag) {
        ContentTagRow row = new ContentTagRow();
        row.setId(tag.id());
        row.setName(tag.name());
        row.setCreatedAt(tag.createdAt());
        return row;
    }

    public ContentTag toDomain() {
        return new ContentTag(id, name, createdAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
