package com.platform.auth.application;

import com.platform.auth.domain.TokenPair;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.dto.AuthResponse;
import com.platform.auth.dto.RegisterRequest;
import com.platform.auth.dto.ResetPasswordRequest;
import com.platform.auth.dto.VerificationCodeLoginRequest;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.application.UserCommandService;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserStatus;
import com.platform.user.dto.CreateUserCommand;
import com.platform.user.dto.UserProfileResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates authentication use cases by tying together {@link VerificationService} (Task 4),
 * {@link TokenService} (Task 5), and the user account services ({@link UserCommandService} /
 * {@link UserQueryService}, Task 2), plus BCrypt password hashing.
 *
 * <p>{@code @Profile("!test")} mirrors {@link VerificationService} / {@link TokenService}: those
 * beans and their MySQL/Redis dependencies are absent under the {@code test} profile, so this
 * service must be excluded too to keep {@code PlatformApplicationTests.contextLoads} and
 * {@code AuthPropertiesTest} green. The accompanying unit test constructs it directly with fakes.
 *
 * <p><b>Login security:</b> password and verification-code login never distinguish "user not found"
 * from "wrong credentials" — both surface {@link ErrorCode#AUTH_INVALID_CREDENTIALS} with an
 * identical message, so a probe cannot enumerate accounts.
 */
@Service
@Profile("!test")
public class AuthService {

    /**
     * Precomputed BCrypt hash consumed ONLY on the password-login "user not found" branch. Its sole
     * purpose is to spend BCrypt CPU time so that branch takes roughly the same time as the
     * "wrong password" branch (which runs a real BCrypt verify), closing a timing side-channel that
     * would otherwise let a probe distinguish a missing account from a wrong password. This hash
     * matches no real credential and is a well-known public BCrypt digest.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final VerificationService verificationService;
    private final TokenService tokenService;
    private final UserCommandService userCommandService;
    private final UserQueryService userQueryService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(VerificationService verificationService,
                       TokenService tokenService,
                       UserCommandService userCommandService,
                       UserQueryService userQueryService,
                       PasswordEncoder passwordEncoder) {
        this.verificationService = verificationService;
        this.tokenService = tokenService;
        this.userCommandService = userCommandService;
        this.userQueryService = userQueryService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a new user after verifying the out-of-band code, issues the first token pair.
     *
     * <p><b>Partial-failure tradeoff (Redis/MySQL):</b> {@code verifyCode} consumes the verification
     * code in Redis via a non-transactional {@code DEL} <em>inside</em> this MySQL
     * {@code @Transactional} method. If {@code createUser} then fails — e.g. a duplicate username or
     * email — the MySQL transaction rolls back but the Redis code is already deleted. The user must
     * request a fresh verification code before retrying. This is an accepted tradeoff: duplicate
     * registration is rare and resend is cheap, so the extra complexity of a Redis compensating
     * action (or moving the DEL after a flush) is not warranted. Same caveat applies to
     * {@link #resetPassword(ResetPasswordRequest)}.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        verificationService.verifyCode(
                VerificationPurpose.REGISTER,
                request.verificationChannel(),
                request.verificationTarget(),
                request.verificationCode());

        String passwordHash = passwordEncoder.encode(request.password());

        // createUser throws message-only PlatformException (COMMON_BAD_REQUEST) on duplicate
        // username/email today; we let it surface unchanged. See Task 6 report.
        UserAccount account = userCommandService.createUser(new CreateUserCommand(
                request.username(), request.email(), request.phone(), passwordHash));

        boolean emailVerified = request.verificationChannel() == VerificationChannel.EMAIL;
        boolean phoneVerified = request.verificationChannel() == VerificationChannel.SMS
                && account.phone() != null;
        userCommandService.markVerified(account.id(), emailVerified, phoneVerified);

        TokenPair pair = tokenService.issue(account.id(), account.username(), account.role());
        UserProfileResponse profile = userQueryService.getCurrentUser(account.id());
        return new AuthResponse(pair, profile);
    }

    public AuthResponse loginWithPassword(com.platform.auth.dto.PasswordLoginRequest request) {
        UserAccount account;
        try {
            account = userQueryService.findAccountByUsernameOrEmail(request.principal());
        } catch (PlatformException ex) {
            // findAccountByUsernameOrEmail throws a "user not found" PlatformException when absent.
            // Convert to AUTH_INVALID_CREDENTIALS so login never leaks account existence. Run a
            // dummy BCrypt verify first so this branch pays the same BCrypt CPU cost as the
            // "wrong password" branch below, closing a timing side-channel. Result is discarded.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw invalidCredentials();
        }
        ensureCanLogin(account);

        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw invalidCredentials();
        }

        userCommandService.recordSuccessfulLogin(account.id());
        TokenPair pair = tokenService.issue(account.id(), account.username(), account.role());
        UserProfileResponse profile = userQueryService.getCurrentUser(account.id());
        return new AuthResponse(pair, profile);
    }

    public AuthResponse loginWithVerificationCode(VerificationCodeLoginRequest request) {
        verificationService.verifyCode(
                VerificationPurpose.LOGIN,
                request.channel(),
                request.target(),
                request.verificationCode());

        UserAccount account;
        try {
            account = findAccountByChannel(request.channel(), request.target());
        } catch (PlatformException ex) {
            // First version: existing users only. Unknown target = invalid credentials, never
            // auto-register and never leak USER_NOT_FOUND.
            throw invalidCredentials();
        }
        ensureCanLogin(account);

        userCommandService.recordSuccessfulLogin(account.id());
        TokenPair pair = tokenService.issue(account.id(), account.username(), account.role());
        UserProfileResponse profile = userQueryService.getCurrentUser(account.id());
        return new AuthResponse(pair, profile);
    }

    public TokenPair refresh(String refreshToken) {
        return tokenService.refresh(refreshToken);
    }

    public void logout(String refreshToken, AuthenticatedPrincipal principal) {
        tokenService.logout(refreshToken, principal);
    }

    /**
     * Resets a user's password after verifying the out-of-band code, then revokes every active
     * refresh-token session for that user.
     *
     * <p><b>Partial-failure tradeoff (Redis/MySQL):</b> same as {@link #register(RegisterRequest)} —
     * the Redis verification code is consumed by a non-transactional {@code DEL} inside this MySQL
     * {@code @Transactional} method. If {@code updatePasswordHash} fails afterward, the MySQL
     * transaction rolls back but the Redis code is gone, so the user must request a new code.
     * Accepted tradeoff; not refactored.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        verificationService.verifyCode(
                VerificationPurpose.RESET_PASSWORD,
                request.channel(),
                request.target(),
                request.verificationCode());

        UserAccount account;
        try {
            account = findAccountByChannel(request.channel(), request.target());
        } catch (PlatformException ex) {
            throw invalidCredentials();
        }

        String newPasswordHash = passwordEncoder.encode(request.newPassword());
        userCommandService.updatePasswordHash(account.id(), newPasswordHash);
        // End every active refresh-token session so a stolen refresh token can no longer mint new
        // access tokens after the password reset.
        tokenService.revokeAllForUser(account.id());
    }

    public UserProfileResponse currentUser(AuthenticatedPrincipal principal) {
        return userQueryService.getCurrentUser(principal.userId());
    }

    private void ensureCanLogin(UserAccount account) {
        if (account.status() != UserStatus.ACTIVE) {
            throw new PlatformException(ErrorCode.AUTH_ACCOUNT_DISABLED, "Account is not active");
        }
    }

    private UserAccount findAccountByChannel(VerificationChannel channel, String target) {
        return channel == VerificationChannel.EMAIL
                ? userQueryService.findAccountByEmail(target)
                : userQueryService.findAccountByPhone(target);
    }

    private static PlatformException invalidCredentials() {
        return new PlatformException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
    }
}
