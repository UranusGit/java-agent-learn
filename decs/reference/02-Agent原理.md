# 第三阶段 - Agent（智能体）

> 目标：让大模型从"只会说话的鹦鹉"变成"能动手的智能枢纽"。
> Agent = LLM + 推理循环 + Tools + Memory

---

## 1. 核心心智模型

### 1.1 一句话理解

> **Agent = LLM + 推理循环（ReAct/Plan-Execute）+ Tools + Memory**

### 1.2 类比 Java

| AI 概念 | Java 类比 |
|---------|----------|
| Tool | 注解了 `@Tool` 的 Java 方法（本质是 LLM 能"看懂的 RPC 描述符"） |
| Agent 循环 | `while (未完成) { LLM决策 → 调工具 → 结果回灌 }` |
| Function Calling / Tool Use | LLM 输出结构化 JSON 让宿主程序调用 |

---

## 2. Agent 的核心范式（必懂）

### 2.1 ReAct (Reasoning + Acting)

```
Thought: 我需要先查张三的工号
Action: query_employee_id
Action Input: {"name": "张三"}
Observation: 工号是 10086
Thought: 现在查工位
Action: query_workstation
...
```

**演进**：现代主流模型（GPT-4 / Claude / Qwen）已通过 **Function Calling / Tool Use API** 把这种文字格式收敛进 JSON，可靠性大幅提升。**ReAct 思想仍在用，只是不再走文本解析**。

### 2.2 Plan-and-Execute

- 先让 LLM 出一个完整 plan，再逐步执行
- 适合长任务，但容错差（第一步错全盘错）
- 代表：**BabyAGI / Plan-and-Solve**

### 2.3 Reflexion / Self-Critique

- 执行后让 LLM 反思、纠错
- 代价：token 翻倍
- 适用：高可靠性场景

---

## 3. 主流 Agent 框架对比

| 框架 | 语言 | 定位 | Java 工程师友好度 |
|------|------|------|-------------------|
| LangChain (Python) | Python | 大而全 | 学习用 |
| **LangGraph (Python)** | Python | 状态机式 Agent，工业级 | **强烈推荐学习** |
| LlamaIndex (Python) | Python | RAG 导向 | 学习用 |
| **LangChain4j** | **Java** | LangChain 移植 | ⭐⭐⭐⭐⭐ |
| **Spring AI** | **Java** | Spring 官方 | ⭐⭐⭐⭐⭐ |
| AutoGen | Python | 多 Agent 对话 | 学习多 Agent |
| CrewAI | Python | 角色化多 Agent | 学习多 Agent |

**建议主战场选 LangChain4j 或 Spring AI**，Python 的 LangGraph 仅用于"学思想"。

---

## 4. LangChain4j / Spring AI 关键能力对照

| 能力 | LangChain4j | Spring AI |
|------|-------------|-----------|
| Chat Model 抽象 | `ChatLanguageModel` | `ChatClient` |
| Function Calling | `@Tool` 注解 + 反射 | `@Tool` 注解 + 反射 |
| RAG | `EmbeddingStoreContentRetriever` | `VectorStore` 抽象 |
| Memory | `ChatMemory` (token/message window) | `ChatMemory` / `ChatClient` Advisors |
| AI Services（声明式接口） | `AiServices.builder()` | `@SystemMessage` + Advisors |
| 流式 | 支持 | 支持 |
| 多模型供应商 | 30+ | 20+ |

---

## 5. 自定义 Tool 的"工程师级"实践

### 5.1 LangChain4j 示例

```java
@Component
public class EmployeeTools {

    @Tool("根据员工姓名查询工号和基本信息")
    public EmployeeInfo queryEmployee(@P("员工姓名") String name) {
        return employeeService.findByName(name);
    }
}

// Agent 注入工具
Assistant agent = AiServices.builder(Assistant.class)
    .chatLanguageModel(model)
    .tools(employeeTools)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .build();
```

### 5.2 生产级要点

- **Tool 描述写不好 = Agent 不会调**。描述要包含**何时该用**和**输入语义**。
- Tool 的**入参/出参 DTO 必须可序列化**，字段名要语义清晰。
- 加**超时 + 重试 + 幂等**（Agent 可能循环调用同一工具）。
- Tool 内部抛异常要被框架转成"Observation"喂回 LLM，让它自己决策下一步。

---

## 6. 推荐资料

### 官方文档（精读）
- LangChain4j：`docs.langchain4j.dev` —— **有完整的 Java 示例**，从零起步首选
- Spring AI：`docs.spring.io/spring-ai/reference/` —— 与 Spring 生态融合最好
- LangGraph 文档：`langchain-ai.github.io/langgraph/` —— 看"为什么要状态机"

### 论文（可选，理解原理）
- ReAct: Synergizing Reasoning and Acting in Language Models (Yao et al., 2022)
- Toolformer (Schick et al., 2023)
- Reflexion (Shinn et al., 2023)

### 视频
- DeepLearning.AI *"Functions, Tools and Agents with LangChain"*
- LangChain 官方油管 *"LangGraph: Multi-Agent Workflow"*

---

## 7. 实操项目：智能运维助手

### 需求示例
> 用户：帮我查下用户服务有几个副本，CPU 高不高？
> Agent：① 调 `k8s_get_deploy` → 拿到 replicas=3 ② 调 `prom_query` → CPU 65%

### 架构（强烈建议 Java 侧实现）

```
Spring Boot (LangChain4j)
   ├── Tool: K8sClientTool（用 fabric8 io.fabric8:kubernetes-client）
   ├── Tool: PromClientTool（用 WebClient 调 Prometheus HTTP API）
   ├── Tool: LogClientTool（调 Loki API）
   └── ChatMemory: Redis-backed
```

### 关键收获
- 体验"工具描述 → 模型自动选择 → 参数抽取"全流程
- 解决**幻觉工具调用**：模型编造不存在的工具 / 错误参数 → 加 schema 校验 + 兜底
- 解决**死循环**：限制 `max_iterations` + Tool 内置"已查询过"短路

---

## 8. 避坑点

- **别给 Agent 太多工具**：超过 10 个工具时，选择准确率断崖下跌。优先做工具的"分组路由 Agent"。
- **会话内存别无限长**：默认 `MessageWindowChatMemory`，超长会导致 token 暴涨和"忘事"。
- **流式响应必须**：否则用户体验极差（等 30 秒看不到字）。
- **生产慎用 LangChain 的 AgentExecutor**（Python 版），它偶有解析异常。Java 侧 LangChain4j 的 `AiServices` 反而更稳。

---

## 9. 学习检查点

> 能讲清楚：
> - Function Calling 的底层机制（模型如何被训练输出 JSON）
> - 为什么 ReAct 时代过去了
> - Agent 死循环如何检测和中断
