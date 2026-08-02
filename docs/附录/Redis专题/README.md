# Redis 专题（学习顺序）

本文件夹是 **Redis 数据结构与分布式能力** 系列。Redis 在本仓库既是数据载体（Stream/Pub/Sub），也是并发控制（分布式锁）。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **00** | [Redis 基础 + Spring Boot 使用](./00-Redis基础与SpringBoot使用.md) | **最先读**。起 Redis、五种基础数据结构（String/List/Set/Hash/ZSet）、TTL、Spring Boot 响应式收发——01/02 的地基 |
| **01** | [Redis Streams 与 Pub/Sub 实战](./01-Redis-Streams与PubSub实战.md) | Stream（持久日志）+ Pub/Sub（实时广播）——数据流载体 |
| **02** | [Redis 分布式锁实战](./02-Redis分布式锁实战.md) | SETNX→Lua→Redisson 看门狗→fencing token——并发控制从朴素到严谨 |
| **03** | [Redis 缓存实战](./03-Redis缓存实战.md) | Cache-Aside 模式、穿透/击穿/雪崩三坑、Spring Cache 注解、RedisTemplate 手动缓存、与 DB 一致性——"怎么把缓存用对" |

**学习路线**：

```mermaid
flowchart LR
    base["00 Redis 基础 + Spring Boot 使用<br/>（地基，最先读）"] --> s1["01 Streams 与 Pub/Sub<br/>（数据流载体）"]
    base --> s2["02 分布式锁<br/>（并发控制）"]
    base --> s3["03 缓存实战<br/>（缓存模式与三坑）"]
    s2 -.->|"击穿的互斥锁重建<br/>用到 SETNX"| s3
```

> **顺序说明**：**00 是地基，最先读**；01、02、03 默认你已会用 Redis 基础，上来直接讲进阶能力。03（缓存）和 02（锁）会互相引用（击穿的互斥锁重建用了 SETNX）。
>
> **关联**：这几篇对应 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的第 4-9 章（Stream/Pub/Sub 做数据总线、第 8 章用 Redisson 做单一写者锁）。
