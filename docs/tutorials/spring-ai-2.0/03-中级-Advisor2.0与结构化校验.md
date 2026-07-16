# L3 中级 - Advisor 2.0 与结构化校验

> 自定义 Advisor + `StructuredOutputValidationAdvisor` 自动校验重试。
>
> 前置：[`./02-初级-ToolCallingAdvisor.md`](./02-初级-ToolCallingAdvisor.md)
> 预计：1 天

---

## 1. 自定义 Advisor 的标准模板

### 1.1 同时实现 Call 和 Stream

```java
package org.demo02.toolkit.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

public class TimingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TimingAdvisor.class);

    @Override
    public String getName() { return "TimingAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        log.info("[{}] call took {} ms", request.chatOptions().getModel(),
                System.currentTimeMillis() - start);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        return chain.nextStream(request)
                .doOnComplete(() -> log.info("stream took {} ms", System.currentTimeMillis() - start));
    }
}
```

### 1.2 三条核心规则

| 规则 | 说明 |
|------|------|
| `getName()` 必须返回唯一名字 | Advisor 链路追踪用 |
| `getOrder()` 决定执行顺序 | 数字小 = 外层 = 先前置 / 最后置 |
| 必须**显式调用** `chain.nextCall/nextStream` | 否则链路断掉，没有 LLM 调用 |

### 1.3 装配方式

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, TimeTools timeTools) {
    return builder
            .defaultTools(timeTools)
            .defaultAdvisors(
                    new TimingAdvisor(),
                    MessageChatMemoryAdvisor.builder(memory).build(),
                    new SimpleLoggerAdvisor()
            )
            .build();
}
```

或单次调用时：
```java
chatClient.prompt()
        .user(q)
        .advisors(new TimingAdvisor())
        .call();
```

---

## 2. 在 Advisor 里修改 Prompt

### 2.1 改 system prompt

```java
public class TenantAwareAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() { return "TenantAwareAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 150; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String tenantId = (String) request.context().get("tenantId");

        // 修改 ChatClientRequest（不可变，要 builder 重建）
        ChatClientRequest newReq = request.mutate()
                .context(ctx -> {
                    ctx.put("tenantPrefix", "tenant_" + tenantId);
                })
                .build();

        return chain.nextCall(newReq);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest newReq = request.mutate().context(c -> c.put("k", "v")).build();
        return chain.nextStream(newReq);
    }
}
```

### 2.2 给 prompt 追加 user message

```java
ChatClientRequest newReq = ChatClientRequest.builder()
        .prompt(request.prompt())   // 保留原 prompt
        .messages(request.prompt().getInstructions())   // 复制原 messages
        // .messages(new UserMessage("..."))            // 追加新 message
        .context(request.context())
        .build();
```

> 2.0 的 `ChatClientRequest` 是 immutable record，所有修改都走 builder，避免状态污染。

---

## 3. StructuredOutputValidationAdvisor

### 3.1 痛点

`.entity(MyType.class)` 在 1.0 只做一次性解析：
- LLM 返回的 JSON 不合法 → 直接抛异常
- 业务代码要么 `try-catch` 要么再次重试

### 3.2 2.0 解决方案：自动校验 + 重试

```java
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;

@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultAdvisors(
                    StructuredOutputValidationAdvisor.builder()
                            .validator(new MyBeanValidator())
                            .maxAttempts(3)
                            .build()
            )
            .build();
}

public class MyBeanValidator implements Validator<Sentiment> {
    @Override
    public ValidationResult validate(Sentiment result) {
        if (result.score() < 0 || result.score() > 1) {
            return ValidationResult.failure("score 必须在 0-1 之间，但收到 " + result.score());
        }
        return ValidationResult.success();
    }
}
```

### 3.3 工作流

```
LLM 返回 JSON
    ↓
BeanOutputConverter 解析成 Sentiment
    ↓
MyBeanValidator.validate(sentiment)
    ↓
┌─ success → 返回结果
└─ failure → 把错误信息加到 prompt，重新调用 LLM
                ↓
                最多重试 maxAttempts 次
```

### 3.4 内置的 validateSchema

如果你用 JSON Schema 校验（不写自定义 Validator），用 `validateSchema()`：

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultAdvisors(
                    StructuredOutputValidationAdvisor.builder()
                            .validateSchema()   // 用 record 的 JSON Schema 自动校验
                            .maxAttempts(3)
                            .build()
            )
            .build();
}
```

> ⚠️ `validateSchema()` **不支持流式**。流式接口只能用 `.entity()` 一次性解析，不做校验。

---

## 4. 校验重试的实战示例

### 4.1 情感分析

```java
public record Sentiment(String emotion, double score, String reason) {}

@GetMapping("/analyze")
public Sentiment analyze(@RequestParam String text) {
    return chatClient.prompt()
            .system("分析情感，返回 JSON：{emotion, score, reason}")
            .user(text)
            .call()
            .entity(Sentiment.class);   // StructuredOutputValidationAdvisor 自动校验
}
```

**效果**：
- LLM 第一次返回 `{"emotion": "happy", "score": 1.5, "reason": "..."}` → 校验失败（score 越界）
- Advisor 自动追加："上次返回 score=1.5，必须在 0-1 之间"
- LLM 第二次返回 `{"emotion": "happy", "score": 0.9, ...}` → 通过

### 4.2 复杂对象

```java
public record OrderAnalysis(
        String category,
        List<String> keywords,
        double urgency,         // 0-1
        LocalDateTime deadline
) {}

public class OrderAnalysisValidator implements Validator<OrderAnalysis> {
    @Override
    public ValidationResult validate(OrderAnalysis r) {
        if (r.urgency() < 0 || r.urgency() > 1)
            return ValidationResult.failure("urgency 必须 0-1");
        if (r.keywords().isEmpty())
            return ValidationResult.failure("keywords 不能为空");
        return ValidationResult.success();
    }
}
```

---

## 5. Advisor 顺序的设计原则

### 5.1 通用顺序表

```
HIGHEST_PRECEDENCE + 100   ← 自定义业务 Advisor（如多租户）
HIGHEST_PRECEDENCE + 150   ← 计时 / 日志类（要包住一切）
HIGHEST_PRECEDENCE + 200   ← MessageChatMemoryAdvisor（默认）
HIGHEST_PRECEDENCE + 300   ← ToolCallingAdvisor（默认）
HIGHEST_PRECEDENCE + 400   ← Memory（内层模式，需配合 disableInternalConversationHistory）
HIGHEST_PRECEDENCE + 500   ← StructuredOutputValidationAdvisor（默认）
HIGHEST_PRECEDENCE + 700   ← RAG Advisor（默认）
```

### 5.2 怎么排

| 你的 Advisor 干什么 | 放哪一层 |
|---------------------|---------|
| 多租户上下文注入 | 最外（+100） |
| 计时 / 限流 / 审计 | 外（+150） |
| 修改 memory 行为 | 内层（+400）需要关掉内部 conversation history |
| 修改结构化校验逻辑 | +500 附近 |
| 改 RAG 检索策略 | +700 附近 |

### 5.3 验证顺序的方法

开 DEBUG 日志，看 Advisor 名字出现顺序：
```yaml
logging:
  level:
    org.springframework.ai.chat.client: DEBUG
```

---

## 6. 多个 Advisor 协作示例

### 6.1 多租户 + Memory + Tool + 校验

```java
@Bean
ChatClient chatClient(
        ChatClient.Builder builder,
        TimeTools timeTools,
        ChatMemory memory
) {
    return builder
            .defaultTools(timeTools)
            .defaultAdvisors(
                    new TenantAwareAdvisor(),                    // +100
                    new SimpleLoggerAdvisor(),                    // 默认顺序
                    MessageChatMemoryAdvisor.builder(memory).build(),   // +200
                    StructuredOutputValidationAdvisor.builder()   // +500
                            .validateSchema()
                            .build()
            )
            .build();
}
```

### 6.2 执行流程

```
TenantAwareAdvisor 前置（注入 tenantId）
  ↓
SimpleLoggerAdvisor 前置（打印 prompt）
  ↓
MemoryAdvisor 前置（读历史消息）
  ↓
ToolCallingAdvisor 前置（启动 tool 循环）
  ↓
ChatModel.call()
  ↓
ToolCallingAdvisor 后置（执行 tool → 再次 call）
  ↓
MemoryAdvisor 后置（写新消息）
  ↓
SimpleLoggerAdvisor 后置（打印响应）
  ↓
StructuredOutputValidation 后置（校验 + 重试）
  ↓
TenantAwareAdvisor 后置（清理上下文）
```

---

## 7. 流式 Advisor 的特殊技巧

### 7.1 用 Reactor 操作 Flux

```java
@Override
public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest request, StreamAdvisorChain chain) {

    return chain.nextStream(request)
            // 缓存第一个 token 时间（TTFT）
            .doOnNext(resp -> {
                if (!firstTokenSeen.getAndSet(true)) {
                    long ttft = System.currentTimeMillis() - startTime.get();
                    log.info("TTFT: {} ms", ttft);
                }
            })
            // 过滤空响应
            .filter(resp -> resp.chatResponse() != null)
            // 错误转换
            .onErrorResume(e -> {
                log.error("Stream error", e);
                return Flux.empty();
            });
}
```

### 7.2 累积响应做后置处理

```java
@Override
public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest request, StreamAdvisorChain chain) {

    StringBuilder accumulator = new StringBuilder();

    return chain.nextStream(request)
            .doOnNext(resp -> {
                String token = resp.chatResponse().getResult().getOutput().getText();
                if (token != null) accumulator.append(token);
            })
            .doOnComplete(() -> {
                log.info("完整响应：{}", accumulator);
                // 这里可以做内容审查 / 敏感词过滤 / 审计落库
            });
}
```

---

## 8. 常见错误

### 8.1 Advisor 不生效

**症状**：日志里没看到你的 Advisor 名字。

**排查**：
1. 是否在 `defaultAdvisors(...)` 里加了？
2. `getOrder()` 数字是不是太大（被其他 Advisor 覆盖）？
3. `getName()` 是否返回 null？

### 8.2 流式 Advisor 把响应吞了

**症状**：业务方收到空响应。

**原因**：Advisor 里 `filter` 太狠，把所有 chunk 都过滤掉了。

**解决**：`filter` 要留至少一个非空 chunk。

### 8.3 StructuredOutputValidation 无限重试

**症状**：日志里 `Validation failed` 反复出现，3 次后抛错。

**原因**：Validator 写错了（永远返回 failure）。

**解决**：单测 Validator 逻辑，确保有合法路径。

### 8.4 流式 + validateSchema 报错

**症状**：`IllegalStateException: validateSchema not supported with streaming`

**原因**：见 [`./11-复现手册-流式与工具调用.md`](./11-复现手册-流式与工具调用.md) §9.1。

**解决**：流式接口不能用 `validateSchema`，用 `.entity()` 一次性解析或同步 endpoint。

---

## 9. 实战：自定义"敏感词过滤" Advisor

```java
public class SensitiveWordAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordAdvisor.class);
    private final Set<String> blocked = Set.of("秘密", "密码", "token");

    @Override
    public String getName() { return "SensitiveWordAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 120; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        String content = response.chatResponse().getResult().getOutput().getText();
        String cleaned = filter(content);
        if (!cleaned.equals(content)) {
            // 重建响应
            return response.mutate()
                    .chatResponse(rebuildWith(content, cleaned))
                    .build();
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
                .map(resp -> {
                    String original = resp.chatResponse().getResult().getOutput().getText();
                    String cleaned = filter(original);
                    if (!cleaned.equals(original)) {
                        return resp.mutate()
                                .chatResponse(rebuildWith(original, cleaned))
                                .build();
                    }
                    return resp;
                });
    }

    private String filter(String text) {
        String result = text;
        for (String word : blocked) {
            result = result.replace(word, "***");
        }
        return result;
    }

    private ChatResponse rebuildWith(String original, String cleaned) {
        // 构造新的 ChatResponse，把 content 换成 cleaned
        return ChatResponse.builder()
                .withGenerations(List.of(new Generation(new AssistantMessage(cleaned))))
                .build();
    }
}
```

---

## 10. 理解检查

1. 自定义 Advisor 必须实现哪两个接口？为什么不能只实现一个？
2. `ChatClientRequest` 为什么是 immutable？怎么修改它？
3. `StructuredOutputValidationAdvisor` 跟 `.entity(Class)` 的关系是什么？
4. 为什么流式不支持 `validateSchema`？
5. Memory Advisor 在 tool 循环外 vs 内有什么区别？

---

## 11. 练习任务

1. 实现 `TimingAdvisor`，记录 TTFT 和总耗时
2. 写一个 `TenantAwareAdvisor`，从请求头读 tenantId 注入 context
3. 实现 `SentimentService`，用 `StructuredOutputValidationAdvisor` 校验 score 0-1
4. 写一个 `SensitiveWordAdvisor`，过滤响应里的敏感词
5. 故意让 LLM 返回不合法 JSON（system prompt 误导），观察 Validation 重试过程
6. 装配多 Advisor（多租户 + Memory + Tool + 校验），验证执行顺序

---

## 12. 进 L4 之前的能力确认

完成本篇你应该能：
- [ ] 不查资料写出一个完整的 Call + Stream Advisor
- [ ] 区分 `ChatClientRequest` 和 `ChatClientResponse` 的用法
- [ ] 熟练使用 `StructuredOutputValidationAdvisor` + 自定义 Validator
- [ ] 设计 4+ Advisor 协作的复杂链路

完成后进入 [`./04-中级-MCP与会话持久化.md`](./04-中级-MCP与会话持久化.md)。
