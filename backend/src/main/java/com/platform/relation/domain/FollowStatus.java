package com.platform.relation.domain;

/**
 * Lifecycle status of a follow edge.
 */
public enum FollowStatus {
    /** Follow is currently in effect. */
    ACTIVE,
    /** Follow has been canceled (unfollowed). The row is retained for history / re-follow idempotency. */
    CANCELED
}
