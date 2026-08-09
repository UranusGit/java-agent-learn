# 02 · Agent 可靠性工程

> 阶段：4 生产化 · 难度：⭐⭐⭐⭐⭐ · 预计：3 天
> 前置：[01 上下文工程](01-上下文工程.md)
> 产出：掌握 Agent 可靠性的核心方案——幂等 / 重试 / 补偿 / 持久化执行

---

## 你将学会

- 为什么 Agent 可靠性本质是分布式系统问题
- 幂等性设计：防止 Agent 重试导致副作用重复
- 持久化执行：用 Temporal 让 Agent 崩溃可恢复
- Resilience4j 三层熔断（工具级 / 查询级 / 系统级）
- 补偿事务：失败后回滚

---

## 为什么这是 Java 工程师的最大护城河

> 来源：[Temporal 官方博客](https://temporal.io/blog/from-ai-hype-to-durable-reality-why-agentic-flows-need-distributed-systems) + [行业调研](../调研/00-Agent架构师行业调研-2026.md)

**Agent 的失败模式（重试 / 幂等 / 补偿 / 超时 / 熔断）本质是分布式系统的经典问题。Python/算法工程师擅长调 prompt，但不擅长分布式系统——这正是 Java 工程师的主场。**

| Agent 问题 | 分布式系统对应 | Java 解决方案 |
|-----------|-------------|-------------|
| Agent 崩溃后无法恢复 | 无状态服务崩溃 | Temporal 持久化执行 |
| 工具重试导致发两封邮件 | 非幂等接口重试 | 幂等性 Key |
| 工具超时 | 服务超时 | Resilience4j TimeLimiter |
| 模型服务过载 | 服务雪崩 | Resilience4j 熔断器 |
| 多步骤部分失败 | 分布式事务 | Saga 补偿模式 |

---

## 知识讲解

### 1. 幂等性设计

**幂等**：同一个操作执行一次和多次效果相同。

```java
// ❌ 非幂等：Agent 重试会发多封邮件
@Tool(description = "发送邮件")
public String sendEmail(String to, String subject, String body) {
    mailSender.send(to, subject, body);  // 重试时又发一封！
    return "已发送";
}

// ✅ 幂等：用 idempotencyKey 防重复
@Tool(description = "发送邮件")
public String sendEmail(String to, String subject, String body) {
    String idempotencyKey = UUID.nameUUIDFromBytes(
        (to + subject).getBytes()).toString();

    // 检查是否已执行过
    if (redisTemplate.hasKey("email:" + idempotencyKey)) {
        return "邮件已发送过（幂等跳过）";
    }

    mailSender.send(to, subject, body);
    redisTemplate.opsForValue().set("email:" + idempotencyKey, "sent",
        Duration.ofDays(7));
    return "已发送";
}
```

> **原则**：所有有副作用的工具（发邮件/写数据库/调外部 API）**必须设计幂等性**。

### 2. Resilience4j 三层熔断

```java
// 层级 1：工具级熔断（单个工具）
@CircuitBreaker(name = "weatherTool", fallbackMethod = "weatherFallback")
@TimeLimiter(name = "weatherTool")
public CompletableFuture<String> getWeather(String city) {
    return CompletableFuture.supplyAsync(() -> {
        // 调用天气 API
    });
}
public String weatherFallback(String city, Exception e) {
    return "天气服务暂时不可用，请稍后重试";
}

// 层级 2：查询级熔断（LLM 调用层）
@CircuitBreaker(name = "llmCall", fallbackMethod = "llmFallback")
public String callLLM(String prompt) {
    return chatClient.prompt().user(prompt).call().content();
}

// 层级 3：系统级熔断（入口层）
@CircuitBreaker(name = "systemLevel")
public String handleRequest(String userQuery) {
    // ...
}
```

### 3. 持久化执行（Durable Execution）

> 来源：[行业调研](../调研/00-Agent架构师行业调研-2026.md)——Durable Agent Execution 是 2026 最热的可靠性方案。

**问题**：Agent 执行到第 5 步时服务器崩溃了——前 4 步的副作用（已发邮件）怎么处理？

**解决方案**：用 Temporal 把 Agent 执行包成"持久化工作流"——每一步都 checkpoint，崩溃后从断点恢复：

```java
// Temporal 工作流：Agent 的每一步都被持久化
@WorkflowInterface
public interface AgentWorkflow {

    @WorkflowMethod
    String executeAgentTask(String instruction);
}

public class AgentWorkflowImpl implements AgentWorkflow {

    @Override
    public String executeAgentTask(String instruction) {
        // 每一步都是 Activity——崩溃后自动从断点恢复
        String userInfo = activities.searchUser("张三");     // checkpoint 1
        String orders = activities.getOrders(userId);         // checkpoint 2
        boolean shouldEmail = activities.evaluateAmount(orders); // checkpoint 3

        if (shouldEmail) {
            // 幂等发送——即使恢复后重试也不会发两封
            activities.sendEmail(email, "优惠内容");           // checkpoint 4
        }

        return activities.generateSummary(userInfo, orders);  // checkpoint 5
    }
}
```

> **崩溃场景**：如果服务器在 checkpoint 3 后崩溃，Temporal 恢复时从 checkpoint 3 继续（不重新搜索用户、不重新查订单）。sendEmail 有幂等保护，即使重试也安全。

### 4. 补偿事务（Saga）

多步骤操作部分失败时，需要回滚已执行的操作：

```java
// 转账 Agent：扣款 → 加款 → 记录
// 如果加款失败，需要回滚扣款

public String transfer(String from, String to, BigDecimal amount) {
    try {
        // 正向操作
        deduct(from, amount);     // 步骤 1
        credit(to, amount);       // 步骤 2（如果失败）
        record(from, to, amount); // 步骤 3

    } catch (CreditFailedException e) {
        // 补偿：回滚步骤 1
        refund(from, amount);
        return "转账失败，已退回";
    }
}
```

---

## 验收检查

- [ ] 所有有副作用的工具有幂等设计
- [ ] 有 Resilience4j 三层熔断
- [ ] 理解持久化执行的概念（Temporal/Restate）
- [ ] 有补偿事务设计（至少一个 Saga 示例）
- [ ] 能解释"为什么 Agent 可靠性是分布式系统问题"

---

## 下一步

→ 下一篇：[03 可观测性建设](03-可观测性建设.md)
