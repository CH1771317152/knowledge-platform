package com.platform.content.application;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PublishStage;
import com.platform.content.dto.PostPublishingStateResponse;
import java.util.List;
import java.util.Optional;

/**
 * Builds {@link PostPublishingStateResponse} from a post (+ optional body) using the canonical
 * six-stage workflow logic. Shared by {@link ContentCommandService} (every command returns the
 * post's resulting state) and {@link ContentQueryService} (the recovery/query endpoint) so the two
 * paths cannot drift. Extracted in Task 7 to remove the duplication the plan flagged.
 *
 * <p>Package-private: only the two application services in this package call it.
 */
final class PublishingStateBuilder {

    private PublishingStateBuilder() {
    }

    /**
     * @param post the post (never null)
     * @param body the post body, or empty if none
     */
    static PostPublishingStateResponse build(ContentPost post, Optional<ContentPostBody> body) {
        boolean bodyConfirmed = body.isPresent()
                && body.get().confirmedAt() != null
                && body.get().bodyObjectKey() != null;
        boolean metadataCompleted = post.publishStage() == PublishStage.METADATA_COMPLETED
                || post.publishStage() == PublishStage.PUBLISHED
                || post.status() == PostStatus.PUBLISHED;
        return new PostPublishingStateResponse(
                post.id(),
                post.status(),
                post.publishStage(),
                body.map(ContentPostBody::bodyObjectKey).orElse(null),
                bodyConfirmed,
                metadataCompleted,
                nextActions(post));
    }

    /**
     * Derives suggested next client actions from the current status/stage. Deleted → none;
     * Published → UNPUBLISH; otherwise the action that advances the current stage.
     */
    static List<String> nextActions(ContentPost post) {
        if (post.status() == PostStatus.DELETED) {
            return List.of();
        }
        if (post.status() == PostStatus.PUBLISHED) {
            return List.of("UNPUBLISH");
        }
        return switch (post.publishStage()) {
            case DRAFT_CREATED -> List.of("REQUEST_BODY_UPLOAD_URL");
            case BODY_URL_ISSUED -> List.of("CONFIRM_BODY");
            case BODY_CONFIRMED -> List.of("UPDATE_METADATA");
            case METADATA_COMPLETED -> List.of("PUBLISH");
            // PUBLISHED is unreachable here: the early-return above already handled it.
            case PUBLISHED -> throw new IllegalStateException("unreachable: PUBLISHED handled above");
        };
    }
}
