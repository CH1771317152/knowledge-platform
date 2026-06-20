package com.platform.relation.domain;

/**
 * Relationship domain event types emitted to the outbox and consumed by the follower projector.
 */
public enum RelationEventType {
    /** A user started following another user. */
    USER_FOLLOWED,
    /** A user canceled an existing follow (unfollow). */
    USER_UNFOLLOWED
}
