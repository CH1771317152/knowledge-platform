package com.platform.relation.application;

import com.platform.common.exception.PlatformException;
import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.dto.FollowRelationResponse;
import com.platform.relation.dto.FollowUserResponse;
import com.platform.relation.repository.RelationRepository;
import com.platform.user.application.UserQueryService;
import com.platform.user.dto.UserProfileResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query side of the relation module: relation-state lookup and the following/followers lists.
 *
 * <p>{@code @Profile("!test")} mirrors {@link RelationCommandService}: it depends on the
 * {@code @Profile("!test")} {@link RelationRepository} MySQL impl, so it must be excluded under the
 * {@code test} profile to keep {@code PlatformApplicationTests.contextLoads} green. The unit test
 * constructs it directly with a fake {@link RelationRepository} and a real {@link UserQueryService}
 * backed by a fake {@code UserRepository}.
 *
 * <p><b>N+1 profile enrichment (v1):</b> {@link #listFollowing} and {@link #listFollowers} resolve
 * each entry's profile one-by-one via {@link UserQueryService#getPublicProfile}. Acceptable for v1; a
 * batched profile fetch can replace it later. A referenced user that no longer exists (profile lookup
 * throws) is SKIPPED rather than surfacing a 500 to the list caller — see {@link #enrich}.
 */
@Service
@Profile("!test")
public class RelationQueryService {

    /** Upper bound on page size to keep list payloads sane. */
    private static final int MAX_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(RelationQueryService.class);

    private final RelationRepository relationRepository;
    private final UserQueryService userQueryService;

    public RelationQueryService(RelationRepository relationRepository, UserQueryService userQueryService) {
        this.relationRepository = relationRepository;
        this.userQueryService = userQueryService;
    }

    /**
     * Whether {@code currentUserId} actively follows {@code targetUserId}. A read — no self-follow
     * rejection; if {@code currentUserId == targetUserId} this simply returns following=false. An
     * absent or CANCELED edge both report following=false with a null followedAt.
     */
    @Transactional(readOnly = true)
    public FollowRelationResponse getRelation(Long currentUserId, Long targetUserId) {
        Optional<UserFollowing> existing = relationRepository.findFollowing(currentUserId, targetUserId);
        boolean following = existing.isPresent() && existing.get().status() == FollowStatus.ACTIVE;
        LocalDateTime followedAt = following ? existing.get().followedAt() : null;
        return new FollowRelationResponse(currentUserId, targetUserId, following, followedAt);
    }

    /**
     * Lists the ACTIVE users {@code userId} follows, newest followed_at first, enriched with each
     * following user's public profile snapshot. Paging is clamped to [1,50] per page, page floored at 0.
     */
    @Transactional(readOnly = true)
    public List<FollowUserResponse> listFollowing(Long userId, int page, int size) {
        int[] paging = clampPaging(page, size);
        List<UserFollowing> rows = relationRepository.findFollowingList(userId, paging[0], paging[1]);
        return rows.stream()
                .map(r -> enrich(r.followingId(), r.followedAt()))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Lists the ACTIVE fans of {@code userId}, newest followed_at first, enriched with each fan's
     * public profile snapshot. Paging is clamped to [1,50] per page, page floored at 0.
     */
    @Transactional(readOnly = true)
    public List<FollowUserResponse> listFollowers(Long userId, int page, int size) {
        int[] paging = clampPaging(page, size);
        List<UserFollower> rows = relationRepository.findFollowerList(userId, paging[0], paging[1]);
        return rows.stream()
                .map(r -> enrich(r.followerId(), r.followedAt()))
                .flatMap(Optional::stream)
                .toList();
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Resolves a user's public profile for list enrichment. If the referenced user no longer exists
     * (deleted between the follow and now), {@link UserQueryService} throws and we return
     * {@link Optional#empty} so the caller skips that entry rather than 500-ing the whole list.
     */
    private Optional<FollowUserResponse> enrich(Long targetUserId, LocalDateTime followedAt) {
        try {
            UserProfileResponse profile = userQueryService.getPublicProfile(targetUserId);
            return Optional.of(new FollowUserResponse(
                    profile.userId(), profile.username(), profile.displayName(), profile.avatarUrl(),
                    followedAt));
        } catch (PlatformException e) {
            log.debug("Skipping relation entry for missing user {}: {}", targetUserId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Clamps page/size to a sane range. Returns {@code {limit, offset}} (page floored at 0; size in
     * [1,50]; offset = page * size).
     */
    private static int[] clampPaging(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new int[] {safeSize, safePage * safeSize};
    }
}
