# User Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder `profile` package with a complete `user` module that owns user account data, public profile data, user status, and user queries.

**Architecture:** Keep `auth` focused on login verification and token concerns. The `user` module owns `user_account` and `user_profile` data through domain types, command/query services, repository contracts, MyBatis infrastructure, DTOs, and REST endpoints.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis, MySQL, JUnit 5.

---

### Task 1: User Domain And Service Tests

**Files:**
- Create: `src/test/java/com/platform/user/application/UserCommandServiceTest.java`
- Create: `src/test/java/com/platform/user/application/UserQueryServiceTest.java`

- [x] Write tests for creating a user with unique username and email.
- [x] Write tests for rejecting duplicate username and email.
- [x] Write tests for updating profile fields.
- [x] Write tests for returning public user profile data.

### Task 2: User Domain And Application

**Files:**
- Create: `src/main/java/com/platform/user/domain/UserAccount.java`
- Create: `src/main/java/com/platform/user/domain/UserProfile.java`
- Create: `src/main/java/com/platform/user/domain/UserRole.java`
- Create: `src/main/java/com/platform/user/domain/UserStatus.java`
- Create: `src/main/java/com/platform/user/application/UserCommandService.java`
- Create: `src/main/java/com/platform/user/application/UserQueryService.java`

- [x] Implement domain records and enums.
- [x] Implement user creation, duplicate checks, and profile updates.
- [x] Implement user profile lookup.

### Task 3: User DTOs, Controller, And Repository

**Files:**
- Create: `src/main/java/com/platform/user/dto/*.java`
- Create: `src/main/java/com/platform/user/controller/UserController.java`
- Create: `src/main/java/com/platform/user/repository/UserRepository.java`
- Create: `src/main/java/com/platform/user/infrastructure/UserMapper.java`
- Create: `src/main/java/com/platform/user/infrastructure/MysqlUserRepository.java`

- [x] Add request and response DTOs.
- [x] Add REST endpoints for public profile, current user profile update, and username lookup.
- [x] Add MyBatis mapper and MySQL repository adapter.

### Task 4: Merge Profile Into User

**Files:**
- Delete: `src/main/java/com/platform/profile/**`
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/module-boundaries.md`
- Modify: `docs/api-draft.md`

- [x] Remove placeholder profile package.
- [x] Update docs to describe the `user` module.

### Verification

- [ ] Run `.\mvnw.cmd test '-Dspring.profiles.active=test'`.
