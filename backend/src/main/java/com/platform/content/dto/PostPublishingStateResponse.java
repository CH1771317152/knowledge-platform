package com.platform.content.dto;

import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PublishStage;
import java.util.List;

/**
 * Snapshot of a post's position in the six-stage publishing workflow, returned by every command so
 * the client can drive the next step. The same builder backs the Task 7 recovery/query endpoint.
 *
 * @param postId             the post id
 * @param status             lifecycle status (DRAFT / PUBLISHED / DELETED)
 * @param publishStage       workflow stage (DRAFT_CREATED → PUBLISHED)
 * @param bodyObjectKey      the confirmed body object key, or null if not yet confirmed
 * @param bodyConfirmed      whether the body has passed SHA-256 verification
 * @param metadataCompleted  whether stage has reached METADATA_COMPLETED or beyond
 * @param nextActions        suggested next client actions for the current stage
 */
public record PostPublishingStateResponse(
        Long postId,
        PostStatus status,
        PublishStage publishStage,
        String bodyObjectKey,
        boolean bodyConfirmed,
        boolean metadataCompleted,
        List<String> nextActions
) {}
