package com.platform.content.dto;

import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary view of a post used in list endpoints (public list + my posts). Excludes the body and
 * files — fetch {@link PostDetailResponse} for those. {@code tags} is the post's tag names.
 *
 * @param postId         the post id
 * @param authorId       the post author's user id
 * @param title          post title (may be null for a freshly-created draft)
 * @param summary        short summary, may be null
 * @param coverObjectKey cover image object key, may be null
 * @param status         lifecycle status (DRAFT / PUBLISHED / DELETED)
 * @param visibility     PUBLIC / PRIVATE
 * @param publishedAt    first-publish timestamp, null if never published
 * @param createdAt      row creation timestamp
 * @param updatedAt      row last-update timestamp
 * @param tags           normalized tag names
 */
public record PostSummaryResponse(
        Long postId,
        Long authorId,
        String title,
        String summary,
        String coverObjectKey,
        PostStatus status,
        PostVisibility visibility,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<String> tags
) {}
