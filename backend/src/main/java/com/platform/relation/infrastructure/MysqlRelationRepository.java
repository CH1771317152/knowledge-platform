package com.platform.relation.infrastructure;

import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.RelationEventType;
import com.platform.relation.domain.RelationOutboxEvent;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import com.platform.relation.event.RelationEventPayload;
import com.platform.relation.repository.RelationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL adapter for {@link RelationRepository}. Mirrors the {@code @Repository @Profile("!test")}
 * discipline used by the content and auth adapters.
 *
 * <p>Enum-typed fields are passed to the mapper as their {@code name()} String (consistent with the
 * content module, which stores {@code PostStatus.name()} etc. explicitly rather than relying on
 * MyBatis's default EnumTypeHandler).
 */
@Repository
@Profile("!test")
public class MysqlRelationRepository implements RelationRepository {

    private final RelationMapper mapper;

    public MysqlRelationRepository(RelationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserFollowing> findFollowing(Long followerId, Long followingId) {
        return mapper.findFollowing(followerId, followingId);
    }

    @Override
    public void insertFollowing(Long followerId, Long followingId, FollowStatus status,
                                LocalDateTime followedAt) {
        mapper.insertFollowing(followerId, followingId, status.name(), followedAt);
    }

    @Override
    public void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt) {
        mapper.activateFollowing(followerId, followingId, followedAt);
    }

    @Override
    public void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt) {
        mapper.cancelFollowing(followerId, followingId, canceledAt);
    }

    @Override
    public void insertOutbox(RelationOutboxEvent event) {
        mapper.insertOutbox(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType().name(), event.followerId(), event.followingId(),
                event.payloadJson(), event.occurredAt());
    }

    @Override
    public List<UserFollowing> findFollowingList(Long followerId, int limit, long offset) {
        return mapper.findFollowingList(followerId, limit, offset);
    }

    @Override
    public List<UserFollower> findFollowerList(Long userId, int limit, long offset) {
        return mapper.findFollowerList(userId, limit, offset);
    }

    @Override
    public void upsertFollowerProjection(RelationEventPayload event, FollowStatus status) {
        // Mirror semantics: the followed user is userId=event.followingId(); the fan is
        // followerId=event.followerId(). ACTIVE records the effective time as followedAt; CANCELED
        // records it as canceledAt.
        Long userId = event.followingId();
        Long followerId = event.followerId();
        LocalDateTime occurredAt = event.occurredAt();
        LocalDateTime followedAt = status == FollowStatus.ACTIVE ? occurredAt : null;
        LocalDateTime canceledAt = status == FollowStatus.CANCELED ? occurredAt : null;
        mapper.upsertFollowerProjection(userId, followerId, status.name(), followedAt, canceledAt);
    }

    @Override
    public boolean markConsumed(String eventId, String consumerGroup) {
        try {
            mapper.insertConsumedEvent(eventId, consumerGroup);
            return true;
        } catch (DuplicateKeyException duplicate) {
            // uk_relation_consumed_event (event_id, consumer_group) already present — first
            // consumer wins, this is a duplicate (idempotent) ack.
            return false;
        }
    }

    @Override
    @Transactional
    public boolean projectIfFirstTime(RelationEventPayload event, String consumerGroup) {
        // Step 1: record the consumed marker inside this transaction. markConsumed catches the
        // DuplicateKeyException itself (a caught exception does NOT roll back), so a false return
        // here is a clean, already-committed skip — we must NOT have written anything in this tx.
        if (!markConsumed(event.eventId(), consumerGroup)) {
            return false; // already consumed by this group — skip
        }
        // Step 2: apply the projection in the SAME transaction. If this throws, the tx rolls back
        // — including the consumed-marker insert above — so the method throws and the consumer
        // routes to retry; on redelivery markConsumed returns true again and re-projects.
        FollowStatus status = event.eventType() == RelationEventType.USER_FOLLOWED
                ? FollowStatus.ACTIVE
                : FollowStatus.CANCELED;
        upsertFollowerProjection(event, status);
        return true;
    }
}
