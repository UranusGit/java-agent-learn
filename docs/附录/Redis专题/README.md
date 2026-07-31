# Redis 专题（学习顺序）

本文件夹是 **Redis 数据结构与分布式能力** 系列。Redis 在本仓库既是数据载体（Stream/Pub/Sub），也是并发控制（分布式锁）。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **01** | [Redis Streams 与 Pub/Sub 实战](./01-Redis-Streams与PubSub实战.md) | **先读**。Stream（持久日志）+ Pub/Sub（实时广播）——数据流载体 |
| **02** | [Redis 分布式锁实战](./02-Redis分布式锁实战.md) | SETNX→Lua→Redisson 看门狗→fencing token——并发控制从朴素到严谨 |

> **关联**：这两篇对应 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的第 4-8 章（Stream/Pub/Sub 做数据总线、第 8 章用 Redisson 做单一写者锁）。
