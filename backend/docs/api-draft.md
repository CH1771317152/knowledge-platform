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

## Feed

```text
GET /api/feed/public             (公开) 公共 Feed（PUBLISHED + PUBLIC，published_at 倒序）
                                      cursor 可选（缺省 = head），格式 {ISO LocalDateTime},{id}
                                      size 默认 20，钳制 [1,50]
                                      Bearer 可选 → 登录读者带 likedByMe/favedByMe overlay，匿名则两位为 null
GET /api/feed/me                 (认证) 当前用户的 Feed（draft + published，所有可见性，排除 DELETED，created_at 倒序）
                                      cursor / size 同上
```

## Search

公开文章搜索。匿名可访问；携带合法 JWT 时返回当前用户点赞/收藏状态。

```text
GET /api/search/posts            (公开) Elasticsearch 公开文章检索（PUBLISHED + PUBLIC + ARTICLE）
                                      q          搜索关键词（标题 / 摘要 / 正文，标题命中优先排序）
                                      tag        标签过滤，可选
                                      contentType 内容类型，第一版固定 ARTICLE
                                      cursor     search_after 游标，HMAC 签名 + TTL，可选（缺省 = head）
                                      size       每页大小，默认 20，钳制 [1,50]
                                      Bearer 可选 → 登录读者带 likedByMe/favedByMe overlay，匿名则两位为 null
```
