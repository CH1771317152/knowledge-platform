package com.platform.config;

import com.platform.auth.infrastructure.jwt.JwtAuthenticationEntryPoint;
import com.platform.auth.infrastructure.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        // Genuinely-public endpoints (presented before anyRequest() — matcher order matters).
                        // Note: /api/auth/me (GET) and /api/auth/logout (POST) are deliberately NOT here, so
                        // they require an authenticated access token at the filter-chain layer (anything else
                        // → JwtAuthenticationEntryPoint → 401). The structural guarantee replaces the previous
                        // reliance on CurrentUserService.requirePrincipal() throwing for an anonymous caller.
                        .requestMatchers(
                                "/actuator/health",
                                "/error",
                                "/api/auth/register",
                                "/api/auth/login/**",
                                "/api/auth/token/refresh",
                                "/api/auth/password/reset",
                                "/api/auth/verification-codes"
                        ).permitAll()
                        // Content reads: order matters. The two authenticated content paths come BEFORE the
                        // public permitAll pair so the single-segment wildcard style does not let anonymous
                        // traffic reach /api/posts/me or the publishing-state endpoint. (Design decision #1.)
                        //   GET /api/posts/me            → authenticated (author's own posts)
                        //   GET /api/posts/{id}/state     → authenticated (author-only publishing state)
                        //   GET /api/posts               → public (published+public feed)
                        //   GET /api/posts/{id}          → public (per-post detail; service enforces read perm)
                        // POST/PUT/DELETE under /api/posts/** fall through to anyRequest().authenticated().
                        .requestMatchers(HttpMethod.GET, "/api/posts/me", "/api/posts/{postId}/publishing-state")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/posts", "/api/posts/{postId}")
                                .permitAll()
                        // Counter reads: the public per-post counters (like/fav/view/comment/share) are
                        // publicly readable (anonymous can see counts). This is a distinct 2-segment path
                        // — the single-segment /api/posts/{postId} matcher above does NOT match it. The
                        // authenticated counterpart GET /api/posts/{postId}/counters/liked (3 segments,
                        // reveals the requester's own like state) is deliberately NOT here and falls
                        // through to anyRequest().authenticated(); so do all interaction writes
                        // (POST/DELETE likes/favorites, POST views). No existing matcher removed/reordered.
                        .requestMatchers(HttpMethod.GET, "/api/posts/{postId}/counters").permitAll()
                        // Relation reads: the following/followers lists are publicly readable
                        // (conventional social UX). The current user's follow state
                        // (GET /api/users/{userId}/relation) is deliberately NOT here — it reveals
                        // the requester's own follow state and stays authenticated, as do the write
                        // endpoints (POST/DELETE /api/users/{userId}/follow), which fall through to
                        // anyRequest().authenticated(). No broad /api/users/** matcher exists, so
                        // these two specific GET sub-paths are clean.
                        .requestMatchers(HttpMethod.GET,
                                "/api/users/{userId}/following",
                                "/api/users/{userId}/followers").permitAll()
                        // Feed reads: the public feed (GET /api/feed/public) is permitAll — anonymous
                        // readers see the page with likedByMe/favedByMe left null (the "no overlay
                        // applied" sentinel); a Bearer token, if present, overlays both bits for the
                        // authenticated reader. GET /api/feed/me is deliberately NOT here and falls
                        // through to anyRequest().authenticated() — it is the current user's own feed
                        // (drafts + published, all visibilities). The /api/feed/ prefix is distinct
                        // from /api/posts/, /api/users/, /api/auth/, so no existing matcher conflicts.
                        .requestMatchers(HttpMethod.GET, "/api/feed/public").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
