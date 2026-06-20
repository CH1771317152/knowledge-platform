# Knowledge Platform Architecture

## Goal

Build a Spring Boot backend for a high-concurrency knowledge publishing platform. The first backend boundary supports user account data, content posts, object storage, user relations, and a dedicated counter module for high-frequency content interactions.

## Package Strategy

The project uses a modular monolith layout. Each business module owns its controller, application, domain, repository, infrastructure, and event packages when needed. This keeps the first version simple while preserving boundaries that can later become service boundaries.

```text
auth        owns login verification, token creation, and current-user context
user        owns user account data, public profile, status, and roles
content     owns knowledge post lifecycle
counter     owns content interactions and counter aggregation
relation    owns user-to-user relationships
storage     owns object storage and file metadata
cache       owns Caffeine local cache setup
config      owns Spring infrastructure configuration
common      owns shared primitives
```

## Counter Boundary

`counter` is intentionally broader than a statistics helper. It owns user-to-content actions whose main system effect is counting or analytics:

- like and unlike
- favorite and unfavorite
- view count
- share count
- counter snapshots
- daily counter aggregates
- hot score materialization
- Redis high-concurrency counter writes
- asynchronous counter flushing

The module does not own content lifecycle. A post title, body, publication status, tags, and author belong to `content`. User-to-user actions such as following belong to `relation`.

## Cache Boundary

`cache` manages local Caffeine caches only. Redis is not treated as a generic cache package because it will also support counters, deduplication, feed structures, rate limiting, and temporary workflow state. Business modules may use Redis through their own infrastructure adapters.

## Kafka Boundary

Kafka configuration lives in `config`. Producers, consumers, and event types stay near the business module that owns the event. For example, counter event handlers live under `counter/event`, while post publishing events live under `content/event`.

## Data Storage

MySQL stores durable business facts and materialized read models. Object files are stored outside MySQL through MinIO or a compatible cloud object store. MySQL stores file metadata only.

The schema is managed by Flyway migrations under `src/main/resources/db/migration`. Flyway runs automatically on application startup, creating and evolving the schema from the versioned SQL files.
