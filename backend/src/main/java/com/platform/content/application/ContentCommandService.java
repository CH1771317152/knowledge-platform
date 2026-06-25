package com.platform.content.application;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.common.util.Strings;
import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.ContentPostFile;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.dto.BodyUploadUrlResponse;
import com.platform.content.dto.ConfirmBodyRequest;
import com.platform.content.dto.CreateDraftRequest;
import com.platform.content.dto.PostFileRequest;
import com.platform.content.dto.PostPublishingStateResponse;
import com.platform.content.dto.UpdatePostMetadataRequest;
import com.platform.content.event.ContentPostEventType;
import com.platform.content.infrastructure.id.ContentIdGenerator;
import com.platform.content.repository.ContentPostRepository;
import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.domain.ObjectKeyPolicy;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the six-stage, resumable, idempotent post-publishing workflow:
 *
 * <pre>
 *   DRAFT_CREATED → (requestBodyUploadUrl) → BODY_URL_ISSUED
 *                → (confirmBody)            → BODY_CONFIRMED
 *                → (updateMetadata)         → METADATA_COMPLETED
 *                → (publish)                → PUBLISHED
 * </pre>
 *
 * <p>Every mutating method is {@code @Transactional}: the {@link ContentPostRepository} is
 * intentionally non-transactional (matching the user-module convention where transactionality lives
 * at the service layer), so multi-statement mutations here must roll back as a unit on failure.
 * The annotation is inert when this service is constructed directly in a unit test (no Spring
 * proxy), so the fakes still run cleanly.
 *
 * <p>{@code @Profile("!test")} mirrors {@link AuthService}: {@link ContentPostRepository}'s MySQL
 * impl and {@link ObjectStorageService}'s Aliyun impl are both {@code @Profile("!test")} (the OSS
 * one is further gated on the non-smoke profiles), so this bean must be excluded under the
 * {@code test} profile to keep {@code PlatformApplicationTests.contextLoads} green. The unit test
 * constructs it directly with fakes.
 *
 * <p><b>Idempotency:</b> {@code createDraft} keys on {@code (authorId, clientRequestId)}; the body
 * URL can be re-requested (fresh put URL, stable object key/version); {@code confirmBody} and
 * {@code publish} return the current state on a duplicate matching request.
 */
@Service
@Profile("!test")
public class ContentCommandService {

    /** Fixed presign lifetime for the body-upload PUT URL. See design decision #2. */
    private static final Duration BODY_UPLOAD_URL_TTL = Duration.ofMinutes(10);

    private static final String BODY_CONTENT_TYPE = "text/markdown";

    /**
     * Hard ceiling on a confirmed post body's byte size. Markdown bodies are expected to be far
     * smaller (kilobytes, low megabytes at most); 2 MiB is a generous upper bound. Enforced at
     * confirm time so that every future detail read — which loads the body fully into memory via
     * {@link ContentQueryService#getPostDetail} — is bounded, avoiding an OOM/amplification vector.
     */
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    private final ContentPostRepository repository;
    private final ContentIdGenerator contentIdGenerator;
    private final ObjectStorageService objectStorageService;
    private final ContentOutboxAppender outboxAppender;

    public ContentCommandService(ContentPostRepository repository,
                                 ContentIdGenerator contentIdGenerator,
                                 ObjectStorageService objectStorageService,
                                 ContentOutboxAppender outboxAppender) {
        this.repository = repository;
        this.contentIdGenerator = contentIdGenerator;
        this.objectStorageService = objectStorageService;
        this.outboxAppender = outboxAppender;
    }

    // --- stage 1: create draft ------------------------------------------------

    /**
     * Creates a draft post, or returns the existing one if {@code clientRequestId} already resolved
     * a post for this author (idempotent). See design decision #3 for the found-but-DELETED case.
     */
    @Transactional
    public PostPublishingStateResponse createDraft(Long authorId, CreateDraftRequest request) {
        String clientRequestId = Strings.trimToNull(request == null ? null : request.clientRequestId());
        if (clientRequestId != null) {
            Optional<ContentPost> existing =
                    repository.findPostByAuthorAndClientRequestId(authorId, clientRequestId);
            if (existing.isPresent()) {
                // Idempotent: same author+clientRequestId returns the same post regardless of its
                // current status (DRAFT, PUBLISHED, or even DELETED — see design decision #3).
                return PublishingStateBuilder.build(
                        existing.get(), repository.findBodyByPostId(existing.get().id()));
            }
        }

        long id = contentIdGenerator.nextId();
        ContentPost post = new ContentPost(
                id, authorId, clientRequestId, null, null, null,
                PostStatus.DRAFT, PostVisibility.PRIVATE, PublishStage.DRAFT_CREATED,
                null, null, null, 0L);
        ContentPostBody body = new ContentPostBody(
                id, PostBodyFormat.MARKDOWN, null, null, null, null, null,
                1, null, null, null, null);
        repository.saveDraft(post, body);
        return PublishingStateBuilder.build(post, Optional.of(body));
    }

    // --- stage 2: request body upload URL -------------------------------------

    /**
     * Issues a presigned PUT URL for the post body markdown at the versioned object key.
     *
     * <p>Two branches:
     * <ul>
     *   <li><b>Body NOT yet confirmed</b> (first upload, or an idempotent re-request before confirm):
     *       keep the CURRENT version's objectKey, refresh the put URL, stage {@code BODY_URL_ISSUED}.
     *       No version bump, no confirmation-field reset. This keeps {@code repeatedBodyUploadUrl}
     *       stable so a client that retries the URL before confirming reuses the same {@code v1} key.</li>
     *   <li><b>Body ALREADY confirmed</b> ({@code body.confirmedAt() != null}) — a RE-EDIT: increment
     *       {@code body_version}, recompute the objectKey from the new version, clear the confirmation
     *       fields (etag/sha256/size/confirmed_at), and re-walk the body lifecycle from
     *       {@code BODY_URL_ISSUED}. A {@code PUBLISHED} post is demoted to {@code DRAFT} (with
     *       {@code publishedAt} preserved) so it comes off the public feed until re-confirmed/re-published.
     *       Without this branch the post is wedged: re-uploading overwrites the same {@code v1} object
     *       and the next {@code confirmBody} compares NEW content against the OLD recorded hash and
     *       throws {@code CONTENT_OBJECT_CONFIRM_FAILED}.</li>
     * </ul>
     */
    @Transactional
    public BodyUploadUrlResponse requestBodyUploadUrl(Long authorId, Long postId) {
        ContentPost post = loadOwnedNonDeletedPost(authorId, postId); // ownership + existence + not-deleted
        ContentPostBody body = repository.findBodyByPostId(postId)
                .orElseThrow(() -> notFound(postId));

        if (body.confirmedAt() != null) {
            // Re-edit: bump version, presign the new-version key, reset confirmation, re-walk lifecycle.
            int newVersion = body.bodyVersion() + 1;
            String newObjectKey = bodyObjectKey(postId, newVersion);
            // NOTE: presignPut is a LOCAL signing op (Aliyun generates the presigned URL without a
            // network call), so holding the transaction across it is fine — both DB writes below stay
            // atomic. A rollback just means this URL expires unused (no partial state to undo).
            PresignedUpload upload =
                    objectStorageService.presignPut(newObjectKey, BODY_CONTENT_TYPE, BODY_UPLOAD_URL_TTL);

            repository.reissueBodyForEdit(postId, upload.bucket(), newObjectKey, upload.expiresAt());
            PostStatus nextStatus = post.status() == PostStatus.PUBLISHED ? PostStatus.DRAFT : post.status();
            repository.updateStatusAndStage(
                    postId, nextStatus, PublishStage.BODY_URL_ISSUED, post.publishedAt());

            return new BodyUploadUrlResponse(
                    postId, newVersion, upload.bucket(), newObjectKey, upload.putUrl(),
                    upload.headers(), upload.expiresAt());
        }

        // Normal branch: same version/key, fresh put URL, stage BODY_URL_ISSUED.
        String objectKey = bodyObjectKey(postId, body.bodyVersion());
        PresignedUpload upload =
                objectStorageService.presignPut(objectKey, BODY_CONTENT_TYPE, BODY_UPLOAD_URL_TTL);

        repository.updateBodyUploadUrl(
                postId, upload.bucket(), objectKey, upload.expiresAt(), PublishStage.BODY_URL_ISSUED);

        return new BodyUploadUrlResponse(
                postId, body.bodyVersion(), upload.bucket(), objectKey, upload.putUrl(),
                upload.headers(), upload.expiresAt());
    }

    // --- stage 3: confirm body ------------------------------------------------

    /**
     * Verifies the uploaded body object's size/etag/SHA-256 against storage and, on success,
     * records the confirmation and advances the stage to {@link PublishStage#BODY_CONFIRMED}.
     */
    @Transactional
    public PostPublishingStateResponse confirmBody(Long authorId, Long postId, ConfirmBodyRequest request) {
        ContentPost post = loadOwnedNonDeletedPost(authorId, postId);
        ContentPostBody body = repository.findBodyByPostId(postId)
                .orElseThrow(() -> notFound(postId));

        String expectedObjectKey = bodyObjectKey(postId, body.bodyVersion());
        if (!expectedObjectKey.equals(request.objectKey())) {
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_KEY_INVALID,
                    "Body object key does not match the issued upload URL");
        }

        boolean alreadyConfirmed = body.confirmedAt() != null;
        if (alreadyConfirmed) {
            boolean sameObject = Objects.equals(body.bodyObjectKey(), request.objectKey())
                    && Objects.equals(body.bodyEtag(), request.etag())
                    && Objects.equals(body.bodySha256(), request.sha256())
                    && body.bodySizeBytes() != null
                    && body.bodySizeBytes() == request.sizeBytes();
            if (sameObject) {
                // Idempotent re-confirm: returns success against the PREVIOUSLY RECORDED hash, does
                // not re-read live storage. Safe because body objectKeys are versioned — the
                // recorded hash pins a specific immutable object.
                return PublishingStateBuilder.build(post, Optional.of(body));
            }
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED,
                    "Body already confirmed with a different object");
        }

        StoredObjectMetadata stat = objectStorageService.statObject(request.objectKey());
        // Cap the body size BEFORE the expensive readObject/sha256 computation. Enforced here so that
        // every future getPostDetail — which reads the full body into memory — is bounded. Either the
        // caller-reported size or the real stored size being oversized is enough to reject.
        if (stat.sizeBytes() > MAX_BODY_BYTES || request.sizeBytes() > MAX_BODY_BYTES) {
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED,
                    "post body exceeds maximum allowed size");
        }
        if (stat.sizeBytes() != request.sizeBytes() || !request.etag().equals(stat.etag())) {
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED,
                    "Reported size/etag does not match the stored object");
        }

        String computedSha256 = sha256Hex(objectStorageService.readObject(request.objectKey()));
        if (!computedSha256.equals(request.sha256())) {
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_CONFIRM_FAILED,
                    "Reported SHA-256 does not match the stored object content");
        }

        repository.confirmBody(postId, request.objectKey(), request.etag(), computedSha256,
                request.sizeBytes(), LocalDateTime.now());
        // confirmBody updates the body row only; advance publish_stage on the post separately.
        // See design decision #1.
        repository.updateStatusAndStage(
                postId, post.status(), PublishStage.BODY_CONFIRMED, post.publishedAt());

        return PublishingStateBuilder.build(refetch(postId), repository.findBodyByPostId(postId));
    }

    // --- stage 4: update metadata ---------------------------------------------

    /**
     * Sets title/summary/visibility/cover, replaces file references and tags, advances stage.
     *
     * <p>Emits a {@link ContentPostEvent} of type {@code POST_EDITED} on the successful completion
     * of the metadata write. If the request also changed {@code visibility} (comparing the post's
     * pre-update visibility to the request's new value), a second {@link ContentPostEvent} of type
     * {@code POST_VISIBILITY_CHANGED} is co-emitted, carrying {@code oldVisibility}/
     * {@code newVisibility} (as {@link Enum#name()} strings) in its {@code changes} map. The Kafka
     * publisher forwards both to {@code content-events} so downstream consumers (counter, feed)
     * can react to either transition.
     */
    @Transactional
    public PostPublishingStateResponse updateMetadata(Long authorId, Long postId, UpdatePostMetadataRequest request) {
        ContentPost post = loadOwnedNonDeletedPost(authorId, postId);
        ContentPostBody body = repository.findBodyByPostId(postId)
                .orElseThrow(() -> notFound(postId));

        if (body.confirmedAt() == null) {
            throw new PlatformException(ErrorCode.CONTENT_BODY_NOT_CONFIRMED,
                    "Cannot update metadata before the body is confirmed");
        }
        if (isBlank(request.title())) {
            throw new PlatformException(ErrorCode.COMMON_BAD_REQUEST, "Title must not be blank");
        }
        if (request.visibility() == null) {
            throw new PlatformException(ErrorCode.COMMON_BAD_REQUEST, "Visibility must not be null");
        }

        if (!isBlank(request.coverObjectKey())
                && !ObjectKeyPolicy.isOwnedObjectKey(request.coverObjectKey(), authorId)) {
            throw new PlatformException(ErrorCode.CONTENT_OBJECT_KEY_INVALID,
                    "Cover object key must live under the author's own prefix");
        }

        List<ContentPostFile> files = new ArrayList<>();
        List<PostFileRequest> requestFiles = request.files() == null ? List.of() : request.files();
        for (PostFileRequest f : requestFiles) {
            if (!ObjectKeyPolicy.isOwnedObjectKey(f.objectKey(), authorId)) {
                throw new PlatformException(ErrorCode.CONTENT_OBJECT_KEY_INVALID,
                        "File object key must live under the author's own prefix");
            }
            files.add(new ContentPostFile(
                    postId, f.objectKey(), f.usageType(), f.contentType(), f.sizeBytes(),
                    f.sortOrder(), null));
        }

        // If a cover object key was supplied, ensure a COVER file reference exists.
        if (!isBlank(request.coverObjectKey())) {
            boolean hasCover = files.stream().anyMatch(x -> x.usageType() != null
                    && x.usageType() == com.platform.content.domain.PostFileUsageType.COVER);
            if (!hasCover) {
                files.add(new ContentPostFile(
                        postId, request.coverObjectKey(),
                        com.platform.content.domain.PostFileUsageType.COVER, null, null, 0, null));
            }
        }

        // Detect a visibility change BEFORE the repository write overwrites the field. The events
        // fire AFTER the repo writes succeed (below), so a rollback drops them along with the write.
        PostVisibility oldVisibility = post.visibility();
        PostVisibility newVisibility = request.visibility();
        boolean visibilityChanged = oldVisibility != newVisibility;

        repository.updateMetadata(
                postId, request.title(), request.summary(), request.visibility(),
                Strings.trimToNull(request.coverObjectKey()));
        repository.replaceFiles(postId, files);
        repository.replaceTags(postId, request.tags() == null ? List.of() : request.tags());
        repository.updateStatusAndStage(
                postId, post.status(), PublishStage.METADATA_COMPLETED, post.publishedAt());

        // Bump source_version and re-read so the outbox event carries the current post state
        // (status + visibility + the new monotonically increasing source version) for downstream
        // search indexing. Two events on a visibility change so they are independently dedup-able.
        repository.bumpSourceVersion(postId);
        ContentPost afterMetadata = refetch(postId);
        LocalDateTime metadataAt = LocalDateTime.now();
        outboxAppender.append(afterMetadata, ContentPostEventType.POST_EDITED, metadataAt);
        if (visibilityChanged) {
            outboxAppender.append(afterMetadata, ContentPostEventType.POST_VISIBILITY_CHANGED,
                    LocalDateTime.now());
        }

        return PublishingStateBuilder.build(afterMetadata, repository.findBodyByPostId(postId));
    }

    // --- stage 5: publish -----------------------------------------------------

    /**
     * Publishes the post (idempotent). Requires the workflow to have reached
     * {@link PublishStage#METADATA_COMPLETED}. {@code publishedAt} is set only on the first publish
     * and preserved across any future re-publish.
     *
     * <p>On the actual first-publish transition ({@code publishedAt} was null → now PUBLISHED), emits
     * a {@link ContentPostEvent} (type {@code POST_PUBLISHED}) via the in-process
     * {@link ApplicationEventPublisher}. A {@code @TransactionalEventListener(AFTER_COMMIT)} Kafka
     * publisher in the {@code event} package then forwards it to the {@code content-events} Kafka
     * topic so the counter module can increment the author's {@code posts_count}. The event is NOT
     * emitted on an idempotent re-publish of an already-published post.
     */
    @Transactional
    public PostPublishingStateResponse publish(Long authorId, Long postId) {
        ContentPost post = loadOwnedNonDeletedPost(authorId, postId);
        // Fetch the body once and reuse it for both the idempotent-republish response and the final
        // first-publish response (C3 fix: previously findBodyByPostId was called up to 3 times here).
        ContentPostBody body = repository.findBodyByPostId(postId).orElseThrow(() -> notFound(postId));

        if (post.status() == PostStatus.PUBLISHED) {
            // Idempotent: no timestamp update, no side effects, no event.
            return PublishingStateBuilder.build(post, Optional.of(body));
        }

        if (stageOrder(post.publishStage()) < stageOrder(PublishStage.METADATA_COMPLETED)) {
            throw new PlatformException(ErrorCode.CONTENT_INVALID_STAGE,
                    "Post is not ready to publish: metadata must be completed first");
        }

        boolean firstPublish = post.publishedAt() == null;
        LocalDateTime publishedAt = firstPublish
                ? LocalDateTime.now()
                : post.publishedAt();
        repository.updateStatusAndStage(postId, PostStatus.PUBLISHED, PublishStage.PUBLISHED, publishedAt);

        if (firstPublish) {
            // Bump source_version + re-read so the outbox event carries the post's now-PUBLISHED
            // state. The outbox relay forwards the naked JSON to content-events after commit.
            repository.bumpSourceVersion(postId);
            ContentPost published = refetch(postId);
            outboxAppender.append(published, ContentPostEventType.POST_PUBLISHED, LocalDateTime.now());
        }

        return PublishingStateBuilder.build(refetch(postId), Optional.of(body));
    }

    // --- unpublish / delete ---------------------------------------------------

    /**
     * Moves a PUBLISHED post back to DRAFT, keeping {@code publishedAt} and the METADATA_COMPLETED
     * stage so it can be re-published. Idempotent for an already-DRAFT post.
     *
     * <p>On the actual PUBLISHED → DRAFT transition, emits a {@link ContentPostEvent} of type
     * {@code POST_UNPUBLISHED}. No event on the idempotent already-DRAFT path.
     */
    @Transactional
    public PostPublishingStateResponse unpublish(Long authorId, Long postId) {
        ContentPost post = loadOwnedPost(authorId, postId);

        if (post.status() == PostStatus.PUBLISHED) {
            repository.updateStatusAndStage(
                    postId, PostStatus.DRAFT, PublishStage.METADATA_COMPLETED, post.publishedAt());
            // Bump + re-read so the outbox event reflects the now-DRAFT state; the relay forwards it
            // to content-events so counter/feed/search react to the unpublish.
            repository.bumpSourceVersion(postId);
            ContentPost unpublished = refetch(postId);
            outboxAppender.append(unpublished, ContentPostEventType.POST_UNPUBLISHED, LocalDateTime.now());
        } else if (post.status() == PostStatus.DRAFT) {
            // Idempotent: already a draft. No event.
        } else {
            // DELETED — cannot unpublish a deleted post. Report CONTENT_ALREADY_DELETED; this is
            // the same guard the other mutating methods apply via loadOwnedPost, but loadOwnedPost
            // only rejects DELETED on the methods that must not touch a deleted post. unpublish on a
            // deleted post is "not applicable" and we surface the existing deleted state reason.
            throw new PlatformException(ErrorCode.CONTENT_ALREADY_DELETED,
                    "Cannot unpublish a deleted post");
        }

        return PublishingStateBuilder.build(refetch(postId), repository.findBodyByPostId(postId));
    }

    /**
     * Soft-deletes the post (idempotent for an already-deleted post).
     *
     * <p>On the active → DELETED transition, emits a {@link ContentPostEvent} of type
     * {@code POST_DELETED}. No event on the idempotent already-DELETED path.
     */
    @Transactional
    public PostPublishingStateResponse delete(Long authorId, Long postId) {
        // loadOwnedNonDeletedPost already rejects a DELETED post (CONTENT_ALREADY_DELETED), so
        // delete() is never invoked on an already-deleted post. softDelete + event is the only path.
        loadOwnedNonDeletedPost(authorId, postId);

        repository.softDelete(postId);
        // Bump + re-read so the outbox event carries the DELETED state; the relay forwards it so
        // counter/feed/search remove the post from their read models.
        repository.bumpSourceVersion(postId);
        ContentPost deleted = refetch(postId);
        outboxAppender.append(deleted, ContentPostEventType.POST_DELETED, LocalDateTime.now());
        return PublishingStateBuilder.build(deleted, repository.findBodyByPostId(postId));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Loads a post, asserts ownership, and rejects a DELETED post. Used by every mutating method
     * that must not operate on a deleted post.
     */
    private ContentPost loadOwnedNonDeletedPost(Long authorId, Long postId) {
        ContentPost post = loadOwnedPost(authorId, postId);
        if (post.status() == PostStatus.DELETED) {
            throw new PlatformException(ErrorCode.CONTENT_ALREADY_DELETED, "Post is deleted");
        }
        return post;
    }

    private ContentPost loadOwnedPost(Long authorId, Long postId) {
        ContentPost post = repository.findPostById(postId)
                .orElseThrow(() -> notFound(postId));
        if (!post.authorId().equals(authorId)) {
            throw new PlatformException(ErrorCode.CONTENT_FORBIDDEN,
                    "Post does not belong to the requesting author");
        }
        return post;
    }

    /**
     * Re-reads a post after a mutation so the returned {@link PostPublishingStateResponse} reflects
     * the freshly written status/stage rather than the pre-mutation in-memory snapshot.
     */
    private ContentPost refetch(Long postId) {
        return repository.findPostById(postId).orElseThrow(() -> notFound(postId));
    }

    private static int stageOrder(PublishStage stage) {
        return switch (stage) {
            case DRAFT_CREATED -> 0;
            case BODY_URL_ISSUED -> 1;
            case BODY_CONFIRMED -> 2;
            case METADATA_COMPLETED -> 3;
            case PUBLISHED -> 4;
        };
    }

    private static String bodyObjectKey(Long postId, int bodyVersion) {
        return "posts/" + postId + "/body/v" + bodyVersion + ".md";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static PlatformException notFound(Long postId) {
        return new PlatformException(ErrorCode.CONTENT_POST_NOT_FOUND, "Post not found: " + postId);
    }

    private static String sha256Hex(InputStream in) {
        try (in) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS/JDK; never missing in practice.
            throw new PlatformException(ErrorCode.COMMON_INTERNAL_ERROR,
                    "SHA-256 algorithm unavailable");
        } catch (IOException e) {
            throw new PlatformException(ErrorCode.STORAGE_OBJECT_CHECK_FAILED,
                    "Failed reading object stream");
        }
    }
}

