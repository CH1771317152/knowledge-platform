# Relation Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Version-control save steps are intentionally omitted for this project until Git/GitHub management is enabled.

**Goal:** Implement the relation module with follow/unfollow, following/follower lists, outbox events, Canal/Kafka delivery, and the first follower projection consumer.

**Architecture:** `relation_following` is the synchronous source of truth. `relation_outbox` is written in the same transaction and is published by Canal to Kafka. A Spring Kafka consumer group `relation-follower-projector-group` updates `relation_follower` asynchronously with retry and DLQ topics.

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis, MySQL, Flyway, Spring Security JWT, Spring Kafka, Canal, Kafka.

---

## File Structure

- Modify: `backend/src/main/resources/db/migration/V1__init.sql`
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-integration.yml`
- Modify: `backend/src/main/java/com/platform/config/KafkaConfig.java`
- Create: `backend/src/main/java/com/platform/relation/domain/FollowStatus.java`
- Create: `backend/src/main/java/com/platform/relation/domain/RelationEventType.java`
- Create: `backend/src/main/java/com/platform/relation/domain/UserFollowing.java`
- Create: `backend/src/main/java/com/platform/relation/domain/UserFollower.java`
- Create: `backend/src/main/java/com/platform/relation/domain/RelationOutboxEvent.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationEventPayload.java`
- Create: `backend/src/main/java/com/platform/relation/event/CanalFlatMessage.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationEventTopics.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationFollowerProjectorConsumer.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationFollowerRetryConsumer.java`
- Create: `backend/src/main/java/com/platform/relation/application/RelationCommandService.java`
- Create: `backend/src/main/java/com/platform/relation/application/RelationQueryService.java`
- Create: `backend/src/main/java/com/platform/relation/controller/RelationController.java`
- Create: `backend/src/main/java/com/platform/relation/dto/FollowRelationResponse.java`
- Create: `backend/src/main/java/com/platform/relation/dto/FollowUserResponse.java`
- Create: `backend/src/main/java/com/platform/relation/repository/RelationRepository.java`
- Create: `backend/src/main/java/com/platform/relation/infrastructure/RelationMapper.java`
- Create: `backend/src/main/java/com/platform/relation/infrastructure/MysqlRelationRepository.java`
- Create: `backend/docs/modules/relation.md`
- Modify: `backend/docs/modules/README.md`
- Modify: `backend/docs/api-draft.md`
- Test: `backend/src/test/java/com/platform/relation/application/RelationCommandServiceTest.java`
- Test: `backend/src/test/java/com/platform/relation/application/RelationQueryServiceTest.java`
- Test: `backend/src/test/java/com/platform/relation/event/RelationFollowerProjectorConsumerTest.java`
- Test: `backend/src/test/java/com/platform/relation/infrastructure/MysqlRelationRepositoryIntegrationTest.java`
- Test: `backend/src/test/java/com/platform/relation/controller/RelationControllerIntegrationTest.java`

---

## Task 1: Schema, Error Codes, And Kafka Properties

**Files:**
- Modify: `backend/src/main/resources/db/migration/V1__init.sql`
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-integration.yml`
- Modify: `backend/src/main/java/com/platform/config/KafkaConfig.java`

- [ ] **Step 1: Add relation error codes**

Append these enum constants to `ErrorCode` after user errors or after storage errors:

```java
RELATION_TARGET_NOT_FOUND,
RELATION_SELF_FOLLOW_FORBIDDEN,
RELATION_FORBIDDEN,
RELATION_EVENT_INVALID,
RELATION_EVENT_CONSUME_FAILED
```

- [ ] **Step 2: Add relation schema to `V1__init.sql`**

Add a `relation module` section after auth or before auth:

```sql
CREATE TABLE relation_following (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    followed_at DATETIME,
    canceled_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_following_pair (follower_id, following_id),
    KEY idx_relation_following_list (follower_id, status, followed_at, id),
    KEY idx_relation_following_reverse (following_id, status, followed_at, id),
    CONSTRAINT fk_relation_following_follower FOREIGN KEY (follower_id) REFERENCES user_account (id),
    CONSTRAINT fk_relation_following_following FOREIGN KEY (following_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_follower (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    follower_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    followed_at DATETIME,
    canceled_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_follower_pair (user_id, follower_id),
    KEY idx_relation_follower_list (user_id, status, followed_at, id),
    KEY idx_relation_follower_reverse (follower_id, status, followed_at, id),
    CONSTRAINT fk_relation_follower_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_relation_follower_follower FOREIGN KEY (follower_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_outbox_event_id (event_id),
    KEY idx_relation_outbox_created_at (created_at),
    KEY idx_relation_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 3: Add Kafka consumer properties**

In `application.yml`, extend `spring.kafka`:

```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: knowledge-platform
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
```

Add relation topic properties under `platform`:

```yaml
  relation:
    kafka:
      events-topic: ${RELATION_EVENTS_TOPIC:relation-events}
      follower-retry-topic: ${RELATION_FOLLOWER_RETRY_TOPIC:relation-follower-retry}
      follower-dlq-topic: ${RELATION_FOLLOWER_DLQ_TOPIC:relation-follower-dlq}
      follower-consumer-group: ${RELATION_FOLLOWER_CONSUMER_GROUP:relation-follower-projector-group}
      follower-retry-consumer-group: ${RELATION_FOLLOWER_RETRY_CONSUMER_GROUP:relation-follower-retry-group}
```

- [ ] **Step 4: Configure manual ack listener factory**

Update `KafkaConfig`:

```java
package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> manualAckKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

- [ ] **Step 5: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: build succeeds; test profile still excludes Kafka auto configuration.

---

## Task 2: Domain, DTO, Event Types

**Files:**
- Create: `backend/src/main/java/com/platform/relation/domain/*.java`
- Create: `backend/src/main/java/com/platform/relation/dto/*.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationEventPayload.java`
- Create: `backend/src/main/java/com/platform/relation/event/CanalFlatMessage.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationEventTopics.java`

- [ ] **Step 1: Create domain enums**

`FollowStatus.java`:

```java
package com.platform.relation.domain;

public enum FollowStatus {
    ACTIVE,
    CANCELED
}
```

`RelationEventType.java`:

```java
package com.platform.relation.domain;

public enum RelationEventType {
    USER_FOLLOWED,
    USER_UNFOLLOWED
}
```

- [ ] **Step 2: Create domain records**

Create `UserFollowing`, `UserFollower`, and `RelationOutboxEvent` as records with the schema fields:

```java
public record UserFollowing(Long id, Long followerId, Long followingId, FollowStatus status,
                            LocalDateTime followedAt, LocalDateTime canceledAt,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {}
```

```java
public record UserFollower(Long id, Long userId, Long followerId, FollowStatus status,
                           LocalDateTime followedAt, LocalDateTime canceledAt,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {}
```

```java
public record RelationOutboxEvent(Long id, String eventId, String aggregateType, String aggregateId,
                                  RelationEventType eventType, Long followerId, Long followingId,
                                  String payloadJson, LocalDateTime occurredAt,
                                  LocalDateTime createdAt) {}
```

- [ ] **Step 3: Create API DTOs**

`FollowRelationResponse` fields:

```java
Long currentUserId;
Long targetUserId;
boolean following;
LocalDateTime followedAt;
```

`FollowUserResponse` fields:

```java
Long userId;
String username;
String displayName;
String avatarUrl;
LocalDateTime followedAt;
```

- [ ] **Step 4: Create event payload records**

```java
public record RelationEventPayload(String eventId, RelationEventType eventType,
                                   String aggregateType, String aggregateId,
                                   Long followerId, Long followingId,
                                   LocalDateTime occurredAt) {}
```

`CanalFlatMessage` must model fields used from Canal flat message:

```java
public record CanalFlatMessage(String database, String table, String type,
                               List<Map<String, String>> data) {}
```

- [ ] **Step 5: Create topic constants**

```java
public final class RelationEventTopics {
    public static final String EVENTS = "${platform.relation.kafka.events-topic}";
    public static final String FOLLOWER_RETRY = "${platform.relation.kafka.follower-retry-topic}";
    public static final String FOLLOWER_DLQ = "${platform.relation.kafka.follower-dlq-topic}";
    private RelationEventTopics() {}
}
```

---

## Task 3: Repository And MyBatis

**Files:**
- Create: `backend/src/main/java/com/platform/relation/repository/RelationRepository.java`
- Create: `backend/src/main/java/com/platform/relation/infrastructure/RelationMapper.java`
- Create: `backend/src/main/java/com/platform/relation/infrastructure/MysqlRelationRepository.java`
- Test: `backend/src/test/java/com/platform/relation/infrastructure/MysqlRelationRepositoryIntegrationTest.java`

- [ ] **Step 1: Define repository contract**

Required methods:

```java
Optional<UserFollowing> findFollowing(Long followerId, Long followingId);
void insertFollowing(Long followerId, Long followingId, FollowStatus status, LocalDateTime followedAt);
void activateFollowing(Long followerId, Long followingId, LocalDateTime followedAt);
void cancelFollowing(Long followerId, Long followingId, LocalDateTime canceledAt);
void insertOutbox(RelationOutboxEvent event);
List<UserFollowing> findFollowingList(Long followerId, int limit, long offset);
List<UserFollower> findFollowerList(Long userId, int limit, long offset);
void upsertFollowerProjection(RelationEventPayload event, FollowStatus status);
boolean markConsumed(String eventId, String consumerGroup);
```

- [ ] **Step 2: Implement MyBatis mapper**

Use annotation SQL. Important statements:

```sql
INSERT INTO relation_following (follower_id, following_id, status, followed_at)
VALUES (#{followerId}, #{followingId}, #{status}, #{followedAt})
```

```sql
UPDATE relation_following
SET status = 'ACTIVE', followed_at = #{followedAt}, canceled_at = NULL
WHERE follower_id = #{followerId} AND following_id = #{followingId}
```

```sql
UPDATE relation_following
SET status = 'CANCELED', canceled_at = #{canceledAt}
WHERE follower_id = #{followerId} AND following_id = #{followingId}
```

```sql
INSERT INTO relation_follower (user_id, follower_id, status, followed_at, canceled_at)
VALUES (#{followingId}, #{followerId}, #{status}, #{followedAt}, #{canceledAt})
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    followed_at = VALUES(followed_at),
    canceled_at = VALUES(canceled_at)
```

```sql
INSERT INTO relation_consumed_event (event_id, consumer_group)
VALUES (#{eventId}, #{consumerGroup})
```

Catch `DuplicateKeyException` in repository `markConsumed` and return `false`.

- [ ] **Step 3: Write repository integration test**

Test flow:

1. Create two users with `UserCommandService`.
2. Insert following.
3. Insert outbox.
4. Upsert follower projection as `ACTIVE`.
5. Mark consumed once returns true.
6. Mark consumed again returns false.
7. Cancel following and upsert follower projection as `CANCELED`.

- [ ] **Step 4: Run integration tests**

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis kafka kafka-init canal
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: repository integration test passes.

---

## Task 4: Command And Query Services

**Files:**
- Create: `backend/src/main/java/com/platform/relation/application/RelationCommandService.java`
- Create: `backend/src/main/java/com/platform/relation/application/RelationQueryService.java`
- Test: `backend/src/test/java/com/platform/relation/application/RelationCommandServiceTest.java`
- Test: `backend/src/test/java/com/platform/relation/application/RelationQueryServiceTest.java`

- [ ] **Step 1: Implement command service**

Constructor dependencies:

```java
RelationRepository relationRepository
UserQueryService userQueryService
ObjectMapper objectMapper
```

`follow(Long currentUserId, Long targetUserId)`:

- reject same id with `RELATION_SELF_FOLLOW_FORBIDDEN`
- verify target exists with `userQueryService.getPublicProfile(targetUserId)`
- if absent relation: insert ACTIVE following
- if CANCELED: activate following
- if ACTIVE: return current state
- insert `USER_FOLLOWED` outbox only when state changes to ACTIVE

`unfollow(Long currentUserId, Long targetUserId)`:

- reject same id
- verify target exists
- if no relation or already CANCELED: return not-following state
- if ACTIVE: cancel following and insert `USER_UNFOLLOWED` outbox

- [ ] **Step 2: Implement event JSON creation**

Aggregate id:

```java
"FOLLOW:" + followerId + ":" + followingId
```

Payload JSON fields:

```json
{
  "eventId": "...",
  "eventType": "USER_FOLLOWED",
  "aggregateType": "USER_FOLLOW",
  "aggregateId": "FOLLOW:1:2",
  "followerId": 1,
  "followingId": 2,
  "occurredAt": "2026-06-20T10:00:00"
}
```

- [ ] **Step 3: Implement query service**

Methods:

```java
FollowRelationResponse getRelation(Long currentUserId, Long targetUserId);
List<FollowUserResponse> listFollowing(Long userId, int page, int size);
List<FollowUserResponse> listFollowers(Long userId, int page, int size);
```

Clamp paging:

```java
int safePage = Math.max(page, 0);
int safeSize = Math.min(Math.max(size, 1), 50);
long offset = (long) safePage * safeSize;
```

For each relation row, call `UserQueryService.getPublicProfile` to enrich user display fields.

- [ ] **Step 4: Write service tests**

Cover:

- `followCreatesFollowingAndOutbox`
- `followIsIdempotentWhenAlreadyActive`
- `unfollowCancelsAndWritesOutbox`
- `unfollowIsIdempotentWhenAlreadyCanceled`
- `rejectsSelfFollow`
- `listFollowingReturnsProfileData`
- `listFollowersReturnsProjectionData`

---

## Task 5: Kafka Follower Projector

**Files:**
- Create: `backend/src/main/java/com/platform/relation/event/RelationFollowerProjectorConsumer.java`
- Create: `backend/src/main/java/com/platform/relation/event/RelationFollowerRetryConsumer.java`
- Test: `backend/src/test/java/com/platform/relation/event/RelationFollowerProjectorConsumerTest.java`

- [ ] **Step 1: Implement Canal message parser**

Parser behavior:

- parse Kafka value as `CanalFlatMessage`
- ignore messages whose table is not `relation_outbox`
- ignore non-INSERT events
- read first row `payload_json`
- parse `payload_json` as `RelationEventPayload`
- throw `RELATION_EVENT_INVALID` for missing or invalid payload

- [ ] **Step 2: Implement main consumer**

Listener:

```java
@KafkaListener(
    topics = "${platform.relation.kafka.events-topic}",
    groupId = "${platform.relation.kafka.follower-consumer-group}",
    containerFactory = "manualAckKafkaListenerContainerFactory"
)
```

Processing:

1. parse event.
2. call `relationRepository.markConsumed(event.eventId(), "relation-follower-projector-group")`.
3. if false, ack and return.
4. for `USER_FOLLOWED`, upsert `relation_follower` as `ACTIVE`.
5. for `USER_UNFOLLOWED`, upsert `relation_follower` as `CANCELED`.
6. ack after success.
7. on failure, send original message to `relation-follower-retry`, then ack original.

- [ ] **Step 3: Implement retry consumer**

Listener topic:

```text
relation-follower-retry
```

Processing:

- retry same projection once.
- on success ack.
- on failure send original message to `relation-follower-dlq`, then ack retry message.

- [ ] **Step 4: Write consumer unit tests**

Use fake repository and mocked `KafkaTemplate<String, String>`.

Cover:

- parses Canal flat message and upserts ACTIVE projection.
- duplicate event is acknowledged without second upsert.
- failure sends to retry topic.
- retry failure sends to DLQ.

---

## Task 6: REST Controller And Security

**Files:**
- Create: `backend/src/main/java/com/platform/relation/controller/RelationController.java`
- Test: `backend/src/test/java/com/platform/relation/controller/RelationControllerIntegrationTest.java`

- [ ] **Step 1: Implement controller**

Endpoints:

```text
POST   /api/users/{userId}/follow
DELETE /api/users/{userId}/follow
GET    /api/users/{userId}/following
GET    /api/users/{userId}/followers
GET    /api/users/{userId}/relation
```

Use `CurrentUserService.requirePrincipal()` for write endpoints and relation-state endpoint.

- [ ] **Step 2: Keep security defaults**

No `SecurityConfig` public matcher is needed for write endpoints. They fall through to `anyRequest().authenticated()`.

If following/followers should be public, add:

```java
.requestMatchers(HttpMethod.GET, "/api/users/{userId}/following", "/api/users/{userId}/followers")
    .permitAll()
```

Do not permit:

```text
GET /api/users/{userId}/relation
```

- [ ] **Step 3: Write controller integration tests**

Flow:

1. Register user A.
2. Register user B.
3. A follows B.
4. `GET /api/users/{B}/relation` with A token returns `following=true`.
5. A unfollows B.
6. relation returns `following=false`.

Projection list can be tested by inserting/upserting follower projection directly through repository until Canal/Kafka end-to-end is verified.

---

## Task 7: Canal/Kafka Smoke Verification

**Files:**
- Modify: `backend/docs/local-canal-kafka.md`
- Create: `backend/docs/modules/relation.md`

- [ ] **Step 1: Start infrastructure**

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis kafka kafka-init canal
```

- [ ] **Step 2: Run backend integration tests**

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- [ ] **Step 3: Manual outbox-to-Kafka smoke**

After schema exists, insert one synthetic relation_outbox row in Navicat:

```sql
INSERT INTO relation_outbox (
    event_id, aggregate_type, aggregate_id, event_type,
    follower_id, following_id, payload_json, occurred_at
) VALUES (
    UUID(), 'USER_FOLLOW', 'FOLLOW:1:2', 'USER_FOLLOWED',
    1, 2,
    JSON_OBJECT(
        'eventId', UUID(),
        'eventType', 'USER_FOLLOWED',
        'aggregateType', 'USER_FOLLOW',
        'aggregateId', 'FOLLOW:1:2',
        'followerId', 1,
        'followingId', 2,
        'occurredAt', DATE_FORMAT(NOW(), '%Y-%m-%dT%H:%i:%s')
    ),
    NOW()
);
```

Then consume one Kafka message:

```powershell
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic relation-events --from-beginning --max-messages 1
```

Expected: one Canal flat message containing `relation_outbox` and `payload_json`.

- [ ] **Step 4: Write module docs**

Create `docs/modules/relation.md` with:

- module responsibility
- source table and projection table
- outbox + Canal + Kafka flow
- API list
- idempotency rules
- retry/DLQ behavior
- testing commands

Update `docs/modules/README.md` and `docs/api-draft.md`.

---

## Self-Review

- Spec coverage: This plan covers following/follower tables, outbox, Canal/Kafka topic flow, first follower projection consumer, retry/DLQ, REST APIs, docs, and tests.
- Scope: Counter/cache/feed consumers are explicitly excluded and can be added as separate downstream consumer groups later.
- Type consistency: Event names, topic names, aggregate id, and consumer group names match the design spec.
- Project note: Git commit steps are omitted because the project is not currently managed through Git/GitHub.
