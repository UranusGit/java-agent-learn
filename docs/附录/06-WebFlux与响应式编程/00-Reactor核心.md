# Reactor 核心：Mono / Flux 操作符全解

> 「本文是对 [教程 01-Spring-AI入门 §3] 和 [教程 09-SSE §2-§4] 的深入展开」

> **定位**：系统讲解 Reactor 的核心抽象 `Mono` 和 `Flux`，覆盖创建、变换、过滤、组合、错误处理、时间操作、背压等全部操作符类别，以及在 Spring AI 流式输出场景中的实战。
>
> **读者画像**：初次接触响应式编程，需要全面理解 Reactor 操作符体系的 Java 开发者。

---

## 1. Reactor 是什么

Reactor 是 Spring 全家桶的响应式编程基础库，也是 Spring WebFlux 和 Spring AI 2.0 的流式 API 基石。它实现了 Reactive Streams 规范（RP204），提供两个核心类型：

```mermaid
graph TB
    subgraph Reactor["Reactor 核心类型"]
        M["Mono&lt;T&gt;<br/>0 或 1 个元素的异步序列<br/>类似 CompletableFuture"]
        F["Flux&lt;T&gt;<br/>0 到 N 个元素的异步序列<br/>类似异步 Stream"]
    end

    subgraph 对比["与 Java 原生对比"]
        CF["CompletableFuture&lt;T&gt;"]
        ST["Stream&lt;T&gt;"]
        OL["Observable&lt;T&gt;<br/>（RxJava）"]
    end

    M -.类似.-> CF
    F -.类似.-> ST
    F -.类似.-> OL

    style Reactor fill:#e3f2fd
```

| 类型 | 元素数量 | 完成信号 | 对标 |
|------|---------|---------|------|
| `Mono<T>` | 0 或 1 | `onComplete` / `onError` | CompletableFuture |
| `Flux<T>` | 0 到 N | `onComplete` / `onError` | RxJava Observable |

关键理念：**Reactor 是声明式的**——你用操作符链描述数据流"应该怎么处理"，在 `subscribe()` 之前不会执行任何操作。

```java
// 声明式——此时什么都不会发生
Flux<String> pipeline = Flux.fromIterable(List.of("a", "b", "c"))
    .map(String::toUpperCase)
    .filter(s -> s.length() == 1)
    .doOnNext(s -> System.out.println("Processing: " + s));

// 订阅——此时开始执行
pipeline.subscribe();
```

---

## 2. Mono 操作符全解

### 2.1 创建 Mono

```java
// 1. 从已有值创建
Mono<String> m1 = Mono.just("Hello");

// 2. 空 Mono
Mono<Object> m2 = Mono.empty();

// 3. 错误 Mono
Mono<String> m3 = Mono.error(new RuntimeException("失败"));

// 4. 从 Supplier 延迟创建（订阅时才执行）
Mono<String> m4 = Mono.fromSupplier(() -> expensiveOperation());

// 5. 从 Callable
Mono<String> m5 = Mono.fromCallable(() -> blockingDbCall());

// 6. 从 Future
Mono<String> m6 = Mono.fromFuture(CompletableFuture.supplyAsync(() -> "result"));

// 7. 从另一个 Publisher
Mono<String> m7 = Mono.from(flux);  // 取 Flux 的第一个元素
```

### 2.2 Spring AI 中的 Mono

Spring AI 的 `ChatClient.call()` 返回 `Mono`（在 WebFlux 模式下）：

```java
Mono<String> response = chatClient.prompt()
    .user("你好")
    .call()
    .content();  // 返回 Mono<String>

// 等价于
Mono<ChatResponse> chatResponse = chatClient.prompt()
    .user("你好")
    .call()
    .chatResponse();
```

### 2.3 Mono 变换操作符

```java
// map：同步变换
Mono<Integer> length = Mono.just("Hello")
    .map(String::length);  // 5

// flatMap：异步变换（返回另一个 Mono）
Mono<String> enhanced = Mono.just("用户问题")
    .flatMap(question -> chatClient.prompt()  // 返回 Mono<String>
        .user(question)
        .call()
        .content()
    );

// handle：带条件的同步+异步混合
Mono<String> filtered = Mono.just("test")
    .handle((value, sink) -> {
        if (value.length() > 3) {
            sink.next(value.toUpperCase());
        } else {
            sink.complete();
        }
    });
```

```mermaid
graph LR
    subgraph 变换对比["map vs flatMap"]
        subgraph map["map：同步变换"]
            M1["Mono&lt;String&gt;<br/>「Hello」"]
            M1 -->|"map(String::length)"| M2["Mono&lt;Integer&gt;<br/>5"]
        end

        subgraph flatMap["flatMap：异步变换"]
            F1["Mono&lt;String&gt;<br/>「用户问题」"]
            F1 -->|"flatMap(q -> callLLM(q))"| F2["Mono&lt;String&gt;<br/>「LLM回复」"]
        end
    end

    style map fill:#e3f2fd
    style flatMap fill:#e8f5e9
```

### 2.4 Mono 错误处理

```java
Mono<String> safe = chatClient.prompt()
    .user("你好")
    .call()
    .content()
    // 超时
    .timeout(Duration.ofSeconds(30))
    // 出错时返回默认值
    .onErrorReturn("服务暂时不可用")
    // 出错时降级到备用 Mono
    .onErrorResume(e -> {
        if (e instanceof TimeoutException) {
            return fallbackService.call();  // 返回 Mono<String>
        }
        return Mono.error(e);  // 重新抛出其他错误
    })
    // 出错时重试
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(10))
        .jitter(0.5)
    )
    // 最终兜底
    .doOnError(e -> log.error("LLM调用失败", e))
    .doFinally(signal -> log.info("请求结束: {}", signal));
```

---

## 3. Flux 操作符全解

### 3.1 创建 Flux

```java
// 1. 从已知元素
Flux<Integer> f1 = Flux.just(1, 2, 3);
Flux<Integer> f2 = Flux.range(1, 100);         // 1 到 100
Flux<String> f3 = Flux.fromIterable(List.of("a", "b", "c"));
Flux<String> f4 = Flux.fromArray(new String[]{"x", "y", "z"});

// 2. 间隔创建
Flux<Long> f5 = Flux.interval(Duration.ofSeconds(1));  // 0, 1, 2, ... 每秒一个

// 3. 空 Flux
Flux<Object> f6 = Flux.empty();

// 4. 从 Supplier
Flux<String> f7 = Flux.generate(
    () -> 0,                                           // 初始状态
    (state, sink) -> {
        sink.next("Value " + state);                   // 发送元素
        if (state == 10) sink.complete();
        return state + 1;                              // 新状态
    }
);

// 5. 从 Stream
Flux<String> f8 = Flux.fromStream(() -> Files.lines(Path.of("file.txt")));

// 6. 合并多个源
Flux<String> f9 = Flux.concat(mono1, mono2);  // 顺序连接
```

### 3.2 Spring AI 中的 Flux

Spring AI 的 `ChatClient.stream()` 返回 `Flux`——LLM 流式输出的每个 token 作为一个元素：

```java
Flux<String> tokenStream = chatClient.prompt()
    .user("写一首关于春天的诗")
    .stream()
    .content();
// Flux<String>：每个元素是 LLM 生成的一个 chunk
// 例如：["春", "风", "又", "绿", "江", "南", "岸", ...]
```

### 3.3 Flux 变换操作符

```java
// === 基本变换 ===

// map：逐元素同步变换
Flux<Integer> lengths = Flux.just("hello", "world", "ai")
    .map(String::length);  // [5, 5, 2]

// flatMap：逐元素异步变换（无序）
Flux<String> results = Flux.just("q1", "q2", "q3")
    .flatMap(query -> callLlmAsync(query)  // 返回 Mono<String>
        .subscribeOn(Schedulers.parallel()),
        2  // 并发度：同时最多 2 个
    );

// concatMap：逐元素异步变换（有序，等待前一个完成）
Flux<String> ordered = Flux.just("q1", "q2", "q3")
    .concatMap(query -> callLlmAsync(query));

// flatMapSequential：异步但最终有序
Flux<String> fastOrdered = Flux.just("q1", "q2", "q3")
    .flatMapSequential(query -> callLlmAsync(query));
```

```mermaid
graph TB
    subgraph 三种映射["flatMap vs concatMap vs flatMapSequential"]
        direction TB
        subgraph FM["flatMap（并发，无序）"]
            FM_IN["输入: A, B, C"]
            FM_A["并发发出<br/>A→M_A, B→M_B, C→M_C"]
            FM_OUT["输出顺序不确定<br/>可能是 B, A, C"]
        end

        subgraph CM["concatMap（串行，有序）"]
            CM_IN["输入: A, B, C"]
            CM_A["A → 等完成"]
            CM_B["B → 等完成"]
            CM_C["C → 等完成"]
            CM_OUT["输出顺序: A, B, C（但慢）"]
        end

        subgraph FMS["flatMapSequential（并发，有序）"]
            FMS_IN["输入: A, B, C"]
            FMS_A["并发发出<br/>A→M_A, B→M_B, C→M_C"]
            FMS_BUF["缓冲结果<br/>按原始顺序排列"]
            FMS_OUT["输出顺序: A, B, C（快）"]
        end
    end

    style FM fill:#fff3e0
    style CM fill:#e3f2fd
    style FMS fill:#c8e6c9
```

### 3.4 Flux 过滤操作符

```java
// filter：条件过滤
Flux<Integer> evens = Flux.range(1, 10)
    .filter(n -> n % 2 == 0);  // [2, 4, 6, 8, 10]

// distinct：去重
Flux<String> unique = Flux.just("a", "b", "a", "c", "b")
    .distinct();  // ["a", "b", "c"]

// take：取前 N 个
Flux<Integer> first3 = Flux.range(1, 100)
    .take(3);  // [1, 2, 3]

// takeUntil：取到条件满足
Flux<Integer> taken = Flux.range(1, 100)
    .takeUntil(n -> n > 5);  // [1, 2, 3, 4, 5, 6]

// skip：跳过前 N 个
Flux<Integer> after2 = Flux.range(1, 10)
    .skip(2);  // [3, 4, 5, 6, 7, 8, 9, 10]

// next：只取第一个
Mono<Integer> first = Flux.range(1, 10)
    .next();  // Mono(1)

// last：只取最后一个
Mono<Integer> end = Flux.range(1, 10)
    .last();  // Mono(10)
```

### 3.5 Flux 组合操作符

```java
// merge：合并多个 Flux（交错，按时间顺序）
Flux<String> merged = Flux.merge(
    Flux.just("A1", "A2").delayElements(Duration.ofMillis(100)),
    Flux.just("B1", "B2").delayElements(Duration.ofMillis(50))
);
// 输出可能: B1, A1, B2, A2（按到达顺序）

// concat：顺序连接（等前一个完成）
Flux<String> concatenated = Flux.concat(
    Flux.just("A1", "A2"),
    Flux.just("B1", "B2")
);
// 输出: A1, A2, B1, B2

// zip：按位置配对
Flux<String> zipped = Flux.zip(
    Flux.just("A", "B", "C"),
    Flux.just("1", "2", "3"),
    (letter, number) -> letter + number
);
// 输出: A1, B2, C3

// combineLatest：任一更新就组合
Flux<String> latest = Flux.combineLatest(
    Flux.interval(Duration.ofMillis(100)).take(3),  // 0, 1, 2
    Flux.interval(Duration.ofMillis(150)).take(2),  // 0, 1
    (a, b) -> "a=" + a + ",b=" + b
);
// 每当任一源发出，就组合最新的值
```

### 3.6 SSE 流式输出中的组合实战

```java
// Agent 场景：合并 LLM 输出流 + 心跳流，保证连接不超时
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamWithHeartbeat(@RequestParam String query) {

    // LLM 输出流
    Flux<ServerSentEvent<String>> dataStream = chatClient.prompt()
        .user(query)
        .stream()
        .content()
        .map(chunk -> ServerSentEvent.<String>builder()
            .event("data")
            .data(chunk)
            .build());

    // 心跳流：每 15 秒发一个心跳
    Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
        .map(i -> ServerSentEvent.<String>builder()
            .event("heartbeat")
            .data("ping")
            .build());

    // 合并两个流
    return Flux.merge(dataStream, heartbeat)
        .takeUntilOther(completionSignal);  // 完成信号到来时停止
}
```

---

## 4. 时间与调度操作符

### 4.1 调度器（Scheduler）

```java
// Schedulers.parallel()：CPU 密集型，线程数 = CPU 核心数
// Schedulers.boundedElastic()：I/O 密集型，适合阻塞调用
// Schedulers.single()：单线程
// Schedulers.immediate()：当前线程

// publishOn：改变后续操作符的执行线程
Flux.range(1, 10)
    .publishOn(Schedulers.parallel())  // 后面的 map 在 parallel 线程执行
    .map(n -> transform(n))
    .publishOn(Schedulers.boundedElastic())  // 后面的操作在 boundedElastic
    .map(n -> blockingCall(n));

// subscribeOn：改变源的数据生产线程
Flux.fromCallable(() -> blockingDbCall())
    .subscribeOn(Schedulers.boundedElastic())  // 从源头就切换线程
    .map(result -> process(result));  // 也在 boundedElastic

// 在 Spring AI 中，LLM 调用通常是 I/O 操作
Mono<String> llmCall = Mono.fromCallable(() ->
        chatClient.prompt().user("query").call().content()
    )
    .subscribeOn(Schedulers.boundedElastic());
```

### 4.2 时间操作符

```java
// delayElements：每个元素延迟
Flux<String> delayed = Flux.just("A", "B", "C")
    .delayElements(Duration.ofSeconds(1));  // 每个元素延迟 1 秒

// interval：固定速率产生
Flux<Long> ticker = Flux.interval(Duration.ofSeconds(5));  // 每 5 秒产生一个

// timeout：超时
Mono<String> withTimeout = chatClient.prompt()
    .user("query")
    .call()
    .content()
    .timeout(Duration.ofSeconds(30))
    .onErrorResume(TimeoutException.class, e ->
        Mono.just("LLM 响应超时")
    );

// elapsed：附带时间戳
Flux<String> withTime = Flux.just("A", "B", "C")
    .elapsed()  // 变成 Flux<Tuple2<Long, String>>，Long 是距上一元素的毫秒数
    .map(t -> t.getT2() + " (+" + t.getT1() + "ms)");
```

---

## 5. 副作用操作符（Side Effects）

副作用操作符不改变数据流，用于"旁路"执行日志、缓存、指标等：

```java
Flux<String> pipeline = chatClient.prompt()
    .user("query")
    .stream()
    .content()
    // 每个元素到达时执行
    .doOnNext(chunk -> {
        log.debug("收到 chunk: {}", chunk);
        metricsCounter.increment();
    })
    // 流完成时执行
    .doOnComplete(() -> {
        log.info("LLM 输出完成");
    })
    // 流出错时执行
    .doOnError(e -> {
        log.error("LLM 输出错误", e);
        errorCounter.increment();
    })
    // 订阅时执行
    .doOnSubscribe(subscription -> {
        log.info("开始订阅 LLM 输出");
    })
    // 最终（无论成功失败）执行
    .doFinally(signal -> {
        log.info("流结束: {}", signal);  // ON_COMPLETE, ON_ERROR, CANCEL
        requestTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    });
```

```mermaid
graph LR
    subgraph 生命周期["Reactor 副作用操作符的时间线"]
        S1["doOnSubscribe<br/>订阅时"]
        S2["doOnNext<br/>每个元素"]
        S3["doOnComplete / doOnError<br/>正常结束/出错"]
        S4["doFinally<br/>最终（含取消）"]

        S1 --> S2 --> S3 --> S4
    end

    style 生命周期 fill:#e3f2fd
```

---

## 6. 背压（Backpressure）基础

Flux 的消费者可以通过 `request(n)` 控制上游的生产速率——这就是背压：

```java
// 自定义订阅者控制拉取速率
tokenStream.subscribe(new BaseSubscriber<String>() {
    @Override
    protected void hookOnSubscribe(Subscription subscription) {
        request(10);  // 初始请求 10 个
    }

    @Override
    protected void hookOnNext(String value) {
        process(value);
        request(5);  // 处理完一批再请求 5 个
    }
});

// onBackpressureBuffer：缓冲
Flux.range(1, 1000000)
    .onBackpressureBuffer(1000)  // 缓冲 1000 个
    .subscribe(...);

// onBackpressureDrop：丢弃
Flux.interval(Duration.ofMillis(1))
    .onBackpressureDrop(dropped -> log.warn("丢弃: {}", dropped))
    .subscribe(...);

// onBackpressureLatest：只保留最新
Flux.interval(Duration.ofMillis(10))
    .onBackpressureLatest()
    .subscribe(...);
```

> 背压的深入讨论见 [01-背压与流量控制](../06-WebFlux与响应式编程/01-背压与流量控制.md)。

---

## 7. 完整实战：Agent 流式推理管线

把所有操作符组合起来，构建一个生产级的 Agent 流式推理管线：

```java
@Service
public class AgentStreamingService {

    private final ChatClient chatClient;
    private final MeterRegistry meters;
    private final ChatMemory chatMemory;

    public Flux<ServerSentEvent<String>> stream(String sessionId, String userQuery) {
        long startTime = System.nanoTime();

        return chatClient.prompt()
            .system("你是一个专业的 AI 助手。")
            .user(userQuery)
            .advisors(a -> a.param("chatId", sessionId))  // 记忆 Advisor
            .stream()
            .content()
            // 副作用：日志 + 指标
            .doOnSubscribe(sub -> log.info("[{}] 开始流式推理", sessionId))
            .doOnNext(chunk -> meters.counter("agent.stream.tokens").increment())
            .doOnError(e -> {
                log.error("[{}] 流式推理失败", sessionId, e);
                meters.counter("agent.stream.errors").increment();
            })
            .doFinally(signal -> {
                long elapsed = System.nanoTime() - startTime;
                meters.timer("agent.stream.duration").record(elapsed, TimeUnit.NANOSECONDS);
                log.info("[{}] 流式推理结束: {} in {}ms", sessionId, signal,
                    elapsed / 1_000_000);
            })
            // 超时保护
            .timeout(Duration.ofSeconds(60))
            // 错误降级
            .onErrorResume(e -> Flux.just(
                "[错误] 推理过程出现问题，请重试: " + e.getMessage()
            ))
            // 合并心跳（防止代理超时断开）
            .mergeWith(
                Flux.interval(Duration.ofSeconds(15))
                    .map(i -> "❤")  // 心跳标记
                    .takeUntilOther(
                        Mono.delay(Duration.ofSeconds(60))  // 心跳最长发 60 秒
                    )
            )
            // 转为 SSE 格式
            .map(chunk -> {
                if ("❤".equals(chunk)) {
                    return ServerSentEvent.<String>builder()
                        .event("heartbeat")
                        .data("ping")
                        .build();
                } else {
                    return ServerSentEvent.<String>builder()
                        .event("data")
                        .data(chunk)
                        .build();
                }
            })
            // 完成事件
            .concatWith(
                Mono.just(ServerSentEvent.<String>builder()
                    .event("done")
                    .data("[DONE]")
                    .build())
            );
    }
}
```

```mermaid
graph TB
    subgraph 管线["Agent 流式推理管线"]
        direction LR
        S1["ChatClient.stream()<br/>LLM 输出流"]
        S2["doOnSubscribe<br/>记录开始"]
        S3["doOnNext<br/>计数 + 日志"]
        S4["timeout(60s)<br/>超时保护"]
        S5["onErrorResume<br/>错误降级"]
        S6["mergeWith(heartbeat)<br/>合并心跳"]
        S7["map → SSE<br/>格式转换"]
        S8["concatWith(done)<br/>结束标记"]
        S9["doFinally<br/>记录耗时"]
    end

    S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8 --> S9

    style 管线 fill:#e8f5e9
```

---

## 8. 常见陷阱

### 8.1 忘记 subscribe

```java
// 问题：声明了但没订阅，什么都不会发生
chatClient.prompt()
    .user("你好")
    .call()
    .content()
    .doOnNext(r -> log.info("收到: {}", r));
// 没有任何输出！需要 .subscribe()

// 修复：在 WebFlux Controller 中返回 Mono/Flux 即可，框架会自动订阅
@GetMapping("/ask")
public Mono<String> ask() {
    return chatClient.prompt()  // 框架负责 subscribe
        .user("你好")
        .call()
        .content();
}
```

### 8.2 在 flatMap 中调用 block()

```java
// 问题：在 reactive 链中调用 block() 会阻塞 Event Loop 线程
Flux.range(1, 10)
    .flatMap(n -> {
        String result = blockingCall(n);  // 阻塞调用
        return Mono.just(result);         // 包成 Mono 仍然阻塞
    });

// 修复：用 fromCallable + subscribeOn
Flux.range(1, 10)
    .flatMap(n -> Mono.fromCallable(() -> blockingCall(n))
        .subscribeOn(Schedulers.boundedElastic())  // 在专用线程池阻塞
    );
```

### 8.3 错误被吞没

```java
// 问题：onErrorReturn 会吞没所有错误信息
chatClient.call().content()
    .onErrorReturn("默认值");  // 你永远不知道为什么失败

// 修复：先记录再降级
chatClient.call().content()
    .doOnError(e -> log.error("调用失败", e))  // 记录错误
    .onErrorReturn("默认值");                   // 再降级
```

---

## 9. 总结

Reactor 是 Spring WebFlux 和 Spring AI 流式 API 的基石。掌握 Mono / Flux 的操作符体系，是开发高性能 Agent 应用的前提：

1. **Mono（0/1）**：用于单次 LLM 调用、数据获取
2. **Flux（0/N）**：用于 SSE 流式输出、批量处理、实时推送
3. **变换**：map（同步）、flatMap（异步无序）、concatMap（异步有序）
4. **组合**：merge（交错）、concat（顺序）、zip（配对）
5. **错误处理**：onErrorResume、retryWhen、timeout 组合使用
6. **副作用**：doOnNext/doOnComplete/doFinally 用于日志和指标
7. **调度器**：publishOn 切换下游线程，subscribeOn 切换上游线程

Spring AI 2.0 的 `ChatClient.stream()` 返回 `Flux<String>`，所有流式 Agent 交互——SSE 推送、多 Agent 并行、工具流式调用——都建立在这些操作符之上。
