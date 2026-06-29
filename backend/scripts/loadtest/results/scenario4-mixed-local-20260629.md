# 场景 4 结果 — 混合读写（local profile 全栈，5 场景并行）

**日期**：2026-06-29
**Profile**：local（全栈运行）
**脚本**：scenario4-mixed.js
**持续**：4 分钟

## 各场景指标

| 场景 | 配置 | QPS | p(50) | p(95) | max |
|---|---|---|---|---|---|
| feed_read | 80 VUs, 0.5-2.5s think | ~48/s | 0.52ms | 1.61ms | 177ms |
| my_feed | 30 VUs, 1-3s think | ~15/s | 1.55ms | 4.2ms | 232ms |
| likes | 100 req/s constant | 100/s | 5.43ms | 13.03ms | 45ms |
| logins | 3 VUs, 10s think | ~0.3/s | 121ms | 143ms | 159ms |
| details | 20 VUs, 2-5s think | ~5/s | 19ms | 30.6ms | 492ms |

## 整体

- 总请求：39,596
- 混合 QPS：162.6/s
- 错误率：0.00%
- VU 峰值：136/333（41%）
- 所有阈值通过

## 关键发现

1. **点赞写入 6.5ms avg**——Kafka acks=all 贡献 ~5ms，在 100 QPS 下可接受
2. **读写完全隔离**——混合负载下 Feed 读 p95=1.61ms，与单独跑一样
3. **文章详情是最慢读路径**（21ms avg）——不走 Feed 缓存，每次查 MySQL + 正文
4. **系统远未到极限**——VU 只用了 41%

## 对比

- 场景 1（单独 Feed 读）p95=2.19ms → 场景 4（混合）p95=1.61ms（无退化）
- 证明后台调度器 + 并发写入不影响 L2 缓存读路径
