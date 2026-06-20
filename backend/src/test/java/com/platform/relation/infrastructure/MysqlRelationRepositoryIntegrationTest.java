package com.platform.relation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.domain.RelationOutboxEvent;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.repository.RelationRepository;
import com.platform.user.application.UserCommandService;
import com.platform.user.dto.CreateUserCommand;
import java.time.LocalDateTime;
import org.assertj.core.groups.Tuple;
import java.util.UUID;
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
class MysqlRelationRepositoryIntegrationTest {

    private static final String FOLLOWER_USERNAME = "it_relation_follower";
    private static final String FOLLOWER_EMAIL = "it_relation_follower@example.com";
    private static final String FOLLOWER_PHONE = "13700000701";
    private static final String FOLLOWING_USERNAME = "it_relation_following";
    private static final String FOLLOWING_EMAIL = "it_relation_following@example.com";
    private static final String FOLLOWING_PHONE = "13700000702";

    @Autowired
    private RelationRepository repository;

    @Autowired
    private UserCommandService userCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long followerId;
    private Long followingId;

    @BeforeEach
    void createUsers() {
        // Clean any leftover rows from prior non-transactional runs so the unique usernames can be
        // (re)created reliably.
        deleteRelationsForTestUsers();
        deleteTestUsers();

        this.followerId = userCommandService.createUser(
                new CreateUserCommand(FOLLOWER_USERNAME, FOLLOWER_EMAIL, FOLLOWER_PHONE, "hash")).id();
        this.followingId = userCommandService.createUser(
                new CreateUserCommand(FOLLOWING_USERNAME, FOLLOWING_EMAIL, FOLLOWING_PHONE, "hash")).id();
    }

    @Test
    void persistsFollowOutboxProjectionAndConsumedDedup() {
        LocalDateTime now = LocalDateTime.now();

        // Step 2: insert an ACTIVE follow edge.
        repository.insertFollowing(followerId, followingId, FollowStatus.ACTIVE, now);
        UserFollowing inserted = repository.findFollowing(followerId, followingId).orElseThrow();
        assertThat(inserted.status()).isEqualTo(FollowStatus.ACTIVE);
        assertThat(inserted.followedAt()).isNotNull();
        assertThat(inserted.canceledAt()).isNull();

        // Step 3: insert an outbox event.
        String eventId = UUID.randomUUID().toString();
        String payloadJson = "{\"eventId\":\"" + eventId + "\",\"followerId\":" + followerId
                + ",\"followingId\":" + followingId + "}";
        RelationOutboxEvent event = new RelationOutboxEvent(
                null, eventId, "FOLLOW", "FOLLOW:" + followerId + ":" + followingId,
                RelationEventType.USER_FOLLOWED, followerId, followingId, payloadJson, now, null);
        repository.insertOutbox(event);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM relation_outbox WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);

        // Step 4: upsert the follower projection as ACTIVE.
        RelationEventPayload payload = new RelationEventPayload(
                eventId, RelationEventType.USER_FOLLOWED, "FOLLOW",
                "FOLLOW:" + followerId + ":" + followingId, followerId, followingId, now);
        repository.upsertFollowerProjection(payload, FollowStatus.ACTIVE);
        // The projection is keyed by (userId=followingId, followerId=followerId).
        assertThat(repository.findFollowerList(followingId, 10, 0))
                .extracting(UserFollower::userId, UserFollower::followerId, UserFollower::status)
                .contains(Tuple.tuple(followingId, followerId, FollowStatus.ACTIVE));

        // Step 5 & 6: markConsumed is idempotent — first call wins, second returns false.
        assertThat(repository.markConsumed(eventId, "relation-follower-projector-group")).isTrue();
        assertThat(repository.markConsumed(eventId, "relation-follower-projector-group")).isFalse();

        // Step 7: cancel the follow.
        LocalDateTime canceledAt = LocalDateTime.now();
        repository.cancelFollowing(followerId, followingId, canceledAt);
        UserFollowing canceled = repository.findFollowing(followerId, followingId).orElseThrow();
        assertThat(canceled.status()).isEqualTo(FollowStatus.CANCELED);
        assertThat(canceled.canceledAt()).isNotNull();

        // Step 8: project the unfollow (CANCELED). The ACTIVE-filtered lists now exclude the edge.
        String unfollowEventId = UUID.randomUUID().toString();
        RelationEventPayload unfollowPayload = new RelationEventPayload(
                unfollowEventId, RelationEventType.USER_UNFOLLOWED, "FOLLOW",
                "FOLLOW:" + followerId + ":" + followingId, followerId, followingId, canceledAt);
        repository.upsertFollowerProjection(unfollowPayload, FollowStatus.CANCELED);

        assertThat(repository.findFollowerList(followingId, 10, 0))
                .extracting(UserFollower::followerId)
                .doesNotContain(followerId);
        assertThat(repository.findFollowingList(followerId, 10, 0))
                .extracting(UserFollowing::followingId)
                .doesNotContain(followingId);
    }

    private void deleteRelationsForTestUsers() {
        Long followerDbId = findUserId(FOLLOWER_USERNAME, FOLLOWER_EMAIL);
        Long followingDbId = findUserId(FOLLOWING_USERNAME, FOLLOWING_EMAIL);
        if (followerDbId != null || followingDbId != null) {
            if (followerDbId != null) {
                jdbcTemplate.update("DELETE FROM relation_following WHERE follower_id = ?", followerDbId);
                jdbcTemplate.update("DELETE FROM relation_following WHERE following_id = ?", followerDbId);
                jdbcTemplate.update("DELETE FROM relation_follower WHERE user_id = ?", followerDbId);
                jdbcTemplate.update("DELETE FROM relation_follower WHERE follower_id = ?", followerDbId);
            }
            if (followingDbId != null) {
                jdbcTemplate.update("DELETE FROM relation_following WHERE follower_id = ?", followingDbId);
                jdbcTemplate.update("DELETE FROM relation_following WHERE following_id = ?", followingDbId);
                jdbcTemplate.update("DELETE FROM relation_follower WHERE user_id = ?", followingDbId);
                jdbcTemplate.update("DELETE FROM relation_follower WHERE follower_id = ?", followingDbId);
            }
        }
    }

    private void deleteTestUsers() {
        jdbcTemplate.update("DELETE FROM user_profile "
                + "WHERE user_id IN (SELECT id FROM user_account WHERE username = ? OR username = ?)",
                FOLLOWER_USERNAME, FOLLOWING_USERNAME);
        jdbcTemplate.update("DELETE FROM user_account WHERE username = ? OR username = ?",
                FOLLOWER_USERNAME, FOLLOWING_USERNAME);
    }

    private Long findUserId(String username, String email) {
        return jdbcTemplate.query(
                "SELECT id FROM user_account WHERE username = ? OR email = ?",
                (rs, rowNum) -> rs.getLong("id"), username, email)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
