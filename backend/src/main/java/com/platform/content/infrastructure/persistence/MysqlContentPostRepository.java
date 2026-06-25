package com.platform.content.infrastructure.persistence;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.repository.ContentPostRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlContentPostRepository implements ContentPostRepository {

    private final ContentPostMapper mapper;

    public MysqlContentPostRepository(ContentPostMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ContentPost saveDraft(ContentPost post, ContentPostBody body) {
        mapper.insertPost(ContentPostRow.fromDomain(post));
        mapper.insertBody(ContentPostBodyRow.fromDomain(body));
        // Read back so the returned aggregate carries DB-generated created_at/updated_at.
        return mapper.findPostById(post.id()).map(ContentPostRow::toDomain).orElseThrow();
    }

    @Override
    public Optional<ContentPost> findPostById(Long postId) {
        return mapper.findPostById(postId).map(ContentPostRow::toDomain);
    }

    @Override
    public Optional<ContentPost> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId) {
        return mapper.findPostByAuthorAndClientRequestId(authorId, clientRequestId)
                .map(ContentPostRow::toDomain);
    }

    @Override
    public Optional<ContentPostBody> findBodyByPostId(Long postId) {
        return mapper.findBodyByPostId(postId).map(ContentPostBodyRow::toDomain);
    }

    @Override
    public List<ContentPostFile> findFilesByPostId(Long postId) {
        return mapper.findFilesByPostId(postId).stream()
                .map(ContentPostFileRow::toDomain)
                .toList();
    }

    @Override
    public List<ContentTag> findTagsByPostId(Long postId) {
        return mapper.findTagsByPostId(postId).stream()
                .map(ContentTagRow::toDomain)
                .toList();
    }

    @Override
    public void updateBodyUploadUrl(Long postId, String bucket, String objectKey, LocalDateTime expiresAt,
                                    PublishStage stage) {
        mapper.updateBodyUploadUrl(postId, bucket, objectKey, expiresAt);
        if (stage != null) {
            mapper.updatePostStage(postId, stage.name());
        }
    }

    @Override
    public void reissueBodyForEdit(Long postId, String bucket, String objectKey, LocalDateTime expiresAt) {
        mapper.reissueBodyForEdit(postId, bucket, objectKey, expiresAt);
    }

    @Override
    public void confirmBody(Long postId, String objectKey, String etag, String sha256, long sizeBytes,
                            LocalDateTime confirmedAt) {
        mapper.confirmBody(postId, objectKey, etag, sha256, sizeBytes, confirmedAt);
    }

    @Override
    public void updateMetadata(Long postId, String title, String summary, PostVisibility visibility,
                               String coverObjectKey) {
        mapper.updateMetadata(postId, title, summary,
                visibility == null ? null : visibility.name(), coverObjectKey);
    }

    @Override
    public void replaceFiles(Long postId, List<ContentPostFile> files) {
        mapper.deleteFiles(postId);
        if (files == null) {
            return;
        }
        for (ContentPostFile file : files) {
            // Pin the row's postId to the owning post so callers can pass files with a null postId.
            ContentPostFile resolved = file.postId() == null ? new ContentPostFile(
                    postId, file.objectKey(), file.usageType(), file.contentType(),
                    file.sizeBytes(), file.sortOrder(), file.createdAt()) : file;
            mapper.insertFile(ContentPostFileRow.fromDomain(resolved));
        }
    }

    @Override
    public void replaceTags(Long postId, List<String> tagNames) {
        // Normalize: trim, lowercase, drop blanks, de-duplicate preserving first-seen order.
        Map<String, String> normalized = new LinkedHashMap<>();
        if (tagNames != null) {
            for (String raw : tagNames) {
                if (raw == null) {
                    continue;
                }
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                normalized.putIfAbsent(lower, lower);
            }
        }

        List<Long> tagIds = new ArrayList<>();
        for (String name : normalized.keySet()) {
            Long tagId = mapper.findTagIdByName(name);
            if (tagId == null) {
                // Two concurrent replaceTags calls supplying a brand-new tag name can both
                // find it absent and both INSERT; the loser violates uk_content_tag_name.
                // Make find-or-create idempotent: on the duplicate, re-resolve by name.
                ContentTagRow row = new ContentTagRow();
                row.setName(name);
                try {
                    mapper.insertTag(row);
                    tagId = row.getId();
                } catch (DuplicateKeyException duplicate) {
                    tagId = mapper.findTagIdByName(name);
                }
            }
            tagIds.add(tagId);
        }

        mapper.deletePostTags(postId);
        for (Long tagId : tagIds) {
            mapper.insertPostTag(postId, tagId);
        }
    }

    @Override
    public void updateStatusAndStage(Long postId, PostStatus status, PublishStage stage,
                                     LocalDateTime publishedAt) {
        mapper.updateStatusAndStage(postId,
                status == null ? null : status.name(),
                stage == null ? null : stage.name(),
                publishedAt);
    }

    @Override
    public void softDelete(Long postId) {
        mapper.softDelete(postId);
    }

    @Override
    public void bumpSourceVersion(Long postId) {
        mapper.bumpSourceVersion(postId);
    }

    @Override
    public List<ContentPost> findPublicPublished(int limit, long offset) {
        return mapper.findPublicPublished(limit, offset).stream()
                .map(ContentPostRow::toDomain)
                .toList();
    }

    @Override
    public List<ContentPost> findByAuthor(Long authorId, int limit, long offset) {
        return mapper.findByAuthor(authorId, limit, offset).stream()
                .map(ContentPostRow::toDomain)
                .toList();
    }

    @Override
    public List<ContentPost> findPublicPublishedAfterId(Long afterId, int limit) {
        return mapper.findPublicPublishedAfterId(afterId, limit).stream()
                .map(ContentPostRow::toDomain)
                .toList();
    }
}
