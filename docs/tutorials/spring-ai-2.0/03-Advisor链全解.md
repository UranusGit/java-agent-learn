# 03 Advisor 链全解（含顺序与实现选择）

> 本文合并自原 L3「Advisor 2.0 与结构化校验」+ 原 12「Advisor 顺序与实现选择」。
>
> 一篇搞定 Advisor 的：自定义模板、顺序设计、实现选择（BaseAdvisor vs Call/Stream）、结构化校验。
>
> 前置：[`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md)
> 预计：1 天

---

## 0. 一张图看懂 Advisor 是什么

Spring AI 2.0 的 Advisor 链是一条**双向流水线**：

```
请求进来 →  before() 按 order 升序执行（小→大）
                ↓
            ChatModel（真正调 LLM）
                ↓
            after()  按 order 降序执行（大→小）
请求出去 ←
```

**记忆口诀**：**"小在外、大在内；外层包内层"**。

```
order = Integer.MIN_VALUE + 200  ← 最外（最先 before、最后 after）
order = 0                        ← 中间
order = Integer.MAX_VALUE        ← 最内（最后 before、最先 after）
```

---

## 1. 三种实现方式的选择

Spring AI 2.0 给你三种写 Advisor 的方式，**选错会增加无谓的代码量和 bug 面**。

### 1.1 三种接口的关系

```
Advisor (extends Ordered)              ← 最顶层，只有 getName() + getOrder()
  ├── CallAdvisor                       ← 同步：adviseCall(req, chain)
  ├── StreamAdvisor                     ← 流式：adviseStream(req, chain) → Flux
  └── BaseAdvisor (extends Call+Stream) ← 同时支持两种，且提供 before/after 模板
```

### 1.2 三种方式的对比

| 方式 | 代码量 | 同步支持 | 流式支持 | 适用场景 |
|------|-------|---------|---------|---------|
| `implements BaseAdvisor` | 最少 | ✅ 自动 | ✅ 自动 | **95% 场景**：日志、改 prompt、改响应、注入上下文 |
| `implements CallAdvisor, StreamAdvisor` | 翻倍 | ✅ 手写 | ✅ 手写 | 需要 call/stream 走**完全不同**的逻辑（罕见） |
| `implements CallAdvisor`（只一个） | 一份 | ✅ | ❌ | 业务只走同步调用，从不 `.stream()` |
| `implements StreamAdvisor`（只一个） | 一份 | ❌ | ✅ | 业务只走流式，从不 `.call()` |

### 1.3 决策树

```
你的 Advisor 业务逻辑在 call 和 stream 下是一样的吗？
├── 是（90% 情况，比如改 prompt、加 system message、记录日志）
│   └── 用 BaseAdvisor，只写 before() / after()，框架自动处理 call/stream
├── 不一样（少数，比如流式要聚合才能判断，同步直接看完整响应）
│   └── 分别 implements CallAdvisor + StreamAdvisor，各写各的
└── 业务只走一种模式（比如内部 SDK 永远 .call()，从不 .stream()）
    └── 只 implements 你需要的那一个，省代码
```

### 1.4 BaseAdvisor 用法（推荐）

```java
public class SimpleLogAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        System.out.println("[REQ]：" + request.prompt().getUserMessage().getText());
        return request;  // 不改就原样返回
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        System.out.println("[RESP] 完成");
        return response;
    }

    @Override
    public String getName() { return "SimpleLogAdvisor"; }

    @Override
    public int getOrder() { return HIGHEST_PRECEDENCE; }  // 显式给 order
}
```

**框架自动做的事**（你不用写）：

- 同步 `adviseCall`：先调 `before(req)` → 调 `chain.nextCall(req)` → 拿到 resp → 调 `after(resp)` → 返回
- 流式 `adviseStream`：先调 `before(req)` → 订阅 `chain.nextStream(req)` → 每个 chunk 走 `after` → 返回 Flux
- 线程调度：用 `getScheduler()` 指定（默认 `Schedulers.boundedElastic()`）

### 1.5 同时 implements CallAdvisor + StreamAdvisor 的用法

只有当你**真的需要两种模式走不同代码**时才用。典型场景：流式下你需要在 `Flux` 上做 Reactor 操作（比如 `flatMap`、`filter`、`buffer`），同步下没这些事。

```java
public class StreamingAwareAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        ChatClientRequest newReq = modifyRequest(req);
        ChatClientResponse resp = chain.nextCall(newReq);
        return modifyResponse(resp);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
        ChatClientRequest newReq = modifyRequest(req);
        return chain.nextStream(newReq)
                .filter(chunk -> !ObjectUtils.isEmpty(chunk.chatResponse()
                        .getResult().getOutput().getText()))
                .map(this::modifyChunk);
    }

    @Override
    public String getName() { return "StreamingAwareAdvisor"; }

    @Override
    public int getOrder() { return HIGHEST_PRECEDENCE + 100; }
}
```

**代价**：两份代码，要保持逻辑一致（不然 call 和 stream 行为不一致，调试地狱）。

### 1.6 三条核心规则

| 规则 | 说明 |
|------|------|
| `getName()` 必须返回唯一名字 | Advisor 链路追踪用 |
| `getOrder()` 必须显式给值 | 不要依赖默认（同 order 时按注册顺序，跨版本不稳定） |
| before 必须用 `mutate()` 派生新对象 | Spring AI 是不可变设计，原地改不生效 |

---

## 2. 默认 order 全表（反编译核实）

把 `spring-ai-client-chat-2.0.0.jar` 解开，逐个 javap 看 Builder 的默认 `advisorOrder` / `order`：

| Advisor | 默认 order | 数值含义 | 链路位置 |
|---------|-----------|---------|---------|
| `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` | `-2147483448` | `Integer.MIN_VALUE + 200` | 常量基准 |
| `MessageChatMemoryAdvisor` | `-2147483448` | 同上 | 默认最外 |
| `ToolCallingAdvisor` | `-2147483348` | `Integer.MIN_VALUE + 300` | Memory 内层 100 |
| `BaseAdvisor` 实现类（自定义） | 自己定 | — | 按需 |
| Spring AI 内置日志类 Advisor | 通常 `0` 左右 | — | 中间 |

### 2.1 这个设计的隐含语义

默认 order 差值是 **100**（Memory=200，Tool=300），留出空隙让你插入自定义 Advisor：

```
-2147483448 (Memory)
   ↑ 这里插入 order = 250 的 Advisor，会在 Memory 和 Tool 之间
-2147483348 (Tool)
   ↑ 这里插入 order = 350 的 Advisor，会在 Tool 内层
```

Spring AI 设计者预留的"插槽"是 200/300/400/500... 每个 advisor 占一个百位数。

### 2.2 自定义 Advisor 的 order 推荐插槽

| 你的 Advisor 做什么 | 推荐 order | 理由 |
|---------------------|-----------|------|
| **日志/审计入口** | `Integer.MIN_VALUE`（最外） | 抓完整请求/响应，包括所有转换 |
| **限流/熔断/鉴权** | `-2147483448` 到 `-2147483400`（Memory 外或同层） | 在 Memory 之前拦截，未授权直接短路 |
| **Prompt 增强（注入系统消息）** | `-2147483400` 到 `-2147483350`（Memory 内、Tool 外） | 在 Memory 之后，避免覆盖历史；在 Tool 之前，让 Tool 看到增强后的 prompt |
| **结果后处理（脱敏、改写）** | `-2147483350` 到 `0`（Tool 内层） | 工具循环结束后，对最终回复加工 |
| **可观测出口（指标上报）** | `Integer.MAX_VALUE`（最内） | 抓到的是最终要返回给用户的内容 |

> **不要全用 0**：项目里很多 advisor 都用默认 0 会导致顺序不可控。

---

## 3. before/after 执行顺序的可视化

假设 advisor 链按 order 排序后是 `A(order=0) → B(order=100) → C(order=200)`：

```
用户请求 ──→ A.before ──→ B.before ──→ C.before ──→ ChatModel.call
                                                       │
用户响应 ←── A.after  ←── B.after  ←── C.after  ←──────┘
```

**关键事实**：

1. **before 阶段**：order 升序。A 最先看到原始请求，C 最后看到（已经被 A、B 改过）。
2. **after 阶段**：order 降序。C 最先看到 LLM 原始响应，A 最后看到（已经被 C、B 加工过）。
3. **A 是"最外层"**：它能看到完整的"请求 → 响应"过程，最适合日志/计时/审计。
4. **C 是"最内层"**：它最贴近 LLM，最适合改 prompt 或加工 LLM 原始输出。

---

## 4. 流式下的 order 行为（**和同步不同**）

流式（`.stream()`）下，`before` 仍然按 order 升序执行一次，但 `after` 是**在每个 chunk 流过时执行**，不是等所有 chunk 收完。

这意味着：

- `before` 里改 prompt：**有效**（一次性，影响整个流）
- `after` 里改单个 chunk 的响应：**有效**（每个 chunk 都会过）
- `after` 里想基于"完整响应"判断（比如检测 tool call）：**必须自己聚合**

Spring AI 提供了 `ChatClientMessageAggregator` 帮你在流里聚合：

```java
new ChatClientMessageAggregator()
    .aggregateChatClientResponse(flux, aggregated -> {
        // 这里能拿到聚合后的完整 ChatClientResponse
        // 但注意：此时 chunk 已经流出去了，这里只能"旁路观察"
    });
```

---

## 5. 自定义 Advisor 实战模板

### 5.1 计时 Advisor（同时实现 Call+Stream）

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
    public int getOrder() { return HIGHEST_PRECEDENCE + 100; }

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

### 5.2 Prompt 增强 Advisor（注入 system 消息）

```java
public class PromptEnhanceAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        // ❌ 错：原地改
        // req.prompt().getUserMessage().setText("...");

        // ✅ 对：用 mutate 派生新对象
        return req.mutate()
                .prompt(req.prompt().mutate()
                        .userMessage(new UserMessage("..."))
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        return resp;
    }

    @Override public String getName() { return "PromptEnhanceAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }   // Memory 内、Tool 外
}
```

### 5.3 结果脱敏 Advisor

```java
public class PiiMaskAdvisor implements BaseAdvisor {

    private final PiiFilter filter;

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        String masked = filter.mask(resp.chatResponse().getText());
        // 用 mutate 返回新响应
        return resp.mutate()
                .chatResponse(/* 替换 text 的 chatResponse */)
                .build();
    }

    @Override public String getName() { return "PiiMaskAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 298; }   // Tool 内层
}
```

---

## 6. 结构化输出校验

### 6.1 问题：LLM 输出的 JSON 不合规

LLM 经常输出"接近但不完全"的 JSON：

```json
{
  "name": "张三",
  "age": "30",          // 应该是数字
  "email": "invalid"    // 邮箱格式错
}
```

直接 `entity(Person.class)` 反序列化会抛异常。

### 6.2 解决：StructuredOutputValidationAdvisor

Spring AI 2.0 提供 `StructuredOutputValidationAdvisor`，自动校验 + 重试：

```java
@Bean
public Advisor validationAdvisor() {
    return StructuredOutputValidationAdvisor.builder()
            .validator(new BeanOutputConverter<>(Person.class))
            .maxAttempts(3)
            .build();
}

// 使用
Person person = chatClient.prompt()
        .user("生成一个虚构人物")
        .advisors(validationAdvisor)
        .call()
        .entity(Person.class);
```

工作流程：

1. LLM 输出 JSON
2. Advisor 用 validator 校验
3. 不通过 → 把错误信息 + 原 JSON 喂回 LLM
4. LLM 修正后重试（最多 3 次）

### 6.3 自定义校验器

```java
public class PersonValidator implements Validator<Person> {

    @Override
    public ValidationResult validate(Person person) {
        List<String> errors = new ArrayList<>();
        if (person.age() < 0 || person.age() > 150) {
            errors.add("age 必须在 0-150 之间");
        }
        if (!person.email().matches("^[\\w.-]+@[\\w.-]+$")) {
            errors.add("email 格式不合法");
        }
        return errors.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(errors);
    }
}
```

---

## 7. order 实战案例：流式 + 工具 + Memory 的正确排序

### 7.1 错误的默认组合

```java
.defaultAdvisors(
    toolCallingAdvisor,                              // order = -2147483348（默认）
    MessageChatMemoryAdvisor.builder(memory).build() // order = -2147483448（默认）
)
// → Memory 在外（小），Tool 在内（大）
```

**看起来对，但**：自定义 ChatConfig 时如果同时改了 Tool 的 order 到 `Integer.MIN_VALUE`，就会反过来 —— Tool 在外、Memory 在内，触发 `conversationId cannot be null` 500 错误。

### 7.2 显式稳妥的写法

```java
@Bean
public ToolCallingAdvisor toolCallingAdvisor(ToolCallingManager mgr) {
    return ToolCallingAdvisor.builder()
            .toolCallingManager(mgr)
            .advisorOrder(100)
            .build();
}

.defaultAdvisors(
    MessageChatMemoryAdvisor.builder(memory).order(0).build(),  // 外层
    toolCallingAdvisor,                                          // 内层
    new SimpleLogAdvisor()
)
```

**why**：Memory 在 Tool 外层时，Tool 的内部循环（多轮 tool call）完全发生在 Memory 的 before/after 之间，Memory 在 `after` 时拿到的还是第一轮的 context（含 `CONVERSATION_ID`），不会 NPE。

### 7.3 顺序决策树

```
我的 Advisor 需要看到工具调用的中间过程吗？
├── 是（比如记录每次 tool call 的耗时）
│   └── order 设在 Tool 外层（< 100），让 Tool 循环在你 inside
└── 否（只关心最终用户看到的回复）
    └── order 设在 Tool 内层（> 100），工具循环结束后再走你的逻辑
```

---

## 8. 常见误区

### 8.1 "我设了 order 但没生效"

**原因 1**：你 override 的是 `Advisor.getOrder()`，但你的 advisor 实例化时构造器里硬编码了 order，构造器优先级高。

**原因 2**：你把 advisor 加进了 `defaultAdvisors(...)`，但调用时又用 `.advisors(spec -> ...)` 加了同名 advisor，导致重复。

### 8.2 "before 改了 prompt，但 LLM 没看到"

**原因**：你在 before 里改的不是 `ChatClientRequest`，而是直接改了 `Prompt` 对象的内部状态。Spring AI 是不可变设计，before 必须**返回一个新的 `ChatClientRequest`**（用 `mutate()` 派生）。

### 8.3 "after 在流式下不触发"

**原因**：你只实现了 `CallAdvisor`，没实现 `StreamAdvisor`。

**修复**：改 `implements BaseAdvisor`，自动覆盖两种模式。

### 8.4 "order 全用 0，偶尔行为飘忽"

Spring AI 在 `BaseAdvisorChain` 内部对同 order 的 advisor 用注册顺序兜底，但这个顺序在不同版本可能变化。**永远给自定义 Advisor 显式 order**。

---

## 9. 为什么项目里的 SimpleLogAdvisor 不用 BaseAdvisor

项目里的 `org.demo02.advisor.SimpleLogAdvisor` 走的是 `implements CallAdvisor, StreamAdvisor`（不是 BaseAdvisor）。原因是它要在流式下做 `filter` 过滤空 chunk 和 `doOnNext` 逐 chunk 打印 —— 这是 Reactor 流式特有的操作，BaseAdvisor 的 `after` 模板做不了这种"逐 chunk 操作"。

**学习点**：当你的流式逻辑需要 Reactor 操作符（filter/map/buffer/flatMap），就用 `implements CallAdvisor, StreamAdvisor`；如果只是改 prompt 或对最终响应做一次性处理，用 `BaseAdvisor`。

---

## 10. 理解检查

1. 能说出 `MessageChatMemoryAdvisor` 和 `ToolCallingAdvisor` 的默认 order 数值和差值含义
2. 能解释"order 越小越靠外"在 before/after 两个阶段分别意味着什么
3. 知道自定义 Advisor 推荐插入的 order 插槽（200/300/400 之间）
4. 能在 30 秒内判断"该用 BaseAdvisor 还是 implements CallAdvisor + StreamAdvisor"
5. 知道流式下 `after` 是逐 chunk 触发，要观察完整响应必须用 `ChatClientMessageAggregator`
6. 能解释为什么项目里的 `SimpleLogAdvisor` 不用 BaseAdvisor（因为流式需要 Reactor 操作符）

---

## 11. 相关文档

- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— Tool 基础
- [`./04-流式响应与Reactor深度.md`](./04-流式响应与Reactor深度.md) —— 流式下 Advisor 行为
- [Spring AI Advisors Reference](https://docs.spring.io/spring-ai/reference/api/advisors.html)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
