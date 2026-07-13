# 附录 - Spring AI vs LangChain4j：何时用何框架（2026 决策版）

> 一句话定位：**单框架是主流，混合使用是少数派。"接入/兜底 vs 思考/编排"的分工模型在 Spring AI 2.0 GA 后已经过时——Spring AI 2.0 原生支持 ToolCallingAdvisor 递归迭代（即 Agent Loop），把 LangChain4j 此前最大的优势抹平了大半。**
>
> 本节是对 `10-SpringAI与LangChain4j分工模型.md`（理论范式）和 `11-企业级Java-AI架构选型真相.md`（现实校正）的**最终决策版**：用一份文档回答"我到底该用哪个"。
>
> 调研日期：2026-07-13。本仓库 pom 实际使用 **Spring AI 1.0.0 + Spring Boot 3.5.10 + JDK 21 + LangChain4j 1.0.1**。Spring AI 2.0.0 GA 已于 2026-06-12 发布（基于 Spring Boot 4 + Jackson 3 + JSpecify）。

---

## 0. 阅读顺序与文档定位

| 文档 | 定位 | 适用读者 |
|------|------|---------|
| [`10-SpringAI与LangChain4j分工模型.md`](./10-SpringAI与LangChain4j分工模型.md) | **理论范式**：两框架"接入/兜底 vs 思考/编排"分工 | 想理解两框架**设计哲学差异**的读者 |
| [`11-企业级Java-AI架构选型真相.md`](./11-企业级Java-AI架构选型真相.md) | **现实校正**：企业不混用，单框架 + Workflow 是主流 | 想知道**企业真实在做什么**的读者 |
| **`13-SpringAI-vs-LangChain4j何时用何框架.md`（本文）** | **决策手册**：基于 2026-07 最新事实给出"用哪个"的明确答案 | 准备**选型/启动项目**的读者 |

**重要事实更新**：第 10 篇写于 Spring AI 1.0 时代，彼时 LangChain4j 在"思考/编排"上有明显优势；Spring AI 2.0 GA（2026-06-12）后这个优势大幅缩水（详见 §2）。本文是 2026-07 的最新答案。

---

## 1. 核心结论（先看这一段）

| 问题 | 答案（2026-07） |
|------|----------------|
| **新项目应该选哪个？** | **90% 场景选 Spring AI**（Spring Boot 项目）/ **Quarkus 项目选 LangChain4j** |
| **Spring AI 1.0 还是 2.0？** | 新项目直接上 **Spring AI 2.0**（已 GA，Agent 能力补齐）；老项目 1.0 跑通后再升级 |
| **应该混用吗？** | **不应该**。混用是理论范式，企业不这么做（维护成本翻倍、收益边际递减） |
| **什么场景必须用 LangChain4j？** | (1) Quarkus 生态；(2) 跨 Spring 生态的纯 Java/CLI/批处理；(3) 复杂状态机（LangGraph4j）且团队已熟 |
| **什么场景必须用 Spring AI？** | (1) Spring Boot 企业项目；(2) 需要 Spring Security/Cloud/Actuator；(3) MCP 优先场景 |
| **能不能两个都学？** | **必须都学**——LangChain4j 入门快适合阶段 1，Spring AI 是最终主框架 |

**最重要的一句话**：选型不是"哪个更好"，而是"你的项目跑在什么生态上"。**Spring Boot 选 Spring AI，Quarkus 选 LangChain4j**——这是一道生态题，不是技术题。

---

## 2. 关键事实校正：Spring AI 2.0 改变了什么

第 10 篇写于 Spring AI 1.0 时代，论点是"LangChain4j 在思考/编排上有优势"。**Spring AI 2.0 GA（2026-06-12）改变了一切**：

### 2.1 Spring AI 2.0 已追平的能力

| 能力 | Spring AI 1.0 | Spring AI 2.0 | LangChain4j 1.0 |
|------|--------------|--------------|----------------|
| **Tool 调用循环** | 手动管理 | ✅ `ToolCallingAdvisor` **自动注册 + 递归迭代**（即原生 Agent Loop） | ✅ AiServices 内置 |
| **结构化输出** | `BeanOutputConverter` | ✅ + `StructuredOutputValidationAdvisor`（带校验+自动重试） | ✅ AiServices 返回类型直接映射 |
| **MCP 集成** | client only | ✅ MCP Java SDK 2.0，client + server，`@McpTool`/`@McpResource`/`@McpPrompt` | ⚠️ 客户端能力，无 server |
| **会话记忆** | `MessageWindowChatMemory` | ✅ `spring-ai-session`（event-sourced，可重放） | ✅ `MessageWindowChatMemory` + 自定义 Store |
| **Agent Loop 终止条件** | 无 | ✅ 通过 Advisor 链组合（预算/轮数/重复检测） | ✅ AiServices 内置 |
| **声明式 Agent** | 无（需手写 ChatClient） | ✅ spring-ai-agent-utils（社区，受 Claude Code 启发） | ✅ AiServices（更成熟） |
| **可观测性** | Micrometer 集成 | ✅ + OTel GenAI Semantic Conventions | ⚠️ 需自建 |

### 2.2 LangChain4j 仍然占优的能力

| 能力 | Spring AI 2.0 | LangChain4j 1.0 |
|------|--------------|----------------|
| **状态机式多 Agent** | ❌ 无原生（需自建或借 LangGraph4j） | ✅ LangGraph4j（独立项目，社区驱动） |
| **AiServices 接口驱动** | ⚠️ 仅靠 ChatClient.Builder + 接口约定 | ✅ 注解丰富（`@SystemMessage`/`@UserMessage`/`@MemoryId`） |
| **跨容器/纯 Java 场景** | ❌ 强依赖 Spring | ✅ 无 Spring 也能跑 |
| **ChatMemory 灵活装配** | ⚠️ Advisor 模式 | ✅ Provider 模式，按会话动态装配 |
| **复用 Python LangChain 心智模型** | ❌ Spring 风格 | ✅ 概念对应清晰 |
| **Quarkus 原生支持** | ❌ | ✅ Red Hat 官方 |

### 2.3 关键结论

**Spring AI 2.0 之后，LangChain4j 仅在 3 个细分场景仍有明显优势**：
1. **Quarkus 生态**（Red Hat 官方背书）
2. **复杂多 Agent 状态机**（LangGraph4j）—— 但企业实战 < 10%，且能用 Anthropic Workflow 替代
3. **无 Spring 容器的纯 Java/CLI 场景**

**其余 90% 场景，Spring AI 2.0 已经够用且更优**（生态、可观测性、MCP、生产化能力）。

---

## 3. 决策树：我该用哪个

### 3.1 一图决策

```
你的项目跑在什么生态？
│
├─ Spring Boot（80% Java 项目）
│   └─→ ✅ Spring AI 2.0
│        （除非有强状态机需求 → 加 LangGraph4j 或 Alibaba Graph）
│
├─ Quarkus / 云原生 / GraalVM 原生镜像
│   └─→ ✅ Quarkus LangChain4j
│        （Red Hat 官方推荐，互斥于 Spring AI）
│
├─ 纯 Java / Kotlin（无框架）
│   ├─→ ✅ LangChain4j（无容器依赖）
│   └─→ ⚠️ Spring AI 也可，但需手动初始化 IoC
│
├─ Kotlin 项目 + 复杂 Agent
│   └─→ ✅ Koog（JetBrains，1.0 GA，实验性跟进）
│
└─ 国内复杂工作流 + Spring 系
    └─→ ✅ Spring AI Alibaba Graph（DAG 编排）
```

### 3.2 决策清单（按优先级）

**第一步：看生态**
- [ ] 项目主框架是 Spring Boot 吗？→ Spring AI 2.0
- [ ] 项目主框架是 Quarkus 吗？→ Quarkus LangChain4j
- [ ] 无主框架？→ LangChain4j（轻量）

**第二步：看复杂度**
- [ ] 单轮 RAG + Tool？→ 任何框架都行
- [ ] 多轮对话 + 多 Tool？→ Spring AI 2.0（Advisor 链 + ToolCallingAdvisor）
- [ ] 多 Agent 状态机？→ Spring AI + LangGraph4j 或 Alibaba Graph
- [ ] 探索式自主 Agent？→ Embabel（Beta）或自研 ReAct

**第三步：看团队**
- [ ] 团队只会 Spring？→ Spring AI（无悬念）
- [ ] 团队 Python LangChain 经验丰富？→ LangChain4j（心智模型对应）
- [ ] 团队既要 Web 又要复杂 Agent？→ 单框架 Spring AI 2.0 + Workflow 模式（不要混用）

---

## 4. 单框架方案详解

### 4.1 单用 Spring AI 2.0（推荐 / 主流）

**适用场景**：
- Spring Boot 企业项目（80% Java AI 场景）
- 需要 Spring Security/Cloud/Actuator/Micrometer
- MCP 优先（Java SDK 2.0 在 Spring AI 内最完整）
- 需要可观测性、审计、限流、熔断等生产化能力

**架构骨架**：

```
HTTP / SSE
  ↓
Controller
  ↓
ChatClient（统一入口）
  ├─ defaultSystem
  ├─ defaultAdvisors:
  │   ├─ SecurityAdvisor（鉴权）
  │   ├─ RateLimitAdvisor（限流，Bucket4j）
  │   ├─ AuditAdvisor（审计落库）
  │   ├─ ToolCallingAdvisor（2.0 自动注册，递归迭代 = Agent Loop）
  │   ├─ MessageChatMemoryAdvisor（记忆）
  │   ├─ QuestionAnswerAdvisor（RAG）
  │   └─ StructuredOutputValidationAdvisor（结构化输出校验）
  └─ defaultTools
  ↓
ChatModel（调 LLM）
  ↓
Flux<String> 流式返回
```

**关键能力**：
- `ToolCallingAdvisor` 自动递归 —— **这就是 Agent Loop**，无需手写 while(true)
- `@Tool` + Spring Bean —— 直接复用 `@Transactional`/`@Cacheable`
- `Advisor` 链 —— 横切关注点的天然位置（鉴权/限流/审计/降级）
- MCP 2.0 —— `@McpTool`/`@McpResource`/`@McpPrompt` 一站式
- Micrometer + OTel GenAI —— 可观测性开箱即用

**何时不够用**：
- 需要复杂状态机多 Agent（→ 加 LangGraph4j 或 Alibaba Graph）
- 团队坚持 Python LangChain 心智模型（→ LangChain4j）

### 4.2 单用 LangChain4j 1.0

**适用场景**：
- Quarkus 项目（Red Hat 官方）
- 无 Spring 容器的纯 Java/CLI/批处理
- 团队有 Python LangChain 背景，心智模型对应清晰
- 复杂 ChatMemory 装配（按会话 ID 动态切换 Store）
- 复杂多 Agent 状态机（用 LangGraph4j）

**架构骨架**：

```
用户输入
  ↓
AiServices 接口（声明式）
  ├─ @SystemMessage
  ├─ @UserMessage
  ├─ @MemoryId
  └─ 返回类型即结构化输出契约
  ↓
AiServices.builder(...)
  ├─ chatLanguageModel
  ├─ contentRetriever（RAG）
  ├─ tools（@Tool 集合）
  ├─ chatMemoryProvider
  └─ structuredOutputConverter
  ↓
LangGraph4j（可选，复杂状态机）
  ↓
ChatLanguageModel 调用
```

**关键能力**：
- `AiServices` 接口驱动，类型安全，IDE 友好
- `@SystemMessage`/`@UserMessage`/`@MemoryId` 注解管理 prompt 和会话
- 返回类型直接映射结构化输出
- LangGraph4j 状态机多 Agent 编排（cyclic graph）
- 跨容器：Quarkus、Plain Java、Spring（手动集成）均可

**何时不够用**：
- 需要 Spring Security/Actuator（→ Spring AI 或自建 AOP）
- 需要 MCP Server（→ Spring AI 2.0）
- 需要生产级可观测性（→ 自建 Micrometer 桥接）

---

## 5. 混合使用：理论可行，企业不推荐

> **核心论点**：混合使用 = 维护成本翻倍 + 收益边际递减 + 团队认知负担。**除非有强约束，不要混用**。

### 5.1 混用的三种形态

**形态 A：Spring AI Web 层 + LangChain4j Agent 层（同进程）**

```java
// 第 10 篇 §7 描述的方案
@Configuration
class Config {
    @Bean
    AnalysisAgent agent(ChatLanguageModel model) {
        return AiServices.builder(AnalysisAgent.class)
            .chatLanguageModel(model).build();
    }
}

@RestController
class Controller {
    private final AnalysisAgent agent;  // LangChain4j
    private final ChatClient client;    // Spring AI
    // ...
}
```

**形态 B：Spring AI 服务 + LangChain4j 服务（跨进程，HTTP/gRPC）**

```
Spring AI 微服务（Web 层）
  ↓ HTTP/gRPC
LangChain4j 微服务（Agent 层）
```

**形态 C：Spring AI 主框架 + LangGraph4j 编排引擎（最常见的混合）**

```java
// Spring AI 做 ChatClient，LangGraph4j 做编排
StateGraph<AgentState> graph = new StateGraph<>(...)
    .addNode("retrieve", SpringAiClientNode(client))
    .addNode("analyze", SpringAiClientNode(client))
    .addEdge("retrieve", "analyze");
```

### 5.2 为什么企业不混用（来自第 11 篇的论据）

| 问题 | 影响 |
|------|------|
| **两套依赖版本要兼容** | Spring Boot 4 / LangChain4j 升级周期不同，常版本冲突 |
| **两套 `@Tool` 注解不通用** | `org.springframework.ai.tool.annotation.Tool` ≠ `dev.langchain4j.agent.tool.Tool` |
| **两套 ChatMemory 接口** | 无法共享会话状态 |
| **两套 Prompt 模板** | Spring AI 的 `PromptTemplate` ≠ LangChain4j 的 `@SystemMessage` |
| **团队认知负担** | 每个新人都要问"这段逻辑放哪边"，没有清晰答案 |
| **可观测性割裂** | Spring AI 的 Micrometer trace 不能穿透 LangChain4j 调用 |

### 5.3 极少数合理的混合场景

| 场景 | 为何合理 |
|------|---------|
| **遗留 LangChain4j 项目，逐步迁移到 Spring AI** | 渐进式重写，Strangler Pattern |
| **企业内部已有 LangChain4j Agent 库，新项目用 Spring AI 调用** | 复用资产，但应规划长期迁移 |
| **学术研究/原型对比** | 仅用于比较框架，非生产代码 |

**关键判断**：以上三种都是**过渡态**或**研究态**，不是稳态。稳态永远是单框架。

---

## 6. 何时**两个都不用**

### 6.1 该用 Python LangChain 而不是 Java 框架

- **场景**：算法研究、模型评估、数据处理管线、需要 HuggingFace 生态
- **判断**：项目核心是"调模型"而不是"做企业应用"→ Python
- **典型**：训练后评估、LoRA 微调脚本、向量库离线 batch embedding

### 6.2 该用编排引擎而不是任何 LLM 框架

- **场景**：多 Agent 复杂状态机、需要 Checkpoint/Time-Travel、跨进程 Agent 通信
- **判断**：Spring AI / LangChain4j 的"编排能力"hold 不住
- **候选**：
  - **Spring AI Alibaba Graph**（国内首选，DAG，1.0 GA）
  - **LangGraph4j**（社区驱动，状态机，Beta）
  - **Temporal + 自写 Agent**（Java 强项，分布式编排）
  - **Koog**（JetBrains，Kotlin，1.0 GA，跟进不押注）

### 6.3 该用 LLM Gateway 而不是直接用框架

- **场景**：多团队、多模型、需要统一成本/审计/限流
- **判断**：每个团队都接 LLM = 重复造轮子
- **候选**：LiteLLM、Portkey、Cloudflare AI Gateway、Kong AI Gateway
- **配合**：Spring AI 在 Gateway 之后做应用层

### 6.4 该等而不是现在动手

- **场景**：A2A 协议（Google，跨 Agent 标准化）、AGNTCY/ASL（Cisco）
- **判断**：这些协议还在演进，**跟进不押注**
- **行动**：持续观察 6-12 个月，等 GA + 多框架采纳后再考虑

---

## 7. 场景速查表

| 场景 | 首选 | 第二选择 | 不要用 |
|------|------|---------|--------|
| Spring Boot 企业 RAG 助手 | Spring AI 2.0 | Spring AI 1.0 | LangChain4j（除非遗留） |
| Quarkus 云原生 AI 微服务 | Quarkus LangChain4j | - | Spring AI（互斥） |
| 命令行/批处理 AI 脚本 | LangChain4j（无容器） | Spring Boot + Spring AI | 重量级编排引擎 |
| 多 Agent 客服系统 | Spring AI 2.0 + Workflow 模式 | Alibaba Graph | 自研 ReAct（过早） |
| 代码评审 Agent | Spring AI 2.0 + Orchestrator-Workers | Koog（Kotlin） | LangChain4j（无优势） |
| 智能运维助手 | Spring AI 2.0 + Tool 体系 | LangChain4j + 自建 | Embabel（Beta） |
| 复杂研究 Agent（开放式） | Spring AI Alibaba Graph | LangGraph4j | Spring AI 单框架（hold 不住） |
| MCP Server 暴露企业工具 | Spring AI 2.0（`@McpTool`） | MCP Java SDK 原生 | LangChain4j（无 server） |
| 流式聊天 Web 应用 | Spring AI 2.0（Flux） | LangChain4j（手动桥接） | 自研 SSE |
| 离线文档向量化批处理 | LangChain4j（轻量） | Spring Batch + Spring AI | Quarkus（不必要） |

---

## 8. 学习路线建议（与本项目对齐）

> 本仓库 pom 当前是 **Spring AI 1.0.0 + LangChain4j 1.0.1**，这是阶段 1-3 的学习状态。生产化阶段（阶段 5）应升级到 Spring AI 2.0。

### 8.1 阶段 1（已完成）：LangChain4j 入门

**为什么先学 LangChain4j**：
- AiServices 声明式 API 直观，**适合建立心智模型**
- 概念与 Python LangChain 对应清晰
- 无需 Spring 容器，**学习摩擦小**
- ChatMemory / Tool / RAG 概念齐全

**学到什么程度**：能独立写多轮对话 + Tool + RAG demo（本仓库已完成）。

### 8.2 阶段 2-3（进行中）：Spring AI 1.0 切入 + RAG + 评估

**为什么切到 Spring AI**：
- Advisor 链是 Spring AOP 的 AI 版，**Java 工程师最熟悉的范式**
- 与 Spring Security/Cloud/Actuator 无缝集成
- 为后续生产化（阶段 5）打基础

**学到什么程度**：能用 Spring AI 1.0 完整实现 RAG + 评估方法论。

### 8.3 阶段 4（计划中）：自研工具箱 + Workflow 模式

**为什么不再用 LangChain4j 对照**：
- Spring AI 2.0 的 `ToolCallingAdvisor` 已经原生支持 Agent Loop
- Anthropic Workflow 模式（5 种）**全部可用 Spring AI 单框架实现**
- 把"自研 Spring AI 工具箱"作为阶段 4 的核心产出

### 8.4 阶段 5（生产化）：升级到 Spring AI 2.0

**为什么必须升级**：
- `ToolCallingAdvisor` 自动注册 = 不再需要手写工具调用循环
- `StructuredOutputValidationAdvisor` = 结构化输出 + 自动重试
- MCP Java SDK 2.0 = `@McpTool` 一站式
- `spring-ai-session` = event-sourced 会话，可重放

**升级成本**：
- Spring Boot 3.5 → 4.0（主要变动）
- Jackson 2 → 3（影响 JSON 序列化）
- JSpecify null-safety（类型系统增强）
- 预计 1-2 周迁移

### 8.5 LangChain4j 在路线中的最终位置

| 阶段 | LangChain4j 角色 |
|------|----------------|
| 阶段 1 | **主框架**（入门） |
| 阶段 2-3 | 对照学习（理解差异） |
| 阶段 4-5 | 仅作历史知识，**不再写新代码** |
| 阶段 6+ | 完全不用（除非遗留迁移） |

**心智模型**：LangChain4j 是**入门老师**，Spring AI 是**最终主框架**。

---

## 9. 修订记录：对第 10 篇和第 11 篇的更正

### 9.1 第 10 篇需要修正的论点

> 第 10 篇作为"理解两框架设计哲学差异"的教材仍有价值，但以下论点已过时：

| 第 10 篇论点 | 2026-07 真相 | 修正 |
|------------|-----------|------|
| "LangChain4j 负责'思考'和'编排'" | Spring AI 2.0 的 `ToolCallingAdvisor` 已实现原生 Agent Loop | 弱化为"LangChain4j 仍有 AiServices 接口驱动优势" |
| "Spring AI 不舒适区：复杂状态机、多 Agent 协作、ReAct 循环细节控制" | Spring AI 2.0 + Advisor 链 + Workflow 模式可覆盖 90% 场景 | 弱化为"复杂多 Agent 状态机仍需 LangGraph4j" |
| "两层分工架构（Spring AI 接入 + LangChain4j 思考）" | 企业不这么做（第 11 篇 §3） | 标注为"理论范式，实战罕见" |
| 第 9 节演进路径"阶段 3 引入 LangChain4j" | 应改为"阶段 3 引入 Alibaba Graph 或 LangGraph4j"（更贴近企业实战） | 见 §8.5 |

### 9.2 第 11 篇仍需补充的点

> 第 11 篇整体结论正确（企业不混用），但 2026-06-12 Spring AI 2.0 GA 后需要补充：

| 补充点 | 内容 |
|-------|------|
| Spring AI 版本 | 标注 2.0 GA（2026-06-12）已发布，第 11 篇基于 1.0 GA 写就 |
| Agent Loop | 2.0 的 `ToolCallingAdvisor` 已实现递归迭代，不再是 Spring AI 的短板 |
| MCP | 2.0 的 MCP Java SDK 2.0 + `@McpTool` 是 Spring AI 独占优势 |
| 升级建议 | 阶段 5 生产化时应升级到 2.0（本文 §8.4） |

### 9.3 三篇文档的最终关系

```
第 10 篇（理论范式）
   │
   │ 现实校正
   ↓
第 11 篇（企业实战）
   │
   │ 版本更新 + 决策落地
   ↓
第 13 篇（本文，决策手册）
```

**读法建议**：
- **理解设计哲学** → 第 10 篇
- **了解企业实战** → 第 11 篇
- **决定用哪个** → 第 13 篇（本文）

---

## 10. 自检清单

读完本文后，你应该能回答：

- [ ] Spring AI 2.0 GA 之后，LangChain4j 还剩哪 3 个独占优势？
- [ ] 为什么企业不混用？三个核心原因是什么？
- [ ] Quarkus 项目应该选哪个框架？为什么？
- [ ] MCP Server 场景下，为什么 Spring AI 2.0 是唯一选择？
- [ ] 本仓库 pom 是 Spring AI 1.0.0，何时应该升级到 2.0？
- [ ] LangChain4j 在你的学习路线里扮演什么角色？最终位置在哪？

---

## 11. 相关文档

- [`10-SpringAI与LangChain4j分工模型.md`](./10-SpringAI与LangChain4j分工模型.md) —— 理论分工模型（已标注部分过时）
- [`11-企业级Java-AI架构选型真相.md`](./11-企业级Java-AI架构选型真相.md) —— 现实校正（企业不混用）
- [`12-ClaudeCode源码启示录.md`](./12-ClaudeCode源码启示录.md) —— Agent 工程借鉴
- [`09-心智模型与决策树.md`](./09-心智模型与决策树.md) —— LLM = 远程 RPC 心智模型
- [`04-Java与AI融合架构.md`](./04-Java与AI融合架构.md) —— 整体架构

---

## 12. 参考资料

1. **Spring AI 2.0.0 GA Available Now**（2026-06-12）—— Spring 官方 GA 公告
2. **Spring AI 2.0 Goes GA**（Visual Studio Magazine, 2026-06-29）—— 第三方观察
3. **Spring AI 2.0 GA and Composable Tool Calling**（JavaRubberDuck, 2026-06-29）—— `ToolCallingAdvisor` 递归迭代细节
4. **Spring AI Agentic Patterns: Agent Skills**（Spring 官方博客, 2026-01-13）—— spring-ai-agent-utils 介绍
5. **LangChain4j Official Documentation** —— AiServices / ChatMemory / Tool 1.0 文档
6. **LangGraph4j GitHub** —— 状态机多 Agent 编排
7. **Anthropic《Building Effective Agents》**（2024-12-19）—— Workflow > Agent 金科玉律
8. **Quarkus LangChain4j Documentation** —— Red Hat 官方立场
