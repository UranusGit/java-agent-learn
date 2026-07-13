# Agent 02 - 防止 Agent 失控

> Demo Agent 能跑就行，生产 Agent 必须**防失控**。
> 本节是 Agent 工程的"安全带"。

---

## 1. Agent 失控的 5 种典型场景

### 1.1 死循环调用

```
LLM: 我需要查询张三
  → 调 queryEmployee("张三")
  → 返回：not found
LLM: 没找到，再试一次
  → 调 queryEmployee("张三")
  → 返回：not found
LLM: 再试...
（无限循环，烧钱）
```

### 1.2 链式调用爆炸

```
LLM 想完成"创建用户并加权限"：
  → createUser("张三")  → 返回 userId
  → addPermission(userId, "read")
  → addPermission(userId, "write")
  → addPermission(userId, "delete")
  → addPermission(userId, "...")  // LLM 决定加 50 个权限
（成本失控）
```

### 1.3 超长上下文

```
LLM 一直调 Tool，每次返回大数据
  → context 越来越长
  → token 超限
  → 报错或自动截断（丢失关键信息）
```

### 1.4 工具失败导致挂死

```
LLM 调外部 API
  → API 30 秒不响应
  → LLM 等待
  → 用户等不及，重复请求
  → 队列堆积
```

### 1.5 幻觉工具调用

```
LLM: 调用工具 "deleteAllData"
（实际你只注册了 queryData，没有 deleteAllData）
LLM 编造了一个不存在的工具
```

---

## 2. 防御机制总览

| 防御 | 解决问题 | 实现难度 |
|------|---------|---------|
| **迭代次数上限** | 死循环 | 简单 |
| **总超时** | 工具失败挂死 | 简单 |
| **token 上限** | 上下文爆炸 | 简单 |
| **成本上限** | 链式调用爆炸 | 中等 |
| **循环检测** | 同参数重复调用 | 中等 |
| **Tool 校验** | 幻觉工具 / 错误参数 | 简单（框架内置） |
| **降级策略** | 模型不可用 | 中等 |

---

## 3. 实现：迭代次数上限

### 3.1 LangChain4j

```java
Assistant agent = AiServices.builder(Assistant.class)
        .chatModel(model)
        .tools(myTools)
        // 限制 LLM 最多做 5 次"决策"
        // LangChain4j 不同版本 API 略有差异，以当前文档为准
        // 通常通过 ChatMemory 大小间接限制
        .chatMemory(MessageWindowChatMemory.withMaxMessages(30))
        .build();
```

**关键认知**：
- 每次 LLM 调用（含 Tool 调用）会在 ChatMemory 里加消息
- 限制 memory 大小 = 间接限制迭代次数
- 推荐：**maxMessages = 5 * 期望迭代数**（每次迭代约 5 条消息）

### 3.2 Spring AI

Spring AI 没有直接的"迭代次数"配置，通过 Advisor 实现（基于 Spring AI 1.0.0 API）：

```java
public class IterationLimitAdvisor implements CallAdvisor {
    private static final int MAX_ITERATIONS = 10;

    @Override
    public String getName() { return "IterationLimitAdvisor"; }

    @Override
    public int getOrder() { return 0; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        Map<String, Object> ctx = req.context();
        Integer count = (Integer) ctx.getOrDefault("iterCount", 0);
        if (count >= MAX_ITERATIONS) {
            throw new RuntimeException("达到最大迭代数：" + MAX_ITERATIONS);
        }
        // ChatClientRequest 是不可变 record，修改 context 必须通过 mutate
        ChatClientRequest newReq = req.mutate()
                .context(new HashMap<>(ctx))  // 复制一份
                .build();
        newReq.context().put("iterCount", count + 1);
        return chain.nextCall(newReq);
    }
}
```

> 📌 1.0.0 的 Advisor API：方法名是 `adviseCall`（不是 `aroundCall`），入参是 `ChatClientRequest`（不是 `AdvisedRequest`），共享上下文通过 `req.context()` 拿（不是 `req.adviseContext()`），链推进用 `chain.nextCall(req)`（不是 `chain.nextAroundCall(req)`）。详见 [spring-ai/02-Advisor链](../spring-ai/02-Advisor链.md)。

---

## 4. 实现：总超时

### 4.1 包装 ChatClient

```java
@Component
public class TimeoutProtectedAgent {

    private final ChatClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public String ask(String q) throws Exception {
        Future<String> future = executor.submit(() -> {
            return client.prompt().user(q).call().content();
        });

        try {
            return future.get(60, TimeUnit.SECONDS);  // 总超时 60 秒
        } catch (TimeoutException e) {
            future.cancel(true);
            return "抱歉，处理超时，请稍后再试。";
        }
    }
}
```

### 4.2 更优雅：用 CompletableFuture

```java
public CompletableFuture<String> askAsync(String q) {
    return CompletableFuture.supplyAsync(() ->
            client.prompt().user(q).call().content())
        .orTimeout(60, TimeUnit.SECONDS)
        .exceptionally(ex -> "处理超时: " + ex.getMessage());
}
```

### 4.3 单 Tool 超时

```java
@Tool("查询天气")
public Weather getWeather(String city) {
    return weatherApi.query(city)
            .timeout(Duration.ofSeconds(5))   // 单 Tool 最多 5 秒
            .block();
}
```

---

## 5. 实现：token 上限

### 5.1 监控单次调用 token

```java
import dev.langchain4j.model.chat.response.ChatResponse;

ChatResponse response = model.chat(ChatRequest.builder()
        .messages(messages)
        .build());

TokenUsage usage = response.tokenUsage();
int totalTokens = usage.totalTokenCount();
if (totalTokens > 50000) {
    log.warn("Token usage high: {}", totalTokens);
}
```

### 5.2 Spring AI 配置

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          max-tokens: 1024   # 单次输出最多 1024 token
```

### 5.3 全局 token 上限（Spring AI Advisor）

> Spring AI 1.0.0 API。

```java
public class TokenLimitAdvisor implements CallAdvisor {
    private static final int MAX_TOTAL_TOKENS = 100_000;
    private final AtomicInteger totalUsed = new AtomicInteger(0);

    @Override
    public String getName() { return "TokenLimitAdvisor"; }

    @Override
    public int getOrder() { return 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        ChatClientResponse resp = chain.nextCall(req);
        int used = resp.chatResponse().getMetadata().getUsage().getTotalTokens();
        int now = totalUsed.addAndGet(used);
        if (now > MAX_TOTAL_TOKENS) {
            throw new RuntimeException("Token 配额已用尽");
        }
        return resp;
    }
}
```

---

## 6. 实现：循环检测

### 6.1 同参数重复调用检测

```java
@Component
public class LoopDetectionToolWrapper {

    private final Map<String, Integer> callHistory = new ConcurrentHashMap<>();

    public <T> T executeWithLoopDetection(String toolName, Object params, Supplier<T> action) {
        String key = toolName + ":" + params.hashCode();
        int count = callHistory.merge(key, 1, Integer::sum);

        if (count > 3) {
            throw new RuntimeException(
                "检测到循环调用: " + toolName + " 已调用 " + count + " 次，参数相同"
            );
        }

        return action.get();
    }
}
```

### 6.2 在 Tool 内使用

```java
@Tool("查询员工")
public EmployeeInfo queryEmployee(String name) {
    return loopDetector.executeWithLoopDetection(
        "queryEmployee", name,
        () -> repo.findByName(name)
    );
}
```

### 6.3 会话级清理

每次新会话开始时清空检测 Map，否则误报。

---

## 7. 实现：成本控制

### 7.1 实时成本计算

```java
@Component
public class CostMonitor {

    // DeepSeek 价格（参考）：输入 ¥1/M tokens，输出 ¥2/M tokens
    private static final BigDecimal INPUT_PRICE = new BigDecimal("0.000001");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("0.000002");

    private final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);

    public void record(int inputTokens, int outputTokens) {
        BigDecimal cost = INPUT_PRICE.multiply(BigDecimal.valueOf(inputTokens))
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(outputTokens)));
        totalCost.accumulateAndGet(cost, BigDecimal::add);

        if (totalCost.get().compareTo(new BigDecimal("10")) > 0) {
            log.warn("Cost exceeds ¥10, current: {}", totalCost.get());
        }
    }
}
```

### 7.2 接入 Advisor

> Spring AI 1.0.0 API。

```java
public class CostAdvisor implements CallAdvisor {
    private final CostMonitor monitor;

    @Override
    public String getName() { return "CostAdvisor"; }

    @Override
    public int getOrder() { return 100; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        ChatClientResponse resp = chain.nextCall(req);
        Usage usage = resp.chatResponse().getMetadata().getUsage();
        monitor.record(usage.getPromptTokens(), usage.getCompletionTokens());
        return resp;
    }
}
```

---

## 8. 实现：幻觉工具防护

### 8.1 框架内置

LangChain4j / Spring AI 都已经内置：
- 只发"实际存在的 Tool" schema 给 LLM
- LLM 编造的工具名，框架不执行，返回错误信息给 LLM

### 8.2 你需要做的

1. **不要在 system prompt 里提到没注册的工具**（LLM 会幻觉）
2. **Tool 描述里不要写"如需 X 功能..."**（如果没实现）
3. **校验 Tool 返回值**（防止 LLM 编造返回数据）

---

## 9. 实现：降级策略

### 9.1 主备模型切换

```java
public String ask(String q) {
    try {
        return primaryClient.prompt().user(q).call().content();
    } catch (Exception e) {
        log.warn("Primary model failed, falling back", e);
        return fallbackClient.prompt().user(q).call().content();
    }
}
```

### 9.2 失败响应模板

```java
@Tool("查询天气")
public Object getWeather(String city) {
    try {
        return weatherApi.get(city);
    } catch (Exception e) {
        return Map.of(
            "status", "degraded",
            "message", "天气服务暂时不可用",
            "fallback", "建议查看手机自带天气应用"
        );
    }
}
```

---

## 10. 整体防御架构（生产级）

```java
@Bean
ChatClient productionClient(ChatClient.Builder builder) {
    return builder
            .defaultSystem("...")
            .defaultAdvisors(
                new RateLimitAdvisor(100),           // 每分钟最多 100 请求
                new CostMonitorAdvisor(¥10),         // 单会话成本上限
                new TokenLimitAdvisor(100_000),      // 总 token 上限
                new IterationLimitAdvisor(10),       // 最大迭代
                new LoopDetectionAdvisor(),          // 循环检测
                new LoggingAdvisor(),                // 日志
                new MessageChatMemoryAdvisor(...),                // 记忆
                new RetrievalAugmentationAdvisor(...)             // RAG（1.0.0 入口，来自 spring-ai-rag）
            )
            .build();
}
```

**Advisor 链顺序**（按 `getOrder()`）：
```
RateLimit → Cost → Token → Iteration → Loop → Logging → Memory → RAG
（外层阻断优先）                       （内层业务功能）
```

---

## 11. 监控告警

### 11.1 关键指标

| 指标 | 告警阈值 |
|------|---------|
| 单请求耗时 P99 | > 60s |
| 单请求 token | > 50K |
| Tool 调用失败率 | > 5% |
| 死循环检测命中 | > 0 |
| 总成本（每小时） | > ¥100 |

### 11.2 接入 Prometheus

```java
@Timed(value = "agent.call.duration", description = "Agent call duration")
@Counted(value = "agent.call.count", description = "Agent call count")
public String ask(String q) { ... }
```

---

## 12. 常见错误

### 12.1 防御太严导致正常请求被拒

**症状**：合法请求被限流。
**解决**：阈值要基于压测，不要拍脑袋。

### 12.2 循环检测误报

**症状**：正常多次调 Tool（如分页查询）被拦截。
**解决**：检测 key 包含参数 hash，相同参数才算循环。

### 12.3 成本告警刷屏

**症状**：每次调用都告警。
**解决**：用滑动窗口 + 累计阈值，不要每次都告警。

---

## 13. 理解检查

1. 死循环和链式爆炸的区别？分别怎么防？
2. 单次请求超时和总 token 上限，哪个更重要？
3. 循环检测的 key 应该包含什么？
4. 为什么 Advisor 链顺序很重要？
5. 监控 Agent 时，最关键的 3 个指标是什么？

---

## 14. 练习任务

1. 给你的 LangChain4j Agent 加迭代上限（通过 ChatMemory 大小）
2. 给 Tool 加单次超时（5 秒）
3. 实现 `LoopDetectionToolWrapper`，测试 LLM 重复调 Tool 时是否被拦截
4. 实现 `CostMonitor`，统计每次请求的成本
5. （进阶）用 Spring AI Advisor 实现完整的"防失控链"
6. （进阶）接 Prometheus + Grafana，做实时监控

完成后进入 [03-多 Tool 编排](./03-多Tool编排.md)。
