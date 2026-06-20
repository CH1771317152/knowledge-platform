# Development Roadmap

## Phase 1: Backend Foundation

- Keep the modular package layout stable.
- Implement `user` account and profile management.
- Implement `auth` registration and login by delegating user creation to `user`.
- Implement JWT issuing and request authentication.
- Add global exception handling and standard API responses.
- Add Flyway or another migration tool before production deployment.

## Phase 2: Content MVP

- Implement post creation, draft editing, publishing, list query, and detail query.
- Keep post metadata and post body separate.
- Add indexes for author feeds and published content lists.
- Add basic Caffeine cache for hot post details.

## Phase 3: Storage MVP

- Implement upload credential generation.
- Store files in MinIO during local development.
- Store file metadata in MySQL.
- Allow content posts to reference uploaded files.

## Phase 4: Counter MVP

- Implement likes and favorites with MySQL unique constraints.
- Add Redis-based interaction state and counters.
- Add asynchronous counter flush through Kafka events.
- Add daily counter aggregate table updates.

## Phase 5: Relation MVP

- Implement follow and unfollow.
- Implement follower and following lists.
- Keep relation data independent from content interaction data.

## Phase 6: High-Concurrency Hardening

- Add idempotency keys for high-frequency write endpoints.
- Add Redis Lua scripts where multi-key counter updates must be atomic.
- Add rate limiting for write-heavy endpoints.
- Add cache penetration and breakdown protection for hot content.
- Add observability for slow SQL, Kafka lag, Redis latency, and endpoint latency.
