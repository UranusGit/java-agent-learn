# 协议与数据库（学习顺序）

本文件夹收录 **横切性的底层知识**：传输协议（SSE）和数据一致性（事务）。它们不属于某个中间件，但贯穿整个系统。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **01** | SSE 协议详解 | Server-Sent Events 协议——`id`/`event`/`Last-Event-ID`/心跳/代理穿透，流式推送的底层 |
| **02** | 数据库事务与 @Transactional 详解 | ACID、传播行为、回滚规则、失效场景——数据一致性的兜底（事件驱动里幂等表要用事务） |

> **关联**：SSE 对应 **管数分离实战（从 Sinks 到 Kafka 演进）** 全程的流式推送；事务对应其中持久化章节的 `@Transactional` 幂等，以及事件驱动架构中"同一事务写业务数据 + 幂等表、靠事务保证原子提交"的实践。

**横切关系**：

```mermaid
flowchart LR
    subgraph PROTO[传输协议]
        SSE["01-SSE 协议详解<br/>id/event/Last-Event-ID/心跳/代理穿透"]
    end
    subgraph TX[数据一致性]
        TXN["02-事务与 @Transactional<br/>ACID/传播行为/回滚规则/失效场景"]
    end
    SSE -->|"流式推送底层"| DOC35["管数分离实战<br/>全程流式推送"]
    TXN -->|"事务幂等"| PERSIST["持久化章节<br/>@Transactional 幂等"]
    TXN -->|"事务幂等"| EVT["事件驱动架构<br/>事务幂等实践"]
```
