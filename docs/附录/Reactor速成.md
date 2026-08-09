# 附录：Reactor 速成

> 响应式编程卡壳时来这补基础。Spring AI 流式输出全靠 Reactor。

## 核心概念

| 概念 | 解释 | Java 类比 |
|------|------|---------|
| **Mono\<T\>** | 0 或 1 个元素的异步序列 | CompletableFuture\<T\> |
| **Flux\<T\>** | 0 到 N 个元素的异步序列 | 异步版 Stream |
| **subscribe** | 触发执行（Flux 是惰性的） | — |
| **map** | 转换元素 | Stream.map |
| **filter** | 过滤元素 | Stream.filter |
| **flatMap** | 异步转换 | — |
| **doOnNext** | 对每个元素做副作用 | peek |

## 流式输出中的 Flux

```java
// Spring AI 流式调用返回 Flux<String>
Flux<String> tokens = chatClient.prompt().user("你好").stream().content();

tokens
    .doOnNext(token -> System.out.print(token))  // 每来一个 token 打印
    .doOnError(e -> System.err.println("出错：" + e))
    .doOnComplete(() -> System.out.println("\n完成"))
    .blockLast();  // 阻塞等待全部完成
```

## 常用操作符

```java
Flux.range(1, 10)               // 1,2,3,...,10
    .filter(n -> n % 2 == 0)    // 2,4,6,8,10
    .map(n -> n * n)            // 4,16,36,64,100
    .reduce(0, Integer::sum)    // 220
    .subscribe(System.out::println);
```

## 相关文档
- 流式输出：`阶段2-核心能力/05-流式输出.md`
