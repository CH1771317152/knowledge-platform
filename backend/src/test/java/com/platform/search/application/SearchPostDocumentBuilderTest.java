package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.ContentTag;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.repository.ContentPostRepository;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.search.domain.SearchPostDocument;
import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SearchPostDocumentBuilderTest {

    @Test
    void buildsDocumentOnlyForPublicPublishedPost() {
        ContentPost post = publicPublishedPost(100L, 8L);
        ContentPostBody body = confirmedBody(100L, "posts/100/body/v1.md");

        SearchPostDocumentBuilder builder = builderFor(post, body,
                key -> new ByteArrayInputStream("# 标题\n正文内容".getBytes(StandardCharsets.UTF_8)));

        SearchPostDocument doc = builder.build(100L).orElseThrow();

        assertThat(doc.postId()).isEqualTo(100L);
        assertThat(doc.contentType()).isEqualTo("ARTICLE");
        assertThat(doc.status()).isEqualTo("PUBLISHED");
        assertThat(doc.visibility()).isEqualTo("PUBLIC");
        assertThat(doc.bodyText()).contains("正文内容");
        assertThat(doc.sourceVersion()).isEqualTo(8L);
        assertThat(doc.tags()).containsExactly("java", "并发");
    }

    @Test
    void returnsEmptyForDraftPost() {
        ContentPost post = new ContentPost(
                100L, 2L, null, "title", "summary", null,
                PostStatus.DRAFT, PostVisibility.PUBLIC, PublishStage.DRAFT_CREATED,
                null, LocalDateTime.now(), LocalDateTime.now(), 1L);
        ContentPostBody body = confirmedBody(100L, "posts/100/body/v1.md");

        SearchPostDocumentBuilder builder = builderFor(post, body,
                key -> new ByteArrayInputStream(new byte[0]));

        assertThat(builder.build(100L)).isEmpty();
    }

    @Test
    void returnsEmptyForPrivateVisibility() {
        ContentPost post = new ContentPost(
                100L, 2L, null, "title", "summary", null,
                PostStatus.PUBLISHED, PostVisibility.PRIVATE, PublishStage.PUBLISHED,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), 1L);
        ContentPostBody body = confirmedBody(100L, "posts/100/body/v1.md");

        SearchPostDocumentBuilder builder = builderFor(post, body,
                key -> new ByteArrayInputStream(new byte[0]));

        assertThat(builder.build(100L)).isEmpty();
    }

    @Test
    void returnsEmptyForMissingPost() {
        // Repository returns empty for findPostById -> builder returns empty, no OSS read attempted.
        StubRepository repo = new StubRepository(null, confirmedBody(100L, "k"));
        SearchPostDocumentBuilder builder = new SearchPostDocumentBuilder(
                repo,
                failingStorage(),
                zeroCounters(),
                null,
                new MarkdownTextExtractor(1000));

        assertThat(builder.build(100L)).isEmpty();
    }

    @Test
    void throwsWhenOssReadFails() {
        ContentPost post = publicPublishedPost(100L, 1L);
        ContentPostBody body = confirmedBody(100L, "posts/100/body/v1.md");

        SearchPostDocumentBuilder builder = builderFor(post, body, key -> {
            throw new RuntimeException("oss down");
        });

        // OSS failure must propagate so the consumer routes to retry/DLQ rather than skipping the index.
        assertThatThrownBy(() -> builder.build(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("posts/100/body/v1.md");
    }

    private static SearchPostDocumentBuilder builderFor(
            ContentPost post, ContentPostBody body, Function<String, InputStream> bodySource) {
        StubRepository repo = new StubRepository(post, body);
        ObjectStorageService storage = new ObjectStorageService() {
            @Override public PresignedUpload presignPut(String objectKey, String contentType, Duration expires) {
                throw new UnsupportedOperationException();
            }
            @Override public StoredObjectMetadata statObject(String objectKey) {
                throw new UnsupportedOperationException();
            }
            @Override public InputStream readObject(String objectKey) {
                return bodySource.apply(objectKey);
            }
        };
        return new SearchPostDocumentBuilder(
                repo,
                storage,
                zeroCounters(),
                (com.platform.user.application.UserQueryService) null,
                new MarkdownTextExtractor(50_000));
    }

    private static ContentPost publicPublishedPost(long id, long sourceVersion) {
        return new ContentPost(
                id, 2L, null, "Java 高并发", "摘要", "cover.jpg",
                PostStatus.PUBLISHED, PostVisibility.PUBLIC, PublishStage.PUBLISHED,
                LocalDateTime.parse("2026-06-25T12:00:00"),
                LocalDateTime.parse("2026-06-25T11:00:00"),
                LocalDateTime.parse("2026-06-25T12:01:00"),
                sourceVersion);
    }

    private static ContentPostBody confirmedBody(long postId, String objectKey) {
        return new ContentPostBody(
                postId, PostBodyFormat.MARKDOWN, "bucket", objectKey,
                "etag", "sha", 100L, 1, null, LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static CounterReadService zeroCounters() {
        // CounterReadService is a concrete class; subclass it for the test, overriding the single
        // method the builder calls. The other methods are unused here.
        return new CounterReadService(null) {
            @Override
            public ArticleCountersResponse getArticleCounters(Long postId) {
                return new ArticleCountersResponse(postId, 0L, 0L, 0L, 0L, 0L);
            }
        };
    }

    private static ObjectStorageService failingStorage() {
        return new ObjectStorageService() {
            @Override public PresignedUpload presignPut(String objectKey, String contentType, Duration expires) {
                throw new UnsupportedOperationException();
            }
            @Override public StoredObjectMetadata statObject(String objectKey) {
                throw new UnsupportedOperationException();
            }
            @Override public InputStream readObject(String objectKey) {
                throw new RuntimeException("oss down");
            }
        };
    }

    /** Minimal ContentPostRepository serving only the three read methods the builder calls. */
    private static final class StubRepository implements ContentPostRepository {
        private final ContentPost post;
        private final ContentPostBody body;

        StubRepository(ContentPost post, ContentPostBody body) {
            this.post = post;
            this.body = body;
        }

        @Override public Optional<ContentPost> findPostById(Long postId) {
            return Optional.ofNullable(post);
        }
        @Override public Optional<ContentPostBody> findBodyByPostId(Long postId) {
            return Optional.ofNullable(body);
        }
        @Override public List<ContentTag> findTagsByPostId(Long postId) {
            return List.of(new ContentTag(1L, "java", null), new ContentTag(2L, "并发", null));
        }

        // ---- unused mutation methods ----
        @Override public ContentPost saveDraft(ContentPost p, ContentPostBody b) { throw new UnsupportedOperationException(); }
        @Override public Optional<ContentPost> findPostByAuthorAndClientRequestId(Long a, String c) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPostFile> findFilesByPostId(Long p) { throw new UnsupportedOperationException(); }
        @Override public void updateBodyUploadUrl(Long p, String b, String k, LocalDateTime e, PublishStage s) { throw new UnsupportedOperationException(); }
        @Override public void reissueBodyForEdit(Long p, String b, String k, LocalDateTime e) { throw new UnsupportedOperationException(); }
        @Override public void confirmBody(Long p, String k, String et, String sh, long s, LocalDateTime a) { throw new UnsupportedOperationException(); }
        @Override public void updateMetadata(Long p, String t, String s, PostVisibility v, String c) { throw new UnsupportedOperationException(); }
        @Override public void replaceFiles(Long p, List<ContentPostFile> f) { throw new UnsupportedOperationException(); }
        @Override public void replaceTags(Long p, List<String> t) { throw new UnsupportedOperationException(); }
        @Override public void updateStatusAndStage(Long p, PostStatus s, PublishStage g, LocalDateTime a) { throw new UnsupportedOperationException(); }
        @Override public void softDelete(Long p) { throw new UnsupportedOperationException(); }
        @Override public void bumpSourceVersion(Long p) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPost> findPublicPublished(int l, long o) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPost> findByAuthor(Long a, int l, long o) { throw new UnsupportedOperationException(); }
        @Override public List<ContentPost> findPublicPublishedAfterId(Long a, int l) { throw new UnsupportedOperationException(); }
    }
}
