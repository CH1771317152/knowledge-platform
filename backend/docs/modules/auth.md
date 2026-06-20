# Auth 模块

## 模块职责

`auth` 模块负责平台的身份认证与登录态管理，是其他业务模块识别"当前请求属于哪个用户、是否已登录"的基础模块。

`auth` 拥有的能力：

- 身份认证（密码登录、验证码登录）。
- 登录态管理（双 Token 签发、刷新、撤销、登出）。
- 验证码发送与校验。
- 访问令牌（JWT）的签名、解析与吊销校验。

`auth` 不拥有的能力（属于其他模块）：

- 用户账号主数据与公开资料（用户名、邮箱、头像、简介等）由 `user` 模块管理。`auth` 通过 `user` 模块的应用服务 `UserQueryService`、`UserCommandService` 完成账号查询、创建账号、修改密码、标记邮箱/手机已验证、更新最近登录时间等操作，不直接读写 `user_account`、`user_profile`。
- 点赞、收藏、关注、内容、文件等业务能力分别归 `counter`、`relation`、`content`、`storage` 模块。

## 核心能力

当前已实现的能力：

- 发送验证码（注册 / 登录 / 重置密码场景）。
- 注册：校验验证码后调用 `user` 模块创建账号。
- 密码登录：用户名或邮箱 + 密码。
- 验证码登录：邮箱 + 验证码。
- 刷新令牌：用 refresh token 换取新的 access + refresh token（轮换）。
- 登出：撤销 refresh token，并把当前 access token 的 jti 写入黑名单。
- 重置密码：校验验证码后调用 `user` 模块改密码。
- 查询当前用户：解析当前 access token，返回当前登录用户资料。

## 双 Token 机制

平台采用 access token + refresh token 的双 Token 方案。

### Access Token

- 短期 JWT，默认有效期 15 分钟（`platform.auth.jwt.access-token-ttl-seconds`，默认 `900` 秒）。
- 签名算法 HS256，密钥来自 `platform.auth.jwt.secret`（启动时校验至少 32 字节）。
- Payload 包含：`sub`（用户 id）、`username`、`role`（枚举名）、`jti`（每 token 唯一）、`typ=access`、`iat`、`exp`、`iss`。
- 不包含邮箱、手机号、头像等敏感字段。
- 解析时严格校验：签名、`iss`、`exp`、以及 `typ` 必须为 `access`（拒绝 refresh 类型 token 重放）。

### Refresh Token

- 服务端可撤销、可轮换的随机串，默认有效期 14 天（`platform.auth.refresh-token.ttl-seconds`，默认 `1209600` 秒）。
- 由 `SecureRandom` 生成（默认 48 字节，Base64url 编码），明文只在签发时返回给客户端一次。
- 数据库中只存 SHA-256 hash（`token_hash`，列宽 `CHAR(64)`），不存明文。
- 每次刷新执行轮换：撤销旧 token（`revoked_at` 置位、`replaced_by_token_id` 指向新 token），签发全新一对 token，通过 `replaced_by_token_id` 串联轮换链。

### 重放检测

当检测到一个**已被撤销**的 refresh token 被再次提交时，视为令牌重放（典型场景是攻击者拿到了旧 token，或客户端复用了旧 token）：

- 立即撤销该用户的**全部** refresh token（`revokeAllByUserId`）。
- 抛出 `AUTH_REFRESH_TOKEN_REUSED`，强制用户重新登录。
- 这是有意为之的激进策略：宁可误伤同一用户的多个设备，也不容许重放窗口存在。

## 验证码机制

### 存储与原子性

- **Redis 为主存储**，承担实时校验、限频、防暴破职责。
- **MySQL 只记可选审计**（`auth_verification_audit`），不存验证码明文，不参与实时校验。
- 发送和校验各由一段 Lua 脚本执行（`redis/auth-verification-send.lua`、`redis/auth-verification-verify.lua`），多 key 操作在脚本内原子完成。

### 默认策略

来自 `platform.auth.verification.*`：

| 控制项 | 默认值 |
| --- | --- |
| 验证码有效期 | 5 分钟（`code-ttl-seconds=300`） |
| 重发间隔 | 60 秒（`resend-interval-seconds=60`） |
| 每小时最多发送 | 5 次（`hourly-send-limit=5`） |
| 最多校验失败 | 5 次（`max-failed-attempts=5`） |
| 验证码长度 | 6 位（`code-length=6`） |

### 使用规则

- 邮箱登录、重置密码**必须**先校验通过验证码。
- 注册同样需要验证码。
- 登录失败（用户不存在 / 密码错误）统一返回 `AUTH_INVALID_CREDENTIALS`，不区分二者，避免泄露用户存在性。

## Redis Key 设计

所有 key 均在 `RedisVerificationStore`、`RedisTokenBlacklist` 中定义。

### 验证码相关

验证码相关 key 是**同一条业务记录在不同维度上的控制状态**，不是一条数据的三个字段：

| Key 模板 | 含义 | 生命周期 |
| --- | --- | --- |
| `auth:verification:code:{purpose}:{channel}:{target}` | 当前有效验证码的 hash | 验证码有效期（默认 5 分钟），校验成功后删除 |
| `auth:verification:send-rate:{purpose}:{channel}:{target}:resend` | 重发间隔控制 | 默认 60 秒 |
| `auth:verification:send-rate:{purpose}:{channel}:{target}:hourly` | 每小时发送计数 | 默认 1 小时（3600 秒） |
| `auth:verification:verify-rate:{purpose}:{channel}:{target}` | 校验失败计数（防暴破） | 默认 1 小时（3600 秒） |

- `send-rate:*:resend` 控制发送频率（60 秒内不可重发）。
- `send-rate:*:hourly` 控制小时级发送上限（默认 5 次）。
- `verify-rate:*` 控制暴力校验（默认最多 5 次失败后锁定）。

### Token 黑名单

| Key 模板 | 含义 |
| --- | --- |
| `auth:jwt:blacklist:{jti}` | 已登出的 access token jti |

值为哨兵 `"1"`，TTL 设为 access token 剩余有效期上限，token 自然过期后 key 自动消失，黑名单集合不会无限增长。

## 数据库表

`auth` 模块使用两张表，由 Flyway 基线迁移 `src/main/resources/db/migration/V1__init.sql` 创建。

### auth_refresh_token

保存 refresh token 的服务端状态。

关键字段与约束：

- `token_hash CHAR(64) NOT NULL`：refresh token 的 SHA-256 hash，**唯一**（`uk_auth_refresh_token_hash`）。
- `token_jti VARCHAR(64) NOT NULL`：refresh token 的 jti，**唯一**（`uk_auth_refresh_token_jti`）。
- `user_id BIGINT NOT NULL`：所属用户，**外键**指向 `user_account(id)`（`fk_auth_refresh_token_user`），并建普通索引 `idx_auth_refresh_token_user_id`。
- `expires_at DATETIME NOT NULL`：过期时间，索引 `idx_auth_refresh_token_expires_at`。
- `revoked_at DATETIME`：撤销时间，空表示未撤销。
- `replaced_by_token_id BIGINT`：轮换时指向新 token 的 id，串联轮换链。
- `device_id`、`user_agent`、`ip_address`：可选的设备/来源信息。
- `created_at`、`updated_at`：记录时间。

### auth_verification_audit

可选的验证码审计流水。

- 只记录发送 / 校验事件（`channel`、`target`、`purpose`、`status`、`failure_reason`、`request_ip`、`consumed_at`）。
- **不存验证码明文**，**不参与实时校验**。
- 仅供事后审计、风控分析使用。

## API 列表

所有 `auth` 接口挂在 `/api/auth/**`，由 `SecurityConfig` 标记为 `permitAll`，**匿名可访问**（注册、登录、刷新、登出、重置密码、发验证码都不需要 bearer token；`/me` 与 `logout` 虽然在 `permitAll` 路径下，但依赖 `SecurityContext` 中已注入的 `AuthenticatedPrincipal`，无有效 token 时返回 401）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/verification-codes` | 发送验证码（注册 / 登录 / 重置密码） |
| `POST` | `/api/auth/register` | 注册（需验证码） |
| `POST` | `/api/auth/login/password` | 密码登录 |
| `POST` | `/api/auth/login/verification-code` | 验证码登录 |
| `POST` | `/api/auth/token/refresh` | 刷新令牌（轮换） |
| `POST` | `/api/auth/logout` | 登出（撤销 refresh + access 进黑名单） |
| `POST` | `/api/auth/password/reset` | 重置密码（需验证码） |
| `GET` | `/api/auth/me` | 查询当前登录用户资料 |

## 安全策略

- **登录失败不泄露用户存在性**：用户不存在与密码错误统一返回 `AUTH_INVALID_CREDENTIALS`。
- **恒定时间 dummy 校验**：密码登录在"用户不存在"分支会先跑一次 dummy BCrypt 校验（命中一个公开的、不匹配任何真实凭证的 hash），使该分支与"密码错误"分支的 BCrypt CPU 开销接近，缓解时序侧信道。
- **access token 可吊销**：登出时把当前 access token 的 jti 写入 Redis 黑名单（`auth:jwt:blacklist:{jti}`），TTL 取 access token 剩余有效期上限；`JwtAuthenticationFilter` 在通过签名/过期校验后会查黑名单拒绝已登出 token。
- **refresh token 不存明文**：库中只存 SHA-256 hash，明文仅签发时返回一次。
- **密码哈希**：使用 BCrypt。
- **注册 / 重置密码必须验证码**：未通过验证码校验不可创建账号或改密码。
- **JWT 严格校验**：签名 + issuer + 过期 + `typ=access` 全部校验。
- **access token 无敏感字段**：payload 只含 `sub`/`username`/`role`/`jti`/`typ`，不含邮箱、手机号、头像。
- **令牌重放即熔断**：检测到 refresh token 重放立即撤销该用户全部 refresh token。

## 本地测试方式

### 单元测试

```bash
./mvnw.cmd test '-Dspring.profiles.active=test'
```

- 不依赖 MySQL / Redis：`test` profile 排除 `DataSourceAutoConfiguration`、`RedisAutoConfiguration`、`KafkaAutoConfiguration`。
- 服务层使用 Fake / 内存实现验证业务规则。

### 集成测试

```bash
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- 依赖本地 MySQL（`localhost:3307`）+ Redis（`localhost:6379`）。
- 覆盖：Redis Lua 验证码脚本（限频、小时上限、暴破锁定、验证码消费）、refresh token 持久化与轮换、完整 HTTP 认证流程（注册 → /me → 刷新 → 重放被拒 → 登出 → 黑名单生效、401 行为）。

### Schema 来源

auth 表（`auth_refresh_token`、`auth_verification_audit`）由 Flyway 基线迁移 `V1__init.sql` 创建。集成测试 context 启动时 Flyway 自动应用迁移，**不再需要手动建表或重建数据卷**。
