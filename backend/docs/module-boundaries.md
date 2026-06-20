# Module Boundaries

## auth

Owns login verification, password verification, token creation, and current-user context.

Does not own user account master data or public profile fields such as avatar or bio.

## user

Owns user account master data and display data: username, email, phone, status, role, display name, avatar, bio, location, and website.

Does not own token creation, session validation, or authentication filters.

## content

Owns knowledge content lifecycle: post creation, draft editing, publishing, unpublishing, title, summary, body, tags, and categories.

Does not own likes, favorites, views, or follow relationships.

## counter

Owns content interaction records and high-concurrency counter design. This includes likes, favorites, views, shares, counter snapshots, daily aggregates, and future hot-score calculation.

Does not own user-to-user relationships.

## relation

Owns user relationships: follow, unfollow, follower list, following list, and future block relationships.

Does not own content favorites or content likes.

## storage

Owns upload credentials, object storage provider calls, file metadata, and file lifecycle status.

Does not own how a content post semantically uses an uploaded file.

## cache

Owns Caffeine local cache names and cache manager configuration.

Does not own Redis-based counters, Redis feed lists, or Redis rate limiting.

## config

Owns Spring framework configuration: web, security, Redis, Kafka, MyBatis, and similar infrastructure wiring.

Business-specific event handlers stay inside their owning modules.

## common

Owns small shared primitives such as API response envelopes, pagination inputs, current-user records, and base exceptions.
