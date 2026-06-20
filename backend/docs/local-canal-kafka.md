# 本地 Canal + Kafka 配置说明

## 组件职责

本地开发环境使用以下链路支撑 relation 模块的 outbox 事件：

```text
relation_outbox(MySQL)
  -> Canal 监听 MySQL binlog
  -> Kafka topic: relation-events
  -> Spring Kafka consumer group: relation-follower-projector-group
```

当前 `deploy/docker-compose.yml` 已配置：

- MySQL：开启 `ROW` 格式 binlog。
- MySQL：当前本地环境使用 MySQL 8.4，显式设置 `binlog_checksum=NONE`。
- Kafka：宿主机使用 `localhost:9092`，容器内部使用 `kafka:29092`。
- Kafka topic：
  - `relation-events`
  - `relation-follower-retry`
  - `relation-follower-dlq`
- Canal：使用 `canal/canal-server:v1.1.8`，监听 `knowledge_platform.relation_outbox`，并发送 Canal flat message 到 `relation-events`。

## 启动方式

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql kafka kafka-init canal
```

如果只想检查容器：

```powershell
docker ps
```

## MySQL 检查

在 Navicat 或 MySQL 命令行执行：

```sql
SHOW VARIABLES LIKE 'log_bin';
SHOW VARIABLES LIKE 'binlog_format';
SHOW VARIABLES LIKE 'binlog_row_image';
SHOW VARIABLES LIKE 'server_id';
SHOW GRANTS FOR 'canal'@'%';
```

期望：

```text
log_bin = ON
binlog_format = ROW
binlog_row_image = FULL
server_id = 1
canal 用户具备 SELECT, REPLICATION SLAVE, REPLICATION CLIENT
```

## Kafka 检查

```powershell
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

期望至少包含：

```text
relation-events
relation-follower-retry
relation-follower-dlq
```

查看 topic 分区：

```powershell
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic relation-events
```

## Canal 检查

```powershell
docker compose -f deploy/docker-compose.yml logs --tail=120 canal
```

Canal 详细日志已经 bind mount 到宿主机：

```text
deploy/canal/logs/canal/canal.log
deploy/canal/logs/relation/relation.log
```

如果 `deploy/canal/logs/relation/relation.log` 不存在，说明 `relation` destination 没有被启动；此时 `relation_outbox` 写入不会进入 Kafka。

如果日志中出现：

```text
command : 'show master status' has an error
```

这是 Canal `v1.1.7` 与 MySQL `8.4` 的兼容问题：Canal 1.1.7 初始化位点时会执行 `SHOW MASTER STATUS`，而 MySQL 8.4 已改为 `SHOW BINARY LOG STATUS`。当前本地环境已升级到 `canal/canal-server:v1.1.8`，`deploy/canal/conf/relation/instance.properties` 中不再写死 binlog 起点，正常情况下不需要手工维护位点。

如果后续排查 binlog 起点，可用 Navicat 执行：

```sql
SHOW BINARY LOG STATUS;
```

如果升级后仍看到 `show master status` 错误，先确认 Canal 容器实际镜像版本是否为 `v1.1.8`，并强制重建 Canal：

```powershell
docker compose -f deploy/docker-compose.yml up -d --force-recreate canal
```

容器刚启动时，如果 Kafka 还没有完全 ready，可能短暂出现连接警告。只要 `relation.log` 中出现 `find start position successfully`，并且 MySQL `PROCESSLIST` 中出现 `canal` 的 `Binlog Dump` 线程，就说明 Canal 已经连上 MySQL binlog。

Canal 详细配置文件：

- `deploy/canal/conf/canal.properties`
- `deploy/canal/conf/relation/instance.properties`

## 注意事项

- Canal 当前只监听 `knowledge_platform.relation_outbox`，不会直接发布业务表 `relation_following` 的变更。
- 在 relation 模块表结构完成前，Canal 不会产生业务消息，这是正常现象。
- `relation_follower` 从表由后续 Spring Kafka consumer 异步更新，不由关注接口同步写入。
- `deploy/canal/conf/canal.properties` 中关闭了 `canal.auto.scan`，避免 Canal 镜像自带的 `example` destination 也被启动。

## 手动 outbox → Kafka smoke

在确认 MySQL / Kafka / Kafka topic / Canal 都正常后，可以插一行合成的 `relation_outbox` 数据，验证 Canal 把 binlog 变更发到了 `relation-events` topic。

> 这一步只验证 Canal→Kafka 的投递链路本身，**不需要**启动后端应用或消费者。

### 1. 起一个 console consumer 监听 relation-events

```powershell
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 --topic relation-events --from-beginning
```

### 2. 向 relation_outbox 插一行合成事件

在 MySQL 中执行（`payload_json` 是合法 JSON，与真实 outbox 行结构一致）：

```sql
INSERT INTO knowledge_platform.relation_outbox
  (event_id, aggregate_type, aggregate_id, event_type, follower_id, following_id, payload_json, occurred_at)
VALUES (
  'smoke-0001',
  'FOLLOW',
  'FOLLOW:1:2',
  'USER_FOLLOWED',
  1, 2,
  '{"eventId":"smoke-0001","eventType":"USER_FOLLOWED","aggregateType":"FOLLOW","aggregateId":"FOLLOW:1:2","followerId":1,"followingId":2,"occurredAt":"2026-06-20T10:00:00"}',
  NOW()
);
```

### 3. 观察 console consumer 收到 Canal flat message

Canal 监听到 `relation_outbox` 的 INSERT 后，会向 `relation-events` 发送一条 Canal flat message，其 `data[0]` 包含刚插入的行（含 `payload_json` 列）。consumer 端能读到即说明 Canal→Kafka 链路正常。

真实 Canal flat message 中，MySQL `JSON` 列 `payload_json` 的输出形态是**字符串**，示例：

```json
{
  "table": "relation_outbox",
  "type": "INSERT",
  "data": [
    {
      "event_id": "smoke-0001",
      "payload_json": "{\"eventId\":\"smoke-0001\",\"eventType\":\"USER_FOLLOWED\"}"
    }
  ]
}
```

这与当前 `RelationEventParser` 的解析方式一致。

### 4. 清理 smoke 数据（可选）

```sql
DELETE FROM knowledge_platform.relation_outbox WHERE event_id = 'smoke-0001';
```

清理只删库里的行；Canal 会再发一条 DELETE 到 `relation-events`，消费者（`RelationFollowerProjectorConsumer`）对非 INSERT 类型直接忽略，不会污染 follower 投影。
