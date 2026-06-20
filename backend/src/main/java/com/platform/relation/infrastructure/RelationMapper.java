package com.platform.relation.infrastructure;

import com.platform.relation.domain.FollowStatus;
import com.platform.relation.domain.UserFollower;
import com.platform.relation.domain.UserFollowing;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for the relation module. Columns are bound directly to the immutable domain
 * records ({@link UserFollowing}, {@link UserFollower}) via {@code @ConstructorArgs}/@Arg} — no
 * separate row classes (records have no setters, so setter-based {@code @Results} cannot apply).
 *
 * <p>Enum-typed columns ({@code status}, {@code event_type}) are stored as their {@code name()}
 * String; the repository passes them as String params on writes, and on reads they are mapped back
 * to {@link FollowStatus} via an explicit {@code javaType} {@code @Arg} (MyBatis converts the
 * String to the enum by name).
 */
@Mapper
public interface RelationMapper {

    @Select("""
            SELECT id, follower_id, following_id, status, followed_at, canceled_at,
                created_at, updated_at
            FROM relation_following
            WHERE follower_id = #{followerId} AND following_id = #{followingId}
            """)
    @ConstructorArgs(value = {
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "follower_id", javaType = Long.class),
            @Arg(column = "following_id", javaType = Long.class),
            @Arg(column = "status", javaType = FollowStatus.class),
            @Arg(column = "followed_at", javaType = LocalDateTime.class),
            @Arg(column = "canceled_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<UserFollowing> findFollowing(@Param("followerId") Long followerId,
                                         @Param("followingId") Long followingId);

    @Insert("""
            INSERT INTO relation_following (follower_id, following_id, status, followed_at)
            VALUES (#{followerId}, #{followingId}, #{status}, #{followedAt})
            """)
    void insertFollowing(@Param("followerId") Long followerId,
                         @Param("followingId") Long followingId,
                         @Param("status") String status,
                         @Param("followedAt") LocalDateTime followedAt);

    @Update("""
            UPDATE relation_following
            SET status = 'ACTIVE', followed_at = #{followedAt}, canceled_at = NULL
            WHERE follower_id = #{followerId} AND following_id = #{followingId}
            """)
    int activateFollowing(@Param("followerId") Long followerId,
                          @Param("followingId") Long followingId,
                          @Param("followedAt") LocalDateTime followedAt);

    @Update("""
            UPDATE relation_following
            SET status = 'CANCELED', canceled_at = #{canceledAt}
            WHERE follower_id = #{followerId} AND following_id = #{followingId}
            """)
    int cancelFollowing(@Param("followerId") Long followerId,
                        @Param("followingId") Long followingId,
                        @Param("canceledAt") LocalDateTime canceledAt);

    @Insert("""
            INSERT INTO relation_outbox (event_id, aggregate_type, aggregate_id, event_type,
                follower_id, following_id, payload_json, occurred_at)
            VALUES (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
                #{followerId}, #{followingId}, #{payloadJson}, #{occurredAt})
            """)
    void insertOutbox(@Param("eventId") String eventId,
                      @Param("aggregateType") String aggregateType,
                      @Param("aggregateId") String aggregateId,
                      @Param("eventType") String eventType,
                      @Param("followerId") Long followerId,
                      @Param("followingId") Long followingId,
                      @Param("payloadJson") String payloadJson,
                      @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT id, follower_id, following_id, status, followed_at, canceled_at,
                created_at, updated_at
            FROM relation_following
            WHERE follower_id = #{followerId} AND status = 'ACTIVE'
            ORDER BY followed_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ConstructorArgs(value = {
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "follower_id", javaType = Long.class),
            @Arg(column = "following_id", javaType = Long.class),
            @Arg(column = "status", javaType = FollowStatus.class),
            @Arg(column = "followed_at", javaType = LocalDateTime.class),
            @Arg(column = "canceled_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    List<UserFollowing> findFollowingList(@Param("followerId") Long followerId,
                                          @Param("limit") int limit,
                                          @Param("offset") long offset);

    @Select("""
            SELECT id, user_id, follower_id, status, followed_at, canceled_at,
                created_at, updated_at
            FROM relation_follower
            WHERE user_id = #{userId} AND status = 'ACTIVE'
            ORDER BY followed_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @ConstructorArgs(value = {
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "follower_id", javaType = Long.class),
            @Arg(column = "status", javaType = FollowStatus.class),
            @Arg(column = "followed_at", javaType = LocalDateTime.class),
            @Arg(column = "canceled_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    List<UserFollower> findFollowerList(@Param("userId") Long userId,
                                        @Param("limit") int limit,
                                        @Param("offset") long offset);

    @Insert("""
            INSERT INTO relation_follower (user_id, follower_id, status, followed_at, canceled_at)
            VALUES (#{userId}, #{followerId}, #{status}, #{followedAt}, #{canceledAt})
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                followed_at = VALUES(followed_at),
                canceled_at = VALUES(canceled_at)
            """)
    void upsertFollowerProjection(@Param("userId") Long userId,
                                  @Param("followerId") Long followerId,
                                  @Param("status") String status,
                                  @Param("followedAt") LocalDateTime followedAt,
                                  @Param("canceledAt") LocalDateTime canceledAt);

    @Insert("""
            INSERT INTO relation_consumed_event (event_id, consumer_group)
            VALUES (#{eventId}, #{consumerGroup})
            """)
    int insertConsumedEvent(@Param("eventId") String eventId,
                            @Param("consumerGroup") String consumerGroup);
}
