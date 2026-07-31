# Reactor 背压（Backpressure）详解

> **配套文档**：[35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 3 章用了 `Sinks.many().multicast().onBackpressureBuffer()`，现有附录 [Reactor Sinks入门](./03-Reactor-Sinks入门.md) 也反复提到"背压"。但"背压"到底是什么、为什么需要、Reactor 怎么处理，一直没系统讲。本篇补上这个认知缺口。
>
> **难度假设**：你用过 `Flux`/`Mono`，能写 `.map().flatMap()`，但不清楚"背压""下游需求""onBackpressureBuffer"这些词的确切含义。

---

## 第 1 章：什么是背压——从一个比喻开始

### 1.1 漏斗比喻

想象一个漏斗：上面倒水（生产者），下面出水（消费者）。

- 如果**倒得慢、出得快**：没事，水不积压。
- 如果**倒得快、出得慢**：水会在漏斗里**越积越多**，最后溢出。

**"背压"（Backpressure）就是消费者反过来告诉生产者："我处理不过来了，你慢点 / 别发了 / 先存着"。** 像消费者伸手"按住"生产者，给它一个反向的压力——back pressure。

### 1.2 响应式流里的背压

Reactor 是**响应式流（Reactive Streams）规范**的实现。响应式流的核心思想是**推-拉结合**：

- 传统推模式（如 `Iterable`/回调）：生产者不管消费者，使劲推，消费者处理不过来就崩/丢。
- 响应式流：**消费者按自己的能力"要"多少（request(n)），生产者才推多少**。消费者不要，生产者不推（或按策略缓冲）。

**背压 = 消费者对生产者速率的控制机制。**

```
生产者 ──推数据──→ 消费者
消费者 ──request(n)──→ 生产者   ← 这就是背压："给我 n 个"
```

---

## 第 2 章：为什么需要背压

### 2.1 没有背压会怎样

经典场景：一个快速数据源（如 Kafka、文件读取、定时器）配一个慢消费者（如写数据库、调外部 API）。

```java
// 危险：interval 每 1ms 发一个，但 writeDb 要 100ms
Flux.interval(Duration.ofMillis(1))
    .flatMap(i -> writeDb(i))   // writeDb 慢
    .subscribe();
```

没有背压控制的话，`interval` 会持续高速发，`flatMap` 把它们摊开（默认并发 256），内存里堆满待写的任务，最终 **OOM**。

### 2.2 背压怎么救

消费者告诉生产者"我一次只能处理这么多"，生产者就按需发。这样快的源也会被"拉"慢到消费者的节奏，不积压。

> **注意**：背压能不能传到最上游，取决于**源是否支持背压**。
> - `Flux.interval`、`Flux.fromIterable` 支持——能被拉慢。
> - 但 `Sinks.Many.tryEmitNext`（命令式塞数据）**不完全受背压约束**——你主动塞，它不一定能"挡住"你。这就引出第 4 章的缓冲/丢弃策略。

---

## 第 3 章：Reactor 里的背压怎么工作

### 3.1 request(n) ——消费者"要"数据

响应式流规范里，每个订阅关联一个 `Subscription`，消费者调 `subscription.request(n)` 表示"给我 n 个"。Reactor 内部自动管理这个——你写 `.subscribe()` 时，它默认请求 `Long.MAX_VALUE`（无限制，等于不要背压）。

```java
// 等价于：无限请求（无背压限制）
flux.subscribe();

// 手动控制（很少直接写，了解原理即可）
flux.subscribe(new BaseSubscriber<String>() {
    @Override
    protected void hookOnSubscribe(Subscription s) {
        request(5);   // 只要 5 个
    }
    @Override
    protected void hookOnNext(String value) {
        System.out.println(value);
        request(5);   // 处理完再要 5 个
    }
});
```

### 3.2 操作符的并发度 = 一种背压控制

`flatMap` 默认并发 256——意味着最多同时 256 个内部流。这本身是"控制下游积压"的手段：

```java
flux.flatMap(i -> slowCall(i), 4)   // 第 2 参数：并发度限制为 4
```

把并发度调小，就是限制同时处理的量，防止下游打爆。**这是实践中最常用的"背压调节"。**

### 3.3 onBackpressureXxx ——源不支持背压时的策略

当源是命令式的（如 Sinks）或热流（如 `share()`），背压传不上去，消费者跟不上时怎么办？Reactor 提供几种策略：

```java
// 1. 缓冲：存起来等消费者慢慢消费（默认有界缓冲，满了触发后面的行为）
flux.onBackpressureBuffer(1000);

// 2. 丢弃：消费者跟不上的直接丢
flux.onBackpressureDrop(i -> log.warn("丢弃: " + i));

// 3. 只留最新：只保留最新的一个，之前的丢
flux.onBackpressureLatest();
```

---

## 第 4 章：Sinks 与背压（管数分离文档的关键）

### 4.1 Sinks 为什么特殊

`Sinks.Many` 是你**命令式**地 `tryEmitNext` 往里塞——它不像 `interval` 那样可以被"拉慢"。**你塞多少它就收多少**，至于下游能不能及时消费，取决于 Sink 的类型：

```java
// multicast + onBackpressureBuffer：每个订阅者各自缓冲
Sinks.many().multicast().onBackpressureBuffer();
```

- `multicast`：支持多个订阅者。
- `onBackpressureBuffer`：如果某个订阅者消费慢，**给这个订阅者缓冲**待发数据。

> **管数分离文档第 3 章为什么用它**：生成器往 Sink 塞字，多个 SSE 订阅者读。某个订阅者（比如网络慢的设备）读得慢，`onBackpressureBuffer` 帮它缓冲，不至于丢字。

### 4.2 tryEmitNext 的返回值

```java
EmitResult result = sink.tryEmitNext(chunk);
switch (result) {
    case OK:               // 成功
        break;
    case FAIL_OVERFLOW:    // 缓冲满了！
        // 背压策略决策：丢？扩容？报错？
        break;
    case FAIL_TERMINATED:  // Sink 已完成/取消
        break;
    case FAIL_CANCELLED:   // 订阅者都取消了
        break;
}
```

> **`FAIL_OVERFLOW` 就是背压信号**——缓冲满了，塞不进去了。生产者要决定怎么办：丢、扩容、或阻塞等待。简单场景忽略返回值（默认行为是丢弃），但严格场景要处理。

---

## 第 5 章：背压实战策略

### 5.1 控制生产者速率（最根本）

如果生产者可拉慢（如 `interval`、`fromIterable`），直接 `flatMap` 限并发，或用 `limitRate`：

```java
flux.limitRate(100)   // 每次向上游 request 100 个
    .flatMap(i -> slowCall(i));
```

### 5.2 缓冲（onBackpressureBuffer）

消费者偶尔慢、但总体能跟上时，缓冲吸收峰值：

```java
flux.onBackpressureBuffer(1000, BufferOverflowStrategy.BLOCK);
// 策略：BLOCK(阻塞) / DROP_OLDEST(丢最老) / DROP_LATEST(丢最新)
```

### 5.3 丢弃（onBackpressureDrop）

数据没那么重要（如心跳、采样），跟不上就丢：

```java
flux.onBackpressureDrop(i -> metrics.dropped.increment());
```

### 5.4 只留最新（onBackpressureLatest）

只要最新值（如实时仪表盘），旧的没意义：

```java
flux.onBackpressureLatest();
```

### 5.5 真实场景：管数分离的 chunk 流

管数分离文档的 chunk 流其实**几乎不担心背压**，因为：

- 生成器每 100ms 吐一个字——**很慢**。
- SSE 推给浏览器——浏览器消费极快。
- 生产远慢于消费，不会积压。

**但一旦换成真 LLM**（可能 100ms 吐一个 token，多 run 并发），或下游是慢存储，就要考虑背压了。这时 `onBackpressureBuffer(大小)` + 处理 `FAIL_OVERFLOW` 是正解。

---

## 第 6 章：常见坑

### 坑 1：以为有 Flux 就自动有背压

**真相**：背压能否生效取决于**源**。命令式源（Sinks、callback 包装）不受背压约束，要手动用 `onBackpressureXxx`。

### 坑 2：flatMap 默认并发 256 导致下游打爆

**解决**：`flatMap(fn, 并发度)` 限制并发，或 `concatMap`（串行，并发 1）。

### 坑 3：onBackpressureBuffer 无界，OOM

**解决**：始终传容量 `onBackpressureBuffer(1000)`，别用无界版本。

### 坑 4：忽略 tryEmitNext 的返回值，数据默默丢失

**解决**：严格场景检查 `EmitResult`，尤其 `FAIL_OVERFLOW`。

### 坑 5：误用 `subscribeOn`/`publishOn` 以为能限流

**真相**：这两个控制的是**线程**，不是速率。限流用 `flatMap` 并发度或 `limitRate`。

---

## 总结

- **背压 = 消费者控制生产者速率**，防止快生产/慢消费导致的积压/OOM。
- **响应式流**：消费者 `request(n)`，生产者按需推。Reactor 内部自动管理。
- **源是否支持背压**：`interval`/`fromIterable` 支持（可拉慢）；`Sinks`（命令式塞）不完全支持，需 `onBackpressureXxx`。
- **实战策略**：限并发（`flatMap(fn,n)`）、缓冲、丢弃、只留最新。
- **Sinks**：`onBackpressureBuffer` 给慢订阅者缓冲；检查 `tryEmitNext` 返回值的 `FAIL_OVERFLOW`。

学完本篇，回头看 [管数分离文档第 3 章](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的 `onBackpressureBuffer` 和 [Reactor Sinks入门](./03-Reactor-Sinks入门.md) 里的"背压"讨论，就真正理解了。
