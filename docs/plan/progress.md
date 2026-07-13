# 学习进度追踪

> 对照 [`00-整体路线.md`](./00-整体路线.md)。每完成一项把 `[ ]` 改成 `[x]`，填上完成日期。
> 不要赶进度，前一阶段没跑通不要急着进下一阶段。
>
> **项目阶梯（P1-P6）**：每个项目对应一个阶段，从易到难演进。
> 毕业标准 = 完成 P5；P6 是延伸方向。

---

## 项目阶梯总览

| # | 项目 | 难度 | 对应阶段 | 状态 |
|---|------|------|---------|------|
| **P1** | 命令行聊天机器人 | ⭐ | 阶段 1 | ⬜ |
| **P2** | 个人知识库问答（本地 PDF） | ⭐⭐ | 阶段 3 | ⬜ |
| **P3** | 单文件代码评审助手 | ⭐⭐⭐ | 阶段 4 | ⬜ |
| **P4** | 智能运维助手（K8s/日志） | ⭐⭐⭐⭐ | 阶段 5 | ⬜ |
| **P5** | 企业级多租户客服系统 | ⭐⭐⭐⭐⭐ | 阶段 6 | ⬜ |
| **P6**（可选） | 多 Agent 研究助手 | ⭐⭐⭐⭐⭐ | 阶段 7-8 | ⬜ |

---

## 阶段 0：环境准备（Day 0）

- [ ] JDK 21 已确认（`java -version`）
- [ ] Maven 3.9+ 已安装（`mvn -v`）
- [ ] IDEA 准备好（Markdown 插件 + 可选 Continue 插件）
- [ ] 模型服务二选一：
  - [ ] 方案 A：LM Studio 已安装 + 已下载模型（`qwen2.5:7b` / `llama3.2:3b`）
  - [ ] 方案 B：DeepSeek API Key 已获取
- [ ] curl 或 Postman 能调通 LLM 拿到回复

**完成日期**：______

---

## 阶段 0.5：LLM 基础速通（Day 1-3）

- [ ] 读 [`reference/心智模型与决策树/01-心智模型与决策树.md`](../reference/心智模型与决策树/01-心智模型与决策树.md) 的"心智模型"部分
- [ ] 手动测 temperature=0 vs 1 的输出差异
- [ ] 观察一次 LLM 输出的 function call JSON
- [ ] 理解"上下文窗口" vs "输出窗口"
- [ ] 知道 Token 计费（input/output/cache read）

**验收**：
- [ ] 能解释"为什么 LLM 是无状态的"
- [ ] 能解释"为什么多轮对话需要 ChatMemory"
- [ ] 能解释"temperature=0 时输出为什么还是会变"

**完成日期**：______

---

## 阶段 1：LangChain4j 入门（Week 1）—【项目 P1】

> 对照教程：[`tutorials/langchain4j/`](../tutorials/langchain4j/)

### Day 1-2：第一次调用
- [ ] 在 `pom.xml` 加 LangChain4j 依赖
- [ ] 写 `Main.java`，调用 LLM，打印回复
- [ ] 理解 `ChatLanguageModel` 抽象

### Day 3-4：多轮对话 + 记忆
- [ ] 加 `ChatMemory`，实现多轮对话
- [ ] 命令行交互式 REPL

### Day 5-6：第一个 Tool
- [ ] 写一个 `@Tool` 方法
- [ ] 让 LLM 自己决定调用
- [ ] 观察日志中的 Function Calling JSON

### Day 7：声明式 Agent
- [ ] 用 `AiServices` 重构
- [ ] 写一篇学习笔记（300 字）

### 项目 P1 验收
- [ ] 命令行多轮对话正常
- [ ] 至少 1 个自定义 Tool 可用
- [ ] 能讲清 ChatMemory 工作原理
- [ ] Git 提交至少 5 次（**功能分支，不直接 push main**）

**完成日期**：______

---

## 阶段 2：Spring AI 1.0 切入（Week 2）

### Day 1-2：Spring Boot 集成
- [ ] 在 demo01 内加 Spring AI 依赖（不新建 demo02）
- [ ] 配置 `application.yml`
- [ ] `/hello` 接口调通 LLM

### Day 3-4：ChatClient + Advisor
- [ ] 注入 `ChatClient.Builder`
- [ ] 加 `MessageChatMemoryAdvisor`
- [ ] 加 `QuestionAnswerAdvisor`（先体验，原理下阶段讲）
- [ ] 理解 Advisor 链

### Day 5-6：Tool（@Tool 注解）
- [ ] Spring AI 的 `@Tool` 重写工具
- [ ] 加数据库 Tool（JdbcTemplate）

### Day 7：双框架对比
- [ ] 写一篇对比文档（`plan/notes/` 下）至少回答：
  - [ ] 同样是 ChatMemory，两框架实现差异？
  - [ ] 同样是 Tool，描述机制差异？
  - [ ] 生产场景你会选哪个？为什么？

**完成日期**：______

---

## 阶段 3：评估方法论 + RAG（Week 3-4）—【项目 P2】

> ⚠️ **顺序调整（2026-07-13）**：评估方法论前置到 Week 3 开头，RAG 在 Week 4 用评估集测。

### Week 3：评估方法论基础（必做，不要跳）

- [ ] **构建测试集**：30-50 条 QA（常见 / 边界 / 反例）
- [ ] **检索指标**：Recall@K / MRR / NDCG
- [ ] **生成评估三件套**：
  - [ ] RAGAS（faithfulness / answer_relevancy / context_precision）
  - [ ] LLM-as-Judge（注意位置偏置）
  - [ ] 人工标注 20 条基准
- [ ] **A/B 对比方法论**：单变量改动跑全集

### Week 4 Day 1-3：基础 RAG（LangChain4j）
- [ ] 引入向量库（Chroma 或 pgvector）
- [ ] 文档加载 → 分块 → 向量化 → 入库
- [ ] `ContentRetriever` 检索增强
- [ ] 不同分块参数对比
- [ ] **用评估集跑一遍记录指标**

### Week 4 Day 4-5：Spring AI 重做 + 评估对比
- [ ] Spring AI 的 `VectorStore` + `QuestionAnswerAdvisor`
- [ ] ETL 管道（Reader/Transformer/Writer）
- [ ] **同评估集测，与 LangChain4j 对比**

### Week 4 Day 6-7：向量库选型 + 升级体验
- [ ] 向量库选型速查（Chroma/pgvector/Qdrant/Milvus/ES 适用场景）
- [ ] 知道索引类型 HNSW vs IVF 差异
- [ ] （可选）混合检索 + 重排（bge-reranker via TEI）

### 项目 P2 验收
- [ ] 至少 30 条测试集 + 3 个量化指标（Recall@5 / faithfulness / 人工评分）
- [ ] 能上传 PDF → 自动回答
- [ ] 测试报告：`plan/notes/RAG评测报告.md`（含 LC4j vs Spring AI 指标对比）

**完成日期**：______

---

## 阶段 3.5：升级到 Spring AI 2.0（Week 4 末，3-5 天）—【新增】

> ⚠️ 前置升级，避免阶段 4 在 1.0 上自研 AgentLoop 后废弃。

- [ ] **依赖升级**：Spring Boot 3.5.10 → 4.0.x，Spring AI 1.0.0 → 2.0.0
- [ ] **Jackson 2 → 3**：序列化代码全量检查
- [ ] **JSpecify null-safety**：Optional 使用审视
- [ ] **回归测试**：阶段 1-3 所有功能在新版本下跑通
- [ ] **结构化输出迁移**：`BeanOutputConverter` → `StructuredOutputValidationAdvisor`
- [ ] **重跑评估集**：阶段 3 的 30 条 QA，升级后指标不退化

### 阶段 3.5 验收
- [ ] Spring Boot 4.0.x + Spring AI 2.0.0 启动正常
- [ ] 阶段 1-3 所有功能回归通过
- [ ] **RAG 评估集回归**：Recall@5 / faithfulness 持平或更优

**完成日期**：______

---

## 阶段 4：Agent + Anthropic 5 大 Workflow 模式（Week 5-6）—【项目 P3】

> 主框架：**Spring AI 2.0**（LangChain4j 仅作对照）
> 核心认知：**Workflow > Agent**，能用工作流解决不要用自主 Agent

### Week 5 Day 1：Prompt Chaining
- [ ] Chaining 模式：读文件 → LLM 分析 → 结构化报告
- [ ] 用评估集测一遍

### Week 5 Day 2：Parallelization
- [ ] 并行：bug / 风格 / 安全 三类 Worker
- [ ] 投票模式（同文件跑 N 次取共识）

### Week 5 Day 3：Routing
- [ ] 按语言路由到 Java/Python/JS 评审员

### Week 5 Day 4：Orchestrator-Workers
- [ ] LLM 动态决定派多少 Worker

### Week 5 Day 5：Evaluator-Optimizer
- [ ] 生成 → 评估 → 改进循环
- [ ] 必须设 `maxIter`（防死循环）

### Week 6 Day 1-2：5 大模式封装成 Advisor
- [ ] `ChainingAdvisor`
- [ ] `ParallelizationAdvisor`
- [ ] `RoutingAdvisor`
- [ ] `OrchestratorWorkersAdvisor`
- [ ] `EvaluatorOptimizerAdvisor`
- [ ] 每个 Advisor 写 demo + 单测

### Week 6 Day 3-4：Agent Loop 终止条件（基于 2.0 `ToolCallingAdvisor`）
- [ ] 不再自研 AgentLoop（2.0 原生支持）
- [ ] 薄壳 Advisor：maxTurns / 美元预算 / 死循环检测（`transitionReason` 重复）

### Week 6 Day 5：工具错误规范 + Shell 安全
- [ ] `@Tool` 异常改返回 `ToolResult.error(stderr)`
- [ ] 安全的 `ShellTool`：显式拒绝元字符（绝不用正则）
- [ ] 120s 超时 + 用 `ExecutorService` / `CompletableFuture` 后台执行
- [ ] 输出三段式（preview + filePath + truncated）

### Week 6 Day 6-7：工具箱 + 评估 + 文档
- [ ] 所有 Advisor/Tool 收拢到 `org.demo01.toolkit` 包
- [ ] **用阶段 3 评估集测 P3**（指标基线）
- [ ] `plan/notes/toolkit-README.md`
- [ ] 笔记：Workflow vs Agent 何时用哪个
- [ ] 笔记：生产环境如何防 Agent 失控

### 项目 P3 验收
- [ ] **P3 单文件代码评审助手能跑**
- [ ] 5 大 Workflow 模式各至少 1 个 demo
- [ ] 5 个可复用 Advisor
- [ ] Agent 有 maxTurns / 预算 / 死循环检测三重保护
- [ ] **P3 评估集指标基线**

**完成日期**：______

---

## 阶段 5：生产化工程（Week 7-9）—【项目 P4】

> 主框架：**Spring AI 2.0 单框架**

### Week 7：流式 + 会话 + 熔断
- [ ] 流式输出（SSE）
- [ ] 会话持久化（`spring-ai-session` 或 Redis）
- [ ] 超时 + 重试 + 降级
- [ ] token 计费监控（含 prompt cache read/write）
- [ ] JSON 严格解析（`StructuredOutputValidationAdvisor`）
- [ ] **Resilience4j 三层熔断**：工具级 / 查询级 / 系统级

### Week 8：Advisor 链 + 可观测性
- [ ] **Advisor 链**（至少 3 个）：
  - [ ] 鉴权 Advisor（Spring Security）
  - [ ] 限流 Advisor（Bucket4j + Redis）
  - [ ] 审计 Advisor（append-only）
- [ ] **可观测性**：Micrometer + OpenTelemetry GenAI Semantic Conventions
- [ ] **多模型路由**：主模型失败切备用（ChatClient fallback）
- [ ] **Prompt Cache 优化**：system prompt 拆静态/动态边界，标 `cache_control`

### Week 9：MCP 接入 + 收尾
- [ ] **MCP 接入**：`@McpTool` / `@McpResource` 暴露 K8s Tool
- [ ] 消费官方 MCP Server（filesystem / git）
- [ ] （可选）Langfuse 全链路 trace
- [ ] （可选）Promptfoo CI 评估集成

### 项目 P4 验收
- [ ] **P4 智能运维助手能跑**：自然语言查 Pod / 日志 / 指标
- [ ] 流式输出正常
- [ ] 重启后会话不丢
- [ ] 至少 3 个 Advisor（鉴权/限流/审计）
- [ ] Micrometer + Prometheus 可视化
- [ ] 至少 1 个 MCP Tool 可用
- [ ] Resilience4j 三层熔断验证
- [ ] 单接口压测稳定

**完成日期**：______

---

## 阶段 6：综合项目全栈交付（Week 10+）—【项目 P5】

> 毕业项目：**企业级多租户客服系统**。复用 P3（代码评审）+ P4（运维）的 Tool。

- [ ] 多租户隔离（每企业独立知识库 + 权限）
- [ ] RAG 回答企业内部文档
- [ ] 复杂工单用 Workflow（Routing → Orchestrator-Workers → Evaluator-Optimizer）
- [ ] 接入工单系统 / CRM / 代码评审（**复用 P3 Tool**）
- [ ] 接入运维监控（**复用 P4 Tool**）
- [ ] 全链路审计
- [ ] 流式 + 会话持久化

### 毕业标准
- [ ] 代码完整可运行，README 清晰
- [ ] 架构图（Excalidraw / draw.io）
- [ ] **评估**：faithfulness > 0.85
- [ ] Docker Compose 一键部署
- [ ] 3 分钟演示视频
- [ ] **博客**发布（掘金 / 知乎 / 个人博客）
- [ ] **简历亮点**：3 个量化指标（QPS / 准确率 / 成本）

**完成日期**：______

---

## 阶段 7（可选）：编排引擎（Week 11-12）—【项目 P6 可选】

> ⚠️ 前置判断：先确认 Advisor 链 + Workflow hold 不住再上

**二选一**：
- [ ] A. Spring AI Alibaba Graph（推荐，国内生态）
- [ ] B. LangGraph4j（社区驱动，实验性，`langgraph4j-core`）

任务：
- [ ] 用编排引擎重写阶段 6 项目的复杂部分
- [ ] Checkpoint + 断点续跑
- [ ] 笔记：Workflow / 状态机 / 自主 Agent 的边界

### 项目 P6（可选）：多 Agent 研究助手
- [ ] 路由 Agent → 检索 Agent → 分析 Agent → 综合 Agent → 评审 Agent
- [ ] Checkpoint + 断点续跑

**完成日期**：______

---

## 阶段 8+：长期方向（6-12 个月+）

> **推荐学习顺序**（按依赖关系）：
> 1. MCP 生态（独立可做）→ 2. LLMOps（前置：阶段 3 评估方法论）→ 3. 成本工程（前置：LLMOps）→ 4. Agent 可靠性工程（前置：阶段 4-5）→ 5. JDK 21+（穿插）

### 8.1 MCP 生态（最高 ROI 单项投资，必做）
- [ ] 把 P3-P5 所有 Tool 改造成 MCP Server 暴露
- [ ] 写一个企业内部 MCP Server（ERP / 工单系统）
- [ ] 每季度 review MCP 规范迭代

### 8.2 LLMOps 全栈（生产化标配）
- [ ] CI/CD 集成评估（Promptfoo + GitHub Actions）
- [ ] 观测栈（Langfuse / Phoenix / Helicone / LangSmith 四选一）
- [ ] OpenTelemetry GenAI Semantic Conventions 全量打 meter/span
- [ ] A/B 测试平台
- [ ] 数据飞轮（trace → 标注 → 评估集 → 改 prompt 闭环）

### 8.3 成本工程（4 层缓存 + 路由）
- [ ] **L1**：Prompt Cache（Anthropic 5min/1hr，10x 成本节省）
- [ ] **L2**：Semantic Cache（GPTCache / Langfuse Cache）
- [ ] **L3**：Model Routing（Haiku/Sonnet/Opus 按复杂度路由）
- [ ] **L4**：Long Context Tradeoff（"Lost in the Middle"问题）

### 8.4 Agent 可靠性工程（Java 工程师护城河）
- [ ] 接入 Temporal 或 Restate（Checkpoint / 重试 / 补偿）
- [ ] Resilience4j 三层错误（工具级 / 查询级 / 系统级）
- [ ] 所有 Tool 设计 idempotencyKey（幂等性）
- [ ] 五维成本追踪（input/output/cacheWrite/cacheRead/webSearch）+ 收益递减检测
- [ ] EU AI Act Article 12 append-only 审计日志

### 8.5 JDK 21+ 现代特性深化（穿插）
- [ ] Virtual Threads（Project Loom）
- [ ] Records + Pattern Matching + Sealed Types
- [ ] JSpecify null-safety

**完成日期**：______

---

## 学习笔记索引

> 每周写一篇，存到 `plan/notes/`。

| 周次 | 主题 | 文件 |
|------|------|------|
| W1 | | |
| W2 | | |
| W3 | | |
| W4 | | |
| W5 | | |
| W6 | | |
| ... | | |

---

## 踩坑记录

> 遇到坑就记下来，存到 `plan/pitfalls.md`，避免重蹈覆辙。

| 日期 | 问题描述 | 解决方案 |
|------|---------|---------|
| | | |

---

## 总体进度速查

| 阶段 | 计划周 | 实际完成日期 | 状态 | 项目 |
|------|--------|------------|------|------|
| 阶段 0：环境准备 | Day 0 | | ⬜ | - |
| 阶段 0.5：LLM 基础速通 | Day 1-3 | | ⬜ | - |
| 阶段 1：LangChain4j 入门 | Week 1 | | ⬜ | P1 |
| 阶段 2：Spring AI 切入 | Week 2 | | ⬜ | - |
| 阶段 3：评估方法论 + RAG | Week 3-4 | | ⬜ | P2 |
| 阶段 3.5：升级到 Spring AI 2.0 | Week 4 末（3-5 天） | | ⬜ | - |
| 阶段 4：Agent + Workflow | Week 5-6 | | ⬜ | P3 |
| 阶段 5：生产化工程 | Week 7-9 | | ⬜ | P4 |
| 阶段 6：综合项目全栈 | Week 10+ | | ⬜ | P5 |
| 阶段 7：编排引擎（可选） | Week 11-12 | | ⬜ | P6 |
| 阶段 8+：长期方向 | 6-12 个月+ | | ⬜ | - |

每完成一个阶段把 ⬜ 改成 ✅，填上完成日期。
