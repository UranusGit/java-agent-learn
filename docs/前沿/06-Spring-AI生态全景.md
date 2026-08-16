# 06-Spring AI 生态全景：框架对比与选型指南

> **定位**：本文调研 Spring AI 生态的完整全景，对比 Spring AI Alibaba、JetBrains Koog、JManus 等基于 Spring AI 或 JVM 构建的 Agent 框架，给出面向不同场景的选型建议。本文不是 Spring AI 的入门教程（参见 [教程 01-Spring AI 框架入门](../教程/01-Spring-AI框架入门.md)），而是站在架构选型视角审视生态格局。
>
> **性质声明**：本文为调研性质，各框架处于快速迭代期，版本特性和成熟度可能随时变化。选型建议基于截至调研时点的公开信息，请以各框架最新文档为准。

---

## 1. Spring AI 生态格局

### 1.1 从框架到生态

Spring AI 自 2024 年发布以来，已经从一个 Spring 官方的 AI 集成库发展为围绕它的 **生态圈**。多个组织和公司基于 Spring AI 构建了更高层的 Agent 框架，形成了"Spring AI 内核 + 多框架外壳"的格局。

```mermaid
graph TB
    subgraph 生态["Spring AI 生态圈"]
        subgraph 内核["Spring AI（官方内核）"]
            SA["ChatClient / Tool / Memory<br/>MCP / RAG / Advisor"]
        end

        subgraph 扩展框架["扩展框架"]
            SAA["Spring AI Alibaba<br/>（阿里巴巴）"]
            KOOG["Koog<br/>（JetBrains）"]
            JMANUS["JManus<br/>（社区）"]
        end

        subgraph 基础设施["基础设施"]
            SB["Spring Boot 4.1"]
            WF["WebFlux / Reactor"]
            J21["Java 21"]
            SC["Spring Cloud"]
        end

        subgraph 下游["下游应用"]
            ENT["企业 Agent 应用"]
            OSS["开源 Agent 项目"]
        end
    end

    基础设施 --> 内核
    内核 --> 扩展框架
    扩展框架 --> 下游
    内核 --> 下游

    style 内核 fill:#bbdefb
    style 扩展框架 fill:#e3f2fd
    style 基础设施 fill:#c8e6c9
    style 下游 fill:#fff9c4
```

### 1.2 为什么需要扩展框架

Spring AI 的定位是 **基础设施层**——它提供了 ChatClient、Tool Calling、Memory、RAG 等基础能力，但刻意不包含高层的 Agent 编排模式。这就像 Spring Framework 提供了 IoC/AOP 但不包含业务逻辑。扩展框架的价值在于：

```mermaid
graph TB
    subgraph 分层["Agent 技术栈分层"]
        L1["应用层<br/>具体业务 Agent"]
        L2["框架层<br/>Agent 编排 / 多 Agent 协作<br/>（扩展框架的领域）"]
        L3["能力层<br/>ChatClient / Tool / Memory<br/>（Spring AI 的领域）"]
        L4["基础设施层<br/>Spring Boot / WebFlux<br/>（Spring 生态）"]
    end

    L1 --> L2 --> L3 --> L4

    style L1 fill:#e3f2fd
    style L2 fill:#bbdefb
    style L3 fill:#c8e6c9
    style L4 fill:#fff9c4
```

各扩展框架的差异化主要在 **框架层**：如何编排多步推理、如何管理 Agent 生命周期、如何实现多 Agent 协作、如何抽象工作流。

### 1.3 竞争格局全景

```mermaid
graph TB
    subgraph JavaAgent生态["Java Agent 框架生态"]
        subgraph Spring阵营["Spring 阵营"]
            SA["Spring AI<br/>（Spring 官方）"]
            SAA["Spring AI Alibaba<br/>（阿里巴巴）"]
        end

        subgraph 独立框架["独立框架"]
            KOOG["Koog<br/>（Kotlin Agent 框架）"]
            JMANUS["JManus<br/>（Java 版 Manus）"]
            LANGCHAIN4J["LangChain4j<br/>（LangChain Java 移植）"]
        end

        subgraph 基础库["基础库"]
            SEMANTIC_KERNEL["Semantic Kernel Java<br/>（微软）"]
        end
    end

    style Spring阵营 fill:#e3f2fd
    style 独立框架 fill:#e8f5e9
    style 基础库 fill:#fff9c4
```

本文重点调研 Spring AI、Spring AI Alibaba、Koog、JManus 四个框架。

---

## 2. Spring AI：官方旗舰

### 2.1 定位与设计理念

Spring AI 是 Spring 官方的 AI 集成框架，其设计理念可以用一句话概括：**"做 AI 领域的 Spring Data"**——提供统一的抽象层，屏蔽不同 AI 供应商的差异。

```mermaid
graph TB
    subgraph SpringAI架构["Spring AI 分层架构"]
        subgraph 应用层["应用层"]
            APP["你的 Agent 应用"]
        end

        subgraph 抽象层["Spring AI 核心抽象"]
            CC["ChatClient<br/>统一对话入口"]
            TM["Tool Manager<br/>工具调用"]
            MM["Memory<br/>记忆管理"]
            VS["VectorStore<br/>向量存储"]
            ADV["Advisor<br/>拦截器链"]
            MCP_CLI["MCP Client<br/>MCP 协议支持"]
        end

        subgraph 适配层["供应商适配"]
            OAI["OpenAI"]
            ANT["Anthropic"]
            GEM["Gemini"]
            DS["DeepSeek"]
            OLL["Ollama"]
            AZURE["Azure OpenAI"]
        end
    end

    APP --> 抽象层
    抽象层 --> 适配层

    style 应用层 fill:#e3f2fd
    style 抽象层 fill:#bbdefb
    style 适配层 fill:#c8e6c9
```

### 2.2 核心特性

| 特性 | 说明 | 成熟度 |
|------|------|--------|
| ChatClient 统一 API | 一套 API 对接所有 LLM | 高 |
| Tool Calling | `@Tool` 注解式工具定义 | 高 |
| Structured Output | `entity(Class)` 结构化输出 | 高 |
| Advisor Chain | 拦截器链（类 AOP） | 高 |
| ChatMemory | 会话记忆管理 | 中 |
| VectorStore | 统一向量存储抽象 | 高 |
| MCP 支持 | 原生 MCP 客户端 + 服务端 | 中 |
| RAG | 检索增强生成支持 | 中 |
| 多模态 | 图像/音频输入支持 | 中 |
| Observation | Micrometer 集成 | 高 |
| Streaming | WebFlux 流式响应 | 高 |

### 2.3 优势与劣势

**优势**：
- Spring 生态无缝集成（DI、AOP、配置、安全）
- 统一抽象降低供应商锁定风险
- 社区活跃，文档完善
- 与 Spring Boot 4.1 深度整合

**劣势**：
- Agent 高级模式（多 Agent 协作、复杂工作流）需要手工构建
- 没有 Agent 运行时（调度、生命周期管理）
- 记忆系统较为基础（详见 [前沿 05](05-Agent记忆前沿.md)）
- 对最新模型特性支持可能滞后

---

## 3. Spring AI Alibaba：阿里增强版

### 3.1 定位

Spring AI Alibaba 是阿里巴巴基于 Spring AI 的扩展项目，定位为 **Spring AI 的超集**——在兼容 Spring AI API 的基础上，增加阿里云模型适配和企业级 Agent 能力。

```mermaid
graph TB
    subgraph 关系["Spring AI Alibaba 与 Spring AI 的关系"]
        BASE["Spring AI Core"]
        ALI["Spring AI Alibaba 扩展层"]

        ALI_BASE["100% 兼容 Spring AI API"]
        ALI_EXTRA["+ DashScope 适配<br/>（通义千问系列）"]
        ALI_AGENT["+ Agent 框架<br/>（Graph / 工作流）"]
        ALI_OSS["+ 阿里云中间件适配<br/>（OSS / TableStore）"]
        ALI_GRAPH["+ 多 Agent 图编排"]
    end

    BASE --> ALI
    ALI --> ALI_BASE
    ALI --> ALI_EXTRA
    ALI --> ALI_AGENT
    ALI --> ALI_OSS
    ALI --> ALI_GRAPH

    style BASE fill:#e3f2fd
    style ALI fill:#c8e6c9
```

### 3.2 增强特性

| 特性 | 说明 | 与 Spring AI 的差异 |
|------|------|-------------------|
| DashScope 适配 | 通义千问/Qwen-VL/Qwen-Audio | Spring AI 原生不支持 |
| Agent Graph | 多 Agent 图编排 | Spring AI 需手工实现 |
| Flow 配置 | 声明式 Agent 工作流 | Spring AI 无此能力 |
| RAG 增强 | 智能文档解析 / 分块 | 比 Spring AI 更丰富 |
| Nacos 集成 | 配置中心 / 服务发现 | Spring AI 无此集成 |
| OssVectorStore | 阿里云向量存储 | Spring AI 无此适配 |
| Prompt 管理 | 结构化 Prompt 模板和版本管理 | Spring AI 的 Prompt 管理较简单 |

### 3.3 Agent Graph 编排

Spring AI Alibaba 最大的差异化特性是 **Agent Graph**——一个声明式的多 Agent 图编排框架：

```mermaid
graph LR
    subgraph AgentGraph["Spring AI Alibaba Agent Graph"]
        S["Start"] --> N1["Agent: 需求分析"]
        N1 --> N2["Agent: 方案设计"]
        N2 --> N3["Agent: 代码实现"]
        N3 --> N4["Agent: 代码审查"]
        N4 -->|"通过"| E["End"]
        N4 -->|"不通过"| N3
    end

    style AgentGraph fill:#c8e6c9
```

这种 Graph 编排模式与我们 [教程 36-Agent 工作流编排](../教程/36-Agent工作流编排.md) 中讨论的编排需求高度对应，但提供了框架级的抽象。它填补了 Spring AI 在多 Agent 协作方面的空白，类似于 LangGraph 在 Python 生态的角色。

### 3.4 适用场景

- 使用阿里云基础设施（DashScope、Nacos、OSS）的团队
- 需要多 Agent 图编排但不想引入额外框架
- 国内企业优先选择国产模型
- 中文 NLP 场景（通义千问优势）
- 国内部署和合规要求

---

## 4. JetBrains Koog：Kotlin 原生 Agent 框架

### 4.1 定位

Koog 是 JetBrains 开源的 Agent 框架，基于 Kotlin 构建（但可被 Java 互操作使用），强调 **类型安全** 和 **结构化 Agent 设计**。它的定位是 **IDE 集成优先的、类型安全的 Agent 框架**。

```mermaid
graph TB
    subgraph Koog特点["Koog 核心设计理念"]
        K1["协程原生<br/>Kotlin Coroutines = Agent 并发模型"]
        K2["DSL 编排<br/>类型安全的多 Agent 编排 DSL"]
        K3["不可变状态<br/>Agent 状态用 data class + 不可变设计"]
        K4["类型安全工具<br/>编译期检查工具参数类型"]
    end

    style Koog特点 fill:#e3f2fd
```

### 4.2 与 Spring AI 的对比

| 维度 | Spring AI | Koog |
|------|-----------|------|
| **语言** | Java 优先 | Kotlin 优先 |
| **并发模型** | WebFlux（Reactor） | Coroutines |
| **Agent 编排** | 手工 / Advisor | 声明式 DSL |
| **工具定义** | `@Tool` 注解 | 类型安全 DSL |
| **状态管理** | 手工 / Advisor | 内置 AAR（Agent-Agnostic Runtime） |
| **Spring 集成** | 原生 | 需要适配 |
| **成熟度** | 高 | 早期 |
| **社区规模** | 大 | 小但活跃 |

### 4.3 Koog DSL 风格示例

```kotlin
// Koog 风格的 Agent 编排（概念代码）
val workflow = agentWorkflow {
    val researcher = agent("researcher") {
        model = ModelProvider.GPT4
        systemPrompt = "你是一个研究助手"
        tools = listOf(SearchTool(), ReadTool())
    }

    val writer = agent("writer") {
        model = ModelProvider.CLAUDE
        systemPrompt = "你是一个技术写作专家"
    }

    val reviewer = agent("reviewer") {
        model = ModelProvider.GPT4
        systemPrompt = "你是一个严格的技术审稿人"
    }

    pipeline {
        researcher transforms { input ->
            "研究以下主题：$input"
        }
        writer transforms { research ->
            "基于研究结果写文章：$research"
        }
        reviewer validates { article ->
            article.length > 1000
        }
    }
}
```

Koog 的 DSL 更 **结构化**——推理步骤、工具定义、错误处理都是一等公民。对比 Spring AI 原生方式，Koog 在类型安全和声明式编排上有明显优势。

### 4.4 适用场景

- Kotlin 技术栈的团队
- 重视类型安全和 DSL 开发体验
- 对协程有深度理解
- JetBrains 生态用户
- Agent 逻辑高度结构化的场景

---

## 5. JManus：Java 版通用 Agent

### 5.1 定位

JManus 是一个受 Manus（通用 AI Agent 产品）启发的 Java 框架，目标是构建 **真正自主的通用 Agent**——不需要为每个任务定制工具和流程，Agent 自己决定做什么、怎么做。它的定位不是提供基础设施，而是提供一个 **可直接使用的通用 Agent 应用**。

```mermaid
graph TB
    subgraph JManus理念["JManus 的核心设计理念"]
        M1["自主规划<br/>Agent 自动分解任务"]
        M2["自主工具选择<br/>Agent 从工具池中选择工具"]
        M3["自主反思<br/>执行后自我评估"]
        M4["自主恢复<br/>出错后自动重试 / 换策略"]
    end

    style JManus理念 fill:#e3f2fd
```

### 5.2 核心能力

| 能力 | 说明 | 与 Spring AI 的差异 |
|------|------|-------------------|
| ReAct 引擎 | 内置 Thought-Action-Observation 循环 | Spring AI 需手工实现 |
| Plan & Execute | 内置任务分解和执行 | Spring AI 无内置支持 |
| Dynamic Tool Selection | 运行时动态选择工具 | Spring AI 工具是静态绑定的 |
| Self-Reflection | 执行后自动反思 | Spring AI 无内置支持 |
| Code Execution | 内置代码沙箱 | Spring AI 需集成 MCP |
| Web Browsing | 内置 Web 浏览能力 | Spring AI 需集成 MCP |
| File Operations | 内置文件读写 | Spring AI 需集成 MCP |

### 5.3 JManus 架构

```mermaid
graph TB
    subgraph JManus["JManus 架构"]
        subgraph 核心层["Agent 核心引擎"]
            ENGINE["Agent 运行时引擎"]
            REACT["ReAct 推理循环"]
            PLAN["任务规划器"]
            REFLECT["反思评估器"]
        end

        subgraph 能力层["内置能力"]
            CODE["代码执行沙箱"]
            WEB["Web 浏览器"]
            FILE["文件操作"]
            SHELL["Shell 命令"]
        end

        subgraph 集成层["外部集成"]
            LLM_IN["多模型路由"]
            MCP_IN["MCP 工具集成"]
            API_IN["外部 API 调用"]
        end
    end

    核心层 --> 能力层
    能力层 --> 集成层

    style 核心层 fill:#bbdefb
    style 能力层 fill:#c8e6c9
    style 集成层 fill:#fff9c4
```

### 5.4 适用场景

- 需要"通用 Agent"能力的场景（不像客服/文档等垂直场景）
- 快速原型开发——不需要为每个任务定制工具
- 探索性任务——Agent 自主决定工具和流程
- 个人或小团队使用

---

## 6. 四框架横向对比

### 6.1 功能对比雷达

```mermaid
graph TB
    subgraph 对比["四框架能力雷达"]
        subgraph SpringAI["Spring AI"]
            SA1["基础能力: ★★★★★"]
            SA2["Agent 编排: ★★☆☆☆"]
            SA3["自主性: ★☆☆☆☆"]
            SA4["生态集成: ★★★★★"]
            SA5["成熟度: ★★★★☆"]
        end

        subgraph SAAlibaba["Spring AI Alibaba"]
            SAA1["基础能力: ★★★★★"]
            SAA2["Agent 编排: ★★★★☆"]
            SAA3["自主性: ★★☆☆☆"]
            SAA4["生态集成: ★★★★☆"]
            SAA5["成熟度: ★★★☆☆"]
        end

        subgraph Koog框架["Koog"]
            K1["基础能力: ★★★☆☆"]
            K2["Agent 编排: ★★★★☆"]
            K3["自主性: ★★★☆☆"]
            K4["生态集成: ★★☆☆☆"]
            K5["成熟度: ★★☆☆☆"]
        end

        subgraph JManus框架["JManus"]
            J1["基础能力: ★★★☆☆"]
            J2["Agent 编排: ★★★☆☆"]
            J3["自主性: ★★★★★"]
            J4["生态集成: ★★☆☆☆"]
            J5["成熟度: ★★☆☆☆"]
        end
    end

    style SpringAI fill:#e3f2fd
    style SAAlibaba fill:#c8e6c9
    style Koog框架 fill:#fff9c4
    style JManus框架 fill:#ffe0b2
```

### 6.2 详细对比表

| 维度 | Spring AI | Spring AI Alibaba | Koog | JManus |
|------|-----------|-------------------|------|--------|
| **基础定位** | AI 集成框架 | Spring AI 增强版 | Kotlin Agent 框架 | 通用自主 Agent |
| **语言** | Java | Java | Kotlin | Java |
| **Agent 模式** | 工具调用 + Advisor | Agent Graph + 工作流 | DSL 协程编排 | ReAct + 自主规划 |
| **多 Agent 协作** | 手工实现 | 内置 Graph | 内置 Pipeline | 内置（Agent 自主委派） |
| **MCP 支持** | 客户端 + 服务端 | 同 Spring AI | 社区适配 | 社区适配 |
| **模型支持** | 全主流模型 | 全主流 + 通义千问 | 全主流模型 | 全主流模型 |
| **Spring 集成** | 原生 | 原生 | 需适配 | 需适配 |
| **学习曲线** | 低（Spring 开发者） | 低 | 中（需学 Kotlin） | 中 |
| **企业级特性** | 强（Observation 等） | 强 | 弱 | 中 |
| **社区活跃度** | 高 | 中高 | 中 | 中 |
| **许可证** | Apache 2.0 | Apache 2.0 | Apache 2.0 | Apache 2.0 |

### 6.3 架构哲学对比

```mermaid
graph TB
    subgraph 理念["四框架的架构哲学"]
        subgraph 哲学1["工具箱哲学"]
            SA_PH["Spring AI：<br/>提供最好的工具<br/>你自己拼装"]
        end

        subgraph 哲学2["脚手架哲学"]
            SAA_PH["Spring AI Alibaba：<br/>工具 + 脚手架<br/>帮你搭起来"]
        end

        subgraph 哲学3["语言哲学"]
            KOOG_PH["Koog：<br/>用语言特性<br/>重新定义开发体验"]
        end

        subgraph 哲学4["自主哲学"]
            JMANUS_PH["JManus：<br/>Agent 自己决定<br/>一切"]
        end
    end

    style 哲学1 fill:#e3f2fd
    style 哲学2 fill:#c8e6c9
    style 哲学3 fill:#fff9c4
    style 哲学4 fill:#ffe0b2
```

---

## 7. 与其他主流 Agent 框架的对比

### 7.1 跨生态对比

```mermaid
graph TB
    subgraph 生态全景["Agent 框架生态全景"]
        subgraph Java["Java/JVM 生态"]
            SA["Spring AI"]
            SAA["Spring AI Alibaba"]
            KOOG["Koog"]
            JMANUS["JManus"]
            LANG4J["LangChain4j"]
        end

        subgraph Python["Python 生态"]
            LC["LangChain / LangGraph"]
            AUTOGEN["AutoGen"]
            CREWAI["CrewAI"]
            OPENAI_SDK["OpenAI Agents SDK"]
        end

        subgraph 通用["语言无关"]
            DIFY["Dify"]
            COZE["Coze"]
            FLOWISE["Flowise"]
        end
    end

    style Java fill:#e3f2fd
    style Python fill:#e8f5e9
    style 通用 fill:#fff9c4
```

### 7.2 Java 生态内部对比：Spring AI vs LangChain4j

| 维度 | Spring AI | LangChain4j |
|------|-----------|-------------|
| **设计哲学** | Spring 生态原生 | LangChain Python 的 Java 移植 |
| **依赖管理** | Spring Boot Starter | 独立依赖 |
| **社区** | Spring 官方 + VMware | 社区驱动 |
| **成熟度** | 2.0 GA | 较早发布但仍在迭代 |
| **企业特性** | 深度 Spring 集成 | 轻量、独立 |
| **学习曲线** | Spring 开发者低 | 任何 Java 开发者 |
| **推荐场景** | 已有 Spring 技术栈 | 非 Spring 项目 |

### 7.3 Java vs Python 生态

```mermaid
graph TB
    subgraph 对比维度["Java vs Python Agent 生态"]
        subgraph Java优势["Java/Spring 优势"]
            JA1["企业级成熟度<br/>Spring 生态 20 年积累"]
            JA2["类型安全<br/>编译期检查"]
            JA3["性能与可扩展性<br/>虚拟线程 / GraalVM"]
            JA4["运维成熟度<br/>decades of DevOps"]
        end

        subgraph Python优势["Python 生态优势"]
            PA1["模型研究前沿<br/>多数论文首发 Python"]
            PA2["框架丰富度<br/>LangChain / AutoGen / ..."]
            PA3["数据科学生态<br/>NumPy / Pandas / ..."]
            PA4["迭代速度<br/>动态语言、快速原型"]
        end
    end

    style Java优势 fill:#e3f2fd
    style Python优势 fill:#e8f5e9
```

---

## 8. 选型决策框架

### 8.1 决策树

```mermaid
graph TB
    START["你的场景？"] --> Q1{"需要通用自主 Agent？<br/>（Agent 自己决定做什么）"}
    Q1 -->|"是"| JMANUS["推荐 JManus"]
    Q1 -->|"否"| Q2{"使用 Kotlin 技术栈？"}
    Q2 -->|"是"| KOOG["推荐 Koog"]
    Q2 -->|"否"| Q3{"使用阿里云 / 通义千问？"}
    Q3 -->|"是"| SAA["推荐 Spring AI Alibaba"]
    Q3 -->|"否"| Q4{"需要多 Agent 图编排？"}
    Q4 -->|"是"| SAA2["推荐 Spring AI Alibaba<br/>（Graph 能力）"]
    Q4 -->|"否"| SA["推荐 Spring AI"]

    style JMANUS fill:#ffe0b2
    style KOOG fill:#fff9c4
    style SAA fill:#c8e6c9
    style SAA2 fill:#c8e6c9
    style SA fill:#e3f2fd
```

### 8.2 场景化推荐

| 场景 | 首选框架 | 备选 | 理由 |
|------|----------|------|------|
| **Spring Boot 企业应用** | Spring AI | Spring AI Alibaba | 无缝集成 Spring 生态 |
| **阿里云全栈** | Spring AI Alibaba | Spring AI | DashScope/Nacos 深度集成 |
| **Kotlin 微服务** | Koog | Spring AI + Kotlin DSL | 协程 + 类型安全 DSL |
| **通用 AI Agent** | JManus | Spring AI + 手工编排 | 自主决策能力强 |
| **多 Agent 协作系统** | Spring AI Alibaba | Koog | Graph 编排能力 |
| **快速原型** | JManus | Spring AI Alibaba | 开箱即用的自主能力 |
| **高可观测性需求** | Spring AI | Spring AI Alibaba | Micrometer 深度集成 |
| **严格类型安全** | Koog | Spring AI | Kotlin 编译期检查 |
| **金融/银行级** | Spring AI 原生 + 自建治理 | - | 最大控制力，满足合规 |
| **多租户 SaaS** | Spring AI 原生 + 自建多租户 | - | 框架级多租户需深度定制 |
| **内部工具/个人使用** | JManus | - | 即用性最强 |
| **教育/学习** | Spring AI 原生 | - | 理解底层原理最重要 |
| **高并发实时场景** | Spring AI 原生 + WebFlux | - | 响应式栈优势 |

### 8.3 选型的常见误区

```mermaid
graph TB
    subgraph 误区["框架选型常见误区"]
        M1["误区1：追求功能最全<br/>实际只需核心功能"]
        M2["误区2：忽视团队能力<br/>Kotlin 团队选了 Java 框架"]
        M3["误区3：低估迁移成本<br/>框架深度耦合后难以更换"]
        M4["误区4：过度关注框架<br/>Agent 的核心在 Prompt 和工具设计"]
        M5["误区5：忽视社区健康<br/>选择停止维护的框架"]
    end

    style 误区 fill:#ffcdd2
```

---

## 9. 混合使用策略

### 9.1 框架组合的可能性

在实际项目中，多个框架可以 **混合使用** 而非互斥选择：

```mermaid
graph TB
    subgraph 组合["框架组合策略"]
        subgraph 方案A["方案 A：Spring AI 为主"]
            SA_BASE["Spring AI<br/>（基础 + 模型接入）"]
            SA_MCP["MCP 工具生态"]
            SA_CUSTOM["自定义 Agent 编排<br/>（Advisor 链）"]
        end

        subgraph 方案B["方案 B：Spring AI Alibaba 增强"]
            SAA_BASE["Spring AI Alibaba<br/>（Spring AI 超集）"]
            SAA_GRAPH["Agent Graph<br/>（多 Agent 编排）"]
            SAA_DASHSCOPE["DashScope<br/>（通义千问）"]
        end

        subgraph 方案C["方案 C：混搭"]
            SA_CORE["Spring AI<br/>（模型接入 + Spring 集成）"]
            KOOG_DSL["Koog DSL<br/>（Agent 编排层）"]
            JMANUS_ENGINE["JManus 引擎<br/>（自主决策场景）"]
        end
    end

    style 方案A fill:#e3f2fd
    style 方案B fill:#c8e6c9
    style 方案C fill:#fff9c4
```

### 9.2 避免供应商锁定

无论选择哪个框架，都应该 **在核心逻辑与框架之间保留抽象层**：

```java
// 防止供应商锁定的抽象层设计（概念）
// 将 Agent 核心逻辑与框架解耦

public interface AgentOrchestrator {
    Flux<AgentStep> execute(AgentTask task);
}

public interface AgentMemory {
    void store(String key, Object value);
    <T> Optional<T> retrieve(String key, Class<T> type);
}

// 实现可以基于任何框架
// 切换框架时只需替换实现，不改业务逻辑
@Component
public class SpringAiOrchestrator implements AgentOrchestrator {
    // Spring AI 实现
}

@Component
@ConditionalOnProperty(name = "agent.framework", havingValue = "alibaba")
public class AlibabaOrchestrator implements AgentOrchestrator {
    // Spring AI Alibaba 实现
}
```

### 9.3 迁移路径评估

| 迁移方向 | 难度 | 说明 |
|----------|------|------|
| Spring AI -> Spring AI Alibaba | 低 | 超集兼容，只需添加依赖 |
| Spring AI Alibaba -> Spring AI | 中 | 需替换 Graph 编排和阿里云依赖 |
| Spring AI -> Koog | 高 | 编程范式不同（Reactor -> Coroutines） |
| Spring AI -> JManus | 中 | API 层差异，但都是 Java |
| LangChain4j -> Spring AI | 中 | 理念相似，API 不同 |

---

## 10. 生态发展趋势

```mermaid
timeline
    title Spring AI 生态演进
    2024 Q1 : Spring AI 0.8<br/>初始版本
    2024 Q3 : Spring AI 1.0<br/>GA 发布
    2025 Q1 : Spring AI Alibaba 发布<br/>首批扩展框架出现
    2025 Q2 : Koog / JManus 发布<br/>生态多元化
    2025 Q4 : Spring AI 2.0 里程碑预览（MCP 支持）<br/>2026-06 : 2.0.0 GA（与教程 01 口径一致）
    2026 预期 : 标准化 Agent 抽象<br/>跨框架互操作
```

### 10.1 融合趋势

```mermaid
graph LR
    subgraph 融合["Java Agent 框架融合趋势"]
        T1["Spring AI 成为<br/>Java Agent 的事实标准层"]
        T2["Spring AI Alibaba 等<br/>作为扩展层"]
        T3["Koog / JManus 等<br/>在特定场景补充"]
        T4["MCP 成为<br/>工具接入统一协议"]
    end

    T1 --> T2 --> T3
    T1 --> T4

    style 融合 fill:#e3f2fd
```

### 10.2 未来预期

| 时间 | 预期发展 |
|------|----------|
| 2026 上半年 | Spring AI 增加 Agent Graph 能力（缩小与 Alibaba 差距） |
| 2026 下半年 | Spring AI 原生支持 A2A 协议 |
| 2026-2027 | Koog 成熟度提升，进入企业采用阶段 |
| 2027+ | Java Agent 框架格局基本稳定，Spring AI 成为标准底座 |

关键趋势：

1. **Spring AI 2.0 的 Agent API**：Spring AI 正在引入更高层的 Agent 抽象（类似 `Agent` 接口），缩小与扩展框架之间的差距。这可能减少扩展框架在编排层面的差异化。
2. **MCP 标准化**：所有框架都在向 MCP 对齐，工具层的差异将缩小，编排层的差异将成为主要区分点。
3. **跨框架互操作**：A2A 协议的兴起可能使不同框架构建的 Agent 之间可以通信，减少"选一个框架"的压力。
4. **企业级特性下沉**：目前扩展框架独有的特性（编排、治理、监控）可能逐步被 Spring AI 原生吸收。

---

## 11. 总结

Spring AI 生态正处于从"单一框架"向"多框架生态"的过渡期。核心调研发现如下：

1. **三层定位清晰**：Spring AI 是内核（基础设施），Spring AI Alibaba / Koog / JManus 是扩展（框架层），具体业务 Agent 是应用。理解这个分层是选型的基础。
2. **Spring AI 是基石**：作为 Spring 官方框架，它是所有 Java Agent 项目的合理起点，统一抽象层和 Spring 生态集成是核心竞争力。
3. **三大框架各有侧重**：Spring AI Alibaba 强在企业集成和 Graph 编排，Koog 强在类型安全和 Kotlin 体验，JManus 强在即用性和通用任务完成。没有"最好"的框架，只有"最合适"的。
4. **选型核心是团队能力与场景**：技术栈匹配度比框架功能列表更重要。一个 Spring 团队选 Koog 造成的摩擦远大于框架本身的优势。
5. **避免过度依赖框架**：Agent 的核心竞争力在于 Prompt 设计、工具生态和记忆策略——这些超越了任何框架的范畴。框架是脚手架，不是建筑本身。
6. **组合使用是可行策略**：不同框架构建的 Agent 可以通过 A2A 协议互操作，不需要强迫自己"只选一个"。
7. **关注 Spring AI 2.0 的演进**：Spring AI 正在加速引入高层 Agent 抽象，这可能重新定义框架层的边界。密切关注官方路线图。

对于本教程体系的技术栈选择（Spring Boot 4.1 + Spring AI 2.0 + WebFlux + Java 21），Spring AI 是自然的核心框架。在需要多 Agent 编排时，可以考虑 Spring AI Alibaba 的 Graph 能力，或基于 Spring AI Advisor 链自行构建编排层。本教程系列的 41 篇教程正是基于 Spring AI 原生能力编写的，它们构成的知识体系超越了任何单一框架的选择。
