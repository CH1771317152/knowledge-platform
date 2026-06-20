# Auth 模块设计规格

## 目标

Auth 模块负责知识发布平台的身份认证与登录态管理，第一版支持验证码发送、注册、密码登录、验证码登录、刷新令牌、登出、重置密码和查询当前用户信息。

本模块采用 Spring Security + JWT 双令牌策略：access token 使用短期 JWT，refresh token 使用服务端可撤销、可轮换的随机令牌。验证码系统支持邮箱和短信渠道，注册与重置密码必须验证码，登录同时支持账号密码和验证码两种方式。

## 已确认决策

- 注册、重置密码必须通过邮箱或短信验证码。
- 登录支持账号密码登录，也支持邮箱或短信验证码登录。
- 采用 access token + refresh token 双令牌策略。
- access token 为短期 JWT，默认 15 分钟有效。
- refresh token 为不可读随机字符串，默认 14 天有效，只在数据库保存 hash。
- 每次刷新 refresh token 时进行轮换，旧 token 立即撤销。
- 检测到 refresh token 重放时，撤销该用户全部 refresh token。
- 验证码主存储使用 Redis，MySQL 只记录可选审计事件。
- Redis 验证码状态拆分为 code、send-rate、verify-rate 三类 key，并通过 Lua 脚本保证发送和校验动作的原子性。

## 模块边界

Auth 模块负责身份认证相关能力：

- 验证码发送、校验、限流、消费。
- 注册认证流程。
- 密码登录。
- 邮箱或短信验证码登录。
- access token 签发与校验。
- refresh token 签发、存储、轮换、撤销。
- 登出。
- 重置密码认证流程。
- 查询当前登录用户。

User 模块继续负责用户账号和用户资料：

- 创建用户账号。
- 查询用户账号。
- 修改密码 hash。
- 标记邮箱或手机号已验证。
- 查询当前用户资料。

Auth 不直接绕过 User 模块操作 `user_account` 和 `user_profile`。注册、修改密码、更新验证状态等动作通过 User 模块的应用服务完成。Auth 可以拥有自己的认证状态表，例如 `auth_refresh_token` 和验证码审计表。

## 推荐包结构

```text
src/main/java/com/platform/auth/
-- application
   -- AuthService
   -- TokenService
   -- VerificationService
   -- CurrentUserService
-- controller
   -- AuthController
   -- VerificationController
-- domain
   -- AuthSession
   -- VerificationCode
   -- TokenPair
   -- VerificationChannel
   -- VerificationPurpose
   -- LoginType
-- infrastructure
   -- jwt
      -- JwtTokenProvider
      -- JwtAuthenticationFilter
      -- JwtAuthenticationEntryPoint
   -- verification
      -- VerificationCodeGenerator
      -- VerificationSender
      -- EmailVerificationSender
      -- SmsVerificationSender
      -- LoggingVerificationSender
   -- redis
      -- RedisVerificationStore
      -- RedisTokenBlacklist
   -- security
      -- AuthenticatedPrincipal
      -- PasswordEncoderConfig
-- repository
   -- AuthRefreshTokenRepository
   -- MysqlAuthRefreshTokenRepository
   -- AuthVerificationAuditRepository
```

## Spring Security 设计

`SecurityConfig` 需要从当前最小配置升级为 JWT 认证链路：

- `/api/auth/**`、`/actuator/health`、`/error` 放行。
- 其他接口默认需要认证。
- `JwtAuthenticationFilter` 从 `Authorization: Bearer <accessToken>` 读取 token。
- `JwtTokenProvider` 校验签名、过期时间、token 类型和 jti。
- 校验成功后，将 `AuthenticatedPrincipal(userId, username, role, jti)` 写入 `SecurityContext`。
- `GET /api/auth/me` 从 `SecurityContext` 获取 userId，再调用 User 模块查询当前用户信息。

access token payload 只保存必要身份信息：

```text
sub: userId
username
role
jti
iat
exp
typ: access
```

不在 token 中保存邮箱、手机号、昵称、头像等敏感或易变信息。

## 数据库设计

### auth_refresh_token

该表是必须表，用于支持 refresh token 的服务端撤销和轮换。

```text
id
user_id
token_hash
token_jti
device_id
user_agent
ip_address
expires_at
revoked_at
replaced_by_token_id
created_at
updated_at
```

关键规则：

- 数据库只保存 refresh token hash，不保存明文。
- refresh token 正常刷新时，旧记录写入 `revoked_at`，新建一条 refresh token 记录。
- `replaced_by_token_id` 用于串联 token 轮换链路。
- 已撤销 token 再次使用时，视为疑似泄露，并撤销该用户全部 refresh token。

### auth_verification_audit

该表为可选审计表，不参与验证码实时校验，不保存验证码明文。

```text
id
channel
target
purpose
status
request_ip
failure_reason
created_at
consumed_at
```

用途：

- 记录验证码发送、消费、失败事件。
- 支持用户安全问题排查。
- 支持短信或邮件供应商问题定位。
- 为后续风控和安全分析保留事件数据。

## Redis 设计

验证码实时状态使用 Redis，不使用 MySQL 作为主存储。

Redis key 采用三类控制状态：

```text
auth:verification:code:{purpose}:{channel}:{target}
auth:verification:send-rate:{purpose}:{channel}:{target}
auth:verification:verify-rate:{purpose}:{channel}:{target}
```

三类 key 不是一条验证码数据的三个字段，而是三个不同生命周期的控制状态：

- `code` 表示当前验证码内容状态，用于判断验证码是否存在、是否正确。
- `send-rate` 表示发送频率状态，用于限制 60 秒内重复发送和小时级发送次数。
- `verify-rate` 表示校验失败状态，用于限制验证码暴力尝试。

不建立统一验证码集合。三类 key 通过相同的 `purpose/channel/target` 命名维度形成业务关联，由 Lua 脚本保证一次发送或一次校验动作中的原子性。

默认策略：

```text
验证码有效期：5 分钟
重复发送间隔：60 秒
每小时最多发送：5 次
最多校验失败：5 次
```

access token 黑名单使用 Redis：

```text
auth:jwt:blacklist:{jti}
```

登出时将当前 access token 的 jti 写入黑名单，TTL 为 access token 剩余有效时间。

## API 设计

```text
POST /api/auth/verification-codes
POST /api/auth/register
POST /api/auth/login/password
POST /api/auth/login/verification-code
POST /api/auth/token/refresh
POST /api/auth/logout
POST /api/auth/password/reset
GET  /api/auth/me
```

### 发送验证码

请求示例：

```json
{
  "channel": "EMAIL",
  "target": "user@example.com",
  "purpose": "REGISTER"
}
```

流程：

1. 校验 channel、target、purpose。
2. 使用 Redis Lua 脚本检查发送频率。
3. 生成 6 位数字验证码。
4. 写入验证码 hash，TTL 5 分钟。
5. 调用 `VerificationSender` 发送邮件或短信。
6. 写入可选 MySQL 审计事件。

### 注册

请求示例：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "phone": null,
  "password": "raw-password",
  "verificationChannel": "EMAIL",
  "verificationTarget": "alice@example.com",
  "verificationCode": "123456"
}
```

流程：

1. 校验验证码，purpose 必须为 `REGISTER`。
2. 使用 BCrypt 生成 password hash。
3. 调用 User 模块创建用户。
4. 根据验证渠道标记 emailVerified 或 phoneVerified。
5. 签发 access token 和 refresh token。
6. 返回 token pair 和当前用户摘要。

注册成功后自动登录。

### 密码登录

请求示例：

```json
{
  "principal": "alice@example.com",
  "password": "raw-password"
}
```

流程：

1. 通过 User 模块按 username 或 email 查询账号。
2. 检查账号状态是否允许登录。
3. 使用 BCrypt 校验密码。
4. 更新最后登录时间。
5. 签发 token pair。

登录失败时不区分用户不存在和密码错误，统一返回认证失败。

### 验证码登录

请求示例：

```json
{
  "channel": "EMAIL",
  "target": "alice@example.com",
  "verificationCode": "123456"
}
```

流程：

1. 校验验证码，purpose 必须为 `LOGIN`。
2. 通过邮箱或手机号查询已有用户。
3. 检查账号状态是否允许登录。
4. 签发 token pair。

第一版验证码登录只允许已有用户登录，不做静默注册。

### 刷新令牌

请求示例：

```json
{
  "refreshToken": "opaque-refresh-token"
}
```

流程：

1. 对 refresh token 做 hash。
2. 查询 `auth_refresh_token`。
3. 检查 token 是否存在、过期或撤销。
4. 有效时撤销旧 refresh token，并签发新的 access token 和 refresh token。
5. 旧 refresh token 被重复使用时，撤销该用户全部 refresh token。

### 登出

请求示例：

```json
{
  "refreshToken": "opaque-refresh-token"
}
```

流程：

1. 撤销 refresh token。
2. 从当前 access token 读取 jti。
3. 将 jti 写入 Redis 黑名单，TTL 到 access token 过期。
4. 返回成功。

### 重置密码

请求示例：

```json
{
  "channel": "EMAIL",
  "target": "alice@example.com",
  "verificationCode": "123456",
  "newPassword": "new-raw-password"
}
```

流程：

1. 校验验证码，purpose 必须为 `RESET_PASSWORD`。
2. 查询目标用户。
3. 使用 BCrypt 生成新 password hash。
4. 调用 User 模块更新密码。
5. 撤销该用户全部 refresh token。

### 查询当前用户

`GET /api/auth/me`

流程：

1. `JwtAuthenticationFilter` 已完成 access token 校验。
2. 从 `SecurityContext` 获取 userId。
3. 调用 User 模块查询当前用户资料。
4. 返回账号状态、用户名、验证状态和公开资料。

## 异常与错误码

认证失败时使用统一错误码，避免泄露过多账号状态。

```text
AUTH_INVALID_CREDENTIALS
AUTH_INVALID_VERIFICATION_CODE
AUTH_VERIFICATION_RATE_LIMITED
AUTH_TOKEN_EXPIRED
AUTH_TOKEN_INVALID
AUTH_REFRESH_TOKEN_REUSED
AUTH_ACCOUNT_DISABLED
AUTH_ACCESS_DENIED
```

密码登录失败不区分用户不存在和密码错误。验证码发送和校验的限流错误需要返回明确语义，方便前端展示倒计时或重试提示。

## 测试策略

### 服务层单元测试

使用 fake repository 和 fake verification store，覆盖：

- 注册成功。
- 注册验证码错误。
- 密码登录成功。
- 密码登录失败。
- 验证码登录成功。
- refresh token 正常轮换。
- refresh token 重放检测。
- 登出撤销。
- 重置密码后撤销全部 refresh token。

### Redis 集成测试

覆盖 Lua 脚本行为：

- 发送限流。
- 小时级发送次数限制。
- 验证失败次数限制。
- 验证码成功消费。
- 验证码 TTL 生效。

### MySQL 集成测试

覆盖 refresh token 持久化行为：

- 创建 refresh token。
- 正常刷新并轮换。
- 登出撤销。
- 旧 refresh token 重放检测。
- 撤销某用户全部 refresh token。

### Security 集成测试

覆盖接口认证链路：

- 未携带 token 访问受保护接口返回 401。
- 有效 access token 可以访问 `/api/auth/me`。
- 黑名单 access token 被拒绝。
- `/api/auth/**` 匿名放行。

## 后续实施顺序

1. 补充 User 模块对 Auth 需要的应用服务接口，例如修改密码、更新验证状态、更新最后登录时间。
2. 新增 auth 配置项、JWT 组件和 Spring Security 过滤链。
3. 新增 refresh token 表、实体、仓储和服务。
4. 新增 Redis Lua 验证码存储和验证码发送接口。
5. 实现注册、密码登录、验证码登录、刷新、登出、重置密码、当前用户接口。
6. 补充单元测试、Redis 集成测试、MySQL 集成测试和 Security 集成测试。
