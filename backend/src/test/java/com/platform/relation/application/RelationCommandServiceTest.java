package com.platform.relation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.domain.RelationOutboxEvent;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.dto.FollowRelationResponse;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.repository.RelationRepository;
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
 * Pure unit test for {@link RelationCommandService}. The service is constructed directly with an
 * in-memory {@link FakeRelationRepository} and a real {@link UserQueryService} backed by a fake
 * {@link FakeUserRepository} (mirroring {@code UserQueryServiceTest}), plus a real
 * {@link ObjectMapper}. This mirrors {@code ContentCommandServiceTest}: the service is
 * {@code @Profile("!test")} and excluded under the {@code test} profile, so it must be built by hand
 * to keep {@code PlatformApplicationTests.contextLoads} green.
 */
class RelationCommandServiceTest {

    private static final Long ME = 7L;
    private static final Long TARGET = 42L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 19, 9, 0);

    private FakeRelationRepository relationRepository;
    private FakeUserRepository userRepository;
    private RelationCommandService service;
    private List<RelationOutboxEvent> insertedOutbox;

    @BeforeEach
    void setUp() {
        relationRepository = new FakeRelationRepository();
        userRepository = new FakeUserRepository();
        insertedOutbox = relationRepository.outbox;
        seedUser(ME, "me");
        seedUser(TARGET, "target");
        service = new RelationCommandService(
                relationRepository, new UserQueryService(userRepository),
                new ObjectMapper().findAndRegisterModules());
    }

    // --- follow ---------------------------------------------------------------

    @Test
    void followCreatesFollowingAndOutbox() {
        FollowRelationResponse response = service.follow(ME, TARGET);

        assertThat(response.following()).isTrue();
        assertThat(response.currentUserId()).isEqualTo(ME);
        assertThat(response.targetUserId()).isEqualTo(TARGET);
        assertThat(response.followedAt()).isNotNull();

        UserFollowing row = relationRepository.findFollowing(ME, TARGET).orElseThrow();
        assertThat(row.status()).isEqualTo(FollowStatus.ACTIVE);

        assertThat(insertedOutbox).hasSize(1);
        assertOutboxEvent(insertedOutbox.get(0), RelationEventType.USER_FOLLOWED, ME, TARGET);
    }

    @Test
    void followIsIdempotentWhenAlreadyActive() {
        service.follow(ME, TARGET);
        RelationOutboxEvent firstEvent = insertedOutbox.get(0);
        LocalDateTime firstFollowedAt = relationRepository.findFollowing(ME, TARGET).orElseThrow().followedAt();

        FollowRelationResponse second = service.follow(ME, TARGET);

        assertThat(second.following()).isTrue();
        // No second outbox row, and the original followedAt is preserved (not overwritten with now()).
        assertThat(insertedOutbox).hasSize(1);
        assertThat(insertedOutbox.get(0)).isSameAs(firstEvent);
        assertThat(second.followedAt()).isEqualTo(firstFollowedAt);
        assertThat(relationRepository.findFollowing(ME, TARGET).orElseThrow().followedAt())
                .isEqualTo(firstFollowedAt);
    }

    @Test
    void followReactivatesWhenCanceled() {
        // Seed a CANCELED edge directly.
        relationRepository.seedCanceled(ME, TARGET, BASE_TIME);
        assertThat(insertedOutbox).isEmpty();

        FollowRelationResponse response = service.follow(ME, TARGET);

        assertThat(response.following()).isTrue();
        UserFollowing row = relationRepository.findFollowing(ME, TARGET).orElseThrow();
        assertThat(row.status()).isEqualTo(FollowStatus.ACTIVE);
        // Reactivation writes exactly one USER_FOLLOWED outbox row.
        assertThat(insertedOutbox).hasSize(1);
        assertOutboxEvent(insertedOutbox.get(0), RelationEventType.USER_FOLLOWED, ME, TARGET);
    }

    // --- unfollow -------------------------------------------------------------

    @Test
    void unfollowCancelsAndWritesOutbox() {
        service.follow(ME, TARGET);
        assertThat(insertedOutbox).hasSize(1);

        FollowRelationResponse response = service.unfollow(ME, TARGET);

        assertThat(response.following()).isFalse();
        assertThat(response.followedAt()).isNull();
        UserFollowing row = relationRepository.findFollowing(ME, TARGET).orElseThrow();
        assertThat(row.status()).isEqualTo(FollowStatus.CANCELED);
        // follow (USER_FOLLOWED) + unfollow (USER_UNFOLLOWED) = two outbox rows.
        assertThat(insertedOutbox).hasSize(2);
        assertOutboxEvent(insertedOutbox.get(1), RelationEventType.USER_UNFOLLOWED, ME, TARGET);
    }

    @Test
    void unfollowIsIdempotentWhenAlreadyCanceled() {
        service.follow(ME, TARGET);
        service.unfollow(ME, TARGET);
        assertThat(insertedOutbox).hasSize(2);

        FollowRelationResponse response = service.unfollow(ME, TARGET);

        assertThat(response.following()).isFalse();
        // No third outbox row.
        assertThat(insertedOutbox).hasSize(2);
    }

    @Test
    void unfollowIsIdempotentWhenAbsent() {
        // No follow edge at all yet.
        FollowRelationResponse response = service.unfollow(ME, TARGET);

        assertThat(response.following()).isFalse();
        assertThat(insertedOutbox).isEmpty();
        assertThat(relationRepository.findFollowing(ME, TARGET)).isEmpty();
    }

    @Test
    void reFollowAfterUnfollowReactivatesWithUserFollowedEvent() {
        service.follow(ME, TARGET);
        service.unfollow(ME, TARGET);
        assertThat(insertedOutbox).hasSize(2);

        FollowRelationResponse response = service.follow(ME, TARGET);

        assertThat(response.following()).isTrue();
        assertThat(relationRepository.findFollowing(ME, TARGET).orElseThrow().status())
                .isEqualTo(FollowStatus.ACTIVE);
        // Reactivation writes a fresh USER_FOLLOWED row (the third outbox entry).
        assertThat(insertedOutbox).hasSize(3);
        assertOutboxEvent(insertedOutbox.get(2), RelationEventType.USER_FOLLOWED, ME, TARGET);
    }

    // --- rejection paths ------------------------------------------------------

    @Test
    void rejectsSelfFollow() {
        assertThatThrownBy(() -> service.follow(ME, ME))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.RELATION_SELF_FOLLOW_FORBIDDEN);
        assertThatThrownBy(() -> service.unfollow(ME, ME))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.RELATION_SELF_FOLLOW_FORBIDDEN);
        assertThat(insertedOutbox).isEmpty();
    }

    @Test
    void followRejectsMissingTarget() {
        assertThatThrownBy(() -> service.follow(ME, 9999L))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.RELATION_TARGET_NOT_FOUND);
        assertThat(insertedOutbox).isEmpty();
    }

    @Test
    void unfollowRejectsMissingTarget() {
        assertThatThrownBy(() -> service.unfollow(ME, 9999L))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.RELATION_TARGET_NOT_FOUND);
        assertThat(insertedOutbox).isEmpty();
    }

    // --- assertions / fixtures ------------------------------------------------

    /** Verifies the canonical shape of an outbox event row. */
    private static void assertOutboxEvent(RelationOutboxEvent event, RelationEventType expectedType,
                                          Long followerId, Long followingId) {
        assertThat(event.eventType()).isEqualTo(expectedType);
        assertThat(event.aggregateType()).isEqualTo("FOLLOW");
        assertThat(event.aggregateId()).isEqualTo("FOLLOW:" + followerId + ":" + followingId);
        assertThat(event.followerId()).isEqualTo(followerId);
        assertThat(event.followingId()).isEqualTo(followingId);
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
        // id + createdAt are DB-assigned; the command service leaves them null.
        assertThat(event.id()).isNull();
        assertThat(event.createdAt()).isNull();
    }

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
     * In-memory {@link RelationRepository}: stores {@link UserFollowing} rows keyed by
     * (followerId, followingId) and records every inserted outbox event. Mirrors
     * {@code FakeContentPostRepository}.
     */
    private static final class FakeRelationRepository implements RelationRepository {
        private final Map<String, UserFollowing> following = new HashMap<>();
        private final List<RelationOutboxEvent> outbox = new ArrayList<>();
        private long nextId = 1L;

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

        @Override
        public void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt) {
            UserFollowing row = following.get(key(followerId, followingId));
            following.put(key(followerId, followingId), new UserFollowing(
                    row.id(), followerId, followingId, FollowStatus.ACTIVE, followedAt, null,
                    row.createdAt(), LocalDateTime.now()));
        }

        @Override
        public void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt) {
            UserFollowing row = following.get(key(followerId, followingId));
            following.put(key(followerId, followingId), new UserFollowing(
                    row.id(), followerId, followingId, FollowStatus.CANCELED, row.followedAt(), canceledAt,
                    row.createdAt(), LocalDateTime.now()));
        }

        @Override
        public void insertOutbox(RelationOutboxEvent event) {
            outbox.add(event);
        }

        @Override
        public List<UserFollowing> findFollowingList(Long followerId, int limit, long offset) {
            return following.values().stream()
                    .filter(r -> followerId.equals(r.followerId()) && r.status() == FollowStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<UserFollower> findFollowerList(Long userId, int limit, long offset) {
            return List.of();
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
            // Not exercised by the command-service tests; projector path is covered by the consumer tests.
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
