package com.platform.relation.domain;

import java.time.LocalDateTime;

/**
 * A row from {@code relation_following}: the people a given user follows.
 *
 * @param id          row id
 * @param followerId  the user who follows (the actor / fan)
 * @param followingId the user being followed (the target)
 * @param status      ACTIVE or CANCELED
 * @param followedAt  when the follow took effect
 * @param canceledAt  when the follow was canceled, null if still ACTIVE
 * @param createdAt   row creation timestamp
 * @param updatedAt   row last-update timestamp
 */
public record UserFollowing(
        Long id,
        Long followerId,
        Long followingId,
        FollowStatus status,
        LocalDateTime followedAt,
        LocalDateTime canceledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
