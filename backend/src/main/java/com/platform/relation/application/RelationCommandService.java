package com.platform.relation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.domain.RelationOutboxEvent;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.dto.FollowRelationResponse;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.repository.RelationRepository;
import com.platform.user.application.UserQueryService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command side of the relation module: follow / unfollow, each emitting a transactional-outbox row
 * in the SAME transaction as the follow mutation (the design's "主表 + Outbox 同事务" decision).
 *
 * <p>{@code @Profile("!test")} mirrors {@link com.platform.content.application.ContentCommandService}:
 * it depends on the {@code @Profile("!test")} {@link RelationRepository} MySQL impl, so it must be
 * excluded under the {@code test} profile to keep {@code PlatformApplicationTests.contextLoads} green.
 * The unit test constructs it directly with a fake {@link RelationRepository} and a real
 * {@link UserQueryService} backed by a fake {@code UserRepository}.
 *
 * <p><b>Transactionality:</b> {@link RelationRepository} is intentionally non-transactional (matching
 * the content/user convention), so multi-statement mutations here MUST be {@code @Transactional} to
 * keep the following-write and outbox-insert atomic. The annotation is inert when this service is
 * constructed directly in a unit test (no Spring proxy), so the fakes still run cleanly.
 *
 * <p><b>Idempotency:</b> {@link #follow} and {@link #unfollow} are idempotent — a repeated follow on
 * an already-ACTIVE edge (or a repeated unfollow on a CANCELED/absent edge) performs no mutation and
 * writes NO outbox row. An outbox row is emitted ONLY when the follow state actually changes (insert,
 * CANCELED→ACTIVE reactivation, or ACTIVE→CANCELED cancellation).
 */
@Service
@Profile("!test")
public class RelationCommandService {

    /** Aggregate type stamped on every relation outbox event. */
    private static final String AGGREGATE_TYPE = "FOLLOW";

    private final RelationRepository relationRepository;
    private final UserQueryService userQueryService;
    private final ObjectMapper objectMapper;

    public RelationCommandService(RelationRepository relationRepository,
                                  UserQueryService userQueryService,
                                  ObjectMapper objectMapper) {
        this.relationRepository = relationRepository;
        this.userQueryService = userQueryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Current user follows {@code targetUserId}. Idempotent: a follow on an already-ACTIVE edge is a
     * no-op (no outbox row). A follow on a CANCELED edge reactivates it (CANCELED→ACTIVE) and emits a
     * {@link RelationEventType#USER_FOLLOWED} outbox row.
     */
    @Transactional
    public FollowRelationResponse follow(Long currentUserId, Long targetUserId) {
        rejectSelfFollow(currentUserId, targetUserId);
        verifyTargetExists(targetUserId);

        LocalDateTime now = LocalDateTime.now();
        Optional<UserFollowing> existing = relationRepository.findFollowing(currentUserId, targetUserId);

        boolean stateChanged;
        if (existing.isEmpty()) {
            relationRepository.insertFollowing(currentUserId, targetUserId, FollowStatus.ACTIVE, now);
            stateChanged = true;
        } else if (existing.get().status() == FollowStatus.CANCELED) {
            relationRepository.activateFollowing(currentUserId, targetUserId, now);
            stateChanged = true;
        } else {
            // Already ACTIVE: idempotent, no mutation, no outbox.
            stateChanged = false;
        }

        if (stateChanged) {
            insertOutbox(RelationEventType.USER_FOLLOWED, currentUserId, targetUserId, now);
        }

        // followedAt is the now() we wrote (same value on insert and reactivation). When the edge was
        // already ACTIVE we return the existing row's followedAt rather than the fresh now().
        LocalDateTime followedAt = existing.isPresent() && !stateChanged
                ? existing.get().followedAt()
                : now;
        return new FollowRelationResponse(currentUserId, targetUserId, true, followedAt);
    }

    /**
     * Current user unfollows {@code targetUserId}. Idempotent: an unfollow on an absent or CANCELED
     * edge is a no-op (no outbox row). An unfollow on an ACTIVE edge cancels it (ACTIVE→CANCELED) and
     * emits a {@link RelationEventType#USER_UNFOLLOWED} outbox row.
     */
    @Transactional
    public FollowRelationResponse unfollow(Long currentUserId, Long targetUserId) {
        rejectSelfFollow(currentUserId, targetUserId);
        verifyTargetExists(targetUserId);

        Optional<UserFollowing> existing = relationRepository.findFollowing(currentUserId, targetUserId);

        boolean stateChanged;
        if (existing.isPresent() && existing.get().status() == FollowStatus.ACTIVE) {
            relationRepository.cancelFollowing(currentUserId, targetUserId, LocalDateTime.now());
            stateChanged = true;
        } else {
            // Absent or already CANCELED: idempotent, no mutation, no outbox.
            stateChanged = false;
        }

        if (stateChanged) {
            insertOutbox(RelationEventType.USER_UNFOLLOWED, currentUserId, targetUserId, LocalDateTime.now());
        }

        return new FollowRelationResponse(currentUserId, targetUserId, false, null);
    }

    // --- helpers --------------------------------------------------------------

    private void rejectSelfFollow(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new PlatformException(ErrorCode.RELATION_SELF_FOLLOW_FORBIDDEN,
                    "A user cannot follow themselves");
        }
    }

    /**
     * Verifies the target user exists. We resolve the target via {@link UserQueryService}, which
     * throws a generic PlatformException ("user not found") on a miss; we convert it to
     * {@link ErrorCode#RELATION_TARGET_NOT_FOUND} so a missing follow target never leaks the
     * user-module's USER_NOT_FOUND code.
     */
    private void verifyTargetExists(Long targetUserId) {
        try {
            userQueryService.getPublicProfile(targetUserId);
        } catch (PlatformException e) {
            throw new PlatformException(ErrorCode.RELATION_TARGET_NOT_FOUND,
                    "Follow target not found: " + targetUserId);
        }
    }

    /**
     * Builds and inserts a {@link RelationOutboxEvent}. {@code payloadJson} is the serialized
     * {@link RelationEventPayload}; a JSON failure is wrapped as
     * {@link ErrorCode#RELATION_EVENT_INVALID}. {@code id} and {@code createdAt} are left null so the
     * DB assigns them.
     */
    private void insertOutbox(RelationEventType eventType, Long followerId, Long followingId,
                              LocalDateTime occurredAt) {
        String eventId = UUID.randomUUID().toString();
        String aggregateId = "FOLLOW:" + followerId + ":" + followingId;
        RelationEventPayload payload = new RelationEventPayload(
                eventId, eventType, AGGREGATE_TYPE, aggregateId, followerId, followingId, occurredAt);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new PlatformException(ErrorCode.RELATION_EVENT_INVALID,
                    "Failed to serialize relation event payload");
        }
        RelationOutboxEvent event = new RelationOutboxEvent(
                null, eventId, AGGREGATE_TYPE, aggregateId, eventType, followerId, followingId,
                payloadJson, occurredAt, null);
        relationRepository.insertOutbox(event);
    }
}
