# Counter 模块设计规格

## 目标

`counter` 模块统一管理平台所有"计数"语义的数据，设计目标面向亿级用户、极高读写并发。

计数范围：

- 内容侧：点赞数、阅读数、收藏数、评论数、转发数。
- 用户侧：关注数、粉丝数、发布作品数、获得点赞数、获得收藏数。

计数数据的特征：读写极高频、单次更新粒度小（±1）、对强一致性要求不高（最终一致即可）。

## 核心不变量

- **事实层是基准**：判定"用户 X 是否对实体 Y 做过动作"的唯一来源。计数是事实层的基数聚合，可由事实层重建。
- **计数层是聚合**：高频读、异步写、最终一致。
- **写路径统一为异步聚合**：所有计数增量都经 Kafka → 聚合 → 落地 CountInt；不同点仅在"事实来源"。

## 已确认决策

1. **计数只存 Redis，不落 MySQL**。用二进制 SDS 块（CountInt）+ 统一 CountSchema 管理偏移；一个实体一个 key。
2. **事实层来源按数据本性分化**（不按"关系"这个名词）：
   - 有 MySQL 真相的（relation_following、content_post、未来 comment 表）→ 用 MySQL 当事实，counter 只聚合计数。
   - 无 MySQL 真相且高频且需去重的内容互动（点赞、收藏）→ 用 Redis 分片位图当事实。
   - 无需去重的（阅读、转发）→ 无事实层，纯聚合。
3. **写路径全异步聚合**：业务模块只发事件，counter 消费聚合。actor 自身计数的即时性由客户端乐观更新，不靠同步计数。
4. **聚合用 Redis agg hash + 双触发刷新**（固定 1s / 自适应 500ms–5s），Lua 原子排空防重复刷。
5. **幂等键绑定"一次动作"**，不绑定"用户是否做过"。所有事件带 `eventId`，消费端按 `eventId` 去重。
6. **view/share 去重用客户端 Idempotency-Key（方案 A）**：一次动作一个 key，重试复用，真实重复用新 key。
7. **事件携带 authorId 等必要字段**，避免消费端反查。
8. **持久化用 RDB + AOF**；计数可由事实层重建（对账）。完整恢复/对账机制列为待办。

## 模块边界

`counter` 拥有：

- CountInt 计数块与其二进制编解码、CountSchema 偏移注册表。
- 内容互动的事实位图（like/fav）。
- 内容互动端点（点赞/取消点赞/收藏/取消收藏/阅读）。
- 增量事件消费、agg 聚合、双触发刷写、CountInt 落地。
- 跨模块事件消费（relation 关注事件、content 发布事件）。
- 计数读、事实读（是否互动过）API。
- 消费幂等去重表 `counter_consumed_event`。

`counter` 不拥有：

- 关注关系本身（relation）、文章生命周期（content）、评论实体（未来 comment）、用户资料（user）。
- Feed、排行榜、通知。

## 事实层来源选择规则

选事实存储看"查询形状 + 数据价值"，不看名词。五问：

1. 要枚举成员吗？（要→索引库；不要→集合结构）
2. 写有多热？（极致→Redis；中→MySQL 可）
3. 数据多密？（布尔+海量→位图；富结构+中量→行）
4. 多不能丢？（关键→MySQL；可容忍→Redis+重建）
5. 有生命周期/审计吗？（有→行；无→位）

落点：

| 计数 | 事实来源 | 位图 | 对账方式 |
|---|---|---|---|
| following / followers | `relation_following`（MySQL） | 否 | `SELECT COUNT(*) WHERE status='ACTIVE'` |
| posts_count | `content_post`（MySQL） | 否 | `SELECT COUNT(*) WHERE status='PUBLISHED'` |
| comment_count | 未来 comment 表（MySQL） | 否 | `SELECT COUNT(*)` |
| article like / fav | 位图（Redis） | 是 | 分片 `BITCOUNT` 求和 |
| user likes_received / favs_received | 作者文章的 like/fav 位图（派生） | 否 | 作者文章 BITCOUNT 之和 |
| view / share | 无事实层 | 否 | 无（纯聚合） |

## 数据模型

### CountSchema（偏移注册表）

每个 etype 定义各 metric 在 CountInt 中的字节偏移。所有计数为 **8 字节有符号 int64，小端**。

```text
etype = ARTICLE :
  offset  0 : like_count
  offset  8 : fav_count
  offset 16 : view_count
  offset 24 : comment_count
  offset 32 : share_count
  预留到 128 字节（共 16 个槽位，留给未来指标）

etype = USER :
  offset  0 : following_count
  offset  8 : followers_count
  offset 16 : posts_count
  offset 24 : likes_received
  offset 32 : favs_received
  预留到 128 字节
```

代码侧 `CountSchema`（枚举或记录）集中管理 `etype → metric → offset`，所有读写偏移由它统一给出。

### CountInt 二进制块

- key：`cnt:{etype}:{eid}`（如 `cnt:ARTICLE:766948934001352704`、`cnt:USER:1001`）。
- 值为二进制字符串，按 Schema 偏移存 int64。
- 懒增长：`SETRANGE` 写偏移时 Redis 自动 zero-pad；`GETRANGE` 越界返回空按 0 处理，无需预分配。
- 原子增减必须用 Lua（读 8 字节→解码→±delta→编码→写回），否则并发竞态。int64 在 2^53（≈9e15）内对 Redis Lua double 精确，计数远不到。

### 分片位图（like/fav 事实层）

key：`bm:{metric}:{etype}:{eid}:{chunk}`

- `metric` ∈ {like, fav}。
- `etype` ∈ {article, video, ...}。
- `eid`：实体 ID。
- `chunk = userId / 262144`；位图内偏移 `= userId % 262144`。
- 一个分片 32KB = 262144 bit。
- 稀疏分配：仅被互动过的分片创建。百万赞文章约 381 分片 ≈ 12MB。
- 位图长期存在；删除内容/用户时由后台任务清理对应实体全部分片（待办）。

Redis 原生 `SETBIT/GETBIT` 单命令原子；`SETBIT` 返回旧 bit 是幂等判定关键。

### 聚合桶 agg hash

- key：`agg:{etype}:{eid}`，Redis Hash，field = metric，value = 累积 delta。
- 写入用原生 `HINCRBY`（原子、int64 安全）。

### Redis Key 总览

```text
bm:{metric}:{etype}:{eid}:{chunk}   事实位图（SETBIT/GETBIT）
cnt:{etype}:{eid}                    CountInt 计数块（Lua INCR-at-offset）
agg:{etype}:{eid}                    聚合增量桶（Hash, HINCRBY；flush 时排空）
```

### MySQL 表

```sql
CREATE TABLE counter_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_counter_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

仅消费幂等去重用；CountInt 与位图都在 Redis。

## 写路径分类

| 类型 | 同步动作 | 异步事件 | 事实/去重 |
|---|---|---|---|
| like / fav | `SETBIT`（幂等判定） | 仅状态跳变才发 | 位图=事实+去重 |
| view / share | 无（直接发事件返回） | 每次都发，每次 +1 | 无事实，靠 eventId 去重 |
| relation / posts / comments | 业务模块已写 MySQL | 消费对方事件 | MySQL=事实 |

### 点赞示例

1. `POST /api/posts/{id}/likes`，JWT 鉴权，校验文章存在（调 content 查询）。
2. `SETBIT bm:like:article:{id}:{chunk} bit 1` → 返回 oldBit。
   - oldBit=0（新赞）：发 `counter-events`，payload 带 `{etype:ARTICLE, eid:id, metric:like, delta:+1, authorId}`。
   - oldBit=1（已赞）：幂等 no-op，返回"已点赞"，不发事件。
3. 毫秒级返回。

消费 → 进 agg 桶 → flush 时 Lua 原子排空 + 落地 CountInt（含给 author 的 `likes_received` fan-out）。

### 阅读示例（view）

1. `POST /api/posts/{id}/views`，JWT 鉴权。
2. 解析 `eventId`（客户端 `Idempotency-Key`）。
3. 发 `counter-events`（metric=view, delta:+1, authorId），立即返回。不同步写 Redis。
4. 消费按 eventId 去重 → agg → CountInt 的 view_count。

## 幂等与去重契约

核心：去重键绑定"一次动作"，不绑定"用户是否做过"。

- **like/fav**：位图状态跳变天然保证"一次动作一个事件"（仅 0→1/1→0 才发）。eventId 由跳变动作生成。
- **view/share**：`eventId = 客户端 Idempotency-Key`。一次动作一个 key，HTTP 重试复用同 key → 同 eventId → 去重；真实重复用新 key → 新 eventId → 计数。
- **relation/posts/comments**：上游模块自身幂等（唯一键/发布幂等）保证一次业务动作一个事件，eventId 来自上游。
- **统一兜底**：消费端在 `counter_consumed_event` 按 `(event_id, consumer_group)` 去重，吸收 Kafka 至少一次重投与消费回放。

结果：业务重复（用户真实多次操作）正常累加；技术重复（重试/重投）被去重，不脏计数。

## 事件设计

Topic：

```text
counter-events
counter-retry
counter-dlq
```

各 3 分区（本地）。Kafka key = `{etype}:{eid}`，同一实体增量落同分区，局部有序利于聚合。

增量事件 payload：

```json
{
  "eventId": "uuid-or-idempotency-key",
  "etype": "ARTICLE",
  "eid": 766948934001352704,
  "metric": "like",
  "delta": 1,
  "authorId": 1001,
  "occurredAt": "2026-06-21T10:00:00"
}
```

`authorId` 可选，用于 fan-out 到作者 `likes_received`/`favs_received`。

跨模块消费的事件（直接消费，不复用 counter-events）：

- relation 的 `USER_FOLLOWED` / `USER_UNFOLLOWED`（topic `relation-events`）→ 增减 USER 的 following/followers。
- content 的 `PostPublishedEvent`（content outbox，待 content 落地该事件）→ USER 的 posts_count +1。

## 聚合与刷写

消费 `counter-events`：

1. 按 `(event_id, consumer_group)` 在 `counter_consumed_event` 去重，命中则 ack 跳过。
2. `HINCRBY agg:{etype}:{eid} {metric} {delta}`。
3. authorId 存在则 `HINCRBY agg:USER:{authorId} {likes_received|favs_received} {delta}`。
4. ack。失败 → retry → dlq（同 relation 模式）。

刷写（双触发）：

- **稳定模式**：固定 1s。
- **自适应模式**：读负载信号（待刷桶数量、消费 lag、上轮 Lua 耗时），动态周期 ∈ [500ms, 5s]。
- 每次 ≤1000 个 agg key，对每个 key 执行 **`flush_drain.lua` 原子排空**：`HGETALL(aggKey)` → `DEL(aggKey)` → 按 Schema 偏移 `SETRANGE` 批量写入 CountInt。单条 Lua = 排空+落地原子（Redis 单线程，Lua 不可中断），天然不重复刷；多实例并发 flush 安全（先排空者拿 delta，后者得空）。
- 批量分片管道（如每 100 key 一组）避免单次 Lua 长阻塞。

## Lua 脚本

`counter_incr_at_offset.lua`：

- KEYS[1] = `cnt:{etype}:{eid}`；ARGV 成对 `[offset1, delta1, offset2, delta2, ...]`。
- 对每对：`GETRANGE` 8 字节（不足按 0）→ 解码 int64 → `+delta`（<0 clamp 0）→ 编码 → `SETRANGE`。

`flush_drain.lua`：

- KEYS[1] = `agg:{etype}:{eid}`，KEYS[2] = `cnt:{etype}:{eid}`。
- `HGETALL(KEYS[1])` → `DEL(KEYS[1])` → 对每个 (metric, delta) 查 Schema 偏移 → `SETRANGE` 写入 KEYS[2]。
- 排空与落地原子。

## 读路径

- 读计数：`GETRANGE cnt:{etype}:{eid} offset offset+7` 解码。批量读用一次 GETRANGE 取整块再切。
- 读事实（"我赞过吗"）：`GETBIT bm:{metric}:{etype}:{eid}:{chunk} bit`。
- 热点计数可叠加 Caffeine 本地缓存（项目已有）短 TTL（可选/待办）。

## API 设计

对外端点（JWT）：

```text
POST   /api/posts/{postId}/likes           点赞（幂等，位图判定）
DELETE /api/posts/{postId}/likes           取消点赞（幂等）
POST   /api/posts/{postId}/favorites       收藏（幂等）
DELETE /api/posts/{postId}/favorites       取消收藏（幂等）
POST   /api/posts/{postId}/views           阅读（Idempotency-Key 去重）
GET    /api/posts/{postId}/counters        读该文章全部计数
GET    /api/posts/{postId}/counters/liked  读"我是否赞过"（事实）
```

view/share 端点接收 `Idempotency-Key` 请求头（或 body 字段）。

内部 API：

- `CounterFactService.like/unlike/fav/unfav(userId, etype, eid, authorId)`：位图 SETBIT + 跳变发事件。
- `CounterFactService.view/share(userId, etype, eid, idempotencyKey, authorId)`：直接发事件。
- `CounterReadService.getCounts(etype, eid)`：读 CountInt 全部字段。
- `CounterReadService.hasActed(userId, etype, eid, metric)`：读位图。
- 跨模块消费者：`RelationCountConsumer`（relation-events）、`ContentPublishCountConsumer`（content 发布事件）。

用户计数（粉丝数等）通常不单独开端点，嵌入用户资料返回（counter 提供内部读 API 给 user 模块）。

## 持久化与恢复

- Redis 开启 **RDB + AOF**（AOF `appendfsync everysec`）。
- 计数可由事实层重建：like/fav 走分片 `BITCOUNT`；relation/posts/comments 走 SQL `COUNT`。
- **对账/重建任务、位图冷备、删除清理任务均列为待办**，本期不实现。

## 一致性模型

- 事实（位图）：强一致（同步单命令），毫秒级。
- 计数（CountInt）：最终一致（聚合+刷写延迟 = 刷写周期，秒级）。
- 幂等：写事实靠 SETBIT 返回值 / Idempotency-Key；消费靠 `counter_consumed_event`。
- 抗漂移：事实层为基准，计数可周期对账校准（待办）。

## 错误码

```text
COUNTER_ENTITY_NOT_FOUND
COUNTER_INVALID_METRIC
COUNTER_FACT_CONFLICT
COUNTER_EVENT_INVALID
COUNTER_EVENT_CONSUME_FAILED
```

## 测试策略

- **单元**：CountSchema 偏移正确性、CountInt int64 编解码、SETBIT 幂等判定、eventId 生成、聚合/刷写逻辑（fake Redis/repository、mock KafkaTemplate）。
- **Redis 集成**：`counter_incr_at_offset.lua`（并发原子性、越界按 0、负数 clamp）、`flush_drain.lua`（排空+落地原子、不重复刷）、位图 SETBIT/GETBIT、agg→flush 端到端。
- **幂等**：重复点赞不重复计数；view 同 Idempotency-Key 不重复计数、不同 key 各计一次；消费去重。
- **跨模块**：消费 relation/content 事件正确增减用户计数。
- **对账（手动）**：位图 BITCOUNT 与 CountInt 一致性。

## 待办（明确暂不实现）

- 计数对账/重建任务（从事实层校准 CountInt）。
- 高价值位图冷备策略。
- 删除内容/用户时的位图分片清理任务。
- 热点计数 Caffeine 本地缓存。
- comment_count 由未来 comment 模块事件驱动（Schema 已预留 offset）。
- block/mute 等扩展行为。
- 自适应刷写的负载信号阈值精调。

## 后续实施顺序

1. 配置：counter 错误码、Kafka topic/属性、`counter_consumed_event` 表（V3 迁移）、counter 属性绑定。
2. 域与 Schema：CountSchema、CountInt 编解码、事件 payload、topic 常量。
3. Redis 计数层：CountInt Lua（incr-at-offset）、位图操作、agg 操作 + Redis 集成测试。
4. 事实与读服务：CounterFactService（like/fav/view/share）、CounterReadService。
5. 聚合消费与刷写：counter-events 消费者 + agg + 双触发刷新（flush_drain.lua）+ retry/dlq。
6. 跨模块消费：relation-events、content 发布事件 + authorId fan-out。
7. REST 控制器与安全：互动端点 + 读端点。
8. 文档与最终验证。
