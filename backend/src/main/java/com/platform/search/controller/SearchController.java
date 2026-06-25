package com.platform.search.controller;

import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.search.application.SearchPostQueryService;
import com.platform.search.dto.SearchPostPageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/search/posts} — public article search backed by Elasticsearch.
 *
 * <p>The endpoint is {@code permitAll} at the filter chain (see {@code SecurityConfig}). Anonymous
 * readers get a page with {@code likedByMe}/{@code favedByMe} left {@code null} (the "no overlay
 * applied" sentinel); a Bearer token, if present, overlays both bits for the authenticated reader via
 * the counter module's bitmaps.
 *
 * <p><b>Profile / conditional:</b> {@code @Profile("!test")} mirrors the query service; the
 * {@code @ConditionalOnProperty(platform.search.enabled=true)} keeps the whole endpoint absent when
 * search is disabled, so requests 404 (no handler) rather than 500 (no Elasticsearch). This is the same
 * gate applied to the query service and the ES client wiring.
 */
@RestController
@RequestMapping("/api/search")
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchController {

    private final SearchPostQueryService service;

    public SearchController(SearchPostQueryService service) {
        this.service = service;
    }

    /**
     * @param q           the search keyword (title / description / body full-text).
     * @param tag         optional tag filter.
     * @param contentType content-type filter, defaults to {@code ARTICLE} (the only indexed type in v1).
     * @param cursor      the signed {@code search_after} cursor from the previous page; omitted on page 1.
     * @param size        page size, default 20, clamped to {@code [1, 50]} by the service.
     */
    @GetMapping("/posts")
    public ApiResponse<SearchPostPageResponse> searchPosts(
            @RequestParam String q,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "ARTICLE") String contentType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(q, tag, contentType, cursor, size, optionalRequesterId()));
    }

    /**
     * Reads the authenticated principal <em>optionally</em> from the {@code SecurityContext}. Unlike
     * {@code CurrentUserService.requirePrincipal()} this does NOT throw for an anonymous caller — the
     * search endpoint is public at the filter layer, so anonymous callers reach here and must map to a
     * {@code null} requester id (the service then skips the personalization overlay).
     *
     * <p>Mirrors the {@code optionalRequesterId()} pattern in {@code FeedController} /
     * {@code ContentController}: the {@code SecurityContext} peek is cheaper and avoids leaning on
     * exception control flow for the anonymous path.
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
