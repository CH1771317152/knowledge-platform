package com.platform.content.infrastructure.persistence;

import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.PostFileUsageType;
import java.time.LocalDateTime;

public class ContentPostFileRow {
    private Long postId;
    private String objectKey;
    private String usageType;
    private String contentType;
    private Long sizeBytes;
    private Integer sortOrder;
    private LocalDateTime createdAt;

    public static ContentPostFileRow fromDomain(ContentPostFile file) {
        ContentPostFileRow row = new ContentPostFileRow();
        row.setPostId(file.postId());
        row.setObjectKey(file.objectKey());
        row.setUsageType(file.usageType().name());
        row.setContentType(file.contentType());
        row.setSizeBytes(file.sizeBytes());
        row.setSortOrder(file.sortOrder());
        row.setCreatedAt(file.createdAt());
        return row;
    }

    public ContentPostFile toDomain() {
        return new ContentPostFile(
                postId,
                objectKey,
                usageType == null ? null : PostFileUsageType.valueOf(usageType),
                contentType,
                sizeBytes,
                sortOrder == null ? 0 : sortOrder,
                createdAt
        );
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getUsageType() {
        return usageType;
    }

    public void setUsageType(String usageType) {
        this.usageType = usageType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
