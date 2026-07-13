# Spring AI 02 - Advisor 链

> Advisor 是 Spring AI 最具差异化的设计，理解了它你就理解了 Spring AI 的精髓。
> 前置：已完成 [01-快速起步](./01-快速起步.md)。
>
> **本文基于 Spring AI 1.0.0**。0.8/0.9 时代的 `aroundCall` / `AdvisedRequest` API 已彻底废弃，1.0.0 改用 `adviseCall` / `ChatClientRequest`，注意区分。

---

## 1. Advisor 是什么

### 1.1 一句话定义

> Advisor 是 Spring AI 版的 **Servlet Filter / Spring AOP** —— 在 LLM 调用前后插入自定义逻辑。

### 1.2 类比你熟悉的概念

| 你的现有心智 | Spring AI 对应 |
|------------|---------------|
| Servlet Filter 链 | Advisor Chain |
| `@Around` 切面 | `adviseCall()` / `adviseStream()` |
| HandlerInterceptor | `CallAdvisor` / `StreamAdvisor` |
| Spring Security Filter | 安全相关 Advisor |

### 1.3 它解决了什么问题

在 LangChain4j 里，你想做这些事：
- 给每个请求加日志
- 给每个请求注入 RAG 检索结果
- 给每个请求做敏感词过滤
- 给每个请求加 memory

**LangChain4j 做法**：要么在 `AiServices.builder()` 里手动装配一堆组件，要么自己写装饰器。

**Spring AI 做法**：写一个 Advisor，加到 `defaultAdvisors(...)` 里，全局生效。

---

## 2. Advisor 的核心机制

### 2.1 调用链路

```
chatClient.prompt().user("hi").call()
            ↓
   ┌──────────────────────────────────────────┐
   │  before(request)                         │ ← Advisor 1（如日志）
   │  ┌──────────────────────────────────┐    │
   │  │  before(request)                 │    │ ← Advisor 2（如 RAG）
   │  │  ┌──────────────────────────┐    │    │
   │  │  │  before(request)         │    │    │ ← Advisor 3（如 Memory）
   │  │  │     → 调用 LLM ←         │    │    │
   │  │  │  after(response)         │    │    │
   │  │  └──────────────────────────┘    │    │
   │  │  after(response)                 │    │
   │  └──────────────────────────────────┘    │
   │  after(response)                         │
   └──────────────────────────────────────────┘
            ↓
        最终响应
```

**洋葱模型**：before 是从外到内，after 是从内到外。

### 2.2 核心接口（1.0.0）

```java
public interface CallAdvisor extends Advisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
}

public interface StreamAdvisor extends Advisor {
    Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain);
}
```

简化记忆：
- `adviseCall` —— 同步调用时被回调
- `adviseStream` —— 流式调用时被回调
- 必须调用 `chain.nextCall(request)` 让链继续走

**关键变化（0.x → 1.0.0）**：
- 方法名 `aroundCall` → `adviseCall`（不再是 `default` 方法，必须实现）
- 请求/响应类型 `AdvisedRequest` / `AdvisedResponse` → `ChatClientRequest` / `ChatClientResponse`
- 链推进 `chain.nextAroundCall(req)` → `chain.nextCall(req)`

### 2.3 ChatClientRequest / ChatClientResponse 关键方法

```java
// ChatClientRequest（record 类）
request.prompt();    // 拿到 Prompt 对象
request.context();   // 拿到 advisor 之间共享的 Map<String, Object>
request.copy();      // 不可变对象，修改前先 copy
request.mutate();    // 返回 Builder，用于改 prompt / context

// 拿用户消息文本
String userText = request.prompt().getUserMessage().getText();
String systemText = request.prompt().getInstructions()  // 系统消息文本
// 拿上下文里的值
Object val = request.context().get("myKey");
```

```java
// ChatClientResponse（record 类）
response.chatResponse();  // 拿到 ChatResponse
response.context();       // 共享上下文
response.mutate();        // 返回 Builder

// 拿模型回答文本
String text = response.chatResponse()
                       .getResult()
                       .getOutput()
                       .getText();
```

---

## 3. 内置 Advisor 速览

Spring AI 提供了几个开箱即用的 Advisor：

| Advisor | 作用 | LangChain4j 对应 |
|---------|------|----------------|
| `MessageChatMemoryAdvisor` | 自动注入对话历史 | `chatMemory(...)` |
| `PromptChatMemoryAdvisor` | 把历史拼到 system prompt | 无内置 |
| `SimpleLoggerAdvisor` | 日志记录 | `logRequests(true)` |
| `SafeGuardAdvisor` | 敏感词拦截 | 无内置，需自己写 |

> ⚠️ **注意**：旧版的 `QuestionAnswerAdvisor` 在 1.0.0 已**不在主依赖里**。RAG 改用独立的 `RetrievalAugmentationAdvisor`（来自 `spring-ai-rag` 模块），需要额外引入依赖，详见 [04-RAG实战](./04-RAG实战.md)。

---

## 4. 实战 1：用内置 Advisor 实现多轮对话

### 4.1 配置 Memory Advisor

```java
@Configuration
public class ChatConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
                .defaultSystem("你是友好的助手")
                .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(memory)
                        .conversationId("default")
                        .build()
                )
                .build();
    }
}
```

### 4.2 使用

```java
@RestController
public class ChatController {

    private final ChatClient client;
    public ChatController(ChatClient client) { this.client = client; }

    @GetMapping("/chat")
    public String chat(@RequestParam String q) {
        return client.prompt().user(q).call().content();
    }
}
```

第二次调用时，第一次的对话自动被注入。**完全无状态代码，记忆在 Advisor 里维护**。

### 4.3 多用户场景

```java
@GetMapping("/chat")
public String chat(@RequestParam String q,
                   @RequestParam String userId) {
    return client.prompt()
            .user(q)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, userId))
            .call()
            .content();
}
```

通过 `CONVERSATION_ID` 参数区分用户。

**对比 LangChain4j**：
```java
// LangChain4j：接口参数 @MemoryId
String chat(@MemoryId String userId, @UserMessage String msg);

// Spring AI：Advisor 参数注入
.advisors(spec -> spec.param(CONVERSATION_ID, userId))
```

---

## 5. 实战 2：自定义 Advisor（核心能力）

### 5.1 简单日志 Advisor

```java
package org.example.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

public class SimpleLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return "SimpleLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;  // 越小越先执行 before
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[REQ] " + request.prompt().getUserMessage().getText());

        ChatClientResponse response = chain.nextCall(request);

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[RESP] " + elapsed + "ms");
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[STREAM REQ] " + request.prompt().getUserMessage().getText());
        return chain.nextStream(request)
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("[STREAM DONE] " + elapsed + "ms");
                });
    }
}
```

### 5.2 装配

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultSystem("...")
            .defaultAdvisors(new SimpleLoggingAdvisor())
            .build();
}
```

### 5.3 关键概念

#### `getOrder()`
- 决定 Advisor 在链中的位置
- 数字越小，before 越先执行（在外层）
- 数字越大，after 越先执行

#### `ChatClientRequest`
- 不可变 record 对象，封装了 `Prompt` 和共享 `context`
- 想**修改**请求时，用 `request.mutate()` 拿到 Builder 改完再 `build()`，然后传给 `chain.nextCall(...)`

#### `chain.nextCall(request)`
- 关键：让链继续走
- 不调用 = 中断链（比如敏感词拦截场景）

---

## 6. 实战 3：敏感词拦截 Advisor

```java
public class SensitiveWordAdvisor implements CallAdvisor, StreamAdvisor {

    private static final List<String> BLOCKED = List.of("密码", "身份证", "银行卡");

    @Override
    public String getName() { return "SensitiveWordAdvisor"; }

    @Override
    public int getOrder() { return -100; }  // 最先执行

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        String text = req.prompt().getUserMessage().getText();
        if (BLOCKED.stream().anyMatch(text::contains)) {
            // 中断链，直接返回拒绝响应
            ChatResponse fake = ChatResponse.builder()
                .withGeneration(new Generation("您的问题包含敏感词，无法回答"))
                .build();
            return ChatClientResponse.builder()
                .chatResponse(fake)
                .context(req.context())
                .build();
        }
        return chain.nextCall(req);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
        String text = req.prompt().getUserMessage().getText();
        if (BLOCKED.stream().anyMatch(text::contains)) {
            return Flux.empty();  // 简化处理
        }
        return chain.nextStream(req);
    }
}
```

**核心**：不调用 `chain.nextCall()` 就能中断整个链。

> 📌 上面的 `ChatResponse.builder()` 和 `ChatClientResponse.builder()` 是 1.0.0 新增的 Builder API。`ChatResponse` 仍然支持 `new ChatResponse(List<Generation>)` 构造，二选一即可。

---

## 7. 实战 4：动态注入 RAG

1.0.0 的 RAG 入口是 `RetrievalAugmentationAdvisor`（来自 `spring-ai-rag` 模块），**不在主依赖里**，需要额外引入：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-rag</artifactId>
</dependency>
<!-- 还需要一个 VectorStore 实现，如 SimpleVectorStore（在 spring-ai-vector-store-simple） -->
```

> ⚠️ **当前项目 pom 没有引入这些依赖**，下面代码仅供了解。完整实战见 [04-RAG实战](./04-RAG实战.md)。

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
    ContentRetriever retriever = VectorStoreContentRetriever.builder()
            .vectorStore(vectorStore)
            .topK(5)
            .similarityThreshold(0.7)
            .build();

    RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .build();

    return builder
            .defaultSystem("基于上下文回答用户问题")
            .defaultAdvisors(ragAdvisor)
            .build();
}
```

**关键**：`RetrievalAugmentationAdvisor` 内部做的事：
1. 拿到用户 query
2. 用 `ContentRetriever` 检索 top-K 相关片段
3. 把片段塞进 prompt（默认追加到 user message 或 system message）
4. 让 LLM 基于增强后的 prompt 回答

**对比 LangChain4j**：
- LangChain4j：`.contentRetriever(retriever)` + `RetrievalAugmentor`
- Spring AI：`.defaultAdvisors(RetrievalAugmentationAdvisor.builder()...)`

底层原理一样，但 Spring AI 把它做成了 Advisor，能和其他 Advisor 自由组合。

---

## 8. Advisor 链的执行顺序

### 8.1 before 顺序

按 `getOrder()` **升序**执行（小的先）。

### 8.2 after 顺序

按 `getOrder()` **降序**执行（大的先）。

### 8.3 实例

假设你有三个 Advisor：
- `A` order=-100（敏感词）
- `B` order=0（日志）
- `C` order=100（Memory）

执行流：
```
A.before → B.before → C.before → LLM → C.after → B.after → A.after
```

### 8.4 推荐顺序约定

| Order | 类型 |
|-------|------|
| -200 ~ -100 | 安全、限流（最先执行） |
| -100 ~ 0 | 日志、审计 |
| 0 ~ 100 | RAG、Memory |
| 100+ | 后处理 |

---

## 9. Advisor vs AiServices（与 LangChain4j 对比）

| 维度 | LangChain4j AiServices | Spring AI Advisor |
|------|------------------------|-------------------|
| 配置位置 | 一次性 builder 装配 | 链式动态组合 |
| 复用性 | 一个 Service 一套配置 | Advisor 跨 ChatClient 复用 |
| 拦截粒度 | 不细 | before/after 精细控制 |
| 流式支持 | 自动 | 显式实现 `StreamAdvisor` |
| 学习曲线 | 平 | 陡 |
| 灵活度 | 中 | 高 |

**结论**：
- LangChain4j 简单直接，**适合个人项目**
- Spring AI Advisor 链灵活强大，**适合企业项目**

---

## 10. 常见错误

### 10.1 Advisor 不生效

**诊断**：在 Advisor 里加打印，看是否进入。

**常见原因**：
- 没加到 `defaultAdvisors(...)`
- Bean 注入了但没用
- 实现了 0.x 的 `aroundCall`（1.0.0 不会识别）

### 10.2 Order 顺序错乱

**症状**：日志在 Memory 之后才打印（应该之前）。
**解决**：调 `getOrder()` 返回值。

### 10.3 链中断了

**症状**：LLM 永远不响应。
**原因**：某个 Advisor 没调用 `chain.nextCall()`。
**解决**：检查所有 Advisor 是否都正确转发。

### 10.4 StreamAdvisor 里的阻塞操作

**症状**：流式接口变成同步。
**原因**：在 `adviseStream` 里调用了阻塞方法。
**解决**：用 `Flux` 的响应式操作（`map`、`doOnNext` 等）。

### 10.5 找不到 aroundCall / AdvisedRequest

**症状**：编译报错或 IDE 找不到符号。
**原因**：从老博客/老文档复制了 0.x API。
**解决**：按本文 2.2 节的 1.0.0 新 API 重写。

---

## 11. 理解检查

1. Advisor 和 Servlet Filter 的本质相同点和不同点？
2. `getOrder()` 返回 -100 和 100 的 Advisor，谁先执行 before？
3. 如何在 Advisor 里中断链？典型场景是什么？
4. 1.0.0 里 `adviseCall` 的入参类型是什么？（答：`ChatClientRequest`，不是 `AdvisedRequest`）
5. 同一个 ChatClient 能挂多少个 Advisor？有上限吗？

---

## 12. 练习任务

1. 用 `MessageChatMemoryAdvisor` 实现多轮对话
2. 写一个 `SimpleLoggingAdvisor`，打印每次调用的耗时
3. 写一个 `SensitiveWordAdvisor`，遇到敏感词时直接拒绝
4. 测试 Order：把日志 Advisor 的 order 从 0 改成 100，观察 before 顺序变化
5. （进阶）写一个 `RateLimitAdvisor`，每秒最多 1 个请求

完成后进入 [03-Tool 调用](./03-Tool调用.md)。
