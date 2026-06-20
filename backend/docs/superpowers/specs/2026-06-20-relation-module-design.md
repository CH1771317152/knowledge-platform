# Relation 模块设计规格

## 目标

`relation` 模块负责用户到用户的关系能力，第一阶段实现关注、取关、关注列表、粉丝列表，并跑通高并发场景下的事件驱动链路：

```text
relation_following 主表 + relation_outbox
  -> Canal
  -> Kafka relation-events
  -> relation-follower-projector-group
  -> relation_follower 从表
```

点赞、收藏、浏览等用户到内容的行为仍归 `counter` 模块，不进入 `relation`。

## 边界

`relation` 拥有：

- 关注、取关。
- 当前用户是否关注目标用户。
- following 列表：我关注了谁。
- followers 列表：谁关注了我。
- 关系事件 outbox。
- 第一阶段的粉丝从表投影消费者。

`relation` 不拥有：

- 内容点赞、收藏、浏览计数。
- Feed 流构建。
- 通知系统。
- 用户资料字段。
- Canal 服务本身的业务逻辑。

## 架构决策

### 1. 主表 + Outbox 同事务

关注接口只同步写两类数据：

```text
relation_following
relation_outbox
```

两者在同一个 MySQL 事务中提交。接口成功表示：关系事实已写入，且事件已可靠落入 outbox。

服务端不直接同步写 `relation_follower`、计数、缓存，避免一次请求承担多个下游更新导致高延迟和双写一致性问题。

### 2. Canal 只监听 Outbox

Canal 只监听：

```text
knowledge_platform.relation_outbox
```

不直接监听 `relation_following`。事件语义由 `relation_outbox.payload_json` 定义，Canal 只负责把 outbox 行变更搬到 Kafka。

### 3. Kafka Topic

第一阶段使用三个 topic：

```text
relation-events
relation-follower-retry
relation-follower-dlq
```

本地开发环境 topic 分区数为 `3`，单副本。生产环境可按吞吐、broker 数量和消费者并行度扩展为 `12`、`24` 或更多。

Kafka key 使用：

```text
aggregate_id = FOLLOW:{followerId}:{followingId}
```

它不会创建更多分区，只是让同一用户对的事件尽量落到同一 partition，获得局部顺序。

### 4. 粉丝表是异步投影

`relation_following` 是唯一事实源。

`relation_follower` 是从 `relation-events` 消费生成的投影表。关注接口成功后，following 列表立即一致；followers 列表允许短暂延迟。

### 5. 不使用关系版本号

第一阶段不引入 `relation_version`。事件包含：

```text
event_id
event_type
aggregate_id
follower_id
following_id
occurred_at
payload_json
```

`occurred_at` 用于审计、排查和后续对账，不作为严格顺序控制字段。

重复消费通过事件去重表、业务唯一键和状态覆盖保证幂等。

## 数据库设计

### relation_following

从“我关注了谁”的视角保存事实关系。

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
```

`status` 取值：

```text
ACTIVE
CANCELED
```

取关不物理删除，更新为 `CANCELED`。再次关注更新为 `ACTIVE`。

### relation_follower

从“谁关注了我”的视角保存粉丝投影。

```sql
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
```

`user_id` 表示被关注者，`follower_id` 表示粉丝。

### relation_outbox

关系事件 outbox。应用只负责写入，Canal 负责读取 binlog 并投递 Kafka。

```sql
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
```

### relation_consumed_event

消费者幂等去重表。

```sql
CREATE TABLE relation_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 事件设计

事件类型：

```text
USER_FOLLOWED
USER_UNFOLLOWED
```

Payload：

```json
{
  "eventId": "uuid",
  "eventType": "USER_FOLLOWED",
  "aggregateType": "USER_FOLLOW",
  "aggregateId": "FOLLOW:1001:2002",
  "followerId": 1001,
  "followingId": 2002,
  "occurredAt": "2026-06-20T10:00:00"
}
```

Canal 发送到 Kafka 的是 relation_outbox 行级 flat message。Spring consumer 从 Canal message 的 `data[0].payload_json` 解析出上述业务事件。

## API 设计

所有写接口需要 JWT。

```text
POST   /api/users/{userId}/follow
DELETE /api/users/{userId}/follow
GET    /api/users/{userId}/following
GET    /api/users/{userId}/followers
GET    /api/users/{userId}/relation
```

规则：

- 不能关注自己。
- 目标用户必须存在。
- 重复关注幂等，返回已关注状态。
- 重复取关幂等，返回未关注状态。
- following 查询读 `relation_following`。
- followers 查询读 `relation_follower`。

分页第一版沿用当前项目 page/size 风格，内部限制 `size <= 50`。后续可演进为 cursor。

## 消费失败策略

第一阶段只实现一个消费者组：

```text
relation-follower-projector-group
```

它订阅：

```text
relation-events
```

处理失败时：

```text
relation-events -> relation-follower-retry -> relation-follower-dlq
```

第一版 retry topic 消费失败直接进入 DLQ。后续可增加多级延迟 retry topic。

## 可靠性原则

- `relation_following` 是唯一事实源。
- `relation_follower`、计数、缓存都是投影，可重建。
- Kafka 消费者必须幂等。
- Canal 是否投递成功由 Canal/Kafka 监控、consumer lag 和后续对账机制保证。
- 对账修复机制放入后续待办：以 `relation_following` 重建 `relation_follower`，并校准计数和缓存。

## 本地基础设施

已配置：

- `deploy/docker-compose.yml`
- `deploy/canal/conf/canal.properties`
- `deploy/canal/conf/relation/instance.properties`
- `docs/local-canal-kafka.md`

本地启动：

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql kafka kafka-init canal
```

Kafka topic：

```text
relation-events
relation-follower-retry
relation-follower-dlq
```

## 测试策略

单元测试：

- 关注幂等。
- 取关幂等。
- 禁止关注自己。
- 目标用户不存在时报错。
- Outbox 事件字段正确。
- Consumer 重复消费不重复写 follower 表。

集成测试：

- MySQL 真实写入 following/outbox。
- Spring Kafka consumer 直接消费测试消息并更新 follower 表。
- Canal 端到端验证作为手动 smoke：插入 relation_outbox，观察 Kafka relation-events。

## 后续待办

- 生产级 Kafka 副本、ISR、监控配置。
- Canal/Kafka lag 告警。
- relation_follower 重建任务。
- follower_count/following_count 计数投影。
- 关系缓存投影。
- block / mute 关系扩展。
