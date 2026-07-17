# 22 Prompt 工程深入（CoT / ToT / ReAct / Self-Consistency）

> Prompt 不是"写一段话"，而是**和模型协作的接口设计**。本文系统化拆解五类高阶 Prompt 模式，并给出在 Spring AI 2.0 里的可落地的代码模板。
>
> 前置：[`./01-2.0基础重塑.md`](./01-2.0基础重塑.md) + [`./03-Advisor链全解.md`](./03-Advisor链全解.md)
> 预计：1.5 天

---

## 0. 认知地图

```
L1：SystemPrompt / Few-shot / Temperature       ← 99% 项目停在这一层
        ↓
L2：CoT / Self-Consistency / Reflexion          ← 让 LLM 像一个会"思考"的人
        ↓
L3：ToT / GoT / ReAct / Plan-Execute            ← 多分支探索 + 工具协作
        ↓
L4：Prompt as Code（模板化 + 版本化 + 评估闭环）  ← 工程化，配套 11 篇评估闭环
```

> ⚠️ **现代 LLM 的范式迁移**：自 GPT-o1 / Claude 3.7 / DeepSeek-R1 起，模型本身已内置"thinking"阶段（隐式 CoT）。**显式 CoT 在推理模型上的增益变小**。但 ToT、ReAct、Self-Consistency 在 Agent / 工具协作场景依然有效。本文同时覆盖两种范式。

---

## 1. Prompt 五要素

一个"高质量 Prompt"由五部分构成，缺一不可：

| 要素 | 作用 | Spring AI 中的位置 |
|------|------|------------------|
| **Role** | 角色设定，约束风格 | `defaultSystem(TextTemplate)` |
| **Task** | 明确做什么（动词 + 宾语 + 输出格式） | user message |
| **Context** | 给模型的背景（用户画像、历史、检索结果） | user message + advisor 注入 |
| **Constraint** | 约束（长度、风格、禁忌、语言） | system message |
| **Format** | 输出结构（JSON Schema / Markdown / 表格） | `bean(Class)` / `StructuredOutputValidationAdvisor` |

**反模式**：把五要素全部塞进一段 user message，没有任何结构。模型很容易丢约束。

**正模式**：

```java
// 本代码仅作学习材料参考
String system = """
        你是公司客服助手「小帮」。
        # 约束
        - 回答不超过 200 字
        - 涉及金额必须给出订单号
        - 不确定的事直接说"我帮您确认一下"，不要编造
        # 风格
        - 友好但简洁
        - 用第二人称
        """;

String user = """
        # 用户
        张三（VIP 客户，历史投诉 0 次）
        
        # 问题
        {question}
        
        # 检索到的相关 FAQ
        {faq}
        
        请按 JSON 输出：{{"answer": "...", "confidence": 0~1, "needsHuman": true/false}}
        """;

chatClient.prompt()
        .system(s -> s.text(system))
        .user(u -> u.text(user).param("question", q).param("faq", faq))
        .call()
        .entity(Answer.class);
```

---

## 2. Zero-shot / Few-shot / Many-shot

### 2.1 选择标准

| 样本数 | 适用 | 注意 |
|-------|------|------|
| 0 | 通用任务（翻译、改写） | 写清楚 Format 即可 |
| 1-3（Few-shot） | 边界明确的分类、抽取 | 样本要覆盖正负类 |
| 5+（Many-shot） | 风格强、规则多、复杂输出 | 警惕 token 成本；考虑用 long-context 模型 |

### 2.2 Few-shot 模板（Spring AI）

```java
// 本代码仅作学习材料参考
String system = """
        你是情感分类器。下面是几个示例：
        
        输入：今天天气真好 → 输出：积极
        输入：我丢了钱包 → 输出：消极
        输入：电梯里有人 → 输出：中性
        
        现在请分类：
        """;

String answer = chatClient.prompt()
        .system(system)
        .user(input)
        .call()
        .content();
```

### 2.3 Few-shot 的"坑"

- **示例顺序敏感**：把正例放在最后，模型倾向输出正类（recency bias）。
- **示例风格就是隐式指令**：示例都写"积极/消极/中性"三字，模型不会写长答案。
- **示例不要泄露测试集**：评估时 few-shot 必须来自训练集/独立集，否则指标虚高。

---

## 3. CoT（Chain-of-Thought）

### 3.1 何时还有效

- 用的是**非推理**模型（GPT-4o、Claude 3.5、Qwen-Max、DeepSeek-V3 chat）。
- 任务是**多步数学/逻辑/因果推断**。
- 输出长度允许"think aloud"。

### 3.2 两种触发方式

**方式 A：零样本 CoT（最简单）**

```
... [问题] ...
Let's think step by step.
```

或中文：

```
请一步一步分析后再给出答案。
```

**方式 B：Few-shot CoT**

```
示例 1
Q：...
A：首先...；其次...；所以答案是 X。

示例 2
...

新问题：...
```

### 3.3 在 Spring AI 里强制 CoT

```java
// 本代码仅作学习材料参考
public record ReasoningAnswer(String reasoning, String answer, double confidence) {}

@Bean
ChatClient cotClient(ChatClient.Builder b) {
    return b.defaultSystem("""
            你是一个严谨的分析助手。
            必须先在 reasoning 字段写出 ≤ 5 步的推理过程，再在 answer 字段给出最终答案。
            不要在 reasoning 中给出最终结论。
            """).build();
}

ReasoningAnswer r = cotClient.prompt()
        .user(q)
        .call()
        .entity(ReasoningAnswer.class);
```

> 推理模型（o1 / R1）：**不要**强制 CoT——模型内部已经做了，外层写反而降低效果。

---

## 4. Self-Consistency

### 4.1 原理

让模型对**同一问题**生成 K 条不同的 CoT 路径，**投票**选最常见的答案。

```
question → [CoT run 1] → A
        → [CoT run 2] → B
        → [CoT run 3] → A
        → [CoT run 4] → A
        → [CoT run 5] → C
答案 = A（多数票）
```

### 4.2 落地（temperature=0.7，并发 K 路）

```java
// 本代码仅作学习材料参考
public <T> T selfConsistent(String question, Class<T> type, int k) {
    List<T> results = IntStream.range(0, k).parallel().mapToObj(i ->
            chatClient.prompt()
                    .system(s -> s.text(COT_SYSTEM))
                    .user(question)
                    .options(o -> o.temperature(0.7))
                    .call()
                    .entity(type)
    ).toList();

    // 投票：取出现次数最多的（要求 T 实现了 equals/hashCode 或 record）
    return results.stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow();
}
```

### 4.3 成本权衡

- K=5 是性价比拐点（增益趋缓，但成本线性增长）。
- 推理模型不需要 Self-Consistency——已经内部采样。
- 仅对**有明确答案**的任务（数学、抽取）有效，开放式生成不适用。

---

## 5. Reflexion（自我反思）

让模型评估自己的回答，发现问题后重写。

```
Round 1: question → answer_v1
Round 2: question + answer_v1 → critique（"我发现 X 不严谨"）
Round 3: question + answer_v1 + critique → answer_v2
```

```java
// 本代码仅作学习材料参考
public String reflexion(String q, int maxRounds) {
    String answer = chatClient.prompt().system(WRITER_SYSTEM).user(q).call().content();
    for (int i = 0; i < maxRounds; i++) {
        String critique = chatClient.prompt()
                .system(CRITIC_SYSTEM)
                .user(u -> u.text("""
                        问题：{q}
                        当前答案：{a}
                        请指出答案中的逻辑漏洞、事实错误、遗漏。
                        """)
                        .param("q", q).param("a", answer))
                .call().content();
        if (critique.contains("无问题")) break;

        answer = chatClient.prompt()
                .system(WRITER_SYSTEM)
                .user(u -> u.text("""
                        问题：{q}
                        上一版答案：{a}
                        反思意见：{c}
                        请修正并给出新答案。
                        """)
                        .param("q", q).param("a", answer).param("c", critique))
                .call().content();
    }
    return answer;
}
```

**适用**：写作、代码生成、复杂推理。**不适用**：低延迟对话（一轮变三轮）。

---

## 6. ToT（Tree-of-Thoughts）

CoT 是链（一条路走到底），ToT 是树（多分支探索 + 剪枝）。

```
                根：问题
              /     |     \
        分支A   分支B   分支C
         |       |       |
       评估 A   评估 B   评估 C   ← 给每个分支打分
         ✓       ✗       ✓
        /              \
      继续A1, A2     继续C1, C2
```

适合：**有明确目标可评估**的问题（24 点游戏、迷宫、复杂规划）。

### 6.1 简化版 ToT（在 Spring AI 里）

```java
// 本代码仅作学习材料参考
public String tot(String problem) {
    // 1. 生成 K 个候选思路
    List<String> thoughts = IntStream.range(0, 3).mapToObj(i ->
            chatClient.prompt().system(EXPAND_SYSTEM)
                    .user(problem).call().content()
    ).toList();

    // 2. 给每个思路打分
    record Scored(String thought, double score) {}
    List<Scored> scored = thoughts.stream().map(t -> {
        double s = score(problem, t);
        return new Scored(t, s);
    }).sorted((a, b) -> Double.compare(b.score, a.score)).toList();

    // 3. 取 top-1 继续展开
    return expand(problem, scored.get(0).thought());
}

private double score(String problem, String thought) {
    String s = chatClient.prompt().system(SCORER_SYSTEM)
            .user(u -> u.text("问题：{p}\n思路：{t}\n请打分 0~1")
                    .param("p", problem).param("t", thought))
            .call().content();
    return Double.parseDouble(s.replaceAll("[^0-9.]", ""));
}
```

### 6.2 ToT vs ReAct

- **ToT**：纯"想"，每步是"思路片段"，靠评估器打分。
- **ReAct**：想 + 行动（Thought-Action-Observation），行动是调工具。

复杂规划用 ToT，需要外部数据用 ReAct。

---

## 7. ReAct（Reasoning + Acting）

最经典的 Agent Prompt 模式，本质就是"边想边查工具"。

### 7.1 单轮 ReAct Prompt

```
你是一个能用工具解决问题的助手。
可用工具：
- search(query): 网络搜索
- calculator(expr): 计算

用户问题：{q}

按以下循环工作：
Thought: 我先理解问题...
Action: search("...")
Observation: ...
Thought: 接下来...
Action: calculator("...")
Observation: ...
...
Final Answer: ...
```

> Spring AI 2.0 里你**不需要手写 ReAct 模板**——`ToolCallingAdvisor` 自动驱动这个循环（见 [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md)）。但理解 ReAct 有助于：
> - 调试 Tool 不被调用时看模型 output 是不是没遵循格式
> - 自己实现 ReAct 变体（如 Reason-then-Plan-then-Act）

### 7.2 Plan-and-Execute（ReAct 升级版）

ReAct 的痛点：模型"边走边想"，容易陷入局部最优。Plan-and-Execute 改为：

```
阶段 1：Planner 一次性生成完整 plan（拆成 N 个子任务）
阶段 2：Executor 依次执行每个子任务（可调工具）
阶段 3：Replanner 看执行结果，决定是 replan 还是收尾
```

详见 [`./09-多Agent编排实战.md`](./09-多Agent编排实战.md) 的 Planner-Executor 模式。

---

## 8. 结构化输出：让 Prompt 落地成代码

### 8.1 三层保障

```java
// 本代码仅作学习材料参考

// 层 1：JSON Schema 注入 prompt（让模型知道目标结构）
String system = """
        输出必须严格符合以下 JSON Schema：
        {schema}
        不要输出任何 JSON 之外的内容。
        """;

// 层 2：bean(Class) 自动反序列化
Answer a = chatClient.prompt().system(system).user(q).call().entity(Answer.class);

// 层 3：StructuredOutputValidationAdvisor 校验失败自动重试（2.0 新）
```

### 8.2 StructuredOutputValidationAdvisor

```java
// 本代码仅作学习材料参考
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;

@Bean
ChatClient client(ChatClient.Builder b) {
    return b.defaultAdvisors(
            StructuredOutputValidationAdvisor.builder()
                    .maxAttempts(3)
                    .build()
    ).build();
}
```

模型第一次返回的 JSON 缺字段 / 类型错时，Advisor 把错误信息塞回去让模型自我修复。

---

## 9. Prompt 模板化与版本化

裸字符串写 prompt 是反模式（见 [`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md)）。

### 9.1 用 ST 模板

Spring AI 2.0 内置 `StringTemplate` 风格的 `TextTemplate`：

```java
// 本代码仅作学习材料参考
import org.springframework.ai.template.st.StTemplateRenderer;

TextTemplate tpl = TextTemplate.builder()
        .renderer(StTemplateRenderer.builder().build())
        .template("""
                你是 {role}。
                任务：{task}
                约束：{constraint}
                """)
        .build();

String rendered = tpl.render(Map.of(
        "role", "客服", "task", "解答退款问题", "constraint", "不超过 200 字"));
```

### 9.2 用 PromptRepo（外部化）

```yaml
spring:
  ai:
    chat:
      prompts:
        location: classpath:/prompts/
```

```text
resources/prompts/
├── customer-service.v1.txt
├── customer-service.v2.txt
└── refund-writer.v1.txt
```

版本切换只改配置，A/B 测试用 11 篇的 PromptVersionRegistry。

---

## 10. Prompt 调试技巧

### 10.1 三层日志

```java
// 本代码仅作学习材料参考
@Bean
BaseAdvisor promptLogger() {
    return new BaseAdvisor() {
        @Override public ChatClientRequest before(ChatClientRequest r, AdvisorChain c) {
            log.info("[PROMPT]\n{}", r.prompt());
            return r;
        }
        @Override public ChatClientResponse after(ChatClientResponse r, AdvisorChain c) {
            log.info("[RESPONSE]\n{}", r.chatResponse());
            return r;
        }
        @Override public String getName() { return "PromptLogger"; }
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    };
}
```

### 10.2 单元化对比

写一个 `PromptTestRunner`，对同一问题用 N 个 Prompt 变体跑，记录答案 + token + 延迟，便于 diff。

### 10.3 在 Langfuse / Phoenix 里看 Prompt 血缘

详见 [`./14-可观测性与成本治理.md`](./14-可观测性与成本治理.md)。Prompt 每次改动都关联一次 eval run，方便回归。

---

## 11. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| 把约束全塞 user message | 模型记不全 | 拆 system / user / format 三段 |
| Few-shot 样本污染测试集 | 指标虚高 | few-shot 来自独立集 |
| 推理模型还硬塞 "step by step" | 效果变差 | 推理模型直接给问题 |
| 显式 CoT + 长 few-shot | token 爆炸 | 二选一 |
| Prompt 写死在代码里 | 无法 A/B | 走 PromptRepo（11 篇） |
| 没有 Format 约束 | 解析失败率高 | 上 StructuredOutputValidationAdvisor |
| 用 ToT 解决简单问题 | 成本 10 倍 | 简单任务用 CoT 或直接 call |
| Reflexion 用于实时对话 | 延迟翻倍 | 离线场景才用 |

---

## 12. 选型决策树

```
任务是开放生成（写作、总结）？
├── 是 → Zero/Few-shot + Format；可叠 Reflexion（离线）
└── 否 →
    任务有明确答案？
    ├── 是 → 推理模型？是 → 直接问
    │           否 → CoT + Self-Consistency（K=5）
    └── 否 →
        需要外部数据？
        ├── 是 → ReAct / Plan-Execute（用 ToolCallingAdvisor）
        └── 否 → ToT（多分支探索 + 剪枝）
```

---

## 13. 实战任务

1. 把项目里现有的 ChatClient 调用，按本文 §1 五要素重写，对比输出质量。
2. 实现一个 `ReflexionAdvisor`，自动给低置信度的回答做反思重写。
3. 对同一组数学题，跑 Zero-shot、CoT、Self-Consistency（K=5）三组，记录准确率（用 11 篇的 eval 框架）。
4. 用 ST 模板把项目所有 prompt 外部化到 `resources/prompts/`。
5. （进阶）实现 Plan-and-Execute 模式（Planner + Executor + Replanner），跑通一个"做菜推荐 Agent"。
6. （选做）调研 Re-Act 衍生范式（Reflexion-Agent / LATS），写一篇内部 wiki。

---

## 14. 理解检查

1. 五要素（Role / Task / Context / Constraint / Format）为什么不能全塞 user message？
2. CoT 在推理模型上为什么效果反而下降？
3. Self-Consistency 投票为什么要求"有明确答案"？
4. Reflexion 和 Fine-tuning 各自的适用场景？
5. ToT 和 ReAct 的核心差别？什么时候选 ToT？
6. Spring AI 2.0 里如何"强制"模型输出 JSON？

---

## 15. 相关文档

- [`./01-2.0基础重塑.md`](./01-2.0基础重塑.md) —— ChatClient 基础
- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— ReAct 的 Spring AI 实现
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 注入 prompt 的标准做法
- [`./09-多Agent编排实战.md`](./09-多Agent编排实战.md) —— Plan-and-Execute 模式
- [`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md) —— Prompt 版本化与评估
- [Anthropic: Prompt Engineering](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview)
- [OpenAI: Prompt Engineering Guide](https://platform.openai.com/docs/guides/prompt-engineering)
- [Tree of Thoughts Paper](https://arxiv.org/abs/2305.10601)
- [Self-Consistency Paper](https://arxiv.org/abs/2203.11171)
- [Reflexion Paper](https://arxiv.org/abs/2303.11366)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
