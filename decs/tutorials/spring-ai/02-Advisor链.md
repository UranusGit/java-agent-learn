# Spring AI 02 - Advisor 链

> Advisor 是 Spring AI 最具差异化的设计，理解了它你就理解了 Spring AI 的精髓。
> 前置：已完成 [01-快速起步](./01-快速起步.md)。

---

## 1. Advisor 是什么

### 1.1 一句话定义

> Advisor 是 Spring AI 版的 **Servlet Filter / Spring AOP** —— 在 LLM 调用前后插入自定义逻辑。

### 1.2 类比你熟悉的概念

| 你的现有心智 | Spring AI 对应 |
|------------|---------------|
| Servlet Filter 链 | Advisor Chain |
| `@Around` 切面 | `aroundCall()` / `aroundStream()` |
| HandlerInterceptor | CallAroundAdvisor |
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

### 2.2 核心接口

```java
public interface CallAdvisor extends BaseAdvisor {
    default AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAdvisorChain chain) {
        return chain.nextAroundCall(advisedRequest);
    }
}

public interface StreamAdvisor extends BaseAdvisor {
    default Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAdvisorChain chain) {
        return chain.nextAroundStream(advisedRequest);
    }
}
```

简化记忆：
- `aroundCall` —— 同步调用时被回调
- `aroundStream` —— 流式调用时被回调
- 必须调用 `chain.nextAroundCall()` 让链继续走

---

## 3. 内置 Advisor 速览

Spring AI 提供了几个开箱即用的 Advisor：

| Advisor | 作用 | LangChain4j 对应 |
|---------|------|----------------|
| `MessageChatMemoryAdvisor` | 自动注入对话历史 | `chatMemory(...)` |
| `QuestionAnswerAdvisor` | 自动 RAG（检索 + 注入） | `contentRetriever(...)` |
| `SafeGuardAdvisor` | 敏感词拦截 | 无内置，需自己写 |
| `SimpleLoggerAdvisor` | 日志记录 | `logRequests(true)` |

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
                    MessageChatMemoryAdvisor.builder(chatMemory)
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

import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Map;

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
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[REQ] " + request.userText());

        AdvisedResponse response = chain.nextAroundCall(request);

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[RESP] " + elapsed + "ms");
        return response;
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[STREAM REQ] " + request.userText());
        return chain.nextAroundStream(request)
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

#### `AdvisedRequest`
- 经过 Advisor 链处理过的请求对象
- 你可以**修改它**（加 system message、加参数）

#### `chain.nextAroundCall(request)`
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
    public AdvisedResponse aroundCall(AdvisedRequest req, CallAdvisorChain chain) {
        String text = req.userText();
        if (BLOCKED.stream().anyMatch(text::contains)) {
            // 中断链，直接返回拒绝响应
            ChatResponse fake = new ChatResponse(List.of(
                new Generation("您的问题包含敏感词，无法回答")
            ));
            return new AdvisedResponse(fake, req.adviseContext());
        }
        return chain.nextAroundCall(req);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest req, StreamAdvisorChain chain) {
        String text = req.userText();
        if (BLOCKED.stream().anyMatch(text::contains)) {
            return Flux.empty();  // 简化处理
        }
        return chain.nextAroundStream(req);
    }
}
```

**核心**：不调用 `chain.nextAroundCall()` 就能中断整个链。

---

## 7. 实战 4：动态注入 RAG（用 QuestionAnswerAdvisor）

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
    return builder
            .defaultSystem("基于上下文回答：{question_answer_context}")
            .defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(
                        SearchRequest.builder()
                            .topK(5)
                            .similarityThreshold(0.7)
                            .build())
                    .promptTemplate(...)
                    .build()
            )
            .build();
}
```

**关键**：`QuestionAnswerAdvisor` 内部做的事：
1. 拿到用户 query
2. 用 `vectorStore` 检索 top-K 相关片段
3. 把片段塞进 system prompt 的 `{question_answer_context}` 占位符
4. 让 LLM 基于增强后的 prompt 回答

**对比 LangChain4j**：
- LangChain4j：`.contentRetriever(retriever)`
- Spring AI：`.defaultAdvisors(QuestionAnswerAdvisor.builder(vs).build())`

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

### 10.2 Order 顺序错乱

**症状**：日志在 Memory 之后才打印（应该之前）。
**解决**：调 `getOrder()` 返回值。

### 10.3 链中断了

**症状**：LLM 永远不响应。
**原因**：某个 Advisor 没调用 `chain.nextAroundCall()`。
**解决**：检查所有 Advisor 是否都正确转发。

### 10.4 StreamAdvisor 里的阻塞操作

**症状**：流式接口变成同步。
**原因**：在 `aroundStream` 里调用了阻塞方法。
**解决**：用 `Flux` 的响应式操作（`map`、`doOnNext` 等）。

---

## 11. 理解检查

1. Advisor 和 Servlet Filter 的本质相同点和不同点？
2. `getOrder()` 返回 -100 和 100 的 Advisor，谁先执行 before？
3. 如何在 Advisor 里中断链？典型场景是什么？
4. `QuestionAnswerAdvisor` 内部做了什么？它和 LangChain4j 的 `ContentRetriever` 谁更灵活？
5. 同一个 ChatClient 能挂多少个 Advisor？有上限吗？

---

## 12. 练习任务

1. 用 `MessageChatMemoryAdvisor` 实现多轮对话
2. 写一个 `SimpleLoggingAdvisor`，打印每次调用的耗时
3. 写一个 `SensitiveWordAdvisor`，遇到敏感词时直接拒绝
4. 测试 Order：把日志 Advisor 的 order 从 0 改成 100，观察 before 顺序变化
5. 集成 `QuestionAnswerAdvisor`（需要先准备一个 VectorStore，下节 RAG 实战会讲）
6. （进阶）写一个 `RateLimitAdvisor`，每秒最多 1 个请求

完成后进入 [03-Tool 调用](./03-Tool调用.md)。
