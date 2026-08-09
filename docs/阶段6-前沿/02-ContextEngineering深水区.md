# 02 · Context Engineering 深水区

> 阶段：6 前沿 · 难度：⭐⭐⭐⭐⭐ · 预计：持续
> 前置：[01 Durable Agent Execution](01-DurableAgentExecution.md)

---

## 2026 前沿方向

> 来源：[行业调研](../调研/00-Agent架构师行业调研-2026.md)——**Agent 自主管理自己的 context budget** 是 2026 新趋势。

### 1. 自主 Context Budget

Agent 自己决定保留哪些历史、丢弃哪些——而不是工程师写死窗口策略：

```java
@Tool(description = "回顾对话历史，决定哪些信息值得保留。tokenBudget 是可用的 token 预算。")
public String compactHistory(String sessionId, int tokenBudget) {
    // LLM 自己评估：哪些历史消息对当前任务重要？
    return chatClient.prompt()
        .system("""
            你是上下文管理器。回顾对话历史，在 tokenBudget 内保留最重要的信息。
            删除：寒暄、重复信息、已完成任务。
            保留：关键决策、未完成任务、用户偏好。
            """)
        .user(getHistory(sessionId))
        .call().content();
}
```

### 2. KV Cache 优化

```
理解 Provider 层的 KV Cache 命中率：
- prefix caching：相同前缀的 prompt 复用缓存
- cache 控制标记：Anthropic 的 cache_control / DeepSeek 的 context caching
- 监控 cache hit rate：低于 50% 说明 prompt 结构有问题
```

### 3. Tool Masking 动态化

```java
// 根据任务类型动态选择工具子集
// 不是全部注册——只注册当前步骤需要的
public String executeStep(String step, String input) {
    Set<Object> requiredTools = toolPlanner.plan(step);  // LLM 决定需要哪些工具
    return chatClient.prompt()
        .user(input)
        .tools(requiredTools)  // 只注册必要的
        .call().content();
}
```

---

## Sourcegraph 四大支柱

> 来源：[Sourcegraph Context Engineering 指南](https://sourcegraph.com/blog/context-engineering)

1. **Retrieve**：精准检索相关信息
2. **Structure**：结构化组织 context
3. **Compress**：压缩冗余信息
4. **Prioritize**：按重要性排序

---

## 下一步

→ 下一篇：[03 AI SRE 自治](03-AISRE自治.md)
