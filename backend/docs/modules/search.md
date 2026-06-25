# Search 模块

## 模块职责

Search 模块负责公开文章搜索，使用 Elasticsearch 解决文章规模增长后的全文检索性能问题。第一版只搜索 `PUBLISHED + PUBLIC + ARTICLE` 内容。

`search` 拥有的能力：

- **全文检索**：按标题、摘要、正文检索文章。
- **排序策略**：标题命中优先，其次按热度（点赞 / 收藏 / 浏览加权）和发布时间排序。
- **Feed 风格结果**：返回与 Feed 一致的列表项结构。
- **匿名 / 登录 overlay**：匿名访问时 `likedByMe` / `favedByMe` 为 `null`，登录访问时实时 overlay。
- **可靠索引维护**：通过 `content_outbox` 可靠维护 ES 文档。
- **热度快照同步**：通过 Counter snapshot 更新 ES 热度快照。
- **索引重建**：支持索引重建和高水位（high watermark）追赶。

`search` 不拥有的能力（属于其他模块）：

- 贴文事实、正文存储、发布六阶段归 `content` 模块（`search` 只消费 `content-events`，并回查当前事实源）。
- 互动计数事实归 `counter` 模块（`search` 只消费 `counter-snapshot-events`，对 ES 文档做局部更新）。
- 用户展示名 / 头像归 `user` 模块（`search` 在文档构建时回查）。
- 正文 OSS 读取归 `storage` 模块。

## 核心能力

- 按标题、摘要、正文检索文章。
- 标题命中优先，其次按热度和发布时间排序。
- 返回 Feed 风格列表项。
- 匿名访问时 `likedByMe/favedByMe` 为 `null`，登录访问时实时 overlay。
- 通过 `content_outbox` 可靠维护 ES 文档。
- 通过 Counter snapshot 更新 ES 热度快照。
- 支持索引重建和高水位追赶。

## 事件链路

Content 写事务写入 `content_outbox`，relay 将裸 JSON 投递到 `content-events`。Search 消费后回查 Content 当前事实源，公开已发布则 upsert，非公开或删除则 delete。

```text
content 写事务
   └─ ContentOutboxAppender ──▶ content_outbox（同事务）
                                  │
                                  ▼
                          ContentOutboxRelay（@Scheduled）
                                  │ content-events（裸 JSON，与 Cache/Counter 共享）
                                  ▼
                          SearchContentEventConsumer
                                  ├─ 回查 Content / User / Storage / Counter 当前事实
                                  ├─ PUBLISHED + PUBLIC  → upsert ES（write 别名）
                                  └─ 非公开 / 删除       → delete ES（缺失视为成功）
```

Counter flush 后写入 `counter_snapshot_outbox`，relay 投递 `counter-snapshot-events`。Search 使用该事件覆盖 ES 中的计数字段。

```text
CounterFlushScheduler
   └─ flushOne(ARTICLE, eid) 成功后 ──▶ counter_snapshot_outbox
                                          │
                                          ▼
                                  CounterSnapshotRelay（@Scheduled）
                                          │ counter-snapshot-events
                                          ▼
                                  SearchCounterSnapshotConsumer
                                          └─ updateCounters ES 局部更新（不创建缺失文档）
```

## 接口

`GET /api/search/posts?q=&tag=&contentType=ARTICLE&cursor=&size=`

详见 [api-draft.md](../api-draft.md) 的 Search 章节。

## 运维

搜索功能默认由 `platform.search.enabled=false` 关闭。开启前需要：

- Elasticsearch 服务可用（Docker :9200）。
- 安装 IK 中文分词插件。
- 索引 mapping（`elasticsearch/post-index-mapping.json`）与读写别名（`knowledge-posts-read` / `knowledge-posts-write`）初始化完成。
