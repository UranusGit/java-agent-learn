# Reactor Sinks 入门——事件总线的底层心脏

> **33b 文档大量使用 `Sinks.Many`**（AgentEventBus、ShardedEventBus、EventSequencer 都靠它），但没系统讲过 Sinks 是什么。这份文档补上，让你理解"热流"在做什么。

## 什么是 Sink？

**Sink 是把"命令式代码"桥接到"响应式流"的入口**。正常的 Flux 由数据源（DB 查询、HTTP 响应、定时器）**被动**驱动——你订阅它，它从源头拉数据。但事件总线场景是**外部代码主动发事件**进流——Sink 就是那个"从外面往里塞数据"的入口。

```
传统 Flux：  [数据源] ──推──→ [订阅者]              （数据源主动推）
Sink Flux：  [代码調 emit] ──Sink──→ [Flux] ──→ [订阅者]  （你代码主动推）
```

33b 的 `AgentEventBus` 就是典型：`emit()` 调 `sink.tryEmitNext(event)` 塞事件，SseController 订阅拿到 Flux。**一个塞、一个接，Sink 是中间的桥**。

---

## Sinks.Many 的三大变体

`Sinks.Many` 推送多个元素。创建时选"新订阅者能看到什么历史"——这是最核心的决策：

| 变体 | 行为 | 典型场景 |
|------|------|---------|
| `Sinks.many().multicast()` | 新订阅者只收**订阅之后**的事件（历史不缓存） | 33b AgentEventBus——重连走 Redis 回放不靠 sink 内存 |
| `Sinks.many().replay().all()` | 新订阅者收到**全部历史**事件 | 审计消费者——想看到全部历史 |
| `Sinks.many().replay().limit(N)` | 新订阅者收到**最近 N 条**历史 | 心跳监控——只看最近状态 |
| `Sinks.many().unicast()` | 只允许**一个**订阅者（多了抛异常） | 专用流（33b 不用） |

**33b 为什么选 `multicast`**：第 1 章明确说"热流不缓存历史，第 2 章关键事件落 Redis 兜底"。如果选 `replay().all()`，内存缓存所有历史—量大了爆内存。所以不用 replay，用 Redis 做可靠的持久化回放。

---

## onBackpressureBuffer——消费者太慢时

```java
Sinks.many().multicast().onBackpressureBuffer(256, false)
```

- **256**：缓冲大小。消费者来不及处理时事件先排队，最多 256 条。超了 `tryEmitNext` 返回 `FAIL_OVERFLOW`（不阻塞生产者）。
- **false**：`autoCancel`——没人订阅时 sink 是否自动关闭。`false` = 即使零订阅者，sink 也保持打开。

> **33b 第 2 章事故 1**：高峰期缓冲满了，`SESSION_COMPLETED` 被丢，前端永远转圈。解法是关键事件落 Redis 兜底（不是简单加大 buffer）。

---

## tryEmitNext vs emitNext

| 方法 | 失败行为 | 33b 用法 |
|------|---------|---------|
| `tryEmitNext(event)` | 返回 `EmitResult`，**不抛异常** | ✅ AgentEventBus 用这个 |
| `emitNext(event, failureHandler)` | 失败时调 failureHandler，可抛可容 | 需要自定义策略时用 |

`tryEmitNext` 的返回值应该处理——至少检查 `FAIL_OVERFLOW`：

```java
EmitResult r = sink.tryEmitNext(event);
if (r == EmitResult.FAIL_OVERFLOW) {
    log.warn("缓冲满，丢弃事件: {}", event);
}
```

### EmitResult 枚举

| 返回值 | 含义 |
|--------|------|
| `OK` | 成功 |
| `FAIL_OVERFLOW` | 缓冲满了（背压溢出） |
| `FAIL_TERMINATED` | sink 已关闭 |
| `FAIL_CANCELLED` | 下游已取消 |
| `FAIL_ZERO_SUBSCRIBER` | 无人订阅且 autoCancel=true 时 sink 关了 |

---

## Sinks.One / Sinks.Empty——单值场景

`Sinks.One<T>` 对应 `Mono<T>`（发 0 或 1 个值后完成）：

```java
Sinks.One<String> sink = Sinks.one();
sink.tryEmitValue("hello");   // 发值
// sink.tryEmitEmpty();       // 也可以发空
Mono<String> m = sink.asMono();
m.subscribe(System.out::println);  // 输出 hello
```

33b 的 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 底层类似这种模式——把同步阻塞调用包成一个 Mono，在弹性线程跑。

---

## 和 FluxSink 的区别

`Flux.create(sink -> { ... sink.next(chunk) })` 里的 `FluxSink` 是另一种 sink——它**绑定在一个 `Flux.create` 里**，流结束自动销毁。`Sinks.Many` **独立于订阅**，所有订阅者共享：

| | `FluxSink`（33b 旧版） | `Sinks.Many`（AgentEventBus） |
|---|---|---|
| 生命周期 | 随 `Flux.create` 订阅 | 全局单例（`@Component`） |
| 多消费者 | ❌ 不能（每订阅重建） | ✅ 多个共享 |
| 时序 | 冷流（订阅才执行） | 热流（不受订阅影响） |
| 33b 作用 | 被淘汰（LlmStepExecutor 用 Flux.concat） | 架构地基 |

---

## 实践验证：试一下 Sinks 的行为

```java
// multicast：先发后订——收不到历史
Sinks.Many<String> s = Sinks.many().multicast().onBackpressureBuffer(16, false);
s.tryEmitNext("A");
s.tryEmitNext("B");
s.asFlux().subscribe(System.out::println);  // 订阅晚于 A、B，收不到
s.tryEmitNext("C");  // 只输出 C
```

换成 `replay().all()` 再试——A、B、C 全收到。这就是热流不缓存历史的直观体验。

```java
// replay().limit(2)：只保留最近 2 条
Sinks.Many<String> s2 = Sinks.many().replay().limit(2);
s2.tryEmitNext("A"); s2.tryEmitNext("B"); s2.tryEmitNext("C");
s2.asFlux().subscribe(System.out::println);  // 输出 B、C（最近 2 条）
s2.tryEmitNext("D");  // 输出 D
```

---

## 和 33b 文档的对照

| 33b 章节 | 用法 |
|---------|------|
| 第 1 章 AgentEventBus | `Sinks.many().multicast().onBackpressureBuffer(256, false)`——核心总线 |
| 第 2 章事故 1 | 缓冲满丢事件 → 关键事件落 Redis 兜底 |
| 第 4 章 ShardedEventBus | 16 个 Sinks.Many，按 sessionId hash 路由 |
| 第 4 章 EventSequencer | 不直接用 Sink，但 `bufferTimeout` 和背压同理 |

---

## 推荐阅读

- [Reactor 官方 Sinks 文档](https://projectreactor.io/docs/core/release/reference/#sinks)
- [33b-Agent可观测性企业级演进实践](../tutorials/spring-ai-2.0/33b-Agent可观测性企业级演进实践.md) —— Sinks 实际运用场景
- [Flux方法速查](./Flux方法速查.md) —— 配套的 Reactor 操作符参考
