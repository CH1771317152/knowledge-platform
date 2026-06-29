# 压力测试目录说明

> 使用 **k6 v2.0.0** 对 Knowledge Platform 后端进行 HTTP 压力测试。
> **前提：后端用 `local` profile 启动**（连全部基础设施 + 启动所有 Kafka 消费者和定时任务）。

## 目录结构

```
backend/scripts/loadtest/
├── CLAUDE.md                ← 本文件（目录说明 + 压测指南）
├── config.js                ← 共享配置（BASE_URL / login / POST_IDS）
├── testuser.md              ← 测试用户信息（userId 970-972）
├── scenario1-feed-read.js   ← Feed 读（带 think time，模拟真实浏览）
├── scenario2-likes-write.js ← 点赞写入（阶梯递增 QPS，找写入拐点）
├── scenario3-login.js       ← 登录（BCrypt CPU 瓶颈）
├── scenario4-mixed.js       ← 混合读写（5 场景并行，最接近真实流量）
├── scenario5-feed-throughput.js ← Feed 纯吞吐极限（无 think time）
├── scenario6-auth-throughput.js ← 认证读+写综合吞吐
└── results/                 ← 压测结果存档（每次跑完把结果贴到这里）
```

## results/ 文件夹

每次压测跑完后，把结果保存为 Markdown 文件放到 `results/` 目录。

**命名规则**：`{场景名}-{profile}-{日期}.md`

```
results/
├── scenario1-feed-read-local-20260629.md
├── scenario2-likes-write-local-20260629.md
└── ...
```

**文件内容模板**：

```markdown
# 场景 N 结果 — {标题}（{profile} profile）

**日期**：YYYY-MM-DD
**Profile**：local
**脚本**：scenarioN-xxx.js

## 结果

| 指标 | 值 |
|---|---|
| 总请求 | |
| 平均 QPS | |
| p(50) | |
| p(95) | |
| max | |
| 错误率 | |

## 分析

（对比上次、发现瓶颈、改进建议）
```

也可以直接用 k6 导出 JSON：

```bash
k6 run --summary-export=results/scenario1-feed.json scenario1-feed-read.js
```

## 环境准备

```bash
# 1. 启动基础设施
cd backend/deploy && docker compose up -d mysql redis kafka kafka-init canal

# 2. 启动后端（local profile = 全栈运行）
cd backend && ./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'

# 3. 确认健康
curl http://localhost:8080/actuator/health
```

## 脚本说明

| 脚本 | 测什么 | 策略 |
|---|---|---|
| scenario1-feed-read.js | Feed 读基线 | 50→100 VUs，think time 0.5-2s |
| scenario2-likes-write.js | 点赞写入拐点 | QPS 50→200→500→1000 阶梯递增 |
| scenario3-login.js | BCrypt CPU 瓶颈 | 5→10→20 VUs |
| scenario4-mixed.js | 混合流量（5 场景并行） | Feed 80VU + 我的 30VU + 点赞 100/s + 登录 3VU + 详情 20VU |
| scenario5-feed-throughput.js | Feed 纯吞吐极限 | QPS 200→5000 无 think time |
| scenario6-auth-throughput.js | 认证读+写综合吞吐 | Feed 100→2000 + 点赞 50→1000 |

## local vs integration 的关键差异

| 组件 | integration（之前） | local（现在） |
|---|---|---|
| Kafka 消费者 | ❌ 不启动 | ✅ 全部运行 |
| 定时任务 | ❌ 不启动 | ✅ 全部运行（500ms-30s） |
| Kafka send | fire-and-forget | **acks=all + 幂等**（等确认 ~5ms） |
| 点赞链路 | SETBIT → 无人消费 | SETBIT → Kafka → 消费者 → 聚合 → 刷写 |

**integration 的压测结果不代表真实性能**——异步链路是空的。

## 压测时同时观察

### Kafka lag（重要）

```bash
docker compose -f backend/deploy/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --all-groups --describe 2>/dev/null
```

### Docker 资源

```bash
docker stats --no-stream
```

### Redis

```bash
docker compose -f backend/deploy/docker-compose.yml exec -T redis redis-cli INFO clients
docker compose -f backend/deploy/docker-compose.yml exec -T redis redis-cli INFO stats
```

## 关键指标

| 指标 | 健康值 | 告警值 |
|---|---|---|
| p(50) | < 5ms | > 50ms |
| p(95) | < 100ms | > 500ms |
| 错误率 | < 1% | > 5% |
| Kafka LAG | < 100 | > 10000 |

## 瓶颈排查顺序

1. k6 是否瓶颈（VU < maxVUs 且 p95 < 50ms → k6 到极限）
2. Kafka 消费 lag（LAG 持续增长）
3. HikariCP 满（日志 "Connection not available"）
4. Redis 慢（redis-cli SLOWLOG）
5. MySQL 慢（SHOW PROCESSLIST）
6. JVM GC（jstat -gc）
7. Docker CPU（docker stats）
