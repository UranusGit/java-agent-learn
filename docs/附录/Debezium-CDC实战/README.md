# Debezium CDC 实战专题

本专题讲 **CDC（变更数据捕获）**——用 Debezium 监听数据库变更日志，自动把数据变更变成事件流。是 [Stream 生产级进阶 方向 A（Outbox）](../Spring-Cloud-Stream专题/04-生产级进阶-Outbox与Schema与分区调优.md) 的 CDC 投递落地。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **01** | [Debezium CDC 实战](./01-Debezium-CDC实战.md) | CDC 原理；PG 逻辑解码；Kafka Connect + Debezium 搭建；SMT 消息转换；Outbox+CDC 落地；轮询 vs CDC 取舍 |

> **前置**：[Stream 生产级进阶 方向 A（Outbox 模式）](../Spring-Cloud-Stream专题/04-生产级进阶-Outbox与Schema与分区调优.md)（CDC 是 Outbox 投递的进阶方式）+ [Docker 入门](../Docker与工具/01-Docker与Docker-Compose入门.md)（搭建用到 docker-compose）。
>
> **适合谁**：Outbox 模式要做到毫秒级低延迟、或要做 DB→搜索/缓存/数仓数据同步的场景。中小项目用轮询即可，规模到了再上 CDC。
