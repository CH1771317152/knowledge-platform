package com.platform.relation.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.common.response.ApiResponse;
import com.platform.relation.application.RelationCommandService;
import com.platform.relation.application.RelationQueryService;
import com.platform.relation.dto.FollowRelationResponse;
import com.platform.relation.dto.FollowUserResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the relation module: wires the {@link RelationCommandService} write side
 * (follow / unfollow) and the {@link RelationQueryService} read side (relation-state lookup and the
 * following/followers lists) behind {@code /api/users/{userId}/...}.
 *
 * <p>{@code @Profile("!test")} mirrors the two services (both depend on the
 * {@code @Profile("!test")} {@link com.platform.relation.repository.RelationRepository} MySQL impl);
 * excluding it under the {@code test} profile keeps {@code PlatformApplicationTests.contextLoads}
 * green, the same discipline as {@link com.platform.content.controller.ContentController} and
 * {@link com.platform.storage.controller.StorageController}.
 *
 * <p><b>Public vs authenticated reads (design decision #2):</b> {@code GET /api/users/{userId}/following}
 * and {@code GET /api/users/{userId}/followers} are public at the filter-chain layer
 * ({@code SecurityConfig}) — conventional social UX. {@code GET /api/users/{userId}/relation} is
 * authenticated (it reveals the <em>current</em> user's follow state and must not be exposed to
 * anonymous callers), and the write endpoints (POST/DELETE follow) fall through to
 * {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/users")
@Profile("!test")
public class RelationController {

    private final RelationCommandService commandService;
    private final RelationQueryService queryService;
    private final CurrentUserService currentUserService;

    public RelationController(RelationCommandService commandService,
                              RelationQueryService queryService,
                              CurrentUserService currentUserService) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.currentUserService = currentUserService;
    }

    // --- write side (all authenticated) ---------------------------------------

    /** The current user follows {@code userId}. Idempotent. */
    @PostMapping("/{userId}/follow")
    public ApiResponse<FollowRelationResponse> follow(@PathVariable Long userId) {
        Long currentUserId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.follow(currentUserId, userId));
    }

    /** The current user unfollows {@code userId}. Idempotent. */
    @DeleteMapping("/{userId}/follow")
    public ApiResponse<FollowRelationResponse> unfollow(@PathVariable Long userId) {
        Long currentUserId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.unfollow(currentUserId, userId));
    }

    // --- read side ------------------------------------------------------------

    /**
     * Whether the current user actively follows {@code userId}. Authenticated: it reveals the current
     * user's follow state, so it is NOT permitAll'd at the filter layer.
     */
    @GetMapping("/{userId}/relation")
    public ApiResponse<FollowRelationResponse> getRelation(@PathVariable Long userId) {
        Long currentUserId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(queryService.getRelation(currentUserId, userId));
    }

    /** Public list of the users {@code userId} follows (newest first). */
    @GetMapping("/{userId}/following")
    public ApiResponse<List<FollowUserResponse>> listFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.listFollowing(userId, page, size));
    }

    /** Public list of {@code userId}'s fans (newest first). */
    @GetMapping("/{userId}/followers")
    public ApiResponse<List<FollowUserResponse>> listFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.listFollowers(userId, page, size));
    }
}
