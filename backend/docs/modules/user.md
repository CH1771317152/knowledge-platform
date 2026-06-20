# 用户模块说明

## 模块定位

`user` 模块负责平台内用户账号和用户资料的管理，是其他业务模块识别用户身份、展示用户公开信息的基础模块。

当前实现已经把原先 `profile` 的职责合并进 `user` 模块。`auth` 模块后续只负责登录认证、令牌签发和当前登录态识别；用户账号创建、资料查询、资料维护等用户数据能力由 `user` 模块提供。

## 已实现功能

- 创建用户账号，并同步创建默认用户资料。
- 校验用户名和邮箱的唯一性。
- 查询用户公开资料。
- 通过用户名查询用户公开资料。
- 查询当前登录用户资料。
- 更新当前登录用户的资料字段。
- 为登录认证场景提供按用户名或邮箱查找账号的能力。

当前模块不负责点赞、收藏、关注、内容发布或文件上传。点赞和收藏归 `counter` 模块管理，关注关系归 `relation` 模块管理，内容归 `content` 模块管理，文件归 `storage` 模块管理。

## 包结构

```text
com.platform.user
  application/      用户用例编排，区分命令与查询
  controller/       用户相关 HTTP 接口
  domain/           用户账号、用户资料、状态、角色等领域对象
  dto/              请求命令与响应 DTO
  infrastructure/   MyBatis 持久化实现
  repository/       用户仓储接口
```

## 核心对象

### UserAccount

表示用户账号主体，字段包括：

- `id`
- `username`
- `email`
- `phone`
- `passwordHash`
- `status`
- `role`
- `emailVerified`
- `phoneVerified`
- `lastLoginAt`
- `createdAt`
- `updatedAt`

`passwordHash` 保存在账号对象中，但公开资料响应不会返回该字段。

### UserProfile

表示用户展示资料，字段包括：

- `userId`
- `displayName`
- `avatarUrl`
- `bio`
- `location`
- `website`
- `birthday`
- `createdAt`
- `updatedAt`

账号和资料在数据库中拆成两张表，便于把认证敏感信息和公开展示信息分离。

## 应用服务

### UserCommandService

负责会改变用户数据的操作。

- `createUser(CreateUserCommand command)`
  - 标准化并校验 `username`、`email`、`passwordHash`。
  - 校验用户名唯一。
  - 校验邮箱唯一。
  - 创建 `ACTIVE` 状态、`USER` 角色的账号。
  - 默认邮箱和手机号均为未验证状态。
  - 创建默认资料，默认 `displayName` 使用用户名。

- `updateProfile(Long userId, UpdateUserProfileCommand command)`
  - 先确认账号存在。
  - 再确认资料存在。
  - 标准化资料字段。
  - 如果资料未发生变化，直接返回原资料。
  - 如果发生变化，调用仓储更新资料。

### UserQueryService

负责只读查询。

- `getPublicProfile(Long userId)`：查询公开资料，响应中不包含邮箱。
- `getPublicProfileByUsername(String username)`：通过用户名查询公开资料。
- `getCurrentUser(Long userId)`：查询当前登录用户资料，响应中包含邮箱。
- `findAccountByUsernameOrEmail(String usernameOrEmail)`：为登录认证提供账号查询能力。

## HTTP 接口

当前控制器统一挂在 `/api/users`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/users/{userId}` | 查询用户公开资料 |
| `GET` | `/api/users/by-username/{username}` | 通过用户名查询公开资料 |
| `GET` | `/api/users/me` | 查询当前登录用户资料 |
| `PUT` | `/api/users/me/profile` | 更新当前登录用户资料 |

`/me` 相关接口依赖 Spring Security 注入的 `CurrentUser`。如果当前用户为空，会抛出 `PlatformException("current user is required")`。

## 数据库表

用户模块当前使用两张表。

### user_account

保存账号基础信息和认证相关字段。

关键约束：

- `username` 唯一。
- `email` 唯一。
- `phone` 唯一。
- `status` 默认 `ACTIVE`。
- `role` 默认 `USER`。
- `email_verified` 默认 `0`。
- `phone_verified` 默认 `0`。
- `last_login_at` 记录最近登录时间，初始为空。
- `created_at` 记录账号创建时间。
- `updated_at` 记录账号更新时间。

### user_profile

保存用户展示资料。

关键约束：

- `user_id` 是主键。
- `user_id` 外键关联 `user_account.id`。
- `birthday` 记录用户生日。
- `created_at` 记录资料创建时间。
- `updated_at` 记录资料更新时间。

## 持久化实现

模块通过 `UserRepository` 定义仓储边界，当前实现是 `MysqlUserRepository`。

`MysqlUserRepository` 依赖 MyBatis 的 `UserMapper`：

- 插入账号后使用数据库生成的主键。
- 使用账号主键创建用户资料。
- 通过 SQL 查询用户名、邮箱和登录名。
- 更新资料时只更新 `user_profile` 表。

这种设计让应用层只依赖仓储接口，不直接依赖 MyBatis。后续如果需要加入缓存、读写分离或分库分表，可以优先在仓储实现层演进。

## 响应模型

`UserProfileResponse` 区分公开资料和当前用户资料：

- 公开资料：不返回邮箱。
- 当前用户资料：返回邮箱。
- 公开资料和当前用户资料都可以返回展示资料字段，例如头像、简介、地区、网站、生日和资料更新时间。

这样可以在 DTO 层明确控制敏感字段暴露范围，避免控制器手动拼装响应时遗漏隐私规则。

## 异常处理

当前模块使用 `PlatformException` 表示业务异常，例如：

- `username already exists`
- `email already exists`
- `user not found`
- `user profile not found`
- `current user is required`

这些异常后续会由统一异常处理转换成标准 API 响应。

## 测试覆盖

当前已有用户模块应用层测试：

- `UserCommandServiceTest`
  - 创建账号和默认资料。
  - 拒绝重复用户名。
  - 拒绝重复邮箱。
  - 更新资料字段，包括生日。

- `UserQueryServiceTest`
  - 查询公开资料。
  - 验证公开资料不会返回邮箱，但会返回公开展示字段。
  - 未找到用户时抛出业务异常。

这些测试使用内存 Fake Repository，重点验证应用服务的业务规则，不依赖数据库和 Spring 容器启动。

测试环境的 `application-test.yml` 会排除 DataSource、Redis 和 Kafka 自动配置，所以当前单元测试不会连接 MySQL。真正的数据库读写需要通过本地环境或后续集成测试验证。

## 当前完成度

如果 Maven 测试已经全部通过，可以认为 `user` 模块的当前阶段开发完成：模块边界、应用服务、HTTP 接口、MyBatis 持久化结构和核心业务测试都已经具备。

需要注意的是，这不代表用户体系的所有最终能力都已经完成。下面能力适合放到后续迭代：

- 与 `auth` 模块打通真实注册和登录流程。
- 增加接口层 WebMvc 测试。
- 增加 MyBatis 集成测试。
- 增加手机号唯一性在应用层的显式校验。
- 增加用户禁用、解禁、角色变更等后台管理能力。
- 对高频公开资料查询接入 Caffeine 或 Redis 缓存。
