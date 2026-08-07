# 第三阶段 - Agent（智能体）

> 目标：让大模型从"只会说话的鹦鹉"变成"能动手的智能枢纽"。
> **Agent = LLM + 推理循环（ReAct/Plan-Execute）+ Tools + Memory**

> 📖 **Agent 理论篇**：本文是"概念字典"，只讲概念；代码与实操一律指向 `../教程/`。
> 实践路径 → `../教程/02-Tool与AgentLoop.md`（Tool）→ `../教程/11-五大Workflow模式与代码评审助手.md`（Workflow）→ `../教程/10-多Agent编排实战.md`（多 Agent）。

---

## 1. 核心心智模型

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

### 2.2 Plan-and-Execute / Reflexion / Self-Critique

- **Plan-and-Execute**：先出完整 plan 再逐步执行。适合长任务，但容错差（第一步错全盘错）。代表：BabyAGI / Plan-and-Solve。
- **Reflexion / Self-Critique**：执行后让 LLM 反思、纠错。代价：token 翻倍；适用：高可靠性场景。

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

## 5. 自定义 Tool

**Tool 实现细节（`@Tool` 注解体系 / 描述打磨 / 多 Tool 编排 / LC4j 入门对照 / 防失控）已融合进教程主线**：

> 去 `../教程/02-Tool与AgentLoop.md` —— Tool 设计与多 Tool 编排的完整章节。

概念上只需记住（生产级要点）：
- **Tool 描述写不好 = Agent 不会调**。描述要包含**何时该用**和**输入语义**。
- 入参/出参 DTO 必须可序列化，字段名语义清晰；加**超时 + 重试 + 幂等**（Agent 可能循环调用同一工具）。
- 内部异常要被框架转成 "Observation" 喂回 LLM，让它自己决策下一步。

最短示例（完整实现见教程）：

```java
@Tool("根据员工姓名查询工号和基本信息")
public EmployeeInfo queryEmployee(@P("员工姓名") String name) {
    return employeeService.findByName(name);
}
```

---

## 6. 推荐资料

- LangChain4j 官方文档（`docs.langchain4j.dev`）—— 有完整的 Java 示例，从零起步首选
- Spring AI 文档（`docs.spring.io/spring-ai/reference/`）—— 与 Spring 生态融合最好
- LangGraph 文档（`langchain-ai.github.io/langgraph/`）—— 看"为什么要状态机"
- 论文：ReAct (Yao et al., 2022) / Toolformer (Schick et al., 2023) / Reflexion (Shinn et al., 2023)

---

## 7. 实操项目：智能运维助手

运维类 Agent 属于"实操"而非"理论"，完整项目方案不在本字典展开，实战去：
- `../教程/21-端到端案例.md` —— 智能客服 Agent 全栈实战（Router + Order + RAG + Chat）
- `../教程/36-研究Agent与知识库实战.md` —— 研究 Agent 与知识库实战（含工具编排与防失控）

核心收获：体验"工具描述 → 模型自动选择 → 参数抽取"全流程，并解决**幻觉工具调用**（schema 校验 + 兜底）与**死循环**（限制迭代 + 短路）。

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

---

> 💡 **卡壳了？** 底层背景（响应式 / Redis / Kafka / SSE / 事务）去 `../附录/` 对应专题补基础；回到 `../教程/` 继续主线。
