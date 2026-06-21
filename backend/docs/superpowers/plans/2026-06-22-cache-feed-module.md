# Cache/Feed Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Git is active (remote `origin` on `main`); each task should end with a commit, handled by the implementer.

**Goal:** Build a three-tier cache (L2 Caffeine local / L1 Redis skeleton / L0 Redis fragments) for the public Feed and "my posts" with cursor pagination, event-driven invalidation, personalization overlay, and periodic reconciliation.

**Architecture:** Cursor-keyset pagination (stable older pages on publish); L0 per-post fragments (with count snapshots from counter); L1 page skeletons (ID lists); L2 full assembled pages (local Caffeine, separate CacheManager). Read path: L2→L1→FeedSourceQuery backfill→L0 assemble→promote L2. Write path: cache-aside delayed double-delete. Invalidation via a unified content lifecycle event (eventType-discriminated on content-events, co-consumed by counter and cache). Counts never invalidate cache (counter async aggregation decoupled).

**Tech Stack:** Java 17, Spring Boot 3.3, Caffeine (separate CacheManager), Redis (StringRedisTemplate), Spring Kafka, MyBatis/MySQL (FeedSourceQuery keyset), Spring Security JWT, Flyway, JUnit 5.

---

## Global Constraints

- Both feeds use **cursor (keyset)** pagination: public `(published_at, id) DESC`; my-posts `(created_at, id) DESC`. Page size clamped to [1, 50].
- L2 uses a **separate CacheManager** (`feedL2CacheManager`) — do NOT touch the existing `CaffeineCacheConfig` / `LocalCacheNames`.
- L1/L0/Redis-backed services and the controller are `@Profile("!test")`; the Kafka invalidation consumer is `@Profile("!test & !integration")` (no live Kafka in tests). Unit tests construct directly with fakes.
- TTL jitter is **TTL-relative**: `ttl * (1 + rand(0..0.3))`, not absolute.
- No new MySQL table for cache; all state is Redis + Caffeine. The only MySQL change is `V4__feed_indexes.sql` (composite indexes on `content_post`).
- Cache is user-agnostic; personalization (`likedByMe`/`favedByMe`) is a read-time overlay via `hasActedBatch`, never written back to cache.
- Commands: unit `./mvnw.cmd test '-Dspring.profiles.active=test'`; integration `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'`.

---

## File Structure

### Counter module (Task 1 — prereq)
- Modify: `counter/application/CounterStore.java` — add `hasActedBatch`
- Modify: `counter/application/CounterReadService.java` — add `hasActedBatch`
- Modify: `counter/infrastructure/redis/RedisCounterStore.java` — implement batch GETBIT
- Modify: `counter/event/ContentPublishCountConsumer.java` — switch on eventType

### Content module (Task 2 — prereq)
- Modify: `content/event/PostPublishedEvent.java` → generalize to `ContentPostEvent` with eventType
- Modify: `content/event/PostPublishedEventKafkaPublisher.java` → handle all event types
- Modify: `content/application/ContentCommandService.java` — fire events from unpublish/delete/updateMetadata
- Create: `content/event/ContentPostEventType.java` — enum of event types

### Cache module (Tasks 3–12)
- Create: `cache/feed/config/FeedCacheProperties.java`
- Create: `cache/feed/config/FeedL2CacheConfig.java` — separate CacheManager
- Create: `cache/feed/domain/Cursor.java`, `FeedPage.java`, `FeedType.java`
- Create: `cache/feed/domain/FeedSourceQuery.java` — interface (content impl in Task 4)
- Create: `cache/feed/domain/PostFragment.java` — L0 fragment record
- Create: `cache/feed/infrastructure/redis/FragmentStore.java` — L0
- Create: `cache/feed/infrastructure/redis/SkeletonStore.java` — L1
- Create: `cache/feed/infrastructure/redis/FeedRedisKeys.java` — key builders
- Create: `cache/feed/application/FeedReadService.java` — three-tier read orchestrator
- Create: `cache/feed/application/FeedSingleFlight.java` — local + distributed lock
- Create: `cache/feed/application/FeedOverlayService.java` — personalization
- Create: `cache/feed/event/FeedInvalidationConsumer.java` — eventType dispatch
- Create: `cache/feed/application/FeedReconciliationScheduler.java`
- Create: `cache/feed/controller/FeedController.java`
- Create: `cache/feed/dto/FeedItemResponse.java`, `FeedPageResponse.java`
- Modify: `common/exception/ErrorCode.java` — CACHE_* codes
- Modify: `config/SecurityConfig.java` — feed read routes
- Create: `db/migration/V4__feed_indexes.sql`

---

## Task 1: Counter — hasActedBatch + Consumer eventType Switch

**Files:**
- Modify: `backend/src/main/java/com/platform/counter/application/CounterStore.java`
- Modify: `backend/src/main/java/com/platform/counter/application/CounterReadService.java`
- Modify: `backend/src/main/java/com/platform/counter/infrastructure/redis/RedisCounterStore.java`
- Modify: `backend/src/main/java/com/platform/counter/event/ContentPublishCountConsumer.java`
- Modify: `backend/src/main/java/com/platform/counter/event/ContentPublishedPayload.java`
- Test: `backend/src/test/java/com/platform/counter/application/CounterReadServiceTest.java` (add hasActedBatch test)
- Test: `backend/src/test/java/com/platform/counter/event/ContentPublishCountConsumerTest.java` (add eventType switch tests)

**Interfaces:**
- Produces: `Map<CounterMetric, Boolean> CounterStore.hasActedBatch(long userId, CounterEntityType etype, List<Long> eids, List<CounterMetric> metrics)` — pipeline batch GETBIT; `CounterReadService.hasActedBatch(...)` delegates.
- Produces: `ContentPublishCountConsumer` switches on `eventType` field in the payload; only `POST_PUBLISHED` → +1, `POST_UNPUBLISHED`/`POST_DELETED` → -1, others → skip.

- [ ] **Step 1: Add `hasActedBatch` to `CounterStore` interface**
```java
Map<CounterMetric, Map<Long, Boolean>> hasActedBatch(long userId, CounterEntityType etype,
        List<Long> eids, List<CounterMetric> metrics);
```
Returns a map keyed by metric → (eid → boolean). Pipeline GETBIT for each (eid, metric) combination.

- [ ] **Step 2: Implement in `RedisCounterStore`**
Use `template.executePipelined(...)` with a `RedisCallback` that calls `conn.setCommands().getBit(...)` (or `stringCommands().getBit`) for each `(bmKey(etype, eid, metric, userId), bitIndex(userId))` — note the BIT OFFSET is always `userId % 262144` (same for all eids of the same user+metric since the chunk is `userId/262144`). Actually the bitmap key changes per eid: `bm:{metric}:{etype}:{eid}:{chunk}`. So for N eids × M metrics, pipeline N*M GETBIT calls. Collect results into the map.

- [ ] **Step 3: Add `hasActedBatch` to `CounterReadService`** — delegate to store.

- [ ] **Step 4: Add `eventType` field to `ContentPublishedPayload`**
Rename to `ContentPostEventPayload` (or keep name, add `String eventType` field with default `"POST_PUBLISHED"`). The existing `eventType` is already hardcoded `"POST_PUBLISHED"` in the publisher — make it a real field.

- [ ] **Step 5: Update `ContentPublishCountConsumer` to switch on eventType**
```java
// in process():
ContentPostEventPayload payload = objectMapper.readValue(value, ContentPostEventPayload.class);
if (!consumedRepo.markConsumed(payload.eventId(), contentConsumerGroup)) { ack; return; }
switch (payload.eventType()) {
    case "POST_PUBLISHED" -> store.addToAggregate(USER, payload.authorId(), POSTS, +1);
    case "POST_UNPUBLISHED", "POST_DELETED" -> store.addToAggregate(USER, payload.authorId(), POSTS, -1);
    default -> { /* POST_EDITED, POST_VISIBILITY_CHANGED — not relevant to posts_count */ }
}
ack.acknowledge();
```

- [ ] **Step 6: Update tests**
- `CounterReadServiceTest`: add `hasActedBatchReturnsCorrectBitmap` — fake store returns a map, assert delegation.
- `ContentPublishCountConsumerTest`: update existing publish test (eventType=POST_PUBLISHED → +1); add `unpublishDecrementsPostsCount`; add `editedEventIsIgnored`.

- [ ] **Step 7: Run** `./mvnw.cmd test '-Dspring.profiles.active=test'` → green (baseline preserved + new tests). `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` → green.

---

## Task 2: Content — Unified Lifecycle Events

**Files:**
- Modify: `backend/src/main/java/com/platform/content/event/PostPublishedEvent.java` → rename/generalize
- Create: `backend/src/main/java/com/platform/content/event/ContentPostEventType.java`
- Modify: `backend/src/main/java/com/platform/content/event/PostPublishedEventKafkaPublisher.java`
- Modify: `backend/src/main/java/com/platform/content/application/ContentCommandService.java`
- Modify: `backend/src/test/java/com/platform/content/application/ContentCommandServiceTest.java`

**Interfaces:**
- Produces: a unified `ContentPostEvent` Spring event with `{eventId, eventType, postId, authorId, occurredAt, changes}`. The Kafka publisher sends to `content-events` with eventType in the JSON envelope. All 5 event types fired from the right ContentCommandService methods.

- [ ] **Step 1: Create `ContentPostEventType` enum**
```java
package com.platform.content.event;
public enum ContentPostEventType {
    POST_PUBLISHED, POST_UNPUBLISHED, POST_DELETED, POST_EDITED, POST_VISIBILITY_CHANGED
}
```

- [ ] **Step 2: Generalize `PostPublishedEvent` → `ContentPostEvent`**
```java
package com.platform.content.event;
import java.time.LocalDateTime;
import java.util.Map;

public record ContentPostEvent(
        String eventId,
        ContentPostEventType eventType,
        Long postId,
        Long authorId,
        LocalDateTime occurredAt,
        Map<String, Object> changes  // e.g. VISIBILITY_CHANGED: {oldVisibility, newVisibility}
) {
    // Convenience for publish (backward-compatible)
    public static ContentPostEvent published(String eventId, Long postId, Long authorId, LocalDateTime at) {
        return new ContentPostEvent(eventId, ContentPostEventType.POST_PUBLISHED, postId, authorId, at, Map.of());
    }
}
```
Keep `PostPublishedEvent` as a deprecated alias or update all references (ContentCommandService + test).

- [ ] **Step 3: Update the Kafka publisher to handle all event types**
The `@TransactionalEventListener(AFTER_COMMIT)` method accepts `ContentPostEvent` (the base). Serializes `{eventId, eventType: e.eventType().name(), postId, authorId, occurredAt: ..., changes}` to JSON; sends to `content-events` with key `post:{postId}`.

- [ ] **Step 4: Fire events from ContentCommandService**
- `publish()` first-publish: `ContentPostEvent.published(...)` (existing, just renamed).
- `unpublish()` on PUBLISHED→DRAFT transition: `new ContentPostEvent(uuid, POST_UNPUBLISHED, postId, authorId, now, Map.of())`.
- `delete()` on active→DELETED: `new ContentPostEvent(uuid, POST_DELETED, ...)`.
- `updateMetadata()` on completion: detect what changed; fire `POST_EDITED`. If visibility changed, also fire `POST_VISIBILITY_CHANGED` with `{oldVisibility, newVisibility}`.
Use `applicationEventPublisher.publishEvent(...)` for each.

- [ ] **Step 5: Update ContentCommandServiceTest**
Update the existing `publishEmitsPostPublishedEventOnFirstPublish` to use the new event type. Add:
- `unpublishEmitsPostUnpublishedEvent`
- `deleteEmitsPostDeletedEvent`
- `updateMetadataEmitsPostEditedEvent`
Each asserts the recording publisher captured the right `ContentPostEventType`.

- [ ] **Step 6: Run** `./mvnw.cmd test '-Dspring.profiles.active=test'` → green. `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` → green.

---

## Task 3: Cache Config, Error Codes, L2 CacheManager

**Files:**
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Create: `backend/src/main/java/com/platform/cache/feed/config/FeedCacheProperties.java`
- Create: `backend/src/main/java/com/platform/cache/feed/config/FeedL2CacheConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/platform/cache/feed/config/FeedCachePropertiesTest.java`

**Interfaces:**
- Produces: `FeedCacheProperties` (TTLs, sizes, jitter); `FeedL2CacheConfig` with a separate `feedL2CacheManager` CacheManager bean.

- [ ] **Step 1: Add CACHE_* error codes**
```java
CACHE_FEED_UNAVAILABLE,
CACHE_FRAGMENT_INCOMPLETE,
CACHE_INVALID_CURSOR,
CACHE_INVALID_PAGE_SIZE
```

- [ ] **Step 2: Add `platform.cache.feed.*` to application.yml**
```yaml
  cache:
    caffeine: ...  # existing
    feed:
      l2:
        head-ttl-seconds: 5
        cursor-ttl-seconds: 60
        max-size: 10000
      l1:
        head-ttl-seconds: 4
        cursor-ttl-seconds: 120
      l0:
        ttl-seconds: 300
      jitter-ratio: 0.3
      reconciliation-interval-ms: 30000
      single-flight-lock-wait-ms: 200
      single-flight-lock-ttl-seconds: 10
```

- [ ] **Step 3: Create `FeedCacheProperties`**
```java
@ConfigurationProperties(prefix = "platform.cache.feed")
public record FeedCacheProperties(L2 l2, L1 l1, L0 l0, double jitterRatio,
                                   long reconciliationIntervalMs, int singleFlightLockWaitMs,
                                   int singleFlightLockTtlSeconds) {
    public record L2(int headTtlSeconds, int cursorTtlSeconds, int maxSize) {}
    public record L1(int headTtlSeconds, int cursorTtlSeconds) {}
    public record L0(int ttlSeconds) {}
}
```

- [ ] **Step 4: Create `FeedL2CacheConfig` (separate CacheManager)**
```java
@Configuration
public class FeedL2CacheConfig {
    @Bean("feedL2CacheManager")
    public CacheManager feedL2CacheManager(FeedCacheProperties props) {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        // head caches: short TTL
        Caffeine<Object,Object> headSpec = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.l2().headTtlSeconds()))
                .maximumSize(props.l2().maxSize());
        // cursor caches: longer TTL
        Caffeine<Object,Object> cursorSpec = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.l2().cursorTtlSeconds()))
                .maximumSize(props.l2().maxSize());
        mgr.registerCustomCache("feed-public-head", headSpec.build());
        mgr.registerCustomCache("feed-user-head", headSpec.build());
        mgr.registerCustomCache("feed-public-cursor", cursorSpec.build());
        mgr.registerCustomCache("feed-user-cursor", cursorSpec.build());
        return mgr;
    }
}
```
NOTE: the cache names here are logical (which tier+feed type). The actual keys (page identifiers) are the keys WITHIN each cache. This gives per-tier TTL control.

- [ ] **Step 5: Write `FeedCachePropertiesTest`** — assert default bindings (head 5s, cursor 60s, jitter 0.3, etc.). `@SpringBootTest @ActiveProfiles("test")` + `@MockBean UserRepository`.

- [ ] **Step 6: Run** unit + integration → green.

---

## Task 4: Feed Source Query (V4 Indexes + Content keyset)

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__feed_indexes.sql`
- Create: `backend/src/main/java/com/platform/cache/feed/domain/Cursor.java`
- Create: `backend/src/main/java/com/platform/cache/feed/domain/FeedPage.java`
- Create: `backend/src/main/java/com/platform/cache/feed/domain/FeedType.java`
- Create: `backend/src/main/java/com/platform/cache/feed/domain/FeedSourceQuery.java`
- Create: `backend/src/main/java/com/platform/content/application/ContentFeedSourceQuery.java` (implements FeedSourceQuery, `@Repository @Profile("!test")`)
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java` (add keyset methods)
- Modify: `backend/src/main/java/com/platform/content/repository/ContentPostRepository.java` (add keyset methods)
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`
- Test: `backend/src/test/java/com/platform/content/infrastructure/persistence/ContentFeedSourceQueryIntegrationTest.java`

**Interfaces:**
- Produces: `FeedSourceQuery` interface with `findPublicFeedAfter(Cursor, int) → FeedPage` and `findUserFeedAfter(Long, Cursor, int) → FeedPage`. `FeedPage = {List<Long> ids, boolean hasMore, Cursor nextCursor}`.

- [ ] **Step 1: V4 migration**
```sql
-- V4__feed_indexes.sql — composite indexes for keyset feed pagination
ALTER TABLE content_post ADD KEY idx_content_post_pub_feed    (status, visibility, published_at DESC, id DESC);
ALTER TABLE content_post ADD KEY idx_content_post_author_feed (author_id, created_at DESC, id DESC);
```

- [ ] **Step 2: Domain types**
```java
// Cursor — immutable pagination anchor
public record Cursor(LocalDateTime timestamp, Long id) {
    public static Cursor head() { return null; }  // null cursor = head page
}

// FeedPage — the skeleton result
public record FeedPage(List<Long> ids, boolean hasMore, Cursor nextCursor) {}

// FeedType
public enum FeedType { PUBLIC, USER }
```

- [ ] **Step 3: FeedSourceQuery interface**
```java
public interface FeedSourceQuery {
    FeedPage findPublicFeedHead(int size);
    FeedPage findPublicFeedAfter(Cursor cursor, int size);
    FeedPage findUserFeedHead(Long userId, int size);
    FeedPage findUserFeedAfter(Long userId, Cursor cursor, int size);
}
```

- [ ] **Step 4: Content keyset mapper methods**
Add to `ContentPostMapper`:
```java
@Select("""
    SELECT id FROM content_post
    WHERE status='PUBLISHED' AND visibility='PUBLIC'
    ORDER BY published_at DESC, id DESC LIMIT #{limit}
    """)
List<Long> findPublicFeedHead(@Param("limit") int limit);

@Select("""
    SELECT id FROM content_post
    WHERE status='PUBLISHED' AND visibility='PUBLIC'
      AND (published_at < #{at} OR (published_at = #{at} AND id < #{id}))
    ORDER BY published_at DESC, id DESC LIMIT #{limit}
    """)
List<Long> findPublicFeedAfter(@Param("at") LocalDateTime at, @Param("id") Long id, @Param("limit") int limit);

// Similarly for user feed (author_id, created_at):
@Select("... WHERE author_id=#{userId} AND status<>'DELETED' ORDER BY created_at DESC, id DESC LIMIT #{limit}")
List<Long> findUserFeedHead(@Param("userId") Long userId, @Param("limit") int limit);

@Select("... WHERE author_id=#{userId} AND status<>'DELETED' AND (created_at < #{at} OR (created_at = #{at} AND id < #{id})) ORDER BY created_at DESC, id DESC LIMIT #{limit}")
List<Long> findUserFeedAfter(@Param("userId") Long userId, @Param("at") LocalDateTime at, @Param("id") Long id, @Param("limit") int limit);
```
Query with `limit = size + 1` (to determine hasMore from the extra row).

- [ ] **Step 5: ContentFeedSourceQuery implementation**
Implements `FeedSourceQuery`. Calls the mapper; builds `FeedPage` from the id list (if list.size > size → hasMore=true, drop the extra; nextCursor from the last id's `(published_at/created_at, id)` — need to fetch that timestamp... or return ids only and let the caller build cursor from content metadata). SIMPLER: `FeedPage` carries the `Cursor` built from the last item — but we only have the id, not its timestamp. Options: (a) `SELECT id, published_at` instead of `SELECT id`; (b) re-derive cursor from the id alone (if ids are snowflake/time-based, the id encodes time — but not reliably for cursor). **Decision: return `(id, timestamp)` pairs from the mapper** so the cursor is complete.

Update mapper to `SELECT id, published_at FROM ...` (or `created_at` for user feed) and return a `List<long[]>` or a lightweight row record. Build `FeedPage.nextCursor` from the last pair.

- [ ] **Step 6: Integration test**
`ContentFeedSourceQueryIntegrationTest` (`@SpringBootTest @ActiveProfiles("integration")`):
- Create 3 published posts; assert `findPublicFeedHead(2)` returns 2 ids (newest first) + hasMore=true + nextCursor correct.
- `findPublicFeedAfter(nextCursor, 2)` returns the 3rd + hasMore=false.
- User feed includes drafts.
- V4 indexes applied (Flyway log).

- [ ] **Step 7: Run** unit + integration → green. Confirm Flyway V4 applied.

---

## Task 5: L0 Fragment Store

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/domain/PostFragment.java`
- Create: `backend/src/main/java/com/platform/cache/feed/infrastructure/redis/FeedRedisKeys.java`
- Create: `backend/src/main/java/com/platform/cache/feed/infrastructure/redis/FragmentStore.java`
- Test: `backend/src/test/java/com/platform/cache/feed/infrastructure/redis/FragmentStoreIntegrationTest.java`

**Interfaces:**
- Produces: `FragmentStore` with `Optional<PostFragment> get(Long postId)`, `Map<Long, PostFragment> multiGet(List<Long> postIds)`, `void put(PostFragment fragment)`, `void putTombstone(Long postId)`, `void delete(Long postId)`.

- [ ] **Step 1: Key builders**
```java
public final class FeedRedisKeys {
    public static String fragment(Long postId) { return "frag:post:" + postId; }
    public static String skeleton(String feedKey) { return "skel:" + feedKey; }
    public static String nullSentinel(String feedKey) { return "null:" + feedKey; }
    public static String lock(String key) { return "lock:" + key; }
    public static String pendingSet() { return "feed:reconcile:pending"; }
    // page key builders:
    public static String publicHead(int size) { return "feed:public:head:sz" + size; }
    public static String publicAfter(LocalDateTime at, Long id, int size) { return "feed:public:after:" + at + ":" + id + ":sz" + size; }
    public static String userHead(Long uid, int size) { return "feed:user:" + uid + ":head:sz" + size; }
    public static String userAfter(Long uid, LocalDateTime at, Long id, int size) { return "feed:user:" + uid + ":after:" + at + ":" + id + ":sz" + size; }
}
```

- [ ] **Step 2: PostFragment record**
```java
public record PostFragment(Long postId, Long authorId, String authorName, String cover,
                           String title, String summary, LocalDateTime publishedAt,
                           long likeCount, long favCount, long viewCount,
                           long commentCount, long shareCount) {
    public boolean isTombstone() { return false; }
    public static PostFragment tombstone(Long postId) { /* a sentinel record */ }
}
```

- [ ] **Step 3: FragmentStore (`@Repository @Profile("!test")`)**
- `get(postId)`: `template.opsForValue().get(FeedRedisKeys.fragment(postId))` → deserialize JSON → `PostFragment`. Check for tombstone marker (`{"deleted":true}`) → return tombstone.
- `multiGet(postIds)`: pipeline or `template.opsForValue().multiGet(keys)` → deserialize each. Missing → not in map.
- `put(fragment)`: serialize to JSON, `SET frag:post:{pid} json EX ttl` (ttl from FeedCacheProperties.l0.ttlSeconds + jitter).
- `putTombstone(postId)`: `SET frag:post:{pid} {"deleted":true} EX 30` (short TTL).
- `delete(postId)`: `DEL frag:post:{pid}`.

- [ ] **Step 4: Integration test**
`FragmentStoreIntegrationTest` (`@SpringBootTest @ActiveProfiles("integration")`):
- `put` + `get` round-trip (all fields preserved).
- `multiGet` returns present + omits missing.
- `putTombstone` + `get` → tombstone detected.
- `delete` → get returns empty.
- TTL + jitter applied (verify with TTL check).

- [ ] **Step 5: Run** unit + integration → green.

---

## Task 6: L1 Page Skeleton Store

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/infrastructure/redis/SkeletonStore.java`
- Test: `backend/src/test/java/com/platform/cache/feed/infrastructure/redis/SkeletonStoreIntegrationTest.java`

**Interfaces:**
- Produces: `SkeletonStore` with `Optional<FeedPage> get(String pageKey)`, `void put(String pageKey, FeedPage page, int ttlSeconds)`, `void delete(String pageKey)`, `boolean isNullSentinel(String pageKey)`.

- [ ] **Step 1: SkeletonStore implementation**
- `get(pageKey)`: read `skel:{pageKey}` → deserialize JSON → `FeedPage`. If `null:{pageKey}` exists → NULL sentinel.
- `put`: serialize `FeedPage` (ids + hasMore + nextCursor) to JSON, `SET skel:{pageKey} json EX ttl+jitter`.
- `putNullSentinel`: `SET null:{pageKey} "1" EX 30`.
- `delete`: `DEL skel:{pageKey} null:{pageKey}`.

- [ ] **Step 2: Integration test**
- put + get round-trip (ids + hasMore + cursor preserved).
- NULL sentinel detected.
- delete clears both skeleton + sentinel.
- TTL applied.

- [ ] **Step 3: Run** → green.

---

## Task 7: L2 Local Cache + Three-Tier Read Orchestration

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/application/FeedReadService.java`
- Create: `backend/src/main/java/com/platform/cache/feed/dto/FeedItemResponse.java`, `FeedPageResponse.java`
- Test: `backend/src/test/java/com/platform/cache/feed/application/FeedReadServiceTest.java`

**Interfaces:**
- Consumes: `FragmentStore`, `SkeletonStore`, `FeedSourceQuery`, `FeedCacheProperties`, `@Qualifier("feedL2CacheManager") CacheManager`.
- Produces: `FeedReadService.readPublicFeed(Cursor cursor, int size, Long requesterIdOrNull) → FeedPageResponse` and `readUserFeed(Long userId, Cursor cursor, int size) → FeedPageResponse`.

- [ ] **Step 1: DTOs**
```java
public record FeedItemResponse(Long postId, Long authorId, String authorName, String cover,
                               String title, String summary, LocalDateTime publishedAt,
                               long likeCount, long favCount, long viewCount,
                               long commentCount, long shareCount,
                               Boolean likedByMe, Boolean favedByMe) {}  // likedByMe/favedByMe nullable (anonymous)

public record FeedPageResponse(List<FeedItemResponse> items, boolean hasMore, Cursor nextCursor) {}
```

- [ ] **Step 2: FeedReadService (`@Service @Profile("!test")`)**
The core read orchestrator. Pseudocode for `readPublicFeed`:
```java
String pageKey = (cursor == null) ? publicHead(size) : publicAfter(cursor, size);
int ttl = (cursor == null) ? l1.headTtlSeconds : l1.cursorTtlSeconds;
String cacheName = (cursor == null) ? "feed-public-head" : "feed-public-cursor";

// 1. L2
FeedPageResponse cached = l2Cache.get(cacheName, pageKey);
if (cached != null) return overlay(cached, requesterIdOrNull);

// 2. L1
Optional<FeedPage> skeleton = skeletonStore.get(pageKey);
FeedPage page;
if (skeleton.isPresent()) {
    page = skeleton.get();
} else {
    // 3. Backfill from source (single-flight in Task 8)
    page = sourceQuery.findPublicFeedAfter(cursor, size);  // or head
    if (page.ids().isEmpty()) { skeletonStore.putNullSentinel(pageKey); return empty; }
    skeletonStore.put(pageKey, page, ttl + jitter);
}

// 4. Assemble from L0
List<PostFragment> fragments = fragmentStore.multiGet(page.ids());
if (anyMissing(fragments) || anyTombstone(fragments)) {
    // tombstone → rebuild page (C3 fix)
    if (anyTombstone(fragments)) {
        page = sourceQuery.findPublicFeedAfter(cursor, size);
        skeletonStore.put(pageKey, page, ttl + jitter);
        fragments = fragmentStore.multiGet(page.ids());
    }
    // still missing → batch backfill
    batchBackfillMissing(fragments, page.ids());
    fragments = fragmentStore.multiGet(page.ids());
}

// 5. Assemble response
FeedPageResponse response = assemble(page, fragments);
// 6. Write L2
l2Cache.put(cacheName, pageKey, response);
// 7. Overlay (Task 9 — placeholder pass-through for now)
return overlay(response, requesterIdOrNull);
```
The `batchBackfillMissing` method: for missing postIds, batch-read from `ContentQueryService` (metadata) + `CounterReadService.getArticleCounters` (counts) + `UserQueryService` (authorName) → build `PostFragment` → `fragmentStore.put` each. Report the approach.

The `overlay` method: stub for now (return response unchanged); implemented in Task 9.

- [ ] **Step 3: FeedReadServiceTest (unit, fakes)**
Fake FragmentStore + SkeletonStore + FeedSourceQuery + a simple in-memory L2 map. Cover:
- L2 hit → direct return (no source/skeleton calls).
- L2 miss, L1 hit → assemble from L0 fragments → return.
- L2 + L1 miss → source backfill → L1 written → assemble.
- Empty source → NULL sentinel.
- Tombstone → page rebuilt from source.
- Missing fragments → batch backfill → assembled.

- [ ] **Step 4: Run** unit → green.

---

## Task 8: Single-Flight (Local + Distributed Lock)

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/application/FeedSingleFlight.java`
- Modify: `backend/src/main/java/com/platform/cache/feed/application/FeedReadService.java` (wire single-flight around source backfill)
- Test: `backend/src/test/java/com/platform/cache/feed/application/FeedSingleFlightTest.java`

**Interfaces:**
- Produces: `FeedSingleFlight` with `<T> T executeWithLock(String key, Supplier<T> supplier)` — local single-flight (ConcurrentHashMap<String,CompletableFuture>) + distributed Redis lock (SETNX with TTL, poll-wait ≤200ms, fallback self-build on timeout).

- [ ] **Step 1: Implement `FeedSingleFlight`**
```java
@Component @Profile("!test")
public class FeedSingleFlight {
    private final ConcurrentHashMap<String, CompletableFuture<Object>> local = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final FeedCacheProperties props;

    @SuppressWarnings("unchecked")
    public <T> T executeWithLock(String key, Supplier<T> supplier) {
        // 1. Local single-flight: dedup within this JVM
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existing = local.putIfAbsent(key, future);
        if (existing != null) {
            try { return (T) existing.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { /* fall through to self-build */ }
        }
        try {
            // 2. Distributed lock: dedup across JVMs
            T result = withDistributedLock(key, supplier);
            future.complete(result);
            return result;
        } finally {
            local.remove(key);
        }
    }

    private <T> T withDistributedLock(String key, Supplier<T> supplier) {
        String lockKey = FeedRedisKeys.lock(key);
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + props.singleFlightLockWaitMs();
        // Try to acquire; poll until wait deadline
        while (System.currentTimeMillis() < deadline) {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(props.singleFlightLockTtlSeconds()));
            if (Boolean.TRUE.equals(acquired)) {
                try { return supplier.get(); }
                finally { releaseLock(lockKey, token); }
            }
            // Not acquired — wait briefly (someone else is building)
            sleep(20);
        }
        // Timeout — fall back to self-build (bounded herd: at most the lock-TTL window)
        return supplier.get();
    }
    // releaseLock: Lua compare-and-delete (only delete if token matches)
}
```

- [ ] **Step 2: Wire into FeedReadService**
Wrap the source-backfill step (step 3 in readPublicFeed) with `singleFlight.executeWithLock(pageKey, () -> sourceQuery.findPublicFeedAfter(cursor, size))`.

- [ ] **Step 3: Test**
- Local single-flight: two concurrent calls with same key → supplier called once.
- Different keys → supplier called twice.
- Distributed lock: (mock Redis) lock acquired → supplier runs; lock contention → poll then acquire after release.
- Timeout fallback → self-build (supplier runs even without lock).

- [ ] **Step 4: Run** → green.

---

## Task 9: Personalization Overlay

**Files:**
- Modify: `backend/src/main/java/com/platform/cache/feed/application/FeedReadService.java`
- Create: `backend/src/main/java/com/platform/cache/feed/application/FeedOverlayService.java`
- Test: `backend/src/test/java/com/platform/cache/feed/application/FeedOverlayServiceTest.java`

**Interfaces:**
- Produces: `FeedOverlayService.overlay(FeedPageResponse page, long userId) → FeedPageResponse` — adds `likedByMe`/`favedByMe` per item via `CounterReadService.hasActedBatch`.

- [ ] **Step 1: FeedOverlayService (`@Service @Profile("!test")`)**
```java
public FeedPageResponse overlay(FeedPageResponse page, long userId) {
    List<Long> postIds = page.items().stream().map(FeedItemResponse::postId).toList();
    if (postIds.isEmpty()) return page;
    Map<CounterMetric, Map<Long, Boolean>> acted = counterReadService.hasActedBatch(
            userId, CounterEntityType.ARTICLE, postIds, List.of(CounterMetric.LIKE, CounterMetric.FAV));
    Map<Long, Boolean> liked = acted.getOrDefault(CounterMetric.LIKE, Map.of());
    Map<Long, Boolean> faved = acted.getOrDefault(CounterMetric.FAV, Map.of());
    List<FeedItemResponse> withOverlay = page.items().stream()
            .map(item -> new FeedItemResponse(item.postId(), ..., item.likeCount(), ...,
                    liked.getOrDefault(item.postId(), false),
                    faved.getOrDefault(item.postId(), false)))
            .toList();
    return new FeedPageResponse(withOverlay, page.hasMore(), page.nextCursor());
}
```

- [ ] **Step 2: Wire into FeedReadService**
Replace the stub `overlay(response, requesterIdOrNull)`: if `requesterIdOrNull != null` → `overlayService.overlay(response, userId)`; else return as-is (anonymous, `likedByMe=null`).

- [ ] **Step 3: Test**
- Overlay adds likedByMe/favedByMe based on hasActedBatch result.
- Empty page → no batch call.
- Anonymous (null userId) → no overlay (likedByMe stays null).

- [ ] **Step 4: Run** → green.

---

## Task 10: Invalidation Consumer + Delayed Double-Delete

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/event/FeedInvalidationConsumer.java`
- Test: `backend/src/test/java/com/platform/cache/feed/event/FeedInvalidationConsumerTest.java`

**Interfaces:**
- Consumes: `content-events` Kafka topic; SkeletonStore + FragmentStore.
- Produces: eventType-driven invalidation (delete head / delete fragment / put tombstone) + delayed double-delete.

- [ ] **Step 1: FeedInvalidationConsumer (`@Component @Profile("!test & !integration")`)**
```java
@KafkaListener(topics = "${platform.content.kafka.events-topic}",
               groupId = "${platform.cache.feed.consumer-group:cache-feed-invalidation}",
               containerFactory = "manualAckKafkaListenerContainerFactory")
public void onContentEvent(String value, Acknowledgment ack) {
    JsonNode node = objectMapper.readTree(value);
    String eventType = node.get("eventType").asText();
    Long postId = node.get("postId").asLong();
    Long authorId = node.has("authorId") ? node.get("authorId").asLong() : null;
    switch (eventType) {
        case "POST_PUBLISHED" -> {
            deletePublicHeadAllSizes();        // delete feed:public:head:sz* (all cached sizes)
            if (authorId != null) deleteUserHeadAllSizes(authorId);
        }
        case "POST_EDITED" -> fragmentStore.delete(postId);
        case "POST_UNPUBLISHED", "POST_DELETED" -> {
            fragmentStore.putTombstone(postId);
            deletePublicHeadAllSizes();
            if (authorId != null) deleteUserHeadAllSizes(authorId);
        }
        case "POST_VISIBILITY_CHANGED" -> {
            // changes has {oldVisibility, newVisibility}
            fragmentStore.delete(postId);  // force rebuild
            deletePublicHeadAllSizes();
        }
    }
    // Delayed double-delete (for L1): schedule a second delete after ~1s
    scheduleDelayedDoubleDelete(eventType, postId, authorId);
    ack.acknowledge();
}
```
The `deletePublicHeadAllSizes()` deletes `skel:feed:public:head:sz*` — since we cache multiple sizes (1–50), use a Redis `KEYS`/`SCAN` (bounded: few sizes) or maintain a set of active head keys. For v1, iterate common sizes or use a `DEL` on the known sizes. Report the approach. The delayed double-delete: an async scheduler (`ScheduledExecutorService`) that re-deletes the same keys after `delayMs` (0.5–1s).

- [ ] **Step 2: Test**
Fake SkeletonStore + FragmentStore + mock KafkaTemplate + mock Acknowledgment.
- `publishedDeletesPublicHeadAndUserHead`
- `editedDeletesFragmentOnly`
- `deletedPutsTombstoneAndDeletesHeads`
- `visibilityChangedDeletesFragmentAndPublicHead`
- delayed double-delete scheduled (verify a second delete call after delay)

- [ ] **Step 3: Run** → green.

---

## Task 11: Reconciliation Scheduler

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/application/FeedReconciliationScheduler.java`
- Test: `backend/src/test/java/com/platform/cache/feed/application/FeedReconciliationSchedulerTest.java`

**Interfaces:**
- Produces: a `@Scheduled` task that refreshes hot page skeletons + hot fragment counts.

- [ ] **Step 1: FeedReconciliationScheduler (`@Component @Profile("!test & !integration")`)**
```java
@Scheduled(fixedRateString = "${platform.cache.feed.reconciliation-interval-ms:30000}")
public void reconcile() {
    // 1. Refresh hot public head skeletons: re-query source, compare with L1, rebuild if drifted
    for (int size : List.of(10, 20)) {  // common sizes
        String key = FeedRedisKeys.publicHead(size);
        FeedPage fresh = sourceQuery.findPublicFeedHead(size);
        Optional<FeedPage> cached = skeletonStore.get(key);
        if (cached.isEmpty() || !cached.get().ids().equals(fresh.ids())) {
            skeletonStore.put(key, fresh, ttl);
        }
    }
    // 2. Refresh hot fragment counts: for each post in the hot head, re-read counter + update fragment
    // (bounded to the head page's posts — not the whole DB)
}
```

- [ ] **Step 2: Test**
Fake SourceQuery + SkeletonStore + FragmentStore. Assert: drifted skeleton is rebuilt; fresh fragment counts written. Light test (the scheduler logic, not the @Scheduled timing).

- [ ] **Step 3: Run** → green.

---

## Task 12: REST Controller + Security + Docs

**Files:**
- Create: `backend/src/main/java/com/platform/cache/feed/controller/FeedController.java`
- Modify: `backend/src/main/java/com/platform/config/SecurityConfig.java`
- Create: `backend/docs/modules/cache.md`
- Modify: `backend/docs/modules/README.md`, `backend/docs/api-draft.md`
- Test: `backend/src/test/java/com/platform/cache/feed/controller/FeedControllerIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/feed/public?cursor=&size=20` (public, optional auth for overlay); `GET /api/feed/me?cursor=&size=20` (authenticated).

- [ ] **Step 1: FeedController (`@RestController @Profile("!test")`)**
```java
@RestController @RequestMapping("/api/feed") @Profile("!test")
public class FeedController {
    // GET /api/feed/public?cursor=...&size=20
    //   cursor is optional (absent = head). Format: "{timestamp},{id}" (URL-encoded).
    //   If authenticated (Bearer token present) → overlay likedByMe/favedByMe.
    //   If anonymous → no overlay (likedByMe=null).
    @GetMapping("/public")
    public ApiResponse<FeedPageResponse> publicFeed(
            @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int size,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) { ... }

    // GET /api/feed/me?cursor=...&size=20  (authenticated)
    @GetMapping("/me")
    public ApiResponse<FeedPageResponse> myFeed(
            @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int size) { ... }
}
```
Parse cursor from the request param (format `"{timestamp},{id}"` or empty for head). Clamp size [1,50].

- [ ] **Step 2: SecurityConfig**
Add `GET /api/feed/public` to `permitAll` (public, anonymous can read; authed gets overlay). `GET /api/feed/me` falls through to `anyRequest().authenticated()`. Verify no conflict with existing matchers (the `/api/feed/` prefix is distinct from `/api/posts/` and `/api/users/`).

- [ ] **Step 3: Integration test**
`FeedControllerIntegrationTest` (`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("integration")`):
- Register a user; create + publish 3 posts.
- Anonymous `GET /api/feed/public?size=2` → 200, 2 items, hasMore=true, likedByMe=null.
- Authenticated `GET /api/feed/public?size=2` → likedByMe/favedByMe present (false initially).
- Like a post (via counter endpoint); re-read feed → likedByMe=true.
- `GET /api/feed/public?cursor=...&size=2` → next page (items 3).
- `GET /api/feed/me` (auth) → user's own posts (incl drafts).
- Publish a new post → `GET /api/feed/public?size=2` head now includes it (after L2 head TTL expiry, or invalidate).

- [ ] **Step 4: Docs**
Create `docs/modules/cache.md` (Chinese, matching counter.md depth): 模块职责、三级缓存架构、分页模型(cursor)、读路径(L2→L1→回源→L0装配→墓碑重建)、写路径(cache-aside双删)、失效机制(eventType表)、个性化overlay、防三大、跨模块依赖、API、待办. Update README + api-draft.

- [ ] **Step 5: Final verification** — `./mvnw.cmd test '-Dspring.profiles.active=test'` + `./mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'` both BUILD SUCCESS.

---

## Self-Review

**1. Spec coverage:**
- cursor pagination (two sort keys) → Task 4 ✓
- L0 fragment store (tombstone, batch backfill) → Task 5 ✓
- L1 skeleton store → Task 6 ✓
- L2 local cache (separate CacheManager) → Task 3 (config) + Task 7 (usage) ✓
- three-tier read path (L2→L1→source→L0→assemble) → Task 7 ✓
- tombstone rebuild (C3) → Task 7 ✓
- single-flight (local + distributed lock + wait policy) → Task 8 ✓
- personalization overlay (hasActedBatch) → Task 9 ✓
- invalidation (eventType dispatch + double-delete) → Task 10 ✓
- reconciliation → Task 11 ✓
- counter hasActedBatch + consumer switch → Task 1 ✓
- content unified events → Task 2 ✓
- V4 indexes → Task 4 ✓
- REST + security + docs → Task 12 ✓
- Anti-三大 (NULL sentinel, single-flight, TTL jitter) → Tasks 5,6,7,8 ✓
- @Profile discipline → noted in Global Constraints ✓

**2. Placeholder scan:** The `overlay` stub in Task 7 is explicitly a placeholder for Task 9 (wired there) — documented, not a gap. The `batchBackfillMissing` method in Task 7 references ContentQueryService + CounterReadService (both exist) — concrete. No TBD/TODO in code steps.

**3. Type consistency:** `Cursor(timestamp, id)` consistent across Tasks 4–12. `FeedPage(ids, hasMore, nextCursor)` consistent. `PostFragment` fields consistent. `FeedPageResponse(items, hasMore, nextCursor)` consistent. `FeedSourceQuery` methods match usage in Tasks 7, 11. `FragmentStore`/`SkeletonStore` method names consistent.

**4. Open dependency:** Task 1 modifies the counter consumer to switch on eventType BEFORE Task 2 starts sending new event types. This ordering (Task 1 → Task 2) is critical (prevents the I1 bug). Confirmed in the task order.
