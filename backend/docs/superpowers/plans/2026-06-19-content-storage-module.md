# Content And Storage Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Version-control save steps are intentionally omitted for this project until the user enables Git/GitHub management.

**Goal:** Implement the content publishing workflow with six-stage resumable post publishing, content-local Snowflake IDs, Aliyun OSS backed Markdown body storage, and JWT-protected storage presign support.

**Architecture:** `content` owns post lifecycle, publishing stage, metadata, tags, file references, body object pointers, and post queries. `storage` owns Aliyun OSS integration, presigned PUT URL generation, object metadata lookup, object reads, and a JWT-protected `/api/storage/presign` endpoint for ordinary user files. MySQL stores durable content metadata and object pointers; OSS stores Markdown bodies and uploaded files.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Security JWT principal, MyBatis, MySQL, Aliyun OSS Java SDK, JUnit 5, MockMvc, fake object storage for deterministic tests.

---

## File Structure

Create or modify these files:

- Modify: `backend/pom.xml`
  Add Aliyun OSS SDK.
- Modify: `backend/src/main/resources/application.yml`
  Replace MinIO-style storage config with Aliyun OSS config and add content ID config.
- Modify: `backend/db/schema.sql`
  Replace current content tables with the new metadata/body pointer/file reference shape.
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
  Add content and storage error codes.
- Create: `backend/src/main/java/com/platform/storage/config/StorageProperties.java`
  Bind OSS endpoint, region, bucket, access key, and presign TTL.
- Create: `backend/src/main/java/com/platform/storage/domain/PresignedUpload.java`
- Create: `backend/src/main/java/com/platform/storage/domain/StoredObjectMetadata.java`
- Create: `backend/src/main/java/com/platform/storage/application/ObjectStorageService.java`
- Create: `backend/src/main/java/com/platform/storage/application/StoragePresignService.java`
- Create: `backend/src/main/java/com/platform/storage/controller/StorageController.java`
- Create: `backend/src/main/java/com/platform/storage/dto/PresignRequest.java`
- Create: `backend/src/main/java/com/platform/storage/infrastructure/AliyunOssObjectStorageService.java`
- Create: `backend/src/test/java/com/platform/storage/application/StoragePresignServiceTest.java`
- Create: `backend/src/test/java/com/platform/storage/controller/StorageControllerIntegrationTest.java`
- Create: `backend/src/test/java/com/platform/storage/infrastructure/FakeObjectStorageService.java`
- Create: `backend/src/test/java/com/platform/storage/infrastructure/AliyunOssObjectStorageSmokeTest.java`
- Create: `backend/src/main/java/com/platform/content/config/ContentIdProperties.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostStatus.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostVisibility.java`
- Create: `backend/src/main/java/com/platform/content/domain/PublishStage.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostBodyFormat.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostFileUsageType.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPost.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPostBody.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPostFile.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentTag.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/id/ContentIdGenerator.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/id/SnowflakeContentIdGenerator.java`
- Create: `backend/src/main/java/com/platform/content/repository/ContentPostRepository.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/*Row.java`
- Create: `backend/src/main/java/com/platform/content/dto/*.java`
- Create: `backend/src/main/java/com/platform/content/application/ContentCommandService.java`
- Create: `backend/src/main/java/com/platform/content/application/ContentQueryService.java`
- Create: `backend/src/main/java/com/platform/content/controller/ContentController.java`
- Create: `backend/src/test/java/com/platform/content/application/ContentCommandServiceTest.java`
- Create: `backend/src/test/java/com/platform/content/application/ContentQueryServiceTest.java`
- Create: `backend/src/test/java/com/platform/content/infrastructure/id/SnowflakeContentIdGeneratorTest.java`
- Create: `backend/src/test/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepositoryIntegrationTest.java`
- Create: `backend/src/test/java/com/platform/content/controller/ContentControllerIntegrationTest.java`
- Create: `backend/docs/modules/content.md`
- Modify: `backend/docs/modules/README.md`
- Modify: `backend/docs/api-draft.md`
- Modify: `backend/docs/testing.md`

---

## Task 1: Dependencies, Configuration, Error Codes, And Schema

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/db/schema.sql`
- Modify: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Create: `backend/src/main/java/com/platform/storage/config/StorageProperties.java`
- Create: `backend/src/main/java/com/platform/content/config/ContentIdProperties.java`
- Test: `backend/src/test/java/com/platform/storage/config/StoragePropertiesTest.java`
- Test: `backend/src/test/java/com/platform/content/config/ContentIdPropertiesTest.java`

- [ ] **Step 1: Add Aliyun OSS SDK dependency**

Add this dependency to `backend/pom.xml` after the existing `minio` dependency. Keep `minio` for now to avoid unrelated dependency churn.

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.18.1</version>
</dependency>
```

- [ ] **Step 2: Update storage and content config**

Replace the current `platform.storage` block in `application.yml` with:

```yaml
  storage:
    provider: ${STORAGE_PROVIDER:aliyun-oss}
    endpoint: ${OSS_ENDPOINT:https://oss-cn-hangzhou.aliyuncs.com}
    region: ${OSS_REGION:cn-hangzhou}
    bucket: ${OSS_BUCKET:knowledge-platform-dev-2026}
    access-key-id: ${OSS_ACCESS_KEY_ID:}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET:}
    presign-expire-minutes: ${OSS_PRESIGN_EXPIRE_MINUTES:10}
  content:
    id:
      worker-id: ${CONTENT_ID_WORKER_ID:1}
      datacenter-id: ${CONTENT_ID_DATACENTER_ID:1}
```

- [ ] **Step 3: Create `StorageProperties`**

```java
package com.platform.storage.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.storage")
public record StorageProperties(
        @NotBlank String provider,
        @NotBlank String endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        String accessKeyId,
        String accessKeySecret,
        @Min(1) @Max(10) int presignExpireMinutes
) {
}
```

- [ ] **Step 4: Create `ContentIdProperties`**

```java
package com.platform.content.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.content.id")
public record ContentIdProperties(
        @Min(0) @Max(31) long workerId,
        @Min(0) @Max(31) long datacenterId
) {
}
```

- [ ] **Step 5: Add content and storage error codes**

Append these enum constants to `ErrorCode`:

```java
CONTENT_POST_NOT_FOUND,
CONTENT_FORBIDDEN,
CONTENT_INVALID_STAGE,
CONTENT_BODY_NOT_CONFIRMED,
CONTENT_METADATA_INCOMPLETE,
CONTENT_OBJECT_KEY_INVALID,
CONTENT_OBJECT_CONFIRM_FAILED,
CONTENT_ALREADY_DELETED,
STORAGE_PRESIGN_FORBIDDEN,
STORAGE_OBJECT_NOT_FOUND,
STORAGE_OBJECT_CHECK_FAILED
```

- [ ] **Step 6: Replace content schema**

In `backend/db/schema.sql`, replace the existing `content_post`, `content_post_body`, `content_tag`, and `content_post_tag` definitions with:

```sql
CREATE TABLE IF NOT EXISTS content_post (
    id BIGINT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    client_request_id VARCHAR(128),
    title VARCHAR(200),
    summary VARCHAR(500),
    cover_object_key VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    publish_stage VARCHAR(32) NOT NULL DEFAULT 'DRAFT_CREATED',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_post_author_request (author_id, client_request_id),
    KEY idx_content_post_author_created (author_id, created_at),
    KEY idx_content_post_status_published (status, visibility, published_at),
    CONSTRAINT fk_content_post_author FOREIGN KEY (author_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS content_post_body (
    post_id BIGINT PRIMARY KEY,
    body_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
    body_bucket VARCHAR(128),
    body_object_key VARCHAR(512),
    body_etag VARCHAR(255),
    body_sha256 CHAR(64),
    body_size_bytes BIGINT,
    body_version INT NOT NULL DEFAULT 1,
    upload_url_expires_at DATETIME,
    confirmed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_post_body_post FOREIGN KEY (post_id) REFERENCES content_post (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS content_post_file (
    post_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    usage_type VARCHAR(32) NOT NULL,
    content_type VARCHAR(128),
    size_bytes BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, object_key, usage_type),
    CONSTRAINT fk_content_post_file_post FOREIGN KEY (post_id) REFERENCES content_post (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS content_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS content_post_tag (
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, tag_id),
    CONSTRAINT fk_content_post_tag_post FOREIGN KEY (post_id) REFERENCES content_post (id),
    CONSTRAINT fk_content_post_tag_tag FOREIGN KEY (tag_id) REFERENCES content_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 7: Write property binding tests**

`StoragePropertiesTest` should assert:

```java
assertThat(storageProperties.endpoint()).isEqualTo("https://oss-cn-hangzhou.aliyuncs.com");
assertThat(storageProperties.region()).isEqualTo("cn-hangzhou");
assertThat(storageProperties.bucket()).isEqualTo("knowledge-platform-dev-2026");
assertThat(storageProperties.presignExpireMinutes()).isEqualTo(10);
```

`ContentIdPropertiesTest` should assert:

```java
assertThat(contentIdProperties.workerId()).isEqualTo(1);
assertThat(contentIdProperties.datacenterId()).isEqualTo(1);
```

- [ ] **Step 8: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: property tests pass and existing tests remain green.

---

## Task 2: Storage Application Service And Presign Controller

**Files:**
- Create: `backend/src/main/java/com/platform/storage/domain/PresignedUpload.java`
- Create: `backend/src/main/java/com/platform/storage/domain/StoredObjectMetadata.java`
- Create: `backend/src/main/java/com/platform/storage/application/ObjectStorageService.java`
- Create: `backend/src/main/java/com/platform/storage/application/StoragePresignService.java`
- Create: `backend/src/main/java/com/platform/storage/dto/PresignRequest.java`
- Create: `backend/src/main/java/com/platform/storage/controller/StorageController.java`
- Test: `backend/src/test/java/com/platform/storage/application/StoragePresignServiceTest.java`
- Test: `backend/src/test/java/com/platform/storage/controller/StorageControllerIntegrationTest.java`

- [ ] **Step 1: Create storage domain records**

```java
package com.platform.storage.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record PresignedUpload(
        String bucket,
        String objectKey,
        String putUrl,
        Map<String, String> headers,
        LocalDateTime expiresAt
) {
}
```

```java
package com.platform.storage.domain;

public record StoredObjectMetadata(
        String bucket,
        String objectKey,
        String etag,
        long sizeBytes,
        String contentType
) {
}
```

- [ ] **Step 2: Create storage service contract**

```java
package com.platform.storage.application;

import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {
    PresignedUpload presignPut(String objectKey, String contentType, Duration expires);

    StoredObjectMetadata statObject(String objectKey);

    InputStream readObject(String objectKey);
}
```

- [ ] **Step 3: Create `PresignRequest`**

```java
package com.platform.storage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PresignRequest(
        @NotBlank String objectKey,
        @NotBlank String contentType,
        @Min(1) @Max(10) int expiresMinutes
) {
}
```

- [ ] **Step 4: Implement `StoragePresignService`**

Key behavior:

```java
public PresignedUpload presignForUser(Long userId, PresignRequest request) {
    validateObjectKey(userId, request.objectKey());
    validateContentType(request.contentType());
    int minutes = Math.min(request.expiresMinutes(), storageProperties.presignExpireMinutes());
    return objectStorageService.presignPut(request.objectKey(), request.contentType(), Duration.ofMinutes(minutes));
}
```

Validation rules:

```java
if (objectKey.startsWith("/")) throw STORAGE_PRESIGN_FORBIDDEN;
if (objectKey.contains("../")) throw STORAGE_PRESIGN_FORBIDDEN;
if (objectKey.length() > 512) throw STORAGE_PRESIGN_FORBIDDEN;
if (!objectKey.startsWith("users/" + userId + "/")) throw STORAGE_PRESIGN_FORBIDDEN;
if (!allowedContentTypes.contains(contentType)) throw STORAGE_PRESIGN_FORBIDDEN;
```

Allowed content types:

```java
Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "application/zip")
```

- [ ] **Step 5: Implement `StorageController`**

```java
package com.platform.storage.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.storage.application.StoragePresignService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.dto.PresignRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StoragePresignService storagePresignService;
    private final CurrentUserService currentUserService;

    public StorageController(StoragePresignService storagePresignService, CurrentUserService currentUserService) {
        this.storagePresignService = storagePresignService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/presign")
    public ApiResponse<PresignedUpload> presign(@Valid @RequestBody PresignRequest request) {
        AuthenticatedPrincipal principal = currentUserService.requirePrincipal();
        return ApiResponse.ok(storagePresignService.presignForUser(principal.userId(), request));
    }
}
```

- [ ] **Step 6: Write `StoragePresignServiceTest`**

Use a fake `ObjectStorageService` returning a deterministic `PresignedUpload`.

Test cases:

```java
void presignsAllowedUserObjectKey()
void rejectsObjectKeyForAnotherUser()
void rejectsObjectKeyWithParentTraversal()
void rejectsObjectKeyStartingWithSlash()
void rejectsUnsupportedContentType()
void capsExpirationAtConfiguredMaximum()
```

Assertions:

```java
assertThat(result.objectKey()).isEqualTo("users/12/files/file-id/a.png");
assertThatThrownBy(() -> service.presignForUser(12L, badRequest))
        .isInstanceOf(PlatformException.class)
        .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.STORAGE_PRESIGN_FORBIDDEN);
```

- [ ] **Step 7: Write `StorageControllerIntegrationTest`**

Use `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("integration")`, and `@MockBean StoragePresignService`.

Test cases:

```java
void rejectsMissingAccessToken()
void returnsPresignedUploadForAuthenticatedUser()
```

Create a real user and access token through existing auth helper flow, or use `@WithMockUser` only if `CurrentUserService` is adapted for tests. Prefer real JWT flow to exercise the same security path as production.

- [ ] **Step 8: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: storage service and controller tests pass.

---

## Task 3: Aliyun OSS Adapter And Fake Object Storage

**Files:**
- Create: `backend/src/main/java/com/platform/storage/infrastructure/AliyunOssObjectStorageService.java`
- Create: `backend/src/test/java/com/platform/storage/infrastructure/FakeObjectStorageService.java`
- Create: `backend/src/test/java/com/platform/storage/infrastructure/AliyunOssObjectStorageSmokeTest.java`

- [ ] **Step 1: Implement `AliyunOssObjectStorageService`**

Create a `@Service @Profile("!test")` implementation. Constructor dependencies:

```java
StorageProperties storageProperties
```

Implementation requirements:

- Create OSS client with endpoint, access key id, and access key secret.
- `presignPut` uses `GeneratePresignedUrlRequest` with method `PUT`.
- Include `Content-Type` in returned headers.
- `statObject` calls `ossClient.getObjectMetadata(bucket, objectKey)`.
- `readObject` calls `ossClient.getObject(bucket, objectKey).getObjectContent()`.
- Convert OSS exceptions for missing objects to `STORAGE_OBJECT_NOT_FOUND`.
- Convert other OSS failures to `STORAGE_OBJECT_CHECK_FAILED`.

Return `PresignedUpload`:

```java
return new PresignedUpload(
        storageProperties.bucket(),
        objectKey,
        url.toString(),
        Map.of("Content-Type", contentType),
        LocalDateTime.now().plus(expires)
);
```

- [ ] **Step 2: Implement test fake storage**

`FakeObjectStorageService` should:

- Store objects in a `Map<String, byte[]>`.
- Expose helper `putObject(String objectKey, String contentType, byte[] content, String etag)`.
- `presignPut` returns `https://fake-oss.local/{objectKey}`.
- `statObject` returns size and etag from the map.
- `readObject` returns `new ByteArrayInputStream(content)`.

- [ ] **Step 3: Write smoke test gated by environment variables**

Create `AliyunOssObjectStorageSmokeTest` with:

```java
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_SECRET", matches = ".+")
@SpringBootTest
@ActiveProfiles("integration")
class AliyunOssObjectStorageSmokeTest {
}
```

Test `presignStatAndReadRoundTrip`:

1. Generate objectKey `users/1/files/smoke/content.md`.
2. Request presigned PUT with `text/plain`.
3. Use `HttpURLConnection` PUT to upload `hello oss`.
4. Call `statObject` and assert size.
5. Call `readObject` and assert content.

This test runs only when OSS credentials are present. It is allowed to create a small object in `knowledge-platform-dev-2026`.

- [ ] **Step 4: Run ordinary tests without OSS dependency**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: default tests pass without requiring Aliyun OSS network access.

- [ ] **Step 5: Run optional OSS smoke test manually**

Run only after confirming environment variables exist:

```powershell
$env:OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
$env:OSS_REGION='cn-hangzhou'
$env:OSS_BUCKET='knowledge-platform-dev-2026'
.\mvnw.cmd '-Dtest=AliyunOssObjectStorageSmokeTest' test '-Dspring.profiles.active=integration'
```

Expected: smoke test uploads, stats, and reads a tiny object from Aliyun OSS.

---

## Task 4: Content Domain And Snowflake ID

**Files:**
- Create: `backend/src/main/java/com/platform/content/domain/PostStatus.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostVisibility.java`
- Create: `backend/src/main/java/com/platform/content/domain/PublishStage.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostBodyFormat.java`
- Create: `backend/src/main/java/com/platform/content/domain/PostFileUsageType.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPost.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPostBody.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentPostFile.java`
- Create: `backend/src/main/java/com/platform/content/domain/ContentTag.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/id/ContentIdGenerator.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/id/SnowflakeContentIdGenerator.java`
- Test: `backend/src/test/java/com/platform/content/infrastructure/id/SnowflakeContentIdGeneratorTest.java`

- [ ] **Step 1: Create enums**

```java
public enum PostStatus { DRAFT, PUBLISHED, DELETED }
public enum PostVisibility { PUBLIC, PRIVATE }
public enum PublishStage { DRAFT_CREATED, BODY_URL_ISSUED, BODY_CONFIRMED, METADATA_COMPLETED, PUBLISHED }
public enum PostBodyFormat { MARKDOWN }
public enum PostFileUsageType { COVER, INLINE_IMAGE, ATTACHMENT }
```

- [ ] **Step 2: Create domain records**

`ContentPost` fields:

```java
Long id;
Long authorId;
String clientRequestId;
String title;
String summary;
String coverObjectKey;
PostStatus status;
PostVisibility visibility;
PublishStage publishStage;
LocalDateTime publishedAt;
LocalDateTime createdAt;
LocalDateTime updatedAt;
```

`ContentPostBody` fields:

```java
Long postId;
PostBodyFormat bodyFormat;
String bodyBucket;
String bodyObjectKey;
String bodyEtag;
String bodySha256;
Long bodySizeBytes;
int bodyVersion;
LocalDateTime uploadUrlExpiresAt;
LocalDateTime confirmedAt;
LocalDateTime createdAt;
LocalDateTime updatedAt;
```

`ContentPostFile` fields:

```java
Long postId;
String objectKey;
PostFileUsageType usageType;
String contentType;
Long sizeBytes;
int sortOrder;
LocalDateTime createdAt;
```

`ContentTag` fields:

```java
Long id;
String name;
LocalDateTime createdAt;
```

- [ ] **Step 3: Create ID generator contract**

```java
package com.platform.content.infrastructure.id;

public interface ContentIdGenerator {
    long nextId();
}
```

- [ ] **Step 4: Implement Snowflake generator**

Use:

```text
timestamp bits: 41
datacenter bits: 5
worker bits: 5
sequence bits: 12
custom epoch: 2026-01-01T00:00:00Z
```

Behavior:

- `nextId()` is synchronized.
- Same millisecond increments sequence.
- Sequence overflow waits until next millisecond.
- Clock moving backwards throws `PlatformException(COMMON_INTERNAL_ERROR, "Clock moved backwards")`.

- [ ] **Step 5: Write Snowflake tests**

Test cases:

```java
void generatesIncreasingIds()
void generatesUniqueIdsAcrossManyCalls()
void embedsDatacenterAndWorkerWithinConfiguredBitRange()
```

Assertions:

```java
List<Long> ids = LongStream.range(0, 10_000).map(i -> generator.nextId()).boxed().toList();
assertThat(ids).doesNotHaveDuplicates();
assertThat(ids).isSorted();
```

- [ ] **Step 6: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: Snowflake tests pass.

---

## Task 5: Content Repository And MyBatis Integration

**Files:**
- Create: `backend/src/main/java/com/platform/content/repository/ContentPostRepository.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostRow.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostBodyRow.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostFileRow.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentTagRow.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/ContentPostMapper.java`
- Create: `backend/src/main/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepository.java`
- Test: `backend/src/test/java/com/platform/content/infrastructure/persistence/MysqlContentPostRepositoryIntegrationTest.java`

- [ ] **Step 1: Create repository contract**

Required operations:

```java
ContentPost saveDraft(ContentPost post, ContentPostBody body);
Optional<ContentPost> findPostById(Long postId);
Optional<ContentPost> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId);
Optional<ContentPostBody> findBodyByPostId(Long postId);
List<ContentPostFile> findFilesByPostId(Long postId);
List<ContentTag> findTagsByPostId(Long postId);
void updateBodyUploadUrl(Long postId, String bucket, String objectKey, LocalDateTime expiresAt, PublishStage stage);
void confirmBody(Long postId, String objectKey, String etag, String sha256, long sizeBytes, LocalDateTime confirmedAt);
void updateMetadata(Long postId, String title, String summary, PostVisibility visibility, String coverObjectKey);
void replaceFiles(Long postId, List<ContentPostFile> files);
void replaceTags(Long postId, List<String> tagNames);
void updateStatusAndStage(Long postId, PostStatus status, PublishStage stage, LocalDateTime publishedAt);
void softDelete(Long postId);
List<ContentPost> findPublicPublished(int limit, long offset);
List<ContentPost> findByAuthor(Long authorId, int limit, long offset);
```

- [ ] **Step 2: Create row classes**

Each row class should follow current user/auth persistence style:

```java
static ContentPostRow fromDomain(ContentPost post)
ContentPost toDomain()
```

Map enums as `String` values.

- [ ] **Step 3: Create MyBatis mapper**

Mapper SQL must include:

```java
@Insert("""
        INSERT INTO content_post (id, author_id, client_request_id, title, summary,
            cover_object_key, status, visibility, publish_stage, published_at)
        VALUES (#{id}, #{authorId}, #{clientRequestId}, #{title}, #{summary},
            #{coverObjectKey}, #{status}, #{visibility}, #{publishStage}, #{publishedAt})
        """)
void insertPost(ContentPostRow row);

@Insert("""
        INSERT INTO content_post_body (post_id, body_format, body_bucket, body_object_key,
            body_etag, body_sha256, body_size_bytes, body_version, upload_url_expires_at,
            confirmed_at)
        VALUES (#{postId}, #{bodyFormat}, #{bodyBucket}, #{bodyObjectKey},
            #{bodyEtag}, #{bodySha256}, #{bodySizeBytes}, #{bodyVersion},
            #{uploadUrlExpiresAt}, #{confirmedAt})
        """)
void insertBody(ContentPostBodyRow row);

@Select("""
        SELECT id, author_id, client_request_id, title, summary, cover_object_key,
            status, visibility, publish_stage, published_at, created_at, updated_at
        FROM content_post
        WHERE id = #{postId}
        """)
Optional<ContentPostRow> findPostById(Long postId);

@Select("""
        SELECT id, author_id, client_request_id, title, summary, cover_object_key,
            status, visibility, publish_stage, published_at, created_at, updated_at
        FROM content_post
        WHERE author_id = #{authorId} AND client_request_id = #{clientRequestId}
        """)
Optional<ContentPostRow> findPostByAuthorAndClientRequestId(Long authorId, String clientRequestId);
```

Use separate methods for files and tags:

```java
void deleteFiles(Long postId);
void insertFile(ContentPostFileRow row);
void deletePostTags(Long postId);
Long findTagIdByName(String name);
void insertTag(String name);
void insertPostTag(Long postId, Long tagId);
```

- [ ] **Step 4: Implement MySQL repository**

Rules:

- `replaceFiles` deletes existing files for post then inserts request files.
- `replaceTags` trims tag names, removes blanks, de-duplicates by exact normalized name, creates missing tags, then replaces associations.
- `findPublicPublished` filters `status='PUBLISHED' AND visibility='PUBLIC'`.
- `findByAuthor` filters `status <> 'DELETED'`.

- [ ] **Step 5: Write repository integration test**

`MysqlContentPostRepositoryIntegrationTest` should:

1. Create a real user through `UserCommandService`.
2. Save a draft with a fixed postId from the test.
3. Assert post and body rows exist.
4. Update body upload URL and assert objectKey/expiresAt.
5. Confirm body and assert etag/sha256/size/confirmedAt.
6. Update metadata.
7. Replace files with one cover and one attachment.
8. Replace tags with `Java`, `Spring`, `Java` and assert only two tag associations.
9. Publish and assert public list includes the post.
10. Soft delete and assert public list excludes the post.

- [ ] **Step 6: Run integration tests**

Run:

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: repository integration tests pass.

---

## Task 6: Content Command Service Workflow

**Files:**
- Create: `backend/src/main/java/com/platform/content/dto/CreateDraftRequest.java`
- Create: `backend/src/main/java/com/platform/content/dto/BodyUploadUrlResponse.java`
- Create: `backend/src/main/java/com/platform/content/dto/ConfirmBodyRequest.java`
- Create: `backend/src/main/java/com/platform/content/dto/UpdatePostMetadataRequest.java`
- Create: `backend/src/main/java/com/platform/content/dto/PostFileRequest.java`
- Create: `backend/src/main/java/com/platform/content/dto/PostPublishingStateResponse.java`
- Create: `backend/src/main/java/com/platform/content/application/ContentCommandService.java`
- Test: `backend/src/test/java/com/platform/content/application/ContentCommandServiceTest.java`

- [ ] **Step 1: Create DTOs**

Use Jakarta Validation:

```java
public record CreateDraftRequest(String clientRequestId) {}

public record ConfirmBodyRequest(
        @NotBlank String objectKey,
        @NotBlank String etag,
        @Min(0) long sizeBytes,
        @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256
) {}

public record PostFileRequest(
        @NotBlank String objectKey,
        @NotNull PostFileUsageType usageType,
        String contentType,
        Long sizeBytes,
        int sortOrder
) {}

public record UpdatePostMetadataRequest(
        @NotBlank String title,
        String summary,
        @NotNull PostVisibility visibility,
        String coverObjectKey,
        List<PostFileRequest> files,
        List<String> tags
) {}
```

`PostPublishingStateResponse` fields:

```java
Long postId;
PostStatus status;
PublishStage publishStage;
String bodyObjectKey;
boolean bodyConfirmed;
boolean metadataCompleted;
List<String> nextActions;
```

- [ ] **Step 2: Implement create draft**

`ContentCommandService.createDraft(Long authorId, CreateDraftRequest request)`:

- If `clientRequestId` present and repository finds existing draft, return existing state.
- Otherwise generate ID.
- Save `ContentPost` and `ContentPostBody`.
- Return publishing state.

Default state:

```text
status = DRAFT
visibility = PRIVATE
publishStage = DRAFT_CREATED
bodyVersion = 1
bodyFormat = MARKDOWN
```

- [ ] **Step 3: Implement body upload URL**

`requestBodyUploadUrl(Long authorId, Long postId)`:

- Load post and body.
- Require author.
- Reject deleted post.
- Compute objectKey `posts/{postId}/body/v{bodyVersion}.md`.
- Call `objectStorageService.presignPut(objectKey, "text/markdown", Duration.ofMinutes(10))`.
- Update body bucket/objectKey/expiresAt and stage `BODY_URL_ISSUED`.
- Return `BodyUploadUrlResponse`.

- [ ] **Step 4: Implement body confirm**

`confirmBody(Long authorId, Long postId, ConfirmBodyRequest request)`:

- Load post and body.
- Require author.
- Reject deleted post.
- Require request objectKey equals expected objectKey.
- If already confirmed with same object data, return state.
- If already confirmed with different object data, throw `CONTENT_OBJECT_CONFIRM_FAILED`.
- Call `statObject`; compare size and etag.
- Read object stream and compute SHA-256.
- Compare computed hash with request hash.
- Persist confirmation and stage `BODY_CONFIRMED`.

- [ ] **Step 5: Implement metadata update**

`updateMetadata(Long authorId, Long postId, UpdatePostMetadataRequest request)`:

- Load post and body.
- Require author.
- Require body confirmed.
- Reject deleted post.
- Validate every file objectKey starts with `users/{authorId}/`.
- Validate coverObjectKey starts with `users/{authorId}/` when nonblank.
- Build `ContentPostFile` list from request.
- If coverObjectKey is present, ensure a `COVER` file reference exists.
- Replace metadata, files, and tags.
- Update stage `METADATA_COMPLETED`.

- [ ] **Step 6: Implement publish**

`publish(Long authorId, Long postId)`:

- Load post and body.
- Require author.
- Reject deleted post.
- If already published, return current state.
- Require stage is `METADATA_COMPLETED` or `PUBLISHED`.
- Set status `PUBLISHED`, stage `PUBLISHED`, and publishedAt only if absent.

- [ ] **Step 7: Implement unpublish and delete**

`unpublish(Long authorId, Long postId)`:

- Require author.
- If status is `PUBLISHED`, set status `DRAFT` and stage `METADATA_COMPLETED`.
- If already draft, return current state.

`delete(Long authorId, Long postId)`:

- Require author.
- If already deleted, return current state.
- Set status `DELETED`.

- [ ] **Step 8: Write command service tests**

Use fake repository, fake ID generator, and fake object storage.

Test cases:

```java
void createsDraftWithSnowflakeId()
void createDraftIsIdempotentWithClientRequestId()
void repeatedBodyUploadUrlKeepsObjectKey()
void confirmBodyComputesSha256AndAdvancesStage()
void confirmBodyIsIdempotentForSameObject()
void confirmBodyRejectsDifferentObjectAfterConfirmation()
void updateMetadataReplacesFilesAndTags()
void publishIsIdempotent()
void deletePreventsPublish()
```

- [ ] **Step 9: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: content command tests pass.

---

## Task 7: Content Query Service And Controllers

**Files:**
- Create: `backend/src/main/java/com/platform/content/dto/PostSummaryResponse.java`
- Create: `backend/src/main/java/com/platform/content/dto/PostDetailResponse.java`
- Create: `backend/src/main/java/com/platform/content/application/ContentQueryService.java`
- Create: `backend/src/main/java/com/platform/content/controller/ContentController.java`
- Test: `backend/src/test/java/com/platform/content/application/ContentQueryServiceTest.java`
- Test: `backend/src/test/java/com/platform/content/controller/ContentControllerIntegrationTest.java`

- [ ] **Step 1: Create response DTOs**

`PostSummaryResponse`:

```java
Long postId;
Long authorId;
String title;
String summary;
String coverObjectKey;
PostStatus status;
PostVisibility visibility;
LocalDateTime publishedAt;
LocalDateTime createdAt;
LocalDateTime updatedAt;
List<String> tags;
```

`PostDetailResponse`:

```java
PostSummaryResponse summary;
String body;
String bodyObjectKey;
String bodySha256;
List<PostFileResponse> files;
```

- [ ] **Step 2: Implement query service**

Methods:

```java
List<PostSummaryResponse> listPublicPosts(int page, int size);
List<PostSummaryResponse> listMyPosts(Long authorId, int page, int size);
PostDetailResponse getPostDetail(Long requesterIdOrNull, Long postId);
PostPublishingStateResponse getPublishingState(Long authorId, Long postId);
```

Permission rules:

- Public published post can be read without login.
- Draft can be read only by author.
- Private published post can be read only by author.
- Deleted post throws `CONTENT_POST_NOT_FOUND`.

Detail reads Markdown via `objectStorageService.readObject(bodyObjectKey)` after permission check.

- [ ] **Step 3: Implement `ContentController` endpoints**

Endpoints:

```text
POST   /api/posts/drafts
POST   /api/posts/{postId}/body/upload-url
POST   /api/posts/{postId}/body/confirm
PUT    /api/posts/{postId}/metadata
POST   /api/posts/{postId}/publish
POST   /api/posts/{postId}/unpublish
DELETE /api/posts/{postId}
GET    /api/posts/{postId}/publishing-state
GET    /api/posts/{postId}
GET    /api/posts
GET    /api/posts/me
```

Use `CurrentUserService.requirePrincipal()` for authenticated endpoints. For `GET /api/posts/{postId}`, allow anonymous access by reading `SecurityContextHolder` directly and treating missing principal as anonymous.

- [ ] **Step 4: Update `SecurityConfig` for public content reads**

Add public read matchers before `anyRequest`:

```java
.requestMatchers(HttpMethod.GET, "/api/posts", "/api/posts/*").permitAll()
```

Do not permit:

```text
GET /api/posts/me
GET /api/posts/{postId}/publishing-state
```

If simple wildcard matching also permits the protected paths, replace with controller-level public endpoint paths that avoid ambiguity, for example `/api/public/posts` and `/api/public/posts/{postId}`. Prefer the least surprising route shape before implementation.

- [ ] **Step 5: Write query service tests**

Test cases:

```java
void publicListReturnsOnlyPublishedPublicPosts()
void authorCanReadDraftDetail()
void anonymousCannotReadDraftDetail()
void anonymousCanReadPublishedPublicDetail()
void anonymousCannotReadPublishedPrivateDetail()
void deletedPostIsNotReadable()
void publishingStateRequiresAuthor()
```

- [ ] **Step 6: Write controller integration tests**

Use real MySQL and fake object storage bean under integration profile.

Flow test:

1. Register/login user and capture access token.
2. Create draft.
3. Request body upload URL.
4. Put Markdown content into fake object storage using returned objectKey.
5. Confirm body.
6. Update metadata.
7. Publish.
8. Anonymous GET detail returns body.
9. GET public list includes post.
10. Author unpublishes post.
11. Anonymous GET detail returns 400 or 404 through API error response.

- [ ] **Step 7: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: query and controller tests pass.

---

## Task 8: Documentation And Manual Verification

**Files:**
- Create: `backend/docs/modules/content.md`
- Modify: `backend/docs/modules/README.md`
- Modify: `backend/docs/api-draft.md`
- Modify: `backend/docs/testing.md`

- [ ] **Step 1: Write content module documentation**

Create `docs/modules/content.md` with sections:

```markdown
# Content 模块

## 模块职责

## 六阶段发布流程

## 状态模型

## 数据库表

## OSS 对象规则

## Storage /presign

## 接口列表

## 幂等规则

## 测试方式
```

- [ ] **Step 2: Update module index**

Add:

```markdown
- [Content 模块](content.md)
```

- [ ] **Step 3: Update API draft**

Replace old content/storage sections with:

```text
POST   /api/posts/drafts
POST   /api/posts/{postId}/body/upload-url
POST   /api/posts/{postId}/body/confirm
PUT    /api/posts/{postId}/metadata
POST   /api/posts/{postId}/publish
POST   /api/posts/{postId}/unpublish
DELETE /api/posts/{postId}
GET    /api/posts/{postId}/publishing-state
GET    /api/posts/{postId}
GET    /api/posts
GET    /api/posts/me
POST   /api/storage/presign
```

- [ ] **Step 4: Update testing docs**

Add:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
.\mvnw.cmd '-Dtest=AliyunOssObjectStorageSmokeTest' test '-Dspring.profiles.active=integration'
```

Document that the OSS smoke test requires:

```text
OSS_ENDPOINT
OSS_REGION
OSS_BUCKET
OSS_ACCESS_KEY_ID
OSS_ACCESS_KEY_SECRET
```

- [ ] **Step 5: Run final automated verification**

Run:

```powershell
docker compose -f deploy/docker-compose.yml up -d mysql redis
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Manual HTTP smoke test**

Start backend:

```powershell
$env:OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
$env:OSS_REGION='cn-hangzhou'
$env:OSS_BUCKET='knowledge-platform-dev-2026'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=integration'
```

Exercise:

```text
POST /api/auth/verification-codes
POST /api/auth/register
POST /api/posts/drafts
POST /api/posts/{postId}/body/upload-url
PUT  returned OSS putUrl with Content-Type text/markdown
POST /api/posts/{postId}/body/confirm
PUT  /api/posts/{postId}/metadata
POST /api/posts/{postId}/publish
GET  /api/posts/{postId}
GET  /api/posts
```

Expected:

- Draft creation returns postId immediately.
- Body upload URL objectKey is `posts/{postId}/body/v1.md`.
- Confirm computes and records sha256.
- Publish is idempotent.
- Public detail returns Markdown body.
- Public list includes the post.

---

## Self-Review

- Spec coverage: This plan covers six-stage content publishing, content-local Snowflake IDs, OSS Markdown body storage, storage `/presign`, JWT security, schema changes, object confirmation, queries, docs, and deferred OSS features.
- Scope: The plan is large but cohesive because content and storage are coupled by the required publishing flow. Counter, relation, Feed, search, cache refresh, review flow, and multipart upload remain deferred.
- Type consistency: DTO, enum, service, repository, and endpoint names are consistent across tasks.
- Project management note: version-control save steps are omitted per user instruction because the current project is not managed through GitHub/Git.
