# Spring Cloud Stream 专题（学习顺序）

本文件夹是 **Spring Cloud Stream（消息中间件抽象框架）** 的完整学习路径，从零基础到能独立设计事件驱动系统。**严格按 01→02→03 顺序读**，这是一个精心设计的能力阶梯。

| 顺序 | 文档 | 你将达到的水平 |
|:---:|------|--------------|
| **01** | [Spring Cloud Stream 从入门到架构师](./01-Spring-Cloud-Stream从入门到架构师.md) | **会发会收**。函数式模型、Binder/Binding/Destination、Supplier/Consumer/StreamBridge、消费组、重试、死信——"会用" |
| **02** | [Spring Cloud Stream 进阶实战](./02-Spring-Cloud-Stream进阶实战.md) | **懂原理懂设计**。Kafka 地基（topic/分区/offset/消费组）、生产调优、Kafka Streams 流式计算、响应式废弃真相、事件驱动架构（EDA） |
| **03** | [事件驱动微服务端到端实战](./03-事件驱动微服务端到端实战.md) | **会做系统**。把前两篇的知识装成一个能跑的事件驱动微服务（订单/库存/支付），落地 Saga 补偿事务、幂等表、消费组隔离 |
| **04** | [生产级进阶：Outbox 与 Schema 与分区调优](./04-生产级进阶-Outbox与Schema与分区调优.md) | **做成生产级**。Outbox 模式（写DB+发事件原子化）、Schema Registry（事件版本演进）、分区深度调优（并发/顺序/热分区） |

> **四篇的递进关系**：01 讲"零件"（一个个 API）→ 02 讲"原理和设计思维"（为什么这么做）→ 03 讲"装一辆能跑的车"（组装成系统）→ 04 讲"做成生产级"（Outbox/Schema/分区）。**不要跳读**，后面依赖前面。
>
> **前置**：建议先有 [Reactor 响应式入门](../Reactor响应式编程/01-Reactor响应式入门.md) 的基础（响应式函数要用 `Flux`）。

---

## 动手实践：敲一遍每个 API

读完上面 01-04 的理论，想**把 Stream 的每个知识点都动手敲一遍**？这份文档用代码走完 Stream 全部能力——Consumer/Function/Supplier、StreamBridge、消费组、重试死信、多 binder、函数组合、响应式、分区、Schema Registry，每章一段可跑代码 + 验证方法：

➡️ **[Spring Cloud Stream 全知识点实践项目](./05-全知识点实践项目.md)**

它是 01-04 的**动手实践版**：理论在专题里，代码在这里。全部练完，对 Stream 就是"既懂原理、又敲过每一行代码"的熟练状态。
