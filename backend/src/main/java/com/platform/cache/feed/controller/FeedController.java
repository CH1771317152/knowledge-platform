package com.platform.cache.feed.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.cache.feed.application.FeedReadService;
import com.platform.cache.feed.domain.Cursor;
import com.platform.cache.feed.dto.FeedPageResponse;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.common.response.ApiResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the feed cache module (Task 12). Exposes the three-tier read path behind
 * {@code /api/feed}:
 * <ul>
 *   <li>{@code GET /api/feed/public} — public, {@code permitAll} at the filter chain. Anonymous callers
 *       get the head/cursor page with {@code likedByMe}/{@code favedByMe} left {@code null} (the
 *       "no overlay applied" sentinel); a Bearer token, if present, overlays both bits for the
 *       authenticated reader.</li>
 *   <li>{@code GET /api/feed/me} — authenticated (falls through to
 *       {@code anyRequest().authenticated()} in {@code SecurityConfig}).</li>
 * </ul>
 *
 * <p><b>Cursor format.</b> The cursor is the opaque keyset token the client round-trips from
 * {@code nextCursor}: {@code {ISO LocalDateTime},{postId}}, e.g. {@code 2026-06-21T10:00,123}. An
 * absent/blank cursor selects the head page; a malformed cursor throws
 * {@link ErrorCode#CACHE_INVALID_CURSOR} (→ 400).
 *
 * <p><b>Page size.</b> Defaults to 20, clamped to {@code [1,50]} to match {@link FeedReadService}'s
 * clamp. The clamp is duplicated here so a bad client value is normalized before it reaches the
 * service (and before it shapes a cache key — different sizes produce different cache keys, so a
 * clamp at the controller also keeps the key-space bounded).
 *
 * <p><b>Profile.</b> {@code @Profile("!test")} mirrors {@link FeedReadService} and the rest of the
 * feed module's Redis-backed wiring (the service depends on {@code @Profile("!test")} stores and the
 * counter read service); excluding the controller under {@code test} keeps
 * {@code PlatformApplicationTests.contextLoads} green.
 */
@RestController
@RequestMapping("/api/feed")
@Profile("!test")
public class FeedController {

    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 50;

    private final FeedReadService feedReadService;
    private final CurrentUserService currentUserService;

    public FeedController(FeedReadService feedReadService, CurrentUserService currentUserService) {
        this.feedReadService = feedReadService;
        this.currentUserService = currentUserService;
    }

    /**
     * Public feed. {@code permitAll} at the filter chain (see {@code SecurityConfig}). The
     * authenticated requester id, if any, drives the personalization overlay (likedByMe/favedByMe);
     * an anonymous reader gets a {@code null} overlay (the sentinel that signals "no overlay applied").
     */
    @GetMapping("/public")
    public ApiResponse<FeedPageResponse> publicFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        int clamped = clampSize(size);
        Cursor parsed = parseCursor(cursor);
        Long requesterIdOrNull = optionalRequesterId();
        return ApiResponse.ok(feedReadService.readPublicFeed(parsed, clamped, requesterIdOrNull));
    }

    /**
     * The current user's own feed (drafts + published, all visibilities except DELETED). Authenticated
     * — falls through to {@code anyRequest().authenticated()}. The personalization overlay is not
     * applied (the "my posts" view is always first-person).
     */
    @GetMapping("/me")
    public ApiResponse<FeedPageResponse> myFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        int clamped = clampSize(size);
        Long userId = currentUserService.requirePrincipal().userId();
        Cursor parsed = parseCursor(cursor);
        return ApiResponse.ok(feedReadService.readUserFeed(userId, parsed, clamped));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Parses the opaque cursor ({@code {ISO timestamp},{id}}). Absent/blank → {@code null} (head page).
     * A malformed cursor (missing comma, unparseable timestamp/id) →
     * {@link ErrorCode#CACHE_INVALID_CURSOR} so the client gets a 400 and can reset to the head.
     */
    static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int comma = cursor.indexOf(',');
        if (comma <= 0 || comma == cursor.length() - 1) {
            throw new PlatformException(ErrorCode.CACHE_INVALID_CURSOR,
                    "cursor format: {timestamp},{id}");
        }
        LocalDateTime timestamp;
        Long id;
        try {
            timestamp = LocalDateTime.parse(cursor.substring(0, comma));
            id = Long.valueOf(cursor.substring(comma + 1));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new PlatformException(ErrorCode.CACHE_INVALID_CURSOR,
                    "cursor format: {timestamp},{id}");
        }
        return new Cursor(timestamp, id);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);
    }

    /**
     * Reads the authenticated principal <em>optionally</em> from the {@code SecurityContext}. The
     * public endpoint is {@code permitAll}, so anonymous callers reach here and must map to a null
     * requester id (the overlay is then skipped). Mirrors
     * {@code ContentController.optionalRequesterId()} rather than catching
     * {@code CurrentUserService.requirePrincipal()}'s exception — the SecurityContext peek is cheaper
     * and does not lean on exception control flow for the anonymous path.
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
