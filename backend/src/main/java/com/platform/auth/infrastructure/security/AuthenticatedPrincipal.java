package com.platform.auth.infrastructure.security;

import com.platform.user.domain.UserRole;

/**
 * Principal attached to the {@code SecurityContext} after a JWT passes the
 * {@code JwtAuthenticationFilter}. Derived from verified access-token claims.
 *
 * @param userId   subject of the access token
 * @param username username claim, for audit/logging
 * @param role     role claim, mapped to a granted authority
 * @param jti      access-token id; used to consult the blacklist on each request and at logout
 */
public record AuthenticatedPrincipal(Long userId, String username, UserRole role, String jti) {
}
