# Reactor 响应式编程（学习顺序）

本文件夹是 **Reactor（Project Reactor）响应式编程**系列，是理解整个仓库 WebFlux / 流式代码的地基。**按编号顺序读**。

| 顺序 | 文档 | 你将学到 |
|:---:|------|---------|
| **01** | [Reactor 响应式入门](./01-Reactor响应式入门.md) | **最先读**。为什么用响应式？`Mono`/`Flux` 是什么？冷流热流、订阅机制——地基中的地基 |
| **02** | [Flux 方法速查](./02-Flux方法速查.md) | 操作符手册（map/flatMap/filter/do系列/onError 系列），读完 01 后随时查 |
| **03** | [Reactor Sinks 入门](./03-Reactor-Sinks入门.md) | 从外部塞数据进响应式世界、做多消费者广播（事件总线模式） |
| **04** | [Reactor 背压详解](./04-Reactor背压详解.md) | 消费太慢怎么办——背压机制、`onBackpressureBuffer` 原理 |
| **05** | [Reactor-AI 流式核心模式](./05-Reactor-AI流式核心模式.md) | 把前面拼起来——AI 流式架构里的 `defer`/`concatWith`/`takeUntil` 实战模式 |

> **建议节奏**：01→02 是必读地基；03/04 按需；05 在你看完 [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 后回来读，会有顿悟感。
