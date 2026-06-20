package com.platform.content.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.content.application.ContentCommandService;
import com.platform.content.application.ContentQueryService;
import com.platform.content.dto.BodyUploadUrlResponse;
import com.platform.content.dto.ConfirmBodyRequest;
import com.platform.content.dto.CreateDraftRequest;
import com.platform.content.dto.PostDetailResponse;
import com.platform.content.dto.PostPublishingStateResponse;
import com.platform.content.dto.PostSummaryResponse;
import com.platform.content.dto.UpdatePostMetadataRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the content module: wires the {@link ContentCommandService} write side and the
 * {@link ContentQueryService} read side behind {@code /api/posts}.
 *
 * <p>{@code @Profile("!test")} mirrors the two services (both depend on the
 * {@code @Profile("!test")} repository + object-storage beans); excluding it under {@code test}
 * keeps {@code PlatformApplicationTests.contextLoads} green.
 *
 * <p><b>Public vs authenticated reads (design decision #1):</b> {@code GET /api/posts} (public list)
 * and {@code GET /api/posts/{postId}} (detail) are public at the filter-chain layer
 * ({@code SecurityConfig}); the detail endpoint reads the authenticated principal <em>optionally</em>
 * — anonymous callers get a null requester id and the service enforces per-post read permission.
 * {@code GET /api/posts/me} and {@code GET /api/posts/{postId}/publishing-state} are authenticated.
 */
@RestController
@RequestMapping("/api/posts")
@Profile("!test")
public class ContentController {

    private final ContentCommandService commandService;
    private final ContentQueryService queryService;
    private final CurrentUserService currentUserService;

    public ContentController(ContentCommandService commandService,
                             ContentQueryService queryService,
                             CurrentUserService currentUserService) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.currentUserService = currentUserService;
    }

    // --- write side (all authenticated) ---------------------------------------

    @PostMapping("/drafts")
    public ApiResponse<PostPublishingStateResponse> createDraft(@Valid @RequestBody CreateDraftRequest request) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.createDraft(authorId, request));
    }

    @PostMapping("/{postId}/body/upload-url")
    public ApiResponse<BodyUploadUrlResponse> requestBodyUploadUrl(@PathVariable Long postId) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.requestBodyUploadUrl(authorId, postId));
    }

    @PostMapping("/{postId}/body/confirm")
    public ApiResponse<PostPublishingStateResponse> confirmBody(
            @PathVariable Long postId, @Valid @RequestBody ConfirmBodyRequest request) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.confirmBody(authorId, postId, request));
    }

    @PutMapping("/{postId}/metadata")
    public ApiResponse<PostPublishingStateResponse> updateMetadata(
            @PathVariable Long postId, @Valid @RequestBody UpdatePostMetadataRequest request) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.updateMetadata(authorId, postId, request));
    }

    @PostMapping("/{postId}/publish")
    public ApiResponse<PostPublishingStateResponse> publish(@PathVariable Long postId) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.publish(authorId, postId));
    }

    @PostMapping("/{postId}/unpublish")
    public ApiResponse<PostPublishingStateResponse> unpublish(@PathVariable Long postId) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.unpublish(authorId, postId));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<PostPublishingStateResponse> delete(@PathVariable Long postId) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(commandService.delete(authorId, postId));
    }

    // --- read side ------------------------------------------------------------

    /** Author-only view of the post's publishing workflow state. */
    @GetMapping("/{postId}/publishing-state")
    public ApiResponse<PostPublishingStateResponse> getPublishingState(@PathVariable Long postId) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(queryService.getPublishingState(authorId, postId));
    }

    /** The current user's own posts (any status/visibility). */
    @GetMapping("/me")
    public ApiResponse<List<PostSummaryResponse>> listMyPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long authorId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(queryService.listMyPosts(authorId, page, size));
    }

    /**
     * Public detail. Anonymous callers (no/invalid token) get a null requester id and the service
     * enforces per-post read permission (only PUBLISHED+PUBLIC is world-readable).
     */
    @GetMapping("/{postId}")
    public ApiResponse<PostDetailResponse> getPostDetail(@PathVariable Long postId) {
        Long requesterIdOrNull = optionalRequesterId();
        return ApiResponse.ok(queryService.getPostDetail(requesterIdOrNull, postId));
    }

    /** Public feed of published+public posts. */
    @GetMapping
    public ApiResponse<List<PostSummaryResponse>> listPublicPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.listPublicPosts(page, size));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Reads the authenticated principal <em>optionally</em> from the {@code SecurityContext}. Unlike
     * {@link CurrentUserService#requirePrincipal()} this does NOT throw for an anonymous caller — the
     * detail endpoint is public at the filter layer, so anonymous callers reach here and must map to a
     * null requester id (the service then enforces per-post permission).
     */
    private static Long optionalRequesterId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return null;
        }
        return principal.userId();
    }
}
