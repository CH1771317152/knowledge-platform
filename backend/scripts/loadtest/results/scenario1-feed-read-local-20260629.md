# 场景 1 结果 — Feed 公开页读取（local profile 全栈）

**日期**：2026-06-29
**Profile**：local（全栈运行，Kafka 消费者 + 定时任务全部在线）
**脚本**：scenario1-feed-read.js

## 结果

| 指标 | 值 |
|---|---|
| 总请求 | 17,989 |
| 平均 QPS | 54.19/s |
| p(50) | 0.67ms |
| p(90) | 1.46ms |
| p(95) | 2.19ms |
| avg | 1.12ms |
| max | 248.75ms |
| 错误率 | 0.00% |
| 数据量/请求 | ~6.4KB |

## 对比 integration profile

| 指标 | integration | local | 差异 |
|---|---|---|---|
| p(50) | 0.75ms | 0.67ms | -11%（噪音） |
| p(95) | 2.56ms | 2.19ms | -14% |
| max | 268ms | 249ms | -7% |
| 错误率 | 0% | 0% | 持平 |

## 分析

- 后台调度器（outbox relay / flush scheduler / snapshot relay / reconciliation / hotkey rotation）对 Feed 读路径**几乎零影响**
- L2 Caffeine 缓存完全隔离了后台任务的资源竞争
- max=249ms 仍是 L2 miss 冷启动的 N+1 碎片回源（20 篇 × 3 次 MySQL 查询）
- 结论：读路径在全栈运行下依然极快，瓶颈不在这里
