# Cache/Feed 模块设计规格

> 本版替换之前的初版，纳入了对抗式评审的全部结论（C1–C3、I1–I7、M1–M7）。

## 目标

为首页"公共 Feed"与用户"我的知文"两个高频读场景提供多层缓存，解决缓存穿透、击穿、雪崩，并把高频计数更新与缓存层解耦。本期两种 Feed 均按**发布时间倒序**（热度排序留可替换接入点，后期实现）。

## 核心思路

- **cursor（keyset）分页 + 无限滚动**：页边界锚定到"某条内容的不可变排序值"，而非"排名"。新发布只进 head，老页边界纹丝不动 → 失效半径 = 一个 head 键，无需版本号、无需 SCAN。
- **三级缓存**：L2 本地完整响应对象 / L1 Redis 页骨架（ID 列表）/ L0 Redis 条目碎片，各层独立 TTL、数据形态不冗余、**各自独立 CacheManager**。
- **写只删（延迟双删）、读负责回填**（cache-aside）。
- **缓存用户无关**：公共 Feed 缓存所有人共享；认证用户读时叠加一层个性化 overlay（likedByMe/favedByMe），不写回缓存。

## 已确认决策

1. 分页用 **cursor（keyset）**，无限滚动模型；page size 可配（1–50）。**两种 Feed 排序键不同**：公共 Feed `(published_at, id) DESC`；"我的知文" `(created_at, id) DESC`（因含草稿、`published_at` 可空）。放弃 offset 与版本号键。
2. 三级缓存：L2=完整响应对象（本地，**独立 CacheManager**）、L1=页骨架 ID 列表+hasMore（Redis）、L0=条目级碎片 JSON+计数碎片（Redis，每条独立 TTL）。
3. 写路径 = cache-aside 延迟双删，不回填；读路径负责回填。**双删只对 L1（Redis）有意义；L2（本地）不受 L1 删除影响**。
4. 失效：发布/删除/可见性变更 → 删 head（双删）；编辑 → 删 L0 碎片（L2 整页靠 TTL 刷新）；**装配时命中墓碑 → 整页回源重建**；计数变化 → 不失效（异步聚合，L0 计数快照靠 TTL/对账刷新）。
5. **内容事件统一契约**：所有内容生命周期事件发到**同一个 `content-events` topic**，带 `eventType` 判别；counter 与 cache 各自的消费者组按 `eventType` 分发（不拆 topic）。
6. 个性化 overlay：认证用户批量 `hasActedBatch` 查 like/fav 状态，叠加 likedByMe/favedByMe；匿名跳过；不污染缓存。
7. 防穿透：NULL 哨兵短 TTL；防击穿：本地 single-flight（L2）+ Redis 分布式锁（L1/L0 回源，**轮询等待 ≤200ms 才回退自建**）；防雪崩：**TTL 相对抖动** `ttl*(1+rand(0..0.3))`。
8. 跨实例 L2 一致性：**跨实例 head 新鲜度 = L2 head TTL（3–5s）**；L1 删除只影响本实例未来 L2 未命中重建路径；本期不上 Pub/Sub（要更快才上）。
9. 热度排序：本期不实现，留可替换"排序键选择"接入点（默认 published_at）。
10. **cache 不引入新 MySQL 表**；回源走 content 的 `FeedSourceQuery` 接口（现在 MySQL keyset 实现，后期 ES 可替换）。

## 模块边界

`cache` 拥有：三级缓存读写、cursor 分页与页骨架、条目碎片装配（含墓碑重建）、个性化 overlay、失效事件消费、定时对账、防三大机制、Feed 读端点。

`cache` 不拥有：内容主数据（content）、计数（counter，cache 只读其快照）、关注关系（relation）。cache 通过 content 的 `FeedSourceQuery` 回源、消费 content 统一生命周期事件、读 counter 的计数快照与 `hasActedBatch`。

## 分页模型

排序键对已发布/已创建内容不可变 → 老窗口稳定。

- **公共 Feed**（`status=PUBLISHED AND visibility=PUBLIC`，按 `published_at`）：
  - head：`ORDER BY published_at DESC, id DESC LIMIT :size`
  - after cursor（游标 = 上一页最后一条 `(published_at, id)`）：
    ```sql
    WHERE (published_at < :at) OR (published_at = :at AND id < :id)
    ORDER BY published_at DESC, id DESC LIMIT :size
    ```
- **"我的知文"**（`author_id=:uid AND status<>'DELETED'`，按 `created_at`，含草稿）：
  - head：`ORDER BY created_at DESC, id DESC LIMIT :size`
  - after cursor（`(created_at, id)`）：
    ```sql
    WHERE (created_at < :at) OR (created_at = :at AND id < :id)
    ORDER BY created_at DESC, id DESC LIMIT :size
    ```
- `size` clamp 到 [1, 50]，缓存键带 size。
- 新发布/新建只进 head；老 cursor 页集合不变（新内容比游标新，不进"小于游标"的集合）。

## 三级缓存数据模型

### L2 — Caffeine 本地（完整响应对象，独立 CacheManager）

- 内容：完整页响应（条目数组 + 游标/size + hasMore），可直接返回。
- 范围：公共 Feed 热门页 + "我的知文"近期页。
- 命中行为：**直接返回**（匿名零 Redis；认证用户再做 overlay）。
- TTL：head 短（3–5s）；cursor 页较长（如 60s，**与中段删除自纠窗口权衡**）。容量受限（W-TinyLFU 驱逐）。
- **独立 CacheManager**：Feed 模块新建自己的 `CacheManager` bean（如 `feedL2CacheManager`），用 `registerCustomCache` 按 cache 名定制 spec；**不碰**现有的 `CaffeineCacheConfig` / `LocalCacheNames`（那是给 `@Cacheable` 用的全局 300s manager，TTL/容量都不适合 Feed）。

### L1 — Redis（页骨架）

- 内容：`{ids: [...], hasMore, cursor, size}`（ID 列表 + 轻索引，不含条目字段）。
- 命中行为：拿 ID 列表 → 去 L0 装配 → 写 L2。**命中不打 DB**。
- TTL：略短于对应 L2 + TTL 相对抖动（避免同刻大面积失效→瞬时回源）。

### L0 — Redis（条目级碎片）

- 内容（每条独立键、独立 TTL）：
  ```json
  {
    "postId": ..., "authorId": ..., "authorName": ..., "cover": ...,
    "title": ..., "summary": ..., "publishedAt": ...,
    "likeCount": ..., "favCount": ..., "viewCount": ...,
    "commentCount": ..., "shareCount": ...
  }
  ```
  - `authorName` 来自 `user_profile.display_name`（回填时查 user 或走 `USER_PROFILE_DETAIL` 本地缓存）。
  - 计数为 **counter 快照**（建碎片时读 CountInt）。**最长滞后 = counter flush（~1s）+ L0 碎片 TTL（X 分钟）**；对账周期内必刷热碎片。
  - `pinned` 字段本期不放（content 无此概念，后期 pin 落地再加）。
- **墓碑**：帖子被删/不可见时，`frag:post:{pid}` 写 `{"deleted":true}` 短 TTL（而非完整碎片）。
- 装配时按 ID 列表 `MGET` 批量取；缺失的按需**批量回源**并回填。

### Redis/本地键约定

```text
L2(本地)/L1(Redis) 页键（同名，L2 是 L1 的本地热副本）:
  feed:public:head:sz{n}
  feed:public:after:{publishedAt}:{id}:sz{n}
  feed:user:{uid}:head:sz{n}
  feed:user:{uid}:after:{createdAt}:{id}:sz{n}

L0 碎片 / 墓碑:
  frag:post:{pid}

NULL 哨兵:
  feed:...:<页键> = 哨兵标记值, 短 TTL（30–60s）
```

## 读路径

```
请求(head 或 after cursor, size) [认证用户 U]:
  1. L2(本地完整页) 命中 ► [overlay] ► 返回                       ← 匿名零 Redis
  2. L2 未命中 ► L1(Redis 骨架 ID 列表)
       命中 ► L0 MGET 碎片
              若任一为墓碑 {"deleted":true} ► 视该 L1 骨架为脏:
                    整页回源(FeedSourceQuery)重建 ids ► 覆写 L1 ► 重新 L0 MGET
              齐全 ► 装配 ► 写 L2 ► [overlay] ► 返回
              缺   ► 批量回源缺的(content+计数+作者名) ► 回填 L0 ► 装配 ► 写 L2 ► [overlay] ► 返回
       未命中 ► single-flight 回源(FeedSourceQuery 查该页 ids) ► 写 L1(+TTL+相对抖动)
                ► L0 MGET/补缺 ► 装配 ► 写 L2 ► [overlay] ► 返回
                (空结果 ► NULL 哨兵短 TTL)
```

- **墓碑重建（C3）**：装配时命中墓碑，**不**静默丢条（否则页返回 size-1、hasMore/cursor 错位），而是**整页回源重建并覆写 L1**（DB 查该窗口自然不含已删帖）。
- **single-flight（I3）**：L2 回源用本地 single-flight（同实例去重）；L1/L0 回源用 Redis 分布式锁 per 缓存键——**抢锁失败者轮询等待 ≤200ms（20ms 间隔）**，超时才回退自建（把惊群窗口压到锁 TTL 级，而非 N 实例同时自建）；锁持有者崩溃靠锁 TTL 释放。

### 个性化 overlay（仅认证用户）

- 对页内每条 post，**批量**查 U 的 counter 位图：`hasActedBatch(U, ARTICLE, [pid...], LIKE)` / `FAV` → 加 `likedByMe`/`favedByMe`。
- ≤50 条，pipeline 1 个 Redis 往返。匿名跳过。**不写回缓存**。
- 依赖 counter 新增 `hasActedBatch`（见跨模块依赖）。

## 写路径（cache-aside 延迟双删）

写路径**只删/失效，不回填**；延迟（>主从延迟+重建，约 0.5–1s）再删一次。回填交给读路径。

**注意**：双删只对 **L1（Redis）** 有意义；L2（本地 Caffeine）**不受 L1 删除影响**，跨实例 L2 收敛靠其自身 TTL（见下）。

## 失效机制（按 content 统一事件的 eventType）

统一事件契约（`content-events` topic）：
```json
{
  "eventId": "uuid",
  "eventType": "POST_PUBLISHED | POST_UNPUBLISHED | POST_DELETED | POST_EDITED | POST_VISIBILITY_CHANGED",
  "postId": ..., "authorId": ..., "occurredAt": "...",
  "changes": { ... }
}
```
Kafka key = `post:{postId}`。cache 的消费者组按 `eventType` 分发：

| eventType | L0 碎片 | 公共 Feed 页 | "我的知文"页 |
|---|---|---|---|
| **POST_PUBLISHED** | 首读时建 | **删 `feed:public:head`（双删）**；cursor 页不动 | **删 `feed:user:{author}:head`（双删）** |
| **POST_EDITED** | **删 `frag:post:{pid}`** | 不动（页只存 id） | 不动 |
| **POST_UNPUBLISHED / POST_DELETED** | **写墓碑** `{"deleted":true}` | 删 head | 删该用户 head |
| **POST_VISIBILITY_CHANGED** | 离开 PUBLIC→写墓碑；否则删碎片 | 进/出 PUBLIC→删公共 head | 删作者 head |

- **字段编辑滞后（I4）**：L2 存完整页（含旧字段），编辑只删 L0 → 含该帖的 **cursor 页**在该字段最长滞后 = 该页 **L2 TTL（~60s）**；**head 页**滞后 ≤ 5s。可接受。
- **中段删除/可见性**：墓碑 + 装配时整页重建（C3）+ TTL + 对账兜底，不再有 hasMore/cursor 错位 bug。
- **计数变化**：走 counter 自己的异步聚合直接写 CountInt；cache **不订阅、不失效**；L0 计数字段靠 TTL/对账刷新。

## 防穿透 / 击穿 / 雪崩

- **穿透**：空窗口（游标到尽头）/ 已删碎片 → NULL 哨兵短 TTL。
- **击穿**：L2 本地 single-flight；L1/L0 回源 Redis 分布式锁 per 键，轮询 ≤200ms 才回退。
- **雪崩**：所有 TTL 加 **相对抖动** `ttl * (1 + rand(0..0.3))`（head 3–5s 的抖动是 ±1.5s，不是绝对 10–30s）。

## 跨实例 L2 一致性（实话版，C2）

- **跨实例 head 新鲜度上界 = L2 head TTL（3–5s）**。
- 删 L1（Redis）head 键**只影响本实例未来 L2 未命中的重建路径**；**不影响**其他实例已命中的 L2（它们根本不查 L1）。
- cursor 页天然稳定（发布不影响），跨实例无一致性问题。
- 本期**不上 Pub/Sub**；若后期要求 head 亚秒级跨实例收敛，再加 Pub/Sub 广播 L2 淘汰（可选增强）。

## 定时对账任务

- 周期（如 30–60s）扫热页：用 `FeedSourceQuery` 重算 head/热 cursor 页 ID 列表，与 L1 比对漂移则重建。
- 用 DB 计数刷新**热碎片**计数字段（兜底计数滞后）。
- 清理漏掉的失效（事件丢、墓碑残留等）。

## 跨模块依赖

### content 侧
1. **`FeedSourceQuery` 接口 + MySQL keyset 实现 + V4 索引**（C1，临时方案；后期 ES 可替换）：
   - 接口：
     ```java
     FeedPage findPublicFeedAfter(PublishedCursor cursor, int size);   // status=PUBLISHED,visibility=PUBLIC, sort (published_at,id)
     FeedPage findUserFeedAfter(Long userId, CreatedCursor cursor, int size); // author=uid,status<>DELETED, sort (created_at,id)
     // FeedPage = {ids, hasMore, nextCursor}
     ```
   - V4 迁移 `V4__feed_indexes.sql`：
     ```sql
     ALTER TABLE content_post ADD KEY idx_content_post_pub_feed    (status, visibility, published_at DESC, id DESC);
     ALTER TABLE content_post ADD KEY idx_content_post_author_feed (author_id, created_at DESC, id DESC);
     ```
2. **统一内容生命周期事件**（I1）：把现有 `PostPublishedEvent` 泛化为带 `eventType` 的统一事件（或保留各类型 Spring 事件、由一个 `@TransactionalEventListener(AFTER_COMMIT)` publisher 统一发 Kafka envelope 到 `content-events`）。新增 `POST_UNPUBLISHED/POST_DELETED/POST_EDITED/POST_VISIBILITY_CHANGED`。content 的 `publish/unpublish/delete/updateMetadata/visibility` 各处发对应事件。

### counter 侧
1. **消费者按 eventType 分发**（I1 修复）：现有 `ContentPublishCountConsumer` 改为 switch——`POST_PUBLISHED → +1 posts`、`POST_UNPUBLISHED/POST_DELETED → -1 posts`（顺手修了只增不减）、其它 → 忽略。不再硬编码按 POST_PUBLISHED 解析。
2. **新增 `hasActedBatch(userId, etype, List<Long> eids, metric) → Map<Long,Boolean>`**（M2）：在 `CounterStore` / `CounterReadService` 加批量位图查询（pipeline GETBIT），供 overlay 使用。

### cache 侧
- 读计数快照调 `CounterReadService.getArticleCounters`（已有）；overlay 调 `hasActedBatch`（新增）。

## 计数与缓存的解耦

- counter 异步聚合直接写 CountInt；cache 不订阅计数事件、不因计数变化失效。
- cache 的 L0 计数字段 = 读时快照，靠 TTL/对账刷新。
- 这把"计数高频写"与"缓存稳定性"完全切开。

## @Profile 纪律（M7）

- cache 的 Kafka 消费者（消费 content-events）：`@Profile("!test & !integration")`（与现有 `ContentPublishCountConsumer` 一致）。
- L1/L0 的 Redis 服务、L2 CacheManager、Feed 服务、控制器：`@Profile("!test")`（test profile 无 Redis）。
- 单元测试直接构造 + fake；集成测试用真 Redis（integration profile）。

## SecurityConfig 路由（M6）

- 公共 Feed 读端点（如 `GET /api/feed/public`）：加入 `permitAll`（与 `/api/posts` 同列）。
- "我的知文"端点（如 `GET /api/feed/me`）：`authenticated()`，**且路径不能被 `/api/posts` 的公开 matcher 误匹配**（用独立 `/api/feed/` 前缀避免歧义）。

## 错误码

```text
CACHE_FEED_UNAVAILABLE        回源失败且无缓存可降级
CACHE_FRAGMENT_INCOMPLETE     碎片装配失败（部分条目无法获取）
CACHE_INVALID_CURSOR          游标非法/过期
CACHE_INVALID_PAGE_SIZE       size 越界
```

## 测试策略

- **单元**：cursor 计算（两种排序键）、键构造、L0 装配（含缺失批量回填 + 墓碑触发整页重建）、overlay（批量 hasActed 映射）、失效动作映射（eventType→删哪些键）、TTL 相对抖动。
- **Redis 集成**：L2/L1/L0 真实读写、single-flight 分布式锁（含等待/回退）、NULL 哨兵、墓碑重建、L0 缺失批量回源回填。
- **集成**：head/after 翻页端到端（两种 Feed）、发布后 head 刷新而 cursor 页稳定、编辑后 L0 刷新（head 5s 内、cursor 页 60s 内）、删除墓碑→装配整页重建、计数快照、overlay 匿名 vs 认证、防穿透。

## 待办（明确暂不实现）

- **热度排序**（留可替换排序键接入点）。
- **Pub/Sub 跨实例 L2 淘汰**（增强，要 head 亚秒级跨实例收敛时上）。
- **post→page 反向索引**（用于精准中段失效；现用墓碑重建 + TTL + 对账兜底）。
- **ES 索引/搜索模块**：后期提供 `FeedSourceQuery` 的 ES 实现。ES 文档字段建议 `postId, authorId, status, visibility, publishedAt, createdAt`（Feed 用）+ `title, summary, tags`（搜索用）；Feed 查询 = ES `search_after`（游标=sort 值）+ filter + sort，与现在 MySQL keyset 语义对齐。
- **pin**（content 暂无此概念；落地后 L0 加 `pinned` 字段、cache 处理 pin 事件）。

## 后续实施顺序

- **step 0（前置，跨模块）**：counter 加 `hasActedBatch` + 改 `ContentPublishCountConsumer` 按 eventType 分发；content 统一生命周期事件契约（eventType 判别）。
- **step 1**：配置 + 错误码 + cache 属性 + **独立 `feedL2CacheManager`**。
- **step 2a**：`V4__feed_indexes.sql` + content 的 `FeedSourceQuery` keyset 实现（阻塞 3/4/5）。
- **step 2b**：content 4 个生命周期事件（统一契约）——可与 3–6 并行，阻塞 7。
- **step 3**：L0 碎片存储与装配（含批量回填 + 墓碑重建）。
- **step 4**：L1 页骨架存储。
- **step 5**：L2 本地缓存（独立 CacheManager）+ 三级读路径编排。
- **step 6**：single-flight（本地 + 分布式锁 + 等待策略）——从 step 5 单拆，独立测试。
- **step 7**：个性化 overlay（hasActedBatch）。
- **step 8**：失效事件消费（eventType 分发 + 删 head/碎片/墓碑）+ 延迟双删。
- **step 9**：定时对账任务。
- **step 10**：REST 控制器（Feed 读端点）+ 安全 + 文档。
