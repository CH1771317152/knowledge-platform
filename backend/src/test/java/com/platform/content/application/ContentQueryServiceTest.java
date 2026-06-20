package com.platform.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostFileUsageType;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.dto.PostDetailResponse;
import com.platform.content.dto.PostPublishingStateResponse;
import com.platform.content.dto.PostSummaryResponse;
import com.platform.content.repository.ContentPostRepository;
import com.platform.storage.infrastructure.FakeObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link ContentQueryService}. Constructs the service directly with an in-memory
 * {@link FakeContentPostRepository} and the test {@link FakeObjectStorageService} — mirroring
 * {@link ContentCommandServiceTest} — so it runs under the {@code test} profile where the MySQL/OSS
 * collaborators are absent and {@code PlatformApplicationTests.contextLoads} stays green.
 */
class ContentQueryServiceTest {

    private static final Long AUTHOR = 7L;
    private static final Long OTHER = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 19, 9, 0);

    private FakeContentPostRepository repository;
    private FakeObjectStorageService objectStorage;
    private ContentQueryService service;

    @BeforeEach
    void setUp() {
        repository = new FakeContentPostRepository();
        objectStorage = new FakeObjectStorageService();
        service = new ContentQueryService(repository, objectStorage);
    }

    // --- listPublicPosts ------------------------------------------------------

    @Test
    void publicListReturnsOnlyPublishedPublicPosts() {
        seed(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "Public one", List.of("go"));
        seed(2L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PRIVATE, "Private published", List.of());
        seed(3L, AUTHOR, PostStatus.DRAFT, PostVisibility.PUBLIC, "Draft", List.of());
        seed(4L, OTHER, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "Public two", List.of("java"));

        List<PostSummaryResponse> posts = service.listPublicPosts(0, 20);

        assertThat(posts).extracting(PostSummaryResponse::postId).containsExactlyInAnyOrder(1L, 4L);
        assertThat(posts).allSatisfy(s -> assertThat(s.tags()).isNotNull());
        // Tags per post are loaded (N+1, acceptable for v1).
        assertThat(posts.stream().filter(s -> s.postId() == 1L).findFirst().orElseThrow().tags())
                .containsExactly("go");
    }

    // --- getPostDetail permission matrix --------------------------------------

    @Test
    void authorCanReadDraftDetail() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.DRAFT, PostVisibility.PRIVATE, "# draft body");
        PostDetailResponse detail = service.getPostDetail(AUTHOR, postId);

        assertThat(detail.summary().postId()).isEqualTo(postId);
        assertThat(detail.body()).isEqualTo("# draft body");
    }

    @Test
    void anonymousCannotReadDraftDetail() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.DRAFT, PostVisibility.PRIVATE, "# draft body");

        // Anonymous (null requester) → existence hidden via NOT_FOUND.
        assertThatThrownBy(() -> service.getPostDetail(null, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_POST_NOT_FOUND);
    }

    @Test
    void anonymousCanReadPublishedPublicDetail() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "# public body");

        PostDetailResponse detail = service.getPostDetail(null, postId);

        assertThat(detail.body()).isEqualTo("# public body");
        assertThat(detail.summary().visibility()).isEqualTo(PostVisibility.PUBLIC);
    }

    @Test
    void anonymousCannotReadPublishedPrivateDetail() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PRIVATE, "# private body");

        // Non-author + anonymous → existence hidden via NOT_FOUND (design decision #2).
        assertThatThrownBy(() -> service.getPostDetail(null, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_POST_NOT_FOUND);
    }

    @Test
    void nonAuthorCannotReadPrivateDetail() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PRIVATE, "# private body");

        assertThatThrownBy(() -> service.getPostDetail(OTHER, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_POST_NOT_FOUND);
    }

    @Test
    void deletedPostIsNotReadable() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "# body");
        repository.softDelete(postId);

        // Even the author cannot read a deleted post → NOT_FOUND (do not distinguish from absent).
        assertThatThrownBy(() -> service.getPostDetail(AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_POST_NOT_FOUND);
    }

    // --- getPostDetail: degraded read when body object is missing (I-1) --------

    @Test
    void detailReturnsNullBodyWhenObjectMissing() {
        Long postId = seedDraftWithBody(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "# body");

        // Re-point the body row at a key that was never stored in the fake object storage, so
        // readObject throws STORAGE_OBJECT_NOT_FOUND while the body row (bodyObjectKey non-null)
        // still drives the read attempt.
        String missingKey = "posts/" + postId + "/body/v2.md";
        repository.seedBody(new ContentPostBody(
                postId, PostBodyFormat.MARKDOWN, "fake-bucket", missingKey, "etag-x", "sha-x",
                1L, 2, null, NOW, NOW, NOW));

        PostDetailResponse detail = service.getPostDetail(null, postId);

        // Degraded read: metadata intact, body null, no exception.
        assertThat(detail).isNotNull();
        assertThat(detail.summary().postId()).isEqualTo(postId);
        assertThat(detail.body()).isNull();
    }

    // --- listPublicPosts: pagination clamping (M-5) ---------------------------

    @Test
    void paginationClampsInvalidPageAndSize() {
        seed(1L, AUTHOR, PostStatus.PUBLISHED, PostVisibility.PUBLIC, "Public one", List.of());

        service.listPublicPosts(-1, 100000);

        // size clamped to MAX_PAGE_SIZE (50); page floored to 0 → offset 0.
        assertThat(repository.lastLimit).isEqualTo(50);
        assertThat(repository.lastOffset).isEqualTo(0L);
    }

    // --- getPublishingState ---------------------------------------------------

    @Test
    void publishingStateRequiresAuthor() {
        Long postId = 1L;
        // Seed at DRAFT_CREATED so the canonical next action is REQUEST_BODY_UPLOAD_URL.
        repository.seedPost(new ContentPost(
                postId, AUTHOR, null, "Draft", "summary", null,
                PostStatus.DRAFT, PostVisibility.PRIVATE, PublishStage.DRAFT_CREATED,
                null, NOW, NOW));

        PostPublishingStateResponse state = service.getPublishingState(AUTHOR, postId);
        assertThat(state.postId()).isEqualTo(postId);
        assertThat(state.nextActions()).containsExactly("REQUEST_BODY_UPLOAD_URL");

        // Non-author → FORBIDDEN (distinct from the read-path hiding: this endpoint is explicitly
        // author-scoped and a non-author reaching it has authenticated, so we signal forbidden).
        assertThatThrownBy(() -> service.getPublishingState(OTHER, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_FORBIDDEN);

        // Absent post → NOT_FOUND.
        assertThatThrownBy(() -> service.getPublishingState(AUTHOR, 999L))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_POST_NOT_FOUND);
    }

    // --- fixtures --------------------------------------------------------------

    /**
     * Seeds a post with the given status/visibility and a confirmed body object whose markdown
     * content is stored in the fake object storage under the standard body object key.
     */
    private Long seedDraftWithBody(Long postId, Long authorId, PostStatus status,
                                   PostVisibility visibility, String bodyMarkdown) {
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] bytes = bodyMarkdown.getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, "text/markdown", bytes, "etag-" + postId);

        repository.seedPost(new ContentPost(
                postId, authorId, null, "Title " + postId, "summary", null,
                status, visibility, PublishStage.METADATA_COMPLETED, NOW, NOW, NOW));
        repository.seedBody(new ContentPostBody(
                postId, PostBodyFormat.MARKDOWN, "fake-bucket", objectKey, "etag-" + postId, "sha-" + postId,
                (long) bytes.length, 1, null, NOW, NOW, NOW));
        repository.seedFiles(postId, List.of(new ContentPostFile(
                postId, "users/" + authorId + "/img.png", PostFileUsageType.INLINE_IMAGE,
                "image/png", 10L, 0, NOW)));
        return postId;
    }

    private void seed(Long postId, Long authorId, PostStatus status, PostVisibility visibility,
                      String title, List<String> tags) {
        repository.seedPost(new ContentPost(
                postId, authorId, null, title, "summary", null,
                status, visibility, PublishStage.METADATA_COMPLETED, NOW, NOW, NOW));
        repository.seedTags(postId, tags);
    }

    /**
     * Compact in-memory {@link ContentPostRepository} exposing only the read methods the query
     * service calls plus a handful of seed/softDelete helpers for setup. Mirrors the fake in
     * {@link ContentCommandServiceTest}.
     */
    private static final class FakeContentPostRepository implements ContentPostRepository {
        final Map<Long, ContentPost> posts = new HashMap<>();
        final Map<Long, ContentPostBody> bodies = new HashMap<>();
        final Map<Long, List<ContentPostFile>> filesByPost = new HashMap<>();
        final Map<Long, List<ContentTag>> tagsByPost = new HashMap<>();
        // Captures the last (limit, offset) passed to a list method, so the pagination-clamp test
        // can assert the clamped values actually reached the repository.
        volatile Integer lastLimit;
        volatile Long lastOffset;

        void seedPost(ContentPost post) {
            posts.put(post.id(), post);
            filesByPost.putIfAbsent(post.id(), new ArrayList<>());
            tagsByPost.putIfAbsent(post.id(), new ArrayList<>());
        }

        void seedBody(ContentPostBody body) {
            bodies.put(body.postId(), body);
        }

        void seedFiles(Long postId, List<ContentPostFile> files) {
            filesByPost.put(postId, new ArrayList<>(files));
        }

        void seedTags(Long postId, List<String> tagNames) {
            List<ContentTag> tags = new ArrayList<>();
            long id = 1L;
            for (String raw : tagNames) {
                String lower = raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
                if (lower != null && !lower.isEmpty()) {
                    tags.add(new ContentTag(id++, lower, NOW));
                }
            }
            tagsByPost.put(postId, tags);
        }

        @Override
        public ContentPost saveDraft(ContentPost post, ContentPostBody body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ContentPost> findPostById(Long postId) {
            return Optional.ofNullable(posts.get(postId));
        }

        @Override
        public Optional<ContentPost> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId) {
            return Optional.empty();
        }

        @Override
        public Optional<ContentPostBody> findBodyByPostId(Long postId) {
            return Optional.ofNullable(bodies.get(postId));
        }

        @Override
        public List<ContentPostFile> findFilesByPostId(Long postId) {
            return List.copyOf(filesByPost.getOrDefault(postId, List.of()));
        }

        @Override
        public List<ContentTag> findTagsByPostId(Long postId) {
            return List.copyOf(tagsByPost.getOrDefault(postId, List.of()));
        }

        @Override
        public void updateBodyUploadUrl(Long postId, String bucket, String objectKey,
                                        LocalDateTime expiresAt, PublishStage stage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void confirmBody(Long postId, String objectKey, String etag, String sha256,
                                long sizeBytes, LocalDateTime confirmedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reissueBodyForEdit(Long postId, String bucket, String objectKey,
                                       LocalDateTime expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateMetadata(Long postId, String title, String summary, PostVisibility visibility,
                                   String coverObjectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceFiles(Long postId, List<ContentPostFile> files) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceTags(Long postId, List<String> tagNames) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateStatusAndStage(Long postId, PostStatus status, PublishStage stage,
                                         LocalDateTime publishedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void softDelete(Long postId) {
            ContentPost post = posts.get(postId);
            posts.put(postId, new ContentPost(post.id(), post.authorId(), post.clientRequestId(),
                    post.title(), post.summary(), post.coverObjectKey(), PostStatus.DELETED,
                    post.visibility(), post.publishStage(), post.publishedAt(), post.createdAt(), NOW));
        }

        @Override
        public List<ContentPost> findPublicPublished(int limit, long offset) {
            lastLimit = limit;
            lastOffset = offset;
            return posts.values().stream()
                    .filter(p -> p.status() == PostStatus.PUBLISHED && p.visibility() == PostVisibility.PUBLIC)
                    .toList();
        }

        @Override
        public List<ContentPost> findByAuthor(Long authorId, int limit, long offset) {
            lastLimit = limit;
            lastOffset = offset;
            return posts.values().stream().filter(p -> authorId.equals(p.authorId())).toList();
        }
    }
}
