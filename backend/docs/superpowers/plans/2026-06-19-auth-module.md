# Auth Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the auth module with verification-code registration, password login, verification-code login, JWT access tokens, rotating refresh tokens, logout, password reset, and current-user lookup.

**Architecture:** Auth owns authentication flows, verification state, JWT security, refresh-token sessions, and auth REST endpoints. User remains the owner of account/profile data, while auth calls user application services for account creation, password changes, verification flags, and last-login updates. Redis stores short-lived verification and access-token blacklist state; MySQL stores user data, refresh-token sessions, and verification audit events.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Security, MyBatis, MySQL, Redis Lua scripts, JJWT 0.12.6, JUnit 5, Spring Security Test.

---

## File Structure

Create and modify these files during implementation:

- Modify: `backend/pom.xml`
  Add JJWT dependencies.
- Modify: `backend/src/main/resources/application.yml`
  Add `platform.auth` JWT, refresh-token, verification, and sender configuration.
- Modify: `backend/db/schema.sql`
  Add `auth_refresh_token` and `auth_verification_audit`.
- Modify: `backend/src/main/java/com/platform/config/SecurityConfig.java`
  Wire JWT filter and auth endpoint permissions.
- Create: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
  Central auth/user-facing error codes.
- Modify: `backend/src/main/java/com/platform/common/exception/PlatformException.java`
  Carry an `ErrorCode`.
- Create: `backend/src/main/java/com/platform/common/exception/GlobalExceptionHandler.java`
  Convert auth and validation exceptions to `ApiResponse`.
- Modify: `backend/src/main/java/com/platform/user/application/UserCommandService.java`
  Add auth-facing account mutation methods.
- Modify: `backend/src/main/java/com/platform/user/application/UserQueryService.java`
  Add auth-facing lookup by email or phone.
- Modify: `backend/src/main/java/com/platform/user/repository/UserRepository.java`
  Add auth-facing repository operations.
- Modify: `backend/src/main/java/com/platform/user/infrastructure/UserMapper.java`
  Add MyBatis SQL for auth-facing operations.
- Modify: `backend/src/main/java/com/platform/user/infrastructure/MysqlUserRepository.java`
  Implement new repository operations.
- Create: `backend/src/main/java/com/platform/auth/config/AuthProperties.java`
  Bind auth configuration.
- Create: `backend/src/main/java/com/platform/auth/domain/*.java`
  Auth enums and domain records.
- Create: `backend/src/main/java/com/platform/auth/dto/*.java`
  Request and response DTOs.
- Create: `backend/src/main/java/com/platform/auth/application/*.java`
  Auth, token, verification, and current-user services.
- Create: `backend/src/main/java/com/platform/auth/repository/*.java`
  Refresh-token and verification-audit repository contracts.
- Create: `backend/src/main/java/com/platform/auth/infrastructure/**/*.java`
  JWT, Redis, MyBatis, sender, and security adapters.
- Create: `backend/src/main/resources/redis/auth-verification-send.lua`
  Atomic send-rate and code-write script.
- Create: `backend/src/main/resources/redis/auth-verification-verify.lua`
  Atomic verify-rate, compare, and consume script.
- Create: `backend/src/test/java/com/platform/auth/**/*.java`
  Unit, Redis integration, MySQL integration, and Security integration tests.
- Modify: `backend/docs/modules/README.md`
  Add auth module document link.
- Create: `backend/docs/modules/auth.md`
  Chinese module document after implementation.

---

## Task 1: Common Error Handling And Auth Configuration

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/platform/common/exception/ErrorCode.java`
- Modify: `backend/src/main/java/com/platform/common/exception/PlatformException.java`
- Create: `backend/src/main/java/com/platform/common/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/platform/auth/config/AuthProperties.java`
- Test: `backend/src/test/java/com/platform/auth/config/AuthPropertiesTest.java`

- [ ] **Step 1: Add JWT dependencies to `pom.xml`**

Add these dependencies after `spring-boot-starter-security`:

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: Add auth configuration to `application.yml`**

Add under `platform`:

```yaml
  auth:
    jwt:
      issuer: ${AUTH_JWT_ISSUER:knowledge-platform}
      secret: ${AUTH_JWT_SECRET:change-this-development-secret-change-this}
      access-token-ttl-seconds: ${AUTH_ACCESS_TOKEN_TTL_SECONDS:900}
    refresh-token:
      ttl-seconds: ${AUTH_REFRESH_TOKEN_TTL_SECONDS:1209600}
      bytes: ${AUTH_REFRESH_TOKEN_BYTES:48}
    verification:
      code-ttl-seconds: ${AUTH_VERIFICATION_CODE_TTL_SECONDS:300}
      resend-interval-seconds: ${AUTH_VERIFICATION_RESEND_INTERVAL_SECONDS:60}
      hourly-send-limit: ${AUTH_VERIFICATION_HOURLY_SEND_LIMIT:5}
      max-failed-attempts: ${AUTH_VERIFICATION_MAX_FAILED_ATTEMPTS:5}
      code-length: ${AUTH_VERIFICATION_CODE_LENGTH:6}
    sender:
      mode: ${AUTH_VERIFICATION_SENDER_MODE:logging}
      expose-code-in-logs: ${AUTH_VERIFICATION_EXPOSE_CODE_IN_LOGS:false}
```

- [ ] **Step 3: Create `ErrorCode`**

```java
package com.platform.common.exception;

public enum ErrorCode {
    COMMON_BAD_REQUEST,
    COMMON_UNAUTHORIZED,
    COMMON_FORBIDDEN,
    COMMON_INTERNAL_ERROR,
    AUTH_INVALID_CREDENTIALS,
    AUTH_INVALID_VERIFICATION_CODE,
    AUTH_VERIFICATION_RATE_LIMITED,
    AUTH_TOKEN_EXPIRED,
    AUTH_TOKEN_INVALID,
    AUTH_REFRESH_TOKEN_REUSED,
    AUTH_ACCOUNT_DISABLED,
    AUTH_ACCESS_DENIED,
    USER_NOT_FOUND,
    USER_DUPLICATE_USERNAME,
    USER_DUPLICATE_EMAIL
}
```

- [ ] **Step 4: Update `PlatformException`**

Make it support both legacy message-only construction and structured error codes:

```java
package com.platform.common.exception;

public class PlatformException extends RuntimeException {
    private final ErrorCode errorCode;

    public PlatformException(String message) {
        this(ErrorCode.COMMON_BAD_REQUEST, message);
    }

    public PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 5: Create `GlobalExceptionHandler`**

```java
package com.platform.common.exception;

import com.platform.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiResponse<String>> handlePlatformException(PlatformException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case COMMON_UNAUTHORIZED, AUTH_TOKEN_EXPIRED, AUTH_TOKEN_INVALID -> HttpStatus.UNAUTHORIZED;
            case COMMON_FORBIDDEN, AUTH_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(exception.errorCode().name()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResponse<String>> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.COMMON_BAD_REQUEST.name()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<String>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ErrorCode.AUTH_ACCESS_DENIED.name()));
    }
}
```

- [ ] **Step 6: Create `AuthProperties`**

```java
package com.platform.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.auth")
public record AuthProperties(
        Jwt jwt,
        RefreshToken refreshToken,
        Verification verification,
        Sender sender
) {
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String secret,
            @Min(60) long accessTokenTtlSeconds
    ) {}

    public record RefreshToken(
            @Min(300) long ttlSeconds,
            @Min(32) int bytes
    ) {}

    public record Verification(
            @Min(60) long codeTtlSeconds,
            @Min(1) long resendIntervalSeconds,
            @Min(1) int hourlySendLimit,
            @Min(1) int maxFailedAttempts,
            @Min(4) int codeLength
    ) {}

    public record Sender(String mode, boolean exposeCodeInLogs) {}
}
```

- [ ] **Step 7: Enable `AuthProperties` binding**

Add `@ConfigurationPropertiesScan("com.platform")` to `PlatformApplication`.

- [ ] **Step 8: Write configuration test**

Create `AuthPropertiesTest`:

```java
package com.platform.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthPropertiesTest {

    @Autowired
    private AuthProperties authProperties;

    @Test
    void bindsDefaultAuthProperties() {
        assertThat(authProperties.jwt().issuer()).isEqualTo("knowledge-platform");
        assertThat(authProperties.jwt().accessTokenTtlSeconds()).isEqualTo(900);
        assertThat(authProperties.refreshToken().ttlSeconds()).isEqualTo(1209600);
        assertThat(authProperties.verification().codeLength()).isEqualTo(6);
    }
}
```

- [ ] **Step 9: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: existing tests and `AuthPropertiesTest` pass.

---

## Task 2: User Module Auth Support

**Files:**
- Modify: `backend/src/main/java/com/platform/user/application/UserCommandService.java`
- Modify: `backend/src/main/java/com/platform/user/application/UserQueryService.java`
- Modify: `backend/src/main/java/com/platform/user/repository/UserRepository.java`
- Modify: `backend/src/main/java/com/platform/user/infrastructure/UserMapper.java`
- Modify: `backend/src/main/java/com/platform/user/infrastructure/MysqlUserRepository.java`
- Modify: `backend/src/test/java/com/platform/user/application/UserCommandServiceTest.java`
- Modify: `backend/src/test/java/com/platform/user/application/UserQueryServiceTest.java`
- Modify: `backend/src/test/java/com/platform/user/infrastructure/MysqlUserRepositoryIntegrationTest.java`

- [ ] **Step 1: Extend `UserRepository`**

Add:

```java
Optional<UserAccount> findAccountByEmail(String email);

Optional<UserAccount> findAccountByPhone(String phone);

void updatePasswordHash(Long userId, String passwordHash);

void markEmailVerified(Long userId);

void markPhoneVerified(Long userId);

void updateLastLoginAt(Long userId);
```

- [ ] **Step 2: Extend `UserCommandService`**

Add methods:

```java
@Transactional
public void updatePasswordHash(Long userId, String passwordHash) {
    userRepository.findAccountById(userId)
            .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
    userRepository.updatePasswordHash(userId, normalizeRequired(passwordHash, "passwordHash"));
}

@Transactional
public void markVerified(Long userId, boolean emailVerified, boolean phoneVerified) {
    userRepository.findAccountById(userId)
            .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
    if (emailVerified) {
        userRepository.markEmailVerified(userId);
    }
    if (phoneVerified) {
        userRepository.markPhoneVerified(userId);
    }
}

@Transactional
public void recordSuccessfulLogin(Long userId) {
    userRepository.findAccountById(userId)
            .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
    userRepository.updateLastLoginAt(userId);
}
```

- [ ] **Step 3: Extend `UserQueryService`**

Add:

```java
@Transactional(readOnly = true)
public UserAccount findAccountByEmail(String email) {
    return userRepository.findAccountByEmail(email)
            .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
}

@Transactional(readOnly = true)
public UserAccount findAccountByPhone(String phone) {
    return userRepository.findAccountByPhone(phone)
            .orElseThrow(() -> new PlatformException(ErrorCode.USER_NOT_FOUND, "user not found"));
}
```

- [ ] **Step 4: Add MyBatis SQL**

Add `@Select` methods for email and phone using the same `userAccountResult` mapping:

```java
@Select("""
        SELECT id, username, email, phone, password_hash, status, role,
            email_verified, phone_verified, last_login_at, created_at, updated_at
        FROM user_account
        WHERE email = #{email}
        """)
@ResultMap("userAccountResult")
Optional<UserAccountRow> findAccountByEmail(@Param("email") String email);

@Select("""
        SELECT id, username, email, phone, password_hash, status, role,
            email_verified, phone_verified, last_login_at, created_at, updated_at
        FROM user_account
        WHERE phone = #{phone}
        """)
@ResultMap("userAccountResult")
Optional<UserAccountRow> findAccountByPhone(@Param("phone") String phone);

@Update("UPDATE user_account SET password_hash = #{passwordHash} WHERE id = #{userId}")
int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

@Update("UPDATE user_account SET email_verified = TRUE WHERE id = #{userId}")
int markEmailVerified(@Param("userId") Long userId);

@Update("UPDATE user_account SET phone_verified = TRUE WHERE id = #{userId}")
int markPhoneVerified(@Param("userId") Long userId);

@Update("UPDATE user_account SET last_login_at = CURRENT_TIMESTAMP WHERE id = #{userId}")
int updateLastLoginAt(@Param("userId") Long userId);
```

Import `org.apache.ibatis.annotations.ResultMap`.

- [ ] **Step 5: Implement MySQL repository methods**

Delegate to the new mapper methods and convert rows with `UserAccountRow::toDomain`.

- [ ] **Step 6: Update fake repositories in user tests**

Implement the new repository methods in each fake repository. `updatePasswordHash`, `markEmailVerified`, `markPhoneVerified`, and `updateLastLoginAt` should replace the stored `UserAccount` record with a new record preserving unchanged fields.

- [ ] **Step 7: Add service tests**

Add tests named:

```java
void updatesPasswordHash()
void marksEmailAsVerified()
void marksPhoneAsVerified()
void recordsSuccessfulLogin()
void findsAccountByEmail()
void findsAccountByPhone()
```

Use assertions on the stored fake account values.

- [ ] **Step 8: Add MySQL integration assertions**

In `MysqlUserRepositoryIntegrationTest`, after saving a user with phone, assert:

```java
repository.markEmailVerified(created.id());
repository.markPhoneVerified(created.id());
repository.updatePasswordHash(created.id(), "new-hash");
repository.updateLastLoginAt(created.id());

UserAccount reloaded = repository.findAccountById(created.id()).orElseThrow();
assertThat(reloaded.emailVerified()).isTrue();
assertThat(reloaded.phoneVerified()).isTrue();
assertThat(reloaded.passwordHash()).isEqualTo("new-hash");
assertThat(reloaded.lastLoginAt()).isNotNull();
```

- [ ] **Step 9: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: unit and integration tests pass.

---

## Task 3: Refresh Token Persistence

**Files:**
- Modify: `backend/db/schema.sql`
- Create: `backend/src/main/java/com/platform/auth/domain/AuthRefreshToken.java`
- Create: `backend/src/main/java/com/platform/auth/repository/AuthRefreshTokenRepository.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/persistence/AuthRefreshTokenRow.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/persistence/AuthRefreshTokenMapper.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/persistence/MysqlAuthRefreshTokenRepository.java`
- Test: `backend/src/test/java/com/platform/auth/infrastructure/persistence/MysqlAuthRefreshTokenRepositoryIntegrationTest.java`

- [ ] **Step 1: Add `auth_refresh_token` table**

Append to `schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS auth_refresh_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_jti VARCHAR(64) NOT NULL,
    device_id VARCHAR(128),
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    replaced_by_token_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_auth_refresh_token_hash (token_hash),
    UNIQUE KEY uk_auth_refresh_token_jti (token_jti),
    KEY idx_auth_refresh_token_user_id (user_id),
    KEY idx_auth_refresh_token_expires_at (expires_at),
    CONSTRAINT fk_auth_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id)
);
```

- [ ] **Step 2: Create domain record**

```java
package com.platform.auth.domain;

import java.time.LocalDateTime;

public record AuthRefreshToken(
        Long id,
        Long userId,
        String tokenHash,
        String tokenJti,
        String deviceId,
        String userAgent,
        String ipAddress,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        Long replacedByTokenId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive(LocalDateTime now) {
        return !isRevoked() && !isExpired(now);
    }
}
```

- [ ] **Step 3: Create repository contract**

```java
package com.platform.auth.repository;

import com.platform.auth.domain.AuthRefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthRefreshTokenRepository {
    AuthRefreshToken save(AuthRefreshToken token);
    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);
    void revoke(Long tokenId, LocalDateTime revokedAt, Long replacedByTokenId);
    void revokeAllByUserId(Long userId, LocalDateTime revokedAt);
}
```

- [ ] **Step 4: Create MyBatis row, mapper, and repository**

Use the current `user` infrastructure pattern: a row class with `fromDomain` and `toDomain`, a mapper with annotation SQL, and a `@Repository @Profile("!test")` adapter.

Required mapper methods:

```java
void insert(AuthRefreshTokenRow row);
Optional<AuthRefreshTokenRow> findByTokenHash(String tokenHash);
Optional<AuthRefreshTokenRow> findById(Long id);
int revoke(Long id, LocalDateTime revokedAt, Long replacedByTokenId);
int revokeAllByUserId(Long userId, LocalDateTime revokedAt);
```

- [ ] **Step 5: Write integration test**

Create `MysqlAuthRefreshTokenRepositoryIntegrationTest` that:

1. Inserts a user through `UserCommandService`.
2. Saves a refresh token with hash `hash-refresh-1`.
3. Finds it by hash.
4. Revokes it with `replacedByTokenId = null`.
5. Saves a second token.
6. Calls `revokeAllByUserId`.
7. Asserts both tokens are revoked.

- [ ] **Step 6: Run integration tests**

Run:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: refresh-token repository integration test passes.

---

## Task 4: Verification Store, Lua Scripts, Senders, And Audit

**Files:**
- Modify: `backend/db/schema.sql`
- Create: `backend/src/main/java/com/platform/auth/domain/VerificationChannel.java`
- Create: `backend/src/main/java/com/platform/auth/domain/VerificationPurpose.java`
- Create: `backend/src/main/java/com/platform/auth/domain/VerificationSendResult.java`
- Create: `backend/src/main/java/com/platform/auth/domain/VerificationCheckResult.java`
- Create: `backend/src/main/java/com/platform/auth/application/VerificationStore.java`
- Create: `backend/src/main/java/com/platform/auth/application/VerificationService.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/redis/RedisVerificationStore.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/verification/VerificationCodeGenerator.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/verification/VerificationSender.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/verification/LoggingVerificationSender.java`
- Create: `backend/src/main/java/com/platform/auth/repository/AuthVerificationAuditRepository.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/persistence/AuthVerificationAuditMapper.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/persistence/MysqlAuthVerificationAuditRepository.java`
- Create: `backend/src/main/resources/redis/auth-verification-send.lua`
- Create: `backend/src/main/resources/redis/auth-verification-verify.lua`
- Test: `backend/src/test/java/com/platform/auth/application/VerificationServiceTest.java`
- Test: `backend/src/test/java/com/platform/auth/infrastructure/redis/RedisVerificationStoreIntegrationTest.java`

- [ ] **Step 1: Add audit table**

Append to `schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS auth_verification_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel VARCHAR(16) NOT NULL,
    target VARCHAR(255) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_ip VARCHAR(64),
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at DATETIME,
    KEY idx_auth_verification_audit_target (target),
    KEY idx_auth_verification_audit_created_at (created_at)
);
```

- [ ] **Step 2: Create verification enums**

```java
package com.platform.auth.domain;

public enum VerificationChannel {
    EMAIL,
    SMS
}
```

```java
package com.platform.auth.domain;

public enum VerificationPurpose {
    REGISTER,
    LOGIN,
    RESET_PASSWORD
}
```

- [ ] **Step 3: Create Lua send script**

`auth-verification-send.lua` inputs:

- KEYS[1] code key.
- KEYS[2] resend key.
- KEYS[3] hourly key.
- ARGV[1] code hash.
- ARGV[2] code ttl seconds.
- ARGV[3] resend ttl seconds.
- ARGV[4] hourly ttl seconds.
- ARGV[5] hourly send limit.

Script:

```lua
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'RESEND_LIMITED'
end

local current = redis.call('INCR', KEYS[3])
if current == 1 then
    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4]))
end

if current > tonumber(ARGV[5]) then
    return 'HOURLY_LIMITED'
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3]))
return 'SENT'
```

- [ ] **Step 4: Create Lua verify script**

`auth-verification-verify.lua` inputs:

- KEYS[1] code key.
- KEYS[2] verify-rate key.
- ARGV[1] presented code hash.
- ARGV[2] max failed attempts.
- ARGV[3] verify-rate ttl seconds.

Script:

```lua
local stored = redis.call('GET', KEYS[1])
if not stored then
    return 'MISSING'
end

local failed = redis.call('GET', KEYS[2])
if failed and tonumber(failed) >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 'LOCKED'
end

if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'MATCHED'
end

local attempts = redis.call('INCR', KEYS[2])
if attempts == 1 then
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
end

if attempts >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return 'LOCKED'
end

return 'MISMATCHED'
```

- [ ] **Step 5: Create `VerificationStore` contract**

```java
package com.platform.auth.application;

import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;

public interface VerificationStore {
    VerificationSendResult storeCode(VerificationPurpose purpose, VerificationChannel channel, String target, String codeHash);

    VerificationCheckResult verifyAndConsume(VerificationPurpose purpose, VerificationChannel channel, String target, String codeHash);
}
```

- [ ] **Step 6: Implement `RedisVerificationStore`**

Use `StringRedisTemplate` and `DefaultRedisScript<String>`. Key methods should build these keys:

```java
private String codeKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
    return "auth:verification:code:%s:%s:%s".formatted(purpose, channel, target);
}

private String resendKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
    return "auth:verification:send-rate:%s:%s:%s:resend".formatted(purpose, channel, target);
}

private String hourlyKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
    return "auth:verification:send-rate:%s:%s:%s:hourly".formatted(purpose, channel, target);
}

private String verifyRateKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
    return "auth:verification:verify-rate:%s:%s:%s".formatted(purpose, channel, target);
}
```

- [ ] **Step 7: Implement `VerificationService`**

Responsibilities:

- Normalize email to lowercase and trim whitespace.
- Normalize phone by trimming whitespace.
- Generate 6-digit codes using `SecureRandom`.
- Hash verification codes with SHA-256 using `purpose:channel:target:code`.
- Call `VerificationStore.storeCode`.
- Call `VerificationSender`.
- Throw `AUTH_VERIFICATION_RATE_LIMITED` when Redis returns a limit result.
- `verifyCode` throws `AUTH_INVALID_VERIFICATION_CODE` unless Redis returns `MATCHED`.

- [ ] **Step 8: Write `VerificationServiceTest`**

Test cases:

```java
void sendsEmailVerificationCode()
void rejectsRateLimitedSend()
void consumesMatchingVerificationCode()
void rejectsWrongVerificationCode()
void normalizesEmailTarget()
```

Use fake store and fake sender classes inside the test.

- [ ] **Step 9: Write Redis integration test**

Test `RedisVerificationStoreIntegrationTest` with real Redis:

```java
@SpringBootTest
@ActiveProfiles("integration")
class RedisVerificationStoreIntegrationTest {
    @Autowired RedisVerificationStore store;

    @Test
    void sendScriptLimitsImmediateResend() {
        VerificationSendResult first = store.storeCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL,
                "redis-send@example.com", "hash-1");
        VerificationSendResult second = store.storeCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL,
                "redis-send@example.com", "hash-2");

        assertThat(first).isEqualTo(VerificationSendResult.SENT);
        assertThat(second).isEqualTo(VerificationSendResult.RESEND_LIMITED);
    }

    @Test
    void verifyScriptConsumesMatchingCode() {
        store.storeCode(VerificationPurpose.LOGIN, VerificationChannel.EMAIL,
                "redis-consume@example.com", "hash-1");

        VerificationCheckResult first = store.verifyAndConsume(VerificationPurpose.LOGIN, VerificationChannel.EMAIL,
                "redis-consume@example.com", "hash-1");
        VerificationCheckResult second = store.verifyAndConsume(VerificationPurpose.LOGIN, VerificationChannel.EMAIL,
                "redis-consume@example.com", "hash-1");

        assertThat(first).isEqualTo(VerificationCheckResult.MATCHED);
        assertThat(second).isEqualTo(VerificationCheckResult.MISSING);
    }

    @Test
    void verifyScriptLocksAfterMaxFailures() {
        store.storeCode(VerificationPurpose.RESET_PASSWORD, VerificationChannel.EMAIL,
                "redis-lock@example.com", "hash-1");

        VerificationCheckResult last = VerificationCheckResult.MISMATCHED;
        for (int index = 0; index < 5; index++) {
            last = store.verifyAndConsume(VerificationPurpose.RESET_PASSWORD, VerificationChannel.EMAIL,
                    "redis-lock@example.com", "wrong-hash");
        }

        assertThat(last).isEqualTo(VerificationCheckResult.LOCKED);
    }
}
```

- [ ] **Step 10: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: unit and Redis integration tests pass.

---

## Task 5: JWT, Token Service, And Security Filter

**Files:**
- Create: `backend/src/main/java/com/platform/auth/domain/TokenPair.java`
- Create: `backend/src/main/java/com/platform/auth/domain/IssuedRefreshToken.java`
- Create: `backend/src/main/java/com/platform/auth/application/TokenService.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/jwt/JwtTokenProvider.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/jwt/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/jwt/JwtAuthenticationEntryPoint.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/redis/RedisTokenBlacklist.java`
- Create: `backend/src/main/java/com/platform/auth/infrastructure/security/AuthenticatedPrincipal.java`
- Modify: `backend/src/main/java/com/platform/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/platform/auth/application/TokenServiceTest.java`
- Test: `backend/src/test/java/com/platform/auth/infrastructure/jwt/JwtTokenProviderTest.java`

- [ ] **Step 1: Create token response records**

```java
package com.platform.auth.domain;

import java.time.LocalDateTime;

public record TokenPair(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresInSeconds,
        LocalDateTime refreshTokenExpiresAt
) {}
```

```java
package com.platform.auth.domain;

public record IssuedRefreshToken(String rawToken, String tokenHash, String tokenJti) {}
```

- [ ] **Step 2: Create `AuthenticatedPrincipal`**

```java
package com.platform.auth.infrastructure.security;

import com.platform.user.domain.UserRole;

public record AuthenticatedPrincipal(Long userId, String username, UserRole role, String jti) {}
```

- [ ] **Step 3: Implement `JwtTokenProvider`**

Required methods:

```java
public String createAccessToken(Long userId, String username, UserRole role, String jti)

public AuthenticatedPrincipal parseAccessToken(String token)

public LocalDateTime expiresAt(String token)
```

Use JJWT `Jwts.builder()`, HS256 signing key from `AuthProperties.jwt().secret()`, and claims `sub`, `username`, `role`, `jti`, `typ`.

- [ ] **Step 4: Implement `TokenService`**

Responsibilities:

- Generate refresh token with `SecureRandom`.
- Hash refresh token using SHA-256.
- Save refresh token row with expiry.
- Create access token with a fresh `UUID` jti.
- Refresh: validate refresh token row, revoke old token, create new token pair.
- Reuse detection: if a found token is revoked, call `revokeAllByUserId` and throw `AUTH_REFRESH_TOKEN_REUSED`.
- Logout: revoke refresh token and blacklist access token jti.
- Reset password support: revoke all user refresh tokens.

- [ ] **Step 5: Implement access-token blacklist**

`RedisTokenBlacklist` methods:

```java
public void blacklist(String jti, long ttlSeconds)

public boolean isBlacklisted(String jti)
```

Use key:

```text
auth:jwt:blacklist:{jti}
```

- [ ] **Step 6: Implement JWT filter**

`JwtAuthenticationFilter` extends `OncePerRequestFilter`:

- Skip if no bearer token exists.
- Parse token with `JwtTokenProvider`.
- Reject when blacklist contains jti.
- Set `UsernamePasswordAuthenticationToken` with `AuthenticatedPrincipal`.
- Let invalid token throw an auth exception handled by entry point.

- [ ] **Step 7: Update `SecurityConfig`**

Use:

```java
return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
        .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/error", "/api/auth/**").permitAll()
                .anyRequest().authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
```

- [ ] **Step 8: Write token tests**

`JwtTokenProviderTest` covers:

```java
void createsAndParsesAccessToken()
void rejectsExpiredToken()
void rejectsRefreshTokenTypeAsAccessToken()
```

`TokenServiceTest` covers:

```java
void issuesTokenPair()
void refreshesByRotatingRefreshToken()
void rejectsRevokedRefreshTokenAndRevokesAllUserTokens()
void logsOutByRevokingRefreshTokenAndBlacklistingAccessJti()
```

- [ ] **Step 9: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Expected: token and JWT tests pass.

---

## Task 6: Auth Application Service And REST Controllers

**Files:**
- Create: `backend/src/main/java/com/platform/auth/dto/SendVerificationCodeRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/PasswordLoginRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/VerificationCodeLoginRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/RefreshTokenRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/LogoutRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/ResetPasswordRequest.java`
- Create: `backend/src/main/java/com/platform/auth/dto/AuthResponse.java`
- Create: `backend/src/main/java/com/platform/auth/application/AuthService.java`
- Create: `backend/src/main/java/com/platform/auth/application/CurrentUserService.java`
- Create: `backend/src/main/java/com/platform/auth/controller/AuthController.java`
- Create: `backend/src/main/java/com/platform/auth/controller/VerificationController.java`
- Test: `backend/src/test/java/com/platform/auth/application/AuthServiceTest.java`
- Test: `backend/src/test/java/com/platform/auth/controller/AuthControllerIntegrationTest.java`

- [ ] **Step 1: Create request DTOs**

Use Jakarta Validation:

```java
public record SendVerificationCodeRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotNull VerificationPurpose purpose
) {}

public record RegisterRequest(
        @NotBlank String username,
        @Email @NotBlank String email,
        String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull VerificationChannel verificationChannel,
        @NotBlank String verificationTarget,
        @NotBlank String verificationCode
) {}

public record PasswordLoginRequest(@NotBlank String principal, @NotBlank String password) {}

public record VerificationCodeLoginRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotBlank String verificationCode
) {}

public record RefreshTokenRequest(@NotBlank String refreshToken) {}

public record LogoutRequest(@NotBlank String refreshToken) {}

public record ResetPasswordRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotBlank String verificationCode,
        @NotBlank @Size(min = 8, max = 72) String newPassword
) {}
```

- [ ] **Step 2: Create `AuthResponse`**

```java
package com.platform.auth.dto;

import com.platform.auth.domain.TokenPair;
import com.platform.user.dto.UserProfileResponse;

public record AuthResponse(TokenPair tokenPair, UserProfileResponse currentUser) {}
```

- [ ] **Step 3: Implement password hashing**

Expose a bean:

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

Place it under `com.platform.auth.infrastructure.security.PasswordEncoderConfig`.

- [ ] **Step 4: Implement `AuthService.register`**

Flow:

1. Verify `REGISTER` code.
2. Encode password with BCrypt.
3. Call `UserCommandService.createUser`.
4. Call `UserCommandService.markVerified`.
5. Issue token pair.
6. Return `AuthResponse`.

- [ ] **Step 5: Implement `AuthService.loginWithPassword`**

Flow:

1. Find account by username or email.
2. Reject non-`ACTIVE` accounts with `AUTH_ACCOUNT_DISABLED`.
3. Match password with BCrypt.
4. Record successful login.
5. Issue token pair.

Invalid principal or password must throw `AUTH_INVALID_CREDENTIALS`.

- [ ] **Step 6: Implement `AuthService.loginWithVerificationCode`**

Flow:

1. Verify `LOGIN` code.
2. Find account by email or phone based on channel.
3. Reject non-`ACTIVE` accounts.
4. Record successful login.
5. Issue token pair.

Do not auto-register missing users.

- [ ] **Step 7: Implement refresh, logout, reset password, and me**

Methods:

```java
public TokenPair refresh(String refreshToken)
public void logout(String refreshToken, AuthenticatedPrincipal principal)
public void resetPassword(ResetPasswordRequest request)
public UserProfileResponse currentUser(AuthenticatedPrincipal principal)
```

Reset password verifies `RESET_PASSWORD` code, updates password hash, and revokes all refresh tokens for that user.

- [ ] **Step 8: Implement controllers**

Endpoints:

```text
POST /api/auth/verification-codes
POST /api/auth/register
POST /api/auth/login/password
POST /api/auth/login/verification-code
POST /api/auth/token/refresh
POST /api/auth/logout
POST /api/auth/password/reset
GET  /api/auth/me
```

Return `ApiResponse.ok(response)` for endpoints with response bodies and `ApiResponse.ok(null)` for logout or password-reset endpoints that only need an acknowledgement.

- [ ] **Step 9: Write `AuthServiceTest`**

Use fake verification service, fake token service, fake user services, and a real `BCryptPasswordEncoder`.

Test cases:

```java
void registersAndReturnsTokenPair()
void passwordLoginReturnsTokenPair()
void passwordLoginRejectsWrongPassword()
void verificationCodeLoginReturnsTokenPairForExistingUser()
void verificationCodeLoginRejectsMissingUser()
void resetPasswordRevokesAllRefreshTokens()
```

- [ ] **Step 10: Write controller integration test**

`AuthControllerIntegrationTest` should use `@SpringBootTest` + `@AutoConfigureMockMvc` with `integration` profile and real MySQL/Redis.

Cover:

```java
void registerLoginRefreshMeAndLogoutFlow()
void protectedEndpointRejectsMissingToken()
void blacklistedTokenCannotAccessMe()
```

- [ ] **Step 11: Run tests**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected: all auth service and endpoint tests pass.

---

## Task 7: Documentation And Final Verification

**Files:**
- Create: `backend/docs/modules/auth.md`
- Modify: `backend/docs/modules/README.md`
- Modify: `backend/docs/testing.md`

- [ ] **Step 1: Write module documentation**

Create `docs/modules/auth.md` with these sections:

```markdown
# Auth 模块

## 模块职责

## 核心能力

## 双 Token 机制

## 验证码机制

## Redis Key 设计

## 数据库表

## API 列表

## 安全策略

## 本地测试方式
```

- [ ] **Step 2: Update module index**

Add auth link to `docs/modules/README.md`:

```markdown
- [Auth 模块](auth.md)
```

- [ ] **Step 3: Update testing document**

Add auth-specific commands and notes:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Mention that Redis integration tests validate Lua verification scripts and JWT blacklist behavior.

- [ ] **Step 4: Run final verification**

Run:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Manual API smoke test**

Start the backend:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=integration'
```

Use an HTTP client to exercise:

```text
POST /api/auth/verification-codes
POST /api/auth/register
POST /api/auth/login/password
POST /api/auth/token/refresh
GET  /api/auth/me
POST /api/auth/logout
```

Expected: registration returns token pair, password login returns token pair, refresh rotates refresh token, me returns current user, logout prevents the same access token from calling me again.

---

## Self-Review

- Spec coverage: The plan covers verification sending, registration, password login, verification-code login, refresh, logout, reset password, current user lookup, JWT security, Redis Lua scripts, MySQL refresh tokens, verification audit, and documentation.
- Scope: Auth is large but cohesive because all tasks contribute to a single authentication module and each task is independently testable.
- Type consistency: DTO, service, repository, and enum names match across tasks.
- Known environment note: this workspace currently reports `fatal: not a git repository` for `git status`, so implementation should not rely on git commits being available unless the repository metadata is restored.
