# 第四阶段 - 多模态与多 Agent

> 目标：突破文本限制，理解 Agent 协作的范式。

---

## 1. 多模态

### 1.1 核心模型

| 类型 | 模型 |
|------|------|
| 闭源 | GPT-4o / Claude 3.5+ / Gemini 2.0+ |
| 开源 | LLaVA、Qwen-VL、InternVL、MiniCPM-V |

### 1.2 Java 调用方式

直接走 **OpenAI 兼容协议**，传图片 URL 或 base64。Spring AI / LangChain4j 都原生支持。

### 1.3 项目方向
- 架构图自动描述
- PDF 报表抽取（用 LlamaParse / Unstructured.io 先解析）
- 工业视觉质检 + 自然语言报告

---

## 2. 多 Agent 协作

### 2.1 框架对比

| 框架 | 特点 | 适用 |
|------|------|------|
| **LangGraph** | 显式状态机，可控性最好 | **生产首选** |
| AutoGen | 对话式协作 | 探索 |
| CrewAI | 角色化（CEO+工程师+QA） | 简单场景 |
| MetaGPT | 软件公司模拟 | 玩具 |
| **LangChain4j (Supervisor Agent)** | Java 原生 | Java 项目 |

### 2.2 经典模式

| 模式 | 说明 |
|------|------|
| **Supervisor-Worker** | 一个主控 Agent 分派任务给子 Agent |
| **Hierarchical** | 多层级 Agent |
| **Round-Table** | 多 Agent 轮流发言，最后达成共识 |

### 2.3 实操

用 LangGraph 实现"产品经理 + 架构师 + 开发"三个 Agent：
- 输入需求
- 输出规格文档 + 设计文档 + 代码框架

---

## 3. 推荐资料

- LangGraph 文档（多 Agent 章节）
- Microsoft AutoGen 论文和示例
- CrewAI 官方文档
- 论文 *"MetaGPT: Meta Programming for Multi-Agent Collaborative Framework"* (2023)

---

## 4. 避坑点

- **多 Agent 容易失控**：成本爆炸、对话陷入循环。**必须设 max_iterations 和总 token 上限**。
- **多 Agent 不一定更好**：单 Agent + 多 Tool 经常更可靠。
- **不要让 Agent 直接操作生产数据库**：通过只读 Tool + 人工审核。
