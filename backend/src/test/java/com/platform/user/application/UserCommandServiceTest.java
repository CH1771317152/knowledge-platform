package com.platform.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.common.exception.PlatformException;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.dto.CreateUserCommand;
import com.platform.user.dto.UpdateUserProfileCommand;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserCommandServiceTest {

    private final FakeUserRepository repository = new FakeUserRepository();
    private final UserCommandService service = new UserCommandService(repository);

    @Test
    void createsUserAccountAndDefaultProfile() {
        CreateUserCommand command = new CreateUserCommand("alice", "alice@example.com", null, "hash");

        UserAccount created = service.createUser(command);

        assertThat(created.id()).isEqualTo(1L);
        assertThat(created.username()).isEqualTo("alice");
        assertThat(created.email()).isEqualTo("alice@example.com");
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.role()).isEqualTo(UserRole.USER);
        assertThat(created.emailVerified()).isFalse();
        assertThat(created.phoneVerified()).isFalse();
        assertThat(created.createdAt()).isEqualTo(FakeUserRepository.DEFAULT_TIME);
        assertThat(created.updatedAt()).isEqualTo(FakeUserRepository.DEFAULT_TIME);
        assertThat(repository.findProfileByUserId(1L))
                .contains(new UserProfile(1L, "alice", null, null, null, null, null,
                        FakeUserRepository.DEFAULT_TIME, FakeUserRepository.DEFAULT_TIME));
    }

    @Test
    void rejectsDuplicateUsername() {
        service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        assertThatThrownBy(() -> service.createUser(new CreateUserCommand("alice", "alice2@example.com", null, "hash")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("username already exists");
    }

    @Test
    void rejectsDuplicateEmail() {
        service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        assertThatThrownBy(() -> service.createUser(new CreateUserCommand("bob", "alice@example.com", null, "hash")))
                .isInstanceOf(PlatformException.class)
                .hasMessage("email already exists");
    }

    @Test
    void updatesProfileFields() {
        UserAccount created = service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        UserProfile updated = service.updateProfile(created.id(),
                new UpdateUserProfileCommand("Alice", "https://cdn.example.com/a.png", "Engineer", "Shanghai",
                        "https://alice.example.com", LocalDate.of(1995, 5, 20)));

        assertThat(updated).isEqualTo(new UserProfile(created.id(), "Alice", "https://cdn.example.com/a.png",
                "Engineer", "Shanghai", "https://alice.example.com", LocalDate.of(1995, 5, 20),
                FakeUserRepository.DEFAULT_TIME, FakeUserRepository.DEFAULT_TIME));
    }

    @Test
    void updatesPasswordHash() {
        UserAccount created = service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        service.updatePasswordHash(created.id(), "new-hash");

        UserAccount stored = repository.findAccountById(created.id()).orElseThrow();
        assertThat(stored.passwordHash()).isEqualTo("new-hash");
    }

    @Test
    void marksEmailAsVerified() {
        UserAccount created = service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        service.markVerified(created.id(), true, false);

        UserAccount stored = repository.findAccountById(created.id()).orElseThrow();
        assertThat(stored.emailVerified()).isTrue();
        assertThat(stored.phoneVerified()).isFalse();
    }

    @Test
    void marksPhoneAsVerified() {
        UserAccount created = service.createUser(new CreateUserCommand("alice", "alice@example.com", "13800000000", "hash"));

        service.markVerified(created.id(), false, true);

        UserAccount stored = repository.findAccountById(created.id()).orElseThrow();
        assertThat(stored.emailVerified()).isFalse();
        assertThat(stored.phoneVerified()).isTrue();
    }

    @Test
    void recordsSuccessfulLogin() {
        UserAccount created = service.createUser(new CreateUserCommand("alice", "alice@example.com", null, "hash"));

        service.recordSuccessfulLogin(created.id());

        UserAccount stored = repository.findAccountById(created.id()).orElseThrow();
        assertThat(stored.lastLoginAt()).isEqualTo(FakeUserRepository.DEFAULT_TIME);
    }

    private static class FakeUserRepository implements UserRepository {
        private static final LocalDateTime DEFAULT_TIME = LocalDateTime.of(2026, 6, 18, 10, 0);
        private final Map<Long, UserAccount> accounts = new HashMap<>();
        private final Map<Long, UserProfile> profiles = new HashMap<>();
        private long nextId = 1L;

        @Override
        public UserAccount save(UserAccount account, UserProfile profile) {
            UserAccount saved = new UserAccount(nextId++, account.username(), account.email(), account.phone(),
                    account.passwordHash(), account.status(), account.role(), false, false, null,
                    DEFAULT_TIME, DEFAULT_TIME);
            accounts.put(saved.id(), saved);
            profiles.put(saved.id(), new UserProfile(saved.id(), profile.displayName(), profile.avatarUrl(),
                    profile.bio(), profile.location(), profile.website(), profile.birthday(),
                    DEFAULT_TIME, DEFAULT_TIME));
            return saved;
        }

        @Override
        public boolean existsByUsername(String username) {
            return accounts.values().stream().anyMatch(account -> account.username().equals(username));
        }

        @Override
        public boolean existsByEmail(String email) {
            return accounts.values().stream().anyMatch(account -> account.email().equals(email));
        }

        @Override
        public Optional<UserAccount> findAccountById(Long userId) {
            return Optional.ofNullable(accounts.get(userId));
        }

        @Override
        public Optional<UserAccount> findAccountByUsername(String username) {
            return accounts.values().stream().filter(account -> account.username().equals(username)).findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail) {
            return accounts.values().stream()
                    .filter(account -> account.username().equals(usernameOrEmail) || account.email().equals(usernameOrEmail))
                    .findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByEmail(String email) {
            String normalized = email == null ? null : email.toLowerCase();
            return accounts.values().stream()
                    .filter(account -> account.email() != null && account.email().toLowerCase().equals(normalized))
                    .findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByPhone(String phone) {
            return accounts.values().stream()
                    .filter(account -> phone.equals(account.phone()))
                    .findFirst();
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
            UserAccount existing = accounts.get(userId);
            accounts.put(userId, new UserAccount(existing.id(), existing.username(), existing.email(),
                    existing.phone(), passwordHash, existing.status(), existing.role(), existing.emailVerified(),
                    existing.phoneVerified(), existing.lastLoginAt(), existing.createdAt(), existing.updatedAt()));
        }

        @Override
        public void markEmailVerified(Long userId) {
            UserAccount existing = accounts.get(userId);
            accounts.put(userId, new UserAccount(existing.id(), existing.username(), existing.email(),
                    existing.phone(), existing.passwordHash(), existing.status(), existing.role(), true,
                    existing.phoneVerified(), existing.lastLoginAt(), existing.createdAt(), existing.updatedAt()));
        }

        @Override
        public void markPhoneVerified(Long userId) {
            UserAccount existing = accounts.get(userId);
            accounts.put(userId, new UserAccount(existing.id(), existing.username(), existing.email(),
                    existing.phone(), existing.passwordHash(), existing.status(), existing.role(), existing.emailVerified(),
                    true, existing.lastLoginAt(), existing.createdAt(), existing.updatedAt()));
        }

        @Override
        public void updateLastLoginAt(Long userId) {
            UserAccount existing = accounts.get(userId);
            accounts.put(userId, new UserAccount(existing.id(), existing.username(), existing.email(),
                    existing.phone(), existing.passwordHash(), existing.status(), existing.role(), existing.emailVerified(),
                    existing.phoneVerified(), DEFAULT_TIME, existing.createdAt(), existing.updatedAt()));
        }
    }
}
