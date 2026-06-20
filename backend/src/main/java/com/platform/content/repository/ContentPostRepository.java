package com.platform.content.repository;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContentPostRepository {

    ContentPost saveDraft(ContentPost post, ContentPostBody body);

    Optional<ContentPost> findPostById(Long postId);

    Optional<ContentPost> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId);

    Optional<ContentPostBody> findBodyByPostId(Long postId);

    List<ContentPostFile> findFilesByPostId(Long postId);

    List<ContentTag> findTagsByPostId(Long postId);

    void updateBodyUploadUrl(Long postId, String bucket, String objectKey, LocalDateTime expiresAt, PublishStage stage);

    /**
     * Re-issues the body for a re-edit after confirmation: bumps {@code body_version}, rewrites the
     * bucket/objectKey/upload expiry, and clears the confirmation fields (etag/sha256/size/confirmedAt).
     * Does NOT touch the post row's stage/status — the caller advances those separately via
     * {@link #updateStatusAndStage}.
     */
    void reissueBodyForEdit(Long postId, String bucket, String objectKey, LocalDateTime expiresAt);

    void confirmBody(Long postId, String objectKey, String etag, String sha256, long sizeBytes, LocalDateTime confirmedAt);

    void updateMetadata(Long postId, String title, String summary, PostVisibility visibility, String coverObjectKey);

    void replaceFiles(Long postId, List<ContentPostFile> files);

    void replaceTags(Long postId, List<String> tagNames);

    void updateStatusAndStage(Long postId, PostStatus status, PublishStage stage, LocalDateTime publishedAt);

    void softDelete(Long postId);

    List<ContentPost> findPublicPublished(int limit, long offset);

    List<ContentPost> findByAuthor(Long authorId, int limit, long offset);
}
