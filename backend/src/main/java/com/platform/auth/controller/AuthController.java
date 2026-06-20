package com.platform.auth.controller;

import com.platform.auth.application.AuthService;
import com.platform.auth.application.CurrentUserService;
import com.platform.auth.domain.TokenPair;
import com.platform.auth.dto.AuthResponse;
import com.platform.auth.dto.LogoutRequest;
import com.platform.auth.dto.PasswordLoginRequest;
import com.platform.auth.dto.RefreshTokenRequest;
import com.platform.auth.dto.RegisterRequest;
import com.platform.auth.dto.ResetPasswordRequest;
import com.platform.auth.dto.VerificationCodeLoginRequest;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for authentication use cases. All paths are under {@code /api/auth/**}, which
 * {@code SecurityConfig} marks {@code permitAll} — registration, login, token refresh, password
 * reset, and verification-code sending are reachable without a bearer token. The {@code /me} and
 * {@code logout} endpoints require an authenticated user; they read the
 * {@link AuthenticatedPrincipal} placed on the {@code SecurityContext} by
 * {@code JwtAuthenticationFilter}.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("!test")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login/password")
    public ApiResponse<AuthResponse> loginWithPassword(@Valid @RequestBody PasswordLoginRequest request) {
        return ApiResponse.ok(authService.loginWithPassword(request));
    }

    @PostMapping("/login/verification-code")
    public ApiResponse<AuthResponse> loginWithVerificationCode(
            @Valid @RequestBody VerificationCodeLoginRequest request) {
        return ApiResponse.ok(authService.loginWithVerificationCode(request));
    }

    @PostMapping("/token/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        AuthenticatedPrincipal principal = currentUserService.requirePrincipal();
        authService.logout(request.refreshToken(), principal);
        return ApiResponse.ok(null);
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me() {
        AuthenticatedPrincipal principal = currentUserService.requirePrincipal();
        return ApiResponse.ok(authService.currentUser(principal));
    }
}
