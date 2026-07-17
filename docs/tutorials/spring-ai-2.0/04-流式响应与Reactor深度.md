# 04 流式响应与 Reactor 深度

> 本文合并自原 11「复现手册-流式与工具调用」+ 原 26「流式 Reactor 深度」。
>
> 一篇搞定：流式响应怎么用、流式 + 工具调用怎么打通、Reactor 在 LLM 场景的所有操作符。
>
> 前置：[`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) + [`./03-Advisor链全解.md`](./03-Advisor链全解.md)
> 预计：1.5 天

---

## 0. 三句话讲清楚原理

1. **自动模式**：`ChatClient` 默认自动注册 `ToolCallingAdvisor`（`Ordered.HIGHEST_PRECEDENCE + 300`），无论 `.call()` 还是 `.stream()` 都透明支持工具调用循环。
2. **流式中的关键坑**：LLM 的一次 tool call 在流式响应里会被切成很多 chunk，**单看一个 chunk 看不到完整 tool call**。必须先聚合再判断（用 `ChatClientMessageAggregator`）。
3. **手动模式**：当你想完全控制"几轮 tool 调用就停"或"每轮换不同 model options"时，关掉自动注册，自己写 `while` 循环。

---

## Part A. 流式响应基础

### A.1 三种粒度

```java
// 1. 完整的 ChatClientResponse（含元数据）
Flux<ChatClientResponse> flux1 = chatClient.prompt()
        .user("hi")
        .stream()
        .chatClientResponse();

// 2. 只要 ChatResponse
Flux<ChatResponse> flux2 = chatClient.prompt()
        .user("hi")
        .stream()
        .chatResponse();

// 3. 只要文本 content（最常用）
Flux<String> flux3 = chatClient.prompt()
        .user("hi")
        .stream()
        .content();
```

### A.2 SSE Controller

```java
@GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamSse(@RequestParam String q) {
    return chatClient.prompt().user(q).stream().content()
            .map(chunk -> ServerSentEvent.<String>builder()
                    .event("delta")
                    .data(chunk)
                    .build())
            .concatWith(Flux.just(ServerSentEvent.<String>builder()
                    .event("done")
                    .data("[DONE]")
                    .build()))
            .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(e.getMessage())
                    .build()));
}
```

### A.3 Cold vs Hot Flux

**Cold Flux**：每个订阅者都会从头开始（再调一次 LLM！）。

```java
// ❌ 反模式：两次订阅会调两次 LLM
Flux<ChatClientResponse> flux = chatClient.prompt().user("hi").stream().chatClientResponse();
flux.subscribe(...);
flux.subscribe(...);
```

**结论**：业务里一个请求一个流，不要复用。

---

## Part B. 流式 + 工具调用实战复现

### B.1 项目依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

### B.2 application.yaml

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com   # 兼容 OpenAI 协议
      chat:
        model: deepseek-chat
```

### B.3 最小工具

```java
@Component
public class TimeTools {
    @Tool(description = "获取服务器当前时间")
    public String currentTime() {
        return new Date().toString();
    }
}
```

### B.4 ChatConfig 装配

```java
@Configuration
public class ChatConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    @Bean
    public ToolCallingAdvisor toolCallingAdvisor(ToolCallingManager mgr) {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(mgr)
                .advisorOrder(100)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  ChatMemory memory,
                                  ToolCallingAdvisor toolCallingAdvisor,
                                  TimeTools timeTools) {
        return builder
                .defaultSystem("你是一个友好的助手")
                .defaultTools(timeTools)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).order(0).build(),
                        toolCallingAdvisor
                )
                .build();
    }
}
```

### B.5 Controller

```java
@RestController
@RequestMapping("/demo02")
public class StreamController {

    private final ChatClient chatClient;

    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String prompt,
                                    @RequestParam String sessionId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
```

### B.6 验证

```bash
curl -N "http://127.0.0.1:8080/demo02/chat-stream?prompt=现在几点了&sessionId=aaaa"
```

预期：流式输出当前时间。

---

## Part C. 复现：两个真实的坑（2026-07-17 实战）

### C.1 坑 1：自定义 ChatClient 下 ToolCallingAdvisor 不自动注册

**症状**：自定义 `@Bean ChatClient` 后，`.stream()` 模式下 LLM 完全不调工具。

**根因**：`ChatClientAutoConfiguration` 的 `ToolCallingAdvisor` 自动注册是 `@ConditionalOnMissingBean(ChatClient.class)`。一旦你写了 `@Bean ChatClient`，自动注册被短路。

**修复**：手动注册 `ToolCallingAdvisor` Bean 并加入 `defaultAdvisors(...)`：

```java
@Bean
public ToolCallingAdvisor toolCallingAdvisor(ToolCallingManager mgr) {
    return ToolCallingAdvisor.builder()
            .toolCallingManager(mgr)
            .build();
}

@Bean
public ChatClient chatClient(ChatClient.Builder builder,
                              ToolCallingAdvisor toolCallingAdvisor,
                              ...) {
    return builder
            .defaultAdvisors(memoryAdvisor, toolCallingAdvisor)
            .build();
}
```

### C.2 坑 2：流式下 conversationId cannot be null NPE

**症状**：修复坑 1 后，请求返回 500，日志有 `conversationId cannot be null`。

**根因**：Advisor 顺序问题。Memory Advisor 在 `after()` 阶段要写回 history，需要 `CONVERSATION_ID`。如果 Memory 在 Tool 内层，工具循环时 context 已经被剥离。

**修复**：调整 order，让 Memory 在外、Tool 在内：

```java
.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(memory).order(0).build(),   // 外
    toolCallingAdvisor                                           // 内（order=100）
)
```

### C.3 验证结果

修复后，curl 流式调用成功，工具正常被调用：

```
Fri Jul 17 01:09:15 CST 2026
```

模型回答："现在服务器时间是凌晨 01:09"。

---

## Part D. Reactor 在 LLM 流里的常用操作符

### D.1 map / filter / scan

```java
chatClient.prompt().user("写首诗").stream().content()
        .filter(chunk -> !chunk.isBlank())
        .map(chunk -> "[chunk] " + chunk)
        .scan("", (acc, chunk) -> acc + chunk)   // 滚动聚合
        .doOnNext(System.out::print)
        .blockLast();
```

### D.2 buffer / reduce / collectList

```java
// 每 10 个 chunk 一组
chatClient.prompt().user("...").stream().content()
        .buffer(10)
        .map(chunks -> String.join("", chunks));

// 流结束拿完整结果
String full = chatClient.prompt().user("...").stream().content()
        .reduce("", String::concat)
        .block();
```

### D.3 flatMap vs concatMap vs switchMap

```java
// flatMap：并发处理，不保序
flux.flatMap(chunk -> asyncTranslate(chunk), 10);   // 并发 10

// concatMap：保序，串行
flux.concatMap(chunk -> asyncTranslate(chunk));

// switchMap：新值来取消上一次订阅（用于"用户输入变化时取消"）
userInputFlux.switchMap(input -> chatClient.prompt().user(input).stream().content());
```

### D.4 错误处理：onErrorResume / retry / onErrorReturn

```java
chatClient.prompt().user("hi").stream().content()
        .timeout(Duration.ofSeconds(30))   // 30 秒没新 chunk 就报错
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .filter(e -> e instanceof WebResponseException
                        && ((WebResponseException) e).getStatusCode() == HttpStatus.TOO_MANY_REQUESTS))
        .onErrorResume(e -> {
            log.warn("Falling back", e);
            return fallbackClient.prompt().user("hi").stream().content();
        })
        .onErrorReturn("服务暂时不可用");
```

**关键**：`retryWhen` 的 filter 决定哪些错误才重试 —— 不要 retry 4xx（永久错误）。

### D.5 timeout vs take

```java
// timeout：两个 chunk 之间的间隔超时
flux.timeout(Duration.ofSeconds(30));

// take：整个流的时长上限
flux.take(Duration.ofSeconds(60));

// 两者结合用
flux.timeout(Duration.ofSeconds(30))
    .take(Duration.ofSeconds(60));
```

### D.6 背压

```java
flux.onBackpressureBuffer();                          // 缓冲所有（默认）
flux.onBackpressureBuffer(100, DROP_OLDEST);          // 缓冲上限
flux.onBackpressureDrop();                            // 消费者跟不上就丢
flux.onBackpressureError();                           // 报错
```

### D.7 控制并发

```java
Flux.fromIterable(queries)
        .flatMap(query -> chatClient.prompt()
                .user(query)
                .stream()
                .content()
                .collectList(),
                10)   // 最多 10 个并发
        .subscribe();
```

---

## Part E. 流式下检测工具调用

### E.1 工具调用在流中是什么样的

```
chunk 1: assistant message，含 toolCalls=[{name:"getWeather", args:"{city:北京}"}]
    ↓ Advisor 检测到 toolCalls，触发工具执行
chunk 2: tool message（工具结果）
    ↓ 重新调用 LLM
chunk 3+: assistant message，开始流式返回最终答案
```

### E.2 用 ChatClientMessageAggregator 旁路聚合

```java
@GetMapping("/chat-stream")
public Flux<String> chatStream(@RequestParam String q) {
    Flux<ChatClientResponse> flux = chatClient.prompt()
            .user(q)
            .stream()
            .chatClientResponse();

    // 旁路聚合，不阻塞主流
    new ChatClientMessageAggregator().aggregateChatClientResponse(flux, aggregated -> {
        log.info("Tool calls: {}", aggregated.chatResponse().getMetadata());
        metrics.recordUsage(aggregated.chatResponse().getMetadata().getUsage());
    });

    // 主流仍然把每个 chunk 输出给客户端
    return flux.map(resp -> resp.chatResponse().getResult().getOutput().getText())
            .filter(Objects::nonNull);
}
```

---

## Part F. 流式实战模式

### F.1 边流边检测关键词

```java
public Flux<String> safeStream(String q) {
    return chatClient.prompt().user(q).stream().content()
            .scan(new StringBuilder(), StringBuilder::append)
            .map(StringBuilder::toString)
            .flatMap(current -> {
                String lastChunk = current.substring(/* last emit length */);
                if (sensitiveWordDetector.contains(lastChunk)) {
                    return Flux.error(new RuntimeException("Sensitive content detected"));
                }
                return Flux.just(lastChunk);
            });
}
```

### F.2 多流并发（不同视角）

```java
public Flux<String> multiPerspective(String question) {
    Flux<String> tech = perspectiveClient.prompt()
            .user("从技术角度回答：" + question)
            .stream().content()
            .map(chunk -> "[技术] " + chunk);

    Flux<String> biz = perspectiveClient.prompt()
            .user("从业务角度回答：" + question)
            .stream().content()
            .map(chunk -> "[业务] " + chunk);

    return Flux.merge(tech, biz);
}
```

### F.3 用户取消

```java
@GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@RequestParam String q) {
    return chatClient.prompt().user(q).stream().content()
            .doOnCancel(() -> {
                log.info("Client cancelled stream");
                metrics.counter("ai.stream.cancelled").increment();
            });
}
```

客户端断开连接时，WebFlux 自动取消订阅。

---

## Part G. 线程模型

### G.1 不要在 Flux 操作符内调阻塞 API

```java
// ❌ 反模式：阻塞 Netty 线程
flux.map(chunk -> jdbc.queryForMap("SELECT ..."));

// ✅ 正模式：切到 boundedElastic
flux.publishOn(Schedulers.boundedElastic())
    .map(chunk -> jdbc.queryForMap("SELECT ..."));

// 或者
flux.flatMap(chunk -> Mono.fromCallable(() -> jdbc.queryForMap("..."))
        .subscribeOn(Schedulers.boundedElastic()));
```

### G.2 BaseAdvisor 的 getScheduler

12 篇提到的 BaseAdvisor 有 `getScheduler()`：

```java
@Override
public Scheduler getScheduler() {
    return Schedulers.boundedElastic();   // after() 跑在这
}
```

如果 Advisor 的 `after()` 调阻塞 API（DB、Redis），把它放到 boundedElastic，避免阻塞 event loop。

---

## Part H. 调试技巧

### H.1 doOnNext / log

```java
flux.doOnNext(chunk -> log.debug("chunk: [{}]", chunk))
    .doOnComplete(() -> log.debug("complete"))
    .doOnCancel(() -> log.debug("cancelled"))
    .doOnError(e -> log.error("error", e));

flux.log("before-filter")
    .filter(...)
    .log("after-filter");
```

### H.2 BlockHound 检测阻塞

```java
BlockHound.install();   // 测试代码加
// 如果有 .block() 在 reactive 线程会报错
```

---

## Part I. 实战避坑

### I.1 "在 Flux 操作符里 block()"

**症状**：服务卡死。

**解决**：用 `Mono.fromCallable` 包装阻塞调用，或 `publishOn(boundedElastic())`。

### I.2 "retry 把永久错误也 retry 了"

用 `Retry.backoff().filter(...)` 只 retry 5xx 和超时，跳过 4xx。

### I.3 "工具调用流式下丢 chunk"

某个 Advisor 在 `after()` 阶段吞了第一个 chunk。用 `ChatClientMessageAggregator` 旁路聚合，不修改主流。

### I.4 "并发太高打爆 LLM API"

`flatMap(fn, concurrency)` 第二参数限并发；用 Resilience4j Bulkhead。

### I.5 "流式输出图像分析时丢内容"

流式 chunk 较小，前端拼接处理不当。见本文 §D.1 的 scan 滚动聚合模式。

---

## Part J. 实战任务

1. 跑通本文 B 部分的 ChatConfig + Controller，curl 验证流式 + 工具调用。
2. 用 `scan` 实现边流边检测关键词，检测到立即 cut。
3. 用 `concatMap` 实现流式翻译（英→中，保序）。
4. 用 `retryWhen` 实现 429 自动重试（指数退避，3 次）。
5. 用 `timeout` + `take(Duration)` 同时设 chunk 间隔超时和整流超时。
6. 实现 fallback 链：主 LLM 流报错时切 fallback LLM 流。
7. （进阶）用 Sinks 实现一个流多订阅者共享。
8. （选做）用 BlockHound 检测项目里的阻塞调用。

---

## K. 理解检查

1. Cold Flux 和 Hot Flux 区别？Spring AI 的 stream() 是哪种？
2. `flatMap` / `concatMap` / `switchMap` 各自特点？
3. `timeout(Duration)` 和 `take(Duration)` 区别？
4. 自定义 ChatClient 下为什么 ToolCallingAdvisor 不自动注册？
5. 流式下 conversationId NPE 的根因和修复？
6. 为什么不能在 Flux 操作符内调阻塞 API？怎么解决？

---

## L. 相关文档

- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— 工具调用基础
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 在流中的顺序
- [Project Reactor Reference](https://projectreactor.io/docs/core/release/reference/)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
