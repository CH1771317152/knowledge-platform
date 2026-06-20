# API Draft

## Auth

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

## User

```text
GET  /api/users/me
PUT  /api/users/me/profile
GET  /api/users/{userId}
GET  /api/users/by-username/{username}
```

## Content

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

## Counter

```text
POST   /api/posts/{postId}/likes
DELETE /api/posts/{postId}/likes
POST   /api/posts/{postId}/favorites
DELETE /api/posts/{postId}/favorites
POST   /api/posts/{postId}/views
GET    /api/posts/{postId}/counters
```

## Relation

```text
POST   /api/users/{userId}/follow
DELETE /api/users/{userId}/follow
GET    /api/users/{userId}/following
GET    /api/users/{userId}/followers
GET    /api/users/{userId}/relation
```

## Storage

```text
POST /api/storage/presign
```
