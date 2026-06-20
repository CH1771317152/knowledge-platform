# Integration Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real MySQL and Redis integration testing support while keeping fast unit tests independent from external services.

**Architecture:** Unit tests continue to use the `test` profile and fake repositories. Integration tests use a new `integration` profile, connect to Docker Compose MySQL/Redis, and run only when Maven's `integration` profile is enabled.

**Tech Stack:** Spring Boot 3.3, MyBatis, MySQL 8.4, Redis 7.4, Caffeine, Maven Surefire/Failsafe.

---

### Task 1: Add Integration Tests

**Files:**
- Create: `src/test/java/com/platform/user/infrastructure/MysqlUserRepositoryIntegrationTest.java`
- Create: `src/test/java/com/platform/cache/RedisAndCacheIntegrationTest.java`

- [ ] **Step 1: Write failing MySQL repository integration test**

Create a Spring Boot integration test that autowires `UserRepository`, writes a unique user, reads it back by id/username/login, updates profile data, and verifies persisted fields.

- [ ] **Step 2: Write failing Redis/cache integration test**

Create a Spring Boot integration test that autowires `StringRedisTemplate` and `CacheManager`, verifies Redis set/get/delete, and verifies the configured Caffeine user cache exists.

- [ ] **Step 3: Run integration tests directly and observe failure before configuration**

Run:

```powershell
.\mvnw.cmd test '-Dtest=MysqlUserRepositoryIntegrationTest,RedisAndCacheIntegrationTest' '-Dspring.profiles.active=integration'
```

Expected before implementation: tests fail because integration execution is not isolated/configured yet or because local services/schema are not ready.

### Task 2: Add Integration Runtime Configuration

**Files:**
- Create: `src/test/resources/application-integration.yml`
- Modify: `pom.xml`
- Modify: `src/main/java/com/platform/user/infrastructure/MysqlUserRepository.java`

- [ ] **Step 1: Add `application-integration.yml`**

Configure MySQL and Redis defaults that match `deploy/docker-compose.yml`.

- [ ] **Step 2: Configure Maven test split**

Set Surefire to exclude `**/*IntegrationTest.java` by default. Add an `integration` Maven profile with Failsafe including `**/*IntegrationTest.java`.

- [ ] **Step 3: Make repository save return database-generated fields**

After inserting account and profile, `MysqlUserRepository.save` should fetch the account by generated id so `createdAt` and `updatedAt` reflect database values.

### Task 3: Document Testing Workflow

**Files:**
- Create: `docs/testing.md`
- Modify: `README.md`

- [ ] **Step 1: Document unit tests**

Explain that `mvnw test` uses `application-test.yml`, excludes DataSource/Redis/Kafka, and validates service-level logic.

- [ ] **Step 2: Document integration tests**

Explain how to start Docker Compose services and run:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

- [ ] **Step 3: Document schema reset caveat**

Explain that Docker MySQL only runs `db/schema.sql` on first volume initialization, so existing volumes may need manual migration or reset.

### Task 4: Verify

**Files:**
- No new files.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: only unit and Spring context tests run; integration tests are excluded.

- [ ] **Step 2: Run integration tests**

Run after Docker Compose MySQL and Redis are available:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: MySQL and Redis integration tests pass.
