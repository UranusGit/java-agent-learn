# L2 初级 - ToolCallingAdvisor 深入

> 把 L1 提到的 `ToolCallingAdvisor` 彻底吃透。本文是 Agent 应用的基石。
>
> 前置：[`./01-2.0基础重塑.md`](./01-2.0基础重塑.md)
> 预计：半天

---

## 1. 为什么需要 Advisor 来管 Tool

### 1.1 没有 Advisor 的世界（1.0）

```java
// 你必须自己写循环
Prompt prompt = new Prompt(new UserMessage(q));
while (true) {
    ChatResponse resp = chatModel.call(prompt);
    if (!resp.hasToolCalls()) {
        return resp.getResult().getOutput().getText();
    }
    ToolExecutionResult r = toolCallingManager.executeToolCalls(prompt, resp);
    prompt = new Prompt(r.conversationHistory());
}
```

**痛点**：
- 每个业务点都要复制这段循环
- 想加日志 / 限流 / 重试，要侵入循环内部
- 流式版本（`Flux<ChatResponse>`）的循环更复杂（要聚合 chunk）

### 1.2 有 Advisor 的世界（2.0）

```java
// 业务代码只有一行
String answer = chatClient.prompt().user(q).tools(myBean).call().content();
```

**`ToolCallingAdvisor` 帮你做了什么**：
1. 监听 ChatModel 的响应
2. 检测到 `hasToolCalls()`
3. 调用 `ToolCallingManager.executeToolCalls(...)`
4. 把工具结果拼进 conversation history
5. 再次调用 ChatModel（递归下一轮）
6. 直到 LLM 不再要工具

---

## 2. ToolCallingAdvisor 的执行图

```
┌──────────────────────────────────────────────────────────────────────┐
│ 用户业务代码：chatClient.prompt().user(q).tools(t).call()              │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │
                          ┌──────▼──────┐
                          │ Memory      │  ← 优先级 HIGHEST+200（外层）
                          │ Advisor     │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │ ToolCalling │  ← 优先级 HIGHEST+300（内层）
                          │ Advisor     │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │ ChatModel   │
                          │ .call()     │
                          └──────┬──────┘
                                 │
                ┌────────────────┼────────────────┐
                │ no tool calls  │ has tool calls │
                ▼                ▼                
           直接返回         ToolCallingManager.executeToolCalls
                                  │
                                  ▼
                          新的 conversation history
                                  │
                                  ▼
                          再次调用 ChatModel（递归下一轮）
```

---

## 3. @Tool 注解体系（2.0 完整版）

### 3.1 基础注解

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Component
public class WeatherTools {

    @Tool(description = "查询指定城市的天气")
    public Weather getWeather(
            @ToolParam(description = "城市中文名，如 '北京'") String city
    ) {
        return new Weather(city, 25.0, "晴");
    }

    public record Weather(String city, double temp, String condition) {}
}
```

### 3.2 2.0 新增：`required` 参数

```java
@Tool(description = "发送邮件")
public String sendEmail(
        @ToolParam(description = "收件人邮箱") String to,
        @ToolParam(description = "邮件主题") String subject,
        @ToolParam(description = "邮件正文") String body,
        @ToolParam(description = "抄送列表，可选", required = false) List<String> cc
) { ... }
```

`required = false` 告诉 LLM "这个参数可以不传"。1.0 没这个能力，所有参数都强制。

### 3.3 2.0 新增：`returnDirect`

```java
@Tool(description = "查询订单状态", returnDirect = true)
public OrderStatus getOrderStatus(@ToolParam("订单号") String orderId) {
    return orderService.findStatus(orderId);
}
```

`returnDirect = true` 表示**工具结果直接返回给用户**，不再喂回 LLM 让它总结。

**应用场景**：
- 结果是结构化数据，不需要 LLM 加工
- 节省一次 LLM 调用（省钱省时）
- 工具返回的是图片 URL / 富文本，LLM 加工反而会搞坏

### 3.4 2.0 新增：ToolContext 跨 Advisor 传递

```java
@GetMapping("/chat")
public String chat(@RequestParam String q, @RequestParam String userId) {
    return chatClient.prompt()
            .user(q)
            .toolContext(Map.of("userId", userId, "tenantId", "acme"))
            .tools(orderTools)
            .call()
            .content();
}

// Tool 方法
@Tool(description = "查询我的订单")
public List<Order> myOrders(ToolContext context) {
    String userId = (String) context.getContext().get("userId");
    return orderService.findByUser(userId);
}
```

**关键**：`ToolContext` 不算 LLM 的参数，LLM 看不到它。它是运行时给 Tool 方法用的"环境变量"。

---

## 4. ToolCallingManager：执行器

`ToolCallingAdvisor` 是**调度者**（管循环），`ToolCallingManager` 是**执行者**（真正调工具方法）。

### 4.1 默认实现

```java
@Bean
ToolCallingManager toolCallingManager() {
    return DefaultToolCallingManager.builder().build();
}
```

Spring AI 2.0 的 starter 默认注册这个 Bean，你通常不需要手写。

### 4.2 自定义：替换异常处理器

```java
@Bean
ToolCallingManager toolCallingManager() {
    return DefaultToolCallingManager.builder()
            .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
            // true = 总是抛异常，不让 LLM 看到
            // false（默认）= 把异常信息作为 tool result 返回给 LLM
            .build();
}
```

**两种策略对比**：

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| `alwaysThrow=true` | 工具失败直接抛，调用方 catch | 工具失败必须中止流程（如支付） |
| `alwaysThrow=false`（默认） | 把异常塞回 LLM，让 LLM 自我修复 | 大部分场景，参考 Claude Code 设计 |

**Anthropic 推荐**：让 LLM 看到失败（[Claude Code 源码启示录](../../reference/生产化与运营/12-ClaudeCode源码启示录.md)）。

### 4.3 配置项

```yaml
spring:
  ai:
    tools:
      throw-exception-on-error: false   # 默认 false，等价 alwaysThrow=false
      enable-logging: true              # 打印工具调用日志
```

---

## 5. 静态工具方法 vs 实例工具方法

### 5.1 实例方法（推荐）

```java
@Component
public class UtilTools {
    @Tool(description = "生成 UUID")
    public String uuid() {
        return UUID.randomUUID().toString();
    }
}

// 注入
@Bean
ChatClient chatClient(ChatClient.Builder builder, UtilTools utilTools) {
    return builder.defaultTools(utilTools).build();
}
```

适合：需要 Spring Bean 注入的工具。

### 5.2 静态方法

```java
public class UtilTools {
    @Tool(description = "生成 UUID")
    public static String uuid() {
        return UUID.randomUUID().toString();
    }
}

// 注册方式不同：用 ToolCallbacks.from(类.class)
ToolCallback[] callbacks = ToolCallbacks.from(UtilTools.class);
```

适合：纯函数、无状态、复用度高。

---

## 6. 动态工具：ToolCallback 接口

当工具定义需要运行时构造（数据库读 / 多租户），用 `ToolCallback` 接口：

```java
public class DynamicQueryTool implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("dynamic_query")
                .description("执行动态 SQL 查询")
                .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "sql": {"type": "string", "description": "SQL 语句"}
                      },
                      "required": ["sql"]
                    }
                    """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        // 自己解析 JSON 参数
        JsonObject args = JsonParser.parseString(toolInput).getAsJsonObject();
        String sql = args.get("sql").getAsString();
        return jdbc.queryForList(sql).toString();
    }
}
```

**注意**：`inputSchema` 必须是合法 JSON Schema 字符串，否则 LLM 看不懂。

---

## 7. ToolSearchToolCallingAdvisor：工具太多时

### 7.1 问题

当工具超过 10 个，所有工具 schema 都塞进 prompt → token 浪费 + LLM 决策变差。

### 7.2 2.0 解决方案

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tool-search-advisor</artifactId>
</dependency>
```

```java
import org.springframework.ai.chat.client.advisor.tool_search.ToolSearchToolCallingAdvisor;

@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
            .defaultTools(allMyTools)  // 注册全部
            .defaultAdvisors(
                    // 每次调用前先根据用户问题挑出相关的 5 个工具
                    ToolSearchToolCallingAdvisor.builder()
                            .maxResults(5)
                            .toolCallbacks(allCallbacks)
                            .build()
            )
            .build();
}
```

**效果**：内部用 embedding 做语义检索，只把最相关的工具 schema 塞进 prompt。

> 适合工具数量 10+ 的企业级 Agent。

---

## 8. 工具调用结果处理

### 8.1 简单类型

```java
@Tool(description = "查天气")
public String getWeather(String city) { ... }   // LLM 直接拿到字符串
```

### 8.2 自定义对象

```java
public record Weather(String city, double temp, String condition) {}

@Tool(description = "查天气")
public Weather getWeather(String city) {
    return new Weather("北京", 25.0, "晴");
}
```

Spring AI 自动用 Jackson 序列化 → LLM 收到 JSON。

### 8.3 集合 / Map

```java
@Tool(description = "列出所有部门")
public List<Department> listDepartments() { ... }
```

### 8.4 注意事项

- 返回值必须可序列化（避免循环引用）
- 大对象警惕 token 浪费（List 1000 元素 → 几万 token）
- 自定义对象字段名要语义清晰（`temperature` 比 `t` 好）

---

## 9. 实战：智能运维 Agent

### 9.1 工具集

```java
@Component
@RequiredArgsConstructor
public class K8sTools {

    private final KubernetesClient k8s;

    @Tool(description = "查询 Deployment 的副本数和就绪状态")
    public DeploymentStatus getDeploymentStatus(
            @ToolParam(description = "命名空间") String namespace,
            @ToolParam(description = "Deployment 名称") String name
    ) {
        Deployment dep = k8s.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return new DeploymentStatus(
                name, namespace,
                dep.getStatus().getReplicas(),
                dep.getStatus().getReadyReplicas()
        );
    }

    public record DeploymentStatus(String name, String namespace,
                                    int desired, int ready) {}
}

@Component
@RequiredArgsConstructor
public class PromTools {

    private final WebClient prom;

    @Tool(description = "查询 Prometheus 指标")
    public double queryMetric(@ToolParam(description = "PromQL 表达式") String query) {
        return prom.get()
                .uri(uri -> uri.path("/api/v1/query").queryParam("query", query).build())
                .retrieve()
                .bodyToMono(PromResponse.class)
                .map(PromResponse::value)
                .block();
    }
}
```

### 9.2 ChatClient 装配

```java
@Bean
ChatClient opsAgent(ChatClient.Builder builder, K8sTools k8s, PromTools prom) {
    return builder
            .defaultSystem("你是运维助手，能查 K8s 和 Prometheus 指标")
            .defaultTools(k8s, prom)
            .build();
}
```

### 9.3 调用示例

```
用户：用户服务有几个副本？CPU 高不高？

LLM：
  Action: getDeploymentStatus(namespace="default", name="user-service")
  Observation: {"name":"user-service","desired":3,"ready":3}

  Action: queryMetric(query="rate(container_cpu_usage_seconds_total{pod=~\"user-service.*\"}[5m])")
  Observation: 0.65

  Answer: 用户服务 3 个副本全部就绪，CPU 使用率约 65%。
```

整个循环 `ToolCallingAdvisor` 自动处理，业务代码零侵入。

---

## 10. 常见错误

### 10.1 `@Tool` 包引错

**症状**：Bean 加载报错或 LLM 看不到工具。

**原因**：引成了 LangChain4j 的 `dev.langchain4j.agent.tool.Tool`。

**解决**：用 `org.springframework.ai.tool.annotation.Tool`。

### 10.2 工具被调用两次

**症状**：日志里 `Executing tool: ...` 出现两次。

**原因**：手动模式忘了加 `AdvisorParams.toolCallingAdvisorAutoRegister(false)`。

**解决**：见 [`./04-流式响应与Reactor深度.md`](./04-流式响应与Reactor深度.md) §6。

### 10.3 ToolContext 丢失

**症状**：`toolContext.getContext().get("userId")` 返回 null。

**原因**：`toolContext()` 在 `prompt()` 链上没加，或者加了但是放在 `.call()` 之后。

**解决**：放在 `.call()` / `.stream()` 之前：
```java
chatClient.prompt()
        .user(q)
        .toolContext(Map.of("userId", uid))
        .tools(tools)
        .call();   // 顺序对
```

### 10.4 `returnDirect=true` 不生效

**症状**：还是 LLM 总结后才返回。

**原因**：用了流式（`.stream()`）。

**解决**：`returnDirect` 当前版本不支持流式，只能在 `.call()` 同步模式用。

### 10.5 ToolCallback 的 inputSchema 不合法

**症状**：LLM 报错"unable to parse tool definition"。

**排查**：用 JSON Schema Validator 校验你的 schema 字符串。

---

## 11. 理解检查

1. `ToolCallingAdvisor` 和 `ToolCallingManager` 的职责分别是什么？
2. `ToolContext` 解决了什么问题？什么场景下用？
3. `returnDirect = true` 和默认行为有什么区别？
4. 2.0 新增的 `required = false` 参数描述解决了什么问题？
5. 工具超过 10 个时应该用什么 Advisor？为什么？

---

## 12. 练习任务

1. 实现 `TimeTools` + `CalculatorTools`，让 LLM 自动串联调用两个工具
2. 用 `ToolContext` 传入 userId，工具根据 userId 过滤数据
3. 写一个 `returnDirect=true` 的工具，对比 LLM 是否还会总结
4. 实现一个 `ToolCallback` 动态工具（手动构造 schema）
5. 启动应用，发请求让 LLM 调用工具，看日志确认 ToolCallingAdvisor 自动循环
6. 故意写一个会抛异常的工具，配置 `alwaysThrow=false`，观察 LLM 是否自我修复

---

## 13. 进 L3 之前的能力确认

完成本篇你应该能：
- [ ] 不查资料说出 `ToolCallingAdvisor` 的优先级和工作流程
- [ ] 区分 `ToolCallingAdvisor` vs `ToolCallingManager` 的职责
- [ ] 熟练使用 `@Tool` / `@ToolParam` / `ToolContext` / `returnDirect`
- [ ] 排查"工具被调两次"等常见错误

> ⚠️ **自定义 `@Bean ChatClient` 的实战提醒**：本文 §4-§5 描述的「ChatClient 自动注册 ToolCallingAdvisor」仅在**没有自定义 ChatClient Bean** 时成立。一旦你写了 `@Bean ChatClient`，`ChatClientAutoConfiguration` 会被 `@ConditionalOnMissingBean` 短路，必须自己显式构造 `ToolCallingAdvisor` Bean 并加进 `defaultAdvisors`。完整复现过程（含流式下 `conversationId cannot be null` 500 错误的根因与修复）见 [`./04-流式响应与Reactor深度.md` §15](./04-流式响应与Reactor深度.md)。

完成后进入 [`./03-Advisor链全解.md`](./03-Advisor链全解.md)。
