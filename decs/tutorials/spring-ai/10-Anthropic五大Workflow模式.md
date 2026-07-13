# Spring AI 10 - Anthropic 五大 Workflow 模式

> 目标：把 Anthropic《Building Effective Agents》5 大 Workflow 模式用 Spring AI 2.0 单框架全部实现一遍。
> 前置：已完成 [01-09](./01-快速起步.md)，理解 ChatClient、Advisor、Tool。
>
> 理论基础：[`reference/选型与对比/09-企业级Java-AI架构选型真相.md §4`](../../reference/选型与对比/09-企业级Java-AI架构选型真相.md)

---

## 0. 核心认知：Workflow > Agent

Anthropic 2024-12 官方博客的核心论点：

> **能用工作流（确定性 DAG）解决的问题，不要用自主 Agent。**

| 维度 | Workflow（确定性） | Agent（自主决策） |
|------|------------------|----------------|
| 路径 | 预先编码 | LLM 自己决定 |
| 可控性 | 高 | 低 |
| 成本 | 可预测 | 不可预测 |
| 可靠性 | 高 | 有概率出错 |
| 调试 | 容易 | 难（每步都是 LLM 调用） |
| 适用 | 80%+ 企业场景 | 开放式研究、探索式任务 |

---

## 1. 模式 1：Prompt Chaining（串联）

### 1.1 场景

固定多步骤任务：写初稿 → 校对 → 翻译。

### 1.2 实现

```java
@Service
@RequiredArgsConstructor
public class ChainingService {
    private final ChatClient client;

    public String translateAfterReview(String topic) {
        // 步骤 1：写初稿
        String draft = client.prompt()
            .user("写一篇关于 %s 的 200 字短文".formatted(topic))
            .call().content();

        // 步骤 2：校对（带 gate，质量不达标可回到上一步）
        String reviewed = client.prompt()
            .user("校对以下文本，修正语法和逻辑问题：\n%s".formatted(draft))
            .call().content();

        // 步骤 3：翻译
        String translated = client.prompt()
            .user("翻译成英文：\n%s".formatted(reviewed))
            .call().content();

        return translated;
    }
}
```

### 1.3 适用场景

- 文档生成 + 润色 + 翻译
- 数据提取 + 格式化 + 入库
- 代码生成 + 测试生成 + 文档生成

### 1.4 反模式

❌ 不要用 Chaining 处理路径不确定的任务（用 Orchestrator-Workers）。

---

## 2. 模式 2：Parallelization（并行）

### 2.1 两种子模式

- **Sectioning（分段）**：把大任务拆成独立子任务并行执行
- **Voting（投票）**：同一任务跑多次/多模型，投票决定结果

### 2.2 Sectioning 实现

```java
@Service
@RequiredArgsConstructor
public class SectioningService {
    private final ChatClient client;

    public String summarizeLongDoc(String doc) {
        // 拆成 3 段
        List<String> sections = splitInto(doc, 3);

        // 并行总结
        List<String> partialSummaries = sections.parallelStream()
            .map(s -> client.prompt()
                .user("总结这段：\n%s".formatted(s))
                .call().content())
            .toList();

        // 汇总
        return client.prompt()
            .user("汇总以下摘要为完整总结：\n%s"
                .formatted(String.join("\n---\n", partialSummaries)))
            .call().content();
    }
}
```

### 2.3 Voting 实现

```java
@Service
@RequiredArgsConstructor
public class VotingService {
    private final ChatClient client;

    public String codeReviewVote(String code, int votes) {
        // 用不同 temperature 跑 N 次
        List<String> reviews = IntStream.range(0, votes).parallel()
            .mapToObj(i -> client.prompt()
                .system("你是严格的代码评审员")
                .user("评审这段代码：\n%s".formatted(code))
                .toolContext(ctx -> ctx.put("temperature", 0.7 + i * 0.1))
                .call().content())
            .toList();

        // 投票汇总（选最常见的 critical issue）
        return client.prompt()
            .user("从以下多份评审中找出共识问题：\n%s"
                .formatted(String.join("\n===\n", reviews)))
            .call().content();
    }
}
```

### 2.4 适用场景

- 长文档总结（Sectioning）
- 敏感决策（Voting，如医疗/法律）
- 多语言并行翻译

---

## 3. 模式 3：Routing（路由）

### 3.1 场景

客服分类：技术问题 → 技术支持；账单问题 → 财务；其余 → 通用。

### 3.2 实现

```java
@Service
@RequiredArgsConstructor
public class RoutingService {
    private final ChatClient client;

    public String handle(String userQuery) {
        // 步骤 1：分类（结构化输出）
        Category cat = client.prompt()
            .system("把用户问题分类为 TECHNICAL / BILLING / GENERAL，只返回分类")
            .user(userQuery)
            .call()
            .entity(Category.class);

        // 步骤 2：路由到不同 handler
        return switch (cat) {
            case TECHNICAL -> techHandler(userQuery);
            case BILLING   -> billingHandler(userQuery);
            case GENERAL   -> generalHandler(userQuery);
        };
    }

    private String techHandler(String q) {
        return client.prompt()
            .system("你是技术支持，回答技术问题")
            .tools(kbTool, logTool)
            .user(q).call().content();
    }
    // ... 其他 handler
}
```

### 3.3 适用场景

- 客服分流
- 多领域知识库路由
- 简单 vs 复杂查询分流（便宜模型 vs 贵模型）

---

## 4. 模式 4：Orchestrator-Workers（编排-工人）

### 4.1 场景

代码修改：编排者读需求 → 决定改哪些文件 → 派工人并行修改 → 汇总。

### 4.2 实现

```java
@Service
@RequiredArgsConstructor
public class OrchestratorService {
    private final ChatClient client;

    public String modifyCode(String requirement) {
        // 步骤 1：Orchestrator 决定派多少 Worker、派给谁
        List<SubTask> subTasks = client.prompt()
            .system("""
                你是编排者。根据需求拆成具体子任务，
                每个 subTask 含 targetFile 和 instruction
                """)
            .user(requirement)
            .call()
            .entity(new ParameterizedTypeReference<List<SubTask>>() {});

        // 步骤 2：Workers 并行执行
        List<String> results = subTasks.parallelStream()
            .map(task -> client.prompt()
                .system("你是代码修改工人，只负责一个文件")
                .tools(fileReadTool, fileWriteTool)
                .user("修改 %s：%s".formatted(task.targetFile(), task.instruction()))
                .call().content())
            .toList();

        // 步骤 3：汇总
        return client.prompt()
            .user("汇总修改结果：\n%s".formatted(String.join("\n", results)))
            .call().content();
    }
}
```

### 4.3 适用场景

- 代码重构、多文件协同修改
- 复杂研究任务（多个方向并行探索）

### 4.4 与 Parallelization 的区别

| 模式 | 任务分配 |
|------|---------|
| **Parallelization** | 预先知道拆法（固定段数/投票次数） |
| **Orchestrator-Workers** | LLM 动态决定拆法 |

---

## 5. 模式 5：Evaluator-Optimizer（评估-优化）

### 5.1 场景

写作润色循环：生成 → 评估 → 改进 → 再评估，直到通过。

### 5.2 实现

```java
@Service
@RequiredArgsConstructor
public class EvaluatorOptimizerService {
    private final ChatClient client;

    public String generateTillPass(String task, int maxIter) {
        String current = client.prompt()
            .system("你是初稿生成器")
            .user(task).call().content();

        for (int i = 0; i < maxIter; i++) {
            // Evaluator 评估
            EvalResult eval = client.prompt()
                .system("你是严格的评估者")
                .user("""
                    任务: %s
                    当前输出: %s
                    评估：是否合格？如不合格给出具体改进建议
                    """.formatted(task, current))
                .call()
                .entity(EvalResult.class);

            if (eval.passed()) return current;

            // Optimizer 改进
            current = client.prompt()
                .system("你是优化器，根据反馈改进输出")
                .user("原输出：%s\n反馈：%s".formatted(current, eval.feedback()))
                .call().content();
        }
        return current; // 超过 maxIter，返回最后版本
    }
}
```

### 5.3 适用场景

- 写作润色（多轮迭代直到达标）
- 代码 + 单元测试（生成→测试→修复→再测）
- 翻译质量优化

### 5.4 防失控

- 必须设 `maxIter`（防死循环）
- 必须有明确的 `passed()` 判断（不能让 LLM 永远"再改改"）
- 详见 [`tutorials/agent/02-防止Agent失控`](../agent/02-防止Agent失控.md)

---

## 6. 模式对比与选型

| 模式 | 适用 | LLM 自主度 | 实现难度 | 阶段 4 自研工具箱是否覆盖 |
|------|------|----------|---------|----------------------|
| Prompt Chaining | 固定多步骤 | 低 | ⭐ | `ChainingAdvisor` |
| Parallelization | 多视角/分段 | 低 | ⭐⭐ | `ParallelizationAdvisor` |
| Routing | 分类分流 | 低 | ⭐⭐ | `RoutingAdvisor` |
| Orchestrator-Workers | 动态拆分 | 中 | ⭐⭐⭐ | `OrchestratorWorkersAdvisor` |
| Evaluator-Optimizer | 迭代优化 | 中 | ⭐⭐⭐ | `EvaluatorOptimizerAdvisor` |

---

## 7. 何时该用真正的 Agent（而非 Workflow）

只有以下场景才用自主 Agent：
1. **任务路径无法预先编码**（如开放式研究）
2. **每一步都需要 LLM 自己决定下一步**（如 ReAct 推理）
3. **可接受不确定性**（研究/原型阶段）

**企业生产环境的比例**：Workflow 占 80%+，自主 Agent 占 < 20%。

---

## 8. 验收清单

- [ ] 5 种模式都至少有 1 个可运行的 demo
- [ ] 能讲清每种模式的适用场景和反模式
- [ ] 知道 Workflow 和 Agent 的边界
- [ ] Orchestrator-Workers demo 能动态拆分任务
- [ ] Evaluator-Optimizer demo 有 maxIter 防失控

---

## 9. 相关文档

- [`reference/选型与对比/09-企业级Java-AI架构选型真相.md`](../../reference/选型与对比/09-企业级Java-AI架构选型真相.md) —— Workflow > Agent 金科玉律
- [`reference/生产化与运营/12-ClaudeCode源码启示录.md`](../../reference/生产化与运营/12-ClaudeCode源码启示录.md) §1.3 —— 5 大模式借鉴
- [../agent/02-防止Agent失控.md](../agent/02-防止Agent失控.md) —— Evaluator-Optimizer 防失控
- Anthropic 官方博客《Building Effective Agents》（2024-12-19）
