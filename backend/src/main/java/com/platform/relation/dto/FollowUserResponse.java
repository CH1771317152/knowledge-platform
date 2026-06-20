package com.platform.relation.dto;

import java.time.LocalDateTime;

/**
 * A follower or following entry in a list endpoint, enriched with the target user's public
 * profile snapshot.
 *
 * @param userId      the target user's id
 * @param username    the target user's username
 * @param displayName the target user's display name, may be null
 * @param avatarUrl   the target user's avatar url, may be null
 * @param followedAt  when the follow edge took effect
 */
public record FollowUserResponse(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        LocalDateTime followedAt
) {}
