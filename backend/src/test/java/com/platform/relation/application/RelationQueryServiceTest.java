package com.platform.relation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.dto.FollowRelationResponse;
import com.platform.relation.dto.FollowUserResponse;
import com.platform.relation.repository.RelationRepository;
import com.platform.relation.event.RelationEventPayload;
import com.platform.user.application.UserQueryService;
import com.platform.user.domain.UserAccount;
import com.platform.user.domain.UserProfile;
import com.platform.user.domain.UserRole;
import com.platform.user.domain.UserStatus;
import com.platform.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link RelationQueryService}. Constructed directly with an in-memory
 * {@link FakeRelationRepository} (which records the limit/offset passed to the list methods) and a
 * real {@link UserQueryService} backed by a fake {@link FakeUserRepository}. Mirrors
 * {@code ContentCommandServiceTest} so it runs under the {@code test} profile.
 */
class RelationQueryServiceTest {

    private static final Long ME = 7L;
    private static final Long TARGET = 42L;

    private FakeRelationRepository relationRepository;
    private FakeUserRepository userRepository;
    private RelationQueryService service;

    @BeforeEach
    void setUp() {
        relationRepository = new FakeRelationRepository();
        userRepository = new FakeUserRepository();
        seedUser(ME, "me");
        seedUser(TARGET, "target");
        service = new RelationQueryService(relationRepository, new UserQueryService(userRepository));
    }

    // --- getRelation ----------------------------------------------------------

    @Test
    void getRelationReturnsFollowingWhenActive() {
        LocalDateTime followedAt = LocalDateTime.of(2026, 6, 19, 9, 0);
        relationRepository.insertFollowing(ME, TARGET, FollowStatus.ACTIVE, followedAt);

        FollowRelationResponse response = service.getRelation(ME, TARGET);

        assertThat(response.following()).isTrue();
        assertThat(response.currentUserId()).isEqualTo(ME);
        assertThat(response.targetUserId()).isEqualTo(TARGET);
        assertThat(response.followedAt()).isEqualTo(followedAt);
    }

    @Test
    void getRelationReturnsNotFollowingWhenCanceled() {
        relationRepository.seedCanceled(ME, TARGET, LocalDateTime.now());

        FollowRelationResponse response = service.getRelation(ME, TARGET);

        assertThat(response.following()).isFalse();
        assertThat(response.followedAt()).isNull();
    }

    @Test
    void getRelationReturnsNotFollowingWhenAbsent() {
        FollowRelationResponse response = service.getRelation(ME, TARGET);

        assertThat(response.following()).isFalse();
        assertThat(response.followedAt()).isNull();
    }

    @Test
    void getRelationReturnsNotFollowingForSelf() {
        // Reads do not reject self; current==target simply reports following=false.
        FollowRelationResponse response = service.getRelation(ME, ME);

        assertThat(response.following()).isFalse();
        assertThat(response.followedAt()).isNull();
    }

    // --- listFollowing / listFollowers ----------------------------------------

    @Test
    void listFollowingReturnsProfileData() {
        LocalDateTime followedAt = LocalDateTime.of(2026, 6, 19, 9, 0);
        relationRepository.insertFollowing(ME, TARGET, FollowStatus.ACTIVE, followedAt);

        List<FollowUserResponse> result = service.listFollowing(ME, 0, 10);

        assertThat(result).hasSize(1);
        FollowUserResponse entry = result.get(0);
        assertThat(entry.userId()).isEqualTo(TARGET);
        assertThat(entry.username()).isEqualTo("target");
        assertThat(entry.displayName()).isEqualTo("target-display");
        assertThat(entry.avatarUrl()).isEqualTo("target-avatar.png");
        assertThat(entry.followedAt()).isEqualTo(followedAt);
    }

    @Test
    void listFollowersReturnsProjectionData() {
        LocalDateTime followedAt = LocalDateTime.of(2026, 6, 19, 9, 0);
        // A follower row: ME follows TARGET, so TARGET has a fan of ME.
        relationRepository.insertFollower(TARGET, ME, FollowStatus.ACTIVE, followedAt);

        List<FollowUserResponse> result = service.listFollowers(TARGET, 0, 10);

        assertThat(result).hasSize(1);
        FollowUserResponse entry = result.get(0);
        assertThat(entry.userId()).isEqualTo(ME);
        assertThat(entry.username()).isEqualTo("me");
        assertThat(entry.followedAt()).isEqualTo(followedAt);
    }

    @Test
    void listFollowingSkipsMissingUsers() {
        // An edge pointing at a user that no longer has an account/profile → skipped, not 500.
        relationRepository.insertFollowing(ME, 9999L, FollowStatus.ACTIVE, LocalDateTime.now());

        List<FollowUserResponse> result = service.listFollowing(ME, 0, 10);

        assertThat(result).isEmpty();
    }

    // --- paging clamp ---------------------------------------------------------

    @Test
    void listFollowingClampsPaging() {
        relationRepository.insertFollowing(ME, TARGET, FollowStatus.ACTIVE, LocalDateTime.now());

        // Negative page floored to 0, huge size clamped to 50.
        service.listFollowing(ME, -3, 9999);

        assertThat(relationRepository.lastFollowingLimit).isEqualTo(50);
        assertThat(relationRepository.lastFollowingOffset).isEqualTo(0L);
    }

    @Test
    void listFollowingClampsSizeToLowerBound() {
        relationRepository.insertFollowing(ME, TARGET, FollowStatus.ACTIVE, LocalDateTime.now());

        // size=0 floored to 1.
        service.listFollowing(ME, 2, 0);

        assertThat(relationRepository.lastFollowingLimit).isEqualTo(1);
        assertThat(relationRepository.lastFollowingOffset).isEqualTo(2L);
    }

    @Test
    void listFollowersClampsPaging() {
        service.listFollowers(TARGET, -1, 1000);

        assertThat(relationRepository.lastFollowerLimit).isEqualTo(50);
        assertThat(relationRepository.lastFollowerOffset).isEqualTo(0L);
    }

    // --- fixtures -------------------------------------------------------------

    private void seedUser(Long id, String username) {
        LocalDateTime ts = LocalDateTime.of(2026, 6, 18, 10, 0);
        UserAccount account = new UserAccount(
                id, username, username + "@example.com", null, "hash", UserStatus.ACTIVE, UserRole.USER,
                true, false, null, ts, ts);
        UserProfile profile = new UserProfile(
                id, username + "-display", username + "-avatar.png", null, null, null,
                LocalDate.of(1995, 1, 1), ts, ts);
        userRepository.accounts.put(id, account);
        userRepository.profiles.put(id, profile);
    }

    /**
     * In-memory {@link RelationRepository} that records the limit/offset passed to the list methods so
     * the paging-clamp tests can assert them.
     */
    private static final class FakeRelationRepository implements RelationRepository {
        private final Map<String, UserFollowing> following = new HashMap<>();
        private final List<UserFollower> followers = new ArrayList<>();
        private long nextId = 1L;

        int lastFollowingLimit = -1;
        long lastFollowingOffset = -1L;
        int lastFollowerLimit = -1;
        long lastFollowerOffset = -1L;

        private static String key(Long followerId, Long followingId) {
            return followerId + ":" + followingId;
        }

        void seedCanceled(Long followerId, Long followingId, LocalDateTime followedAt) {
            following.put(key(followerId, followingId), new UserFollowing(
                    nextId++, followerId, followingId, FollowStatus.CANCELED, followedAt,
                    LocalDateTime.now(), followedAt, followedAt));
        }

        @Override
        public Optional<UserFollowing> findFollowing(Long followerId, Long followingId) {
            return Optional.ofNullable(following.get(key(followerId, followingId)));
        }

        @Override
        public void insertFollowing(Long followerId, Long followingId, FollowStatus status,
                                    LocalDateTime followedAt) {
            following.put(key(followerId, followingId), new UserFollowing(
                    nextId++, followerId, followingId, status, followedAt, null, followedAt, followedAt));
        }

        void insertFollower(Long userId, Long followerId, FollowStatus status, LocalDateTime followedAt) {
            followers.add(new UserFollower(
                    nextId++, userId, followerId, status, followedAt, null, followedAt, followedAt));
        }

        @Override
        public void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt) {
        }

        @Override
        public void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt) {
        }

        @Override
        public void insertOutbox(com.platform.relation.domain.RelationOutboxEvent event) {
        }

        @Override
        public List<UserFollowing> findFollowingList(Long followerId, int limit, long offset) {
            lastFollowingLimit = limit;
            lastFollowingOffset = offset;
            return following.values().stream()
                    .filter(r -> followerId.equals(r.followerId()) && r.status() == FollowStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<UserFollower> findFollowerList(Long userId, int limit, long offset) {
            lastFollowerLimit = limit;
            lastFollowerOffset = offset;
            return followers.stream()
                    .filter(f -> userId.equals(f.userId()) && f.status() == FollowStatus.ACTIVE)
                    .toList();
        }

        @Override
        public void upsertFollowerProjection(RelationEventPayload event, FollowStatus status) {
        }

        @Override
        public boolean markConsumed(String eventId, String consumerGroup) {
            return true;
        }

        @Override
        public boolean projectIfFirstTime(RelationEventPayload event, String consumerGroup) {
            // Not exercised by the query-service tests; projector path is covered by the consumer tests.
            return true;
        }
    }

    /** Minimal fake {@link UserRepository} backing a real {@link UserQueryService}. */
    private static final class FakeUserRepository implements UserRepository {
        final Map<Long, UserAccount> accounts = new HashMap<>();
        final Map<Long, UserProfile> profiles = new HashMap<>();

        @Override
        public UserAccount save(UserAccount account, UserProfile profile) {
            accounts.put(account.id(), account);
            profiles.put(profile.userId(), profile);
            return account;
        }

        @Override
        public boolean existsByUsername(String username) {
            return accounts.values().stream().anyMatch(a -> username.equals(a.username()));
        }

        @Override
        public boolean existsByEmail(String email) {
            return accounts.values().stream().anyMatch(a -> email.equals(a.email()));
        }

        @Override
        public Optional<UserAccount> findAccountById(Long userId) {
            return Optional.ofNullable(accounts.get(userId));
        }

        @Override
        public Optional<UserAccount> findAccountByUsername(String username) {
            return accounts.values().stream().filter(a -> username.equals(a.username())).findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByUsernameOrEmail(String usernameOrEmail) {
            return accounts.values().stream()
                    .filter(a -> usernameOrEmail.equals(a.username()) || usernameOrEmail.equals(a.email()))
                    .findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByEmail(String email) {
            return accounts.values().stream().filter(a -> email.equals(a.email())).findFirst();
        }

        @Override
        public Optional<UserAccount> findAccountByPhone(String phone) {
            return Optional.empty();
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
}
