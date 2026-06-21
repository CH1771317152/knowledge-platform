# 模块文档索引

这里用于沉淀各业务模块的实现说明。每个模块独立维护一个文档，重点说明模块职责、已实现能力、核心流程、数据模型、对外接口、测试覆盖和后续边界。

## 当前模块

| 模块 | 文档 | 说明 |
| --- | --- | --- |
| user | [user.md](user.md) | 用户账号、用户资料、当前用户资料查询与更新 |
| auth | [auth.md](auth.md) | 身份认证、登录态管理、双 Token、验证码 |
| content | [content.md](content.md) | 贴文六阶段发布、正文 OSS 存储、Storage presign |
| relation | [relation.md](relation.md) | 关注/取关、following/followers 列表、关系事件 outbox + Canal + Kafka + 粉丝投影 |
| counter | [counter.md](counter.md) | 文章点赞/收藏/浏览计数、用户关注/粉丝/作品计数、位图事实层 + Redis 聚合 + 跨模块事件消费 |
| cache | [cache.md](cache.md) | Feed 三级缓存（L2 Caffeine / L1 Redis 骨架 / L0 Redis 片段）、keyset cursor 分页、个性化 overlay、统一 content 事件失效 + 对账调度 |

## 模块文档列表

- [用户模块](user.md)
- [Auth 模块](auth.md)
- [Content 模块](content.md)
- [Relation 模块](relation.md)
- [Counter 模块](counter.md)
- [Cache / Feed 模块](cache.md)

## 文档约定

- 一个模块一个文档，文件名与 Java 包名保持一致。
- 文档只描述当前已经实现或明确规划的模块边界，不混入其他模块的内部实现。
- 涉及跨模块协作时，只说明调用关系和依赖方向。
- 模块能力发生变化时，同步更新对应文档和本索引。
