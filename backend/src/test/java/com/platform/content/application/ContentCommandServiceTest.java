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
import com.platform.content.dto.BodyUploadUrlResponse;
import com.platform.content.dto.ConfirmBodyRequest;
import com.platform.content.dto.CreateDraftRequest;
import com.platform.content.dto.PostFileRequest;
import com.platform.content.dto.PostPublishingStateResponse;
import com.platform.content.dto.UpdatePostMetadataRequest;
import com.platform.content.infrastructure.id.ContentIdGenerator;
import com.platform.content.repository.ContentPostRepository;
import com.platform.storage.infrastructure.FakeObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link ContentCommandService}. The service is constructed directly with an
 * in-memory {@link FakeContentPostRepository}, a fake {@link ContentIdGenerator}, and the test
 * {@link FakeObjectStorageService} — mirroring {@code AuthServiceTest} / {@code TokenServiceTest}
 * so the test runs under the {@code test} profile where the MySQL/OSS collaborators are absent and
 * {@code PlatformApplicationTests.contextLoads} stays green.
 */
class ContentCommandServiceTest {

    private static final Long AUTHOR = 7L;
    private static final Long OTHER_AUTHOR = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 19, 9, 0);

    private FakeContentPostRepository repository;
    private SequenceContentIdGenerator idGenerator;
    private FakeObjectStorageService objectStorage;
    private ContentCommandService service;

    @BeforeEach
    void setUp() {
        repository = new FakeContentPostRepository();
        idGenerator = new SequenceContentIdGenerator();
        objectStorage = new FakeObjectStorageService();
        service = new ContentCommandService(repository, idGenerator, objectStorage);
    }

    // --- createDraft ----------------------------------------------------------

    @Test
    void createsDraftWithSnowflakeId() {
        PostPublishingStateResponse state = service.createDraft(AUTHOR, new CreateDraftRequest(null));

        assertThat(state.postId()).isEqualTo(1L);
        assertThat(state.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(state.publishStage()).isEqualTo(PublishStage.DRAFT_CREATED);
        assertThat(state.bodyConfirmed()).isFalse();
        assertThat(state.metadataCompleted()).isFalse();
        assertThat(state.nextActions()).containsExactly("REQUEST_BODY_UPLOAD_URL");

        ContentPost stored = repository.findPostById(1L).orElseThrow();
        assertThat(stored.authorId()).isEqualTo(AUTHOR);
        assertThat(stored.visibility()).isEqualTo(PostVisibility.PRIVATE);

        ContentPostBody body = repository.findBodyByPostId(1L).orElseThrow();
        assertThat(body.bodyFormat()).isEqualTo(PostBodyFormat.MARKDOWN);
        assertThat(body.bodyVersion()).isEqualTo(1);
    }

    @Test
    void createDraftIsIdempotentWithClientRequestId() {
        PostPublishingStateResponse first =
                service.createDraft(AUTHOR, new CreateDraftRequest("client-req-1"));
        PostPublishingStateResponse second =
                service.createDraft(AUTHOR, new CreateDraftRequest("client-req-1"));

        assertThat(second.postId()).isEqualTo(first.postId());
        // Only one post created despite two calls.
        assertThat(idGenerator.issued).hasSize(1);
        assertThat(repository.posts).hasSize(1);
    }

    @Test
    void createDraftReturnsExistingStateEvenWhenDeleted() {
        // Design decision #3: a found-but-DELETED clientRequestId returns the existing state rather
        // than creating a fresh post.
        Long id = service.createDraft(AUTHOR, new CreateDraftRequest("req-deleted")).postId();
        repository.softDelete(id);

        PostPublishingStateResponse state =
                service.createDraft(AUTHOR, new CreateDraftRequest("req-deleted"));

        assertThat(state.postId()).isEqualTo(id);
        assertThat(state.status()).isEqualTo(PostStatus.DELETED);
        assertThat(repository.posts).hasSize(1);
    }

    // --- requestBodyUploadUrl -------------------------------------------------

    @Test
    void repeatedBodyUploadUrlKeepsObjectKey() {
        Long postId = service.createDraft(AUTHOR, new CreateDraftRequest(null)).postId();

        BodyUploadUrlResponse first = service.requestBodyUploadUrl(AUTHOR, postId);
        BodyUploadUrlResponse second = service.requestBodyUploadUrl(AUTHOR, postId);

        assertThat(first.objectKey()).isEqualTo("posts/" + postId + "/body/v1.md");
        assertThat(second.objectKey()).isEqualTo(first.objectKey());
        assertThat(second.bodyVersion()).isEqualTo(first.bodyVersion());
        assertThat(second.bucket()).isEqualTo(first.bucket());

        ContentPost post = repository.findPostById(postId).orElseThrow();
        assertThat(post.publishStage()).isEqualTo(PublishStage.BODY_URL_ISSUED);
    }

    @Test
    void requestBodyUploadUrlRejectsNonAuthor() {
        Long postId = service.createDraft(AUTHOR, new CreateDraftRequest(null)).postId();

        assertThatThrownBy(() -> service.requestBodyUploadUrl(OTHER_AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_FORBIDDEN);
    }

    @Test
    void requestBodyUploadUrlRejectsDeleted() {
        Long postId = service.createDraft(AUTHOR, new CreateDraftRequest(null)).postId();
        repository.softDelete(postId);

        assertThatThrownBy(() -> service.requestBodyUploadUrl(AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_ALREADY_DELETED);
    }

    // --- requestBodyUploadUrl: body re-edit (I1) -----------------------------

    @Test
    void reRequestingUploadUrlAfterConfirmBumpsVersionAndResetsConfirmation() {
        // Regression for I1: a confirmed body re-requested for upload MUST bump the version and reset
        // the confirmation fields, otherwise the next confirmBody compares NEW content against the OLD
        // recorded hash and wedges the post.
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String v1Key = "posts/" + postId + "/body/v1.md";
        byte[] v1 = "first body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(v1Key, "text/markdown", v1, "etag-1");
        service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(v1Key, "etag-1", v1.length, sha256Hex(v1)));

        // Re-request the upload URL on the confirmed body → re-edit.
        BodyUploadUrlResponse reissued = service.requestBodyUploadUrl(AUTHOR, postId);

        assertThat(reissued.objectKey()).isEqualTo("posts/" + postId + "/body/v2.md");
        assertThat(reissued.bodyVersion()).isEqualTo(2);

        ContentPostBody body = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(body.bodyVersion()).isEqualTo(2);
        assertThat(body.bodyObjectKey()).isEqualTo("posts/" + postId + "/body/v2.md");
        assertThat(body.confirmedAt()).isNull();
        assertThat(body.bodySha256()).isNull();
        assertThat(body.bodyEtag()).isNull();
        assertThat(body.bodySizeBytes()).isNull();

        ContentPost post = repository.findPostById(postId).orElseThrow();
        assertThat(post.publishStage()).isEqualTo(PublishStage.BODY_URL_ISSUED);

        // Put NEW v2 content and re-confirm — must succeed, not throw CONTENT_OBJECT_CONFIRM_FAILED.
        String v2Key = "posts/" + postId + "/body/v2.md";
        byte[] v2 = "edited body with different bytes".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(v2Key, "text/markdown", v2, "etag-2");
        PostPublishingStateResponse state = service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                v2Key, "etag-2", v2.length, sha256Hex(v2)));

        assertThat(state.publishStage()).isEqualTo(PublishStage.BODY_CONFIRMED);
        assertThat(state.bodyConfirmed()).isTrue();
    }

    @Test
    void reEditingPublishedBodyReturnsPostToDraft() {
        // A published post whose body is re-edited must come off the public feed (DRAFT) until
        // re-confirmed/re-published, while publishedAt (first-publish history) is preserved.
        Long postId = fullyPreparedPost(AUTHOR);
        service.publish(AUTHOR, postId);
        ContentPost published = repository.findPostById(postId).orElseThrow();
        assertThat(published.status()).isEqualTo(PostStatus.PUBLISHED);
        LocalDateTime firstPublishedAt = published.publishedAt();
        assertThat(firstPublishedAt).isNotNull();

        // Re-edit the body.
        BodyUploadUrlResponse reissued = service.requestBodyUploadUrl(AUTHOR, postId);
        assertThat(reissued.bodyVersion()).isEqualTo(2);

        ContentPost afterEdit = repository.findPostById(postId).orElseThrow();
        assertThat(afterEdit.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(afterEdit.publishStage()).isEqualTo(PublishStage.BODY_URL_ISSUED);
        assertThat(afterEdit.publishedAt()).isEqualTo(firstPublishedAt);

        // Re-walk the full lifecycle on v2 and re-publish.
        String v2Key = "posts/" + postId + "/body/v2.md";
        byte[] v2 = "re-edited published body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(v2Key, "text/markdown", v2, "etag-v2");
        service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(v2Key, "etag-v2", v2.length, sha256Hex(v2)));
        service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "Title", "summary", PostVisibility.PUBLIC, null, List.of(), List.of()));

        PostPublishingStateResponse republished = service.publish(AUTHOR, postId);
        assertThat(republished.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(republished.publishStage()).isEqualTo(PublishStage.PUBLISHED);
        // publishedAt preserved across the re-edit + re-publish cycle.
        assertThat(repository.findPostById(postId).orElseThrow().publishedAt()).isEqualTo(firstPublishedAt);
    }

    // --- confirmBody ----------------------------------------------------------

    @Test
    void confirmBodyComputesSha256AndAdvancesStage() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] content = "# Hello body".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);
        objectStorage.putObject(objectKey, "text/markdown", content, "etag-abc");

        PostPublishingStateResponse state = service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                objectKey, "etag-abc", content.length, sha));

        assertThat(state.publishStage()).isEqualTo(PublishStage.BODY_CONFIRMED);
        assertThat(state.bodyConfirmed()).isTrue();
        assertThat(state.nextActions()).containsExactly("UPDATE_METADATA");

        ContentPostBody body = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(body.bodyEtag()).isEqualTo("etag-abc");
        assertThat(body.bodySha256()).isEqualTo(sha);
        assertThat(body.bodySizeBytes()).isEqualTo((long) content.length);
        assertThat(body.confirmedAt()).isNotNull();
    }

    @Test
    void confirmBodyIsIdempotentForSameObject() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] content = "body text".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);
        objectStorage.putObject(objectKey, "text/markdown", content, "etag-1");

        ConfirmBodyRequest request = new ConfirmBodyRequest(objectKey, "etag-1", content.length, sha);
        service.confirmBody(AUTHOR, postId, request);
        // Second call with identical fields: idempotent, no error.
        PostPublishingStateResponse state = service.confirmBody(AUTHOR, postId, request);

        assertThat(state.publishStage()).isEqualTo(PublishStage.BODY_CONFIRMED);
    }

    @Test
    void confirmBodyRejectsDifferentObjectAfterConfirmation() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] content1 = "first body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, "text/markdown", content1, "etag-1");

        service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                objectKey, "etag-1", content1.length, sha256Hex(content1)));

        // Submit a different etag/size/sha for the same key → conflict.
        assertThatThrownBy(() -> service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                objectKey, "etag-2", content1.length + 1, sha256Hex("other".getBytes()))))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode()
                        == ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED);
    }

    @Test
    void confirmBodyRejectsWrongEtagAgainstStorage() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] content = "body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, "text/markdown", content, "real-etag");

        assertThatThrownBy(() -> service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                objectKey, "wrong-etag", content.length, sha256Hex(content))))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode()
                        == ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED);
    }

    @Test
    void confirmBodyRejectsMismatchedObjectKey() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);

        assertThatThrownBy(() -> service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                "posts/" + postId + "/body/v9.md", "etag", 1L, sha256Hex(new byte[] {1}))))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_OBJECT_KEY_INVALID);
    }

    @Test
    void confirmBodyRejectsOversizedBody() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String objectKey = "posts/" + postId + "/body/v1.md";
        // Body just over 2 MiB. Pre-seed the fake object storage so statObject/readObject see the
        // real oversized bytes; FakeObjectStorageService.statObject reports stored.content().length.
        int oversize = (2 * 1024 * 1024) + 16;
        byte[] content = new byte[oversize];
        java.util.Arrays.fill(content, (byte) 'x');
        objectStorage.putObject(objectKey, "text/markdown", content, "etag-oversize");

        assertThatThrownBy(() -> service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                objectKey, "etag-oversize", oversize, "0".repeat(64))))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode()
                        == ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED);

        // Size check short-circuited before the sha256 read: nothing confirmed.
        ContentPostBody body = repository.findBodyByPostId(postId).orElseThrow();
        assertThat(body.confirmedAt()).isNull();
    }

    // --- updateMetadata -------------------------------------------------------

    @Test
    void updateMetadataReplacesFilesAndTags() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        String bodyObjectKey = "posts/" + postId + "/body/v1.md";
        byte[] content = "body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(bodyObjectKey, "text/markdown", content, "etag");
        service.confirmBody(AUTHOR, postId, new ConfirmBodyRequest(
                bodyObjectKey, "etag", content.length, sha256Hex(content)));

        PostPublishingStateResponse state = service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "My Title",
                "summary",
                PostVisibility.PUBLIC,
                "users/" + AUTHOR + "/cover.png",
                List.of(new PostFileRequest(
                        "users/" + AUTHOR + "/img1.png", PostFileUsageType.INLINE_IMAGE, "image/png", 10L, 0)),
                List.of("Go", "  go  ", "Java")));

        assertThat(state.publishStage()).isEqualTo(PublishStage.METADATA_COMPLETED);
        assertThat(state.metadataCompleted()).isTrue();
        assertThat(state.nextActions()).containsExactly("PUBLISH");

        List<ContentPostFile> files = repository.findFilesByPostId(postId);
        // image + auto-added cover reference.
        assertThat(files).hasSize(2);
        assertThat(files).anyMatch(f -> f.usageType() == PostFileUsageType.COVER
                && f.objectKey().equals("users/" + AUTHOR + "/cover.png"));

        List<ContentTag> tags = repository.findTagsByPostId(postId);
        // Normalized: duplicate "Go"/"go" collapses to one; "Java" preserved.
        assertThat(tags).extracting(ContentTag::name).containsExactlyInAnyOrder("go", "java");
    }

    @Test
    void updateMetadataRejectsForeignObjectKey() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        confirmBodyFor(postId, AUTHOR);

        assertThatThrownBy(() -> service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null,
                List.of(new PostFileRequest(
                        "users/" + OTHER_AUTHOR + "/img.png", PostFileUsageType.ATTACHMENT, null, null, 0)),
                null)))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_OBJECT_KEY_INVALID);
    }

    @Test
    void updateMetadataRejectsBeforeBodyConfirmed() {
        Long postId = service.createDraft(AUTHOR, new CreateDraftRequest(null)).postId();
        service.requestBodyUploadUrl(AUTHOR, postId); // stage BODY_URL_ISSUED, body not confirmed

        assertThatThrownBy(() -> service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null, List.of(), List.of())))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_BODY_NOT_CONFIRMED);
    }

    // --- updateMetadata: object-key safety (C1 — shared policy with StoragePresignService) ----

    @Test
    void updateMetadataRejectsPathTraversalObjectKey() {
        // The traversal key STARTS with the valid "users/{authorId}/" prefix, so it would have
        // passed the old startsWith-only check. The shared ObjectKeyPolicy must catch the "../".
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        confirmBodyFor(postId, AUTHOR);
        String traversalKey = "users/" + AUTHOR + "/../../users/999/x.png";

        assertThatThrownBy(() -> service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null,
                List.of(new PostFileRequest(
                        traversalKey, PostFileUsageType.ATTACHMENT, "image/png", 1L, 0)),
                null)))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_OBJECT_KEY_INVALID);
    }

    @Test
    void updateMetadataRejectsLeadingSlashObjectKey() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        confirmBodyFor(postId, AUTHOR);
        String leadingSlashKey = "/users/" + AUTHOR + "/x.png";

        assertThatThrownBy(() -> service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null,
                List.of(new PostFileRequest(
                        leadingSlashKey, PostFileUsageType.ATTACHMENT, "image/png", 1L, 0)),
                null)))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_OBJECT_KEY_INVALID);
    }

    // --- updateMetadata: replace semantics (M5) --------------------------------

    @Test
    void updateMetadataReplacesPreviousFiles() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        confirmBodyFor(postId, AUTHOR);

        // First call with files [A, B].
        service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null,
                List.of(
                        new PostFileRequest(
                                "users/" + AUTHOR + "/a.png", PostFileUsageType.INLINE_IMAGE, "image/png", 1L, 0),
                        new PostFileRequest(
                                "users/" + AUTHOR + "/b.png", PostFileUsageType.INLINE_IMAGE, "image/png", 1L, 1)),
                List.of()));
        // Second call replaces with just [C].
        service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, null,
                List.of(new PostFileRequest(
                        "users/" + AUTHOR + "/c.png", PostFileUsageType.INLINE_IMAGE, "image/png", 1L, 0)),
                List.of()));

        List<ContentPostFile> files = repository.findFilesByPostId(postId);
        // replaceFiles must drop A/B — only C remains, NOT [A, B, C].
        assertThat(files).extracting(ContentPostFile::objectKey)
                .containsExactly("users/" + AUTHOR + "/c.png");
    }

    // --- updateMetadata: cover dedup (M6) --------------------------------------

    @Test
    void updateMetadataDoesNotDuplicateCoverWhenAlreadyInFiles() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        confirmBodyFor(postId, AUTHOR);
        String coverKey = "users/" + AUTHOR + "/cover.png";

        // The files list already contains a COVER usage for coverKey; supplying the same key as
        // coverObjectKey must NOT auto-add a second COVER reference.
        service.updateMetadata(AUTHOR, postId, new UpdatePostMetadataRequest(
                "T", null, PostVisibility.PUBLIC, coverKey,
                List.of(new PostFileRequest(coverKey, PostFileUsageType.COVER, "image/png", 1L, 0)),
                List.of()));

        List<ContentPostFile> files = repository.findFilesByPostId(postId);
        long coverCount = files.stream()
                .filter(f -> f.usageType() == PostFileUsageType.COVER && coverKey.equals(f.objectKey()))
                .count();
        assertThat(coverCount).isEqualTo(1L);
    }

    // --- publish --------------------------------------------------------------

    @Test
    void publishIsIdempotent() {
        Long postId = fullyPreparedPost(AUTHOR);

        service.publish(AUTHOR, postId);
        LocalDateTime firstPublishedAt = repository.findPostById(postId).orElseThrow().publishedAt();
        assertThat(firstPublishedAt).isNotNull();

        PostPublishingStateResponse second = service.publish(AUTHOR, postId);

        assertThat(second.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(second.publishStage()).isEqualTo(PublishStage.PUBLISHED);
        // publishedAt preserved across the idempotent re-publish.
        assertThat(repository.findPostById(postId).orElseThrow().publishedAt()).isEqualTo(firstPublishedAt);
        assertThat(second.nextActions()).containsExactly("UNPUBLISH");
    }

    @Test
    void publishBeforeMetadataCompletedIsInvalidStage() {
        Long postId = seedConfirmedReadyPost(AUTHOR, null);
        // Stage is BODY_CONFIRMED — not yet METADATA_COMPLETED.

        assertThatThrownBy(() -> service.publish(AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_INVALID_STAGE);
    }

    @Test
    void deletePreventsPublish() {
        Long postId = fullyPreparedPost(AUTHOR);

        service.delete(AUTHOR, postId);
        assertThat(repository.findPostById(postId).orElseThrow().status()).isEqualTo(PostStatus.DELETED);

        assertThatThrownBy(() -> service.publish(AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_ALREADY_DELETED);
    }

    @Test
    void unpublishReturnsToDraftAndAllowsRePublish() {
        Long postId = fullyPreparedPost(AUTHOR);
        service.publish(AUTHOR, postId);

        PostPublishingStateResponse state = service.unpublish(AUTHOR, postId);
        assertThat(state.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(state.publishStage()).isEqualTo(PublishStage.METADATA_COMPLETED);

        // Re-publish still works (publishedAt preserved through the cycle).
        PostPublishingStateResponse republished = service.publish(AUTHOR, postId);
        assertThat(republished.status()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void publishRejectsNonAuthor() {
        Long postId = fullyPreparedPost(AUTHOR);

        assertThatThrownBy(() -> service.publish(OTHER_AUTHOR, postId))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.CONTENT_FORBIDDEN);
    }

    // --- shared fixtures ------------------------------------------------------

    /** Creates a draft and requests the body upload URL, leaving the post at BODY_URL_ISSUED. */
    private Long seedConfirmedReadyPost(Long author, String clientRequestId) {
        Long postId = service.createDraft(author, new CreateDraftRequest(clientRequestId)).postId();
        service.requestBodyUploadUrl(author, postId);
        return postId;
    }

    private void confirmBodyFor(Long postId, Long author) {
        String objectKey = "posts/" + postId + "/body/v1.md";
        byte[] content = "body".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, "text/markdown", content, "etag");
        service.confirmBody(author, postId, new ConfirmBodyRequest(
                objectKey, "etag", content.length, sha256Hex(content)));
    }

    /** Drives a post all the way through to METADATA_COMPLETED. */
    private Long fullyPreparedPost(Long author) {
        Long postId = seedConfirmedReadyPost(author, null);
        confirmBodyFor(postId, author);
        service.updateMetadata(author, postId, new UpdatePostMetadataRequest(
                "Title", "summary", PostVisibility.PUBLIC, null, List.of(), List.of()));
        return postId;
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Monotonic id generator recording every issued id, standing in for the snowflake generator. */
    private static final class SequenceContentIdGenerator implements ContentIdGenerator {
        final List<Long> issued = new ArrayList<>();
        private long next = 1L;

        @Override
        public long nextId() {
            long id = next++;
            issued.add(id);
            return id;
        }
    }

    /**
     * In-memory {@link ContentPostRepository} mirroring the production row semantics (soft delete
     * sets status DELETED; replaceFiles/replaceTags normalize and replace; confirmBody advances the
     * body row only). Pattern follows {@code AuthServiceTest}'s FakeUserRepository.
     */
    private static final class FakeContentPostRepository implements ContentPostRepository {
        final Map<Long, ContentPost> posts = new HashMap<>();
        final Map<Long, ContentPostBody> bodies = new HashMap<>();
        final Map<Long, List<ContentPostFile>> filesByPost = new HashMap<>();
        final Map<Long, List<ContentTag>> tagsByPost = new HashMap<>();

        @Override
        public ContentPost saveDraft(ContentPost post, ContentPostBody body) {
            ContentPost saved = withTimestamps(post, NOW);
            posts.put(saved.id(), saved);
            bodies.put(body.postId(), withTimestamps(body, NOW));
            filesByPost.put(body.postId(), new ArrayList<>());
            tagsByPost.put(body.postId(), new ArrayList<>());
            return saved;
        }

        @Override
        public Optional<ContentPost> findPostById(Long postId) {
            return Optional.ofNullable(posts.get(postId));
        }

        @Override
        public Optional<ContentPost> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId) {
            if (clientRequestId == null) {
                return Optional.empty();
            }
            return posts.values().stream()
                    .filter(p -> authorId.equals(p.authorId())
                            && clientRequestId.equals(p.clientRequestId()))
                    .findFirst();
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
        public void updateBodyUploadUrl(Long postId, String bucket, String objectKey, LocalDateTime expiresAt,
                                        PublishStage stage) {
            ContentPostBody body = bodies.get(postId);
            if (body != null) {
                bodies.put(postId, new ContentPostBody(body.postId(), body.bodyFormat(), bucket, objectKey,
                        body.bodyEtag(), body.bodySha256(), body.bodySizeBytes(), body.bodyVersion(),
                        expiresAt, body.confirmedAt(), body.createdAt(), NOW));
            }
            if (stage != null) {
                setStage(postId, stage);
            }
        }

        @Override
        public void reissueBodyForEdit(Long postId, String bucket, String objectKey, LocalDateTime expiresAt) {
            ContentPostBody body = bodies.get(postId);
            // Mirror the mapper: bump body_version, rewrite bucket/objectKey/upload expiry, and clear
            // the confirmation fields (etag/sha256/size/confirmed_at).
            bodies.put(postId, new ContentPostBody(body.postId(), body.bodyFormat(), bucket, objectKey,
                    null, null, null, body.bodyVersion() + 1, expiresAt, null,
                    body.createdAt(), NOW));
        }

        @Override
        public void confirmBody(Long postId, String objectKey, String etag, String sha256, long sizeBytes,
                                LocalDateTime confirmedAt) {
            ContentPostBody body = bodies.get(postId);
            bodies.put(postId, new ContentPostBody(body.postId(), body.bodyFormat(), body.bodyBucket(),
                    objectKey, etag, sha256, sizeBytes, body.bodyVersion(), body.uploadUrlExpiresAt(),
                    confirmedAt, body.createdAt(), NOW));
        }

        @Override
        public void updateMetadata(Long postId, String title, String summary, PostVisibility visibility,
                                   String coverObjectKey) {
            ContentPost post = posts.get(postId);
            posts.put(postId, new ContentPost(post.id(), post.authorId(), post.clientRequestId(), title,
                    summary, coverObjectKey, post.status(), visibility, post.publishStage(),
                    post.publishedAt(), post.createdAt(), NOW));
        }

        @Override
        public void replaceFiles(Long postId, List<ContentPostFile> files) {
            List<ContentPostFile> resolved = new ArrayList<>();
            if (files != null) {
                for (ContentPostFile f : files) {
                    resolved.add(f.postId() == null
                            ? new ContentPostFile(postId, f.objectKey(), f.usageType(), f.contentType(),
                                    f.sizeBytes(), f.sortOrder(), NOW)
                            : f);
                }
            }
            filesByPost.put(postId, resolved);
        }

        @Override
        public void replaceTags(Long postId, List<String> tagNames) {
            // Normalize like MysqlContentPostRepository: trim, lowercase, drop blanks, de-dupe.
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
            List<ContentTag> tags = new ArrayList<>();
            long id = 1L;
            for (String name : normalized.keySet()) {
                tags.add(new ContentTag(id++, name, NOW));
            }
            tagsByPost.put(postId, tags);
        }

        @Override
        public void updateStatusAndStage(Long postId, PostStatus status, PublishStage stage,
                                         LocalDateTime publishedAt) {
            ContentPost post = posts.get(postId);
            posts.put(postId, new ContentPost(post.id(), post.authorId(), post.clientRequestId(),
                    post.title(), post.summary(), post.coverObjectKey(),
                    status == null ? post.status() : status,
                    post.visibility(),
                    stage == null ? post.publishStage() : stage,
                    publishedAt, post.createdAt(), NOW));
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
            return posts.values().stream()
                    .filter(p -> p.status() == PostStatus.PUBLISHED && p.visibility() == PostVisibility.PUBLIC)
                    .toList();
        }

        @Override
        public List<ContentPost> findByAuthor(Long authorId, int limit, long offset) {
            return posts.values().stream().filter(p -> authorId.equals(p.authorId())).toList();
        }

        private void setStage(Long postId, PublishStage stage) {
            ContentPost post = posts.get(postId);
            posts.put(postId, new ContentPost(post.id(), post.authorId(), post.clientRequestId(),
                    post.title(), post.summary(), post.coverObjectKey(), post.status(), post.visibility(),
                    stage, post.publishedAt(), post.createdAt(), NOW));
        }

        private static ContentPost withTimestamps(ContentPost post, LocalDateTime ts) {
            return new ContentPost(post.id(), post.authorId(), post.clientRequestId(), post.title(),
                    post.summary(), post.coverObjectKey(), post.status(), post.visibility(),
                    post.publishStage(), post.publishedAt(), ts, ts);
        }

        private static ContentPostBody withTimestamps(ContentPostBody body, LocalDateTime ts) {
            return new ContentPostBody(body.postId(), body.bodyFormat(), body.bodyBucket(),
                    body.bodyObjectKey(), body.bodyEtag(), body.bodySha256(), body.bodySizeBytes(),
                    body.bodyVersion(), body.uploadUrlExpiresAt(), body.confirmedAt(), ts, ts);
        }
    }
}
