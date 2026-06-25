# Knowledge Platform — CLAUDE.md

> 高并发知识发布平台后端。Java 17 / Spring Boot 3.3.5 / 模块化单体。
> 源码 `backend/`，基础设施 `backend/deploy/`，文档 `backend/docs/`。

## 技术栈

Java 17（运行时 21）/ Spring Boot 3.3.5 / Spring Security 6 / MyBatis
MySQL 8.4（Docker :3307）/ Redis 7.4（Docker :6379）/ Caffeine
Kafka 3.8.0 KRaft / Canal v1.1.8（binlog→Kafka）/ Flyway（V1–V4）
Aliyun OSS SDK 3.18.1 / JJWT 0.12.6（HS256）/ BCrypt

## 模块架构

```
com.platform
├── user       账号/资料/角色/状态
├── auth       JWT 双令牌 + 验证码 + Spring Security 链
├── content    六阶段可中断发布（正文存 OSS）/ 雪花 ID / cursor 分页
├── storage    OSS 适配层（presign / stat / read）
├── relation   关注关系（outbox + Canal→Kafka 异步投影）
├── counter    计数（Redis CountInt + 位图 + Kafka 异步聚合）
├── cache      Feed 三级缓存（L2/L1/L0）+ 热key检测
├── common     ApiResponse / ErrorCode / PlatformException / GlobalExceptionHandler
└── config     SecurityConfig / KafkaConfig / RedisConfig / MyBatisConfig
```

每个模块遵循分层：`domain`（枚举+记录）→ `repository`（接口）→ `infrastructure`（MyBatis/Redis 实现）→ `application`（服务）→ `controller`（REST）。

## 文档导航

| 需要查什么 | 去哪里 |
|---|---|
| 某模块的设计原理 | `docs/superpowers/specs/{date}-{module}-design.md` |
| 某模块的任务拆分 | `docs/superpowers/plans/{date}-{module}.md` |
| 某模块的使用说明 | `docs/modules/{module}.md` |
| API 端点清单 | `docs/api-draft.md` |
| 测试策略 | `docs/testing.md` |
| Canal/Kafka 本地启动 | `docs/local-canal-kafka.md` |
| 延期功能 / TODO | `docs/deferred-features.md`（如存在） |

## 运行命令

```bash
cd backend/deploy && docker compose up -d mysql redis kafka kafka-init canal
./mvnw.cmd test '-Dspring.profiles.active=test'                          # 单元（无外部依赖）
./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration' # 集成（需 MySQL+Redis）
./mvnw.cmd clean verify '-Pintegration' '-Dspring.profiles.active=integration'  # 全量
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=integration'      # 启动
```

## 项目级约束（所有开发必须遵守）

### 1. @Profile 纪律（最高优先级）

test profile 排除了 DataSource / Redis / Kafka 自动配置。

| Bean 类型 | @Profile |
|---|---|
| MySQL 仓储 / Redis 仓储 / Service / Controller | `!test` |
| `@KafkaListener` / `@Scheduled` | `!test & !integration` |
| OSS 真实适配器 | `!test & (!integration \| aliyun-oss-smoke)` |

任何新 bean 如果依赖 DataSource/Redis/Kafka，必须加对应 `@Profile`，否则 test profile 下 contextLoads 崩溃。单元测试直接构造（fake），不依赖 Spring 注入。

后端按照"Controler->Service->Repository"分层

### 2. Flyway 迁移不可修改

已应用的迁移文件**不可修改**（Flyway checksum 验证）。新表/改表 → 新建 `V{n}__name.sql`。当前版本：V1→V2→V3→V4。

### 3. 分页用 cursor（keyset），不用 offset

所有 Feed/列表场景使用 `(timestamp, id)` cursor 分页。offset 在活跃数据上导致全页失效。

### 4. 计数只存 Redis

counter 模块的计数值不落 MySQL（除消费幂等表）。CountInt 二进制 SDS + 位图 + Lua 原子增减（`bit` 库，**非** `string.pack`——Redis Lua 是 5.1）。

### 5. MyBatis record 映射

Java record 无 setter → 用 `@ConstructorArgs`/`@Arg`（非 `@Results`/`@ResultMap`）。Redis Lua 返回整数 → `DefaultRedisScript<Long>`。

### 6. 多 CacheManager 加 @Primary

项目有两个 CacheManager：现有的 `cacheManager`（CaffeineCacheConfig，`@Primary`）+ Feed 专用 `feedL2CacheManager`。Feed L2 用 `@Qualifier` 注入，不碰 `LocalCacheNames`。

### 7. 环境约定

- 项目 MySQL 在 **Docker :3307**（本机原生 MySQL 在 :3306 是另一个实例）
- Git SSH 端口 22 在中国被墙，`~/.ssh/config` 已配 `github.com → ssh.github.com:443`
- SSH key 已去密码；推送偶发 reset → 重试即可
- PowerShell 的 `$env:` 不传给 Git Bash——凭据放 `~/.bashrc` 或 `setx`

## API 路由

```
permitAll:   /api/auth/{register,login/**,token/refresh,...}
             /api/feed/public / /api/posts, /api/posts/{id}
             /api/users/{id}/{following,followers}
authenticated: 其余所有
```

SecurityConfig 使用**有序匹配器**：具体的 authenticated 路径（如 `/api/posts/me`、`/api/feed/me`）放在 permitAll 通配之前。

## Redis Key 命名

```
auth:verification:*    auth:jwt:blacklist:*     cnt:{etype}:{eid}
bm:{metric}:{etype}:{eid}:{chunk}              agg:{etype}:{eid}
counter:flush:pending                           frag:post:{pid}
skel:{pageKey}                                  lock:{key}
```

## Git

仓库 `git@github.com:CH1771317152/knowledge-platform.git`，分支 `main`。

## 测试基线

236 单元 + 62 集成，1 skip（OSS smoke 环境变量门控）。集成测试需 `docker compose up -d mysql redis`。
