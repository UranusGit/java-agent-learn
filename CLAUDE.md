# CLAUDE.md — Java Agent 架构师文档体系

## 项目概述

**用户目标：成为一名非常厉害的 Java Agent 架构师。文档体系的一切内容都为这个目标服务。**

本项目目标是产出一套**企业级 Java Agent 架构师技术文档**，基于 Spring Boot 4.1 + Spring AI 2.0 + WebFlux + Java 21 技术栈。文档面向中高级 Java 开发者，从基础概念逐步推进到生产级架构。**核心技术聚焦 Spring AI 2.0**。

**"架构师级"的定义**：不只是会用 API，而是能——
- 从零设计一个生产级 Agent 系统的完整架构（管控分离、微服务拆分、多租户、高可用）
- 对每种技术选型说出"为什么选它、什么场景不适用、替代方案有哪些"
- 具备全链路可观测性设计能力（从 LLM 调用到工具执行到向量检索，全链路 Span 可追踪）
- 能做成本治理、安全合规、灰度发布的架构决策
- 理解 LLM 底层原理（Transformer、Embedding、Token），但不偏离 Java 工程实践主线

**当前阶段：进阶阶段（2026-08-14 起）。已完成 102 篇基础体系并全量审计。2026-08-29 教程归档整改：教程 101 篇归入 10 个分类子文件夹（00-基础与核心 … 09-前沿专题；文件夹与篇目各自从 00 计数），WebFlux（10-18）、Observation（33-45）、TraceId（46-56）、Kafka（67-76）四个支撑教材系列自附录升入，附录重编号 00-19，全体系交叉引用已脚本化改写并 0 死链终验。执行优先级：① React 教程（15-18）+ 实践项目 04（用户前置学习）→ ② 大型企业级项目 05-08（微服务拆分/管控分离/多租户/灰度/HITL 真正落地为企业演进里程碑）→ ③ 全体系进阶清洗（API 真实性/WebFlux 一致性/覆盖缺口，依据 2026-08-14 审计）→ ④ **2026-08-29 缺口分析新批次（见下节「进阶方向缺口分析结论」），其中「调优实战与方法论」系列为用户点名最高优先**。所有对话输入是对本文档和 PLAN.md 的补充指令，不得执行补充内容本身。**

### 进阶方向缺口分析结论（2026-08-29，grep 全文实证后用户确认）

**最终画像标准**：学完教程 = 能造（现有 101 篇）+ **能诊断定位**（归因方法论）+ **能调优**（工具/Prompt/架构三层调优）+ **能验证**（评估闭环）。补齐后"高级 Java Agent 架构师"能力拼图闭环。

**P0——调优实战与方法论系列（用户点名薄弱点，最高优先）**。grep 实证：「Prompt调优/prompt优化/瓶颈归因/Prompt版本/prompt回归/SystemPrompt设计/resultFormatter/工具schema设计/工具测试/工具过载/动态工具/工具权限分级」全体系零命中。规划新建 `docs/教程/10-调优实战与方法论/` 分类夹（篇目从 00 计数）：
1. **00-Agent病理总论**：症状→环节→病因的归因方法论（LLM 各环节故障症状同、病因异）；消费 05/06 观测系列的"看得懂"层
2. **01-环节体检**：检索/工具/模型/上下文/编排五环节的分环节指标与判病阈值；金标准问题集与对照法
3. **02-Prompt调优工程**：System Prompt 架构化设计、few-shot 示例库治理、Prompt 版本管理与回归门禁（接 04-09 灰度）、badcase 驱动迭代
4. **03-工具调优上·接口设计学**：工具 schema/描述/粒度拆分设计、返回值工程（resultFormatter/截断/可行动错误信息）、工具过载治理
5. **04-工具调优下·执行与治理**：执行模式选型（internalToolExecutionEnabled）、并行工具调用、超时/重试/幂等/熔断、动态注册与权限分级、工具契约测试与"观测数据→schema 调优"飞轮
6. **05-架构调优**：调优阶梯（单环调优→升维架构的判据）、成本-效果帕累托排序、架构劣化识别（多 Agent 互污染/记忆膨胀/上下文腐化）
7. **06-综合实战**：一个"效果差 Agent"从诊断到治愈的全过程串联

**P1——横向协议与生态缺口（层级错位：概念在前沿、缺教程工程篇）**：① A2A 协议工程化教程篇（现仅前沿 00 概览+09/08 正文提及）② Agent 机器身份与 OAuth 专篇（零覆盖；on-behalf-of/MCP 授权流）③ rerank 专篇（零覆盖，落附录 19 或 10）④ 微调与数据回流决策篇（零覆盖；飞轮"训练侧"断头路）⑤ reasoning model/thinking budget 编程篇（零覆盖，落附录 17）⑥ Ollama 本地部署篇（附录 15 只写 vLLM）。

**P2——补强项**：AG-UI 标准事件协议对齐（03 系列内加节）、MCP 服务端开发与网关化治理（接项目 03）、语音/实时多模态下钻（视项目 17 深做）。**明确不做**：多模态视觉再扩、LangGraph 详解、新增项目（32 个已饱和）。

### API 真实性铁律（2026-08-14 审计后新增；2026-08-16 本地 jar javap 实证修订；2026-08-16 追加"反编译实证总纲"）

- **铁律 0——一切以本地 jar 反编译实证为准（最高优先级，覆盖一切）**：文档中出现的**所有** SDK 元素——类、接口、方法、构造器、字段、参数、注解、枚举值、配置键、依赖坐标——**必须先对本地 Maven 仓库的 jar 反编译实证，确认真实存在且签名一致，才允许写入文档**。禁止凭记忆、禁止用 1.x 知识、禁止从网上文档/博客推断、禁止"感觉应该有"。具体：
  - **唯一实证来源**：本地仓库 `/Volumes/data/software/maven/repository/org/springframework/ai/<artifact>/2.0.0/`（Spring AI/Boot/Reactor/Micrometer/MCP SDK 等同理），版本以 pom.xml 声明为准（Spring AI 2.0.0 / Boot 4.1.0）
  - **实证工具**：`javap -classpath <jar路径> <全限定类名>` 看真实签名（含方法参数、返回类型、是否 default/abstract）；`javap -c` 可看字节码确认行为；`jar tf <jar> | grep <关键词>` 确认类所在 jar 与包路径；不确定的类先 `jar tf` 全仓搜索定位
  - **流程**：先实证 → 签名核对 → 才写入；**任何未实证/无法实证的元素禁止写入文档**，只能标注「概念代码」或「需引入依赖后 javap 实证」（如 BOM 声明但本机未下载的模块）
  - **版本锁定**：同名的 1.x/2.0.0 签名可能完全不同（如 `ChatClientRequest`、`ChatMemory.get`），一律以本地 2.0.0 jar 实测为准；已实证的结论沉淀到 `scripts/api-baseline-spring-ai-2.0.0.md` 作为全体系 ground truth

- **Advisor 唯一基准**：主接口 `CallAdvisor.adviseCall(ChatClientRequest, CallAdvisorChain)` / `StreamAdvisor.adviseStream(ChatClientRequest, StreamAdvisorChain)`（2.0 式，`org.springframework.ai.chat.client.advisor.api`）。**注意：`BaseAdvisor.before(ChatClientRequest, AdvisorChain)/after(ChatClientResponse, AdvisorChain)` 在 2.0.0 真实存在**（javap 实证，双参）；需要 before/after 语义时可继承 BaseAdvisor，也可直接实现 CallAdvisor/StreamAdvisor。**禁止** `adviseRequest`、`chain.next()`、单参 before/after、`AdvisedRequest`
- **注解唯一基准**：`@Tool` / `@ToolParam`；禁止 `@ToolMethod`；`@ToolParam` 无 `value()` 属性（只有 `required()`/`description()`）
- **MCP 真实坐标**：`spring-ai-starter-mcp-client` 系列（**无** `spring-ai-starter-mcp-client-webflux`，WebFlux 走 `mcp-spring-webflux`）；客户端类型为 MCP SDK 2.0.0 的 `io.modelcontextprotocol.client.McpSyncClient`（**非** `org.springframework.ai.mcp.McpClient`，也**无** `io.modelcontextprotocol.sdk.mcp` 包）；工厂 `McpClient.sync(McpClientTransport)`；`McpSchema` 嵌套类型在 `io.modelcontextprotocol.spec`；`@Tool` 暴露为 MCP 工具需显式 `ToolCallbackProvider` Bean（`SyncMcpToolCallbackProvider`）
- **ChatMemory**：本地 2.0.0 官方仓库仅 `InMemoryChatMemoryRepository`（自动装配）；`ChatMemory.get(String)` 单参；无 Jdbc/Redis 官方仓库（持久化自研 `implements ChatMemoryRepository`）
- **HITL 正确落点**：`ToolCallingManager` 装饰器或 `ToolCallback` 包装层，不是 Advisor
- **WebFlux 铁律**：禁止 ThreadLocal 传递请求上下文（用 Reactor Context）；禁止在 EventLoop 上 block/Thread.sleep；Redis 用 ReactiveRedisTemplate
- **配置键基准**：`spring.ai.chat.observations.log-prompt|log-completion|include-error-logging`（无 `include-prompt-content`）；`spring.ai.tools.observations.include-content`；`spring.ai.mcp.client.streamable-http.connections.<name>.url`；无 `spring.ai.tool-calling.*`
- **Observation 基准**：领域上下文是 `ToolCallingObservationContext`（非 `ToolObservationContext`）；`Observation.Context` 无 `getDuration()/getTraceId()`（时长用 `ctx.put/get(Object)` 计时，TraceId 用 `Tracer.currentSpan()`）
- **结构化输出**：`entity(Class, Consumer<EntityParamSpec>)` + `useProviderStructuredOutput()/validateSchema()` 是真实 API
- **非官方/示意代码必须显式标注**："概念代码"或"伪代码，真实 API 见附录 05-SpringAI2-API基准"
- **多态/抽象 SDK 对象不可 JSON 直接序列化（2026-08-22 教训）**：Spring AI 的 `Message` 及子类（`UserMessage`/`AssistantMessage`…）字段 `private final`、无 `@JsonCreator`/`@JsonProperty`——Jackson 无 property-based Creator，**任何 `GenericJacksonJsonRedisSerializer` 直接持久化 `Message` 都会在读取时失败（读回 `LinkedHashMap` 或 `no property-based Creator`）**。正确范式 = 存可序列化的 `Map`（type/content/metadata）+ 读取时按 `MessageType` 用 `.builder()` 重建（详见附录 00-Advisor与ChatMemory §2.3）。写"能否序列化某对象"方案前，必须对该**真实对象**做 round-trip，不能只 javap 看接口
- **官方 docs/main 分支 ≠ 本地锁版本，禁止超前照抄**：Spring 官方文档（含 GitHub `main` 分支）可能描述**更高版本**的 API（如 2.1 才有 `RedisChatMemoryRepository`/`JdbcChatMemoryRepository`），本地 pom 是 2.0.0 则此类类**不存在**。设计前先 `jar tf/javap` 本地仓库确认该类/模块是否有对应 2.0.0 版本，再决定"照抄官方"还是"自研并标注明确"

### 技术栈与依赖清单

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 4.1.0 | 主框架（parent POM） |
| Spring AI BOM | 2.0.0 | AI 能力依赖管理 |
| spring-boot-starter-webflux | 4.1.0 | 响应式 Web 框架（非 MVC） |
| spring-ai-starter-model-openai | 2.0.0 | OpenAI 模型集成（含 DeepSeek 兼容） |
| Lombok | 随 Boot 版本 | 简化样板代码 |
| Java | 21 | 编译目标 |
| React | 19 | Agent 前端（教程 15-18 + 项目 04；仅文档讲解，不生成前端源码文件） |
| Vite + TypeScript | 最新稳定 | React 项目工程化 |

**额外依赖使用规则**：文档中引入 pom.xml 未声明的新依赖（如 Redis、PgVector、Micrometer Prometheus 等）时，必须标注「需在 pom.xml 中添加依赖」并给出完整 `<dependency>` 片段，但不实际修改 pom.xml。

## 目录结构与命名规范

```
docs/
├── 教程/                    # 教材性质，知识点全面透彻
│   ├── 00-XXX.md
│   ├── 01-XXX.md
│   └── ...
├── 附录/                    # 教程中非主线知识点的全量补充
│   ├── 00-子主题/
│   │   ├── 00-XXX.md
│   │   └── 01-XXX.md
│   └── 01-子主题/
│       └── ...
├── 项目/                    # 多个互相独立的企业级项目
│   ├── 00-项目A/               # 每个子文件夹是一个完整独立项目
│   │   ├── 00-XXX.md              # 项目内部从 00 开始，含自己的演进迭代
│   │   ├── 01-XXX.md
│   │   └── ...
│   ├── 01-项目B/               # 另一个完全独立的项目
│   │   └── ...
│   └── ...
└── 前沿/                    # 可选，前沿调研补充
    ├── 00-XXX.md
    └── ...
```

> **学习顺序第一优先级（2026-08-15 用户确认）：docs 顶层只有 教程/项目/附录/前沿 四个子文件夹；同一文件夹下的文件和子文件夹一律从 00 开始计数，序号即学习顺序。跨板块的穿插时机（何时从教程切项目、何时下钻附录）由 docs/README.md 的「推荐学习路线」说明，不通过目录物理结构表达。**

### 命名规则

- **所有文件和子文件夹**命名格式：`XX-名称`，XX 为两位数字，从 `00` 开始
- 示例：`00-Agent核心概念.md`、`01-Spring-AI入门.md`
- 子文件夹同样从 `00` 开始编号：`00-基础架构/`、`01-通信协议/`
- 数字保证阅读顺序，不可跳号；**同一文件夹内序号即学习顺序**（新增文档插入时若打乱学习顺序，需重排该文件夹编号并同步全量交叉引用）
- **铁律：文件名一律 `XX-主题.md`，禁止任何序号冗余前缀**——禁止 `XX-迭代N-xxx.md`（迭代码）、禁止 `XX-进阶N-xxx.md`（进阶N 序号）。物理编号 `XX` 已表达顺序，"迭代一/进阶三"等中文序数是冗余且会随插入造成整批重命名。**只允许在 `XX` 之后紧接主题名**（如 `03-租户配额与限流.md`、`08-轨迹级评估与模拟沙箱.md`）。主题名内的"高级/进阶"作为正常词（如 `35-高级RAG`、`14-进阶代码讲解`）不受此限，仅禁止"序数 N 紧跟迭代码/进阶N倒前缀"。2026-08-21 全体系已清理：`迭代N-`/`进阶N-` 前缀文件清零。

### 四大板块定位

| 板块 | 定位 | 特征 |
|------|------|------|
| **教程** | 教材，讲透每个知识点 | 全面、系统、不遗漏；各种情况都写清楚；含原理、代码示例、Mermaid 图 |
| **项目** | 多个**互相独立**的企业级项目 | 每个子文件夹（`00-项目A/`、`01-项目B/`...）是一个完整独立的项目，内部有自己的演进迭代（从最小 demo 起步，按需求迭代增强）；**项目之间互不依赖、互不引用、互不为前置**；一个项目结束就结束，不在项目间穿插；不生成代码文件，只生成项目文档（需求分析、架构设计、迭代说明、代码片段讲解）；代码由用户手写 |
| **附录** | 教程中非主线知识点的全量补充 | 对教程中提及但未展开的知识点做深度补充；含子文件夹按主题分类（`00`-`13` 为主题下钻层；`14-开源代码深度分析` 承载 GitHub 开源项目的全量代码分析；2026-08-29 整改后原 06-WebFlux、17-Kafka、18-Observation、23-TraceId 四个教材系列已升入教程，附录重编号为 00-19） |
| **前沿** | 可选，前沿技术调研 | 根据概念自行调研补充新技术、新趋势、新论文 |

### 开源代码深度分析规范（附录 14 适用）

`docs/附录/14-开源代码深度分析/` 承载对 GitHub 开源项目的全量代码分析（三个子文件夹：`00-deepseek-harness`、`01-codex-harness`、`02-claude-code源码架构`，各对象内部篇目从 00 计数）。除通用附录规范外，还必须：

- **每篇 ≥ 6000 字符**，含 `file:line` 代码引用、真实签名/事件名/常量（不得编造 API）；未证实处显式标注
- **每篇含多张 Mermaid 图**（64 块全量本地校验通过，用 mermaid@11.13.0 与 Typora 同款内核校验；另须通过 `scripts/check-mermaid-audit.py` 零发现）
- **每篇含「转译到 Spring AI / Java 生态」对照小节**——分析对象虽多为非 Java 技术栈，但结论必须服务于「Java Agent 架构师」目标
- **含一篇纵向合成篇（11-设计哲学与架构模式）**：五条设计哲学（Why）+ 四大架构模式（How）+ 一条消息的完整旅程（实现逻辑 What），把横向子系统篇拧成设计主线
- **含一篇 Java 全量取经手册（12-Java工程师借鉴手册）**：把 00-11 全部核心知识点（哲学/架构/各子系统机制/安全/工程化）逐条映射 Java/Spring AI 落地，作为 Java 工程师落地篇
- **每篇标注教程/附录锚点**，与体系既有内容交叉引用

### 企业级项目演进规范（项目 05-13 大型项目适用）

大型企业级项目（`05-企业级Agent中台/`、`06-金融风控Agent系统/`、`07-跨国多租户SaaS-Agent平台/`、`08-Agent供应链安全网关/`、`09-智能运维AIOps平台/`、`10-数据智能分析平台/`、`11-工业质检与预测性维护/`、`12-研发效能DevOps平台/`、`13-事件溯源Agent运行时平台/`）除遵守上述通用规范外，还必须：

- **按企业真实演进节奏迭代**：单体 → 模块化 → 服务拆分 → 控制面建设 → 治理能力 → 高可用，每个迭代篇必须回答四问——新增了什么需求、影响了哪些模块、架构如何演进、上一版本的痛点是什么
- **每个迭代篇含量化验收标准**：性能指标、可靠性指标、成本指标或安全指标中至少一项可度量
- **企业级架构能力必须作为迭代里程碑落地**（不是文末展望）：微服务拆分、管控分离、多租户、灰度发布、HITL、可观测、成本治理中至少 3 项在迭代中真正实现，并标注对应教程锚点
- **每个项目绑定 1 个主架构主题**：中台=拆分+管控分离、金融=HITL+合规审计、SaaS=多租户+灰度+驻留、安全网关=供应链+零信任、AIOps=可观测+HITL+长任务、数据平台=数据权限+成本+SQL安全、工业=边缘+多模态+时序、研发效能=多Agent+工作流+评估、事件溯源运行时=事件溯源会话日志+能力缝+工具管线+管控分离——九个项目合起来覆盖教程 20-44 全部企业级与进阶主题
- **含"演进决策记录"**：每次架构演进以 ADR 风格记录决策上下文、备选方案、取舍理由（呼应附录 10）

### 编写顺序 vs 学习顺序（两者相反，不可混淆）

```mermaid
graph LR
    subgraph 编写顺序["编写顺序（文档生成）"]
        direction LR
        W1["① 教程<br/>搭建主线"] --> W2["② 项目<br/>覆盖教程知识点"] --> W3["③ 附录<br/>补充教程盲区"] --> W4["④ 前沿<br/>扩展视野"]
    end
```

```mermaid
graph LR
    subgraph 学习顺序["学习顺序（用户阅读）"]
        direction LR
        R1["① 项目<br/>直接实践"] --> R2["② 教程<br/>遇阻塞点查教材"] --> R3["③ 附录<br/>教材不够深再查"]
    end
```

**编写顺序**：教程（主线）→ 项目（覆盖教程知识点）→ 附录（补充教程）→ 前沿（扩展）
**学习顺序**：项目（实践先行）→ 教程（遇到阻塞点查对应教材）→ 附录（看教材遇到阻塞点再深入）

这意味着：
- 项目文档中必须在关键位置标注 `[教程 XX-XXX]` 引用，指向读者实践受阻时该看的教材篇目
- 教程文档中必须在相关位置标注 `[附录 XX-XXX]` 引用，指向读者深入受阻时该看的附录篇目
- 附录是教程的"下钻层"，不是独立知识体系——必须有对应的教程锚点

## 质量标准

- **教程——教材深度**：每个知识点必须讲透彻、讲全面——原理、适用条件、边界情况、常见误区、最佳实践、反模式都要覆盖。不是"够用就行"，是"读完这一篇就不需要再查其他资料"
- **项目——企业级演进**：从最小可运行 demo 起步，按企业真实需求迭代。每次迭代明确说明：新增了什么需求、影响了哪些模块、架构如何演进、之前版本的痛点是什么。**禁止一上来就给最终版代码**
- **项目——代码不落地**：项目文档中讲解代码设计、架构决策、关键代码片段，但**不生成 .java 文件**。用户需要手写代码来学习
- **演进结构**：教程按知识体系线性编号；**每个项目内部**按迭代版本递进（00-最小demo → 01-第一次迭代 → 02-第二次迭代...），但项目之间完全独立
- **Mermaid 图表（强制）**：所有图表必须使用 Mermaid 语法——架构图、流程图、时序图、状态机、ER 图、甘特图等。**绝对禁止使用任何形式的文本流程图、ASCII 字符画、箭头拼图**（如 `┌──→`、`|==>`、`[XXX]-->` 等）。只要内容需要可视化表达，就必须用 Mermaid。每篇教程至少 2 张 Mermaid 图
- **流程图硬闸门（强制）**：禁止"光杆子链"——一条线串到底、无分支无对比的 flowchart = "列表换个框"，零信息增益。选中 flowchart 前强制检查：纯先后顺序 → 编号列表/表格/timeline；节点是"状态" → stateDiagram-v2；多角色交互 → sequenceDiagram；只有 `{ }` 判断 / ≥2 平行支路 / 分层 subgraph / 数据管道（异构节点+产物流边）才允许 flowchart。**版本演进路线（V1→VN）一律用 timeline，不画 flowchart**
- **Mermaid 子图与标签语法（强制）**：子图标题用方括号 `subgraph id["标题"]`，**禁止** `subgraph id{"标题"}`（菱形，Mermaid 11.13.0 解析失败）；标签引号必须成对闭合、定界符匹配（禁止 `A("[x]"]` 这种开头 `(` 结尾 `]` 的不匹配写法）
- **代码示例**：所有代码基于本项目技术栈（Spring Boot 4.1 / Spring AI 2.0 / WebFlux / Java 21），可编译，有完整 import 语句
- **交叉引用（两层引导）**：
  - 项目 → 教程：项目文档中在关键知识点处标注 `「遇到阻塞？→ [教程 XX-XXX §X]」`，引导读者去查教材
  - 教程 → 附录：教程文档中在需要深入处标注 `「想深入？→ [附录 XX-XXX §X]」`，引导读者去查附录
  - 引用格式统一为 `[板块 XX-XXX §X]`，如 `[教程 03-工具调用 §2]`、`[附录 00-Java21新特性/00-虚拟线程 §1]`
- **每篇文档结构**：开头 `> **定位**：本文讲什么、读者画像、前置阅读` 引用块 → 正文 → 总结
- **字数要求**：教程每篇 ≥ 3000 字（不含代码），核心架构篇 ≥ 5000 字；项目每次迭代文档 ≥ 2000 字
- **企业级架构深度**：教程和项目必须覆盖以下企业级场景，不能只讲 demo 级用法：
  - 管控分离：Control Plane（配置/治理/编排/策略）与 Data Plane（推理/执行/检索）的架构分离
  - 多微服务拆分：Agent 服务、工具服务、LLM 网关、检索服务独立部署与通信
  - 工具执行可观测：Spring AI 原生 `spring.ai.tool` Observation、Tool Call 链路追踪、参数与结果记录
  - Trace 全链路追踪：ChatClient → ChatModel → Tool → VectorStore 全链路 Span、OpenTelemetry 集成、gen_ai 语义约定
  - 多页面流式响应：跨页面/跨会话的 SSE 连接管理、断线重连、流式中断恢复
  - 历史记录持久化：会话归档、审计日志、对话回放、合规留存
  - 多租户隔离：会话隔离、资源配额、数据隔离、工具权限分级
  - 成本治理：Token 计量（`gen_ai.client.token.usage`）、模型路由降级、预算上限、按租户/用户成本归因
  - Human-in-the-Loop：人工审批触发、危险操作确认、升级机制
  - 灰度发布与版本管理：Prompt 版本控制、模型 A/B 测试、流量切分
- **架构师进阶深度**：除企业级架构外，必须覆盖以下 2026 年架构师必备的进阶能力：
  - 上下文工程：五层拼接策略（System Prompt→工具 Schema→记忆摘要→RAG→用户输入）、上下文压缩、Token 预算分配、KV/Prompt Cache、语义缓存
  - 高级 RAG：GraphRAG（知识图谱多跳推理）、Agentic RAG（Agent 自主决策检索）、混合检索+重排、自适应检索深度
  - Agent 工作流编排：DAG 图编排、条件分支与循环、状态机 vs 工作流选型、Spring AI Alibaba Graph / Koog 集成
  - 自我反思与评估：Reflection 模式、Agent 效果量化评估、在线监控+离线评估闭环
  - 性能优化：批量请求合并、并行工具调用、流式+缓存组合、Token 效率优化
  - 高级记忆架构：三层记忆（短期→长期→外部 RAG）、语义记忆 vs 情景记忆、记忆演化与衰减
  - Agent 安全深度：Prompt 注入分类（直接/间接）、Tool Poisoning、数据泄露防护（DLP）
- **架构师进阶深度 II**（2026 年生产级 Agent 必备）：
  - 长任务持久化与恢复：检查点（Checkpoint）、崩溃后断点恢复、幂等重试、预算控制（最大轮次/Token/时长）、死循环防护
  - 数据飞轮与持续改进：用户反馈采集 → 在线/离线评估 → Prompt/RAG/模型优化 → 灰度发布 → 闭环飞轮；何时微调 vs 优化 Prompt vs 改进 RAG
  - 响应式错误处理：WebFlux 异常传播链（onErrorResume/onErrorMap/retryWhen）、流式中断后的部分结果处理、背压实战、响应式上下文传递
  - Agent 治理与合规：AI 治理框架（NIST AI RMF / EU AI Act）、模型卡片与数据卡片、数据隐私（GDPR / 个人信息保护法）、偏见检测与缓解、行为审计与可解释性
  - 多模型协作与供应策略：模型编排（不同任务用不同模型）、多供应商冗余、API Key 池管理、自建 vs 商用决策框架、边缘部署（Ollama/vLLM 混合编排）

## 硬性规则

1. **限制已解除**（2026-08-13）。允许编写 `docs/` 下的实际文档。用户会在需要时重新开启限制
2. **IMPORTANT** 用户每次补充后，同步更新 CLAUDE.md（行为约束变化）和 PLAN.md（任务/范围变化），保持两者一致
3. **IMPORTANT** docs 顶层只有 `教程/`、`项目/`、`附录/`、`前沿/` 四个子文件夹（教程、项目、附录均可有分类子文件夹），不按 architecture/patterns/protocol 等主题分目录；同一文件夹下的子文件夹与文件各自从 `00` 计数、序号即学习顺序
4. **IMPORTANT** 所有文件和子文件夹命名格式为 `XX-名称`，XX 两位数字从 `00` 开始，不跳号
5. **IMPORTANT** 项目文档不生成代码文件（.java），只生成 Markdown 文档讲解架构和设计；用户需要手写代码
6. **IMPORTANT** 项目演进从最小 demo 起步，按需求迭代逐步增强——禁止一上来就给最终版架构
7. **IMPORTANT** `docs/项目/` 下的每个子文件夹是一个**互相独立**的完整项目。项目之间严禁交叉引用、严禁互为前置条件、严禁在项目 A 中引用项目 B 的内容。一个项目子文件夹结束，该项目就彻底结束
8. **IMPORTANT** 所有代码示例必须与 pom.xml 声明的依赖一致——Spring Boot 4.1.0、Spring AI 2.0.0、WebFlux（非 MVC）、Java 21
9. **禁止**在文档中硬编码密钥、Token、连接字符串；使用 `${ENV_VAR}` 占位符
10. **禁止**生成与 Spring AI 2.0 API 不兼容的代码；引用 API 时标注版本 `// Spring AI 2.0.0`
11. 每篇教程必须包含"适用场景"和"不适用场景"两个段落
12. **IMPORTANT** 所有图表必须使用 Mermaid 语法。禁止任何形式的文本流程图、ASCII 字符画、箭头拼图。需要可视化时一律用 Mermaid
13. **IMPORTANT** 禁止光杆子 flowchart（纯先后顺序、版本演进路线无分支一律用表格/列表/timeline）；子图标题必须用方括号 `subgraph id["标题"]`，禁止菱形 `{}`。批量生成后用 `scripts/check-mermaid-audit.py` 自检，0 发现才交付
14. 所有外部引用（论文/规范/文档）必须标注来源链接

## 工作流偏好

- 用户补充新需求时：先更新 PLAN.md 的任务列表，再判断是否需要更新 CLAUDE.md 的约束规则
- 多方案选择时：列出 2-3 个方案的优劣对比，给出推荐，由用户决定
- CLAUDE.md 超 200 行时：将领域特定规则拆到 `.claude/rules/` 下
- 文档生成顺序：按 PLAN.md 中的 Phase 顺序推进，不跳跃
- **运行时排错方法论（跨项目通用，不绑定任何具体工程）**：
  - 下"某文件当前内容 / 是否已被修改"结论前，先重新 Read，禁用旧快照断言——文件变更在高频，几轮前的 Read 结果可能已失效
  - 下"编译是否通过 / 是否成功"结论前，先 `mvn clean` 再验证——Maven 增量编译会吃 `target/` 里的历史 class，让它"看似通过"实为假象
  - 报错排查优先"复现用户那一条命令 + 逐变量消去"，用 curl、javap、jar 等取真值，而不是罗列可能原因
  - 用户质疑结论时，视为命令去重新查证，不要复述旧结论兜圈子
  - 涉及源码/依赖/配置文件的改动仍需遵守「禁止触碰」与用户授权；但一切读取、编译、运行、实证随时可做
  - **持久化/序列化 bug 必须"读写两端都验证"，警惕"第一次成功、第二次失败"**：写入成功 ≠ 能读回。读回只在已有数据时触发（故首次请求无历史读空不炸、第二次读旧数据才炸）。对"对象→存储"这类多态反序列化，用独立最小可运行程序（复制到 /tmp）做 serialize→deserialize 的 round-trip，打印返回真实类型，别只看"能写"或"编译过"
- **硬规则「先实证，后结论」**：任何给用户的**结论/方案/报错原因**，在落笔前必须已经用实证验证过；给结论与实证之间是一条硬闸门——**没有先跑过（编译 / 运行 / round-trip / 对真实依赖验证）就不许给出最终结论**。落在纸上的每一句话都要能回答"我怎么验证的"。不给"先试这个不行再试那个"，不给未经实证的猜测式结论。

## 禁止触碰

- **不得执行**任何文档内容的实际编写（只更新 CLAUDE.md 和 PLAN.md）
- **不得生成**任何 .java 代码文件或项目源码文件
- **不得修改** `.claude/` 目录下的任何文件
- **绝对禁止触碰 `src/` 下的源码，一行都不许改、不许新增、不许删除**（含新建任何 `.java` 文件到 `src/`）。需要编译/运行实证时，一律复制到 `/tmp` 下的临时副本做验证，严禁直接改动 `src/` 内文件或 `pom.xml`
- **不得修改** `.env`、`.gitignore`
