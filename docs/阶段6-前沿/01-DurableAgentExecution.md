# 01 · Durable Agent Execution（持久化执行）

> 阶段：6 前沿 · 难度：⭐⭐⭐⭐⭐ · 预计：持续
> 前置：[阶段 5 毕业](../阶段5-架构师/06-项目P5-企业客服平台.md)
> 产出：深入掌握持久化 Agent 执行——2026 最热的可靠性方案

---

## 为什么这是最高优先级前沿

> 来源：[行业调研](../调研/00-Agent架构师行业调研-2026.md) + [Temporal 官方博客](https://temporal.io/blog/from-ai-hype-to-durable-reality-why-agentic-flows-need-distributed-systems)

Temporal 官方标题直白：**《From AI Hype to Durable Reality》**。Agent 需要分布式系统的纪律——durability、retries、idempotency。

**核心价值**：
- Agent 执行到第 5 步崩溃 → 从第 5 步自动恢复（不重做前 4 步）
- 每一步都持久化 → 不会因为进程重启而丢失
- 内置重试 + 补偿 → 不用手写

---

## 深入 Temporal

```java
// Agent 工作流：每一步都是 Activity，自动持久化
@WorkflowInterface
public interface CustomerServiceWorkflow {
    @WorkflowMethod
    String handle(String userQuery, String tenantId);
}

public class CustomerServiceWorkflowImpl implements CustomerServiceWorkflow {

    private final CustomerActivities activities;

    @Override
    public String handle(String query, String tenantId) {
        // 每一步都 checkpoint。崩溃后从这里恢复。
        String intent = activities.classifyIntent(query, tenantId);

        // 并行分支也是持久化的
        Promise<String> knowledgeResult = Async.function(
            activities::searchKnowledge, intent, tenantId);
        Promise<String> orderResult = Async.function(
            activities::queryOrders, intent, tenantId);

        // 等待两个分支
        String reply = activities.generateReply(
            knowledgeResult.get(), orderResult.get());

        // 评审（Evaluator-Optimizer）
        for (int i = 0; i < 3; i++) {
            boolean pass = activities.evaluate(reply);
            if (pass) break;
            reply = activities.improve(reply);
        }

        // 审计（append-only）
        activities.auditLog(tenantId, query, reply);

        return reply;
    }
}
```

**崩溃恢复场景**：

| 崩溃位置 | 恢复行为 |
|---------|---------|
| classifyIntent 之后 | 跳过已完成的步骤，从搜索知识库开始 |
| generateReply 之后 | 跳过生成，从评估开始 |
| auditLog 之后 | 工作流已完成，无需恢复 |

---

## Restate 替代方案

> Restate 是 Temporal 的轻量替代，更适合 Agent 场景：
> - 内置 Java SDK
> - 更简单的编程模型
> - 原生支持 LLM 调用的持久化

---

## 验收检查

- [ ] 理解持久化执行 vs 普通执行的区别
- [ ] 能用 Temporal 写一个 Agent 工作流
- [ ] 测试过崩溃恢复（kill 进程后自动恢复）
- [ ] 理解每一步 checkpoint 的意义

---

## 下一步

→ 下一篇：[02 Context Engineering 深水区](02-ContextEngineering深水区.md)
