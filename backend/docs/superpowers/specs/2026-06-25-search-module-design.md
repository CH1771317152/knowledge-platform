# Search 模块设计

## 1. 目标

Search 模块用于解决文章数量增长后，MySQL 全文查询性能不足的问题。第一阶段只支持公开文章搜索，搜索范围固定为：

- `status = PUBLISHED`
- `visibility = PUBLIC`
- `content_type = ARTICLE`

搜索结果复用 Feed 列表展示结构，避免搜索命中后再批量回查 MySQL。搜索接口只在结果返回前调用计数模块补充当前用户态，例如当前用户是否点赞、是否收藏。

核心排序目标为：

1. 标题命中优先。
2. 热度更高优先。
3. 更新时间或发布时间更近优先。

## 2. 模块边界

Search 模块只负责 Elasticsearch 索引、查询、排序、分页和搜索结果组装。

Content 模块仍然是文章事实源，负责文章生命周期、正文对象地址、元数据和可见性。Search 只通过内容事件与内容查询能力构建索引文档。

Storage 模块仍然负责 OSS 交互。Search 在构建索引时读取正文 markdown 对象，转换为可检索正文文本。

Counter 模块仍然是计数事实源。Search 索引内保存点赞、收藏、浏览等计数快照，用于排序；查询返回时再调用 Counter 实时补充用户态。

User 模块仍然是作者资料事实源。Search 索引内保存 Feed 展示所需的作者快照，例如作者 ID、昵称、头像。

Cache 模块继续负责 Feed 缓存，不负责搜索结果缓存。搜索结果是否缓存后续根据访问特征单独设计。

## 3. 总体架构

Search 模块新增包：

```text
src/main/java/com/platform/search/
--config          Elasticsearch 与搜索参数配置
--domain          搜索文档、查询条件、cursor、排序模型
--application     搜索查询服务、索引构建服务、索引重建服务
--infrastructure  Elasticsearch 适配器、Kafka 消费者
--controller      搜索接口
```

核心链路分为两条：

1. 内容索引链路：`content_outbox -> outbox relay -> content-events -> search consumer -> Elasticsearch`
2. 计数快照链路：`counter flush -> reliable snapshot queue -> counter-snapshot-events -> search consumer -> Elasticsearch partial update`

查询链路：

```text
GET /api/search/posts
  -> SearchPostQueryService
  -> Elasticsearch search_after 查询
  -> 从 ES _source 构造 FeedItemResponse
  -> CounterReadService.hasActedBatch 补充 likedByMe/favedByMe
  -> 返回 cursor 分页结果
```

## 4. Content 事件可靠性

当前 Content 模块使用 `@TransactionalEventListener(AFTER_COMMIT)` 发送 `content-events`，属于轻量 best-effort 方案。Search 模块将搜索索引作为核心读模型，因此同步升级为可靠 outbox 方案。

`content-events` 的 Kafka 消息契约保持为现有“裸 JSON 内容事件”，不切换为 Canal flat message。这样现有 Cache 和 Counter 消费者不需要同时理解两种 envelope。`content_outbox` 只是可靠投递机制，outbox relay 负责把 `payload_json` 原样投递到 `content-events`。

Content 写事务中同时完成：

1. 更新文章主表、正文表、标签表等事实数据。
2. 写入 `content_outbox` 事件表。

事件投递由 outbox relay 负责，将 `content_outbox.payload_json` 投递到 Kafka `content-events` topic。Search、Cache、Counter 分别使用自己的 consumer group 消费同一份内容生命周期事件。

第一版不再让 Canal 直接投递 `content_outbox` 到 `content-events`，避免 Kafka topic 内出现 Canal envelope 和业务裸 JSON 混用。Canal 仍可作为后续 relay 的数据源，但不能改变最终 topic 的消息契约。

`content_outbox` 至少包含：

```text
id              bigint auto increment，重放和高水位使用
event_id        varchar，业务事件唯一 ID
aggregate_type  varchar，固定 POST
aggregate_id    bigint，postId
event_type      varchar，例如 POST_PUBLISHED
payload_version int，第一版为 1
payload_json    json，最终投递到 Kafka 的裸 JSON
source_version  bigint，内容源版本，用于乱序保护
occurred_at      datetime，业务事件发生时间
created_at       datetime，outbox 写入时间
published_at     datetime nullable，relay 成功投递后记录；如果使用纯 binlog relay 可不依赖该字段
```

唯一约束：

- `event_id` 唯一。
- Kafka key 固定为 `POST:{postId}`，保证单篇文章事件进入同一 partition。

`payload_json` 必须包含：

```json
{
  "eventId": "...",
  "eventType": "POST_PUBLISHED",
  "postId": 123,
  "authorId": 456,
  "status": "PUBLISHED",
  "visibility": "PUBLIC",
  "sourceVersion": 12,
  "occurredAt": "2026-06-25T12:00:00"
}
```

Search 消费的内容事件包括：

- `POST_PUBLISHED`：构建或覆盖 ES 文档。
- `POST_EDITED`：重新构建 ES 文档。
- `POST_VISIBILITY_CHANGED`：如果变为公开则 upsert，如果变为非公开则删除索引。
- `POST_UNPUBLISHED`：删除索引。
- `POST_DELETED`：删除索引。

Search 消费者必须幂等，ES 文档 ID 使用 `postId`，重复 upsert 或 delete 没有副作用。

为了处理乱序、重放、retry/DLQ 回放和重建并发，Search 不直接信任事件 payload 决定最终索引内容。处理任何内容事件时，Search 都先回查 Content 当前事实源：

- 当前仍为 `PUBLISHED + PUBLIC`：构建最新文档并 upsert。
- 当前不是公开已发布，或文章不存在/已删除：删除 ES 文档。

ES 文档中保存 `source_version`。如果实现支持外部版本或脚本比较，则旧版本事件不能覆盖新版本文档；如果暂不使用 ES 外部版本，也必须通过回查当前事实源保证旧事件不会把已删除或已私有文章重新写回搜索结果。

## 5. Elasticsearch 索引设计

索引使用别名管理：

- 读取别名：`knowledge-posts-read`
- 写入别名：`knowledge-posts-write`
- 物理索引：`knowledge-posts-v1`

后续 mapping 升级时新建 `knowledge-posts-v2`，重建完成并追平增量后，原子切换读写别名。

文档 ID 为 `postId`。

主要字段：

```text
post_id             long，用于文档 ID 与稳定排序
content_type        keyword，第一阶段固定 ARTICLE
status              keyword
visibility          keyword
author_id           long
author_name         text，带 keyword 子字段
author_avatar       keyword
title               text，带 keyword 子字段
description         text
body_text           text，仅索引，不从 _source 返回
cover_object_key    keyword
tags                keyword，带 text 子字段
tags_json           flattened 或 object，用于展示
publish_time        date，统一 UTC 或 epoch millis
update_time         date，统一 UTC 或 epoch millis
like_count          long
favorite_count      long
view_count          long
comment_count       long
share_count         long
source_version      long
indexed_at          date
```

中文分词使用 IK 插件。索引侧采用较宽召回，例如 `ik_max_word`；查询侧采用较窄匹配，例如 `ik_smart`。字段类型不是 Elasticsearch 的历史 document type，而是 mapping field type。

正文 markdown 不完整返回给前端，但用于检索。索引前对 markdown 做清洗，移除明显语法噪声，并限制最大索引正文长度，例如 20k 到 50k 字符。超出部分截断，避免大文章造成索引膨胀。

`content_type` 第一阶段为 Search 索引常量 `ARTICLE`，不是当前 Content MySQL 表必须新增的业务列。

实现时需要落成具体 Elasticsearch mapping JSON：

- 可排序字段必须开启 doc values。
- `keyword` 子字段设置合理 `ignore_above`。
- `body_text` 从普通搜索响应 `_source` 中排除，避免返回体过大。
- IK 分词字段同时保留 keyword 子字段用于精确过滤或展示。

## 6. 排序与相关性

查询使用 Elasticsearch `function_score`、`rescore` 或等价查询组合。基础文本相关性来自：

- `title^5`
- `description^2`
- `body_text^1`

热度分使用计数快照：

- `favorite_count` 权重最高。
- `like_count` 次之。
- `view_count` 最低。

计数分值使用对数或平滑函数，避免一篇超高浏览文章压制所有文本相关性。

时间分使用发布时间衰减。第一阶段以 `publish_time` 为主，`update_time` 只作为同分辅助排序；编辑文章不会因为修改时间改变主要排序。

“标题命中优先”不是只依赖 `title^5` 的软 boost。第一版采用显式标题命中优先级：

1. title 命中结果排在非 title 命中结果之前。
2. 同一组内再按综合相关性、热度、发布时间排序。

实现可以使用分层查询、`rescore`，或在 script score 中生成 `title_hit` 排序字段。具体实现以能稳定满足该优先级为准。

所有权重放入配置，例如：

```text
platform.search.rank.title-boost
platform.search.rank.description-boost
platform.search.rank.body-boost
platform.search.rank.favorite-weight
platform.search.rank.like-weight
platform.search.rank.view-weight
platform.search.rank.recency-weight
```

稳定排序追加 `post_id`，保证 `search_after` cursor 可重复。

## 7. 分页设计

搜索分页使用 `search_after` cursor，不使用深分页 offset。

返回结果中包含 `nextCursor`。cursor 内部保存完整 ES sort values，而不是只保存分页位置。例如：

```text
title_hit
score
heat_score
publish_time
post_id
```

对外 cursor 使用 Base64 JSON 字符串，前端不需要理解内部结构。

如果用户刷新搜索词或过滤条件，需要从空 cursor 重新查询。cursor 只对相同 query/filter/size 有效。

cursor JSON 还需要包含：

- query/filter/size hash，防止跨查询误用。
- rankNow，请求首次查询时固定，用于时间衰减，避免翻页过程中 `_score` 因当前时间变化而漂移。
- expiresAt，限制 cursor 使用窗口。
- signature 或 HMAC，避免客户端篡改 cursor。

如果后续发现翻页仍受刷新影响明显，再引入 Elasticsearch PIT。第一版先通过固定 `rankNow`、完整 sort values 和短 cursor 有效期控制漂移。

## 8. 计数快照同步

Counter 模块不把每一次点赞、收藏、浏览直接同步到 ES。Search 使用计数快照事件更新 ES。

推荐链路：

```text
CounterAggregateConsumer 消费 counter-events
  -> Redis agg 聚合
CounterFlushScheduler flush ARTICLE:{postId}
  -> CountInt 正式计数更新完成
  -> 写入可靠 snapshot pending 队列
CounterSnapshotRelay
  -> 发布 counter-snapshot-events
SearchCounterSnapshotConsumer
  -> ES partial update like_count/favorite_count/view_count/comment_count/share_count
```

计数快照事件只携带文章 ID 和各计数字段的新值。Search 消费者使用 `postId` 幂等覆盖 ES 对应字段。

如果一篇文章已从 ES 删除，计数快照更新允许忽略，不重新创建文档。

`flushOne` 与 Kafka send 不是一个事务，不能在 flush 后 fire-and-forget 发送快照。第一版需要在 flush 成功后写入一个可靠 pending 结构，例如 Redis Stream 或 MySQL outbox，由独立 relay 确认发送。重复发送快照没有副作用，因为 Search 只做字段覆盖。

如果 snapshot relay 或 Search 消费出现长时间异常，索引重建会从 Counter 当前 CountInt 快照重新校准热度字段。

## 9. 搜索接口

第一阶段接口：

```text
GET /api/search/posts?q=&tag=&contentType=ARTICLE&cursor=&size=
```

权限：

- 公开搜索允许匿名访问。
- 如果携带合法 JWT，则补充当前用户态。
- 如果匿名访问，`likedByMe` 和 `favedByMe` 返回 `null`，与 Feed 当前语义一致；已登录用户返回 `true/false`。

`GET /api/search/posts` 必须加入 `SecurityConfig` 的 `permitAll` 有序匹配。Controller 内部使用与 Feed/Content 公开详情一致的可选 JWT 读取方式。

返回结构：

- 列表项复用 `FeedItemResponse` 的主要字段。
- 包装层额外返回 `nextCursor` 和 `hasMore`。

第一阶段不支持私有文章搜索、草稿搜索、作者后台搜索和复杂高级搜索。

## 10. 索引重建与修复

Search 必须提供索引重建能力，用于：

- Elasticsearch 索引损坏。
- mapping 升级。
- 消费者漏消费。
- 初次部署导入历史文章。

重建流程：

1. 记录 `content_outbox.id` 当前高水位。
2. 创建新物理索引，例如 `knowledge-posts-v2`。
3. 从 MySQL 分页扫描公开已发布文章。
4. 读取 OSS 正文对象并清洗正文。
5. 读取 Counter 当前计数快照。
6. 构建 ES bulk 请求写入新索引。
7. 回放高水位之后的 `content_outbox` 事件到新索引，直到追平。
8. 校验文档数量、抽样内容和删除/私有状态。
9. 原子切换 `knowledge-posts-read` 和 `knowledge-posts-write` 到新索引。

第一阶段可以先实现应用内管理服务或命令式入口，不对普通用户暴露接口。

如果使用双写新旧索引代替高水位回放，也必须保证切换前新索引已经包含扫描期间的发布、编辑、删除和可见性变更。

## 11. 失败处理

Content 事件消费失败时，Search 消费者写入自己的 retry topic。超过重试阈值后进入 search DLQ。消费者提交 offset，避免单条坏消息阻塞整个分区。

Counter 快照事件消费失败同样进入 search retry / DLQ。

topic 命名：

- `search-content-retry`
- `search-content-dlq`
- `search-counter-retry`
- `search-counter-dlq`

retry 需要保留原 Kafka key，例如 `POST:{postId}` 或 `ARTICLE:{postId}`，并记录失败原因、重试次数和首次失败时间。后续运维文档补充 DLQ 重放工具和告警指标。

消费者幂等策略：

- 内容索引 upsert：`postId` 作为 ES 文档 ID。
- 内容删除：delete missing 视为成功。
- 计数更新：覆盖字段值，不做递增。

后续由索引重建或对账修复 DLQ 中无法自动恢复的问题。

消费内容事件时读取 OSS 正文必须设置超时、最大正文长度和最大并发。OSS 正文缺失、校验失败或读取超时不能把文章索引为公开可搜索文档，应进入 retry/DLQ 并产生告警。索引重建时使用 bounded worker 和 ES bulk backpressure，避免打爆 OSS 或 Elasticsearch。

## 12. 测试策略

单元测试直接构造服务和 fake repository，不启动 Spring 容器和外部组件。

重点覆盖：

- 查询条件构造。
- 排序参数构造。
- cursor 编解码。
- ES 文档构建。
- 内容事件到 upsert/delete 的路由。
- 计数快照事件到 partial update 的路由。
- 用户态 overlay。

集成测试可以在后续加入 Elasticsearch 容器后启用，验证 mapping、IK 分词、真实查询排序和 bulk 重建。

Profile 规则沿用项目现有约束：

- ES 客户端、controller、真实 repository 使用 `@Profile("!test")` 并受 `platform.search.enabled=true` 控制，避免现有未启用 ES 的 integration profile 装配失败。
- Kafka listener 和 scheduled task 使用 `@Profile("!test & !integration")`。

后续如果将 Elasticsearch 纳入标准 integration 基础设施，再将对应集成测试放入专用 profile 或 Testcontainers 配置。

## 13. 分阶段实施

第一阶段：基础设施与可靠内容事件

1. 添加 Elasticsearch Docker 服务和 IK 插件配置。
2. 添加 Search 模块基础结构。
3. 添加具体索引 mapping 与读写别名初始化。
4. Content 模块升级 `content_outbox`，保持 `content-events` 裸 JSON 契约。
5. 添加 Search 内容事件消费者、retry/DLQ 和 OSS 正文读取保护。

第二阶段：初次索引与查询接口

1. 添加索引重建能力和高水位追赶。
2. 完成 markdown 清洗和正文截断。
3. 添加搜索查询接口，返回 Feed 风格结果。
4. 添加 search_after cursor 分页和可选 JWT 用户态 overlay。

第三阶段：计数快照与热度排序

1. Counter flush 后写入可靠 snapshot pending 队列。
2. Snapshot relay 发布 `counter-snapshot-events`。
3. Search 消费计数快照并 partial update ES。
4. 调整标题命中、热度和时间衰减权重。

第四阶段：运维与集成验证

1. 补充真实 ES 集成测试。
2. 补充 DLQ 重放工具和运维文档。
3. 同步 `backend/docs/api-draft.md` 与 `backend/docs/modules/search.md`。
