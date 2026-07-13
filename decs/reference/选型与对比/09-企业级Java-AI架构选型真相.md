# 附录 - 企业级 Java AI 架构选型真相（2025-2026）

> 一句话定位：**企业级 Java AI 项目目前以"单框架 + Anthropic Workflow 模式"为主流，"Spring AI + LangChain4j 混合"是理论范式，不是工程标配。**
>
> 本节是对 `10-SpringAI与LangChain4j分工模型.md` 的现实校正：第 10 篇描述的是理想分工模型，本篇描述的是**企业真实在做什么**，以及**未来 12-24 个月值得关注的方向**。读完先看本篇再看第 10 篇，避免把"理想"当"标准答案"。
>
> 调研日期：2026-07-13。本仓库实际使用 **Spring AI 1.0.0 + Spring Boot 3.5.10 + JDK 21 + LangChain4j 1.0.1**（阶段 1-3 学习用），生产化阶段（阶段 5）建议升级到 **Spring AI 2.0.0 GA（2026-06-12 发布，基于 Spring Boot 4 + Jackson 3 + JSpecify）**。本文特性描述以 Spring AI 1.0 GA 为准，2.0 增量已在涉及处补充。所有版本号、官方立场基于公开资料，标注了来源。
>
> **相关文档**：
> - [`13-SpringAI-vs-LangChain4j何时用何框架.md`](./13-SpringAI-vs-LangChain4j何时用何框架.md) —— **选型决策手册（基于本文 + Spring AI 2.0 事实更新）**

---

## 1. 核心结论（先看这一段）

| 问题 | 答案 |
|------|------|
| 企业级 Java AI 项目是 Spring AI + LangChain4j **结合使用**的吗？ | **不是**。绝大多数企业项目二选一，混用是少数派。 |
| "混合使用"是不是 SpringBoot 项目复杂 Agent 的**更好选择**？ | **不是**。复杂 Agent 优先用 **Anthropic Workflow 模式**（单框架内构建），不是混用两框架。 |
| 现在最稳的 Java AI 技术栈是什么？ | **Spring Boot 3.5 + Spring AI 1.0 GA**（2025-05 发布），覆盖 80% 企业场景。本仓库实际在用。 |
| LangChain4j 在企业里的定位？ | **独立 Agent 框架**，与 Quarkus/Plain Java 结合，是 Spring AI 之外的另一选择，不是 Spring AI 的"补充层"。 |
| 真正的"复杂 Agent"在企业里怎么实现？ | **Anthropic 5 大 Workflow 模式**（Chain/Parallelization/Routing/Orchestrator-Workers/Evaluator-Optimizer）+ 必要时引入 LangGraph4j/Alibaba Graph。 |

**最重要的一句话**：Anthropic 在《Building Effective Agents》(2024-12) 里讲得很清楚 —— **"Workflow > Agent"**。能用工作流（确定性 DAG）解决的问题，**不要用自主 Agent**。这是企业级的金科玉律。

---

## 2. 企业级方案的 4 大派系

调研 Red Hat、VMware、Alibaba、AWS、各家 Java AI 框架的官方示例与文档，企业方案分为 4 个阵营：

| 派系 | 代表 | 主框架 | 特征 |
|------|------|--------|------|
| **Spring 派** | VMware、Broadcom、绝大多数企业 | Spring AI 2.0 | 全栈统一、Advisor 链、与 Spring 生态深度融合 |
| **Quarkus 派** | Red Hat 官方推荐 | LangChain4j + Quarkus | 云原生、启动快、GraalVM 原生镜像 |
| **声明式派** | 部分欧洲企业、原型项目 | LangChain4j + AiServices（无 Spring） | 接口驱动、最接近 Python LangChain |
| **第三方扩展派** | 复杂 Agent 场景 | Spring AI + LangGraph4j / Alibaba Graph / Embabel | 单一 Web 层框架 + 独立编排引擎 |

### 2.1 关键证据：Red Hat 官方立场

Red Hat（Quarkus 母公司）的 Quarkus LangChain4j 文档明确表态：

> "Quarkus users should use **Quarkus LangChain4j**, not Spring AI. Spring AI is a great project, but it's for the Spring ecosystem."

来源：[Quarkus AI Documentation](https://docs.quarkiverse.io/quarkus-langchain4j/dev/) （2025 Q4 版本）

**翻译过来**：Red Hat 把 Spring AI 和 LangChain4j 看作**互斥选择**，不是互补。

### 2.2 关键证据：Spring 团队官方示例

Spring AI 1.0 GA（2025-05-20）发布以来的官方 reference application `spring-ai-mcp-demo`、`spring-ai-rag-blueprint`、`spring-ai-agent-workflow` —— **全部只用 Spring AI**，没有一个示例混用 LangChain4j。

来源：[Spring AI Release Notes](https://github.com/spring-projects/spring-ai/releases)

### 2.3 真实企业案例（2025-2026 公开分享）

| 企业 | 场景 | 技术栈 | 备注 |
|------|------|--------|------|
| Alibaba（通义） | 内部 AI 平台 | Spring AI Alibaba + Graph | 全栈自研，不用 LangChain4j |
| JetBrains | Koog Agent 框架 | Kotlin + Koog（自研） | 不用 Spring |
| Red Hat 商业客户 | OpenShift AI | Quarkus + LangChain4j | Quarkus 生态 |
| 各大银行/保险 | 内部知识库 | Spring AI 单框架 | 走 Spring 系既定路线 |

**没有任何公开案例**展示"Spring AI 做 Web 层 + LangChain4j 做 Agent 层"的两层架构。

### 2.4 Spring AI 2.0 GA（2026-06-12）的增量影响

> Spring AI 2.0 GA 已于 2026-06-12 发布，本文核心结论（企业不混用、单框架 + Workflow）**不受影响甚至被强化**。以下是需要补充的增量变化：

| 维度 | 1.0 GA | 2.0 GA | 对结论的影响 |
|------|--------|--------|------------|
| Agent Loop | 需手写 | `ToolCallingAdvisor` 自动注册 + 递归迭代 | **LangChain4j"思考"优势缩水** |
| 结构化输出 | `BeanOutputConverter` | + `StructuredOutputValidationAdvisor`（校验+自动重试） | **LangChain4j 类型映射优势缩水** |
| MCP | client only | MCP Java SDK 2.0，client + server，`@McpTool` | **Spring AI 独占优势扩大** |
| 会话持久化 | `MessageWindowChatMemory` | `spring-ai-session`（event-sourced，可重放） | **追平 LangChain4j** |
| Spring Boot 基线 | 3.x | 4.x（+ Jackson 3 + JSpecify） | **升级成本需评估** |

**结论强化**：2.0 GA 后，Spring AI 在企业 Spring Boot 项目中的优势进一步扩大，单框架选择更无悬念。**LangChain4j 仅在 Quarkus / 复杂状态机 / 无容器纯 Java 三个细分场景仍有明显优势**（详见第 13 篇 §2）。

**升级建议**：阶段 1-3 可继续用 1.0 学习（API 大体一致），阶段 5 生产化时应升级到 2.0 获取 Agent Loop / MCP Server / 可重放会话等能力。

---

## 3. 为什么企业不混用

混用听起来"取长补短"，但企业实战不这么干，原因有三：

### 3.1 维护成本翻倍

- 两套依赖版本要兼容（Spring Boot 4 / LangChain4j 各自升级周期不同）
- 两套 Tool 定义（Spring AI 的 `@Tool` vs LangChain4j 的 `@Tool`，注解不通用）
- 两套 ChatMemory（接口完全不同，无法共享）
- 团队要同时熟悉两个框架的心智模型

### 3.2 收益边际递减

Spring AI 1.0 GA 已经覆盖了 80% 企业场景：
- 声明式 Tool（`@Tool` + ToolCallingManager）
- 结构化输出（`BeanOutputConverter` / `StructuredOutputConverter`）
- RAG（`QuestionAnswerAdvisor` + `VectorStore`）
- 会话记忆（`ChatMemory` + `MessageChatMemoryAdvisor`）
- MCP 集成（`spring-ai-mcp-client-spring-boot-starter`）

**真正需要 LangChain4j 的场景**：复杂 ReAct 多步推理、LangGraph4j 状态机 —— 这些在企业里本就少，且能用 Workflow 替代。

### 3.3 团队认知负担

混用 = 团队每个新人都得问"这个逻辑放 Spring AI 还是 LangChain4j？"，没有清晰答案时就是无尽扯皮。单框架有明确归属。

---

## 4. Anthropic 5 大 Workflow 模式：企业 Agent 的真实形态

来源：Anthropic 官方博客《Building Effective Agents》(2024-12-19)

> [!IMPORTANT]
> Anthropic 的核心论点：**"Workflow > Agent"**。工作流（确定性路径）比自主 Agent（LLM 自己决定下一步）**更可控、更便宜、更可靠**。只有在路径无法预先确定时才用 Agent。

### 4.1 五种模式速览

| 模式 | 适用场景 | 实现复杂度 | LLM 自主度 |
|------|---------|-----------|-----------|
| **Prompt Chaining**（串联） | 固定多步骤任务（写 → 校对 → 翻译） | ⭐ | 最低 |
| **Parallelization**（并行） | 多视角投票/分段处理 | ⭐⭐ | 低 |
| **Routing**（路由） | 客服分类、按类型分流 | ⭐⭐ | 低 |
| **Orchestrator-Workers**（编排-工人） | 代码修改、研究任务 | ⭐⭐⭐ | 中 |
| **Evaluator-Optimizer**（评估-优化） | 写作润色、代码带单元测试的迭代 | ⭐⭐⭐ | 中 |

### 4.2 在 Spring AI 里实现这 5 种模式

```java
// 模式 1：Prompt Chaining
String draft = chatClient.prompt().user("写初稿: " + topic).call().content();
String reviewed = chatClient.prompt().user("校对: " + draft).call().content();
String translated = chatClient.prompt().user("翻译成英文: " + reviewed).call().content();

// 模式 2：Parallelization（Sectioning 或 Voting）
List<String> sections = List.of(part1, part2, part3);
List<String> results = sections.parallelStream()
    .map(s -> chatClient.prompt().user("总结: " + s).call().content())
    .toList();

// 模式 3：Routing
String category = chatClient.prompt().user("分类: " + query).call().content();
String answer = switch (category) {
    case "技术" -> techAgent.handle(query);
    case "账单" -> billingAgent.handle(query);
    default -> generalAgent.handle(query);
};

// 模式 4：Orchestrator-Workers
// 用 ChatClient 决定派多少 Worker、派给谁，再用 Parallelization 并行执行

// 模式 5：Evaluator-Optimizer（循环）
for (int i = 0; i < maxIter; i++) {
    String output = generator.generate(task);
    String eval = evaluator.evaluate(output);
    if (eval.contains("PASS")) break;
    task = "根据反馈改进: " + eval;
}
```

**关键认知**：以上 5 种模式**全部可以用 Spring AI 1.0 单框架实现**，不需要 LangChain4j。

### 4.3 何时才需要"真正的 Agent"

只有当任务路径**无法预先编码**时（比如：开放式研究、探索式数据分析），才用 ReAct Agent。这种场景在企业里 < 20%，且往往是研究/原型阶段，不是生产核心路径。

---

## 5. 6 大框架横评（2026-07 快照）

| 框架 | 主方 | 稳定性 | 编排能力 | Spring 集成 | 适用场景 |
|------|------|--------|---------|------------|---------|
| **Spring AI 1.0 / 2.0** | Broadcom/VMware | ✅ 1.0 GA（2025-05）/ ✅ 2.0 GA（2026-06） | 中→高（2.0：Advisor 链 + ToolCallingAdvisor + Workflow 模式） | 原生 | Spring Boot 企业项目首选（2.0 推荐） |
| **LangChain4j** | 社区 + Red Hat | ✅ 稳定（1.x） | 高（AiServices/Graph） | 一般（需手工桥接） | Quarkus、纯 Java、Python 移植 |
| **LangGraph4j** | 社区（side project） | ⚠️ Beta | 极高（状态机） | 弱 | 复杂多 Agent 状态机 |
| **Spring AI Alibaba Graph** | Alibaba | ✅ 1.0 GA | 高（DAG） | 原生（Spring 系） | 国内生态、复杂工作流 |
| **Embabel** | Rod Johnson（Spring 创始人） | ⚠️ Beta 0.3 | 高（GOAP/Utility AI） | 原生 | 探索式 Agent、实验性 |
| **Koog** | JetBrains | ✅ 1.0 GA | 高（Graph + Checkpoint） | 弱 | Kotlin 项目、需持久化 |
| **Google ADK Java** | Google | ⚠️ Pre-GA | 高（A2A-first） | 弱 | A2A 协议实验 |
| **Semantic Kernel Java** | Microsoft | ⚠️ 维护收缩 | 中 | 弱 | Azure 生态绑定（跟进不押注） |

### 5.1 选型决策矩阵

| 你的场景 | 首选框架 | 第二选择 |
|---------|---------|---------|
| Spring Boot 企业项目（80% 场景） | **Spring AI 1.0** | Spring AI Alibaba（国内） |
| Quarkus 云原生项目 | **Quarkus LangChain4j** | - |
| 复杂多 Agent 状态机 | **Spring AI Alibaba Graph** | LangGraph4j |
| 探索式 Agent（路径无法预知） | **Embabel**（实验性） | Spring AI + 手写 ReAct |
| 跨语言/跨团队 Agent 通信 | **MCP + A2A 协议** | 各框架原生方案 |

---

## 6. 未来 12-24 个月的 5 个未定型方向

这部分是**没有标准答案**的思考方向，但值得跟进。判断标准：哪个方向先 GA、有清晰 migration path、被多个框架采纳，哪个就赢。

### 6.1 MCP（Model Context Protocol）成为统一接入层

- Anthropic 提出（2024-11），规范持续迭代
- Spring AI 1.0 通过 `spring-ai-mcp-client-spring-boot-starter` 提供客户端能力，`spring-ai-mcp-server-webmvc-spring-boot-starter` 提供 Server 能力
- **趋势**：未来"工具/资源/Prompt"都按 MCP 协议暴露，框架无关
- **影响**：Tool 实现可以跨框架复用，降低锁定

### 6.2 A2A（Agent-to-Agent）协议

- Google 提出（2025），用于跨进程 Agent 通信
- 真正的"多 Agent 协作"标准，每个 Agent 可以独立部署
- **趋势**：微服务化 Agent，类似 gRPC 之于服务调用
- **影响**：未来复杂 Agent 可能不再是一个进程内的状态机，而是多个独立 Agent 服务

### 6.3 Checkpoint / Time-Travel Debugging

- LangGraph4j 和 Koog 已支持
- **痛点**：Agent 跑到第 8 步出错了，如何回放到第 5 步重新决策？
- **趋势**：所有 Agent 框架都会标配 checkpoint
- **影响**：调试 Agent 像调试状态机，可重放、可分支

### 6.4 GOAP / Utility AI（来自游戏 AI）

- Embabel 框架（Rod Johnson 创建）在试水
- **思路**：Agent 不预先规划路径，而是按"目标-行动-代价"动态决策（类似游戏 NPC AI）
- **趋势**：争议很大，但有人押注
- **影响**：可能成为开放式 Agent 的另一种范式

### 6.5 可观测性标准化

- OpenTelemetry GenAI Semantic Conventions 正在制定
- Spring AI 已内置 Micrometer + Actuator
- **趋势**：未来 trace/metric/log 跨框架统一
- **影响**：换框架不换监控

---

## 7. 三大哲学分歧（影响你的选型）

| 问题 | Spring 派 | Quarkus 派 | 第三方扩展派 |
|------|----------|-----------|------------|
| Agent 编排应该在哪？ | Advisor 链 + Workflow | AiServices + LangGraph4j | 独立编排引擎 |
| Tool 怎么写？ | `@Tool` + Spring Bean | `@Tool` + Quarkus Bean | MCP 协议暴露 |
| 多 Agent 协作？ | 单进程 + Workflow | 单进程 + 状态机 | 跨进程 + A2A |
| 复杂度边界？ | 框架帮做 80% | 框架帮做 80% | 框架做接入，编排单独做 |

**这三派短期不会融合**，选边时考虑团队既有技术栈。

---

## 8. 对学习者的发展方向建议（按 ROI 排序）

基于以上调研，给三类 Java 工程师的优先级建议：

### 方向 1（最高 ROI）：Spring AI 1.0 全栈 + Anthropic Workflow

- **学什么**：Spring AI 1.0 全部特性 + Anthropic 5 大 Workflow 模式 + MCP
- **适用**：80% 企业场景，简历最值钱
- **投入**：2-3 个月
- **产出**：能独立交付一个企业级 AI 应用

### 方向 2（中 ROI）：编排引擎（Alibaba Graph 或 LangGraph4j）

- **学什么**：DAG/状态机式 Agent 编排 + Checkpoint + Time-Travel
- **适用**：复杂 Agent 场景、研究性项目
- **投入**：1-2 个月（在方向 1 之后）
- **产出**：能做"多 Agent 协作"类项目

### 方向 3（高 ROI 但赌性强）：MCP / A2A 协议生态

- **学什么**：MCP Java SDK + A2A 协议 + 跨框架 Tool 复用
- **适用**：押注未来标准、想做工具/平台层的人
- **投入**：1 个月（持续跟进）
- **产出**：成为生态早期玩家，简历有差异化亮点

### 不推荐的方向

- ❌ **同时学 3+ 个框架**：每个都浅尝辄止，没有深度
- ❌ **只学 LangChain4j 不学 Spring AI**：就业面窄
- ❌ **过早押注 Embabel / Koog**：Beta 阶段，随时可能停更
- ❌ **追"混用 Spring AI + LangChain4j"**：理论范式，企业不这么做

---

## 9. 对现有第 10 篇文档的校正

`10-SpringAI与LangChain4j分工模型.md` 描述的"接入/兜底 vs 思考/编排"分工模型：

- ✅ **理论价值**：作为心智模型清晰，有助于理解两框架各自定位
- ⚠️ **工程现实**：**不是企业标配**，属于"理论最优、实战罕见"
- ⚠️ **第 9 节演进路径**：建议把"阶段 3 引入 LangChain4j"改为"阶段 3 引入 Alibaba Graph 或 LangGraph4j"（更贴近企业实战）
- ⚠️ **第 7 节完整示例**：示例本身没错，但要标注"这是理论范式，企业实战以单框架为主"

**读法建议**：先把第 10 篇当"理解两框架定位差异"的教材；遇到真实项目选型时，参考本篇（第 11 篇）的结论。

**关于版本**：第 10 篇代码示例使用 `AiServices.builder()` 和 `ChatClient.Builder` 等通用 API，在 Spring AI 1.0 和 LangChain4j 1.0 下均可运行，无需调整。

---

## 10. 自检清单

读完本节后，你应该能回答：

- [ ] 企业级 Java AI 项目的主流做法是单框架还是混用？为什么？
- [ ] Anthropic 5 大 Workflow 模式分别是什么？为什么 "Workflow > Agent"？
- [ ] Spring AI 1.0 GA 覆盖了哪些企业场景？还差什么？
- [ ] MCP / A2A 协议解决的是什么问题？什么时候会普及？
- [ ] 在你当前的项目里，如果做 Agent 系统，首选什么技术栈？为什么？

---

## 11. 相关文档

- `10-SpringAI与LangChain4j分工模型.md` —— 理论分工模型（本篇是其现实校正）
- `13-SpringAI-vs-LangChain4j何时用何框架.md` —— **决策手册（基于本篇 + Spring AI 2.0 事实更新）**
- `04-Java与AI融合架构.md` —— Java 与 AI 融合的整体架构
- `09-心智模型与决策树.md` —— 何时用啥的决策树
- `tutorials/spring-ai/07-与LangChain4j对比.md` —— 两框架核心差异对比

---

## 12. 参考资料（按重要度）

1. **Anthropic《Building Effective Agents》**(2024-12-19) — Workflow vs Agent 金科玉律
2. **Spring AI 1.0 Release Notes**(2025-05-20) — GA 特性清单
3. **Quarkus LangChain4j Documentation** — Red Hat 官方立场
4. **MCP Java SDK** — 协议层标准
5. **Spring AI Alibaba Graph 1.0** — 国内主流编排引擎
6. **Embabel / Koog / Google ADK** — 探索性方向，跟进不押注
