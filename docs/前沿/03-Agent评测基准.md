# 03-Agent 评测基准：如何衡量 Agent 的能力

> **定位**：本文调研当前主流的 Agent 评测基准（AgentBench、SWE-bench、GAIA、WebArena 等），分析它们的评估维度、方法论、局限性，以及在企业级场景中如何设计自己的 Agent 评测体系。读完本文，你将拥有一个系统化的 Agent 质量评估框架。
>
> **性质声明**：本文为调研性质，Agent 评测领域正处于快速发展期，新的基准和方法论不断涌现。

---

## 1. 为什么 Agent 评测极其困难

### 1.1 与传统软件测试的本质区别

传统软件测试有明确的"正确答案"——函数输入产生预期输出。但 Agent 的行为是 **非确定性的**——同一个 Prompt，同一个任务，Agent 可能走完全不同的推理路径，甚至给出措辞不同但语义等价的答案。

```mermaid
graph TB
    subgraph 传统测试["传统软件测试"]
        T1["输入：确定"] --> T2["程序：确定逻辑"] --> T3["输出：确定"]
        T4["断言：assert output == expected"]
    end

    subgraph Agent测试["Agent 评测困境"]
        A1["输入：自然语言<br/>（天然模糊）"] --> A2["LLM：概率推理<br/>（非确定性）"] --> A3["输出：开放式<br/>（无数正确答案）"]
        A4["断言：???<br/>如何判断'正确'？"]
    end

    传统测试 -.->|"范式不适用"| Agent测试

    style 传统测试 fill:#c8e6c9
    style Agent测试 fill:#ffcdd2
```

### 1.2 评测的四个维度

Agent 的复杂性要求我们从多个维度评估：

```mermaid
graph TB
    subgraph 评测维度["Agent 评测的四维框架"]
        D1["能力维度<br/>能不能做到？"]
        D2["效率维度<br/>做得好不好？"]
        D3["安全维度<br/>会不会出事？"]
        D4["成本维度<br/>花多少代价？"]
    end

    D1 --> M1["准确率 / 成功率<br/>工具调用正确率"]
    D2 --> M2["步数 / 延迟<br/>Token 消耗"]
    D3 --> M3["越界率 / 幻觉率<br/>安全违规率"]
    D4 --> M4["单任务成本<br/>$/task"]

    style 评测维度 fill:#e3f2fd
```

---

## 2. 学术界主流评测基准

### 2.1 AgentBench：综合能力基准

AgentBench（清华大学等，2023）是最早的系统化 Agent 综合评测基准，覆盖 8 个不同环境的 Agent 任务：

```mermaid
graph TB
    subgraph AgentBench["AgentBench 评测环境"]
        E1["操作系统<br/>Linux 终端操作"]
        E2["数据库<br/>SQL 查询"]
        E3["知识图谱<br/>实体推理"]
        E4["卡片游戏<br/>斗地主策略"]
        E5["横向思维谜题<br/>Lateral Thinking"]
        E6["居家场景<br/>Household（模拟器）"]
        E7["Web 购物<br/>WebShop"]
        E8["Web 浏览<br/>Mind2Web"]
    end

    style AgentBench fill:#e3f2fd
```

AgentBench 的核心指标是 **任务完成率（Success Rate）**——Agent 是否在限定步数内完成了任务。它的贡献在于首次提供了跨环境的标准化比较框架。

### 2.2 SWE-bench：软件工程基准

SWE-bench（Princeton，2023）是目前最具影响力的 Agent 编码能力基准。它的设计非常巧妙：从真实 GitHub 仓库（Django、Flask、Requests 等）中收集已解决的 Issue，让 Agent 自主完成"从 Issue 描述到提交修复代码"的全流程。

```mermaid
graph LR
    subgraph SWEbench["SWE-bench 任务流程"]
        ISSUE["输入：GitHub Issue<br/>（Bug 描述 / 功能需求）"]
        REPO["代码仓库<br/>（issue 发生时的版本）"]
        TEST["隐藏的测试用例<br/>（验证修复是否正确）"]

        AGENT["被测 Agent"]
        ISSUE --> AGENT
        REPO --> AGENT
        AGENT -->|"自主浏览代码<br/>定位 Bug<br/>编写修复<br/>运行测试"| PATCH["输出：代码补丁"]
        PATCH -->|"运行测试"| TEST
        TEST -->|"通过 / 失败"| SCORE["得分"]
    end

    style SWEbench fill:#e3f2fd
```

SWE-bench 的关键设计决策：

| 决策 | 选择 | 理由 |
|------|------|------|
| 任务来源 | 真实 GitHub Issue | 而非人造题——避免"刷榜" |
| 评判标准 | 单元测试通过 | 客观可验证——避免主观打分 |
| 环境 | 完整代码仓库 | 考验 Agent 的代码导航能力 |
| 输出格式 | Git diff 补丁 | 模拟真实开发工作流 |

SWE-bench 的结果揭示了一个残酷的现实：即使是最强的 Agent，解决率也只有约 40-50%（SWE-bench Lite），完整 SWE-bench 的解决率更低（20-30%）。

### 2.3 基准对比总览

```mermaid
graph TB
    subgraph 基准对比["Agent 评测基准对比"]
        subgraph 综合类["综合能力"]
            B1["AgentBench<br/>8 环境 / 综合评测"]
            B2["GAIA<br/>通用 AI 助手<br/>多模态 + 多步推理"]
        end

        subgraph 编码类["编码能力"]
            B3["SWE-bench<br/>真实 GitHub Issue 修复"]
            B4["HumanEval<br/>函数级代码生成"]
            B5["LiveCodeBench<br/>实时竞赛编程"]
        end

        subgraph Web类["Web 交互"]
            B6["WebArena<br/>多网站交互"]
            B7["Mind2Web<br/>网页操作理解"]
            B8["VisualWebArena<br/>视觉 Web 交互"]
        end

        subgraph 工具类["工具使用"]
            B9["ToolBench<br/>大规模 API 调用"]
            B10["τ-bench<br/>客服 Agent 双轮交互"]
        end

        subgraph 安全类["安全与对齐"]
            B11["HarmBench<br/>有害行为检测"]
            B12["AgentHarm<br/>Agent 安全评测"]
        end
    end

    style 综合类 fill:#e3f2fd
    style 编码类 fill:#e8f5e9
    style Web类 fill:#fff9c4
    style 工具类 fill:#ffe0b2
    style 安全类 fill:#ffcdd2
```

### 2.4 基准能力矩阵

| 基准 | 评估维度 | 任务数量 | 评判方式 | 局限性 |
|------|----------|---------|----------|--------|
| AgentBench | 综合能力 | ~300 | 成功率 | 环境偏简单 |
| SWE-bench | 编码修复 | 2,294 | 测试通过 | 仅限 Python |
| SWE-bench Lite | 编码修复（简化） | 300 | 测试通过 | 任务经过筛选 |
| GAIA | 通用助手 | 466 | 精确匹配 | 多模态依赖重 |
| WebArena | Web 操作 | 812 | 端状态验证 | 网站模型固定 |
| ToolBench | 工具调用 | 16,000+ | 工具调用准确率 | API 质量参差 |
| tau-bench | 客服对话 | 165 | 政策遵循 | 领域窄 |

---

## 3. 评估方法论

### 3.1 三种评估范式

```mermaid
graph TB
    subgraph 评估范式["Agent 评估的三种范式"]
        P1["1. 结果导向<br/>Outcome-based"]
        P2["2. 过程导向<br/>Process-based"]
        P3["3. LLM-as-Judge<br/>LLM 评判"]
    end

    P1 --> D1["只看最终结果是否正确<br/>如：测试是否通过"]
    P1 --> ADV1["优点：客观可量化"]
    P1 --> DIS1["缺点：忽略推理质量<br/>运气好也可能猜对"]

    P2 --> D2["逐步检查 Agent 的推理过程<br/>如：每步推理是否合理"]
    P2 --> ADV2["优点：能发现'蒙对'的案例"]
    P2 --> DIS2["缺点：评估成本极高<br/>需要人工标注"]

    P3 --> D3["用另一个 LLM 评判 Agent 输出<br/>如：GPT-4 打分 Claude 输出"]
    P3 --> ADV3["优点：可大规模自动化"]
    P3 --> DIS3["缺点：评判者自身有偏差<br/>偏好长答案等问题"]

    style 评估范式 fill:#e3f2fd
```

### 3.2 LLM-as-Judge 的偏差

LLM-as-Judge 是目前最常用的 Agent 评估方法（用于 LMSYS Chatbot Arena 等），但它存在系统性偏差：

| 偏差类型 | 表现 | 缓解策略 |
|----------|------|----------|
| **位置偏差** | 倾向第一个答案 | 随机化答案顺序 |
| **长度偏差** | 倾向更长的答案 | 控制答案长度一致 |
| **自我偏好** | GPT-4 倾向 GPT-4 的答案 | 用多个不同模型交叉评判 |
| **格式偏差** | 倾向 Markdown 格式更好的答案 | 去除格式后评判 |
| **难度偏差** | 在简单任务上区分度低 | 设计难度分层任务 |

### 3.3 tau-bench 的双轮交互范式

tau-bench（2024）引入了一个重要的评估范式：**Agent 与用户的双轮交互**。传统基准只给 Agent 一个任务描述，tau-bench 则模拟真实场景——Agent 需要主动向"用户"提问来收集信息：

```mermaid
sequenceDiagram
    participant T as 测试系统<br/>（扮演用户）
    participant A as 被测 Agent

    T->>A: "我想退掉上周买的耳机"
    A->>A: 推理：需要订单号来处理退换
    A->>T: "请问您的订单号是多少？"
    T->>T: 验证：Agent 是否问了正确的问题
    T->>A: "订单号是 ORD-12345"
    A->>A: 推理：查询退换货政策
    A->>T: "您的耳机在 7 天退货期内<br/>已为您发起退货流程"
    T->>T: 验证：Agent 是否正确执行了退换流程<br/>是否遵循了公司政策
```

这种范式比传统"单轮任务"更接近真实生产环境。

---

## 4. 企业级 Agent 评测框架设计

### 4.1 为什么公开基准不够用

公开基准（SWE-bench、AgentBench）解决的是"模型能力横向比较"问题，但企业场景有三个独特需求：

```mermaid
graph TB
    subgraph 企业需求["企业级评测的独特需求"]
        N1["领域特异性<br/>你的 Agent 处理的是<br/>你公司的业务流程"]
        N2["持续评估<br/>Agent 每次更新都需要<br/>回归测试"]
        N3["多维质量<br/>不仅要正确<br/>还要安全 / 低成本 / 快速"]
    end

    N1 --> C1["需要自建领域测试集"]
    N2 --> C2["需要 CI/CD 集成"]
    N3 --> C3["需要多维评分体系"]

    style 企业需求 fill:#e3f2fd
```

### 4.2 企业级评测架构

```mermaid
graph TB
    subgraph 企业评测["企业级 Agent 评测架构"]
        subgraph 数据层["测试数据管理"]
            GOLD["黄金测试集<br/>人工标注的高质量案例"]
            ADVERSARIAL["对抗测试集<br/>边界 / 安全 / 越狱"]
            PROD["生产采样<br/>真实用户对话采样"]
        end

        subgraph 执行层["评测执行"]
            AUTO["自动化测试<br/>CI/CD 流水线"]
            HUMAN["人工评测<br/>抽样人工审查"]
            AB["A/B 测试<br/>新旧版本对比"]
        end

        subgraph 评分层["多维评分"]
            ACC["准确率"]
            SAFE["安全分"]
            COST["成本分"]
            LATENCY["延迟分"]
            SAT["用户满意度"]
        end

        subgraph 报告层["评测报告"]
            DASH["评测看板"]
            TREND["趋势分析"]
            ALERT["回归告警"]
        end
    end

    数据层 --> 执行层
    执行层 --> 评分层
    评分层 --> 报告层

    style 数据层 fill:#e3f2fd
    style 执行层 fill:#bbdefb
    style 评分层 fill:#c8e6c9
    style 报告层 fill:#fff9c4
```

### 4.3 测试集构建方法论

构建企业级 Agent 测试集的推荐流程：

```mermaid
graph LR
    subgraph 构建流程["测试集构建五步法"]
        S1["1. 从生产日志采样<br/>覆盖典型场景"]
        S2["2. 人工标注黄金答案<br/>专家审核"]
        S3["3. 构造对抗案例<br/>安全 / 边界 / 异常"]
        S4["4. 定义评估指标<br/>精确匹配 / 语义匹配"]
        S5["5. 持续迭代<br/>每月更新测试集"]
    end

    S1 --> S2 --> S3 --> S4 --> S5
    S5 -.->|"反馈"| S1

    style 构建流程 fill:#e8f5e9
```

### 4.4 Spring AI 中的评测集成

```java
// 概念代码：Agent 评测集成到 CI/CD
@Service
public class AgentEvaluationService {

    private final ChatClient agentClient;
    private final List<TestCase> testCases;
    private final LLMJudge llmJudge;

    @Scheduled(cron = "0 2 * * *")  // 每天凌晨 2 点运行
    public EvaluationReport runDailyEvaluation() {
        var results = testCases.parallelStream()
            .map(testCase -> evaluateTestCase(testCase))
            .toList();

        return EvaluationReport.builder()
            .totalCases(results.size())
            .passed((int) results.stream().filter(r -> r.passed()).count())
            .avgTokenCost(results.stream().mapToInt(r -> r.tokens()).average().orElse(0))
            .avgLatency(results.stream().mapToLong(r -> r.latencyMs()).average().orElse(0))
            .build();
    }

    private TestResult evaluateTestCase(TestCase testCase) {
        // 1. 执行 Agent
        var response = agentClient.prompt()
            .user(testCase.input())
            .call()
            .content();

        // 2. LLM-as-Judge 评分
        var score = llmJudge.judge(
            testCase.input(),
            response,
            testCase.expectedOutput(),
            testCase.rubric()  // 评分标准
        );

        return new TestResult(testCase.id(), response, score);
    }
}
```

---

## 5. 评测的局限性与陷阱

### 5.1 Goodhart 定律

> "当一个指标成为目标时，它就不再是一个好指标。" —— Charles Goodhart

Agent 评测同样受制于这一定律。当模型开发团队过度优化某个基准的成绩时，该基准就失去了评估意义：

```mermaid
graph TB
    subgraph Goodhart陷阱["Agent 评测的 Goodhart 陷阱"]
        B1["基准发布"] --> B2["模型针对基准优化"]
        B2 --> B3["基准成绩虚高"]
        B3 --> B4["实际能力提升停滞"]
        B4 --> B5["发布新基准"]
        B5 --> B1
    end

    style Goodhart陷阱 fill:#ffcdd2
```

### 5.2 数据污染问题

公开基准的测试数据可能已经混入了 LLM 的训练数据——模型不是在"推理"，而是在"回忆"训练时见过的答案。SWE-bench 等基准已开始采用以下对策：

- **时间切分**：只用模型训练截止日期之后的新 Issue
- **数据去重**：确保测试集与训练数据不重叠
- **私有评估集**：不公开测试数据，只提供评测 API

### 5.3 环境简化偏差

大多数基准为了可控性，简化了真实环境的复杂性：

| 真实环境 | 基准简化 | 差距 |
|----------|---------|------|
| 生产数据库有数百万行 | 基准用几百行测试数据 | 性能行为不同 |
| 真实 Web 页面动态变化 | 基准用固定快照 | 泛化能力无法评估 |
| 用户表述模糊/有错别字 | 基准用精确的任务描述 | 理解能力被高估 |
| 多步任务中间可能出错 | 基准假设原子操作可靠 | 容错能力无法评估 |

---

## 6. Agent 评测的发展趋势

### 6.1 从静态到动态

```mermaid
graph LR
    subgraph 评测演进["Agent 评测范式演进"]
        S1["静态基准<br/>固定测试集"] --> S2["动态基准<br/>自动生成对抗任务"]
        S2 --> S3["持续评测<br/>生产环境实时监控"]
        S3 --> S4["自适应评测<br/>根据 Agent 能力调整难度"]
    end

    style 评测演进 fill:#e3f2fd
```

### 6.2 从单 Agent 到多 Agent

当前基准主要评估单个 Agent 的能力，但实际生产中 Agent 通常需要协作（[教程 08-多 Agent 协作](../教程/08-多Agent协作.md)）。多 Agent 评测需要评估：

- **协作效率**：多 Agent 是否比单 Agent 更快完成任务？
- **通信开销**：Agent 间的通信成本是否可接受？
- **错误传播**：一个 Agent 的错误是否被其他 Agent 放大？

### 6.3 从能力到安全

```mermaid
graph TB
    subgraph 安全评测["Agent 安全评测维度"]
        SE1["越狱攻击<br/>能否绕过安全限制？"]
        SE2["Prompt 注入<br/>恶意输入能否劫持 Agent？"]
        SE3["权限滥用<br/>Agent 是否做了超出授权的事？"]
        SE4["信息泄露<br/>Agent 是否泄露了敏感信息？"]
        SE5["拒绝服务<br/>能否诱导 Agent 消耗大量资源？"]
    end

    style 安全评测 fill:#ffcdd2
```

安全评测是一个独立且快速发展的领域，我们在 [教程 25-安全与权限控制](../教程/25-安全与权限控制.md) 中讨论的安全策略需要配合这些安全基准来验证。

---

## 7. 评测实践建议

### 7.1 选择合适基准的决策树

```mermaid
graph TB
    START["你的 Agent 类型？"] --> Q1{"编码类？"}
    Q1 -->|"是"| SWE["SWE-bench / HumanEval"]
    Q1 -->|"否"| Q2{"Web 交互类？"}
    Q2 -->|"是"| WEB["WebArena / Mind2Web"]
    Q2 -->|"否"| Q3{"客服/对话类？"}
    Q3 -->|"是"| TAU["tau-bench"]
    Q3 -->|"否"| Q4{"工具使用类？"}
    Q4 -->|"是"| TOOL["ToolBench"]
    Q4 -->|"否"| Q5{"通用能力？"}
    Q5 -->|"是"| GAIA["GAIA / AgentBench"]
    Q5 -->|"否"| CUSTOM["自建领域基准"]

    style SWE fill:#c8e6c9
    style WEB fill:#bbdefb
    style TAU fill:#fff9c4
    style TOOL fill:#ffe0b2
    style GAIA fill:#e3f2fd
    style CUSTOM fill:#ffcdd2
```

### 7.2 企业级评测的黄金法则

1. **结果 + 过程双评估**：不仅看最终答案是否正确，还要检查推理路径是否合理。
2. **LLM-Judge + 人工抽检**：LLM 评判覆盖量，人工抽检覆盖质。
3. **生产采样持续评测**：从真实用户对话中采样，避免"实验室成绩"。
4. **多维评分而非单一分数**：准确率、安全性、成本、延迟缺一不可。
5. **对抗测试常态化**：定期组织红队构造对抗案例。

---

## 8. 总结

Agent 评测是一个远比传统软件测试复杂的领域。核心调研发现如下：

1. **基准繁荣**：AgentBench、SWE-bench、GAIA、WebArena 等基准覆盖了综合能力、编码、Web 交互、工具使用等多个维度，但每个基准都有其局限性。
2. **三种评估范式**：结果导向（客观但粗糙）、过程导向（精确但昂贵）、LLM-as-Judge（可扩展但有偏差）各有优劣，实际应用中应组合使用。
3. **Goodhart 陷阱不可忽视**：基准成绩不等于真实能力，需要持续更新测试集和引入私有评估集。
4. **企业必须自建评测体系**：公开基准只能做横向参考，垂直领域必须构建包含黄金测试集、对抗测试集和生产采样的多维评测框架。
5. **安全评测是独立维度**：Agent 的安全性（越狱、注入、权限滥用）需要专门的评估，不能混在能力评测中。

对于 Java Agent 架构师而言，建议将 Agent 评测视为与开发同等重要的工程活动——投入至少 30% 的精力到测试集构建、评测自动化和持续监控中。
