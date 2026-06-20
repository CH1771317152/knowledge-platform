# Backend Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the initial Spring Boot backend scaffold for the knowledge publishing platform.

**Architecture:** Use a modular monolith under `com.platform`, with business modules owning their own layered packages. Keep `counter` as the owner of content interactions and high-concurrency counter design, keep `relation` focused on user-to-user relationships, and keep `cache` focused on Caffeine local cache management.

**Tech Stack:** Java 17, Spring Boot 3, Maven, MySQL, Redis, Kafka, Caffeine, MinIO.

---

### Task 1: Project Skeleton

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/platform/PlatformApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/platform/PlatformApplicationTests.java`
- Create: `src/test/resources/application-test.yml`

- [x] Add Maven dependencies for Spring Web, Validation, Security, MyBatis, MySQL, Redis, Kafka, Caffeine, MinIO, and tests.
- [x] Add the Spring Boot application entrypoint.
- [x] Add a test profile that avoids external infrastructure connections.
- [x] Add a context-load test.

### Task 2: Module Packages

**Files:**
- Create: package markers under `src/main/java/com/platform/auth`
- Create: package markers under `src/main/java/com/platform/user`
- Create: package markers under `src/main/java/com/platform/content`
- Create: package markers under `src/main/java/com/platform/counter`
- Create: package markers under `src/main/java/com/platform/relation`
- Create: package markers under `src/main/java/com/platform/storage`

- [x] Create layered package layout for each business module.
- [x] Keep business-specific event packages inside the owning module.
- [x] Add starter counter enums for target type and counter type.

### Task 3: Shared Infrastructure

**Files:**
- Create: `src/main/java/com/platform/cache/CaffeineCacheConfig.java`
- Create: `src/main/java/com/platform/cache/LocalCacheNames.java`
- Create: `src/main/java/com/platform/config/*.java`
- Create: `src/main/java/com/platform/common/**/*.java`

- [x] Add Caffeine local cache configuration.
- [x] Add framework configuration placeholders.
- [x] Add basic shared response, exception, pagination, and current-user types.

### Task 4: Database And Docs

**Files:**
- Create: `db/schema.sql`
- Create: `deploy/docker-compose.yml`
- Create: `README.md`
- Create: `docs/architecture.md`
- Create: `docs/module-boundaries.md`
- Create: `docs/development-roadmap.md`
- Create: `docs/api-draft.md`

- [x] Add initial MySQL schema.
- [x] Add local infrastructure compose file.
- [x] Document architecture, module boundaries, roadmap, and API draft.

### Verification

- [ ] Run `.\mvnw.cmd test '-Dspring.profiles.active=test'`.
- [ ] Run `.\mvnw.cmd spring-boot:run` after local infrastructure is available.

Verification is pending until Maven is available on the local machine.
