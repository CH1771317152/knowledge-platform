package com.platform.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class MysqlUserRepositoryIntegrationTest {

    private static final String USERNAME = "it_user_mysql";
    private static final String EMAIL = "it_user_mysql@example.com";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("""
                DELETE FROM user_profile
                WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("DELETE FROM user_account WHERE username = ? OR email = ?", USERNAME, EMAIL);
    }

    @Test
    void savesFindsAndUpdatesUserThroughMysql() {
        UserAccount account = new UserAccount(null, USERNAME, EMAIL, "13800000000", "hash",
                UserStatus.ACTIVE, UserRole.USER, false, false, null, null, null);
        UserProfile profile = new UserProfile(null, "Integration User", null, "bio", "Shanghai",
                "https://example.com", LocalDate.of(1995, 5, 20), null, null);

        UserAccount saved = userRepository.save(account, profile);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(saved.emailVerified()).isFalse();
        assertThat(saved.phoneVerified()).isFalse();

        assertThat(userRepository.existsByUsername(USERNAME)).isTrue();
        assertThat(userRepository.existsByEmail(EMAIL)).isTrue();
        assertThat(userRepository.findAccountByUsername(USERNAME)).contains(saved);
        assertThat(userRepository.findAccountByUsernameOrEmail(EMAIL)).contains(saved);

        UserProfile persistedProfile = userRepository.findProfileByUserId(saved.id()).orElseThrow();
        assertThat(persistedProfile.displayName()).isEqualTo("Integration User");
        assertThat(persistedProfile.birthday()).isEqualTo(LocalDate.of(1995, 5, 20));
        assertThat(persistedProfile.createdAt()).isNotNull();
        assertThat(persistedProfile.updatedAt()).isNotNull();

        UserProfile updated = userRepository.updateProfile(new UserProfile(saved.id(), "Updated User",
                "https://cdn.example.com/avatar.png", "updated bio", "Beijing", "https://updated.example.com",
                LocalDate.of(1996, 6, 21), persistedProfile.createdAt(), persistedProfile.updatedAt()));

        assertThat(updated.displayName()).isEqualTo("Updated User");
        assertThat(userRepository.findProfileByUserId(saved.id()).orElseThrow())
                .extracting(UserProfile::displayName, UserProfile::birthday)
                .containsExactly("Updated User", LocalDate.of(1996, 6, 21));

        userRepository.markEmailVerified(saved.id());
        userRepository.markPhoneVerified(saved.id());
        userRepository.updatePasswordHash(saved.id(), "new-hash");
        userRepository.updateLastLoginAt(saved.id());

        UserAccount reloaded = userRepository.findAccountById(saved.id()).orElseThrow();
        assertThat(reloaded.emailVerified()).isTrue();
        assertThat(reloaded.phoneVerified()).isTrue();
        assertThat(reloaded.passwordHash()).isEqualTo("new-hash");
        assertThat(reloaded.lastLoginAt()).isNotNull();
    }
}
