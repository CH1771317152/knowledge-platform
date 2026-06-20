package com.platform.relation.dto;

import java.time.LocalDateTime;

/**
 * Result of a "does currentUser follow targetUser" query (GET /relation/following/{targetUserId}).
 *
 * @param currentUserId the authenticated user making the query
 * @param targetUserId  the user being checked against
 * @param following     true if currentUserId has an ACTIVE follow on targetUserId
 * @param followedAt    when the follow took effect, null if not following
 */
public record FollowRelationResponse(
        Long currentUserId,
        Long targetUserId,
        boolean following,
        LocalDateTime followedAt
) {}
