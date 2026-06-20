# Counter Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Git is now active for this project (remote `origin` on `main`); each task should end with a commit, handled by the implementer.

**Goal:** Build the counter module — a Redis-only, high-throughput counting system using binary SDS CountInt blocks (fixed-offset int64 via a CountSchema), sharded bitmaps as the like/fav fact layer, and a Kafka async aggregation pipeline (consumer → agg hash → dual-trigger Lua flush) feeding all platform counts (content like/fav/view/comment/share and user following/followers/posts/likes_received/favs_received).

**Architecture:** Facts and counts are split by data nature. like/fav use a sharded Redis bitmap as the dedup fact (`SETBIT` returns the prior bit → emit increment event only on a 0↔1 transition); view/share have no fact layer (pure aggregate, deduped by a client `Idempotency-Key` traveling as `eventId`); relation/posts counts consume other modules' events (their MySQL is the fact). All increments flow through one async pipeline: business modules emit `counter-events`, a consumer aggregates deltas into a Redis `agg` hash, and a dual-trigger flusher atomically drains each agg key into the CountInt blob via Lua (`flush_drain.lua`: `HGETALL`+`DEL`+`SETRANGE`-at-offset, atomic). Consumer idempotency is enforced by a MySQL `counter_consumed_event` table keyed by `(event_id, consumer_group)`.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Kafka (KRaft), Redis (StringRedisTemplate + Lua), MyBatis/MySQL (only for `counter_consumed_event`), Flyway, Spring Security JWT, JUnit 5.

---

## Global Constraints

- Redis is the ONLY store for counts and like/fav facts; MySQL is used solely for the `counter_consumed_event` dedup table. No count values live in MySQL.
- All counters are 8-byte little-endian signed int64 at fixed offsets defined by `CountSchema`; Redis Lua handles atomic read-modify-write at an offset (int64 is exact in Redis Lua doubles up to 2^53, far above any count).
- `@Profile("!test")` on every bean touching Redis/MySQL/Kafka producer (store, services, controller, MyBatis repo); `@Profile("!test & !integration")` on every `@KafkaListener` consumer and the flush scheduler (so no live Kafka containers / scheduled flushes run in tests). Unit tests construct beans directly with fakes; integration tests use real Redis (available at `localhost:6379` under the `integration` profile).
- Idempotency contract: every increment event carries `eventId`; the consumer dedups on `(event_id, consumer_group)` in `counter_consumed_event`. like/fav `eventId` is generated per bitmap transition; view/share `eventId` = the client `Idempotency-Key`.
- Reuse the existing `manualAckKafkaListenerContainerFactory` bean in `KafkaConfig` (added in the relation module) for all counter listeners — do not create a second factory.
- Schema migration is a NEW `V3__counter.sql` (V1 and V2 are already applied and must not be edited).
- Commands: unit `./mvnw.cmd test '-Dspring.profiles.active=test'`; integration `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'`.

---

## File Structure

- Modify: `backend/src/main/resources/application.yml` — add `platform.counter.*` (kafka topics, flush config).
- Modify: `backend/deploy/docker-compose.yml` — add `counter-events`, `counter-retry`, `counter-dlq` topics to `kafka-init`.
- Create: `backend/src/main/resources/db/migration/V3__counter.sql` — `counter_consumed_event`.
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java` — add COUNTER_* codes.
- Create: `backend/src/main/java/com/platform/counter/config/CounterProperties.java`
- Create: `backend/src/main/java/com/platform/counter/domain/CounterEntityType.java`, `CounterMetric.java`, `CountSchema.java`
- Create: `backend/src/main/java/com/platform/counter/domain/CountIntCodec.java`
- Create: `backend/src/main/java/com/platform/counter/event/CounterEventPayload.java`, `CounterEventTopics.java`
- Create: `backend/src/main/resources/redis/counter-incr-at-offset.lua`, `flush-drain.lua`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/redis/RedisCounterStore.java`
- Create: `backend/src/main/java/com/platform/counter/repository/CounterConsumedEventRepository.java`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/persistence/CounterConsumedEventMapper.java`, `MysqlCounterConsumedEventRepository.java`
- Create: `backend/src/main/java/com/platform/counter/application/CounterFactService.java`, `CounterReadService.java`
- Create: `backend/src/main/java/com/platform/counter/application/CounterAggregateConsumer.java`, `CounterFlushScheduler.java`
- Create: `backend/src/main/java/com/platform/counter/event/RelationCountConsumer.java` (and `ContentPublishCountConsumer.java` — see Task 7 dependency note)
- Create: `backend/src/main/java/com/platform/counter/controller/CounterController.java`
- Create: `backend/src/main/java/com/platform/counter/dto/*Response.java`
- Tests under `backend/src/test/java/com/platform/counter/**`
- Create: `backend/docs/modules/counter.md`; modify `backend/docs/modules/README.md`, `backend/docs/api-draft.md`.

---

## Task 1: Configuration, Error Codes, Migration

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/deploy/docker-compose.yml`
- Create: `backend/src/main/resources/db/migration/V3__counter.sql`
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Create: `backend/src/main/java/com/platform/counter/config/CounterProperties.java`
- Test: `backend/src/test/java/com/platform/counter/config/CounterPropertiesTest.java`

**Interfaces:**
- Produces: `CounterProperties` record (binds `platform.counter.*`); COUNTER_* error codes; `counter_consumed_event` table; Kafka topics `counter-events`/`counter-retry`/`counter-dlq`.

- [ ] **Step 1: Add counter error codes to `ErrorCode.java`**

Append after the RELATION_* codes:
```java
COUNTER_ENTITY_NOT_FOUND,
COUNTER_INVALID_METRIC,
COUNTER_FACT_CONFLICT,
COUNTER_EVENT_INVALID,
COUNTER_EVENT_CONSUME_FAILED
```

- [ ] **Step 2: Create `V3__counter.sql`**

```sql
-- V3__counter.sql — counter module: consumer idempotency dedup (counts/bitmap live in Redis)
CREATE TABLE counter_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_counter_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 3: Add `platform.counter.*` to `application.yml`**

Under the existing `platform:` block (sibling of `auth`/`storage`/`content`/`relation`):
```yaml
  counter:
    kafka:
      events-topic: ${COUNTER_EVENTS_TOPIC:counter-events}
      retry-topic: ${COUNTER_RETRY_TOPIC:counter-retry}
      dlq-topic: ${COUNTER_DLQ_TOPIC:counter-dlq}
      consumer-group: ${COUNTER_CONSUMER_GROUP:counter-aggregate-group}
      retry-consumer-group: ${COUNTER_RETRY_CONSUMER_GROUP:counter-aggregate-retry-group}
    flush:
      mode: ${COUNTER_FLUSH_MODE:adaptive}        # fixed | adaptive
      fixed-interval-ms: ${COUNTER_FLUSH_FIXED_MS:1000}
      min-interval-ms: ${COUNTER_FLUSH_MIN_MS:500}
      max-interval-ms: ${COUNTER_FLUSH_MAX_MS:5000}
      batch-size: ${COUNTER_FLUSH_BATCH:1000}
```

- [ ] **Step 4: Add counter topics to `kafka-init` in `deploy/docker-compose.yml`**

In the `kafka-init` service command, after the relation topic lines, add:
```text
&& /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic counter-events --partitions 3 --replication-factor 1 \
&& /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic counter-retry --partitions 3 --replication-factor 1 \
&& /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic counter-dlq --partitions 3 --replication-factor 1
```
(Match the existing line-continuation style of the relation topics.)

- [ ] **Step 5: Create `CounterProperties`**

```java
package com.platform.counter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.counter")
public record CounterProperties(Kafka kafka, Flush flush) {

    public record Kafka(
            String eventsTopic,
            String retryTopic,
            String dlqTopic,
            String consumerGroup,
            String retryConsumerGroup
    ) {}

    public record Flush(
            String mode,            // "fixed" | "adaptive"
            long fixedIntervalMs,
            long minIntervalMs,
            long maxIntervalMs,
            int batchSize
    ) {}
}
```
`@ConfigurationPropertiesScan("com.platform")` is already on `PlatformApplication` (added in auth), so it binds automatically.

- [ ] **Step 6: Write `CounterPropertiesTest`**

```java
package com.platform.counter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CounterPropertiesTest {
    @Autowired CounterProperties counterProperties;

    @Test
    void bindsDefaults() {
        assertThat(counterProperties.kafka().eventsTopic()).isEqualTo("counter-events");
        assertThat(counterProperties.flush().mode()).isEqualTo("adaptive");
        assertThat(counterProperties.flush().minIntervalMs()).isEqualTo(500);
        assertThat(counterProperties.flush().batchSize()).isEqualTo(1000);
    }
}
```
If `@SpringBootTest` under `test` needs a `UserRepository` bean (excluded datasource), add `@MockBean com.platform.user.repository.UserRepository` as in `AuthPropertiesTest`.

- [ ] **Step 7: Run tests**

`./mvnw.cmd test '-Dspring.profiles.active=test'` → BUILD SUCCESS; existing tests green; `CounterPropertiesTest` passes. `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` → Flyway applies V3, existing integration tests green.

---

## Task 2: Domain — CountSchema, CountIntCodec, Event Types

**Files:**
- Create: `backend/src/main/java/com/platform/counter/domain/CounterEntityType.java`, `CounterMetric.java`, `CountSchema.java`, `CountIntCodec.java`
- Create: `backend/src/main/java/com/platform/counter/event/CounterEventPayload.java`, `CounterEventTopics.java`
- Test: `backend/src/test/java/com/platform/counter/domain/CountSchemaTest.java`, `CountIntCodecTest.java`

**Interfaces:**
- Produces: `CounterEntityType` enum {ARTICLE, USER}; `CounterMetric` enum {LIKE, FAV, VIEW, COMMENT, SHARE, FOLLOWING, FOLLOWERS, POSTS, LIKES_RECEIVED, FAVS_RECEIVED}; `CountSchema.offset(entityType, metric) -> int`; `CountIntCodec` (read/write int64 LE at offset in `byte[]`); `CounterEventPayload` record; `CounterEventTopics` constants.

- [ ] **Step 1: Create enums**

```java
package com.platform.counter.domain;
public enum CounterEntityType { ARTICLE, USER }
```
```java
package com.platform.counter.domain;
public enum CounterMetric {
    LIKE, FAV, VIEW, COMMENT, SHARE,
    FOLLOWING, FOLLOWERS, POSTS, LIKES_RECEIVED, FAVS_RECEIVED
}
```

- [ ] **Step 2: Create `CountSchema`**

```java
package com.platform.counter.domain;

import java.util.Map;

/** Fixed byte offsets of each counter within a CountInt blob (8-byte little-endian int64 each). */
public final class CountSchema {

    private static final Map<CounterEntityType, Map<CounterMetric, Integer>> OFFSETS = Map.of(
            CounterEntityType.ARTICLE, Map.of(
                    CounterMetric.LIKE, 0,
                    CounterMetric.FAV, 8,
                    CounterMetric.VIEW, 16,
                    CounterMetric.COMMENT, 24,
                    CounterMetric.SHARE, 32),
            CounterEntityType.USER, Map.of(
                    CounterMetric.FOLLOWING, 0,
                    CounterMetric.FOLLOWERS, 8,
                    CounterMetric.POSTS, 16,
                    CounterMetric.LIKES_RECEIVED, 24,
                    CounterMetric.FAVS_RECEIVED, 32));

    public static final int BYTES_PER_COUNTER = 8;

    private CountSchema() {}

    public static int offset(CounterEntityType type, CounterMetric metric) {
        Integer off = OFFSETS.getOrDefault(type, Map.of()).get(metric);
        if (off == null) {
            throw new IllegalArgumentException("no offset for " + type + "/" + metric);
        }
        return off;
    }
}
```

- [ ] **Step 3: Create `CountIntCodec`**

Pure-Java int64 little-endian read/write at offset (used in unit tests and to assemble batched Lua args; the runtime Redis path uses Lua, but the codec encodes/decodes byte arrays for tests and any in-JVM needs).

```java
package com.platform.counter.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CountIntCodec {

    private CountIntCodec() {}

    public static byte[] encodeLong(long value) {
        byte[] b = new byte[8];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
        return b;
    }

    public static long decodeLong(byte[] bytes, int offset) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            int idx = offset + i;
            b[i] = idx < bytes.length ? bytes[idx] : 0;
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
```

- [ ] **Step 4: Create event payload + topics**

```java
package com.platform.counter.event;

import com.platform.counter.domain.CounterMetric;
import java.time.LocalDateTime;

public record CounterEventPayload(
        String eventId,
        CounterEntityTypeAlias etype,
        CounterMetric metric,
        Long eid,
        long delta,
        Long authorId,
        LocalDateTime occurredAt) {}
```
NOTE: to keep the payload self-describing without coupling the `event` package to `domain` for the type alias, use the real `CounterEntityType` directly:
```java
package com.platform.counter.event;

import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import java.time.LocalDateTime;

public record CounterEventPayload(
        String eventId,
        CounterEntityType etype,
        CounterMetric metric,
        Long eid,
        long delta,
        Long authorId,          // nullable; present for content interactions to fan out to author
        LocalDateTime occurredAt) {}
```
(Use this second version; delete the alias idea.)

`CounterEventTopics` (plain default-name constants — NOT `${...}`, same rule as `RelationEventTopics`):
```java
package com.platform.counter.event;

public final class CounterEventTopics {
    public static final String EVENTS = "counter-events";
    public static final String RETRY = "counter-retry";
    public static final String DLQ = "counter-dlq";
    public static final String CONSUMER_GROUP = "counter-aggregate-group";
    public static final String RETRY_CONSUMER_GROUP = "counter-aggregate-retry-group";
    private CounterEventTopics() {}
}
```

- [ ] **Step 5: Write `CountSchemaTest`**

```java
package com.platform.counter.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CountSchemaTest {
    @Test
    void articleOffsets() {
        assertThat(CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.LIKE)).isZero();
        assertThat(CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.SHARE)).isEqualTo(32);
    }
    @Test
    void userOffsets() {
        assertThat(CountSchema.offset(CounterEntityType.USER, CounterMetric.FOLLOWERS)).isEqualTo(8);
    }
    @Test
    void unknownMetricThrows() {
        assertThatThrownBy(() -> CountSchema.offset(CounterEntityType.ARTICLE, CounterMetric.FOLLOWERS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 6: Write `CountIntCodecTest`**

```java
package com.platform.counter.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountIntCodecTest {
    @Test
    void roundTrip() {
        byte[] b = CountIntCodec.encodeLong(1234567890123L);
        assertThat(CountIntCodec.decodeLong(b, 0)).isEqualTo(1234567890123L);
    }
    @Test
    void decodeTreatsShortBufferAsZero() {
        assertThat(CountIntCodec.decodeLong(new byte[]{1, 2}, 0)).isEqualTo(0x0201L);
    }
}
```

- [ ] **Step 7: Run tests** → `./mvnw.cmd test '-Dspring.profiles.active=test'` BUILD SUCCESS.

---

## Task 3: Redis Counter Store (Lua + Bitmap + Agg)

**Files:**
- Create: `backend/src/main/resources/redis/counter-incr-at-offset.lua`, `flush-drain.lua`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/redis/RedisCounterStore.java`
- Test: `backend/src/test/java/com/platform/counter/infrastructure/redis/RedisCounterStoreIntegrationTest.java`

**Interfaces:**
- Consumes: `CounterProperties` (topic names not needed here), `CountSchema`, `CounterEntityType`, `CounterMetric`.
- Produces: `RedisCounterStore` with:
  - `long readCount(etype, eid, metric)` — `GETRANGE cnt:{etype}:{eid} off off+7`, decode.
  - `Map<CounterMetric,Long> readCounts(etype, eid)` — `GETRANGE` whole blob, decode all known offsets.
  - `boolean hasActed(etype, eid, metric, userId)` — `GETBIT bm:{metric}:{etype}:{eid}:{chunk} bit`.
  - `boolean setBitIfAbsent(etype, eid, metric, userId)` — `SETBIT ... 1`, returns true iff old bit was 0 (transition).
  - `boolean clearBitIfPresent(etype, eid, metric, userId)` — `SETBIT ... 0`, returns true iff old bit was 1.
  - `void addToAggregate(etype, eid, metric, delta)` — `HINCRBY agg:{etype}:{eid} metric delta`; also `SADD counter:flush:pending {etype}:{eid}`.
  - `List<String> drainPendingBatch(int n)` — `SPOP counter:flush:pending n` (the agg keys to flush).
  - `void flushOne(String aggKey)` — runs `flush-drain.lua` on (aggKey, cntKey).
  - `int pendingCount()` — `SCARD counter:flush:pending` (load signal for adaptive flush).

- [ ] **Step 1: Create `counter-incr-at-offset.lua`**

Atomic offset increment (used directly only for tests / cross-module fan-out; the main path is flush-drain). KEYS[1]=cnt key; ARGV = [offset, delta] pairs.
```lua
local key = KEYS[1]
for i = 1, #ARGV, 2 do
  local off = tonumber(ARGV[i])
  local delta = tonumber(ARGV[i + 1])
  local raw = redis.call('GETRANGE', key, off, off + 7)
  local val = 0
  if string.len(raw) == 8 then
    val = tonumber(string.unpack('<i8', raw))
  end
  val = val + delta
  if val < 0 then val = 0 end
  redis.call('SETRANGE', key, off, string.pack('<i8', val))
end
return 1
```
NOTE: Redis 7.x Lua (5.1) supports `string.pack`/`unpack`. If the project's Redis is older, fall back to manual byte packing; verify on the integration Redis first (see Step 6).

- [ ] **Step 2: Create `flush-drain.lua`**

KEYS[1] = `agg:{etype}:{eid}`; KEYS[2] = `cnt:{etype}:{eid}`. ARGV = flat `[metric, offset]` pairs (the schema offsets for this etype) so the script can map agg field name → CountInt offset without the script knowing the schema.
```lua
local aggKey = KEYS[1]
local cntKey = KEYS[2]
local fields = redis.call('HGETALL', aggKey)
if #fields == 0 then return 0 end
redis.call('DEL', aggKey)
for i = 1, #ARGV, 2 do
  local metric = ARGV[i]
  local off = tonumber(ARGV[i + 1])
  for j = 1, #fields, 2 do
    if fields[j] == metric then
      local delta = tonumber(fields[j + 1])
      local raw = redis.call('GETRANGE', cntKey, off, off + 7)
      local val = 0
      if string.len(raw) == 8 then val = tonumber(string.unpack('<i8', raw)) end
      val = val + delta
      if val < 0 then val = 0 end
      redis.call('SETRANGE', cntKey, off, string.pack('<i8', val))
    end
  end
end
return 1
```

- [ ] **Step 3: Implement `RedisCounterStore`**

`@Repository @Profile("!test")`. Inject `StringRedisTemplate`. Load both scripts via `DefaultRedisScript<String>` with `setScriptText` in `@PostConstruct` (mirror `RedisVerificationStore` from the auth module). Key builders:
```java
String cntKey(CounterEntityType t, Long eid) { return "cnt:" + t + ":" + eid; }
String bmKey(CounterEntityType t, Long eid, CounterMetric m, long userId) {
    return "bm:" + m.name().toLowerCase() + ":" + t + ":" + eid + ":" + (userId / 262144L);
}
String aggKey(CounterEntityType t, Long eid) { return "agg:" + t + ":" + eid; }
long bitIndex(long userId) { return userId % 262144L; }
```
`setBitIfAbsent`: `Boolean wasSet = template.opsForValue().setBit(bmKey, bitIndex(userId)); return !wasSet;` (setBit returns the old bit; true = old was 0 = transition). `clearBitIfPresent`: same with value `false`, return the old bit (true = was 1).
`readCount`: `byte[] raw = getRange(cntKey, off, off+7); return CountIntCodec.decodeLong(raw, 0);` (GETRANGE returns bytes; if empty, decodeLong treats as 0).
`addToAggregate`: `template.opsForHash().increment(aggKey, metric.name(), delta); template.opsForSet().add("counter:flush:pending", aggKeyTag(t,eid));`
`drainPendingBatch`: `return template.opsForSet().pop("counter:flush:pending", n);` (returns the popped agg tags; convert tag → aggKey via a helper, or store the full aggKey in the set).
`flushOne(aggKey)`: build the ARGV `[metric, offset]` pairs for that etype from `CountSchema`, run `flush-drain.lua` with KEYS=[aggKey, cntKey].

- [ ] **Step 4: Write `RedisCounterStoreIntegrationTest`**

`@SpringBootTest @ActiveProfiles("integration")`, autowire `RedisCounterStore`. Use unique etype/eid per test (e.g. `ARTICLE`, `9000 + random`) to avoid cross-test key collisions; clean up keys in `@AfterEach` (`template.delete(...)`). Cover:
- `bitmapSetBitIsIdempotent`: setBitIfAbsent twice → first true, second false; hasActed true.
- `countIncrViaLuaIsAtomic`: run `counter-incr-at-offset.lua` with 100 threads each +1 → final readCount == 100.
- `aggregateThenFlushDrainsAtomically`: addToAggregate 3 metrics on one agg key → flushOne → agg key gone, readCounts reflect the deltas; calling flushOne again is a no-op (dedup via DEL).
- `negativeDeltaClampsToZero`.

- [ ] **Step 5: Run integration test**

`./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` → BUILD SUCCESS. If `string.pack`/`unpack` is unavailable on the integration Redis, switch `counter-incr-at-offset.lua`/`flush-drain.lua` to manual little-endian byte packing and re-run (record which form is used).

- [ ] **Step 6: Run unit suite** → `./mvnw.cmd test '-Dspring.profiles.active=test'` green (store is `@Profile("!test")`-gated; contextLoads unaffected).

---

## Task 4: Consumed-Event Repository

**Files:**
- Create: `backend/src/main/java/com/platform/counter/repository/CounterConsumedEventRepository.java`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/persistence/CounterConsumedEventMapper.java`, `MysqlCounterConsumedEventRepository.java`
- Test: `backend/src/test/java/com/platform/counter/infrastructure/persistence/MysqlCounterConsumedEventRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `boolean markConsumed(String eventId, String consumerGroup)` — inserts into `counter_consumed_event`; returns true on success, false on `DuplicateKeyException` (idempotent dedup). Mirror the relation module's `markConsumed`.

- [ ] **Step 1: Repository contract**
```java
package com.platform.counter.repository;
public interface CounterConsumedEventRepository {
    boolean markConsumed(String eventId, String consumerGroup);
}
```

- [ ] **Step 2: Mapper + MySQL adapter**

`@Mapper` with `@Insert("INSERT INTO counter_consumed_event (event_id, consumer_group) VALUES (#{eventId}, #{consumerGroup})")`. `MysqlCounterConsumedEventRepository` `@Repository @Profile("!test")` delegates and catches `org.springframework.dao.DuplicateKeyException` → return false.

- [ ] **Step 3: Integration test**

Insert a user (FK not required — this table has no FKs) — actually no user needed; just call `markConsumed(eid, group)` twice → true then false. `@Transactional` rollback for cleanup.

- [ ] **Step 4: Run** → unit green; integration green (table exists via V3).

---

## Task 5: Fact Service + Read Service

**Files:**
- Create: `backend/src/main/java/com/platform/counter/application/CounterFactService.java`, `CounterReadService.java`
- Create: `backend/src/main/java/com/platform/counter/dto/ArticleCountersResponse.java`, `InteractionResponse.java`
- Test: `backend/src/test/java/com/platform/counter/application/CounterFactServiceTest.java`, `CounterReadServiceTest.java`

**Interfaces:**
- Consumes: `RedisCounterStore` (Task 3), `KafkaTemplate<String,String>`, `ObjectMapper`, `CounterProperties`.
- Produces:
  - `CounterFactService.like(userId, eid, authorId)` / `unlike` / `fav` / `unfav` → returns `boolean changed` (transition); emits `counter-events` with delta +1/-1 only on transition.
  - `CounterFactService.view(userId, eid, authorId, idempotencyKey)` / `share(...)` → always emits (delta +1), `eventId = idempotencyKey`.
  - `CounterReadService.getArticleCounters(eid)` → `ArticleCountersResponse`; `hasActed(userId, eid, metric)` → boolean.

- [ ] **Step 1: DTOs**

`ArticleCountersResponse(Long postId, long like, long fav, long view, long comment, long share)`; `InteractionResponse(boolean changed, boolean acting)` (acting = current state after the call).

- [ ] **Step 2: Implement `CounterFactService`**

`@Service @Profile("!test")`. `like`: `boolean transition = store.setBitIfAbsent(ARTICLE, eid, LIKE, userId); if (transition) emit(like, +1, authorId); return transition;`. `unlike`: `boolean transition = store.clearBitIfPresent(...); if (transition) emit(LIKE, -1, authorId);`. `view`/`share`: validate `idempotencyKey` non-blank (else `COUNTER_EVENT_INVALID`); `emit(metric, +1, authorId, eventId=idempotencyKey)`. `emit` builds `CounterEventPayload`, serializes to JSON, `kafkaTemplate.send(eventsTopic, etype+":"+eid, json)`. JsonProcessingException → `COUNTER_EVENT_INVALID`.

- [ ] **Step 3: Implement `CounterReadService`**

`@Service @Profile("!test") @Transactional(readOnly=true)`-style (no DB, but mark intent). `getArticleCounters(eid)` → `store.readCounts(ARTICLE, eid)` mapped to DTO. `hasActed` → `store.hasActed(ARTICLE, eid, metric, userId)`.

- [ ] **Step 4: `CounterFactServiceTest` (unit, fake store + mock KafkaTemplate)**

Use a fake `RedisCounterStore` (subclass or interface — make `RedisCounterStore` methods extractable; simplest: the test fakes by extending `RedisCounterStore` with a null `StringRedisTemplate` and overriding each method, OR extract a `CounterStore` interface and have the test fake it — **extract a `CounterStore` interface** so the unit test fakes it cleanly, `RedisCounterStore implements CounterStore`). Mock `KafkaTemplate` (`when(send(...)).thenReturn(CompletableFuture.completedFuture(null))`). Cover:
- `likeOnNewArticleSetsBitAndEmitsIncrement`
- `likeWhenAlreadyLikedIsIdempotentNoEmit`
- `unlikeClearsBitAndEmitsDecrement`
- `unlikeWhenNotLikedIsIdempotentNoEmit`
- `viewEmitsWithIdempotencyKeyAsEventId`
- `viewRejectsBlankIdempotencyKey` → COUNTER_EVENT_INVALID
- fan-out: like with authorId emits payload carrying authorId (capture the sent JSON, assert authorId present).

- [ ] **Step 5: `CounterReadServiceTest`** — fake store; assert counters mapping + hasActed.

- [ ] **Step 6: Run** → unit green. (Refactor: extract `CounterStore` interface in Task 3's `RedisCounterStore` if not already; update Task 3's file. Keep `RedisCounterStore` `@Repository @Profile("!test")` implementing `CounterStore`.)

---

## Task 6: Aggregate Consumer + Dual-Trigger Flush

**Files:**
- Create: `backend/src/main/java/com/platform/counter/application/CounterAggregateConsumer.java`, `CounterFlushScheduler.java`
- Test: `backend/src/test/java/com/platform/counter/application/CounterAggregateConsumerTest.java`

**Interfaces:**
- Consumes: `CounterConsumedEventRepository`, `CounterStore` (agg ops + flush), `KafkaTemplate`, `CounterProperties`, `ObjectMapper`.
- Produces: `CounterAggregateConsumer.onEvent(String value, Acknowledgment ack)` (main, `counter-events`) and `onRetry` (`counter-retry`); `CounterFlushScheduler` (`@Profile("!test & !integration")`) tick + flush.

- [ ] **Step 1: `CounterAggregateConsumer`**

`@Component @Profile("!test & !integration")`. `@KafkaListener(topics="${platform.counter.kafka.events-topic}", groupId="${platform.counter.kafka.consumer-group}", containerFactory="manualAckKafkaListenerContainerFactory")`.
Processing:
1. Parse `value` as `CounterEventPayload` (JSON). On parse failure → route to retry, ack.
2. `if (!consumedRepo.markConsumed(payload.eventId(), consumerGroup)) { ack; return; }` (dedup).
3. `store.addToAggregate(payload.etype(), payload.eid(), payload.metric(), payload.delta())`.
4. If `payload.authorId() != null` and metric is LIKE/FAV → `store.addToAggregate(USER, authorId, likes_received|favs_received, payload.delta())`.
5. ack. Any exception → `kafkaTemplate.send(retryTopic, etype+":"+eid, value)` then ack (poison-loop-safe; retry-send failure → log + ack).
`onRetry` mirrors but on failure routes to `dlq-topic`. Use the retry-consumer-group for its `markConsumed`.

- [ ] **Step 2: `CounterFlushScheduler`**

`@Component @Profile("!test & !integration")`. `@Scheduled(fixedRateString = "${platform.counter.flush.min-interval-ms:500}")` tick. Each tick:
- Compute effective interval: if `mode==fixed` → `fixedIntervalMs`; else adaptive → `clamp(pendingCount()-based signal)` in `[minIntervalMs, maxIntervalMs]` (v1 signal: `store.pendingCount()` → e.g. `>5000 buckets ? minIntervalMs : maxIntervalMs`, linear between; exact curve is TODO).
- If `now - lastFlush >= effectiveInterval`: `List<String> keys = store.drainPendingBatch(batchSize); for each: store.flushOne(key);` then `lastFlush = now`.
- This runs only in non-test/non-integration profiles, so no live flush in tests.

- [ ] **Step 3: `CounterAggregateConsumerTest` (unit)**

Fake `CounterConsumedEventRepository` + fake `CounterStore` (capture agg adds + flushOne) + mock `KafkaTemplate`. Cover:
- `parsesEventAggregatesAndAcks`
- `duplicateEventAcksWithoutSecondAggregate` (markConsumed false)
- `likeEventFanOutsToAuthorReceived`
- `malformedEventRoutesToRetry`
- `aggregateFailureRoutesToRetry`
- `retryFailureRoutesToDlq`

- [ ] **Step 4: Run** → unit green; integration green (consumers/scheduler gated out of integration).

---

## Task 7: Cross-Module Consumers (relation + content-publish)

**Files:**
- Create: `backend/src/main/java/com/platform/counter/event/RelationCountConsumer.java`, `ContentPublishCountConsumer.java`
- Test: `backend/src/test/java/com/platform/counter/event/RelationCountConsumerTest.java`

**Interfaces:**
- Consumes: relation's `relation-events` (Canal flat messages of `relation_outbox` → `RelationEventPayload`), content's publish event (when available).
- Produces: user count updates via `CounterStore.addToAggregate(USER, userId, FOLLOWING|FOLLOWERS, ±1)` and `(USER, authorId, POSTS, +1)`.

- [ ] **Step 1: `RelationCountConsumer`**

`@Component @Profile("!test & !integration")`. `@KafkaListener(topics="${platform.relation.kafka.events-topic}", groupId="${platform.relation.kafka.follower-consumer-group}-counter"` OR a dedicated `counter-relation-group` property — **add `relation-consumer-group` to `CounterProperties.kafka`** and use it). Reuse relation's `RelationEventParser` (`com.platform.relation.event.RelationEventParser.parse(objectMapper, value)`) to extract `RelationEventPayload`. For `USER_FOLLOWED`: `addToAggregate(USER, followerId, FOLLOWING, +1)` and `addToAggregate(USER, followingId, FOLLOWERS, +1)`; for `USER_UNFOLLOWED`: deltas -1. Dedup via `counter_consumed_event` with the counter-relation group. Retry/DLQ to counter retry/dlq.

- [ ] **Step 2: `ContentPublishCountConsumer`**

`@Component @Profile("!test & !integration")`. Consumes content's publish event. **Dependency note:** content currently has a TODO outbox hook for `PostPublishedEvent` and does not yet emit it. Implement this consumer against the agreed payload shape `{eventId, postId, authorId, occurredAt}`; unit-test it with a synthetic event. End-to-end activation requires content's publish flow to emit the event — track as a content-side follow-up (small: content's `publish()` adds an outbox insert + the event flows through its existing Canal→Kafka path, OR content publishes directly to a `content-events` topic). For now: `addToAggregate(USER, authorId, POSTS, +1)` on the event; dedup via `counter_consumed_event`.

- [ ] **Step 3: Add `relation-consumer-group` / `content-events-topic` to `CounterProperties`**

Extend `CounterProperties.Kafka` with `relationConsumerGroup` and `contentEventsTopic` (+ defaults). Update `application.yml`.

- [ ] **Step 4: `RelationCountConsumerTest` (unit)** — fake store + fake consumed repo; feed a Canal flat message containing a `relation_outbox` USER_FOLLOWED payload → assert both FOLLOWING and FOLLOWERS +1. USER_UNFOLLOWED → -1. Duplicate → dedup.

- [ ] **Step 5: Run** → unit green; integration green.

---

## Task 8: REST Controller + Security + Docs

**Files:**
- Create: `backend/src/main/java/com/platform/counter/controller/CounterController.java`
- Modify: `backend/src/main/java/com/platform/config/SecurityConfig.java` (public read for `GET /api/posts/{postId}/counters`)
- Test: `backend/src/test/java/com/platform/counter/controller/CounterControllerIntegrationTest.java`
- Create: `backend/docs/modules/counter.md`; modify `backend/docs/modules/README.md`, `backend/docs/api-draft.md`

**Interfaces:**
- Consumes: `CounterFactService`, `CounterReadService`, `CurrentUserService` (auth module).
- Produces: the interaction + read endpoints.

- [ ] **Step 1: `CounterController`**

`@RestController @Profile("!test")`. Base `@RequestMapping("/api/posts")`. Use `currentUserService.requirePrincipal().userId()` for writes. Read `Idempotency-Key` header for view/share.
```text
POST   /{postId}/likes            -> like   (returns InteractionResponse)
DELETE /{postId}/likes            -> unlike
POST   /{postId}/favorites        -> fav
DELETE /{postId}/favorites        -> unfav
POST   /{postId}/views            -> view   (Idempotency-Key required)
GET    /{postId}/counters         -> getArticleCounters   (public)
GET    /{postId}/counters/liked   -> hasActed(LIKE)       (authenticated)
```
For like/fav, resolve `authorId` by loading the post's author via `ContentQueryService` (or `UserQueryService`-equivalent) — if a lightweight author lookup isn't available, accept the authorId resolution as a small content-side helper call and cache; report the chosen approach.

- [ ] **Step 2: SecurityConfig**

Add `permitAll` for `GET /api/posts/{postId}/counters` (public read of counts). Keep `GET /api/posts/{postId}/counters/liked` and all writes authenticated (fall through to `anyRequest().authenticated()`). Place the new matcher with the other specific GET matchers; verify no regression (full integration suite).

- [ ] **Step 3: Integration test**

`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("integration")`, real MySQL + Redis. Register a user (reuse CapturingSender pattern), create an article via content's draft→confirm→metadata→publish flow (or, to keep it focused, seed via content's services directly), then:
- `POST /api/posts/{id}/likes` → 200, changed=true; second like → changed=false (idempotent).
- `GET /api/posts/{id}/counters` (anonymous ok) → like=1.
- `GET /api/posts/{id}/counters/liked` (with token) → true.
- `DELETE /likes` → changed=true; counters like=... (async; the CountInt updates via the agg pipeline — but the consumer is gated out of integration, so for the integration test the count may lag). **To keep the integration test deterministic, the test should assert the BITMAP state (liked true/false) and the direct `CounterFactService`/`RedisCounterStore` reads, NOT the async-aggregated count.** Document this.
- `POST /views` without `Idempotency-Key` → 400 (COUNTER_EVENT_INVALID); with key → 200.

- [ ] **Step 4: Docs**

`docs/modules/counter.md` (Chinese, mirror relation.md): 模块职责、架构与数据流、CountSchema/CountInt/位图、写路径分类、幂等去重契约、聚合与双触发刷写、跨模块集成、API、一致性模型、测试方式、待办. Update `docs/modules/README.md` (add counter link) and `docs/api-draft.md` (Counter section with the new endpoints).

- [ ] **Step 5: Final verification** — `./mvnw.cmd test '-Dspring.profiles.active=test'` and `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` both BUILD SUCCESS.

---

## Self-Review

**Spec coverage:** all spec sections map to tasks — config/schema/error codes (T1), CountSchema/codec/events (T2), Redis store/Lua/bitmap/agg (T3), consumed-event dedup (T4), fact+read services (T5), aggregate consumer + dual-trigger flush (T6), cross-module relation/content consumers (T7), controller+security+docs (T8). Fact-layer selection rule implemented via the per-metric write paths in T5 + the cross-module consumers in T7. Idempotency contract (eventId / bitmap transition / Idempotency-Key / counter_consumed_event) covered in T4–T6. RDB+AOF and reconciliation are runtime/TODO (no code task) — explicitly listed as TODO in the spec.

**Placeholder scan:** the content-publish event dependency (T7) is flagged as a content-side follow-up, not a placeholder — the consumer is implemented and unit-tested against a synthetic event. The `string.pack` Lua question is gated behind a verify step (T3 Step 5). Adaptive flush curve is marked v1-simple with tuning as TODO (matches spec). No "TBD/TODO/implement later" in code steps.

**Type consistency:** `CounterStore` interface (extracted in T5 Step 6, back-applied to T3) used consistently by T5/T6/T7. `CounterEventPayload` fields match across T2/T5/T6. `CounterEntityType`/`CounterMetric` enums used consistently. Kafka topics/groups resolved via `CounterProperties` (not literal `${...}` in constants). `manualAckKafkaListenerContainerFactory` reused (not duplicated).

**Open dependency (call out before execution):** content does not yet emit `PostPublishedEvent`. The `ContentPublishCountConsumer` (T7) is implemented + unit-tested, but its end-to-end requires a small content-side change (emit the publish event). Decide before T7 whether to (a) defer that consumer, or (b) pair it with a content-side outbox emission.
