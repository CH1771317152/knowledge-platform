package com.platform.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.auth.domain.AuthRefreshToken;
import com.platform.auth.repository.AuthRefreshTokenRepository;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class MysqlAuthRefreshTokenRepositoryIntegrationTest {

    private static final String USERNAME = "it_refresh_user";
    private static final String EMAIL = "it_refresh_user@example.com";
    private static final String PHONE = "13700000007";

    @Autowired
    private AuthRefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void cleanAndCreateUser() {
        jdbcTemplate.update("""
                DELETE FROM auth_refresh_token
                WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("""
                DELETE FROM user_profile
                WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR email = ?)
                """, USERNAME, EMAIL);
        jdbcTemplate.update("DELETE FROM user_account WHERE username = ? OR email = ?", USERNAME, EMAIL);

        UserAccount account = new UserAccount(null, USERNAME, EMAIL, PHONE, "hash",
                UserStatus.ACTIVE, UserRole.USER, false, false, null, null, null);
        UserProfile profile = new UserProfile(null, "Refresh User", null, "bio", "Shanghai",
                "https://example.com", LocalDate.of(1995, 5, 20), null, null);
        UserAccount saved = userRepository.save(account, profile);
        this.userId = saved.id();
    }

    @Test
    void savesFindsRevokesAndRevokesAllByUser() {
        LocalDateTime now = LocalDateTime.now();

        AuthRefreshToken token = new AuthRefreshToken(
                null, userId, "hash-refresh-1", "jti-refresh-1", "device-1", "UA-1", "127.0.0.1",
                now.plusDays(7), null, null, null, null);
        AuthRefreshToken saved = refreshTokenRepository.save(token);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(saved.tokenHash()).isEqualTo("hash-refresh-1");

        AuthRefreshToken found = refreshTokenRepository.findByTokenHash("hash-refresh-1").orElseThrow();
        assertThat(found.userId()).isEqualTo(userId);
        assertThat(found.isRevoked()).isFalse();

        refreshTokenRepository.revoke(found.id(), now.minusSeconds(1), null);
        AuthRefreshToken revoked = refreshTokenRepository.findByTokenHash("hash-refresh-1").orElseThrow();
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.replacedByTokenId()).isNull();

        AuthRefreshToken token2 = new AuthRefreshToken(
                null, userId, "hash-refresh-2", "jti-refresh-2", "device-2", "UA-2", "10.0.0.1",
                now.plusDays(7), null, null, null, null);
        AuthRefreshToken saved2 = refreshTokenRepository.save(token2);
        assertThat(saved2.id()).isNotNull();

        Long firstId = saved.id();
        AuthRefreshToken found1 = refreshTokenRepository.findByTokenHash("hash-refresh-1").orElseThrow();
        assertThat(found1.isRevoked()).isTrue();

        refreshTokenRepository.revokeAllByUserId(userId, now);

        AuthRefreshToken reloaded1 = refreshTokenRepository.findByTokenHash("hash-refresh-1").orElseThrow();
        AuthRefreshToken reloaded2 = refreshTokenRepository.findByTokenHash("hash-refresh-2").orElseThrow();
        assertThat(reloaded1.revokedAt()).isNotNull();
        assertThat(reloaded2.revokedAt()).isNotNull();

        // The already-revoked first token's timestamp should be unchanged.
        assertThat(reloaded1.revokedAt()).isEqualTo(found1.revokedAt());
        assertThat(firstId).isNotNull();
    }

    @Test
    void revokePersistsReplacedByTokenId() {
        LocalDateTime now = LocalDateTime.now();

        AuthRefreshToken first = new AuthRefreshToken(
                null, userId, "hash-rotation-1", "jti-rotation-1", "device-1", "UA-1", "127.0.0.1",
                now.plusDays(7), null, null, null, null);
        AuthRefreshToken savedFirst = refreshTokenRepository.save(first);
        Long firstId = savedFirst.id();

        AuthRefreshToken second = new AuthRefreshToken(
                null, userId, "hash-rotation-2", "jti-rotation-2", "device-1", "UA-1", "127.0.0.1",
                now.plusDays(7), null, null, null, null);
        AuthRefreshToken savedSecond = refreshTokenRepository.save(second);
        Long secondId = savedSecond.id();

        LocalDateTime revokedAt = now.minusSeconds(1);
        refreshTokenRepository.revoke(firstId, revokedAt, secondId);

        AuthRefreshToken reloadedFirst = refreshTokenRepository.findByTokenHash("hash-rotation-1").orElseThrow();
        assertThat(reloadedFirst.revokedAt()).isNotNull();
        assertThat(reloadedFirst.replacedByTokenId()).isEqualTo(secondId);

        // Sanity: the second (replacing) token is still active and unlinked.
        AuthRefreshToken reloadedSecond = refreshTokenRepository.findByTokenHash("hash-rotation-2").orElseThrow();
        assertThat(reloadedSecond.isRevoked()).isFalse();
        assertThat(reloadedSecond.replacedByTokenId()).isNull();
        assertThat(secondId).isNotEqualTo(firstId);
    }
}
