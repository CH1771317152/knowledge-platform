package com.platform.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.search.application.SearchPostQueryService;
import com.platform.search.dto.SearchPostPageResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-layer test for {@link SearchController}. The controller is a thin adapter over
 * {@link SearchPostQueryService} (which has its own dedicated unit tests for the anonymous /
 * authenticated / cursor / size-clamp contracts), so these tests pin only the controller-specific
 * behavior that the service tests cannot cover:
 *
 * <ul>
 *   <li><b>Routing & binding</b> — {@code GET /api/search/posts} binds {@code q}, {@code tag},
 *       {@code contentType}, {@code cursor}, {@code size} and applies the documented defaults
 *       ({@code contentType=ARTICLE}, {@code size=20}).</li>
 *   <li><b>Anonymous requester id</b> — a request with no populated {@code SecurityContext} yields a
 *       {@code null} requester id (the service then skips the overlay). This is the public-feed
 *       contract the {@code permitAll} filter-chain rule depends on.</li>
 *   <li><b>Authenticated requester id</b> — when the {@code SecurityContext} carries an
 *       {@link AuthenticatedPrincipal}, its {@code userId} flows through to the service.</li>
 *   <li><b>Missing required param</b> — a request without {@code q} fails binding (400).</li>
 * </ul>
 *
 * <p><b>Why standalone MockMvc, not {@code @SpringBootTest}:</b> the search endpoint is gated behind
 * {@code @ConditionalOnProperty(platform.search.enabled=true)} and the whole search wiring (ES client,
 * query service, controller) is absent under both the {@code test} and the default {@code integration}
 * profiles. Bootstrapping the full context would therefore not register the controller. Standalone
 * setup constructs the controller directly with a stubbed service — no Elasticsearch, no Spring
 * context, no external dependencies — which matches the project's "@Profile discipline: unit tests
 * construct directly, no Spring injection" rule.
 */
class SearchControllerTest {

    private SearchPostQueryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SearchPostQueryService.class);
        // standaloneSetup builds the controller without a Spring context; the @Profile /
        // @ConditionalOnProperty class annotations are inert here. A ControllerAdvice is not wired
        // because the controller itself throws nothing the test exercises — the only failure paths
        // (bad cursor / missing required param) surface as IllegalArgumentException / 400 directly.
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(service)).build();
    }

    @AfterEach
    void clearSecurityContext() {
        // SecurityContext is thread-local and shared across tests in the same JVM; always clear so a
        // previous test's authenticated principal cannot leak into the next one.
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousRequestPassesNullRequesterIdAndAppliesDefaults() throws Exception {
        // No SecurityContext populated → optionalRequesterId() returns null → overlay skipped.
        SearchPostPageResponse stub = new SearchPostPageResponse(
                List.of(item(1L, false, false)), true, "next-cursor");
        // size defaults to 20 when omitted; the verify() below pins this exact default.
        when(service.search(eq("java"), isNull(), eq("ARTICLE"), isNull(), eq(20), isNull()))
                .thenReturn(stub);

        MvcResult result = mockMvc.perform(get("/api/search/posts").param("q", "java"))
                .andExpect(status().isOk())
                // ApiResponse shape: { success, message, data }.
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.items[0].postId").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andReturn();

        // The controller passes the documented defaults when the optional params are omitted.
        verify(service).search("java", null, "ARTICLE", null, 20, null);

        // Sanity: the response serialized as the ApiResponse wrapper, not a raw page.
        assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    @Test
    void authenticatedRequestForwardsRequesterIdFromPrincipal() throws Exception {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                42L, "alice", com.platform.user.domain.UserRole.USER, "jti-1");
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext ctx = new SecurityContextImpl();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        // An authenticated reader: the overlay resolves real booleans (true here). The controller
        // must forward the requester id (42L) from the SecurityContext, not null.
        SearchPostPageResponse stub = new SearchPostPageResponse(
                List.of(item(1L, true, false)), false, null);
        when(service.search(eq("java"), eq("spring"), eq("ARTICLE"), eq("cur"), eq(5), eq(42L)))
                .thenReturn(stub);

        mockMvc.perform(get("/api/search/posts")
                        .param("q", "java")
                        .param("tag", "spring")
                        .param("cursor", "cur")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[0].favedByMe").value(false))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        // userId 42L from the principal is what reaches the service — the overlay path depends on it.
        verify(service).search("java", "spring", "ARTICLE", "cur", 5, 42L);
    }

    @Test
    void missingRequiredQueryReturnsBadRequest() throws Exception {
        // q is @RequestParam (required) — omitting it must fail binding with a 4xx, never reach the
        // service. This is the only validation the controller itself enforces.
        mockMvc.perform(get("/api/search/posts"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(service);
    }

    // --- helpers --------------------------------------------------------------

    private static FeedItemResponse item(long postId, boolean likedByMe, boolean favedByMe) {
        return new FeedItemResponse(
                postId, 2L, "作者", "cover.jpg", "title-" + postId, "summary",
                LocalDateTime.now(), 10L, 1L, 100L, 0L, 0L, likedByMe, favedByMe);
    }
}
