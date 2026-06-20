package com.platform.relation.domain;

import java.time.LocalDateTime;

/**
 * A row from {@code relation_follower}: the fans of a given user (the reverse-projection of
 * {@link UserFollowing}). This is the read model maintained by the follower projector.
 *
 * <p>Semantics: {@code userId} is the user being followed (被关注者); {@code followerId} is the
 * fan (粉丝) who follows them. So a row {@code (userId=10, followerId=7)} means "user 7 follows
 * user 10", and is the mirror of the {@code UserFollowing} row {@code (followerId=7,
 * followingId=10)}.
 *
 * @param id          row id
 * @param userId      the user being followed (被关注者)
 * @param followerId  the fan who follows them (粉丝)
 * @param status      ACTIVE or CANCELED
 * @param followedAt  when the follow took effect
 * @param canceledAt  when the follow was canceled, null if still ACTIVE
 * @param createdAt   row creation timestamp
 * @param updatedAt   row last-update timestamp
 */
public record UserFollower(
        Long id,
        Long userId,
        Long followerId,
        FollowStatus status,
        LocalDateTime followedAt,
        LocalDateTime canceledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
