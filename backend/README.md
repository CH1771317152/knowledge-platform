# Knowledge Platform

High-concurrency knowledge publishing platform backend scaffold.

## Stack

- Java 17
- Spring Boot 3
- MySQL
- Redis
- Kafka
- Caffeine
- MinIO or compatible object storage

## Module Layout

```text
src/main/java/com/platform/
  auth/        authentication, login, registration, JWT
  user/        user account data, public profile, status, roles
  content/     knowledge posts, drafts, publishing, tags
  counter/     likes, favorites, views, shares, counter aggregation
  relation/    follows, followers, blocks
  storage/     object storage and file metadata
  cache/       local Caffeine cache management
  config/      Spring, security, Redis, Kafka, MyBatis configuration
  common/      shared response, exception, pagination, security types
```

## Local Development

Maven commands use the project Maven Wrapper. On Windows, make sure `JAVA_HOME` points to a JDK directory before running `mvnw.cmd`.

Start infrastructure:

```bash
docker compose -f deploy/docker-compose.yml up -d
```

The project Docker Compose maps MySQL to local port `3307` by default to avoid conflicts with an existing local MySQL service.

Run tests:

```powershell
.\mvnw.cmd test '-Dspring.profiles.active=test'
```

Run integration tests with real MySQL and Redis:

```powershell
.\mvnw.cmd verify '-Pintegration' '-Dspring.profiles.active=integration'
```

Run the backend:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux, use:

```bash
./mvnw test -Dspring.profiles.active=test
./mvnw spring-boot:run
```

Database schema is managed by Flyway migrations under [src/main/resources/db/migration](src/main/resources/db/migration). Flyway runs automatically on application startup (and during integration tests), so no manual table creation is needed.

More testing details are in [docs/testing.md](docs/testing.md).
