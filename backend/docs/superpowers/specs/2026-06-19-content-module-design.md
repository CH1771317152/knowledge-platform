# Content 模块设计规格

## 目标

Content 模块负责知识贴文的核心发布流程。第一版采用“分阶段、可中断、状态化、幂等”的发布设计，将文章从草稿创建到正式发布拆成多个原子操作，支持前端在任意阶段中断后恢复。

本设计同时覆盖 storage 模块在本任务中的职责：storage 只作为 OSS 交互适配层，对外提供 JWT 鉴权的 `/api/storage/presign` 接口，并为 content 提供内部对象预签名、对象元数据查询和对象读取能力。

## 已确认决策

- 正文 Markdown 存储在阿里云 OSS，不存储在 MySQL。
- MySQL 只保存贴文元数据、发布状态、正文对象指针、校验信息、文件引用和标签。
- content 模块内部实现雪花算法，只用于生成 `content_post.id`。
- `storage_file.id` 和其他模块 ID 不在本任务中调整。
- 文章生命周期状态和发布流程阶段分开管理。
- 正文 objectKey 使用 `posts/{postId}/body/v{bodyVersion}.md`，不包含 authorId。
- 普通图片/附件 objectKey 使用 `users/{userId}/files/{uuid}/{filename}` 前缀，便于 storage `/presign` 做 JWT 权限校验。
- storage 对外只提供 `/api/storage/presign`，允许前端直接申请普通文件/图片上传预签名 URL。
- 正文上传 URL 不走 storage controller，而是由 content 接口生成并内部调用 storage service。
- 正文确认阶段由服务端真实访问 OSS，校验对象存在、size、etag，并读取对象流计算 sha256。
- 第一版详情页由后端读取 OSS 正文并返回，不直接返回正文 GET 预签名 URL。
- 发布成功后的缓存刷新、Feed 索引等副作用先预留，不在第一版实现。

## 模块边界

### content

content 负责：

- 草稿创建。
- 正文上传 URL 申请。
- 正文对象确认和完整性校验。
- 元数据完善。
- 发布、取消发布、软删除。
- 公开列表、我的列表、详情查询。
- 贴文和普通文件/图片对象的引用关系。
- 标签管理。
- content 内部雪花 ID 生成。

content 不负责：

- 点赞、收藏、浏览等计数行为，这些属于 counter。
- 用户关注关系，这些属于 relation。
- OSS SDK 细节，这些属于 storage。
- 真实短信、邮件、认证逻辑，这些属于 auth。

### storage

storage 负责：

- 阿里云 OSS SDK 适配。
- 生成 PUT 预签名 URL。
- 查询对象元数据。
- 读取对象流，供 content 做 sha256 校验和正文详情返回。
- 对外 `/api/storage/presign` 的 JWT 鉴权和 objectKey 前缀校验。

storage 不负责：

- 文章发布流程。
- 正文发布状态。
- 普通文件和文章之间的语义关系。
- 完整文件生命周期表设计。

## 存储模型

### content_post

贴文元数据表，用于列表、检索、作者主页和管理后台，不存正文。

```text
id BIGINT PRIMARY KEY
author_id BIGINT NOT NULL
client_request_id VARCHAR(128)
title VARCHAR(200)
summary VARCHAR(500)
cover_object_key VARCHAR(512)
status VARCHAR(32) NOT NULL
visibility VARCHAR(32) NOT NULL
publish_stage VARCHAR(32) NOT NULL
published_at DATETIME
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

约束与索引：

```text
UNIQUE(author_id, client_request_id)
idx_content_post_author_created(author_id, created_at)
idx_content_post_status_published(status, visibility, published_at)
```

`title` 和 `summary` 在草稿创建阶段允许为空。正式发布前必须通过元数据完善接口写入合法值。

### content_post_body

正文对象指针表，不存正文文本，只保存 OSS 定位和完整性校验信息。

```text
post_id BIGINT PRIMARY KEY
body_format VARCHAR(32) NOT NULL
body_bucket VARCHAR(128)
body_object_key VARCHAR(512)
body_etag VARCHAR(255)
body_sha256 CHAR(64)
body_size_bytes BIGINT
body_version INT NOT NULL DEFAULT 1
upload_url_expires_at DATETIME
confirmed_at DATETIME
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

正文 objectKey 规则：

```text
posts/{postId}/body/v{bodyVersion}.md
```

示例：

```text
posts/766948934001352704/body/v1.md
```

### content_post_file

贴文文件引用表，用于记录正文里引用的图片、附件和封面等对象。

```text
post_id BIGINT NOT NULL
object_key VARCHAR(512) NOT NULL
usage_type VARCHAR(32) NOT NULL
content_type VARCHAR(128)
size_bytes BIGINT
sort_order INT NOT NULL DEFAULT 0
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
PRIMARY KEY(post_id, object_key, usage_type)
```

`usage_type`：

```text
COVER
INLINE_IMAGE
ATTACHMENT
```

普通图片/附件 objectKey 规则：

```text
users/{userId}/files/{uuid}/{filename}
```

content 直接记录 objectKey，不依赖 `storage_file.id`。这是因为本任务中 storage 被定位为 OSS 适配层，而不是完整文件实体模块。

### content_tag

标签表：

```text
id BIGINT PRIMARY KEY AUTO_INCREMENT
name VARCHAR(64) NOT NULL
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
UNIQUE(name)
```

### content_post_tag

贴文标签关联表：

```text
post_id BIGINT NOT NULL
tag_id BIGINT NOT NULL
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
PRIMARY KEY(post_id, tag_id)
```

第一版标签在元数据完善时整体替换，不做增量追加。

## 状态模型

### post status

文章生命周期状态：

```text
DRAFT
PUBLISHED
DELETED
```

### visibility

可见性：

```text
PUBLIC
PRIVATE
```

### publish stage

发布流程阶段：

```text
DRAFT_CREATED
BODY_URL_ISSUED
BODY_CONFIRMED
METADATA_COMPLETED
PUBLISHED
```

不保留 `BODY_UPLOADED`。前端直传 OSS 不经过服务端，服务端真正知道正文可用是在 confirm 成功之后。

## 六阶段发布流程

### 1. 创建草稿实例

```text
POST /api/posts/drafts
Authorization: Bearer <accessToken>
```

请求：

```json
{
  "clientRequestId": "optional-client-id"
}
```

行为：

- 使用 content 内部雪花算法生成 postId。
- 插入 `content_post`。
- 插入 `content_post_body` 空记录。
- 设置 `status = DRAFT`。
- 设置 `visibility = PRIVATE`。
- 设置 `publish_stage = DRAFT_CREATED`。
- 立即返回 postId 和发布状态。

幂等：

- `clientRequestId` 为空时，每次创建新草稿。
- `clientRequestId` 不为空时，同一 `author_id + client_request_id` 返回同一个 postId。

### 2. 申请正文上传 URL

```text
POST /api/posts/{postId}/body/upload-url
Authorization: Bearer <accessToken>
```

行为：

- 校验当前用户是作者。
- 校验文章不是 `DELETED`。
- 生成 objectKey：`posts/{postId}/body/v{bodyVersion}.md`。
- 内部调用 storage service 生成 PUT 预签名 URL。
- 设置或保持 `publish_stage >= BODY_URL_ISSUED`。
- 更新 `upload_url_expires_at`。

响应：

```json
{
  "postId": 766948934001352704,
  "bodyVersion": 1,
  "bucket": "knowledge-platform-dev-2026",
  "objectKey": "posts/766948934001352704/body/v1.md",
  "putUrl": "https://knowledge-platform-dev-2026.oss-cn-hangzhou.aliyuncs.com/posts/766948934001352704/body/v1.md?signature=example",
  "headers": {
    "Content-Type": "text/markdown"
  },
  "expiresAt": "2026-06-19T13:10:00"
}
```

幂等：

- 重复调用可以返回新的 `putUrl`。
- `objectKey` 和 `bodyVersion` 保持不变。
- 不创建新正文版本。

### 3. 前端直传 OSS

前端使用上一步返回的 `putUrl` 和 headers 上传 Markdown 正文。

第一版实现普通 PUT 预签名上传。分片上传、断点续传的接口和状态先记录到延期能力文档，后续由 storage 扩展。

### 4. 内容确认与校验

```text
POST /api/posts/{postId}/body/confirm
Authorization: Bearer <accessToken>
```

请求：

```json
{
  "objectKey": "posts/766948934001352704/body/v1.md",
  "etag": "xxx",
  "sizeBytes": 12345,
  "sha256": "64位sha256"
}
```

行为：

- 校验当前用户是作者。
- 校验文章不是 `DELETED`。
- 校验 objectKey 匹配当前 postId 和 bodyVersion。
- 调用 storage `statObject` 校验对象存在、size、etag。
- 调用 storage `readObject` 读取正文对象流。
- 服务端计算 sha256，与请求值比对。
- 写入 `body_object_key`、`body_etag`、`body_sha256`、`body_size_bytes`、`confirmed_at`。
- 推进 `publish_stage = BODY_CONFIRMED`。

幂等：

- 如果已确认，且 objectKey、etag、size、sha256 与记录一致，直接返回成功。
- 如果已确认但提交信息不同，返回冲突错误。

说明：

- S3/OSS 的 ETag 在分片上传场景下不一定等于 MD5。
- sha256 是业务完整性依据，ETag 作为对象存储元数据一起记录和比对。

### 5. 元数据完善

```text
PUT /api/posts/{postId}/metadata
Authorization: Bearer <accessToken>
```

请求：

```json
{
  "title": "文章标题",
  "summary": "摘要",
  "visibility": "PUBLIC",
  "coverObjectKey": "users/12/files/uuid/cover.png",
  "files": [
    {
      "objectKey": "users/12/files/uuid/a.png",
      "usageType": "INLINE_IMAGE",
      "contentType": "image/png",
      "sizeBytes": 12345,
      "sortOrder": 0
    }
  ],
  "tags": ["Java", "Spring"]
}
```

行为：

- 校验当前用户是作者。
- 校验正文已 `BODY_CONFIRMED`。
- 校验 title 非空。
- 校验 visibility 合法。
- 校验 `coverObjectKey` 和 `files[].objectKey` 都在 `users/{authorId}/` 前缀下。
- 覆盖写 `content_post` 元数据。
- 整体替换 `content_post_file`。
- 整体替换标签关联。
- 推进 `publish_stage = METADATA_COMPLETED`。

幂等：

- 同样请求重复提交，最终状态一致。
- files/tags 使用整体替换，避免重复添加。

### 6. 正式发布

```text
POST /api/posts/{postId}/publish
Authorization: Bearer <accessToken>
```

行为：

- 校验当前用户是作者。
- 校验文章不是 `DELETED`。
- 校验 `publish_stage >= METADATA_COMPLETED`。
- 设置 `status = PUBLISHED`。
- 设置 `publish_stage = PUBLISHED`。
- 首次发布时设置 `published_at`。
- 预留 `PostPublishedEvent` 或 outbox 事件点。

幂等：

- 如果已经 `PUBLISHED`，直接返回当前发布状态。
- 不重复更新时间戳。
- 不重复触发副作用。

## 恢复接口

```text
GET /api/posts/{postId}/publishing-state
Authorization: Bearer <accessToken>
```

返回当前发布流程状态，用于前端从任意阶段恢复。

响应示例：

```json
{
  "postId": 766948934001352704,
  "status": "DRAFT",
  "publishStage": "BODY_CONFIRMED",
  "bodyObjectKey": "posts/766948934001352704/body/v1.md",
  "bodyConfirmed": true,
  "metadataCompleted": false,
  "nextActions": ["UPDATE_METADATA", "PUBLISH_AFTER_METADATA"]
}
```

## 其他内容接口

公开列表：

```text
GET /api/posts
```

只返回：

```text
status = PUBLISHED
visibility = PUBLIC
```

列表只查 `content_post` 元数据，不读取 OSS 正文。

我的文章列表：

```text
GET /api/posts/me
Authorization: Bearer <accessToken>
```

返回当前用户自己的草稿和已发布文章，默认不返回 `DELETED`。

文章详情：

```text
GET /api/posts/{postId}
```

权限规则：

- `PUBLISHED + PUBLIC`：任何人可看。
- `DRAFT`：只有作者可看。
- `PUBLISHED + PRIVATE`：只有作者可看。
- `DELETED`：默认不可看。

第一版详情页由后端读取 OSS Markdown 正文，并随元数据一起返回。

取消发布：

```text
POST /api/posts/{postId}/unpublish
Authorization: Bearer <accessToken>
```

行为：

- 只有作者可操作。
- `PUBLISHED -> DRAFT`。
- `publish_stage` 保持 `METADATA_COMPLETED`。
- `published_at` 第一版保留，不清空。

软删除：

```text
DELETE /api/posts/{postId}
Authorization: Bearer <accessToken>
```

行为：

- 只有作者可操作。
- 设置 `status = DELETED`。
- 不删除 OSS 正文对象。
- 不删除图片/附件对象。
- 公开列表不再展示。
- 详情默认不可访问。

## Storage 设计

### 配置

```yaml
platform:
  storage:
    provider: aliyun-oss
    endpoint: ${OSS_ENDPOINT:https://oss-cn-hangzhou.aliyuncs.com}
    region: ${OSS_REGION:cn-hangzhou}
    bucket: ${OSS_BUCKET:knowledge-platform-dev-2026}
    access-key-id: ${OSS_ACCESS_KEY_ID:}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET:}
    presign-expire-minutes: ${OSS_PRESIGN_EXPIRE_MINUTES:10}
```

AccessKey 只从环境变量读取，不写入代码和文档明文。

### 对外接口

```text
POST /api/storage/presign
Authorization: Bearer <accessToken>
```

请求：

```json
{
  "objectKey": "users/12/files/550e8400-e29b-41d4-a716-446655440000/a.png",
  "contentType": "image/png",
  "expiresMinutes": 10
}
```

响应：

```json
{
  "bucket": "knowledge-platform-dev-2026",
  "objectKey": "users/12/files/550e8400-e29b-41d4-a716-446655440000/a.png",
  "putUrl": "https://knowledge-platform-dev-2026.oss-cn-hangzhou.aliyuncs.com/users/12/files/550e8400-e29b-41d4-a716-446655440000/a.png?signature=example",
  "headers": {
    "Content-Type": "image/png"
  },
  "expiresAt": "2026-06-19T13:10:00"
}
```

鉴权和校验：

- 必须登录。
- 从 JWT `AuthenticatedPrincipal` 获取 userId。
- objectKey 必须以 `users/{currentUserId}/` 开头。
- objectKey 不能以 `/` 开头。
- objectKey 不能包含 `../`。
- objectKey 长度不能超过 512。
- `expiresMinutes` 最大 10。
- contentType 必须在白名单中。

第一版 contentType 白名单：

```text
image/png
image/jpeg
image/webp
image/gif
application/pdf
text/plain
application/zip
```

### 内部服务接口

storage application service 提供：

```java
PresignedUpload presignPut(String objectKey, String contentType, Duration expires);
ObjectMetadata statObject(String objectKey);
InputStream readObject(String objectKey);
```

content 使用这些内部能力生成正文上传 URL、确认正文对象、读取详情正文。

## 阿里云 OSS 信息

开发环境已确认：

```text
region: cn-hangzhou
bucket: knowledge-platform-dev-2026
endpoint: https://oss-cn-hangzhou.aliyuncs.com
CORS Origin: http://127.0.0.1:5173
```

前端开发时需要从 `http://127.0.0.1:5173` 打开页面。如果使用 `http://localhost:5173`，浏览器会认为这是不同 Origin，OSS CORS 可能不通过。

## content 内部雪花 ID

雪花 ID 生成器只放在 content 模块内部：

```text
content.infrastructure.id
- ContentIdGenerator
- SnowflakeContentIdGenerator
- ContentSnowflakeProperties
```

配置：

```yaml
platform:
  content:
    id:
      worker-id: ${CONTENT_ID_WORKER_ID:1}
      datacenter-id: ${CONTENT_ID_DATACENTER_ID:1}
```

使用范围：

- `content_post.id`

不影响其他模块 ID 策略。

## 错误码

建议新增：

```text
CONTENT_POST_NOT_FOUND
CONTENT_FORBIDDEN
CONTENT_INVALID_STAGE
CONTENT_BODY_NOT_CONFIRMED
CONTENT_METADATA_INCOMPLETE
CONTENT_OBJECT_KEY_INVALID
CONTENT_OBJECT_CONFIRM_FAILED
CONTENT_ALREADY_DELETED
STORAGE_PRESIGN_FORBIDDEN
STORAGE_OBJECT_NOT_FOUND
STORAGE_OBJECT_CHECK_FAILED
```

## 测试策略

### content 单元测试

覆盖：

- 创建草稿返回雪花 ID。
- `clientRequestId` 幂等。
- 重复申请正文 URL 时 objectKey 不变。
- 正文确认成功推进阶段。
- 已确认正文重复确认幂等。
- 已确认正文重复确认但信息不同返回冲突。
- 元数据整体替换 files/tags。
- 发布幂等。
- 删除后禁止发布。
- 公开列表不返回草稿、私密和删除文章。
- 详情权限符合 status/visibility 规则。

### storage 单元测试

覆盖：

- 拒绝未登录请求。
- 拒绝非当前用户前缀。
- 拒绝 `../` objectKey。
- 拒绝以 `/` 开头的 objectKey。
- 拒绝超长过期时间。
- 拒绝不在白名单的 contentType。
- 接受合法 `users/{userId}/files/{uuid}/{filename}` objectKey。

### 集成测试

默认集成测试仍以 MySQL、Redis 和 fake object storage 为主，不强依赖阿里云 OSS。

原因：

- 真实 OSS 依赖外部网络。
- 真实 OSS 可能产生费用。
- 团队本地环境不一定都有相同云资源。

建议增加一个默认关闭的 OSS smoke test：

```text
AliyunOssObjectStorageSmokeTest
```

仅在 `OSS_ENDPOINT`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 都存在时运行，用于手动验证真实阿里云 OSS presign、PUT、stat、read 链路。

## 延期能力记录

本设计中明确暂不实现但后续需要跟进的能力，统一记录在：

```text
backend/docs/deferred-features.md
```
