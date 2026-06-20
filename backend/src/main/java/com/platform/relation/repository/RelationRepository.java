package com.platform.relation.repository;

import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationOutboxEvent;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.event.RelationEventPayload;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for the relation module.
 *
 * <p>The command service writes follow mutations to {@code relation_following} and the matching
 * outbox event to {@code relation_outbox} in the same transaction. The follower projector consumes
 * outbox events and maintains the {@code relation_follower} read model via
 * {@link #upsertFollowerProjection(RelationEventPayload, FollowStatus)}. Idempotent consumption is
 * guarded by {@link #markConsumed(String, String)} (first consumer wins).
 */
public interface RelationRepository {

    /** Finds a single follow edge by its (follower, following) key. */
    Optional<UserFollowing> findFollowing(Long followerId, Long followingId);

    /** Inserts a new follow edge with the given status. */
    void insertFollowing(Long followerId, Long followingId, FollowStatus status, LocalDateTime followedAt);

    /** Reactivates a (previously canceled) follow edge. */
    void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt);

    /** Cancels an existing follow edge (sets status=CANCELED, records canceledAt). */
    void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt);

    /** Inserts a transactional-outbox row for a relationship domain event. */
    void insertOutbox(RelationOutboxEvent event);

    /** Lists the ACTIVE users a given user follows (paginated, newest followed_at first). */
    List<UserFollowing> findFollowingList(Long followerId, int limit, long offset);

    /** Lists the ACTIVE fans of a given user (paginated, newest followed_at first). */
    List<UserFollower> findFollowerList(Long userId, int limit, long offset);

    /**
     * Upserts the {@code relation_follower} projection for an event. {@code userId} is the followed
     * user (event.followingId); {@code followerId} is the fan (event.followerId). When
     * {@code status == ACTIVE}, {@code followedAt} is set to {@code event.occurredAt()} and
     * {@code canceledAt} is null; when {@code CANCELED}, the reverse.
     */
    void upsertFollowerProjection(RelationEventPayload event, FollowStatus status);

    /**
     * Records that {@code consumerGroup} has consumed {@code eventId}. Returns {@code true} if this
     * call was the first to record it, {@code false} if it was already recorded (idempotent dedup
     * — first consumer wins).
     */
    boolean markConsumed(String eventId, String consumerGroup);

    /**
     * Atomically record consumption and apply the follower projection for this consumer group.
     * Returns {@code true} if projected (first time for this group); {@code false} if already
     * consumed (duplicate, skip). On projection failure the transaction rolls back the consumed
     * marker, so a redelivery/retry re-attempts cleanly.
     *
     * <p>This closes the C1 window where {@link #markConsumed(String, String)} and
     * {@link #upsertFollowerProjection(RelationEventPayload, FollowStatus)} were two separate calls:
     * a crash or throw between them left the event marked consumed for the group but never
     * projected, and the retry consumer (a different group) could not recover it.
     */
    boolean projectIfFirstTime(RelationEventPayload event, String consumerGroup);
}
