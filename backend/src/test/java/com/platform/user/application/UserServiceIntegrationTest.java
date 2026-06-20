package com.platform.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.dto.CreateUserCommand;
import com.platform.user.dto.UpdateUserProfileCommand;
import com.platform.user.dto.UserProfileResponse;
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
class UserServiceIntegrationTest {

    private static final String USERNAME = "it_user_service";
    private static final String EMAIL = "it_user_service@example.com";

    @Autowired
    private UserCommandService userCommandService;

    @Autowired
    private UserQueryService userQueryService;

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
    void createsQueriesAndUpdatesUserWithRealMysqlStorage() {
        UserAccount created = userCommandService.createUser(
                new CreateUserCommand(USERNAME, EMAIL, "13800000001", "hash"));

        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.role()).isEqualTo(UserRole.USER);
        assertThat(created.emailVerified()).isFalse();
        assertThat(created.phoneVerified()).isFalse();

        UserProfileResponse publicProfile = userQueryService.getPublicProfile(created.id());
        assertThat(publicProfile.username()).isEqualTo(USERNAME);
        assertThat(publicProfile.email()).isNull();
        assertThat(publicProfile.displayName()).isEqualTo(USERNAME);

        userCommandService.updateProfile(created.id(), new UpdateUserProfileCommand(
                "Service User",
                "https://cdn.example.com/service.png",
                "Real MySQL service test",
                "Shanghai",
                "https://service.example.com",
                LocalDate.of(1997, 7, 22)
        ));

        UserProfileResponse currentUser = userQueryService.getCurrentUser(created.id());
        assertThat(currentUser.email()).isEqualTo(EMAIL);
        assertThat(currentUser.displayName()).isEqualTo("Service User");
        assertThat(currentUser.birthday()).isEqualTo(LocalDate.of(1997, 7, 22));

        UserProfileResponse byUsername = userQueryService.getPublicProfileByUsername(USERNAME);
        assertThat(byUsername.userId()).isEqualTo(created.id());
        assertThat(byUsername.email()).isNull();
        assertThat(byUsername.location()).isEqualTo("Shanghai");
    }
}
