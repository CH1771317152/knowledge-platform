# Feed Hot Key Detection Design

## 背景

当前 `cache.feed` 模块已经实现三级缓存：

```text
L2 Caffeine 本地完整页 -> L1 Redis FeedPage 骨架 -> L0 Redis PostFragment 片段
```

现有 TTL 策略是固定 TTL 加随机抖动。这个策略简单稳定，但无法区分热点页面和冷门页面：热点页面过期后仍会频繁回源，冷门页面又可能占用较长缓存时间。

本设计为 Feed 缓存增加本地热 key 探测能力，通过最近时间窗口内的访问次数动态调整 L1 / L0 写入 TTL。

## 目标

- 读请求只记录热度，不刷新 Redis TTL。
- Redis 缓存重新写入时，根据热度决定 TTL。
- 只统计 Feed `pageKey`，不为每个 `frag:post:{postId}` 建热度计数器。
- L1 skeleton 使用自己的 pageKey 热度计算 TTL。
- L0 fragment 继承触发回填的 pageKey 热度计算 TTL。
- 业务失效时同步 reset 热度计数。
- 自然 TTL 过期不 reset，让热度窗口自然衰减。
- 控制内存和计算量，避免热 key 探测器本身成为新的性能问题。

## 非目标

- 不做全局热 key 统计。每个 JVM 节点只基于本节点流量判断热度。
- 不在每次 Redis 命中时执行 `EXPIRE` 或其他 Redis 写操作。
- 不单独统计 L0 fragment 热度。
- 不改变 Feed 的 cursor 分页语义。
- 不改变 L2 Caffeine 固定短 TTL 策略。L2 仍作为本地读侧节流层。

## 统计对象

只统计逻辑 Feed 页面 key：

```text
feed:public:head:sz20
feed:public:after:{timestamp}:{id}:sz20
feed:user:{uid}:head:sz20
feed:user:{uid}:after:{timestamp}:{id}:sz20
```

不统计 Redis 物理 key：

```text
skel:{pageKey}
null:{pageKey}
frag:post:{postId}
```

原因：

- pageKey 是 Feed 读路径的自然入口，能代表某一页访问热度。
- pageKey 数量低于 fragment 数量。
- L0 fragment 的访问来自页面装配，继承页面热度可以减少统计对象数量。

## 热度窗口模型

使用本地滑动窗口，窗口由总时长和时间片组成。

示例配置：

```text
windowSeconds = 60
sliceSeconds = 10
bucketCount = 6
coldThresholdTicks = bucketCount / 2 = 3
```

每个 pageKey 对应一个计数器：

```text
HotKeyCounter
  int[] counts
  long lastAccessTick
```

全局状态：

```text
currentTick  单调递增的时间片编号
currentSlot  currentTick % bucketCount
```

访问时只更新当前槽位：

```text
record(pageKey):
  counter = counters.get(pageKey)
  if counter 不存在:
      if counters.size >= maxTrackedKeys:
          忽略统计
          return
      创建 int[bucketCount]

  counter.counts[currentSlot]++
  counter.lastAccessTick = currentTick
```

时间片轮转时：

```text
rotate():
  currentTick++
  currentSlot = currentTick % bucketCount

  for each counter:
      counter.counts[currentSlot] = 0
      if currentTick - counter.lastAccessTick >= coldThresholdTicks:
          remove counter
```

轮转时只做两件事：

- 清空新进入的槽位。
- 删除连续多个时间片未访问的冷 key。

轮转时不计算所有 key 的热度。热度只在写缓存需要 TTL 时计算。

## 热度计算

```text
heat(pageKey):
  counter 不存在 -> 0
  返回 sum(counter.counts)
```

默认等级：

```text
heat < 50            LOW
50 <= heat <= 200    MEDIUM
heat > 200           HIGH
```

阈值必须配置化。

## TTL 策略

动态 TTL 开启时，L1 和 L0 的动态基础 TTL 使用 hot key 配置中的基础值，而不是沿用旧的 L1 / L0 固定 TTL。

```text
baseTtlSeconds = 30
```

关闭 hot key 功能时，写入逻辑回退到调用方传入的原始 TTL，保持原有缓存行为。

动态 TTL：

```text
LOW:    baseTtl
MEDIUM: baseTtl + 60s
HIGH:   baseTtl + 120s
```

再叠加固定区间随机抖动：

```text
random(5s, 10s)
```

公式：

```text
ttlFor(pageKey, fallbackTtl):
  if hotKey disabled:
      return fallbackTtl + random(jitterMin, jitterMax)

  heat = heat(pageKey)
  if heat < lowThreshold:
      ttl = baseTtlSeconds
  else if heat <= highThreshold:
      ttl = baseTtlSeconds + mediumExtra
  else:
      ttl = baseTtlSeconds + highExtra
  return ttl + random(jitterMin, jitterMax)
```

这里的上限来自配置本身：

```text
baseTtl + highExtra + jitterMax
```

不做无限叠加。

## 三级缓存接入

### 读路径

`FeedReadService.readPage` 在确定 pageKey 后立即记录：

```text
hotKeyDetector.record(pageKey)
```

这一步只写本地内存，不访问 Redis。

### L1 skeleton

`SkeletonStore.put(pageKey, page, fallbackTtl)` 写 Redis 前计算：

```text
ttl = hotKeyDetector.ttlFor(pageKey, fallbackTtl)
SET skel:{pageKey} json EX ttl
```

### L0 fragment

`FragmentStore` 不知道 pageKey，因此不自行计算热度。

`FeedReadService.backfillMissing` 在某个页面下回填缺失 fragment 时，使用当前 pageKey 计算 L0 TTL：

```text
fragmentTtl = hotKeyDetector.ttlFor(pageKey, props.l0().ttlSeconds())
fragmentStore.put(fragment, fragmentTtl)
```

也就是说，热门页面触发的 fragment 回填会获得更长 TTL。

### L2 Caffeine

L2 继续使用当前固定短 TTL：

```text
head 5s
cursor 60s
```

L2 的职责是本地读侧节流，不参与动态热度 TTL。

## 失效与 reset

业务失效时同步 reset 相关 pageKey 的热度计数。

### POST_PUBLISHED

动作：

```text
删除 public head skeleton
删除 author user head skeleton
reset public/user head pageKey
```

### POST_EDITED

动作：

```text
删除 frag:post:{postId}
不 reset pageKey
```

原因：编辑只影响片段内容，不改变页面骨架热度。

### POST_UNPUBLISHED / POST_DELETED

动作：

```text
写 L0 tombstone
删除 public/user head skeleton
reset public/user head pageKey
```

### POST_VISIBILITY_CHANGED

动作：

```text
删除 L0 fragment
删除 public head skeleton
reset public head pageKey
```

### 自然 TTL 过期

不 reset。

原因：如果某个 key 因自然过期消失，但最近仍有访问热度，下一次回填应该利用热度获得更长 TTL。

## 配置建议

挂在 `platform.cache.feed.hot-key` 下：

```yaml
platform:
  cache:
    feed:
      hot-key:
        enabled: true
        window-seconds: 60
        slice-seconds: 10
        max-tracked-keys: 50000
        cold-threshold-ratio: 0.5
        low-threshold: 50
        high-threshold: 200
        base-ttl-seconds: 30
        medium-extra-ttl-seconds: 60
        high-extra-ttl-seconds: 120
        jitter-min-seconds: 5
        jitter-max-seconds: 10
```

校验规则：

- `windowSeconds > 0`
- `sliceSeconds > 0`
- `windowSeconds % sliceSeconds == 0`
- `bucketCount = windowSeconds / sliceSeconds`
- `bucketCount` 必须在 `[6, 12]`
- `maxTrackedKeys > 0`
- `highThreshold > lowThreshold`
- `coldThresholdRatio > 0 && coldThresholdRatio <= 1`
- `jitterMaxSeconds >= jitterMinSeconds >= 0`

## 并发模型

热度探测器是本地内存组件，需要线程安全。

推荐实现：

- `ConcurrentHashMap<String, HotKeyCounter>` 保存计数器。
- `AtomicLong currentTick` 保存全局时间片编号。
- `AtomicInteger currentSlot` 保存当前槽位。
- `HotKeyCounter.record(slot, tick)` 内部同步单个 counter，保证 `counts[slot]++` 和 `lastAccessTick` 更新一致。
- `rotate()` 遍历 map，逐个 counter 清 slot，并删除冷 key。

不需要 Redis 锁，也不需要跨 JVM 同步。

## 性能边界

访问记录：

```text
O(1)
```

TTL 计算：

```text
O(bucketCount)，默认 6-12
```

时间片轮转：

```text
O(activeTrackedPageKeys)
```

内存：

```text
O(activeTrackedPageKeys * bucketCount)
```

通过以下手段控制：

- 只统计 pageKey。
- 冷 key 以 `lastAccessTick` 淘汰。
- `maxTrackedKeys` 做极端保护。

## 测试策略

### 单元测试

覆盖 `FeedHotKeyDetector`：

- 首次 record 创建计数器。
- 单槽位自增。
- heat 汇总多个槽位。
- rotate 清空新槽位。
- 超过冷阈值删除计数器。
- 超过 `maxTrackedKeys` 时忽略新 key。
- `ttlFor` 对低/中/高热度返回正确区间。
- `reset` 删除计数器。
- disabled 时 `record` 不产生计数，`ttlFor` 返回基础 TTL 加抖动。

覆盖 `FeedReadService`：

- 每次 `readPublicFeed` / `readUserFeed` 调用会 record pageKey。
- 缺失 fragment 回填时 L0 TTL 使用当前 pageKey 热度。

覆盖 `SkeletonStore`：

- 写 L1 时使用动态 TTL。

覆盖 `FeedInvalidationConsumer`：

- 删除 head skeleton 时 reset 对应 pageKey。
- delayed double-delete 不需要再次 reset，reset 一次即可。

### 集成测试

覆盖真实 Redis：

- 热 pageKey 写入 L1 后 TTL 大于冷 key。
- 热 pageKey 触发 L0 fragment 回填后，fragment TTL 大于冷 key 回填的 fragment TTL。
- 业务失效后 detector 中对应 pageKey 被 reset。

## 风险与取舍

- 本地热度不是全局热度。多实例之间可能得到不同 TTL，但这只影响命中率，不影响正确性。
- 轮转会扫描活跃 pageKey。由于只统计 pageKey 并设置冷淘汰和最大数量，第一版可以接受。
- L0 继承页面热度是近似策略。一个 fragment 可能出现在多个页面中，最终 TTL 取决于触发本次回填的页面热度。这个取舍用少量准确性换取更低内存和计算复杂度。
- 动态 TTL 会增加热点缓存停留时间。业务失效链路必须继续作为正确性保障。

## 验收标准

- 热 key 探测器只在本地内存记录访问，不产生额外 Redis 写放大。
- L1 skeleton 写入 TTL 会随 pageKey 热度变化。
- L0 fragment 写入 TTL 会继承当前 pageKey 热度。
- 冷 key 会在若干时间片无访问后从统计器中移除。
- content 事件失效时，对应 head pageKey 热度会 reset。
- 现有 Feed 读路径语义不变。
