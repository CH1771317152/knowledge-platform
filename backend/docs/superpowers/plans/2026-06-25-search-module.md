# Search Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Elasticsearch-backed public article search module with reliable content indexing, cursor pagination, count snapshot ranking, and Feed-style responses.

**Architecture:** Content remains the source of truth and writes `content_outbox` events in the same DB transaction as post lifecycle changes. A relay publishes existing naked JSON `content-events`; Search consumes those events, rebuilds the current document from Content/User/Storage/Counter sources, and writes to Elasticsearch through read/write aliases. Counter flushes create reliable count snapshot work, and Search consumes snapshots as idempotent ES partial updates.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, Flyway, MySQL 8.4, Redis 7.4, Kafka 3.8, Elasticsearch 8.x with IK analyzer, Spring Kafka, Spring Security, Aliyun OSS.

---

## Scope And Execution Notes

- This plan intentionally changes more than one module because Search depends on reliable Content events and Counter snapshots.
- Execute tasks in order. Do not expose the public search endpoint before Task 7, because Tasks 2-6 establish indexing and deletion safety.
- Keep existing `content-events` Kafka value as naked JSON. Do not publish Canal flat messages to `content-events`.
- Do not mutate existing Flyway migrations. Add `V5__search_content_outbox.sql`.
- Unit tests should construct services directly. Real ES integration tests are introduced only after Docker/ES wiring is in place.

## File Structure Map

### Existing Files To Modify

- `backend/pom.xml`: add Elasticsearch client dependency.
- `backend/deploy/docker-compose.yml`: add Elasticsearch service and search Kafka topics.
- `backend/src/main/resources/application.yml`: add `platform.search.*`, `spring.elasticsearch.*`, and counter snapshot topic settings.
- `backend/src/main/resources/db/migration/V5__search_content_outbox.sql`: new migration for `content_post.source_version`, `content_outbox`, and `counter_snapshot_outbox`.
- `backend/src/main/java/com/platform/config/SecurityConfig.java`: permit `GET /api/search/posts`.
- `backend/src/main/java/com/platform/content/domain/ContentPost.java`: add `sourceVersion`.
- `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java`: include `source_version`, outbox queries, scan helpers.
- `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`: map `sourceVersion`, expose scan/outbox helpers through repositories.
- `backend/src/main/java/com/platform/content/application/ContentCommandService.java`: write outbox events transactionally.
- `backend/src/main/java/com/platform/content/event/ContentPostEvent.java`: add `status`, `visibility`, `sourceVersion`.
- `backend/src/main/java/com/platform/content/event/ContentPostEventKafkaPublisher.java`: retire or replace with outbox relay; no direct `@TransactionalEventListener` Kafka send remains.
- `backend/src/main/java/com/platform/counter/application/CounterFlushScheduler.java`: enqueue snapshot work after article flush.
- `backend/src/main/java/com/platform/counter/config/CounterProperties.java`: add snapshot topic/relay settings.

### New Search Files

- `backend/src/main/java/com/platform/search/config/SearchProperties.java`
- `backend/src/main/java/com/platform/search/config/ElasticsearchConfig.java`
- `backend/src/main/resources/elasticsearch/post-index-mapping.json`
- `backend/src/main/java/com/platform/search/domain/SearchPostDocument.java`
- `backend/src/main/java/com/platform/search/domain/SearchCursor.java`
- `backend/src/main/java/com/platform/search/domain/SearchPostQuery.java`
- `backend/src/main/java/com/platform/search/dto/SearchPostPageResponse.java`
- `backend/src/main/java/com/platform/search/application/SearchCursorCodec.java`
- `backend/src/main/java/com/platform/search/application/MarkdownTextExtractor.java`
- `backend/src/main/java/com/platform/search/application/SearchPostDocumentBuilder.java`
- `backend/src/main/java/com/platform/search/application/SearchPostQueryService.java`
- `backend/src/main/java/com/platform/search/application/SearchIndexRebuildService.java`
- `backend/src/main/java/com/platform/search/infrastructure/elasticsearch/SearchPostIndexRepository.java`
- `backend/src/main/java/com/platform/search/infrastructure/kafka/SearchContentEventConsumer.java`
- `backend/src/main/java/com/platform/search/infrastructure/kafka/SearchCounterSnapshotConsumer.java`
- `backend/src/main/java/com/platform/search/controller/SearchController.java`
- `backend/src/main/java/com/platform/search/package-info.java`

### New Content/Counter Support Files

- `backend/src/main/java/com/platform/content/event/ContentOutboxEvent.java`
- `backend/src/main/java/com/platform/content/repository/ContentOutboxRepository.java`
- `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentOutboxMapper.java`
- `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentOutboxRepository.java`
- `backend/src/main/java/com/platform/content/application/ContentOutboxAppender.java`
- `backend/src/main/java/com/platform/content/event/ContentOutboxRelay.java`
- `backend/src/main/java/com/platform/counter/event/CounterSnapshotEvent.java`
- `backend/src/main/java/com/platform/counter/repository/CounterSnapshotOutboxRepository.java`
- `backend/src/main/java/com/platform/counter/infrastructure/persistence/CounterSnapshotOutboxMapper.java`
- `backend/src/main/java/com/platform/counter/infrastructure/persistence/MysqlCounterSnapshotOutboxRepository.java`
- `backend/src/main/java/com/platform/counter/event/CounterSnapshotRelay.java`

---

### Task 1: Elasticsearch Infrastructure And Configuration

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/deploy/docker-compose.yml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/platform/search/config/SearchProperties.java`
- Create: `backend/src/main/java/com/platform/search/config/ElasticsearchConfig.java`
- Create: `backend/src/main/resources/elasticsearch/post-index-mapping.json`
- Create: `backend/src/test/java/com/platform/search/config/SearchPropertiesTest.java`

- [ ] **Step 1: Add failing property binding test**

Create `backend/src/test/java/com/platform/search/config/SearchPropertiesTest.java`:

```java
package com.platform.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SearchPropertiesTest {

    @Test
    void bindsSearchProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("platform.search.enabled", "true");
        source.put("platform.search.index.read-alias", "knowledge-posts-read");
        source.put("platform.search.index.write-alias", "knowledge-posts-write");
        source.put("platform.search.index.body-max-chars", "50000");
        source.put("platform.search.cursor.secret", "test-secret-test-secret");
        source.put("platform.search.cursor.ttl-seconds", "600");
        source.put("platform.search.rank.title-boost", "5.0");
        source.put("platform.search.rank.description-boost", "2.0");
        source.put("platform.search.rank.body-boost", "1.0");
        source.put("platform.search.rank.favorite-weight", "3.0");
        source.put("platform.search.rank.like-weight", "2.0");
        source.put("platform.search.rank.view-weight", "0.2");
        source.put("platform.search.rank.recency-weight", "1.0");

        SearchProperties props = new Binder(source)
                .bind("platform.search", Bindable.of(SearchProperties.class))
                .orElseThrow();

        assertThat(props.enabled()).isTrue();
        assertThat(props.index().readAlias()).isEqualTo("knowledge-posts-read");
        assertThat(props.index().writeAlias()).isEqualTo("knowledge-posts-write");
        assertThat(props.index().bodyMaxChars()).isEqualTo(50_000);
        assertThat(props.cursor().ttl()).isEqualTo(Duration.ofSeconds(600));
        assertThat(props.rank().titleBoost()).isEqualTo(5.0);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cd backend
./mvnw -Dtest=SearchPropertiesTest test
```

Expected: compilation fails because `SearchProperties` does not exist.

- [ ] **Step 3: Add `SearchProperties`**

Create `backend/src/main/java/com/platform/search/config/SearchProperties.java`:

```java
package com.platform.search.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.search")
public record SearchProperties(
        boolean enabled,
        Index index,
        Cursor cursor,
        Rank rank,
        Kafka kafka,
        Rebuild rebuild) {

    public record Index(
            String readAlias,
            String writeAlias,
            String initialIndex,
            int bodyMaxChars,
            int ossReadTimeoutMs,
            int ossMaxConcurrency) {}

    public record Cursor(String secret, Duration ttl) {}

    public record Rank(
            double titleBoost,
            double descriptionBoost,
            double bodyBoost,
            double favoriteWeight,
            double likeWeight,
            double viewWeight,
            double recencyWeight) {}

    public record Kafka(
            String contentConsumerGroup,
            String contentRetryTopic,
            String contentDlqTopic,
            String counterSnapshotTopic,
            String counterConsumerGroup,
            String counterRetryTopic,
            String counterDlqTopic) {}

    public record Rebuild(int scanBatchSize, int bulkBatchSize) {}
}
```

- [ ] **Step 4: Add Elasticsearch config**

Create `backend/src/main/java/com/platform/search/config/ElasticsearchConfig.java`:

```java
package com.platform.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(SearchProperties.class)
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    RestClient elasticsearchRestClient(org.springframework.core.env.Environment env) {
        String uris = env.getProperty("spring.elasticsearch.uris", "http://localhost:9200");
        return RestClient.builder(HttpHost.create(uris)).build();
    }

    @Bean
    ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
```

- [ ] **Step 5: Add dependency**

Modify `backend/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
</dependency>
<dependency>
    <groupId>org.elasticsearch.client</groupId>
    <artifactId>elasticsearch-rest-client</artifactId>
</dependency>
```

- [ ] **Step 6: Add application properties**

Modify `backend/src/main/resources/application.yml`:

```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS:http://localhost:9200}

platform:
  search:
    enabled: ${SEARCH_ENABLED:false}
    index:
      read-alias: ${SEARCH_INDEX_READ_ALIAS:knowledge-posts-read}
      write-alias: ${SEARCH_INDEX_WRITE_ALIAS:knowledge-posts-write}
      initial-index: ${SEARCH_INDEX_INITIAL:knowledge-posts-v1}
      body-max-chars: ${SEARCH_BODY_MAX_CHARS:50000}
      oss-read-timeout-ms: ${SEARCH_OSS_READ_TIMEOUT_MS:3000}
      oss-max-concurrency: ${SEARCH_OSS_MAX_CONCURRENCY:8}
    cursor:
      secret: ${SEARCH_CURSOR_SECRET:change-this-search-cursor-secret}
      ttl-seconds: ${SEARCH_CURSOR_TTL_SECONDS:600}
    rank:
      title-boost: ${SEARCH_RANK_TITLE_BOOST:5.0}
      description-boost: ${SEARCH_RANK_DESCRIPTION_BOOST:2.0}
      body-boost: ${SEARCH_RANK_BODY_BOOST:1.0}
      favorite-weight: ${SEARCH_RANK_FAVORITE_WEIGHT:3.0}
      like-weight: ${SEARCH_RANK_LIKE_WEIGHT:2.0}
      view-weight: ${SEARCH_RANK_VIEW_WEIGHT:0.2}
      recency-weight: ${SEARCH_RANK_RECENCY_WEIGHT:1.0}
    kafka:
      content-consumer-group: ${SEARCH_CONTENT_CONSUMER_GROUP:search-content-indexer-group}
      content-retry-topic: ${SEARCH_CONTENT_RETRY_TOPIC:search-content-retry}
      content-dlq-topic: ${SEARCH_CONTENT_DLQ_TOPIC:search-content-dlq}
      counter-snapshot-topic: ${COUNTER_SNAPSHOT_TOPIC:counter-snapshot-events}
      counter-consumer-group: ${SEARCH_COUNTER_CONSUMER_GROUP:search-counter-snapshot-group}
      counter-retry-topic: ${SEARCH_COUNTER_RETRY_TOPIC:search-counter-retry}
      counter-dlq-topic: ${SEARCH_COUNTER_DLQ_TOPIC:search-counter-dlq}
    rebuild:
      scan-batch-size: ${SEARCH_REBUILD_SCAN_BATCH:500}
      bulk-batch-size: ${SEARCH_REBUILD_BULK_BATCH:200}
```

Also add under `platform.counter.kafka`:

```yaml
snapshot-topic: ${COUNTER_SNAPSHOT_TOPIC:counter-snapshot-events}
snapshot-consumer-group: ${COUNTER_SNAPSHOT_CONSUMER_GROUP:counter-snapshot-relay-group}
```

- [ ] **Step 7: Add ES mapping resource**

Create `backend/src/main/resources/elasticsearch/post-index-mapping.json`:

```json
{
  "settings": {
    "analysis": {
      "analyzer": {
        "ik_index_analyzer": {
          "type": "custom",
          "tokenizer": "ik_max_word"
        },
        "ik_search_analyzer": {
          "type": "custom",
          "tokenizer": "ik_smart"
        }
      }
    }
  },
  "mappings": {
    "_source": {
      "excludes": ["body_text"]
    },
    "properties": {
      "post_id": { "type": "long" },
      "content_type": { "type": "keyword" },
      "status": { "type": "keyword" },
      "visibility": { "type": "keyword" },
      "author_id": { "type": "long" },
      "author_name": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": { "keyword": { "type": "keyword", "ignore_above": 128 } }
      },
      "author_avatar": { "type": "keyword", "ignore_above": 512 },
      "title": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } }
      },
      "description": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer"
      },
      "body_text": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer"
      },
      "cover_object_key": { "type": "keyword", "ignore_above": 512 },
      "tags": {
        "type": "keyword",
        "fields": {
          "text": {
            "type": "text",
            "analyzer": "ik_index_analyzer",
            "search_analyzer": "ik_search_analyzer"
          }
        }
      },
      "tags_json": { "type": "flattened" },
      "publish_time": { "type": "date" },
      "update_time": { "type": "date" },
      "like_count": { "type": "long" },
      "favorite_count": { "type": "long" },
      "view_count": { "type": "long" },
      "comment_count": { "type": "long" },
      "share_count": { "type": "long" },
      "source_version": { "type": "long" },
      "indexed_at": { "type": "date" }
    }
  }
}
```

- [ ] **Step 8: Add Docker service and topics**

Modify `backend/deploy/docker-compose.yml`:

```yaml
  elasticsearch:
    image: elasticsearch:8.15.3
    container_name: knowledge-platform-elasticsearch
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
```

Add to `kafka-init` command:

```bash
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic counter-snapshot-events --partitions 3 --replication-factor 1
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic search-content-retry --partitions 3 --replication-factor 1
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic search-content-dlq --partitions 3 --replication-factor 1
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic search-counter-retry --partitions 3 --replication-factor 1
/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists --topic search-counter-dlq --partitions 3 --replication-factor 1
```

Add volume:

```yaml
  elasticsearch-data:
```

IK analyzer still needs a compatible plugin image before real ES smoke. If the plain image is used temporarily, integration tests that require IK must be disabled through `platform.search.enabled=false`.

- [ ] **Step 9: Run configuration tests**

Run:

```bash
cd backend
./mvnw -Dtest=SearchPropertiesTest test
```

Expected: PASS.

- [ ] **Step 10: Checkpoint commit**

```bash
git add backend/pom.xml backend/deploy/docker-compose.yml backend/src/main/resources/application.yml backend/src/main/resources/elasticsearch/post-index-mapping.json backend/src/main/java/com/platform/search/config backend/src/test/java/com/platform/search/config
git commit -m "feat(search): add elasticsearch configuration"
```

---

### Task 2: Content Outbox Schema And Repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__search_content_outbox.sql`
- Modify: `backend/src/main/java/com/platform/content/domain/ContentPost.java`
- Create: `backend/src/main/java/com/platform/content/event/ContentOutboxEvent.java`
- Create: `backend/src/main/java/com/platform/content/repository/ContentOutboxRepository.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentOutboxMapper.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentOutboxRepository.java`
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java`
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostRow.java`
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`
- Test: `backend/src/test/java/com/platform/content/event/ContentOutboxEventTest.java`

- [ ] **Step 1: Write outbox event serialization test**

Create `backend/src/test/java/com/platform/content/event/ContentOutboxEventTest.java`:

```java
package com.platform.content.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ContentOutboxEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadIsNakedJsonContentEvent() throws Exception {
        ContentOutboxEvent event = ContentOutboxEvent.postLifecycle(
                "evt-1",
                "POST_PUBLISHED",
                101L,
                7L,
                "PUBLISHED",
                "PUBLIC",
                3L,
                LocalDateTime.parse("2026-06-25T12:00:00"));

        JsonNode node = objectMapper.readTree(event.payloadJson());

        assertThat(event.aggregateType()).isEqualTo("POST");
        assertThat(event.aggregateId()).isEqualTo(101L);
        assertThat(event.kafkaKey()).isEqualTo("POST:101");
        assertThat(node.path("eventId").asText()).isEqualTo("evt-1");
        assertThat(node.path("eventType").asText()).isEqualTo("POST_PUBLISHED");
        assertThat(node.path("postId").asLong()).isEqualTo(101L);
        assertThat(node.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(node.path("visibility").asText()).isEqualTo("PUBLIC");
        assertThat(node.path("sourceVersion").asLong()).isEqualTo(3L);
    }
}
```

- [ ] **Step 2: Run the failing test**

```bash
cd backend
./mvnw -Dtest=ContentOutboxEventTest test
```

Expected: compilation fails because `ContentOutboxEvent` does not exist.

- [ ] **Step 3: Add Flyway migration**

Create `backend/src/main/resources/db/migration/V5__search_content_outbox.sql`:

```sql
-- V5__search_content_outbox.sql — reliable content events and search snapshot support

ALTER TABLE content_post
    ADD COLUMN source_version BIGINT NOT NULL DEFAULT 0 AFTER updated_at;

CREATE TABLE content_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_version INT NOT NULL DEFAULT 1,
    payload_json JSON NOT NULL,
    source_version BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME,
    UNIQUE KEY uk_content_outbox_event_id (event_id),
    KEY idx_content_outbox_unpublished (published_at, id),
    KEY idx_content_outbox_aggregate (aggregate_type, aggregate_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE counter_snapshot_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME,
    UNIQUE KEY uk_counter_snapshot_event_id (event_id),
    KEY idx_counter_snapshot_unpublished (published_at, id),
    KEY idx_counter_snapshot_entity (entity_type, entity_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 4: Add content outbox event record**

Create `backend/src/main/java/com/platform/content/event/ContentOutboxEvent.java`:

```java
package com.platform.content.event;

import java.time.LocalDateTime;

public record ContentOutboxEvent(
        Long id,
        String eventId,
        String aggregateType,
        Long aggregateId,
        String eventType,
        int payloadVersion,
        String payloadJson,
        long sourceVersion,
        LocalDateTime occurredAt,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {

    public static ContentOutboxEvent postLifecycle(
            String eventId,
            String eventType,
            Long postId,
            Long authorId,
            String status,
            String visibility,
            long sourceVersion,
            LocalDateTime occurredAt) {
        String payload = """
                {"eventId":"%s","eventType":"%s","postId":%d,"authorId":%d,"status":"%s","visibility":"%s","sourceVersion":%d,"occurredAt":"%s"}\
                """.formatted(eventId, eventType, postId, authorId, status, visibility, sourceVersion, occurredAt);
        return new ContentOutboxEvent(
                null,
                eventId,
                "POST",
                postId,
                eventType,
                1,
                payload,
                sourceVersion,
                occurredAt,
                null,
                null);
    }

    public String kafkaKey() {
        return aggregateType + ":" + aggregateId;
    }
}
```

If JSON escaping becomes necessary for titles or change maps later, replace the formatted string with `ObjectMapper`. This first payload only uses enum names, ids, version, and timestamp.

- [ ] **Step 5: Add repository contract and MyBatis mapper**

Create `backend/src/main/java/com/platform/content/repository/ContentOutboxRepository.java`:

```java
package com.platform.content.repository;

import com.platform.content.event.ContentOutboxEvent;
import java.time.LocalDateTime;
import java.util.List;

public interface ContentOutboxRepository {
    void append(ContentOutboxEvent event);
    List<ContentOutboxEvent> findUnpublished(int limit);
    void markPublished(Long id, LocalDateTime publishedAt);
    long currentHighWatermark();
    List<ContentOutboxEvent> findAfterId(long afterId, int limit);
}
```

Create `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentOutboxMapper.java`:

```java
package com.platform.content.infrastructure.persistence;

import com.platform.content.event.ContentOutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentOutboxMapper {

    @Insert("""
            INSERT INTO content_outbox (event_id, aggregate_type, aggregate_id, event_type,
                payload_version, payload_json, source_version, occurred_at)
            VALUES (#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
                #{payloadVersion}, CAST(#{payloadJson} AS JSON), #{sourceVersion}, #{occurredAt})
            """)
    void insert(ContentOutboxEvent event);

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload_version,
                payload_json, source_version, occurred_at, created_at, published_at
            FROM content_outbox
            WHERE published_at IS NULL
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "aggregate_type", javaType = String.class),
            @Arg(column = "aggregate_id", javaType = Long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "payload_version", javaType = int.class),
            @Arg(column = "payload_json", javaType = String.class),
            @Arg(column = "source_version", javaType = long.class),
            @Arg(column = "occurred_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "published_at", javaType = LocalDateTime.class)
    })
    List<ContentOutboxEvent> findUnpublished(@Param("limit") int limit);

    @Update("UPDATE content_outbox SET published_at = #{publishedAt} WHERE id = #{id} AND published_at IS NULL")
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

    @Select("SELECT COALESCE(MAX(id), 0) FROM content_outbox")
    long currentHighWatermark();

    @Select("""
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload_version,
                payload_json, source_version, occurred_at, created_at, published_at
            FROM content_outbox
            WHERE id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "aggregate_type", javaType = String.class),
            @Arg(column = "aggregate_id", javaType = Long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "payload_version", javaType = int.class),
            @Arg(column = "payload_json", javaType = String.class),
            @Arg(column = "source_version", javaType = long.class),
            @Arg(column = "occurred_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "published_at", javaType = LocalDateTime.class)
    })
    List<ContentOutboxEvent> findAfterId(@Param("afterId") long afterId, @Param("limit") int limit);
}
```

Create `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentOutboxRepository.java`:

```java
package com.platform.content.infrastructure.persistence;

import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class MysqlContentOutboxRepository implements ContentOutboxRepository {

    private final ContentOutboxMapper mapper;

    public MysqlContentOutboxRepository(ContentOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(ContentOutboxEvent event) {
        mapper.insert(event);
    }

    @Override
    public List<ContentOutboxEvent> findUnpublished(int limit) {
        return mapper.findUnpublished(limit);
    }

    @Override
    public void markPublished(Long id, LocalDateTime publishedAt) {
        mapper.markPublished(id, publishedAt);
    }

    @Override
    public long currentHighWatermark() {
        return mapper.currentHighWatermark();
    }

    @Override
    public List<ContentOutboxEvent> findAfterId(long afterId, int limit) {
        return mapper.findAfterId(afterId, limit);
    }
}
```

- [ ] **Step 6: Add source version to content post mapping**

Modify `ContentPost.java`:

```java
public record ContentPost(
        Long id,
        Long authorId,
        String clientRequestId,
        String title,
        String summary,
        String coverObjectKey,
        PostStatus status,
        PostVisibility visibility,
        PublishStage publishStage,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long sourceVersion
) {}
```

Modify every `SELECT` in `ContentPostMapper` that reads `content_post` to include `source_version`, and add:

```java
@Update("UPDATE content_post SET source_version = source_version + 1 WHERE id = #{postId}")
int bumpSourceVersion(@Param("postId") Long postId);
```

Modify `ContentPostRow` to include `sourceVersion` and map it in `toDomain()` / `fromDomain()`.

- [ ] **Step 7: Run content outbox tests**

```bash
cd backend
./mvnw -Dtest=ContentOutboxEventTest test
```

Expected: PASS.

- [ ] **Step 8: Run existing content tests**

```bash
cd backend
./mvnw -Dtest=ContentCommandServiceTest test
```

Expected: failures are acceptable only where tests need updating for `sourceVersion`; no production compilation errors remain.

- [ ] **Step 9: Checkpoint commit**

```bash
git add backend/src/main/resources/db/migration/V5__search_content_outbox.sql backend/src/main/java/com/platform/content backend/src/test/java/com/platform/content/event/ContentOutboxEventTest.java
git commit -m "feat(content): add reliable content outbox schema"
```

---

### Task 3: Transactional Content Event Recording And Relay

**Files:**
- Create: `backend/src/main/java/com/platform/content/application/ContentOutboxAppender.java`
- Create: `backend/src/main/java/com/platform/content/event/ContentOutboxRelay.java`
- Modify: `backend/src/main/java/com/platform/content/application/ContentCommandService.java`
- Modify: `backend/src/main/java/com/platform/content/event/ContentPostEventKafkaPublisher.java`
- Test: `backend/src/test/java/com/platform/content/application/ContentOutboxAppenderTest.java`
- Test: `backend/src/test/java/com/platform/content/event/ContentOutboxRelayTest.java`
- Modify: `backend/src/test/java/com/platform/content/application/ContentCommandServiceTest.java`

- [ ] **Step 1: Add appender test**

Create `backend/src/test/java/com/platform/content/application/ContentOutboxAppenderTest.java`:

```java
package com.platform.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.event.ContentPostEventType;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentOutboxAppenderTest {

    @Test
    void appendsNakedJsonEventWithCurrentPostState() {
        FakeOutboxRepository repo = new FakeOutboxRepository();
        ContentOutboxAppender appender = new ContentOutboxAppender(repo);
        ContentPost post = new ContentPost(
                10L, 2L, "req", "title", "summary", null,
                PostStatus.PUBLISHED, PostVisibility.PUBLIC, PublishStage.PUBLISHED,
                LocalDateTime.parse("2026-06-25T12:00:00"),
                LocalDateTime.parse("2026-06-25T11:00:00"),
                LocalDateTime.parse("2026-06-25T12:00:00"),
                4L);

        appender.append(post, ContentPostEventType.POST_PUBLISHED, LocalDateTime.parse("2026-06-25T12:00:01"));

        assertThat(repo.events).hasSize(1);
        ContentOutboxEvent event = repo.events.get(0);
        assertThat(event.aggregateId()).isEqualTo(10L);
        assertThat(event.eventType()).isEqualTo("POST_PUBLISHED");
        assertThat(event.sourceVersion()).isEqualTo(4L);
        assertThat(event.payloadJson()).contains("\"visibility\":\"PUBLIC\"");
    }

    static class FakeOutboxRepository implements ContentOutboxRepository {
        final List<ContentOutboxEvent> events = new ArrayList<>();
        public void append(ContentOutboxEvent event) { events.add(event); }
        public List<ContentOutboxEvent> findUnpublished(int limit) { return List.of(); }
        public void markPublished(Long id, LocalDateTime publishedAt) {}
        public long currentHighWatermark() { return 0; }
        public List<ContentOutboxEvent> findAfterId(long afterId, int limit) { return List.of(); }
    }
}
```

- [ ] **Step 2: Implement appender**

Create `backend/src/main/java/com/platform/content/application/ContentOutboxAppender.java`:

```java
package com.platform.content.application;

import com.platform.content.domain.ContentPost;
import com.platform.content.event.ContentOutboxEvent;
import com.platform.content.event.ContentPostEventType;
import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ContentOutboxAppender {

    private final ContentOutboxRepository repository;

    public ContentOutboxAppender(ContentOutboxRepository repository) {
        this.repository = repository;
    }

    public void append(ContentPost post, ContentPostEventType eventType, LocalDateTime occurredAt) {
        repository.append(ContentOutboxEvent.postLifecycle(
                UUID.randomUUID().toString(),
                eventType.name(),
                post.id(),
                post.authorId(),
                post.status().name(),
                post.visibility().name(),
                post.sourceVersion(),
                occurredAt));
    }
}
```

For unit tests that construct `ContentCommandService`, create an overload or constructor accepting a fake `ContentOutboxAppender` without Spring profile involvement.

- [ ] **Step 3: Modify `ContentCommandService` to write outbox**

Replace `ApplicationEventPublisher applicationEventPublisher` field with `ContentOutboxAppender outboxAppender`.

For each real lifecycle mutation:

```java
repository.updateStatusAndStage(postId, PostStatus.PUBLISHED, PublishStage.PUBLISHED, publishedAt);
repository.bumpSourceVersion(postId);
ContentPost after = refetch(postId);
outboxAppender.append(after, ContentPostEventType.POST_PUBLISHED, LocalDateTime.now());
```

Apply the same pattern for:

- `POST_EDITED` after `updateMetadata` when metadata changes for a confirmed/published post.
- `POST_VISIBILITY_CHANGED` after visibility changes.
- `POST_UNPUBLISHED` after published-to-draft transition.
- `POST_DELETED` after soft delete.

Do not append events on idempotent no-op paths.

- [ ] **Step 4: Retire direct Kafka publisher**

Replace `ContentPostEventKafkaPublisher` body with a deprecated no-op bean, or delete the bean after all references are gone. The final runtime publisher must be `ContentOutboxRelay`, not `@TransactionalEventListener`.

The file can become:

```java
package com.platform.content.event;

/**
 * Retained temporarily so old references remain searchable. Content events are now emitted through
 * content_outbox + ContentOutboxRelay, not through TransactionalEventListener.
 */
@Deprecated
public final class ContentPostEventKafkaPublisher {
    private ContentPostEventKafkaPublisher() {}
}
```

- [ ] **Step 5: Add relay test**

Create `backend/src/test/java/com/platform/content/event/ContentOutboxRelayTest.java`:

```java
package com.platform.content.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class ContentOutboxRelayTest {

    @Test
    void publishesUnpublishedEventsAndMarksPublished() {
        ContentOutboxRepository repo = mock(ContentOutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ContentOutboxEvent event = new ContentOutboxEvent(
                1L, "evt-1", "POST", 99L, "POST_PUBLISHED", 1,
                "{\"eventId\":\"evt-1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":99}",
                1L, LocalDateTime.now(), LocalDateTime.now(), null);
        when(repo.findUnpublished(100)).thenReturn(List.of(event));

        ContentOutboxRelay relay = new ContentOutboxRelay(repo, kafka, "content-events", 100);
        relay.flushOnce();

        verify(kafka).send("content-events", "POST:99", event.payloadJson());
        verify(repo).markPublished(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }
}
```

- [ ] **Step 6: Implement relay**

Create `backend/src/main/java/com/platform/content/event/ContentOutboxRelay.java`:

```java
package com.platform.content.event;

import com.platform.content.repository.ContentOutboxRepository;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !integration")
public class ContentOutboxRelay {

    private final ContentOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public ContentOutboxRelay(
            ContentOutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${platform.content.kafka.events-topic}") String topic,
            @Value("${platform.content.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.content.outbox.relay-interval-ms:500}")
    public void flushOnce() {
        for (ContentOutboxEvent event : repository.findUnpublished(batchSize)) {
            kafkaTemplate.send(topic, event.kafkaKey(), event.payloadJson());
            repository.markPublished(event.id(), LocalDateTime.now());
        }
    }
}
```

- [ ] **Step 7: Run tests**

```bash
cd backend
./mvnw -Dtest=ContentOutboxAppenderTest,ContentOutboxRelayTest,ContentCommandServiceTest test
```

Expected: PASS after updating `ContentCommandServiceTest` fakes to assert outbox append rather than Spring event publication.

- [ ] **Step 8: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/content backend/src/test/java/com/platform/content
git commit -m "feat(content): publish lifecycle events through outbox"
```

---

### Task 4: Search Domain, Cursor, And Markdown Extraction

**Files:**
- Create: `backend/src/main/java/com/platform/search/domain/SearchPostDocument.java`
- Create: `backend/src/main/java/com/platform/search/domain/SearchCursor.java`
- Create: `backend/src/main/java/com/platform/search/domain/SearchPostQuery.java`
- Create: `backend/src/main/java/com/platform/search/dto/SearchPostPageResponse.java`
- Create: `backend/src/main/java/com/platform/search/application/SearchCursorCodec.java`
- Create: `backend/src/main/java/com/platform/search/application/MarkdownTextExtractor.java`
- Test: `backend/src/test/java/com/platform/search/application/SearchCursorCodecTest.java`
- Test: `backend/src/test/java/com/platform/search/application/MarkdownTextExtractorTest.java`

- [ ] **Step 1: Add cursor codec tests**

Create `backend/src/test/java/com/platform/search/application/SearchCursorCodecTest.java`:

```java
package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.search.domain.SearchCursor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchCursorCodecTest {

    @Test
    void encodesAndDecodesSignedCursor() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        SearchCursor cursor = new SearchCursor(
                "hash-1",
                Instant.parse("2026-06-25T12:00:00Z"),
                Instant.parse("2026-06-25T12:10:00Z"),
                List.of(1, 9.3d, 4.2d, "2026-06-25T11:00:00Z", 100L));

        String token = codec.encode(cursor);
        SearchCursor decoded = codec.decode(token, "hash-1");

        assertThat(decoded.queryHash()).isEqualTo("hash-1");
        assertThat(decoded.sortValues()).containsExactly(1, 9.3d, 4.2d, "2026-06-25T11:00:00Z", 100);
    }

    @Test
    void rejectsCursorForDifferentQueryHash() {
        SearchCursorCodec codec = new SearchCursorCodec("cursor-secret-for-tests", 600);
        SearchCursor cursor = new SearchCursor(
                "hash-1", Instant.now(), Instant.now().plusSeconds(600), List.of(1, 2, 3));

        String token = codec.encode(cursor);

        assertThatThrownBy(() -> codec.decode(token, "hash-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor query mismatch");
    }
}
```

- [ ] **Step 2: Add markdown extraction test**

Create `backend/src/test/java/com/platform/search/application/MarkdownTextExtractorTest.java`:

```java
package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTextExtractorTest {

    @Test
    void stripsMarkdownNoiseAndTruncates() {
        MarkdownTextExtractor extractor = new MarkdownTextExtractor(20);

        String text = extractor.extract("# 标题\n\n![图](oss://x)\n[链接](https://example.com)\n`code`\n正文内容很多很多");

        assertThat(text).doesNotContain("![");
        assertThat(text).doesNotContain("](");
        assertThat(text.length()).isLessThanOrEqualTo(20);
    }
}
```

- [ ] **Step 3: Implement domain records**

Create `SearchPostDocument.java`:

```java
package com.platform.search.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SearchPostDocument(
        Long postId,
        String contentType,
        String status,
        String visibility,
        Long authorId,
        String authorName,
        String authorAvatar,
        String title,
        String description,
        String bodyText,
        String coverObjectKey,
        List<String> tags,
        Map<String, Object> tagsJson,
        Instant publishTime,
        Instant updateTime,
        long likeCount,
        long favoriteCount,
        long viewCount,
        long commentCount,
        long shareCount,
        long sourceVersion,
        Instant indexedAt) {}
```

Create `SearchCursor.java`:

```java
package com.platform.search.domain;

import java.time.Instant;
import java.util.List;

public record SearchCursor(
        String queryHash,
        Instant rankNow,
        Instant expiresAt,
        List<Object> sortValues) {}
```

Create `SearchPostQuery.java`:

```java
package com.platform.search.domain;

import java.time.Instant;

public record SearchPostQuery(
        String keyword,
        String tag,
        String contentType,
        int size,
        SearchCursor cursor,
        Instant rankNow,
        String queryHash) {}
```

Create `SearchPostPageResponse.java`:

```java
package com.platform.search.dto;

import com.platform.cache.feed.dto.FeedItemResponse;
import java.util.List;

public record SearchPostPageResponse(
        List<FeedItemResponse> items,
        boolean hasMore,
        String nextCursor) {}
```

- [ ] **Step 4: Implement cursor codec**

Create `SearchCursorCodec.java` with HMAC SHA-256:

```java
package com.platform.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.search.domain.SearchCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SearchCursorCodec {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final String secret;
    private final long ttlSeconds;

    public SearchCursorCodec(String secret, long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    public SearchCursor newCursor(String queryHash, Instant rankNow, List<Object> sortValues) {
        return new SearchCursor(queryHash, rankNow, rankNow.plusSeconds(ttlSeconds), sortValues);
    }

    public String encode(SearchCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            String payload64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            String sig = sign(payload64);
            return payload64 + "." + sig;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to encode cursor", e);
        }
    }

    public SearchCursor decode(String token, String expectedQueryHash) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !sign(parts[0]).equals(parts[1])) {
                throw new IllegalArgumentException("invalid cursor signature");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            SearchCursor cursor = objectMapper.readValue(payload, SearchCursor.class);
            if (!cursor.queryHash().equals(expectedQueryHash)) {
                throw new IllegalArgumentException("cursor query mismatch");
            }
            if (Instant.now().isAfter(cursor.expiresAt())) {
                throw new IllegalArgumentException("cursor expired");
            }
            return cursor;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }

    private String sign(String payload64) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload64.getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 5: Implement markdown extractor**

Create `MarkdownTextExtractor.java`:

```java
package com.platform.search.application;

public class MarkdownTextExtractor {

    private final int maxChars;

    public MarkdownTextExtractor(int maxChars) {
        this.maxChars = maxChars;
    }

    public String extract(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("`{1,3}[^`]*`{1,3}", " ")
                .replaceAll("[>#*_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
```

- [ ] **Step 6: Run unit tests**

```bash
cd backend
./mvnw -Dtest=SearchCursorCodecTest,MarkdownTextExtractorTest test
```

Expected: PASS.

- [ ] **Step 7: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/search/domain backend/src/main/java/com/platform/search/dto backend/src/main/java/com/platform/search/application/SearchCursorCodec.java backend/src/main/java/com/platform/search/application/MarkdownTextExtractor.java backend/src/test/java/com/platform/search/application
git commit -m "feat(search): add cursor and markdown primitives"
```

---

### Task 5: Search Index Repository And Document Builder

**Files:**
- Create: `backend/src/main/java/com/platform/search/infrastructure/elasticsearch/SearchPostIndexRepository.java`
- Create: `backend/src/main/java/com/platform/search/application/SearchPostDocumentBuilder.java`
- Create: `backend/src/test/java/com/platform/search/application/SearchPostDocumentBuilderTest.java`
- Create: `backend/src/test/java/com/platform/search/infrastructure/elasticsearch/SearchPostIndexRepositoryTest.java`

- [ ] **Step 1: Add document builder test**

Create `SearchPostDocumentBuilderTest` with fakes for content, storage, user, and counter:

```java
package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.content.domain.ContentPost;
import com.platform.content.domain.ContentPostBody;
import com.platform.content.domain.PostBodyFormat;
import com.platform.content.domain.PostStatus;
import com.platform.content.domain.PostVisibility;
import com.platform.content.domain.PublishStage;
import com.platform.search.domain.SearchPostDocument;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SearchPostDocumentBuilderTest {

    @Test
    void buildsDocumentOnlyForPublicPublishedPost() {
        ContentPost post = new ContentPost(
                100L, 2L, null, "Java 高并发", "摘要", "cover.jpg",
                PostStatus.PUBLISHED, PostVisibility.PUBLIC, PublishStage.PUBLISHED,
                LocalDateTime.parse("2026-06-25T12:00:00"),
                LocalDateTime.parse("2026-06-25T11:00:00"),
                LocalDateTime.parse("2026-06-25T12:01:00"),
                8L);
        ContentPostBody body = new ContentPostBody(
                100L, PostBodyFormat.MARKDOWN, "bucket", "posts/100/body/v1.md",
                "etag", "sha", 100L, 1, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());

        SearchPostDocumentBuilder builder = SearchPostDocumentBuilder.forTest(
                post,
                body,
                () -> new ByteArrayInputStream("# 标题\n正文内容".getBytes(StandardCharsets.UTF_8)));

        SearchPostDocument doc = builder.build(100L).orElseThrow();

        assertThat(doc.postId()).isEqualTo(100L);
        assertThat(doc.contentType()).isEqualTo("ARTICLE");
        assertThat(doc.bodyText()).contains("正文内容");
        assertThat(doc.sourceVersion()).isEqualTo(8L);
    }
}
```

- [ ] **Step 2: Implement document builder**

Create `SearchPostDocumentBuilder.java`. The production constructor should depend on:

- `ContentPostRepository`
- `ObjectStorageService`
- `CounterReadService`
- user query service or repository that can return public author profile
- `SearchProperties`

Core behavior:

```java
public Optional<SearchPostDocument> build(Long postId) {
    ContentPost post = contentRepository.findPostById(postId).orElse(null);
    if (post == null || post.status() != PostStatus.PUBLISHED || post.visibility() != PostVisibility.PUBLIC) {
        return Optional.empty();
    }
    ContentPostBody body = contentRepository.findBodyByPostId(postId).orElse(null);
    if (body == null || body.bodyObjectKey() == null) {
        return Optional.empty();
    }
    String markdown = readUtf8(objectStorageService.readObject(body.bodyObjectKey()));
    String bodyText = markdownTextExtractor.extract(markdown);
    ArticleCountersResponse counters = counterReadService.getArticleCounters(postId);
    List<String> tags = contentRepository.findTagsByPostId(postId).stream().map(ContentTag::name).toList();
    AuthorSnapshot author = authorSnapshot(post.authorId());
    return Optional.of(new SearchPostDocument(...));
}
```

The implementation must not index a document when OSS body read fails; it should throw a recoverable exception so the event consumer routes to retry/DLQ.

- [ ] **Step 3: Add index repository test with fake client adapter**

Keep unit tests free of real Elasticsearch. `SearchPostIndexRepository` should wrap ES calls, while tests use a fake subclass or adapter to verify:

- `upsert(document)` uses `postId` as ID.
- `delete(postId)` treats missing documents as success.
- `updateCounters(postId, counts)` does partial update and does not create missing documents.

Test names:

```java
void upsertUsesPostIdAsDocumentId()
void deleteMissingDocumentIsSuccess()
void counterUpdateDoesNotCreateDocument()
```

- [ ] **Step 4: Implement index repository**

Create `SearchPostIndexRepository.java` with methods:

```java
public void ensureInitialIndex();
public void upsert(SearchPostDocument document);
public void delete(Long postId);
public void updateCounters(Long postId, CounterSnapshotEvent snapshot);
public SearchResultPage search(SearchPostQuery query);
public void bulkUpsert(List<SearchPostDocument> documents);
public void switchAliases(String newIndex);
```

Use `SearchProperties.index().writeAlias()` for writes and `readAlias()` for searches.

- [ ] **Step 5: Run tests**

```bash
cd backend
./mvnw -Dtest=SearchPostDocumentBuilderTest,SearchPostIndexRepositoryTest test
```

Expected: PASS.

- [ ] **Step 6: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/search/application/SearchPostDocumentBuilder.java backend/src/main/java/com/platform/search/infrastructure/elasticsearch/SearchPostIndexRepository.java backend/src/test/java/com/platform/search
git commit -m "feat(search): build and persist search documents"
```

---

### Task 6: Search Content Event Consumer With Retry And DLQ

**Files:**
- Create: `backend/src/main/java/com/platform/search/infrastructure/kafka/SearchContentEventConsumer.java`
- Create: `backend/src/test/java/com/platform/search/infrastructure/kafka/SearchContentEventConsumerTest.java`

- [ ] **Step 1: Add consumer routing tests**

Create tests covering:

```java
void publishedEventBuildsCurrentDocumentAndUpserts()
void privateCurrentPostDeletesDocument()
void malformedEventRoutesToDlqAndAcks()
void builderFailureRoutesToRetryAndAcks()
```

Core test assertion for stale/private state:

```java
consumer.onContentEvent("{\"eventId\":\"e1\",\"eventType\":\"POST_PUBLISHED\",\"postId\":100}", ack);

assertThat(index.deletedIds()).containsExactly(100L);
assertThat(index.upserts()).isEmpty();
verify(ack).acknowledge();
```

- [ ] **Step 2: Implement consumer**

Create `SearchContentEventConsumer.java`:

```java
package com.platform.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.search.application.SearchPostDocumentBuilder;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !integration")
public class SearchContentEventConsumer {

    private final ObjectMapper objectMapper;
    private final SearchPostDocumentBuilder builder;
    private final SearchPostIndexRepository indexRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SearchProperties properties;

    public SearchContentEventConsumer(ObjectMapper objectMapper,
                                      SearchPostDocumentBuilder builder,
                                      SearchPostIndexRepository indexRepository,
                                      KafkaTemplate<String, String> kafkaTemplate,
                                      SearchProperties properties) {
        this.objectMapper = objectMapper;
        this.builder = builder;
        this.indexRepository = indexRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${platform.content.kafka.events-topic}",
            groupId = "${platform.search.kafka.content-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onContentEvent(String value, Acknowledgment ack) {
        try {
            JsonNode node = objectMapper.readTree(value);
            Long postId = node.path("postId").asLong();
            builder.build(postId).ifPresentOrElse(indexRepository::upsert, () -> indexRepository.delete(postId));
        } catch (IllegalArgumentException badMessage) {
            kafkaTemplate.send(properties.kafka().contentDlqTopic(), value);
        } catch (Exception recoverable) {
            kafkaTemplate.send(properties.kafka().contentRetryTopic(), value);
        } finally {
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 3: Run consumer tests**

```bash
cd backend
./mvnw -Dtest=SearchContentEventConsumerTest test
```

Expected: PASS.

- [ ] **Step 4: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/search/infrastructure/kafka/SearchContentEventConsumer.java backend/src/test/java/com/platform/search/infrastructure/kafka/SearchContentEventConsumerTest.java
git commit -m "feat(search): consume content events into index"
```

---

### Task 7: Search Query Service, Controller, And Security

**Files:**
- Create: `backend/src/main/java/com/platform/search/application/SearchPostQueryService.java`
- Create: `backend/src/main/java/com/platform/search/controller/SearchController.java`
- Modify: `backend/src/main/java/com/platform/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/platform/search/application/SearchPostQueryServiceTest.java`
- Test: `backend/src/test/java/com/platform/search/controller/SearchControllerTest.java`

- [ ] **Step 1: Add query service overlay tests**

Create `SearchPostQueryServiceTest` cases:

```java
void anonymousSearchLeavesUserStateNull()
void authenticatedSearchOverlaysLikedAndFaved()
void invalidCursorIsRejected()
```

Expected anonymous item:

```java
assertThat(item.likedByMe()).isNull();
assertThat(item.favedByMe()).isNull();
```

Expected authenticated item:

```java
assertThat(item.likedByMe()).isTrue();
assertThat(item.favedByMe()).isFalse();
```

- [ ] **Step 2: Implement query service**

Create `SearchPostQueryService.java`:

```java
package com.platform.search.application;

import com.platform.cache.feed.dto.FeedItemResponse;
import com.platform.counter.application.CounterReadService;
import com.platform.counter.domain.CounterEntityType;
import com.platform.counter.domain.CounterMetric;
import com.platform.search.domain.SearchPostQuery;
import com.platform.search.dto.SearchPostPageResponse;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchPostQueryService {

    private final SearchPostIndexRepository indexRepository;
    private final CounterReadService counterReadService;
    private final SearchCursorCodec cursorCodec;

    public SearchPostQueryService(SearchPostIndexRepository indexRepository,
                                  CounterReadService counterReadService,
                                  SearchCursorCodec cursorCodec) {
        this.indexRepository = indexRepository;
        this.counterReadService = counterReadService;
        this.cursorCodec = cursorCodec;
    }

    public SearchPostPageResponse search(String keyword, String tag, String contentType,
                                         String cursorToken, int size, Long requesterIdOrNull) {
        String queryHash = SearchQueryHasher.hash(keyword, tag, contentType, size);
        Instant rankNow = Instant.now();
        var cursor = cursorToken == null || cursorToken.isBlank()
                ? null
                : cursorCodec.decode(cursorToken, queryHash);
        SearchPostQuery query = new SearchPostQuery(keyword, tag, contentType, Math.min(Math.max(size, 1), 50),
                cursor, cursor == null ? rankNow : cursor.rankNow(), queryHash);
        var page = indexRepository.search(query);
        List<FeedItemResponse> items = requesterIdOrNull == null
                ? page.items()
                : overlay(page.items(), requesterIdOrNull);
        String next = page.nextSortValues() == null ? null
                : cursorCodec.encode(cursorCodec.newCursor(queryHash, query.rankNow(), page.nextSortValues()));
        return new SearchPostPageResponse(items, page.hasMore(), next);
    }

    private List<FeedItemResponse> overlay(List<FeedItemResponse> items, Long requesterId) {
        List<Long> ids = items.stream().map(FeedItemResponse::postId).toList();
        Map<CounterMetric, Map<Long, Boolean>> states = counterReadService.hasActedBatch(
                requesterId, CounterEntityType.ARTICLE, ids, List.of(CounterMetric.LIKE, CounterMetric.FAV));
        return items.stream().map(item -> new FeedItemResponse(
                item.postId(), item.authorId(), item.authorName(), item.cover(), item.title(), item.summary(),
                item.publishedAt(), item.likeCount(), item.favCount(), item.viewCount(),
                item.commentCount(), item.shareCount(),
                states.get(CounterMetric.LIKE).getOrDefault(item.postId(), false),
                states.get(CounterMetric.FAV).getOrDefault(item.postId(), false))).toList();
    }
}
```

Add `SearchQueryHasher` as a small package-private class in the same file or separate file:

```java
final class SearchQueryHasher {
    static String hash(String keyword, String tag, String contentType, int size) {
        return Integer.toHexString((String.valueOf(keyword) + "|" + tag + "|" + contentType + "|" + size).hashCode());
    }
}
```

- [ ] **Step 3: Implement controller**

Create `SearchController.java`:

```java
package com.platform.search.controller;

import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.search.application.SearchPostQueryService;
import com.platform.search.dto.SearchPostPageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchController {

    private final SearchPostQueryService service;

    public SearchController(SearchPostQueryService service) {
        this.service = service;
    }

    @GetMapping("/posts")
    public ApiResponse<SearchPostPageResponse> searchPosts(
            @RequestParam String q,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "ARTICLE") String contentType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(q, tag, contentType, cursor, size, optionalRequesterId()));
    }

    private static Long optionalRequesterId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return null;
        }
        return principal.userId();
    }
}
```

- [ ] **Step 4: Permit public search**

Modify `SecurityConfig` after feed/content public matchers and before `anyRequest()`:

```java
.requestMatchers(HttpMethod.GET, "/api/search/posts").permitAll()
```

- [ ] **Step 5: Run tests**

```bash
cd backend
./mvnw -Dtest=SearchPostQueryServiceTest,SearchControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/search/application/SearchPostQueryService.java backend/src/main/java/com/platform/search/controller/SearchController.java backend/src/main/java/com/platform/config/SecurityConfig.java backend/src/test/java/com/platform/search
git commit -m "feat(search): expose public post search"
```

---

### Task 8: Index Rebuild With High-Watermark Catch-Up

**Files:**
- Modify: `backend/src/main/java/com/platform/content/repository/ContentPostRepository.java`
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java`
- Modify: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`
- Create: `backend/src/main/java/com/platform/search/application/SearchIndexRebuildService.java`
- Test: `backend/src/test/java/com/platform/search/application/SearchIndexRebuildServiceTest.java`

- [ ] **Step 1: Add rebuild test**

Create a fake repository test:

```java
package com.platform.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchIndexRebuildServiceTest {

    @Test
    void capturesHighWatermarkScansThenReplaysAfterWatermarkBeforeAliasSwitch() {
        FakeContentOutbox outbox = new FakeContentOutbox(10L);
        FakeIndex index = new FakeIndex();
        SearchIndexRebuildService service = new SearchIndexRebuildService(outbox, index, new FakeScanner());

        service.rebuild("knowledge-posts-v2");

        assertThat(index.steps()).containsExactly(
                "create:knowledge-posts-v2",
                "bulk:scan",
                "replay-after:10",
                "switch:knowledge-posts-v2");
    }
}
```

- [ ] **Step 2: Add scan methods**

Extend `ContentPostRepository`:

```java
List<ContentPost> findPublicPublishedAfterId(Long afterId, int limit);
```

Add mapper:

```java
@Select("""
        SELECT id, author_id, client_request_id, title, summary, cover_object_key,
            status, visibility, publish_stage, published_at, created_at, updated_at, source_version
        FROM content_post
        WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC' AND id > #{afterId}
        ORDER BY id ASC
        LIMIT #{limit}
        """)
@ResultMap("contentPostResult")
List<ContentPostRow> findPublicPublishedAfterId(@Param("afterId") Long afterId, @Param("limit") int limit);
```

- [ ] **Step 3: Implement rebuild service**

Create `SearchIndexRebuildService.java`:

```java
package com.platform.search.application;

import com.platform.content.repository.ContentOutboxRepository;
import com.platform.content.repository.ContentPostRepository;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import java.util.ArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.search", name = "enabled", havingValue = "true")
public class SearchIndexRebuildService {

    private final ContentOutboxRepository outboxRepository;
    private final ContentPostRepository contentRepository;
    private final SearchPostDocumentBuilder builder;
    private final SearchPostIndexRepository indexRepository;
    private final SearchProperties properties;

    public SearchIndexRebuildService(ContentOutboxRepository outboxRepository,
                                     ContentPostRepository contentRepository,
                                     SearchPostDocumentBuilder builder,
                                     SearchPostIndexRepository indexRepository,
                                     SearchProperties properties) {
        this.outboxRepository = outboxRepository;
        this.contentRepository = contentRepository;
        this.builder = builder;
        this.indexRepository = indexRepository;
        this.properties = properties;
    }

    public void rebuild(String newIndex) {
        long highWatermark = outboxRepository.currentHighWatermark();
        indexRepository.createIndex(newIndex);
        Long afterId = 0L;
        while (true) {
            var posts = contentRepository.findPublicPublishedAfterId(afterId, properties.rebuild().scanBatchSize());
            if (posts.isEmpty()) {
                break;
            }
            var docs = new ArrayList<com.platform.search.domain.SearchPostDocument>();
            for (var post : posts) {
                builder.build(post.id()).ifPresent(docs::add);
                afterId = post.id();
            }
            indexRepository.bulkUpsertToIndex(newIndex, docs);
        }
        replayAfter(highWatermark, newIndex);
        indexRepository.switchAliases(newIndex);
    }

    private void replayAfter(long highWatermark, String targetIndex) {
        long afterId = highWatermark;
        while (true) {
            var events = outboxRepository.findAfterId(afterId, properties.rebuild().scanBatchSize());
            if (events.isEmpty()) {
                break;
            }
            for (var event : events) {
                builder.build(event.aggregateId())
                        .ifPresentOrElse(doc -> indexRepository.upsertToIndex(targetIndex, doc),
                                () -> indexRepository.deleteFromIndex(targetIndex, event.aggregateId()));
                afterId = event.id();
            }
        }
    }
}
```

- [ ] **Step 4: Run rebuild tests**

```bash
cd backend
./mvnw -Dtest=SearchIndexRebuildServiceTest test
```

Expected: PASS.

- [ ] **Step 5: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/content backend/src/main/java/com/platform/search/application/SearchIndexRebuildService.java backend/src/test/java/com/platform/search/application/SearchIndexRebuildServiceTest.java
git commit -m "feat(search): add index rebuild with outbox catch-up"
```

---

### Task 9: Counter Snapshot Outbox, Relay, And Search Partial Updates

**Files:**
- Create: `backend/src/main/java/com/platform/counter/event/CounterSnapshotEvent.java`
- Create: `backend/src/main/java/com/platform/counter/repository/CounterSnapshotOutboxRepository.java`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/persistence/CounterSnapshotOutboxMapper.java`
- Create: `backend/src/main/java/com/platform/counter/infrastructure/persistence/MysqlCounterSnapshotOutboxRepository.java`
- Create: `backend/src/main/java/com/platform/counter/event/CounterSnapshotRelay.java`
- Modify: `backend/src/main/java/com/platform/counter/application/CounterFlushScheduler.java`
- Create: `backend/src/main/java/com/platform/search/infrastructure/kafka/SearchCounterSnapshotConsumer.java`
- Test: `backend/src/test/java/com/platform/counter/event/CounterSnapshotRelayTest.java`
- Test: `backend/src/test/java/com/platform/search/infrastructure/kafka/SearchCounterSnapshotConsumerTest.java`

- [ ] **Step 1: Add snapshot event record**

Create `CounterSnapshotEvent.java`:

```java
package com.platform.counter.event;

import com.platform.counter.dto.ArticleCountersResponse;
import java.time.LocalDateTime;

public record CounterSnapshotEvent(
        String eventId,
        String entityType,
        Long entityId,
        long likeCount,
        long favoriteCount,
        long viewCount,
        long commentCount,
        long shareCount,
        LocalDateTime occurredAt) {

    public static CounterSnapshotEvent article(String eventId, ArticleCountersResponse counters, LocalDateTime at) {
        return new CounterSnapshotEvent(
                eventId, "ARTICLE", counters.postId(), counters.like(), counters.fav(),
                counters.view(), counters.comment(), counters.share(), at);
    }

    public String kafkaKey() {
        return entityType + ":" + entityId;
    }
}
```

- [ ] **Step 2: Add relay tests**

`CounterSnapshotRelayTest` should verify:

- unpublished snapshot row sends to `counter-snapshot-events`.
- row is marked `published_at`.
- duplicate relay is harmless because published rows are not selected again.

- [ ] **Step 3: Implement snapshot outbox repository**

Create mapper/repository mirroring Content outbox, using `counter_snapshot_outbox` from V5:

```java
public interface CounterSnapshotOutboxRepository {
    void append(CounterSnapshotEvent event);
    List<CounterSnapshotEventRow> findUnpublished(int limit);
    void markPublished(Long id, LocalDateTime publishedAt);
}
```

Use payload JSON shaped as:

```json
{
  "eventId": "...",
  "entityType": "ARTICLE",
  "entityId": 123,
  "likeCount": 10,
  "favoriteCount": 2,
  "viewCount": 99,
  "commentCount": 0,
  "shareCount": 1,
  "occurredAt": "2026-06-25T12:00:00"
}
```

- [ ] **Step 4: Modify `CounterFlushScheduler`**

Inject `CounterSnapshotOutboxRepository` and `CounterReadService`.

After successful `store.flushOne(etype, eid)`:

```java
if (etype == CounterEntityType.ARTICLE) {
    ArticleCountersResponse counters = counterReadService.getArticleCounters(eid);
    snapshotOutbox.append(CounterSnapshotEvent.article(UUID.randomUUID().toString(), counters, LocalDateTime.now()));
}
```

This must happen after `flushOne`, so the snapshot reflects the flushed CountInt state.

- [ ] **Step 5: Implement `CounterSnapshotRelay`**

Create scheduled relay with:

```java
@Scheduled(fixedDelayString = "${platform.counter.snapshot.relay-interval-ms:500}")
public void flushOnce() {
    for (CounterSnapshotOutboxRow row : repository.findUnpublished(batchSize)) {
        kafkaTemplate.send(properties.kafka().snapshotTopic(), row.kafkaKey(), row.payloadJson());
        repository.markPublished(row.id(), LocalDateTime.now());
    }
}
```

Use `@Profile("!test & !integration")`.

- [ ] **Step 6: Add Search snapshot consumer**

Create `SearchCounterSnapshotConsumer.java`:

```java
package com.platform.search.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.counter.event.CounterSnapshotEvent;
import com.platform.search.config.SearchProperties;
import com.platform.search.infrastructure.elasticsearch.SearchPostIndexRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !integration")
public class SearchCounterSnapshotConsumer {

    private final ObjectMapper objectMapper;
    private final SearchPostIndexRepository indexRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SearchProperties properties;

    public SearchCounterSnapshotConsumer(ObjectMapper objectMapper,
                                         SearchPostIndexRepository indexRepository,
                                         KafkaTemplate<String, String> kafkaTemplate,
                                         SearchProperties properties) {
        this.objectMapper = objectMapper;
        this.indexRepository = indexRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "${platform.search.kafka.counter-snapshot-topic}",
            groupId = "${platform.search.kafka.counter-consumer-group}",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onSnapshot(String value, Acknowledgment ack) {
        try {
            CounterSnapshotEvent event = objectMapper.readValue(value, CounterSnapshotEvent.class);
            if ("ARTICLE".equals(event.entityType())) {
                indexRepository.updateCounters(event.entityId(), event);
            }
        } catch (IllegalArgumentException badMessage) {
            kafkaTemplate.send(properties.kafka().counterDlqTopic(), value);
        } catch (Exception recoverable) {
            kafkaTemplate.send(properties.kafka().counterRetryTopic(), value);
        } finally {
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 7: Run snapshot tests**

```bash
cd backend
./mvnw -Dtest=CounterSnapshotRelayTest,SearchCounterSnapshotConsumerTest,CounterFlushSchedulerTest test
```

Expected: PASS after updating `CounterFlushSchedulerTest` constructor fakes.

- [ ] **Step 8: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/counter backend/src/main/java/com/platform/search/infrastructure/kafka/SearchCounterSnapshotConsumer.java backend/src/test/java/com/platform/counter backend/src/test/java/com/platform/search/infrastructure/kafka
git commit -m "feat(search): sync counter snapshots to search index"
```

---

### Task 10: Module Docs, API Draft, And Package Markers

**Files:**
- Create: `backend/src/main/java/com/platform/search/package-info.java`
- Create: `backend/docs/modules/search.md`
- Modify: `backend/docs/modules/README.md`
- Modify: `backend/docs/api-draft.md`
- Modify: `backend/docs/development-roadmap.md`
- Test: all targeted unit tests from previous tasks

- [ ] **Step 1: Add package marker**

Create `backend/src/main/java/com/platform/search/package-info.java`:

```java
/**
 * Elasticsearch-backed public post search.
 *
 * <p>The search index is a read model. Content, User, Storage, and Counter remain the source
 * modules; Search consumes reliable events and rebuilds documents from current facts.
 */
package com.platform.search;
```

- [ ] **Step 2: Add module documentation**

Create `backend/docs/modules/search.md`:

```markdown
# Search 模块

## 模块职责

Search 模块负责公开文章搜索，使用 Elasticsearch 解决文章规模增长后的全文检索性能问题。第一版只搜索 `PUBLISHED + PUBLIC + ARTICLE` 内容。

## 核心能力

- 按标题、摘要、正文检索文章。
- 标题命中优先，其次按热度和发布时间排序。
- 返回 Feed 风格列表项。
- 匿名访问时 `likedByMe/favedByMe` 为 `null`，登录访问时实时 overlay。
- 通过 `content_outbox` 可靠维护 ES 文档。
- 通过 Counter snapshot 更新 ES 热度快照。
- 支持索引重建和高水位追赶。

## 事件链路

Content 写事务写入 `content_outbox`，relay 将裸 JSON 投递到 `content-events`。Search 消费后回查 Content 当前事实源，公开已发布则 upsert，非公开或删除则 delete。

Counter flush 后写入 `counter_snapshot_outbox`，relay 投递 `counter-snapshot-events`。Search 使用该事件覆盖 ES 中的计数字段。

## 接口

`GET /api/search/posts?q=&tag=&contentType=ARTICLE&cursor=&size=`

## 运维

搜索功能默认由 `platform.search.enabled=false` 关闭。开启前需要 Elasticsearch 服务、IK 插件、索引 mapping 与别名初始化完成。
```

- [ ] **Step 3: Update module README**

Add to `backend/docs/modules/README.md` current modules table:

```markdown
| search | [search.md](search.md) | Elasticsearch 公开文章检索、content outbox 索引同步、counter 快照热度排序 |
```

Add to list:

```markdown
- [Search 模块](search.md)
```

- [ ] **Step 4: Update API draft**

Add to `backend/docs/api-draft.md`:

```markdown
## Search

### GET /api/search/posts

公开文章搜索。匿名可访问；携带合法 JWT 时返回当前用户点赞/收藏状态。

Query:

- `q`: 搜索关键词。
- `tag`: 标签过滤，可选。
- `contentType`: 内容类型，第一版固定 `ARTICLE`。
- `cursor`: `search_after` 游标，可选。
- `size`: 每页大小，默认 20，最大 50。
```

- [ ] **Step 5: Run verification**

```bash
cd backend
./mvnw test
```

Expected: unit tests pass. If local command execution still cannot run in the shell, run Maven test phase from IDEA and record the result in the final implementation notes.

- [ ] **Step 6: Checkpoint commit**

```bash
git add backend/src/main/java/com/platform/search/package-info.java backend/docs/modules/search.md backend/docs/modules/README.md backend/docs/api-draft.md backend/docs/development-roadmap.md
git commit -m "docs(search): document search module"
```

---

## Final Verification Checklist

- [ ] `./mvnw test` passes, or IDEA Maven test phase passes with the same test set.
- [ ] `platform.search.enabled=false` keeps existing app and integration profiles from requiring Elasticsearch.
- [ ] `GET /api/search/posts` is `permitAll`.
- [ ] `content-events` remains naked JSON for Cache, Counter, and Search.
- [ ] Search content consumer回查 Content 当前事实源 before upsert/delete.
- [ ] Search delete handles missing ES documents as success.
- [ ] Rebuild records outbox high watermark before scanning and replays after it before alias switch.
- [ ] Counter snapshot events overwrite count fields and never create missing ES documents.
- [ ] Anonymous search responses use `likedByMe=null` and `favedByMe=null`.
- [ ] Docs mention ES/IK operational requirement and `platform.search.enabled`.

## Suggested Subagent Assignment

- Agent 1: Task 1, ES config and mapping.
- Agent 2: Tasks 2-3, Content outbox and relay.
- Agent 3: Tasks 4-7, Search domain/index/query API.
- Agent 4: Tasks 8-9, Rebuild and Counter snapshot.
- Agent 5: Task 10, docs and final verification.

Run review after each task before starting the next task that depends on it.
