package com.platform.auth.application;

import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Reads the authenticated principal established by {@code JwtAuthenticationFilter} from the
 * {@code SecurityContext}. Controllers handling authenticated endpoints (logout, /me) use this
 * instead of repeating the {@code SecurityContextHolder} lookup + cast.
 *
 * <p>Not {@code @Profile}-gated: it only touches {@code SecurityContextHolder}, which is always
 * available. Under the {@code test} profile {@code AuthService} (the main caller's collaborator set)
 * is absent, but this bean is harmless on its own.
 */
@Service
public class CurrentUserService {

    /**
     * @return the authenticated principal; never null
     * @throws PlatformException {@link ErrorCode#COMMON_UNAUTHORIZED} when no authenticated user is
     *         present (the request reached this point without a valid access token)
     */
    public AuthenticatedPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            throw new PlatformException(ErrorCode.COMMON_UNAUTHORIZED, "Authentication required");
        }
        return principal;
    }
}
