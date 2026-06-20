# Content 模块

## 模块职责

`content` 模块负责平台贴文（post）的完整生命周期管理，是知识内容发布、检索、详情展示的基础业务模块。

`content` 拥有的能力：

- 六阶段可恢复的发布流程（草稿创建 → 正文上传 URL → 正文确认与完整性校验 → 元数据 → 发布）。
- 草稿、发布、下线、删除等贴文状态流转。
- 正文（Markdown）上传 URL 签发、正文对象确认与大小 / etag / SHA-256 完整性校验。
- 贴文元数据（标题、摘要、可见性、封面）、标签、文件引用（封面 / 正文内联图片 / 附件）。
- 公开列表、我的列表、详情查询（含公开 / 私有可见性鉴权）。
- `content` 内部的雪花 ID 生成（`content_post.id` 由模块自有的 `SnowflakeContentIdGenerator` 签发，不依赖数据库自增）。

`content` 不拥有的能力（属于其他模块）：

- 点赞、收藏、浏览计数归 `counter` 模块。
- 用户关注关系归 `relation` 模块。
- OSS SDK 的具体细节（凭证、客户端、SDK 调用）归 `storage` 模块。

`content` 通过 `storage` 模块的内部服务 `ObjectStorageService` 完成三件事：生成正文上传 URL、确认对象（`statObject` 校验 size/etag）、读取正文流（`readObject` 计算 SHA-256）。这些内部调用不走 HTTP `/api/storage/presign`，而是直接使用应用层注入的 `ObjectStorageService` Bean。

## 六阶段发布流程

贴文发布是六阶段、可恢复、幂等的工作流，由 `ContentCommandService` 编排。`publish_stage`（五阶段状态机）与 `status`、`visibility` 三者相互独立。

```text
DRAFT_CREATED → (requestBodyUploadUrl) → BODY_URL_ISSUED
             → (confirmBody)            → BODY_CONFIRMED
             → (updateMetadata)         → METADATA_COMPLETED
             → (publish)                → PUBLISHED
```

| 阶段 | 进入接口 | 行为 |
| --- | --- | --- |
| `DRAFT_CREATED` | `POST /api/posts/drafts` | 创建草稿。`status=DRAFT`、`visibility=PRIVATE`、`publish_stage=DRAFT_CREATED`。正文对象指针行同时建立（`body_version=1`，尚未确认）。 |
| `BODY_URL_ISSUED` | `POST /api/posts/{id}/body/upload-url` | 生成正文 PUT 上传 URL。**正文由前端直传 OSS**，服务端此时还不知道正文是否真的可用。objectKey = `posts/{postId}/body/v{bodyVersion}.md`，TTL 固定 10 分钟。 |
| `BODY_CONFIRMED` | `POST /api/posts/{id}/body/confirm` | 服务端在确认阶段**真实访问 OSS**：`statObject` 校验 size 与 etag，再 `readObject` 读流计算 SHA-256，三者和客户端上报值一致才记录确认。body 大小上限 **2 MiB**（`MAX_BODY_BYTES`）在 confirm 阶段强制。 |
| `METADATA_COMPLETED` | `PUT /api/posts/{id}/metadata` | 写入标题、摘要、可见性、封面、文件引用与标签，整体替换（非追加）。要求正文已确认。 |
| `PUBLISHED` | `POST /api/posts/{id}/publish` | 要求到达 `METADATA_COMPLETED`。`status=PUBLISHED`、`publish_stage=PUBLISHED`，首次发布写入 `published_at`，后续重发保留。 |

正文由前端直传 OSS 的设计点：服务端在 `BODY_URL_ISSUED` 阶段无法知道对象是否已上传，必须等到 `confirmBody` 调用时 `statObject` 才能确认对象真实存在，因此状态机里**没有 `BODY_UPLOADED` 阶段**。

恢复接口：`GET /api/posts/{postId}/publishing-state`（需认证，仅作者）返回当前 `status` / `publish_stage` / `bodyConfirmed` / `metadataCompleted` 以及 `nextActions`。`nextActions` 语义：

| 当前状态 | nextActions |
| --- | --- |
| 已删除（`DELETED`） | `[]`（空） |
| `PUBLISHED` | `["UNPUBLISH"]` |
| `DRAFT_CREATED` | `["REQUEST_BODY_UPLOAD_URL"]` |
| `BODY_URL_ISSUED` | `["CONFIRM_BODY"]` |
| `BODY_CONFIRMED` | `["UPDATE_METADATA"]` |
| `METADATA_COMPLETED` | `["PUBLISH"]` |

`PublishingStateBuilder` 统一构建发布状态响应，被命令服务（每个命令都返回结果状态）与查询服务（恢复接口）共用，保证两条路径不漂移。

## 状态模型

`content` 的状态由三个相互独立的字段描述：

- `status`（`PostStatus`）：`DRAFT` / `PUBLISHED` / `DELETED`。
- `visibility`（`PostVisibility`）：`PUBLIC` / `PRIVATE`。草稿默认 `PRIVATE`。
- `publish_stage`（`PublishStage`）：上述五阶段。

设计上**不保留 `BODY_UPLOADED` 阶段**：正文是前端直传 OSS 的，服务端在 `confirmBody` 之前无法确认正文已上传，因此只有 `BODY_URL_ISSUED` 与 `BODY_CONFIRMED` 两个与正文相关的阶段。

公开可见性判定在查询服务层执行：只有 `status=PUBLISHED` 且 `visibility=PUBLIC` 的贴文对匿名访客可见；作者本人可看到自己任意状态 / 可见性的贴文（`/api/posts/me` 与 `/api/posts/{id}/publishing-state`）。

## 数据库表

定义在 Flyway 基线迁移 `src/main/resources/db/migration/V1__init.sql`，五张表：

### content_post

贴文元数据主表。`id` 为雪花 ID（由应用层签发，非自增）。

- `id BIGINT PRIMARY KEY`：雪花 ID。
- `author_id BIGINT NOT NULL`：作者，外键 `fk_content_post_author` 指向 `user_account(id)`。
- `client_request_id VARCHAR(128)`：客户端幂等键。
- `title VARCHAR(200)`、`summary VARCHAR(500)`、`cover_object_key VARCHAR(512)`：元数据字段。
- `status` 默认 `DRAFT`、`visibility` 默认 `PRIVATE`、`publish_stage` 默认 `DRAFT_CREATED`。
- `published_at DATETIME`：首次发布时间，重发保留。
- `UNIQUE KEY uk_content_post_author_request (author_id, client_request_id)`：幂等创建草稿的唯一键。
- `idx_content_post_status_published (status, visibility, published_at)`：支撑公开 Feed 查询。

### content_post_body

正文对象指针（**不存正文文本**）。正文 Markdown 存在 OSS，库里只存定位 + 完整性指纹。

- `post_id BIGINT PRIMARY KEY`：主键即 `content_post.id`，外键 `fk_content_post_body_post`。
- `body_format VARCHAR(32)` 默认 `MARKDOWN`。
- `body_bucket`、`body_object_key`、`body_etag`、`body_sha256 CHAR(64)`、`body_size_bytes`、`body_version INT`：对象定位与完整性指纹。
- `upload_url_expires_at`、`confirmed_at`：上传 URL 过期时间与正文确认时间。

### content_post_file

文件引用，**复合主键** `(post_id, object_key, usage_type)`。

- `usage_type`：`COVER` / `INLINE_IMAGE` / `ATTACHMENT`。
- `content_type`、`size_bytes`、`sort_order`：可选的文件描述。
- 外键 `fk_content_post_file_post` 指向 `content_post(id)`。

### content_tag

标签字典，`name` 唯一（`uk_content_tag_name`），`id` 自增。

### content_post_tag

贴文与标签的关联，主键 `(post_id, tag_id)`，双外键指向 `content_post` 与 `content_tag`。

> 正文 Markdown 不进 MySQL，全部存储在 OSS（详见下节）。MySQL 只存对象指针与完整性指纹。

## OSS 对象规则

存储桶 / Region / Endpoint（来自 `application.yml` 的 `platform.storage`，可通过环境变量覆盖）：

- bucket：`knowledge-platform-dev-2026`
- region：`cn-hangzhou`
- endpoint：`https://oss-cn-hangzhou.aliyuncs.com`
- CORS：前端需从 `http://127.0.0.1:5173` 访问 OSS（CORS Origin）。

对象 key 规则：

| 用途 | objectKey 模板 | 由谁生成 |
| --- | --- | --- |
| 正文 | `posts/{postId}/body/v{bodyVersion}.md` | 服务端（`ContentCommandService`），不走 `/presign` 校验 |
| 普通图片 / 附件 | `users/{userId}/files/{uuid}/{filename}` | 客户端构造，经 `/api/storage/presign` 或元数据引用时校验 |

`ObjectKeyPolicy.isOwnedObjectKey` 统一校验用户拥有的对象 key：

- 必须以 `users/{ownerId}/` 前缀开头（末尾 `/` 防止 `ownerId=1` 匹配到 `users/12/`）。
- 禁止前导 `/`（绝对 key）。
- 禁止 `../` 路径穿越。
- 长度 ≤ 512。

正文 key 由服务端按版本规则生成，因此本身不受 `/presign` 的 owner 前缀校验；但元数据更新里的封面与文件引用走**同一** `ObjectKeyPolicy`，保证两条路径的归属规则不漂移。

## Storage /presign

`POST /api/storage/presign`（`StorageController` + `StoragePresignService`）用于普通文件 / 图片直传 OSS，**需要 JWT 鉴权**。

- `objectKey` 必须以 `users/{当前 userId}/` 开头，否则 `STORAGE_PRESIGN_FORBIDDEN`。
- `contentType` 白名单：`image/png`、`image/jpeg`、`image/webp`、`image/gif`、`application/pdf`、`text/plain`、`application/zip`。
- `expiresMinutes` 上限为 `platform.storage.presign-expire-minutes`（默认 `10`，`StorageProperties` 限定 1~10），请求值超过上限会被截断到上限。

正文上传 URL **不走** `/api/storage/presign`，而是 `content` 内部直接调用 `ObjectStorageService.presignPut` 生成（固定 TTL 10 分钟、`text/markdown`、版本化 objectKey）。

## 接口列表

所有 `content` 接口挂在 `/api/posts`，`storage` presign 挂在 `/api/storage`。鉴权由 `SecurityConfig` 的**有序**请求匹配器决定（顺序很关键）：

| 方法 | 路径 | 鉴权 |
| --- | --- | --- |
| `POST` | `/api/posts/drafts` | 认证 |
| `POST` | `/api/posts/{postId}/body/upload-url` | 认证 |
| `POST` | `/api/posts/{postId}/body/confirm` | 认证 |
| `PUT` | `/api/posts/{postId}/metadata` | 认证 |
| `POST` | `/api/posts/{postId}/publish` | 认证 |
| `POST` | `/api/posts/{postId}/unpublish` | 认证 |
| `DELETE` | `/api/posts/{postId}` | 认证 |
| `GET` | `/api/posts/{postId}/publishing-state` | 认证（作者） |
| `GET` | `/api/posts/me` | 认证（作者自己的全部贴文） |
| `GET` | `/api/posts` | **公开**（permitAll，仅 published+public） |
| `GET` | `/api/posts/{postId}` | **公开**（permitAll，详情按 status/visibility 在服务层鉴权） |
| `POST` | `/api/storage/presign` | 认证 |

`SecurityConfig` 中的有序匹配（顺序不可调换）：

1. `GET /api/posts/me`、`GET /api/posts/{postId}/publishing-state` → `authenticated()`。**匹配器在前**，避免被后面单段通配的 permitAll 误放行匿名流量。
2. `GET /api/posts`、`GET /api/posts/{postId}` → `permitAll()`。详情接口在过滤器层公开，控制器里读取认证主体是**可选**的（匿名访客得到 null requesterId），由查询服务层按 `status`/`visibility` 执行逐贴读权限：只有 `PUBLISHED` + `PUBLIC` 对世界可读。
3. 其余写接口落入 `anyRequest().authenticated()`。

> `POST /api/auth/me` 与 `POST /api/auth/logout` 不在 permitAll 列表，需要有效 access token（与 `auth` 模块设计一致）。

## 幂等规则

- `createDraft`：以 `(authorId, clientRequestId)` 为幂等键。重复提交返回同一贴文（不论当前是 `DRAFT` / `PUBLISHED` 还是已 `DELETED`），唯一约束 `uk_content_post_author_request` 兜底。
- `requestBodyUploadUrl`：可重复申请。同一 `bodyVersion` 下 objectKey 不变、版本号不变，仅返回新的 putUrl。
- `confirmBody`：对同一对象（objectKey/etag/sha256/size 全等）重确认幂等，直接返回已记录状态；若已确认但对象不同，抛 `CONTENT_OBJECT_CONFIRM_FAILED`。
- `publish` / `unpublish` / `delete`：幂等。重复发布不更新 `publishedAt`、无副作用；对已删除贴文 `unpublish` 抛 `CONTENT_ALREADY_DELETED`。
- `updateMetadata`：`files` 与 `tags` 是**整体替换**（replace），不是追加。

## 测试方式

### 单元测试

```bash
./mvnw.cmd test '-Dspring.profiles.active=test'
```

- `test` profile 排除 `DataSourceAutoConfiguration`、`RedisAutoConfiguration`、`KafkaAutoConfiguration`，不依赖外部服务。
- 服务层测试（`ContentCommandServiceTest`、`ContentQueryServiceTest`、`SnowflakeContentIdGeneratorTest`、`StoragePresignServiceTest`）使用 Fake Repository / Fake `ObjectStorageService` 验证业务规则。

### 集成测试

```bash
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- 依赖本地 MySQL（`localhost:3307`）+ Redis（`localhost:6379`）。
- `ContentControllerIntegrationTest` 通过真实 HTTP（MockMvc）覆盖完整发布流程：注册 → 草稿 → upload-url → confirm → metadata → publish → 匿名读 → unpublish → 错误分支。
- 集成测试使用 `FakeObjectStorageService`（非真实 OSS），不产生 OSS 网络访问与费用。

### OSS smoke test

```bash
./mvnw.cmd '-Dtest=AliyunOssObjectStorageSmokeTest' test '-Dspring.profiles.active=integration'
```

仅当以下条件**同时满足**时才会真正运行（否则被 JUnit 条件注解跳过）：

- 环境变量 `RUN_ALIYUN_OSS_SMOKE_TEST=true`。
- 环境变量 `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 已设置。
- 额外激活 `aliyun-oss-smoke` profile（与 `integration` 一起），该 profile 会把 `AliyunOssObjectStorageService`（`@Profile("!test & (!integration | aliyun-oss-smoke)")`）切回真实阿里云 OSS 适配器。

该 smoke test 验证真实 OSS 的 presign PUT → `statObject` → `readObject` 闭环。默认集成测试不运行它，无需 OSS 网络与凭证。

### Schema 来源

`content` 五张表由 Flyway 基线迁移 `V1__init.sql` 创建。集成测试 context 启动时 Flyway 自动应用迁移，**不再需要手动建表或 drop + recreate 数据卷**。
