# Cache / Feed 模块

## 模块职责

`cache.feed` 模块负责"内容 Feed 流"的高性能读路径与缓存维护。它是 `content` 模块（贴文）的下游读模型：把 `content` 的发布 / 编辑 / 删除事实，物化成一组可被多级缓存高效服务的 Feed 页。

`cache.feed` 拥有的能力：

- **三级缓存读路径**（L2 本地 Caffeine → L1 Redis 骨架 → L0 Redis 片段）：一次 Feed 读命中最热路径只走本地内存，避免任何 Redis / DB 调用。
- **Keyset（cursor）分页**：公共 Feed 按 `published_at` 倒序、"我的" Feed 按 `created_at` 倒序，用 `(timestamp, id)` 双列 cursor 形成严格全序，保证分页稳定。
- **个性化 overlay**：在已装配的页上为登录读者打 `likedByMe` / `favedByMe` 位，复用 `counter` 模块的 `hasActedBatch` 位图批量查询。
- **失效机制**：消费 `content-events` 统一事件（`eventType` 分发：PUBLISHED / EDITED / UNPUBLISHED / DELETED / VISIBILITY_CHANGED），按事件语义失效对应的 L1 骨架 / L0 片段 / 墓碑，并执行 cache-aside 延迟双删。
- **对账调度**：周期性刷新热点公共 head 骨架，自愈失效事件漏掉 / TTL 未到的漂移骨架。

`cache.feed` 不拥有的能力（属于其他模块）：

- 贴文事实、正文存储、发布六阶段归 `content` 模块（`cache.feed` 只消费其事件、并回源 `FeedSourceQuery`）。
- 互动事实（点赞 / 收藏位图）归 `counter` 模块（`cache.feed` 只读 `hasActedBatch` 做 overlay，不写位图）。
- 用户展示名归 `user` 模块（`cache.feed` 在 L0 片段回填时按 authorId 查 `UserQueryService.findAccountById`，结果缓存在片段里）。

## 架构与数据流

```text
读路径（FeedReadService，三级缓存）             写/失效路径（cache-aside 双删）
                                                  content-events（统一事件）
  GET /api/feed/public?cursor&size                    │
   └─ FeedController                                    ▼
        ├─ /public → permitAll（Bearer 可选 → overlay）   FeedInvalidationConsumer（按 eventType 分发）
        └─ /me     → 认证                                   ├─ POST_PUBLISHED        删 public/user head 骨架（双删）
            │                                               ├─ POST_EDITED           删 L0 片段
            ▼                                               ├─ POST_UNPUBLISHED/DELETED  墓碑 L0 + 删 head 骨架（双删）
        FeedReadService.readPage（共享管线）                  └─ POST_VISIBILITY_CHANGED  删 L0 片段 + 删 public head 骨架（双删）
          1. L2（Caffeine，feedL2CacheManager）──hit── overlay ── 返回
             │ miss
             ▼
          2. NULL 哨兵？──yes── 返回空页                 周期对账（FeedReconciliationScheduler）
             │ no                                          │ 每 reconciliation-interval-ms（默认 30s）
             ▼                                             ▼
          3. L1 骨架 miss → 单飞回源 FeedSourceQuery → 写 L1   对热点 public head（size 10/20）：
             │ hit                                            重新查 source，比对 cached.ids
             ▼                                                漂移 / 缺失 → 重写骨架；命中 → 不动
          4. L0 片段 multiGet
             │
             ▼
          5. 墓碑？──yes── 单飞重建骨架 + 写 L1 + 再 multiGet
             │ no
             ▼
          6. 缺失片段批量回填（content + counter + user）→ 写 L0
             │
             ▼
          7. 装配 → 写 L2 → overlay → 返回
```

### 三级职责

| 层 | 载体 | 内容 | TTL | 命中省下的开销 |
| --- | --- | --- | --- | --- |
| **L2** | Caffeine 本地（`feedL2CacheManager`） | 完整装配的 `FeedPageResponse`（每页键） | head 5s / cursor 60s | 命中即零 Redis、零 DB，直接 overlay 返回 |
| **L1** | Redis（`skel:{pageKey}`） | 页骨架 `FeedPage`（id 列表 + hasMore + nextCursor） | 动态 TTL，默认 base 30s，按 pageKey 热度加 0/60/120s + jitter（见[热 key 探测](#热-key-探测与动态-ttl)） | 命中即省 DB keyset 查询，仍需 L0 multiGet 装配 |
| **L0** | Redis（`frag:post:{id}`） | 单帖片段 `PostFragment` JSON | 动态 TTL，回填时继承当前 pageKey 热度（`ttlFor(pageKey, l0.ttlSeconds)`） | 骨架命中后一次 `MGET` 拿全部片段 |

> L2 故意短 TTL（head 5s）：它的价值是吸收"极短时间内同一页的重复读"，而非长期缓存；较长的 L1/L0 承担长期命中。

## 分页模型（Keyset cursor）

Feed 用 keyset 分页而非 offset：cursor 是 `(timestamp, id)` 双列，与排序键 + 主键形成严格全序，插入 / 删除都不会让分页漂移。

| Feed | 排序列（倒序） | cursor 列 | 含义 |
| --- | --- | --- | --- |
| 公共 Feed | `published_at` | `(published_at, id)` | 全站 PUBLISHED + PUBLIC 贴文 |
| 我的 Feed | `created_at` | `(created_at, id)` | 当前用户所有贴文（draft + published，含非 PUBLIC，排除 DELETED） |

- **head 页**：cursor 缺省（`GET /api/feed/public` 不带 `cursor`），取最新一页。
- **cursor 页**：取严格"在 cursor 之后"的下一页；`nextCursor` 由本页最后一行的 `(timestamp, id)` 派生，`hasMore=false` 时为 `null`。
- **cursor 透传**：cursor 对客户端是不透明 token，格式 `{ISO LocalDateTime},{id}`（如 `2026-06-21T10:00,123`）。客户端原样回传 `nextCursor` 即可翻页。
- **页大小**：默认 20，钳制到 `[1,50]`。不同 size 产生不同的 cache key（`sz10` / `sz20` / `sz50`），所以控制器端的钳制同时也约束了 key 空间。

## 读路径详解（FeedReadService）

`readPage` 是四种 Feed（公共 head / 公共 cursor / 我的 head / 我的 cursor）的共享管线，只有"回源查询"那一处是 Feed 特定的（`BackfillQuery` 函数接口注入）：

1. **L2 检查**：按 `(cacheName, pageKey)` 取 Caffeine 条目。命中即 overlay 返回，零 Redis / DB。
2. **NULL 哨兵检查**：`null:{pageKey}` 存在表示"这一页可证为空"（短窗口），直接返回空页，跳过 DB。
3. **L1 骨架检查**：`skel:{pageKey}` miss 时，**单飞**（`FeedSingleFlight.executeWithLock`）回源 `FeedSourceQuery`。空结果写 NULL 哨兵返回空页；非空写 L1 骨架。单飞只包住**源查询**（最贵的 DB call），L1 写与后续装配便宜且幂等，跑在锁外。
4. **L0 片段装配**：`MGET` 一次性取骨架里所有 id 的 `PostFragment`。
5. **墓碑检查（C3 修复）**：`anyTombstone(ids)` 命中说明骨架里有已删帖 → 骨架过期。单飞重建骨架、写 L1、再 multiGet。重建后仍空的页（如全删了）写 NULL 哨兵返回空页。
6. **缺失片段回填**：到这步仍缺的片段（刚过期、或重建后并发删除竞态）按 id 批量回填 —— `content.findPostById` + `counter.getArticleCounters` + `user.findAccountById` 拼成 `PostFragment`，逐个写 L0。已删帖的回填返回空，该 id 不装配（页比骨架短，`hasMore`/`nextCursor` 仍权威）。
7. **装配 + 提升 L2**：按骨架 id 序拼出 `FeedPageResponse` 写 L2，再 overlay 返回。

### 单飞（FeedSingleFlight）

回源（步骤 3）与墓碑重建（步骤 5）都走单飞：

- **JVM 内**：`CompletableFuture` 去重，同一 pageKey 的并发 miss 折叠成一次源查询。
- **跨集群**：Redis `SET NX EX` 分布锁（`lock:{key}`），持锁者查询、其余节点 poll-wait。
- **兜底**：锁等待超时则本地自建一次（bounded self-build），避免一个慢节点拖垮整页可用性。

单飞只包源查询；L1 写 / L0 装配在锁外跑。

## 写路径（cache-aside 延迟双删）

`FeedInvalidationConsumer` 消费 `content-events`，按 `eventType` 失效缓存。L1 head 骨架的删除采用 cache-aside 延迟双删以关闭"删除与并发回填"竞态：

```text
消费者删 head 骨架 ──▶ （此窗口内一个并发读把骨架又填回来了） ──▶ 1s 后第二次删除 ──▶ 骨架最终为空
```

- 第一次删除：关上新读的门。
- 第二次删除（延迟 1s）：清掉竞态窗口内被回填的值。
- 1s 远小于 L1 head TTL（4s+），双删落在 TTL 窗口内。

> 仅 head 骨架需要双删。cursor 页由其 keyset cursor 天然界定、靠 TTL 老化，不做双删（按前缀扫描失效 cursor 页的开销远大于它带来的新鲜度收益）。

## 失效机制（统一 content 事件 eventType 分发表）

content 模块统一了事件信封（`ContentPostEvent` + `ContentPostEventType`），消费者按 `eventType` 字段分发：

| eventType | 失效动作 | 理由 |
| --- | --- | --- |
| `POST_PUBLISHED` | 删 public head 骨架 + 作者 user head 骨架（双删） | 新帖进入 head 页，骨架过期 |
| `POST_EDITED` | 删该帖 L0 片段 | 标题 / 摘要 / 封面 / 计数变了；骨架（id 列表）未变 |
| `POST_UNPUBLISHED` / `POST_DELETED` | 墓碑该帖 L0 片段 + 删 public/user head 骨架（双删） | 帖子从所有 Feed 消失；墓碑让任何还指向它的骨架触发重建 |
| `POST_VISIBILITY_CHANGED` | 删该帖 L0 片段 + 删 public head 骨架 | 可见性变化可能让帖子进 / 出公共 Feed；作者 user Feed 含所有可见性，其骨架不受影响 |

- 失效的 head 页 size 覆盖 `{10, 20, 50}`（控制器常见钳制值）。
- **墓碑 vs 删除**：`POST_UNPUBLISHED` / `POST_DELETED` 用墓碑（`frag:post:{id} = TOMBSTONE`，短 TTL 30s），区别于普通删除 —— 墓碑是"已删"信号，触发读路径的骨架重建；普通删除只是"过期"，触发回填。
- **失败处理**：任何异常（坏 JSON、缺字段、Redis 宕）记日志吞掉 + ack —— 毒消息不得卡住 partition。一次漏失效退化成"读到的骨架过期，直到 TTL 到期或对账扫到"，都有界且安全。

## 个性化 overlay（FeedOverlayService）

`likedByMe` / `favedByMe` 是**读时、按用户**的关注点，从不进缓存：

- L2 条目里这两个字段恒为 `null`（"未应用 overlay"哨兵）。
- 装配（或读 L2）之后，`FeedReadService.overlay` 对登录读者委托 `FeedOverlayService`：**一次** `counterReadService.hasActedBatch(userId, ARTICLE, postIds, [LIKE, FAV])` 同时盖两个位。
- 匿名读者（`requesterIdOrNull == null`）跳过 overlay，两位保持 `null` —— 客户端据此区分"未应用 overlay"与"应用了 overlay、用户未操作"。
- 缺位默认 `false`：位图无记录即"未操作"。

## 防穿透 / 击穿 / 雪崩

| 风险 | 对策 | 实现 |
| --- | --- | --- |
| **缓存穿透**（反复查不存在的页） | NULL 哨兵 `null:{pageKey}`（30s） | 空结果写哨兵，后续空页读跳过 DB |
| **缓存击穿**（热 key 过期瞬间并发回源） | 单飞（JVM 内 `CompletableFuture` 去重 + 跨集群 Redis `SET NX EX` 锁） | 同一 pageKey 并发 miss 折叠成一次源查询 |
| **缓存雪崩**（大批 key 同期过期） | TTL 相对抖动：`ttl * (1 + rand(0..jitterRatio))`，`jitterRatio=0.3` | `SkeletonStore.applyJitter` / `FragmentStore.applyJitter`；大批回填后 TTL 落在 `[base, base*1.3]` |

墓碑（30s）+ NULL 哨兵（30s）都用短固定 TTL：只够撑过重建 / 空页窗口，不长期占位。

## 热 key 探测与动态 TTL

Feed 缓存支持本地热 key 探测，用最近一个滑动窗口内的 pageKey 访问次数动态调整 Redis 写入 TTL（`FeedHotKeyDetector`）。

- 只统计 pageKey，不统计 `frag:post:{postId}` —— 片段本身没有"热度"概念，它的 TTL 继承自回填时所在页的 pageKey 热度。
- L1 skeleton 在 `SkeletonStore.put` 里用 `FeedHotKeyDetector.ttlFor(pageKey, baseTtl)` 算出最终 TTL；热 pageKey 的骨架活得更久。
- L0 fragment 在 `FeedReadService.backfillMissing` 里同样调 `ttlFor(pageKey, props.l0().ttlSeconds())`，把结果原样传给 `FragmentStore.put`。`FragmentStore` 不再做自己的抖动 —— 抖动由 detector 在 `ttlFor` 里统一加，避免二次抖动。
- 读请求只更新本地热度计数（`record(pageKey)`），**不刷新 Redis TTL**。Redis TTL 只在写 / 回填时设置一次。
- 业务失效（`FeedInvalidationConsumer` 删 head 骨架时）调 `reset(pageKey)` 清掉对应 pageKey 的本地热度；自然 TTL 过期**不** reset（detetor 不知道 Redis 那侧何时过期）。

默认窗口为 60 秒、每 10 秒一个时间片（`window-seconds=60`, `slice-seconds=10`，bucket 数 = 6）。每个 pageKey 维护一个 `int[] counts`（每槽一个计数）和 `lastAccessTick`。`@Scheduled` 时间片轮转时：清空即将被复用的槽位、并对连续半个窗口（`coldThresholdTicks = ceil(bucketCount * coldThresholdRatio) = 3`）未访问的 key 直接 evict。还有 `max-tracked-keys`（默认 50_000）作为全局上限，超过则新 key 直接被忽略（保护本地内存）。

热度等级（`FeedHotKeyDetector.ttlFor`）：

| heat（窗口内访问次数） | 等级 | 最终 TTL |
| --- | --- | --- |
| `< 50`（`low-threshold`） | 低热度 | `base-ttl-seconds + jitter` |
| `50 - 200` | 中热度 | `base-ttl-seconds + medium-extra-ttl-seconds(60s) + jitter` |
| `> 200`（`high-threshold`） | 高热度 | `base-ttl-seconds + high-extra-ttl-seconds(120s) + jitter` |

`base-ttl-seconds` 默认 30s（独立于 `l1.headTtlSeconds` / `l0.ttlSeconds`，专门给热 key 探测用）。`jitter` 在 `[jitter-min-seconds, jitter-max-seconds]`（默认 `[5, 10]`）里取随机整数，用于错开批量回填的过期时间。

> 当 `hot-key.enabled=false` 时，detector 不再 track 任何 key，`ttlFor` 直接返回 `传入的 baseTtl + jitter`（即退化成"无热度的固定 base"），便于灰度关闭。

该机制是**本 JVM 本地优化**，不保证多实例热度一致；它只影响缓存命中率与新鲜度，**不影响业务正确性** —— TTL 长短只决定骨架何时过期回源，骨架内容始终由源查询和对账保证正确。

## 跨模块依赖

| 依赖方向 | 用途 | 接口 |
| --- | --- | --- |
| `cache.feed` → `content` | 回源 Feed 页（keyset 分页） | `FeedSourceQuery`（接口，由 `content` 侧实现 `ContentFeedSourceQuery`） |
| `cache.feed` → `content` | 回填单帖元数据 | `ContentQueryService.findPostById` |
| `cache.feed` → `counter` | 个性化 overlay 批量查位图 | `CounterReadService.hasActedBatch` |
| `cache.feed` → `counter` | 回填单帖计数 | `CounterReadService.getArticleCounters` |
| `cache.feed` → `user` | 回填作者展示名 | `UserQueryService.findAccountById` |
| `cache.feed` ←（消费）← `content` | 统一 content 事件失效 | `FeedInvalidationConsumer` 读 `content-events` |

依赖方向：`cache.feed` 单向依赖 `content` / `counter` / `user`，不反向。`content` / `counter` / `user` 不感知 `cache.feed`。

## API 列表

所有接口挂在 `/api/feed`，由 `SecurityConfig` 的有序请求匹配器决定鉴权：

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/feed/public` | **公开**（permitAll） | 公共 Feed（PUBLISHED + PUBLIC，`published_at` 倒序）。`cursor` 可选（缺省 = head），`size` 默认 20 钳制 `[1,50]`。Bearer 可选 → 登录读者带 `likedByMe`/`favedByMe` overlay，匿名则两位为 `null` |
| `GET` | `/api/feed/me` | 认证 | 当前用户的 Feed（draft + published，所有可见性，排除 DELETED，`created_at` 倒序）。`cursor` / `size` 同上 |

### cursor 格式

`cursor={ISO LocalDateTime},{id}`，如 `cursor=2026-06-21T10:00,123`。

- 缺省 / 空白 → head 页。
- 格式错误（无逗号、时间戳 / id 不可解析）→ `CACHE_INVALID_CURSOR`（→ 400），客户端应重置回 head。
- `nextCursor` 为 `null` 时（`hasMore=false`）客户端停止翻页。

### 返回体

统一 `ApiResponse<FeedPageResponse>`（`{success, message, data}`），`data` 为：

```json
{
  "items": [
    { "postId": 1, "authorId": 7, "authorName": "alice", "cover": "...",
      "title": "...", "summary": "...", "publishedAt": "2026-06-21T10:00:00",
      "likeCount": 12, "favCount": 3, "viewCount": 100, "commentCount": 2, "shareCount": 1,
      "likedByMe": true, "favedByMe": false }
  ],
  "hasMore": true,
  "nextCursor": { "timestamp": "2026-06-21T10:00:00", "id": 123 }
}
```

- 匿名读 `/public`：`likedByMe` / `favedByMe` 为 `null`（哨兵）。
- 认证读 `/public`：两位为 `true`/`false`。
- `/me`：两位恒为 `null`（"我的贴文"视角本就第一人称，无 overlay）。

## 本地测试方式

### 单元测试

```bash
./mvnw.cmd test '-Dspring.profiles.active=test'
```

- `test` profile 排除 `DataSourceAutoConfiguration`、`RedisAutoConfiguration`、`KafkaAutoConfiguration`，不依赖外部服务。
- 服务 / 调度器 / 消费者直接用 Mockito mock 构造（`FragmentStore` / `SkeletonStore` / `FeedSourceQuery` / `CounterReadService` / `ContentQueryService` / `UserQueryService`），L2 用真实 `CaffeineCacheManager`，验证三级管线、墓碑重建、单飞、overlay、对账分支、失效分发 —— **不依赖真实 Redis / Kafka / DB**。
- `FeedReconciliationSchedulerTest` 直接调 `reconcile()`（`@Scheduled` 注解在容器外惰性），覆盖 drifted / fresh / missing 三分支 + 空源页不缓存 + 单 size 失败不中断扫描。

### Redis / MySQL 集成测试

```bash
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- 依赖本地 MySQL（`localhost:3307`）+ Redis（`localhost:6379`），Flyway 自动应用 `V1`/`V2`/`V3`/`V4`。
- `FragmentStoreIntegrationTest` / `SkeletonStoreIntegrationTest` 在真实 Redis 上跑 `multiGet` / 墓碑 / NULL 哨兵 / TTL 抖动序列化。
- Feed 读路径 / 控制器 / 消费者 / 对账调度在 `test` / `integration` profile 下均被 `@Profile` 门控，集成测试不启动真实 `@KafkaListener` 容器与 `@Scheduled` 任务。

### @Profile 门控总览

| Bean | `@Profile` | 含义 |
| --- | --- | --- |
| `FeedController` | `!test` | test profile 下不装配（依赖 `@Profile("!test")` 的 `FeedReadService`） |
| `FeedReadService` / `FeedOverlayService` | `!test` | 依赖 `@Profile("!test")` 的 `FragmentStore` / `SkeletonStore` / `CounterReadService` |
| `FragmentStore` / `SkeletonStore` | `!test` | test profile 下由 fake / mock 替换 |
| `FeedInvalidationConsumer` | `!test & !integration` | 自动化测试都不启动真实 Kafka 监听容器 |
| `FeedReconciliationScheduler` | `!test & !integration` | 自动化测试不启动真实 `@Scheduled` 任务 |

因此：**只有生产 / 手动本地运行（默认 profile）时**，消费者与对账调度才随应用一起启动，对接真实 Kafka / Redis 链路。

### Schema 来源

Feed 索引由 Flyway 迁移 `V4__feed_indexes.sql` 提供（公共 / 用户 Feed 的 keyset 分页索引）。`cache.feed` 本身不落 MySQL，只落 Redis。

## 待办

- **热度排序接入点**：当前公共 Feed 按 `published_at` 倒序。未来接入热度（counter 的 view / like / fav 衰减分）时，排序键变化 → cursor 列变化 → cache key 命名空间需隔离（如 `feed:public:hot:...`），`FeedSourceQuery` 实现切换。
- **Pub/Sub L2 淘汰**：当前 L2 靠短 TTL（head 5s）自然过期，多实例间存在最长 5s 的脏读窗口。可用 Redis Pub/Sub 在失效时广播 L2 淘汰，把窗口压到毫秒级。
- **ES FeedSourceQuery 实现**：`FeedSourceQuery` 当前由 content 侧基于 MySQL keyset 实现；复杂筛选（标签 / 话题 / 全文）可换成 Elasticsearch 实现，`cache.feed` 无需改动（接口隔离）。
- **pin（置顶贴文）**：head 页需支持置顶帖，置顶帖不参与 keyset 分页、单独拼到页首；失效逻辑需对置顶事件敏感。
- **片段计数刷新**：当前 L0 片段计数只在回填时拉一次，靠 TTL（300s）老化。可加一个轻量周期任务对热点片段批量刷新计数（不重拉元数据），降低计数新鲜度延迟。
- **cursor 页失效策略**：当前 cursor 页只靠 TTL 老化，不做主动失效。若未来需要更短的新鲜度延迟，可评估按 authorId / 时间窗的有限前缀扫描失效。
