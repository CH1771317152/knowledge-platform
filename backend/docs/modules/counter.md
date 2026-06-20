# Counter 模块

## 模块职责

`counter` 模块负责平台所有"数字型"指标的读写：文章的点赞 / 收藏 / 浏览 / 评论 / 分享计数，以及用户的关注数 / 粉丝数 / 作品数 / 收到的赞 / 收到的收藏计数。它是 `content`、`relation` 等业务模块的下游读模型，同时也是互动行为（点赞、收藏、浏览）的写入口。

`counter` 拥有的能力：

- 文章互动写接口：点赞 / 取消点赞、收藏 / 取消收藏、浏览（`POST/DELETE /api/posts/{postId}/likes|favorites`、`POST /api/posts/{postId}/views`）。
- 文章计数读：文章的五项计数（`GET /api/posts/{postId}/counters`，公开）、当前用户是否点赞（`GET /api/posts/{postId}/counters/liked`，认证）。
- 互动事实层：MySQL 计数账本 + Redis 位图 + Redis 聚合暂存。
- 互动事件的聚合消费（`counter-events` → Redis agg）。
- 跨模块计数消费：消费 `relation-events` 维护用户 following/followers 计数、消费 `content-events` 维护用户作品数。
- 聚合刷写调度（Lua 原子排空 agg → CountInt blob）。

`counter` 不拥有的能力（属于其他模块）：

- 关注关系事实归 `relation` 模块（`counter` 只消费其事件，不写主表）。
- 贴文发布、正文存储归 `content` 模块（`counter` 只消费其 publish 事件、并为互动接口查询文章作者 id）。
- 计数本身的"事实来源"语义见下文：互动行为的事实是 Redis 位图（like/fav）或消费账本（view/share），计数总值是位图 / 账本的**最终一致**投影，而非强一致主表。

## 架构与数据流

```text
互动写（like / fav / view）                          关系 / 内容事件（跨模块）
  └─ CounterController                                     relation-events ─┐
       ├─ findPostAuthorId（content，存在性 + 作者）         content-events ──┤
       └─ CounterFactService                                 counter-events ─┤
            ├─ like/fav：位图 0↔1 跳变 ──────┐                       │
            └─ view：Idempotency-Key 校验 ──┤                       │
                  └─ KafkaTemplate.send ─── counter-events ─────────┤
                                                                   ▼
                                            CounterAggregateConsumer（主 + retry）
                                              ├─ counter_consumed_event 去重（按 group）
                                              ├─ agg:{etype}:{eid}  HINCRBY
                                              └─ author 侧 fan-out（LIKES_RECEIVED / FAVS_RECEIVED）
                                                   │
                                                   ▼
                                     CounterFlushScheduler（双触发 @Scheduled）
                                       └─ flush-drain.lua 原子排空 agg → cnt CountInt blob

跨模块 USER 计数：
  RelationCountConsumer     relation-events ─→ follower FOLLOWING±1 / following FOLLOWERS±1
  ContentPublishCountConsumer content-events ─→ author POSTS +1
```

### 事实层选择规则（MySQL / 位图 / 无）

`counter` 为每个 `(实体类型, 指标)` 三元组选择**最便宜且语义正确**的事实载体，三种互斥选择：

| 互动形状 | 事实载体 | 原因 |
| --- | --- | --- |
| like / fav（有状态：每个用户至多一个 LIKE/FAV 位） | **Redis 位图**（`bm:{metric}:{etype}:{eid}:{shard}`） | 一个用户对一篇文章至多持有一个 LIKE 位和一个 FAV 位。位图天然把这个"至多一次"约束编码进载体本身：`SETBIT ... 1` 返回旧值即可判断 0→1 的真实跳变。无需 MySQL 行，无需事件去重表（事件 id 是每次跳变新生成的 UUID，由位图保证每用户每侧至多发一次）。 |
| view / share（无状态：每次都算） | **无事实载体**，靠消费去重账本 | 浏览没有"用户对文章的状态"，每次请求都应计数。去重只能靠客户端提供的 `Idempotency-Key`（既是 Kafka 事件 id，又是消费侧 dedup key）。空 key 直接拒绝（`COUNTER_EVENT_INVALID`）。 |
| 聚合计数总值 | **Redis CountInt blob** + MySQL `counter_consumed_event` | CountInt blob 是事件聚合后的投影（最终一致）；消费账本保证同一 `event_id` 在同一 consumer group 内只聚合一次。 |

因此：

- **点赞 / 收藏的事实源是位图**，强一致（位图跳变同步发生在写请求内）。`GET /counters/liked` 直读位图，立即一致。
- **计数总值（如 like 总数）是事件聚合的投影**，最终一致（受 agg flush 周期约束）。
- **关系 / 内容计数**没有位图，事实源是上游模块（`relation_following` / 贴文发布），`counter` 只是消费其事件聚合出 USER 计数。

## 数据模型

计数主数据在 Redis，MySQL 只存消费去重账本。

### MySQL：`counter_consumed_event`（消费去重账本）

由 Flyway 迁移 `src/main/resources/db/migration/V3__counter.sql` 创建。

- `id BIGINT PK AUTO_INCREMENT`。
- `event_id VARCHAR(64) NOT NULL`：事件唯一 id。
- `consumer_group VARCHAR(128) NOT NULL`：消费者组名。每个 `@KafkaListener` 用自己的 group，各自维护独立账本（主聚合组、retry 组、relation 组、content 组互不影响）。
- `consumed_at DATETIME DEFAULT CURRENT_TIMESTAMP`。
- `UNIQUE KEY uk_counter_consumed_event (event_id, consumer_group)`：同一事件在同一组内只能消费一次；重复投递插入冲突 → 视为已处理，直接 ack（first-consumer-wins）。

> 计数本身不落 MySQL。`counter_consumed_event` 只承担幂等去重，不承担事实源。

### Redis：CountInt blob（计数总值投影）

固定布局的二进制串，每个指标占 8 字节小端 int64。布局由 `CountSchema` 固定：

- `ARTICLE`（40 字节）：`LIKE=0, FAV=8, VIEW=16, COMMENT=24, SHARE=32`。
- `USER`（40 字节）：`FOLLOWING=0, FOLLOWERS=8, POSTS=16, LIKES_RECEIVED=24, FAVS_RECEIVED=32`。

- Key：`cnt:{etype}:{eid}`，例如 `cnt:ARTICLE:123`。
- 编解码：`CountIntCodec`（`encodeLong` / `decodeLong`，小端）。
- 读取：`RedisCounterStore.readCount` 用 `GETRANGE` 按 schema 偏移取 8 字节解码。
- 写入：只由 `flush-drain.lua` 排空 agg 时写入（`SETRANGE`），业务侧不直接写 CountInt。测试可见的 `incrAtOffset`（`counter-incr-at-offset.lua`）仅供集成测试直接打点。

### Redis：位图（like / fav 事实源）

- Key：`bm:{metric小写}:{etype}:{eid}:{shard}`，例如 `bm:like:ARTICLE:123:0`。
- 分片：每个 shard 32KB = 262144 bit（`BITS_PER_CHUNK`），shard = `userId / 262144`，bit 偏移 = `userId % 262144`。
- `setBitIfAbsent`（点赞）：`SETBIT key offset 1`，返回旧值；旧值为 0 → 真实跳变。
- `clearBitIfPresent`（取消点赞）：`SETBIT key offset 0`，返回旧值；旧值为 1 → 真实跳变。
- `hasActed`：`GETBIT`，读当前位。`GET /counters/liked` 直接走这里。

### Redis：agg 暂存 + pending 集合（聚合中转）

- Key：`agg:{etype}:{eid}`，Hash，field = 指标名，value = 待刷写的增量。
- Pending 集合：`counter:flush:pending`，Set，成员为 `{etype}:{eid}` tag。
- 写入：`addToAggregate` 做 `HINCRBY agg:{etype}:{eid} {metric} {delta}` + `SADD counter:flush:pending {tag}`。
- 排空：`drainPendingBatch` 用 `SPOP key count` 弹出至多 N 个 tag；`flushOne` 对每个 tag 跑 `flush-drain.lua`。

## 事件设计

### 互动事件 `CounterEventPayload`（发到 `counter-events`）

```json
{
  "eventId": "uuid 或客户端 Idempotency-Key",
  "etype": "ARTICLE",
  "metric": "LIKE",
  "eid": 123,
  "delta": 1,
  "authorId": 7,
  "occurredAt": "2026-06-21T10:00:00"
}
```

- `eventId`：like/fav 用每次跳变新生成的 UUID（位图保证每用户每侧至多发一次）；view/share 用客户端 `Idempotency-Key`（消费侧 dedup 依据）。
- `authorId`：内容互动才填，供消费侧 fan-out 到作者 `LIKES_RECEIVED` / `FAVS_RECEIVED`；view/comment/share 不 fan-out。
- Kafka key：`"{ETYPE}:{EID}"`，保证同一实体的所有增量落同一分区，聚合器按到达顺序 fold。
- 发送方式：`KafkaTemplate.send` 返回的 `Future` **故意不等待**（fire-and-forget），把请求延迟与 broker 可用性解耦；发送失败丢失的单次增量由对账（TODO）从位图 / 消费账本恢复。

## 写路径三分类

`CounterFactService` 按互动形状分三类，幂等机制各不相同：

### 1. like / fav（位图门控）

```text
like(userId, ARTICLE, eid, authorId):
  transition = store.setBitIfAbsent(...)        // SETBIT ... 1，返回旧值
  if transition: emit(LIKE, +1, authorId, UUID) // 仅在 0→1 真跳变时发事件
  return transition                              // changed 标志
```

unlike / unfav 对称（`clearBitIfPresent`，1→0）。重复点赞 / 重复取消都是**静默 no-op**，不发事件。`changed` 直接反映位图跳变。

### 2. view / share（Idempotency-Key 门控）

```text
view(userId, ARTICLE, eid, authorId, key):
  requireIdempotencyKey(key)                     // 空 key → COUNTER_EVENT_INVALID
  emit(VIEW, +1, authorId, key)                  // 每次都发，事件 id = key
```

无状态，每次都算。去重完全在消费侧用 `counter_consumed_event`（事件 id = key）完成。

### 3. relation / content 跨模块消费（无本地写）

`counter` 自身不发起这些事件，只消费上游：

- `RelationCountConsumer` 消费 `relation-events`（Canal flat message）：`USER_FOLLOWED` → follower `FOLLOWING +1`、following `FOLLOWERS +1`；`USER_UNFOLLOWED` 对称 `−1`。复用 `RelationEventParser` 解 Canal 信封。
- `ContentPublishCountConsumer` 消费 `content-events`（content 模块 `AFTER_COMMIT` 直接发的裸 JSON）：`POST_PUBLISHED` → author `POSTS +1`。

两者都用各自的 consumer group 在 `counter_consumed_event` 里去重，因此同一关系事件被 relation 投影器和 counter 消费器各消费一次、互不串扰。

## 幂等去重契约（汇总）

四道独立的幂等闸门，对应不同互动形状：

| 闸门 | 机制 | 适用 |
| --- | --- | --- |
| **eventId** | `counter_consumed_event` 的 `uk(event_id, group)` | 所有事件的消费侧 dedup（first-consumer-wins） |
| **位图跳变** | `setBitIfAbsent` / `clearBitIfPresent` 返回旧值 | like / fav 的写侧幂等（重复请求静默 no-op，不发事件） |
| **Idempotency-Key** | 客户端必传，成为事件 id；空 key 被 `COUNTER_EVENT_INVALID` 拒绝 | view / share 的写侧幂等（无状态互动的唯一 dedup 依据） |
| **counter_consumed_event** | 同 eventId 表 | 跨模块消费者各自的 group 独立账本（relation 组 / content 组 / 主聚合组 / retry 组） |

> 注意：`counter_consumed_event`（MySQL 去重）与 `addToAggregate`（Redis 聚合）**不在同一事务**——Redis 无法参与 MySQL 事务。若主聚合成功但 LIKE/FAV 作者 fan-out 抛错，retry 路径会在**不同的 group** 下重新去重，可能把文章 like/fav 计总数短暂放大，直到对账修正。这是 v1 接受的折中（计数可从位图重建）。view/share 无 fan-out，不受此窗口影响。未来的硬化可把主聚合 + fan-out 折进同一个 Lua 脚本。

## 聚合与双触发刷写（Lua 原子排空）

聚合分两段：先 `HINCRBY` 进 agg hash，再由调度器排空进 CountInt blob。

### flush-drain.lua

- `KEYS[1] = agg:{etype}:{eid}`（暂存 hash），`KEYS[2] = cnt:{etype}:{eid}`（持久 blob）。
- `ARGV = [metric, offset, ...]`：该 etype 的 schema 偏移（Java 侧按 `CountSchema` 构造）。
- 原子语义：`HGETALL agg` → `DEL agg` → 对每个 field：`GETRANGE cnt @offset` → `+delta`（<0 钳到 0）→ `SETRANGE`。返回 1 表示已排空，0 表示 agg 为空。
- 排空 + 写 blob 在同一个 Lua 脚本里，Redis 单线程保证这一步原子，不会出现"agg 清空了但 blob 没写"的中间态。

### CounterFlushScheduler（双触发）

- `@Scheduled(fixedRate)` 固定唤醒（默认 500ms），但真实节奏由 `effectiveIntervalMs()` 决定：
  - `fixed` 模式：直接用配置的固定间隔。
  - `adaptive` 模式：按 pending-agg 数量在 `LOW_PENDING(500)..HIGH_PENDING(5000)` 区间线性插值 `maxIntervalMs(5000)..minIntervalMs(500)`，负载越高刷得越勤。
- 到点后 `drainPendingBatch(batchSize)`（`SPOP` 弹出至多 N 个 tag）→ 逐个 `flushOne`。单个 tag 解析失败只记日志跳过（tag 已从 set 弹出，由对账 TODO 兜底），不阻塞整批。
- **`@Profile("!test & !integration")`**：自动化测试不启动真实 `@Scheduled` 任务；单元测试直接构造调度器，调 `flushPendingBatch()` / `effectiveIntervalMs()`。

### 消费失败与重试 / DLQ

```text
counter-events ──(主聚合失败)──▶ counter-retry ──(再失败)──▶ counter-dlq
```

- 主聚合消费者失败（payload 损坏、聚合异常）→ 把**原始 raw value** 发到 `counter-retry` 并 ack（partition 不阻塞）。
- retry 消费者再走一遍同一管线，但用自己的 group 去重；任何失败 → 发到 `counter-dlq` 并 ack。第一版只重试一次。
- retry/DLQ 的 send 本身失败 → 记日志吞掉（避免 poison-loop 卡死 partition），v1 接受"消息丢失并记日志"。
- relation / content 消费者失败时统一路由到 `counter-retry`（counter 模块共享一个 retry topic）。

## 跨模块集成（消费上游事件）

| 消费者 | 消费 topic | group | source 事件 | 投影目标 |
| --- | --- | --- | --- | --- |
| `CounterAggregateConsumer`（主） | `counter-events` | `counter-aggregate-group` | `CounterEventPayload` | agg（实体计数 + 作者 fan-out） |
| `CounterAggregateConsumer`（retry） | `counter-retry` | `counter-aggregate-retry-group` | 同上（独立账本重做） | 同上 |
| `RelationCountConsumer` | `relation-events` | `counter-relation-group` | `RelationEventPayload`（Canal 包裹） | follower FOLLOWING、following FOLLOWERS |
| `ContentPublishCountConsumer` | `content-events` | `counter-content-group` | `ContentPublishedPayload`（裸 JSON） | author POSTS |

依赖方向：`counter` →（消费）→ `relation` / `content`；`counter` →（查 authorId）→ `content`。`content` / `relation` 不反向依赖 `counter`。

## API 列表

所有接口挂在 `/api/posts/{postId}/...`，由 `SecurityConfig` 的有序请求匹配器决定鉴权：

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/posts/{postId}/likes` | 认证 | 当前用户点赞，位图门控；`changed` 反映 0→1 跳变 |
| `DELETE` | `/api/posts/{postId}/likes` | 认证 | 取消点赞，位图门控；`changed` 反映 1→0 跳变 |
| `POST` | `/api/posts/{postId}/favorites` | 认证 | 收藏，同 likes 的位图语义 |
| `DELETE` | `/api/posts/{postId}/favorites` | 认证 | 取消收藏 |
| `POST` | `/api/posts/{postId}/views` | 认证 | 浏览，**必须带 `Idempotency-Key` 头**，缺失 → 400 |
| `GET` | `/api/posts/{postId}/counters` | **公开**（permitAll） | 文章五项计数（like/fav/view/comment/share） |
| `GET` | `/api/posts/{postId}/counters/liked` | 认证 | 当前用户是否点赞（读位图，立即一致） |

鉴权与有序匹配（`SecurityConfig`）：

1. `GET /api/posts/{postId}/counters` → `permitAll()`。匿名可读计数（常规社交体验）。这是一个**两段**路径，与已有的单段 `/api/posts/{postId}` permitAll 不冲突。
2. `GET /api/posts/{postId}/counters/liked`（**三段**路径）及所有 `POST/DELETE` 写接口 → 落入 `anyRequest().authenticated()`。`/counters/liked` 揭示的是**当前请求者**的点赞状态，不能暴露给匿名调用方。

返回体统一用 `ApiResponse<T>`（`{success, message, data}`）。互动接口的 `data` 是 `InteractionResponse(changed)`；`counters` 的 `data` 是 `ArticleCountersResponse(postId, like, fav, view, comment, share)`。

业务规则：

- **文章必须存在**：like/fav/view 调 `ContentQueryService.findPostAuthorId` 解析作者并校验存在性；文章不存在或已软删 → `COUNTER_ENTITY_NOT_FOUND`（→ 400）。
- **view 必须带 `Idempotency-Key`**：缺失 / 空白 → `COUNTER_EVENT_INVALID`（→ 400）。
- **认证缺失**：写接口与 `/counters/liked` 无 token → 401（`JwtAuthenticationEntryPoint`）。
- **authorId 用途**：仅作为 LIKE/FAV 事件的 fan-out 路由键，让作者的 `LIKES_RECEIVED` / `FAVS_RECEIVED` 随其内容动。

## 一致性模型

| 数据 | 一致性 | 说明 |
| --- | --- | --- |
| **互动事实**（位图） | 强一致 | 位图跳变在写请求内同步完成（Redis 单线程）。`hasActed` / `/counters/liked` 直读位图，立即一致。 |
| **互动事实**（view/share） | 由消费账本保证 at-least-once + 去重 → effectively-once | 无本地事实载体，`counter_consumed_event` 保证同 event_id 同 group 消费一次。 |
| **计数总值**（CountInt blob） | 最终一致 | 由 agg flush 周期决定（adaptive 模式 500ms~5s）。业务侧**不直接读 blob 做强一致断言**。 |
| **作者侧计数**（LIKES_RECEIVED 等） | 最终一致 + 可能短暂放大 | agg 主聚合与 LIKE/FAV fan-out 非原子（见上文"幂等去重契约"），retry 路径可能重复 fold；由对账（TODO）修正。 |

**对账（TODO）**：位图是 like/fav 的可重建事实源，`counter_consumed_event` 是 view/share 与跨模块事件的去重账本。一个周期性对账任务可扫描位图重算 like/fav 总数、扫描消费账本核对 view/share，把漂移的 CountInt blob 校准回真值。当前未实现。

## 本地测试方式

### 单元测试

```bash
./mvnw.cmd test '-Dspring.profiles.active=test'
```

- `test` profile 排除 `DataSourceAutoConfiguration`、`RedisAutoConfiguration`、`KafkaAutoConfiguration`，不依赖外部服务。
- 服务（`CounterFactService` / `CounterReadService`）与消费者（`CounterAggregateConsumer` / `RelationCountConsumer` / `ContentPublishCountConsumer`）用 fake `CounterStore` + 被 mock 的 `KafkaTemplate` + 真实 `ObjectMapper` 直接构造，验证位图跳变 / 事件发送 / 去重 / fan-out / 路由 retry/DLQ 逻辑，**不依赖真实 Redis / Kafka**。
- `CounterFlushScheduler` 单元测试直接调 `flushPendingBatch()` / `effectiveIntervalMs()`，验证 fixed 与 adaptive 两种模式。
- controller / 服务 / 消费者均 `@Profile` 门控（见下），`test` profile 下不装配真实 bean。

### Redis / MySQL 集成测试

```bash
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- 依赖本地 MySQL（`localhost:3307`）+ Redis（`localhost:6379`），Flyway 自动应用 `V1`/`V2`/`V3`。
- `RedisCounterStoreIntegrationTest` 在真实 Redis 上跑 `setBitIfAbsent` / `clearBitIfPresent` / `hasActed` / `addToAggregate` / `flushOne`，验证 `flush-drain.lua` 的原子排空与 `counter-incr-at-offset.lua` 的定点自增。
- `MysqlCounterConsumedEventRepositoryIntegrationTest` 在真实 MySQL 上验证 `markConsumed` 的 first-consumer-wins 语义。
- `CounterControllerIntegrationTest` 走完整 HTTP 栈（注册两个真实用户、创建文章、Bearer token 驱动互动接口），**断言位图状态**（`/counters/liked`、`changed` 标志）与端点响应，**不断言异步聚合后的计数值**（消费者与 flusher 在 integration profile 下被门控，不会及时刷写）。

### 跨模块消费单元测试

`RelationCountConsumerTest` / `ContentPublishCountConsumerTest` 用真实 `RelationEventParser` / Jackson 解析上游事件 payload，验证正确 fold 成 `FOLLOWING/FOLLOWERS/POSTS` 增量、去重与失败路由，**不接真实 Kafka**。

### @Profile 门控总览

| Bean | `@Profile` | 含义 |
| --- | --- | --- |
| `CounterController` | `!test` | test profile 下不装配（依赖 test 排除的服务 / store） |
| `CounterFactService` / `CounterReadService` | `!test` | 同上（依赖 `@Profile("!test")` 的 `CounterStore`） |
| `RedisCounterStore`（`CounterStore` 实现） | `!test` | test profile 下由 fake 替换 |
| `CounterAggregateConsumer` | `!test & !integration` | 自动化测试都不启动真实 Kafka 监听容器 |
| `RelationCountConsumer` / `ContentPublishCountConsumer` | `!test & !integration` | 同上 |
| `CounterFlushScheduler` | `!test & !integration` | 自动化测试不启动真实 `@Scheduled` 任务 |

因此：**只有生产 / 手动本地运行（默认 profile）时**，三个消费者与 flusher 才随应用一起启动，对接真实 Kafka / Redis 链路。

### Schema 来源

`counter_consumed_event` 由 Flyway 迁移 `V3__counter.sql` 创建。集成测试 context 启动时 Flyway 自动应用 `V1` + `V2` + `V3`，**不需要手动建表**。计数本身不落 MySQL，只落 Redis。

## 待办

- **对账重建**：周期任务扫描位图重算 like/fav 总数、扫描 `counter_consumed_event` 核对 view/share，把漂移的 CountInt blob 校准回真值（恢复 broker 发送失败 / agg flush 跳过的丢失增量）。
- **位图清理**：被取消点赞的用户位不会自动回收；长期看需要位图压缩 / 冷热分离策略。
- **热点缓存**：高频读的文章计数可在 CountInt blob 之上再加一层本地 / 进程内缓存，降低 Redis QPS。
- **agg 原子化**：把主聚合 + LIKE/FAV 作者 fan-out 折进同一个 Lua 脚本，消除 retry 路径的短暂放大窗口。
- **adaptive flush 调参**：当前 `LOW/HIGH_PENDING` 阈值与 min/max 间隔是占位值，需按真实负载压测标定。
- **DLQ 自动化**：`counter-dlq` 目前只人工兜底，未来可加自动重放或告警。
