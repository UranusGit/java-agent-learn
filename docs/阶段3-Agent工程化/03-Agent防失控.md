# 03 · Agent 防失控

> 阶段：3 Agent 工程化 · 难度：⭐⭐⭐ · 预计：1 天
> 前置：[02 五大 Workflow 模式](02-五大Workflow模式.md)
> 产出：实现 Agent 三重保护——maxTurns / 美元预算 / 死循环检测

---

## 你将学会

- Agent 失控的三种模式（无限循环 / 成本爆炸 / 死循环）
- 三重保护：maxTurns + 美元预算 + transitionReason 重复检测
- 用 Advisor 实现保护机制

---

## 为什么需要这个

Agent 是自主的——它可以自己决定调用多少次工具。如果出问题：

| 失控类型 | 后果 | 原因 |
|---------|------|------|
| 无限循环 | 永远不结束 | 没有终止条件 |
| 成本爆炸 | 烧光预算 | 每次循环都消耗 token |
| 死循环 | 看似在工作，实则原地转 | LLM 重复请求相同的工具 |

**没有三重保护的 Agent 不能上线。**

---

## 三重保护

### 保护 1：maxTurns（硬上限）

```java
// Agent 最多循环 10 次（硬终止）
int maxTurns = 10;

// 在 ToolCallingAdvisor 上配置
ChatClient client = ChatClient.builder(model)
    .defaultAdvisors(
        ToolCallingAdvisor.builder()
            .maxIterations(maxTurns)  // ← 硬上限
            .build()
    )
    .build();
```

### 保护 2：美元预算（成本上限）

```java
@Component
public class BudgetGuardAdvisor implements BaseAdvisor {

    private final AtomicLong totalTokens = new AtomicLong();
    private final long maxTokens = 50_000;  // 预算：5 万 token（约 ¥0.05）

    @Override
    public ChatClientResponse after(ChatClientResponse response) {
        long tokens = response.chatResponse().getMetadata().getUsage().getTotalTokens();
        long total = totalTokens.addAndGet(tokens);

        if (total > maxTokens) {
            throw new BudgetExceededException(
                "预算超限！已用 " + total + " tokens（预算 " + maxTokens + "）");
        }
        return response;
    }
}
```

### 保护 3：死循环检测（行为模式检测）

```java
@Component
public class LoopDetectionAdvisor implements BaseAdvisor {

    private final Deque<String> recentActions = new LinkedList<>();
    private static final int WINDOW_SIZE = 5;  // 检测最近 5 次操作

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        // 记录当前请求的特征（工具名 + 参数摘要）
        String signature = extractToolSignature(request);

        recentActions.addLast(signature);
        if (recentActions.size() > WINDOW_SIZE) {
            recentActions.removeFirst();
        }

        // 检测：如果最近 WINDOW_SIZE 次操作完全相同，判定为死循环
        if (recentActions.size() == WINDOW_SIZE && allSame(recentActions)) {
            throw new LoopDetectedException(
                "检测到死循环：Agent 连续 " + WINDOW_SIZE + " 次执行相同操作");
        }

        return request;
    }

    private boolean allSame(Deque<String> actions) {
        return new HashSet<>(actions).size() == 1;
    }
}
```

---

## 验收检查

- [ ] Agent 有 maxTurns 硬上限（不会无限循环）
- [ ] 有美元预算保护（超过预算自动终止）
- [ ] 有死循环检测（相同操作重复触发时终止）
- [ ] 能解释"为什么 Agent 需要三重保护"

---

## 下一步

→ 下一篇：[04 Prompt Injection 防御](04-PromptInjection防御.md)
