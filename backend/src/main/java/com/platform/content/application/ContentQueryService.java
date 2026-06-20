package com.platform.content.application;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.dto.PostDetailResponse;
import com.platform.content.dto.PostFileResponse;
import com.platform.content.dto.PostPublishingStateResponse;
import com.platform.content.dto.PostSummaryResponse;
import com.platform.content.repository.ContentPostRepository;
import com.platform.storage.application.ObjectStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query/read side of the content module: public + authenticated list endpoints, per-post detail
 * (with body read live from object storage), and the publishing-state recovery endpoint.
 *
 * <p>{@code @Profile("!test")} mirrors {@link ContentCommandService}: it depends on the
 * {@code @Profile("!test")} {@link ContentPostRepository} MySQL impl and the
 * {@link ObjectStorageService} bean (active under integration/non-test), so it must be excluded
 * under the {@code test} profile to keep {@code PlatformApplicationTests.contextLoads} green.
 *
 * <p><b>Permission model for {@link #getPostDetail} (design decision #2):</b> rather than distinguish
 * "exists but forbidden" from "does not exist", every non-readable case for a non-author returns
 * {@link ErrorCode#CONTENT_POST_NOT_FOUND}. This hides the existence of drafts and private posts from
 * anonymous and non-author callers. Only the post's own author may read a DRAFT or a PRIVATE post.
 *
 * <p><b>N+1 tags (design decision #4):</b> the list endpoints issue one {@code findTagsByPostId}
 * query per returned post — acceptable for v1; a batched tag fetch can replace it later.
 */
@Service
@Profile("!test")
public class ContentQueryService {

    /** Upper bound on page size to keep list payloads sane. */
    private static final int MAX_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(ContentQueryService.class);

    private final ContentPostRepository repository;
    private final ObjectStorageService objectStorageService;

    public ContentQueryService(ContentPostRepository repository,
                               ObjectStorageService objectStorageService) {
        this.repository = repository;
        this.objectStorageService = objectStorageService;
    }

    /** Lists published+public posts for the public feed, newest-paged. */
    @Transactional(readOnly = true)
    public List<PostSummaryResponse> listPublicPosts(int page, int size) {
        int[] paging = clampPaging(page, size);
        int limit = paging[0];
        long offset = paging[1];
        List<ContentPost> posts = repository.findPublicPublished(limit, offset);
        return posts.stream().map(this::toSummary).toList();
    }

    /**
     * Lists all non-deleted posts (drafts and published, public and private) for the author,
     * newest-paged. The backing SQL excludes {@link PostStatus#DELETED}.
     */
    @Transactional(readOnly = true)
    public List<PostSummaryResponse> listMyPosts(Long authorId, int page, int size) {
        int[] paging = clampPaging(page, size);
        int limit = paging[0];
        long offset = paging[1];
        List<ContentPost> posts = repository.findByAuthor(authorId, limit, offset);
        return posts.stream().map(this::toSummary).toList();
    }

    /**
     * Returns full detail for a post, reading the body markdown live from object storage.
     *
     * @param requesterIdOrNull the authenticated user's id, or null for an anonymous caller
     * @param postId            the post id
     * @see #canRead(ContentPost, Long) for the permission rules
     */
    @Transactional(readOnly = true)
    public PostDetailResponse getPostDetail(Long requesterIdOrNull, Long postId) {
        ContentPost post = repository.findPostById(postId)
                .orElseThrow(() -> notFound(postId));
        if (post.status() == PostStatus.DELETED) {
            // Do not distinguish deleted from not-found (design decision #2).
            throw notFound(postId);
        }
        if (!canRead(post, requesterIdOrNull)) {
            // Hide existence of drafts/private posts from non-authors → NOT_FOUND.
            throw notFound(postId);
        }

        ContentPostBody body = repository.findBodyByPostId(postId).orElse(null);
        PostSummaryResponse summary = toSummary(post);

        String bodyText = null;
        String bodyObjectKey = body == null ? null : body.bodyObjectKey();
        String bodySha256 = body == null ? null : body.bodySha256();
        if (bodyObjectKey != null) {
            // Read the markdown body fully into a UTF-8 string; null bodyObjectKey (not yet
            // uploaded/confirmed) → body field stays null.
            try {
                bodyText = readString(objectStorageService.readObject(bodyObjectKey));
            } catch (PlatformException e) {
                // A missing body object is a server-side data issue (the body row points at an
                // object that no longer exists). Rather than surface a 400 to an anonymous reader,
                // degrade gracefully: return the detail with body=null while keeping metadata valid.
                // Only the not-found case is swallowed; genuine IO errors still propagate.
                if (e.errorCode() != ErrorCode.STORAGE_OBJECT_NOT_FOUND) {
                    throw e;
                }
                log.warn("Body object {} for post {} is missing; returning degraded detail",
                        bodyObjectKey, postId);
            }
        }

        List<PostFileResponse> files = repository.findFilesByPostId(postId).stream()
                .map(ContentQueryService::toFileResponse)
                .toList();

        return new PostDetailResponse(summary, bodyText, bodyObjectKey, bodySha256, files);
    }

    /**
     * Lightweight internal lookup of a post's author id by id, with no permission gating. Used by
     * cross-module callers (e.g. the counter module's like/fav fan-out) that need only the author
     * and the post's existence — not its body or visibility. A soft-deleted ({@link PostStatus#DELETED})
     * post is reported as absent so callers treat it as "not found".
     *
     * <p>Unlike {@link #getPostDetail} / {@link #getPublishingState} this does NOT enforce read
     * permission or author ownership: the interaction endpoints (like/fav/view) are open to any
     * authenticated user regardless of a post's visibility, and the author id is only used to route
     * the {@code LIKES_RECEIVED}/{@code FAVS_RECEIVED} fan-out event.
     */
    @Transactional(readOnly = true)
    public Optional<Long> findPostAuthorId(Long postId) {
        return repository.findPostById(postId)
                .filter(post -> post.status() != PostStatus.DELETED)
                .map(ContentPost::authorId);
    }

    /**
     * Returns the publishing state of a post for the recovery/query endpoint. Only the post's author
     * may read it; a non-author gets {@link ErrorCode#CONTENT_FORBIDDEN}, an absent post gets
     * {@link ErrorCode#CONTENT_POST_NOT_FOUND}. Shares the builder with {@link ContentCommandService}
     * (design decision #3) so the two paths cannot drift.
     */
    @Transactional(readOnly = true)
    public PostPublishingStateResponse getPublishingState(Long authorId, Long postId) {
        ContentPost post = repository.findPostById(postId)
                .orElseThrow(() -> notFound(postId));
        if (!post.authorId().equals(authorId)) {
            throw new PlatformException(ErrorCode.CONTENT_FORBIDDEN,
                    "Publishing state is only visible to the post author");
        }
        return PublishingStateBuilder.build(post, repository.findBodyByPostId(postId));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Read-permission rules:
     * <ul>
     *   <li>PUBLISHED + PUBLIC → anyone (including anonymous, requesterIdOrNull == null).</li>
     *   <li>otherwise (DRAFT, or PUBLISHED+PRIVATE) → only the author.</li>
     * </ul>
     */
    private static boolean canRead(ContentPost post, Long requesterIdOrNull) {
        if (post.status() == PostStatus.PUBLISHED && post.visibility() == PostVisibility.PUBLIC) {
            return true;
        }
        return requesterIdOrNull != null && requesterIdOrNull.equals(post.authorId());
    }

    private PostSummaryResponse toSummary(ContentPost post) {
        List<String> tags = repository.findTagsByPostId(post.id()).stream()
                .map(ContentTag::name)
                .toList();
        return new PostSummaryResponse(
                post.id(),
                post.authorId(),
                post.title(),
                post.summary(),
                post.coverObjectKey(),
                post.status(),
                post.visibility(),
                post.publishedAt(),
                post.createdAt(),
                post.updatedAt(),
                tags);
    }

    private static PostFileResponse toFileResponse(ContentPostFile file) {
        return new PostFileResponse(
                file.objectKey(), file.usageType(), file.contentType(), file.sizeBytes(), file.sortOrder());
    }

    /**
     * Clamps page/size to a sane range. Returns {@code {limit, offset}}.
     */
    private static int[] clampPaging(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new int[] {safeSize, safePage * safeSize};
    }

    private static String readString(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlatformException(ErrorCode.STORAGE_OBJECT_CHECK_FAILED,
                    "Failed reading body object stream");
        }
    }

    private static PlatformException notFound(Long postId) {
        return new PlatformException(ErrorCode.CONTENT_POST_NOT_FOUND, "Post not found: " + postId);
    }
}
