package com.platform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.auth.domain.TokenPair;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.dto.PasswordLoginRequest;
import com.platform.auth.dto.RegisterRequest;
import com.platform.auth.dto.ResetPasswordRequest;
import com.platform.auth.dto.VerificationCodeLoginRequest;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.user.application.UserCommandService;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure unit test for {@link AuthService}. Mirrors {@link TokenServiceTest}: the service is
 * constructed directly with fakes so it runs under the {@code test} profile where the MySQL/Redis
 * collaborators are absent.
 *
 * <p>Approach (design decision #1):
 * <ul>
 *   <li>{@link VerificationService} and {@link TokenService} are stubbed via anonymous subclasses —
 *       they are {@code @Profile("!test")} concrete classes with rich collaborator sets, so thin
 *       fakes are far lighter than wiring the real ones.</li>
 *   <li>{@link UserCommandService} / {@link UserQueryService} are plain {@code @Service} beans with
 *       a single collaborator ({@link UserRepository}), so they are used <em>real</em>, backed by an
 *       in-memory {@link FakeUserRepository}. This exercises the real duplicate-email/not-found
 *       paths that the login-not-found-conversion relies on.</li>
 *   <li>{@link PasswordEncoder} is a real {@link BCryptPasswordEncoder} so password matching is
 *       exercised end to end.</li>
 * </ul>
 */
class AuthServiceTest {

    private static final LocalDateTime DEFAULT_TIME = LocalDateTime.of(2026, 6, 18, 10, 0);

    private FakeUserRepository userRepository;
    private StubVerificationService verificationService;
    private StubTokenService tokenService;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = new FakeUserRepository();
        verificationService = new StubVerificationService();
        tokenService = new StubTokenService();
        passwordEncoder = new BCryptPasswordEncoder();

        UserCommandService userCommandService = new UserCommandService(userRepository);
        UserQueryService userQueryService = new UserQueryService(userRepository);

        authService = new AuthService(
                verificationService,
                tokenService,
                userCommandService,
                userQueryService,
                passwordEncoder
        );
    }

    @Test
    void registersAndReturnsTokenPair() {
        verificationService.assumeValid();
        RegisterRequest request = registerRequest("alice", "alice@example.com", "password123");

        var response = authService.register(request);

        assertThat(response.tokenPair().accessToken()).isEqualTo(StubTokenService.ACCESS_TOKEN);
        assertThat(response.tokenPair().refreshToken()).isEqualTo(StubTokenService.REFRESH_TOKEN);
        assertThat(response.currentUser().username()).isEqualTo("alice");

        // Verification marks the channel's identity as verified.
        UserAccount stored = userRepository.findAccountByUsername("alice").orElseThrow();
        assertThat(stored.emailVerified()).isTrue();

        // Password is BCrypt-hashed, not stored in plaintext.
        assertThat(stored.passwordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", stored.passwordHash())).isTrue();

        assertThat(tokenService.issuedUserIds).contains(stored.id());
    }

    @Test
    void passwordLoginReturnsTokenPair() {
        seedUser("alice", "alice@example.com", "password123");

        var response = authService.loginWithPassword(
                new PasswordLoginRequest("alice", "password123"));

        assertThat(response.tokenPair().refreshToken()).isEqualTo(StubTokenService.REFRESH_TOKEN);
        assertThat(response.currentUser().username()).isEqualTo("alice");
        assertThat(tokenService.issuedUserIds).hasSize(1);
    }

    @Test
    void passwordLoginRejectsWrongPassword() {
        seedUser("alice", "alice@example.com", "password123");

        assertThatThrownBy(() -> authService.loginWithPassword(
                new PasswordLoginRequest("alice", "wrong-password")))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void passwordLoginConvertsUnknownPrincipalToInvalidCredentials() {
        // No user seeded. Must yield AUTH_INVALID_CREDENTIALS, NOT a leaky "user not found".
        assertThatThrownBy(() -> authService.loginWithPassword(
                new PasswordLoginRequest("ghost", "password123")))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_INVALID_CREDENTIALS)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void verificationCodeLoginReturnsTokenPairForExistingUser() {
        verificationService.assumeValid();
        UserAccount account = seedUser("alice", "alice@example.com", "password123");

        var response = authService.loginWithVerificationCode(new VerificationCodeLoginRequest(
                VerificationChannel.EMAIL, "alice@example.com", "123456"));

        assertThat(response.tokenPair().refreshToken()).isEqualTo(StubTokenService.REFRESH_TOKEN);
        assertThat(response.currentUser().userId()).isEqualTo(account.id());
        assertThat(tokenService.issuedUserIds).contains(account.id());
    }

    @Test
    void verificationCodeLoginRejectsMissingUser() {
        verificationService.assumeValid();
        // No user seeded: must NOT auto-register and must NOT leak USER_NOT_FOUND.
        assertThatThrownBy(() -> authService.loginWithVerificationCode(new VerificationCodeLoginRequest(
                VerificationChannel.EMAIL, "ghost@example.com", "123456")))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_INVALID_CREDENTIALS);

        // And no user account was created.
        assertThat(userRepository.findAccountByEmail("ghost@example.com")).isEmpty();
    }

    @Test
    void resetPasswordRevokesAllRefreshTokens() {
        verificationService.assumeValid();
        UserAccount account = seedUser("alice", "alice@example.com", "password123");
        String originalHash = account.passwordHash();

        authService.resetPassword(new ResetPasswordRequest(
                VerificationChannel.EMAIL, "alice@example.com", "123456", "brand-new-pw"));

        UserAccount stored = userRepository.findAccountById(account.id()).orElseThrow();
        assertThat(stored.passwordHash()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches("brand-new-pw", stored.passwordHash())).isTrue();
        assertThat(tokenService.revokedUserIds).contains(account.id());
    }

    // --- helpers ------------------------------------------------------------------

    private UserAccount seedUser(String username, String email, String password) {
        String hash = passwordEncoder.encode(password);
        return userRepository.save(
                new UserAccount(null, username, email, null, hash, UserStatus.ACTIVE, UserRole.USER,
                        false, false, null, null, null),
                new UserProfile(null, username, null, null, null, null, null, null, null));
    }

    private static RegisterRequest registerRequest(String username, String email, String password) {
        return new RegisterRequest(
                username, email, null, password,
                VerificationChannel.EMAIL, email, "123456");
    }

    /**
     * Minimal stub of the {@code @Profile("!test")} {@link VerificationService}. By default every
     * {@code verifyCode} call throws the production "invalid code" error; {@link #assumeValid()}
     * flips it to a no-op so tests that need to pass verification can do so without Redis.
     */
    private static class StubVerificationService extends VerificationService {
        private boolean valid;

        StubVerificationService() {
            // Collaborators are unused on the overridden path; nulls are safe.
            super(null, null, null, null, null);
        }

        void assumeValid() {
            this.valid = true;
        }

        @Override
        public void verifyCode(VerificationPurpose purpose, VerificationChannel channel,
                               String target, String code) {
            if (!valid) {
                throw new PlatformException(ErrorCode.AUTH_INVALID_VERIFICATION_CODE,
                        "Invalid or expired verification code");
            }
        }

        @Override
        public void sendCode(VerificationPurpose purpose, VerificationChannel channel, String target) {
            // No-op: not exercised by AuthService.
        }
    }

    /**
     * Minimal stub of the {@code @Profile("!test")} {@link TokenService} that records issued and
     * revoked users and returns a fixed token pair.
     */
    private static class StubTokenService extends TokenService {
        static final String ACCESS_TOKEN = "stub-access";
        static final String REFRESH_TOKEN = "stub-refresh";
        private static final TokenPair PAIR = new TokenPair(
                ACCESS_TOKEN, REFRESH_TOKEN, 900L, DEFAULT_TIME.plusDays(14));

        final java.util.List<Long> issuedUserIds = new java.util.ArrayList<>();
        final java.util.List<Long> revokedUserIds = new java.util.ArrayList<>();

        StubTokenService() {
            super(null, null, null, null, null);
        }

        @Override
        public TokenPair issue(Long userId, String username, UserRole role) {
            issuedUserIds.add(userId);
            return PAIR;
        }

        @Override
        public TokenPair refresh(String rawRefreshToken) {
            return PAIR;
        }

        @Override
        public void logout(String rawRefreshToken,
                           com.platform.auth.infrastructure.security.AuthenticatedPrincipal principal) {
            // No-op for unit coverage.
        }

        @Override
        public void revokeAllForUser(Long userId) {
            revokedUserIds.add(userId);
        }
    }

    /**
     * In-memory {@link UserRepository} mirroring the fake in {@code UserCommandServiceTest}, plus
     * tracking of issued/revoked state.
     */
    private static final class FakeUserRepository implements UserRepository {
        private final Map<Long, UserAccount> accounts = new HashMap<>();
        private final Map<Long, UserProfile> profiles = new HashMap<>();
        private long nextId = 1L;

        @Override
        public UserAccount save(UserAccount account, UserProfile profile) {
            UserAccount saved = new UserAccount(nextId++, account.username(), account.email(),
                    account.phone(), account.passwordHash(), account.status(), account.role(),
                    false, false, null, DEFAULT_TIME, DEFAULT_TIME);
            accounts.put(saved.id(), saved);
            profiles.put(saved.id(), new UserProfile(saved.id(), profile.displayName(), profile.avatarUrl(),
                    profile.bio(), profile.location(), profile.website(), profile.birthday(),
                    DEFAULT_TIME, DEFAULT_TIME));
            return saved;
        }

        @Override
        public boolean existsByUsername(String username) {
            return accounts.values().stream().anyMatch(a -> a.username().equals(username));
        }

        @Override
        public boolean existsByEmail(String email) {
            return accounts.values().stream().anyMatch(a -> a.email().equalsIgnoreCase(email));
        }

        @Override
        public Optional<UserAccount> findAccountById(Long userId) {
            return Optional.ofNullable(accounts.get(userId));
        }

        @Override
        public Optional<UserAccount> findAccountByUsername(String username) {
            return accounts.values().stream().filter(a -> a.username().equals(username)).findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail) {
            return accounts.values().stream()
                    .filter(a -> a.username().equals(usernameOrEmail) || a.email().equalsIgnoreCase(usernameOrEmail))
                    .findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByEmail(String email) {
            String normalized = email == null ? null : email.toLowerCase();
            return accounts.values().stream()
                    .filter(a -> a.email() != null && a.email().equalsIgnoreCase(normalized))
                    .findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByPhone(String phone) {
            return accounts.values().stream().filter(a -> phone != null && phone.equals(a.phone())).findFirst();
        }

        @Override
        public Optional<UserProfile> findProfileByUserId(Long userId) {
            return Optional.ofNullable(profiles.get(userId));
        }

        @Override
        public UserProfile updateProfile(UserProfile profile) {
            profiles.put(profile.userId(), profile);
            return profile;
        }

        @Override
        public void updatePasswordHash(Long userId, String passwordHash) {
            UserAccount e = accounts.get(userId);
            accounts.put(userId, copy(e, e.username(), e.email(), e.phone(), passwordHash, e.status(),
                    e.role(), e.emailVerified(), e.phoneVerified(), e.lastLoginAt()));
        }

        @Override
        public void markEmailVerified(Long userId) {
            UserAccount e = accounts.get(userId);
            accounts.put(userId, copy(e, e.username(), e.email(), e.phone(), e.passwordHash(), e.status(),
                    e.role(), true, e.phoneVerified(), e.lastLoginAt()));
        }

        @Override
        public void markPhoneVerified(Long userId) {
            UserAccount e = accounts.get(userId);
            accounts.put(userId, copy(e, e.username(), e.email(), e.phone(), e.passwordHash(), e.status(),
                    e.role(), e.emailVerified(), true, e.lastLoginAt()));
        }

        @Override
        public void updateLastLoginAt(Long userId) {
            UserAccount e = accounts.get(userId);
            accounts.put(userId, copy(e, e.username(), e.email(), e.phone(), e.passwordHash(), e.status(),
                    e.role(), e.emailVerified(), e.phoneVerified(), DEFAULT_TIME));
        }

        private static UserAccount copy(UserAccount e, String username, String email, String phone,
                                        String passwordHash, UserStatus status, UserRole role,
                                        boolean emailVerified, boolean phoneVerified, LocalDateTime lastLoginAt) {
            return new UserAccount(e.id(), username, email, phone, passwordHash, status, role,
                    emailVerified, phoneVerified, lastLoginAt, e.createdAt(), e.updatedAt());
        }

        // Silence unused-param warnings for the LocalDate-bearing profile constructor reference.
        @SuppressWarnings("unused")
        private LocalDate unused() {
            return null;
        }
    }
}
