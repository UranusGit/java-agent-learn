# Kafka 专题（学习顺序）

本文件夹是 **Apache Kafka** 系列。Spring Cloud Stream 用 Kafka Binder 时底层就是 Kafka，理解 Kafka 才能真正调优。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **01** | [Kafka 核心概念与 Spring Boot 实战](./01-Kafka核心概念与SpringBoot实战.md) | **先读**。Kafka 是什么、topic/分区/offset/消费组/副本、Spring Boot 怎么用——地基 |
| **02** | [Kafka 可视化工具推荐](./02-Kafka可视化工具推荐.md) | 用图形工具观察消息、调试 topic——开发排障利器 |
| **03** | [Conduktor 纳管 Kafka 部署手册](./03-Conduktor纳管Kafka部署手册.md) | 用 Conduktor 纳管 Kafka 集群——进阶运维 |

> **建议**：01 是必读地基（配合 [Spring Cloud Stream 进阶](../Spring-Cloud-Stream专题/02-Spring-Cloud-Stream进阶实战.md) 第 1 章一起看）。02/03 按需——需要调优/运维时再读。
