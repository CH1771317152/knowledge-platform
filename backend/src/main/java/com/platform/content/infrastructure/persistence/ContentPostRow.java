package com.platform.content.infrastructure.persistence;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import java.time.LocalDateTime;

public class ContentPostRow {
    private Long id;
    private Long authorId;
    private String clientRequestId;
    private String title;
    private String summary;
    private String coverObjectKey;
    private String status;
    private String visibility;
    private String publishStage;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long sourceVersion;

    public static ContentPostRow fromDomain(ContentPost post) {
        ContentPostRow row = new ContentPostRow();
        row.setId(post.id());
        row.setAuthorId(post.authorId());
        row.setClientRequestId(post.clientRequestId());
        row.setTitle(post.title());
        row.setSummary(post.summary());
        row.setCoverObjectKey(post.coverObjectKey());
        row.setStatus(post.status().name());
        row.setVisibility(post.visibility().name());
        row.setPublishStage(post.publishStage().name());
        row.setPublishedAt(post.publishedAt());
        row.setCreatedAt(post.createdAt());
        row.setUpdatedAt(post.updatedAt());
        row.setSourceVersion(post.sourceVersion());
        return row;
    }

    public ContentPost toDomain() {
        return new ContentPost(
                id,
                authorId,
                clientRequestId,
                title,
                summary,
                coverObjectKey,
                status == null ? null : PostStatus.valueOf(status),
                visibility == null ? null : PostVisibility.valueOf(visibility),
                publishStage == null ? null : PublishStage.valueOf(publishStage),
                publishedAt,
                createdAt,
                updatedAt,
                sourceVersion
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCoverObjectKey() {
        return coverObjectKey;
    }

    public void setCoverObjectKey(String coverObjectKey) {
        this.coverObjectKey = coverObjectKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getPublishStage() {
        return publishStage;
    }

    public void setPublishStage(String publishStage) {
        this.publishStage = publishStage;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(long sourceVersion) {
        this.sourceVersion = sourceVersion;
    }
}
