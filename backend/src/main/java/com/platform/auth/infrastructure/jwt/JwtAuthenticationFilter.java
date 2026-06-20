package com.platform.auth.infrastructure.jwt;

import com.platform.auth.infrastructure.redis.RedisTokenBlacklist;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts and validates the {@code Authorization: Bearer <access-token>} header on every request.
 *
 * <p>Behavior:
 * <ul>
 *   <li>No header → anonymous; continue the chain (public endpoints serve; protected endpoints
 *       trigger the {@link JwtAuthenticationEntryPoint}).</li>
 *   <li>Invalid/expired token → the parse exception is stashed on the request as attribute
 *       {@link #ATTR_AUTH_ERROR} and authentication is NOT set. The request continues; for a
 *       protected endpoint Spring's {@code ExceptionTranslationFilter} invokes the entry point,
 *       which writes a 401 (never a 500).</li>
 *   <li>Valid token whose jti is blacklisted (logout) → treated like an invalid token (no
 *       authentication → 401 via entry point).</li>
 *   <li>Valid, non-blacklisted token → a populated {@code Authentication} is placed in the
 *       {@code SecurityContext}.</li>
 * </ul>
 *
 * <p>The blacklist is injected via {@code ObjectProvider} so the filter bean still loads under the
 * {@code test} profile, where {@code RedisTokenBlacklist} (a {@code @Profile("!test")} bean) is
 * absent; in that case the blacklist check is simply skipped.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTH_ERROR = "platform.auth.error";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectProvider<RedisTokenBlacklist> tokenBlacklistProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   ObjectProvider<RedisTokenBlacklist> tokenBlacklistProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistProvider = tokenBlacklistProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        AuthenticatedPrincipal principal;
        try {
            principal = jwtTokenProvider.parseAccessToken(token);
        } catch (PlatformException ex) {
            // Stash for the entry point / handlers and proceed unauthenticated. A protected
            // endpoint then yields 401 via the authentication entry point, not 500.
            request.setAttribute(ATTR_AUTH_ERROR, ex);
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        RedisTokenBlacklist blacklist = tokenBlacklistProvider.getIfAvailable();
        if (blacklist != null && blacklist.isBlacklisted(principal.jti())) {
            request.setAttribute(ATTR_AUTH_ERROR, new PlatformException(
                    ErrorCode.AUTH_TOKEN_INVALID, "Access token has been revoked"));
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = toAuthentication(principal);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private static Authentication toAuthentication(AuthenticatedPrincipal principal) {
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
