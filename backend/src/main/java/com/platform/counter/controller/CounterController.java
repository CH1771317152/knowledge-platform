package com.platform.counter.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.common.response.ApiResponse;
import com.platform.content.application.ContentQueryService;
import com.platform.counter.application.CounterFactService;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.counter.dto.ArticleCountersResponse;
import com.platform.counter.dto.InteractionResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the counter module: the interaction endpoints (like / fav / view) and the
 * counter reads, all mounted under {@code /api/posts/{postId}/...}.
 *
 * <p>{@code @Profile("!test")} mirrors {@link CounterFactService} / {@link CounterReadService}
 * (both depend on the {@code @Profile("!test")} {@code CounterStore} Redis impl and on the
 * {@code @Profile("!test")} {@link ContentQueryService}); excluding this controller under
 * {@code test} keeps {@code PlatformApplicationTests.contextLoads} green.
 *
 * <p><b>authorId resolution (design decision #1):</b> the like/fav fan-out needs the post's author
 * so the consumer can move the author's {@code LIKES_RECEIVED}/{@code FAVS_RECEIVED} counter. The
 * controller resolves it (and checks the post's existence) via the lightweight internal
 * {@link ContentQueryService#findPostAuthorId}, which returns the author id of any non-deleted post
 * with <em>no</em> permission gating — interaction endpoints are open to any authenticated user
 * regardless of a post's visibility, and the author id is only an event-routing key. A missing or
 * soft-deleted post surfaces as {@link ErrorCode#COUNTER_ENTITY_NOT_FOUND}.
 *
 * <p><b>Idempotency by interaction shape (mirrors {@link CounterFactService}):</b>
 * <ul>
 *   <li>like / fav / unlike / unfav are gated by the bitmap — the {@code changed} flag in the
 *       {@link InteractionResponse} reflects a real 0↔1 bit transition; a repeat is a no-op.</li>
 *   <li>view requires a client-supplied {@code Idempotency-Key} header (the consumer dedupes on it);
 *       a missing/blank key is rejected with {@link ErrorCode#COUNTER_EVENT_INVALID} → 400.</li>
 * </ul>
 *
 * <p><b>Public vs authenticated reads:</b> {@code GET /api/posts/{postId}/counters} is public at the
 * filter chain ({@code SecurityConfig}) — anonymous callers see the counts.
 * {@code GET /api/posts/{postId}/counters/liked} reveals the requesting user's own like state and
 * stays authenticated; all writes fall through to {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/posts")
@Profile("!test")
public class CounterController {

    private static final CounterEntityType ARTICLE = CounterEntityType.ARTICLE;
    private static final CounterMetric LIKE = CounterMetric.LIKE;

    private final CounterFactService factService;
    private final CounterReadService readService;
    private final CurrentUserService currentUserService;
    private final ContentQueryService contentQueryService;

    public CounterController(CounterFactService factService,
                             CounterReadService readService,
                             CurrentUserService currentUserService,
                             ContentQueryService contentQueryService) {
        this.factService = factService;
        this.readService = readService;
        this.currentUserService = currentUserService;
        this.contentQueryService = contentQueryService;
    }

    // --- like -----------------------------------------------------------------

    @PostMapping("/{postId}/likes")
    public ApiResponse<InteractionResponse> like(@PathVariable Long postId) {
        long userId = currentUserService.requirePrincipal().userId();
        long authorId = resolveAuthor(postId);
        boolean changed = factService.like(userId, ARTICLE, postId, authorId);
        return ApiResponse.ok(new InteractionResponse(changed));
    }

    @DeleteMapping("/{postId}/likes")
    public ApiResponse<InteractionResponse> unlike(@PathVariable Long postId) {
        long userId = currentUserService.requirePrincipal().userId();
        long authorId = resolveAuthor(postId);
        boolean changed = factService.unlike(userId, ARTICLE, postId, authorId);
        return ApiResponse.ok(new InteractionResponse(changed));
    }

    // --- favorite -------------------------------------------------------------

    @PostMapping("/{postId}/favorites")
    public ApiResponse<InteractionResponse> favorite(@PathVariable Long postId) {
        long userId = currentUserService.requirePrincipal().userId();
        long authorId = resolveAuthor(postId);
        boolean changed = factService.fav(userId, ARTICLE, postId, authorId);
        return ApiResponse.ok(new InteractionResponse(changed));
    }

    @DeleteMapping("/{postId}/favorites")
    public ApiResponse<InteractionResponse> unfavorite(@PathVariable Long postId) {
        long userId = currentUserService.requirePrincipal().userId();
        long authorId = resolveAuthor(postId);
        boolean changed = factService.unfav(userId, ARTICLE, postId, authorId);
        return ApiResponse.ok(new InteractionResponse(changed));
    }

    // --- view -----------------------------------------------------------------

    @PostMapping("/{postId}/views")
    public ApiResponse<InteractionResponse> view(@PathVariable Long postId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        long userId = currentUserService.requirePrincipal().userId();
        long authorId = resolveAuthor(postId);
        factService.view(userId, ARTICLE, postId, authorId, idempotencyKey);
        return ApiResponse.ok(new InteractionResponse(true));
    }

    // --- reads ----------------------------------------------------------------

    /**
     * Public article counters. Anonymous callers (no/invalid token) reach here —
     * {@code SecurityConfig} permits {@code GET /api/posts/{postId}/counters} without auth.
     *
     * <p><b>Staleness note:</b> counts reflect the last flush; up to one flush interval
     * ({@code platform.counter.flush.max-interval-ms}) of in-flight deltas may not yet be reflected.
     * This is the accepted eventual-consistency window; periodic reconciliation recalibrates from the
     * fact layer. The bitmap-based {@code /counters/liked} read, by contrast, IS immediate (strong
     * consistency) — it reads the live bitmap, not the flushed count blob.
     */
    @GetMapping("/{postId}/counters")
    public ApiResponse<ArticleCountersResponse> getCounters(@PathVariable Long postId) {
        return ApiResponse.ok(readService.getArticleCounters(postId));
    }

    /** Whether the current user has liked this post. Authenticated — reveals the user's own state. */
    @GetMapping("/{postId}/counters/liked")
    public ApiResponse<Boolean> hasLiked(@PathVariable Long postId) {
        long userId = currentUserService.requirePrincipal().userId();
        return ApiResponse.ok(readService.hasActed(userId, ARTICLE, postId, LIKE));
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Resolves the post's author id, throwing {@link ErrorCode#COUNTER_ENTITY_NOT_FOUND} if the post
     * does not exist (or is soft-deleted). Doubles as the interaction's existence precondition.
     */
    private long resolveAuthor(Long postId) {
        return contentQueryService.findPostAuthorId(postId)
                .orElseThrow(() -> new PlatformException(ErrorCode.COUNTER_ENTITY_NOT_FOUND,
                        "Post not found: " + postId))
                .longValue();
    }
}
