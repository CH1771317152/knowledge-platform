package com.platform.content.infrastructure.persistence;

import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.PostBodyFormat;
import java.time.LocalDateTime;

public class ContentPostBodyRow {
    private Long postId;
    private String bodyFormat;
    private String bodyBucket;
    private String bodyObjectKey;
    private String bodyEtag;
    private String bodySha256;
    private Long bodySizeBytes;
    private Integer bodyVersion;
    private LocalDateTime uploadUrlExpiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ContentPostBodyRow fromDomain(ContentPostBody body) {
        ContentPostBodyRow row = new ContentPostBodyRow();
        row.setPostId(body.postId());
        row.setBodyFormat(body.bodyFormat().name());
        row.setBodyBucket(body.bodyBucket());
        row.setBodyObjectKey(body.bodyObjectKey());
        row.setBodyEtag(body.bodyEtag());
        row.setBodySha256(body.bodySha256());
        row.setBodySizeBytes(body.bodySizeBytes());
        row.setBodyVersion(body.bodyVersion());
        row.setUploadUrlExpiresAt(body.uploadUrlExpiresAt());
        row.setConfirmedAt(body.confirmedAt());
        row.setCreatedAt(body.createdAt());
        row.setUpdatedAt(body.updatedAt());
        return row;
    }

    public ContentPostBody toDomain() {
        return new ContentPostBody(
                postId,
                bodyFormat == null ? null : PostBodyFormat.valueOf(bodyFormat),
                bodyBucket,
                bodyObjectKey,
                bodyEtag,
                bodySha256,
                bodySizeBytes,
                bodyVersion == null ? 0 : bodyVersion,
                uploadUrlExpiresAt,
                confirmedAt,
                createdAt,
                updatedAt
        );
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public String getBodyFormat() {
        return bodyFormat;
    }

    public void setBodyFormat(String bodyFormat) {
        this.bodyFormat = bodyFormat;
    }

    public String getBodyBucket() {
        return bodyBucket;
    }

    public void setBodyBucket(String bodyBucket) {
        this.bodyBucket = bodyBucket;
    }

    public String getBodyObjectKey() {
        return bodyObjectKey;
    }

    public void setBodyObjectKey(String bodyObjectKey) {
        this.bodyObjectKey = bodyObjectKey;
    }

    public String getBodyEtag() {
        return bodyEtag;
    }

    public void setBodyEtag(String bodyEtag) {
        this.bodyEtag = bodyEtag;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public void setBodySha256(String bodySha256) {
        this.bodySha256 = bodySha256;
    }

    public Long getBodySizeBytes() {
        return bodySizeBytes;
    }

    public void setBodySizeBytes(Long bodySizeBytes) {
        this.bodySizeBytes = bodySizeBytes;
    }

    public Integer getBodyVersion() {
        return bodyVersion;
    }

    public void setBodyVersion(Integer bodyVersion) {
        this.bodyVersion = bodyVersion;
    }

    public LocalDateTime getUploadUrlExpiresAt() {
        return uploadUrlExpiresAt;
    }

    public void setUploadUrlExpiresAt(LocalDateTime uploadUrlExpiresAt) {
        this.uploadUrlExpiresAt = uploadUrlExpiresAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
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
}
