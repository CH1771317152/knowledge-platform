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
POST   /api/posts/{postId}/likes            (认证) 当前用户点赞，位图门控，返回 changed
DELETE /api/posts/{postId}/likes            (认证) 取消点赞，位图门控，返回 changed
POST   /api/posts/{postId}/favorites        (认证) 收藏，位图门控，返回 changed
DELETE /api/posts/{postId}/favorites        (认证) 取消收藏，位图门控，返回 changed
POST   /api/posts/{postId}/views            (认证) 浏览，必须带 Idempotency-Key 头
GET    /api/posts/{postId}/counters         (公开) 文章五项计数 like/fav/view/comment/share
GET    /api/posts/{postId}/counters/liked   (认证) 当前用户是否点赞（读位图）
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
