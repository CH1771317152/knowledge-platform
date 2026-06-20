# Relation 模块

## 模块职责

`relation` 模块负责平台用户之间的关注关系管理，是后续 Feed、通知、计数等下游能力的关系事实来源。

`relation` 拥有的能力：

- 关注 / 取关目标用户（写侧）。
- 查询当前用户是否关注某目标用户（`GET /api/users/{userId}/relation`）。
- following 列表（我关注了谁，`GET /api/users/{userId}/following`）。
- followers 列表（谁关注了我，`GET /api/users/{userId}/followers`）。
- 关系事件的 transactional outbox（与关系主表同事务写入）。
- 第一阶段粉丝投影消费者（把 outbox 事件投影成 follower 从表）。

`relation` 不拥有的能力（属于其他模块）：

- 点赞、收藏、浏览计数归 `counter` 模块（粉丝数 / 关注数也不由 `relation` 同步维护，见下文数据流）。
- 内容、Feed、通知归 `content` 及后续模块。
- Canal 本身、Kafka 集群、OSS 归基础设施，`relation` 只以消费者的身份接入它们。

## 架构与数据流（主表 + Outbox + Canal + Kafka + 投影）

```text
follow / unfollow（写）
  └─ @Transactional 同一事务内写：
       relation_following（源，status ACTIVE/CANCELED）
       relation_outbox  （事件行，payload_json）
       ↓ 接口成功 = 关系事实 + 事件都已落库

relation_outbox(MySQL binlog)
  └─ Canal 只监听 relation_outbox ─→ Kafka topic: relation-events
       └─ relation-follower-projector-group 消费
            └─ 解析 Canal flat message → 投影 relation_follower（从表）
```

设计要点：

- **`relation_following` 是唯一事实源**。关注关系的所有状态变化（新增、复活、取消）都先落在 `relation_following`，与 `relation_outbox` 在**同一 MySQL 事务**内写入。因此关注 / 取关接口只要返回成功，关系事实与事件都已落库，没有"接口成功但事件丢失"的中间态。
- **Canal 只监听 `relation_outbox`**，不直接监听业务表 `relation_following`。Canal 把 `relation_outbox` 行级变更以 Canal flat message 发到 Kafka `relation-events`。
- **follower 从表是异步投影**，由 `relation-follower-projector-group` 消费 `relation-events` 把事件投影成 `relation_follower` 行。服务端关注 / 取关接口**不同步写** follower 从表、不同步写计数、不写缓存——这是为了在高并发下避免主表写完后还要同步落多个下游（双写一致性与放大写放大）。
- **一致性边界**：following 列表读主表 `relation_following`，立即一致；followers 列表读投影表 `relation_follower`，允许短暂延迟（事件从 outbox 落库到被消费投影之间的窗口）。

## 数据库表

四张表由 Flyway 迁移 `src/main/resources/db/migration/V2__relation.sql` 创建（user / auth / content 表在 `V1__init.sql`）。

### relation_following（源表）

关注关系的唯一事实源。

- `follower_id BIGINT NOT NULL`：粉丝（发起关注的用户）。
- `following_id BIGINT NOT NULL`：被关注者。
- `status VARCHAR(32) NOT NULL`：`ACTIVE` / `CANCELED`。**取关不物理删除**，只把 `status` 置为 `CANCELED`，再次关注时由 `CANCELED` 复活为 `ACTIVE`。
- `followed_at DATETIME`：关注时间；复活关注时刷新为当前时间。
- `canceled_at DATETIME`：取消时间。
- `UNIQUE KEY uk_relation_following_pair (follower_id, following_id)`：一对关注关系唯一，保证幂等与复活语义。
- 索引 `idx_relation_following_list (follower_id, status, followed_at, id)` 支撑 following 列表；`idx_relation_following_reverse (following_id, status, followed_at, id)` 支撑反向查询（follower 投影核对）。
- 双外键指向 `user_account(id)`。

### relation_follower（投影表）

follower 从表，由消费者异步投影维护，支撑 followers 列表。

- `user_id BIGINT NOT NULL`：**被关注者**（即 following_id 的投影侧）。
- `follower_id BIGINT NOT NULL`：粉丝（即 follower_id 的投影侧）。
- `status VARCHAR(32) NOT NULL`：`ACTIVE` / `CANCELED`，随事件同步。
- `followed_at` / `canceled_at`：与源表对应。
- `UNIQUE KEY uk_relation_follower_pair (user_id, follower_id)`：投影去重 upsert 的依据。
- 索引 `idx_relation_follower_list (user_id, status, followed_at, id)` 支撑 followers 列表（newest first）。

### relation_outbox（事件表）

transactional outbox 的事件载体，被 Canal 监听。

- `event_id VARCHAR(64) NOT NULL`：事件唯一 id（UUID），**唯一**（`uk_relation_outbox_event_id`），消费者据此去重。
- `aggregate_type VARCHAR(64) NOT NULL`：固定 `FOLLOW`。
- `aggregate_id VARCHAR(128) NOT NULL`：`FOLLOW:{followerId}:{followingId}`。
- `event_type VARCHAR(64) NOT NULL`：`USER_FOLLOWED` / `USER_UNFOLLOWED`。
- `follower_id` / `following_id`：冗余字段，便于排查。
- `payload_json JSON NOT NULL`：完整业务事件（见下节）。
- `occurred_at DATETIME NOT NULL`：事件发生时间。
- 索引 `idx_relation_outbox_created_at`、`idx_relation_outbox_aggregate (aggregate_type, aggregate_id)`。

### relation_consumed_event（消费去重表）

消费者幂等去重账本。

- `event_id VARCHAR(64) NOT NULL`。
- `consumer_group VARCHAR(128) NOT NULL`：消费者组名（主投影组与 retry 组各自独立账本）。
- `UNIQUE KEY uk_relation_consumed_event (event_id, consumer_group)`：同一事件在同一组内只能消费一次；重复投递时插入冲突 → 视为已处理，直接 ack。

## 事件设计

两种事件类型（`RelationEventType`）：

- `USER_FOLLOWED`：新增关注（插入）或 `CANCELED` → `ACTIVE` 复活。
- `USER_UNFOLLOWED`：`ACTIVE` → `CANCELED` 取关。

聚合标识：

- `aggregate_type = FOLLOW`。
- `aggregate_id = FOLLOW:{followerId}:{followingId}`（关注方在前、被关注方在后，与 `UserFollowing` 的字段顺序一致）。

`payload_json` 字段（`RelationEventPayload`，由 `RelationCommandService` 用 `ObjectMapper` 序列化）：

```json
{
  "eventId": "uuid",
  "eventType": "USER_FOLLOWED",
  "aggregateType": "FOLLOW",
  "aggregateId": "FOLLOW:1:2",
  "followerId": 1,
  "followingId": 2,
  "occurredAt": "2026-06-20T10:00:00"
}
```

Canal 把 `relation_outbox` 的行级变更作为 Canal flat message 发到 Kafka。flat message 的 `data[0]` 是变更行（包含 `payload_json` 列），消费者从 `data[0].payload_json` 反序列化出业务事件，再据此投影。

## API 列表

所有接口挂在 `/api/users/{userId}/...`，由 `SecurityConfig` 的有序请求匹配器决定鉴权（顺序很关键）：

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/users/{userId}/follow` | 认证 | 当前用户关注 `userId`，幂等 |
| `DELETE` | `/api/users/{userId}/follow` | 认证 | 当前用户取关 `userId`，幂等 |
| `GET` | `/api/users/{userId}/following` | **公开**（permitAll） | `userId` 关注了谁，newest first，分页 `page`/`size` |
| `GET` | `/api/users/{userId}/followers` | **公开**（permitAll） | 谁关注了 `userId`，newest first，分页 `page`/`size` |
| `GET` | `/api/users/{userId}/relation` | 认证 | 当前用户是否关注 `userId` |

鉴权与有序匹配（`SecurityConfig`）：

1. `GET /api/users/{userId}/following`、`GET /api/users/{userId}/followers` → `permitAll()`。following/followers 列表公开可读，符合常规社交体验。
2. `GET /api/users/{userId}/relation`、`POST/DELETE /api/users/{userId}/follow` 落入 `anyRequest().authenticated()`。`/relation` 揭示的是**当前请求者**的关注状态，不能暴露给匿名调用方。

业务规则：

- **禁止自我关注**：`followerId == followingId` 抛 `RELATION_SELF_FOLLOW_FORBIDDEN`（follow / unfollow 都拒绝，getRelation 不拒绝，自己查自己返回 following=false）。
- **目标必须存在**：目标用户不存在抛 `RELATION_TARGET_NOT_FOUND`（内部把 user 模块的 `USER_NOT_FOUND` 转译为 relation 自己的 code，不泄露 user 模块错误码）。
- **关注 / 取关幂等**：见下节。
- 列表分页：`page` 下限 0、`size` 上限 50（`MAX_PAGE_SIZE`），按 `followed_at` newest first。列表项会被 enrich 为用户公开资料快照；引用到的用户若已不存在则跳过该项（不返回 500）。

## 幂等规则

- **关注幂等**：已 `ACTIVE` 的边再次 follow 是 no-op，**不写 outbox**。`CANCELED` 的边再次 follow 会复活为 `ACTIVE` 并发 `USER_FOLLOWED`。首次关注（不存在）插入 `ACTIVE` 并发 `USER_FOLLOWED`。
- **取关幂等**：已 `CANCELED` 或不存在的边再次 unfollow 是 no-op，**不写 outbox**。`ACTIVE` 的边取关置 `CANCELED` 并发 `USER_UNFOLLOWED`。
- **关键点**：outbox 行**只在状态真正变化时**写入，重复幂等请求不会产生重复事件。
- **消费者去重**：通过 `relation_consumed_event` 的 `uk(event_id, consumer_group)` 去重。同一 `event_id` 在同一 consumer group 内第二次被消费时 `markConsumed` 返回 false → 直接 ack，不重复投影。主投影组（`relation-follower-projector-group`）与 retry 组（`relation-follower-retry-group`）各自维护独立账本。

## 消费失败与重试 / DLQ

```text
relation-events ──(主投影失败)──▶ relation-follower-retry ──(再失败)──▶ relation-follower-dlq
```

三阶段，第一版只重试一次：

1. **主投影消费者**（`RelationFollowerProjectorConsumer`，group `relation-follower-projector-group`）消费 `relation-events`：解析 Canal flat message → 校验 table 是 `relation_outbox` 且 type 是 `INSERT` → `markConsumed` 去重 → 投影 `relation_follower`。任何失败（解析失败、payload 损坏、投影异常）都把**原始消息**发到 `relation-follower-retry`，然后 ack 原消息，保证 partition 不阻塞。
2. **retry 消费者**（`RelationFollowerRetryConsumer`，group `relation-follower-retry-group`）消费 `relation-follower-retry`：再走一遍同样的 解析 → 去重（用 retry 组自己的账本）→ 投影。成功则 ack；**任何失败**把原始消息发到 `relation-follower-dlq`，然后 ack（retry partition 不阻塞）。第一版只重试一次，retry 失败即进 DLQ。
3. **DLQ**（`relation-follower-dlq`）：人工排查兜底，无自动消费。

解析过滤规则（主消费者与 retry 消费者一致）：

- 非 `relation_outbox` 表的 Canal 消息 → 忽略（ack，不算失败）。
- 非 `INSERT` 类型（如 UPDATE/DELETE） → 忽略。
- `data` 为空 / `payload_json` 缺失 / 损坏 → 走 retry（视为失败）。
- retry topic 的消息若再次解析出"应忽略"（非 outbox / 非 INSERT），retry 消费者也会直接 ack（不再 DLQ）。

> 若 retry send / DLQ send 本身失败，消费者记录日志后吞掉异常（避免 poison-loop 卡死 partition）；v1 接受"消息丢失并记日志"作为兜底。

## 本地基础设施与启动

relation 模块的完整事件链路依赖 MySQL binlog + Canal + Kafka：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis kafka kafka-init canal
```

- **`kafka-init`** 在 Kafka 启动后自动创建 3 个 topic（各 3 分区、副本 1）：
  - `relation-events`（Canal 发送 outbox 变更）
  - `relation-follower-retry`（主投影失败重试）
  - `relation-follower-dlq`（重试失败兜底）
- **Canal** 监听 `knowledge_platform.relation_outbox`，把行级变更发到 `relation-events`。Canal 实例配置在 `deploy/canal/conf/relation/instance.properties`，只过滤 `relation_outbox`，不发布 `relation_following` 的直接变更。
- Kafka 宿主机端口 `localhost:9092`，容器内部 `kafka:29092`。
- 当前本地 MySQL 使用 8.4，Canal 镜像使用 `canal/canal-server:v1.1.8`，避免旧版 Canal 1.1.7 初始化位点时执行 MySQL 8.4 已不支持的 `SHOW MASTER STATUS`。

详细命令、MySQL binlog 检查、topic 检查、Canal 日志查看、以及 outbox → Kafka 手动 smoke 步骤见 `docs/local-canal-kafka.md`。

## 本地测试方式

### 单元测试

```bash
./mvnw.cmd test '-Dspring.profiles.active=test'
```

- `test` profile 排除 `DataSourceAutoConfiguration`、`RedisAutoConfiguration`、`KafkaAutoConfiguration`，不依赖外部服务。
- 消费者（`RelationFollowerProjectorConsumer`、`RelationFollowerRetryConsumer`）与服务（`RelationCommandService`、`RelationQueryService`）用 fake `RelationRepository` + 被 mock 的 `KafkaTemplate` 直接构造，验证解析 / 去重 / 投影 / 路由 retry/DLQ 逻辑，**不依赖真实 Kafka**。
- 消费者 / 服务 / controller 均 `@Profile` 门控（见下），`test` profile 下不会装配真实 bean。

### 集成测试

```bash
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- 依赖本地 MySQL（`localhost:3307`）+ Redis（`localhost:6379`），Flyway 自动应用 `V1__init.sql` 与 `V2__relation.sql`。
- **消费者 `@Profile("!test & !integration")`**：集成测试 profile 下**不启动任何 `@KafkaListener` 容器**，避免真实 Kafka 让集成测试不可重复。follower 投影的正确性通过直接调用 `RelationRepository.upsertFollowerProjection` 在真实 MySQL 上验证（不经过 Kafka）。
- controller / repository 在 `integration` profile 下装配真实 MySQL 实现，集成测试覆盖 HTTP follow / unfollow / relation / following / followers 链路、主表幂等与复活、目标存在性校验等。

### @Profile 门控总览

| Bean | `@Profile` | 含义 |
| --- | --- | --- |
| `RelationController` | `!test` | test profile 下不装配（依赖 test 排除的 MySQL repo） |
| `RelationCommandService` / `RelationQueryService` / `RelationRepository` | `!test` | 同上 |
| `RelationFollowerProjectorConsumer` / `RelationFollowerRetryConsumer` | `!test & !integration` | 自动化测试（test + integration）都不启动真实 Kafka 监听容器 |

因此：**只有生产 / 手动本地运行（默认 profile）时，两个消费者才随应用一起启动**，对接真实 Canal→Kafka 链路。

### Schema 来源

relation 四张表（`relation_following`、`relation_follower`、`relation_outbox`、`relation_consumed_event`）由 Flyway 迁移 `V2__relation.sql` 创建。集成测试 context 启动时 Flyway 自动应用 `V1` + `V2`，**不需要手动建表**。
