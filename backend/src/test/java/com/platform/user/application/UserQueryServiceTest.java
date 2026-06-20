package com.platform.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.common.exception.PlatformException;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.dto.UserProfileResponse;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserQueryServiceTest {

    @Test
    void returnsPublicProfileByUserId() {
        UserQueryService service = new UserQueryService(new SingleUserRepository());

        UserProfileResponse response = service.getPublicProfile(1L);

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.email()).isNull();
        assertThat(response.birthday()).isEqualTo(LocalDate.of(1995, 5, 20));
        assertThat(response.profileUpdatedAt()).isEqualTo(SingleUserRepository.UPDATED_AT);
    }

    @Test
    void rejectsUnknownUserId() {
        UserQueryService service = new UserQueryService(new EmptyUserRepository());

        assertThatThrownBy(() -> service.getPublicProfile(99L))
                .isInstanceOf(PlatformException.class)
                .hasMessage("user not found");
    }

    @Test
    void findsAccountByEmail() {
        UserQueryService service = new UserQueryService(new SingleUserRepository());

        UserAccount account = service.findAccountByEmail("alice@example.com");

        assertThat(account.id()).isEqualTo(1L);
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.email()).isEqualTo("alice@example.com");
    }

    @Test
    void findsAccountByPhone() {
        UserQueryService service = new UserQueryService(new SingleUserRepository());

        UserAccount account = service.findAccountByPhone("13800000000");

        assertThat(account.id()).isEqualTo(1L);
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.phone()).isEqualTo("13800000000");
    }

    @Test
    void findsAccountByEmailIsCaseInsensitive() {
        UserQueryService service = new UserQueryService(new SingleUserRepository());

        UserAccount account = service.findAccountByEmail("Alice@EXAMPLE.com");

        assertThat(account.id()).isEqualTo(1L);
        assertThat(account.username()).isEqualTo("alice");
        assertThat(account.email()).isEqualTo("alice@example.com");
    }

    private static class SingleUserRepository implements UserRepository {
        private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 6, 18, 10, 0);
        private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 18, 11, 0);
        private final UserAccount account = new UserAccount(1L, "alice", "alice@example.com", "13800000000",
                "hash", UserStatus.ACTIVE, UserRole.USER, true, false, null, CREATED_AT, UPDATED_AT);
        private final UserProfile profile = new UserProfile(1L, "Alice", "avatar.png", "Engineer", "Shanghai", null,
                LocalDate.of(1995, 5, 20), CREATED_AT, UPDATED_AT);

        @Override
        public UserAccount save(UserAccount account, UserProfile profile) {
            return this.account;
        }

        @Override
        public boolean existsByUsername(String username) {
            return this.account.username().equals(username);
        }

        @Override
        public boolean existsByEmail(String email) {
            return this.account.email().equals(email);
        }

        @Override
        public Optional<UserAccount> findAccountById(Long userId) {
            return userId.equals(account.id()) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<UserAccount> findAccountByUsername(String username) {
            return username.equals(account.username()) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail) {
            return usernameOrEmail.equals(account.username()) || usernameOrEmail.equals(account.email())
                    ? Optional.of(account)
                    : Optional.empty();
        }

        @Override
        public Optional<UserAccount> findAccountByEmail(String email) {
            String normalized = email == null ? null : email.toLowerCase();
            return account.email() != null && account.email().toLowerCase().equals(normalized)
                    ? Optional.of(account)
                    : Optional.empty();
        }

        @Override
        public Optional<UserAccount> findAccountByPhone(String phone) {
            return phone.equals(account.phone()) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<UserProfile> findProfileByUserId(Long userId) {
            return userId.equals(profile.userId()) ? Optional.of(profile) : Optional.empty();
        }

        @Override
        public UserProfile updateProfile(UserProfile profile) {
            return profile;
        }

        @Override
        public void updatePasswordHash(Long userId, String passwordHash) {
        }

        @Override
        public void markEmailVerified(Long userId) {
        }

        @Override
        public void markPhoneVerified(Long userId) {
        }

        @Override
        public void updateLastLoginAt(Long userId) {
        }
    }

    private static class EmptyUserRepository extends SingleUserRepository {
        @Override
        public Optional<UserAccount> findAccountById(Long userId) {
            return Optional.empty();
        }
    }
}
