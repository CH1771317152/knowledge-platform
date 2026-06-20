# 测试说明

项目后端测试分为两条线：单元测试和集成测试。

## 单元测试

单元测试用于快速验证应用层业务逻辑，不依赖 MySQL、Redis、Kafka 等外部服务。

运行命令：

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

`test` profile 会读取 `src/test/resources/application-test.yml`，其中排除了：

- `DataSourceAutoConfiguration`
- `RedisAutoConfiguration`
- `KafkaAutoConfiguration`

因此当前用户模块的 `UserCommandServiceTest`、`UserQueryServiceTest` 使用 Fake Repository 验证服务层规则，不连接真实数据库。

## 集成测试

集成测试用于验证真实基础设施链路，包括 MySQL、Redis 和本地 Caffeine 缓存配置。

先启动项目依赖：

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis
```

当前 Docker Compose 默认端口：

| 服务 | 本机端口 | 容器端口 |
| --- | --- | --- |
| MySQL | `3307` | `3306` |
| Redis | `6379` | `6379` |
| Redis Insight | `5540` | `5540` |

MySQL 默认连接信息：

- database: `knowledge_platform`
- username: `root`
- password: `root`

运行集成测试：

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

`integration` profile 会读取 `src/test/resources/application-integration.yml`，默认连接：

- MySQL: `jdbc:mysql://localhost:3307/knowledge_platform`
- Redis: `localhost:6379`

如果你要连接自己的 MySQL 或 Redis，可以通过环境变量覆盖：

```powershell
$env:MYSQL_URL='jdbc:mysql://localhost:3306/knowledge_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='your-password'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

## 当前集成测试覆盖

`MysqlUserRepositoryIntegrationTest` 验证：

- 通过真实 MyBatis Mapper 插入 `user_account` 和 `user_profile`。
- 读取数据库生成的 `id`、`created_at`、`updated_at`。
- 通过用户名、邮箱、用户 id 查询账号。
- 更新用户公开资料并重新查询验证。
- 验证 `birthday` 等新增字段可以真实落库。

`RedisAndCacheIntegrationTest` 验证：

- `StringRedisTemplate` 可以连接 Redis。
- Redis 字符串写入、读取、删除可用。
- Spring `CacheManager` 可以拿到 `userProfileDetail` 本地 Caffeine 缓存。

## 数据库迁移（Flyway）

数据库 schema 现在由 [Flyway](https://flywaydb.org/) 版本化迁移脚本管理，位于 `src/main/resources/db/migration/`。基线迁移是 `V1__init.sql`，包含 user / content / auth 三个模块的 9 张表。

Flyway 在以下时机自动执行迁移，**因此不再需要任何手动建表**：

- 应用启动时（`application.yml` 中 `spring.flyway.enabled=true`，且 `baseline-on-migrate=true` + `baseline-version=0`，对空库会从 V1 开始全部应用）。
- 集成测试 context 启动时（`integration` profile 同样启用 Flyway，集成测试会自建 schema）。
- 单元测试 profile（`test`）显式设置 `spring.flyway.enabled=false`，不触发迁移、也不需要数据源。

新增表或修改列时，创建一个新的迁移脚本，例如 `V2__add_counter_tables.sql`，Flyway 下次启动时会自动按版本号顺序应用。`flyway_schema_history` 表由 Flyway 自身维护，记录已应用的版本。

配置里设置了 `clean-disabled: true`，这是生产安全护栏——`flyway:clean`（会删除全部表）被禁用，避免误操作。如需重置开发库，手动执行 `DROP DATABASE` / `CREATE DATABASE`（开发库 `knowledge_platform` 已通过这种方式重建为 Flyway 托管的干净状态），重启后 Flyway 会重新应用全部迁移。

## Redis 可视化调试

项目的 Docker Compose 已经包含 Redis Insight，用于在网页端查看 Redis 中的 key、value、TTL 和数据类型。

启动 Redis 和 Redis Insight：

```powershell
docker compose -f deploy/docker-compose.yml up -d redis redisinsight
```

浏览器访问：

```text
http://localhost:5540
```

在 Redis Insight 中新增 Redis 连接时，如果 Redis Insight 也是通过本项目 Docker Compose 启动的，连接信息填写：

| 配置 | 值 |
| --- | --- |
| Host | `redis` |
| Port | `6379` |
| Username | 空 |
| Password | 空 |
| Database | `0` |

如果使用的是 Redis Insight 桌面版，连接的是宿主机映射端口，则填写：

| 配置 | 值 |
| --- | --- |
| Host | `localhost` |
| Port | `6379` |
| Username | 空 |
| Password | 空 |
| Database | `0` |

Auth 模块调试时可以按前缀筛选：

```text
auth:*
```

常见 key：

```text
auth:verification:code:*
auth:verification:send-rate:*
auth:verification:verify-rate:*
auth:jwt:blacklist:*
```

验证码 key 有 TTL，并且验证成功后会被消费删除。因此如果要观察验证码相关数据，需要在发送验证码之后、校验验证码之前查看。

## Auth 模块集成测试

Auth 模块新增了三类集成测试，覆盖验证码、刷新令牌和完整 HTTP 认证流程。

### 验证码 Redis 集成测试

验证 Redis 中验证码相关 Lua 脚本（`auth-verification-send.lua`、`auth-verification-verify.lua`）的真实行为：

- 重发间隔限制（60 秒内不可重发）。
- 每小时发送上限（默认 5 次）。
- 暴力校验锁定（默认最多 5 次失败）。
- 验证码校验成功后被消费、不可重放。

### 刷新令牌 MySQL 集成测试

验证 `auth_refresh_token` 的轮换与撤销链路：

- 正常刷新：旧 token 撤销、新 token 签发、`replaced_by_token_id` 串联。
- 撤销后旧 token 不可再次刷新。
- 重放检测：已撤销 token 被再次提交时，级联撤销该用户全部 refresh token。

### AuthController 集成测试

验证完整 HTTP 认证流程：

- 注册 → `/me` → 刷新 → 旧 token 重放被拒 → 登出 → 黑名单生效的端到端链路。
- 缺失 token、已黑名单 token 访问受保护资源时返回 401。

### Auth 表的 Schema 来源

Auth 模块新增的两张表（`auth_refresh_token`、`auth_verification_audit`）由 Flyway 基线迁移 `V1__init.sql` 创建。集成测试 context 启动时 Flyway 自动应用迁移，因此**不再需要手动建表或重建数据卷**。

## Content / Storage 模块集成测试

Content 模块新增了端到端 HTTP 集成测试与 OSS smoke test。

### Content 发布流程集成测试

`ContentControllerIntegrationTest` 验证完整六阶段发布流程（对真实 MySQL + `FakeObjectStorageService`，不经真实 OSS）：

- 注册真实用户拿到 access token。
- 草稿 → upload-url → confirm → metadata → publish 的完整链路。
- 匿名访客可读 published+public 详情、不可读未发布 / 私有贴文。
- unpublish、删除以及关键错误分支（越权、未确认正文、对象不匹配等）。

集成测试默认使用 `FakeObjectStorageService`：不需要 OSS 网络、不需要 OSS 凭证、不产生费用。`MysqlContentPostRepositoryIntegrationTest` 覆盖持久化层的真实 MyBatis 读写。

### OSS smoke test（可选，默认跳过）

`AliyunOssObjectStorageSmokeTest` 是可选的真实阿里云 OSS 闭环测试（presign PUT → `statObject` → `readObject`）。它**只有同时满足以下条件才会真正运行**，否则被 JUnit 条件注解跳过：

- 环境变量 `RUN_ALIYUN_OSS_SMOKE_TEST=true`。
- 环境变量 `OSS_ENDPOINT`、`OSS_REGION`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 已设置。
- 通过 `@ActiveProfiles({"integration", "aliyun-oss-smoke"})` 激活 `aliyun-oss-smoke` profile，该 profile 会把对象存储适配器从 Fake 切换为真实的 `AliyunOssObjectStorageService`（`@Profile("!test & (!integration | aliyun-oss-smoke)")`）。

因此默认集成测试运行无需任何 OSS 网络与凭证，OSS smoke test 只在显式提供凭证并开启开关时运行。

运行 smoke test：

```powershell
$env:RUN_ALIYUN_OSS_SMOKE_TEST='true'
$env:OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
$env:OSS_REGION='cn-hangzhou'
$env:OSS_BUCKET='knowledge-platform-dev-2026'
$env:OSS_ACCESS_KEY_ID='...'
$env:OSS_ACCESS_KEY_SECRET='...'
.\mvnw.cmd '-Dtest=AliyunOssObjectStorageSmokeTest' test '-Dspring.profiles.active=integration,aliyun-oss-smoke'
```

### Content 五张表的 Schema 来源

Content 模块新增的五张表（`content_post`、`content_post_body`、`content_post_file`、`content_tag`、`content_post_tag`）由 Flyway 基线迁移 `V1__init.sql` 创建。集成测试 context 启动时 Flyway 自动应用迁移，因此**不再需要手动建表或 drop + recreate 数据卷**。

