# 04-Java 工程师借鉴手册：Codex Harness 全部机制 → Spring AI 落地映射

> **定位**：把 00-03 全部核心机制逐条映射到 Java/Spring AI 2.0 落地，作为会话引擎建设的取经总手册。读者画像：要在 Spring AI 上自建 harness 层的 Java 架构师。锚点：[教程 00-基础与核心/02-ChatClient与对话模型]、[教程 00-基础与核心/03-工具调用]、[教程 02-SpringAI核心机制/02-Agent状态管理]、[教程 03-React前端与AgenticUI/04-流式工具调用与事件协议]、[教程 03-React前端与AgenticUI/04-流式工具调用与事件协议]、[教程 08-架构师进阶/00-上下文工程]、[教程 05-Observation可观测/07-指标治理：Token计量、SLO与基数熔断]。API 均以已实证基准为界，未实证标注「概念代码」。

---

## 一、机制总映射表（30 条）

### 骨架级（必抄）

| # | codex 机制 | Spring AI / Java 落地 |
|---|-----------|----------------------|
| 1 | 单写者 actor + 有界命令/无界事件双通道 | `ArrayBlockingQueue<Op>`(容量~512) 单消费者虚拟线程 + `Sinks.Many<AgentEvent>`；Op/Event = Java 21 sealed interface |
| 2 | SessionTask trait + 单点收尾 | `interface SessionTask { Mono<Void> run(); Mono<Void> abort(reason); }`；收尾统一在 `doFinally` 钩子（统计/落盘/终态事件/idle） |
| 3 | 三段式取消（cancel→100ms 宽限→强杀） | ①`Disposable`/取消信号 ②`block(Duration.ofMillis(100))` 宽限 ③dispose 强停 |
| 4 | AbortOnDrop 防泄漏 | try-with-resources 包 Disposable（Java 无 RAII，弱化为显式作用域） |
| 5 | 持久化先于终态事件 | `persist().then(emitFinal())` 串行保序 |
| 6 | Op/Event 枚举即完整 API 契约 | 先写契约再写实现；Web 后端直接映射 WebSocket 双向消息 |

### 交互级

| # | 机制 | 落地 |
|---|------|------|
| 7 | Steering（运行中输入不丢不打断） | 会话 pendingInput 队列；stream 工具循环的采样间隙（`concatMap`）检查合并 |
| 8 | MailboxDeliveryPhase 迟到消息裁决 | 状态机两态：已展示给用户→归下轮；未展示→本轮吸收 |
| 9 | 自动唤醒 | idle 会话监听 pending 队列带 trigger 的消息自动开新 turn |
| 10 | 事件分层（生命周期/delta/工具 Begin-End 成对/资源/流错误） | 与 [教程 03-React前端与AgenticUI/04-流式工具调用与事件协议] AgentEvent 协议同构，补充 TokenCount 资源事件 |

### 工具级

| # | 机制 | 落地 |
|---|------|------|
| 11 | spec/executor/exposure 三分离 | `ToolCallback`（已实证）+ 自研注册表侧 car data（exposure/parallel/escalate）「概念代码」 |
| 12 | 每 turn 装配（特性门控+暴露重算） | turn 开始重建 model-visible 工具集，而非启动时固定 |
| 13 | trusted vs external 注册 + 保留名守卫 | 内置工具重名 fail-fast；MCP 工具禁注册 shell 类保留名（防冒充） |
| 14 | **失败回填而非抛异常** | 工具错误返回 `{"success":false,...}` 文本；Spring AI 工具抛异常会断流 |
| 15 | RwLock 并行门 | `ReentrantReadWriteLock` 按工具粒度；支持并行=read，否则=write |
| 16 | PTY 常驻会话+增量轮询 | `session_id/chunk_id/yield_time_ms` 协议照搬；Web 形态改 WebSocket 续航 |
| 17 | 审批三态纯函数 | `decide(policy, request) → Skip/NeedsApproval/Forbidden`，单测 4×2 矩阵 |
| 18 | 结构化 key 审批缓存 | key 按资源粒度（canonical 命令/path/host+port）；命中=全 key 已批；写入=逐 key |
| 19 | 失败升级重试（最小权限先试） | TCM 装饰器两段尝试（受限→全权），缓存联动免二次审批 |
| 20 | fail-closed 默认 | 一切异常路径落最保守决策（Deny/Forbidden） |

### 上下文级

| # | 机制 | 落地 |
|---|------|------|
| 21 | `Arc<Vec>`+版本号 | `record HistorySnapshot(List,long)` + `AtomicReference`；CAS 替换 |
| 22 | 三路径压缩统一生命周期 | `CompactionStrategy` 策略接口 + 统一 CompactTask（事件/落盘不变） |
| 23 | pre/mid-turn 重注入位置 | turn 中压缩：初始上下文插在**最后一条真实 user 消息前** |
| 24 | 模型切换触发压缩 | comp_hash 等价物：模型标识 hash 变化→重压历史 |
| 25 | world_state 差异注入 | 环境快照+baseline，变化才注 diff；静态指令一次注入 |
| 26 | token 服务器优先+启发式兜底 | `Usage.getPromptTokens()` 等已实证方法做决策；估算只预警 |

### 客户端/持久化级

| # | 机制 | 落地 |
|---|------|------|
| 27 | 双层客户端+降级粘性 | ChatClient 请求级 + ProviderSession 连接级（降级后锁会话） |
| 28 | 集中重试状态机 | `Retry.backoff` 按请求类别 filter 区分 |
| 29 | 协议投影层 | 内部全量事件 → 前端视图服务投影；多端一后端 |
| 30 | append-only JSONL+投影方法族恢复 | 每行自完整 JSON；恢复入口枚举（New/Resumed/Forked）纯函数重放；TurnContext 每 turn 快照 |

## 二、按项目形态裁剪

| 形态 | 必抄 | 可省 |
|------|------|------|
| Web 服务（Spring AI 主形态） | #1-6/10-20/21-30，Op/Event→WebSocket | 本地沙箱（容器/VM 替代）、PTY（改 WebSocket 会话） |
| 流式对话 Agent | #1/2/3/5/7/10/14 + 教程 02-SpringAI核心机制/00-SSE流式通信 全量 | 审批缓存、压缩（短会话） |
| 长任务 Agent | + #21-26/30（压缩与恢复是命门） | — |
| 多 Agent 平台 | + #8/9（mailbox/唤醒）、TOML 式声明角色 | — |

## 三、最小骨架 cold start 清单（Java 版）

- [ ] `sealed interface Op/Event permits ...`（契约先行）
- [ ] 有界命令队列 + 单消费者虚拟线程 + Sinks 事件出口
- [ ] `SessionTask` 接口 + `doFinally` 单点收尾
- [ ] 三段取消包装器（`interrupt(TaskHandle)`）
- [ ] 工具注册表：exposure/parallel 元数据 + 失败回填工具基类
- [ ] 审批三态函数 + ApprovalKey 缓存（第一版只有 Never/Always 也要有表结构）
- [ ] `HistorySnapshot`+version + 一条压缩路径（先做 token-budget 最简）
- [ ] ChatClient 重试分类 + 降级粘性
- [ ] JSONL 事件日志 + 恢复重放（持久化先于终态事件）

## 四、与本项目体系的接点

- 教程层：#1-10 ↔ [教程 00-基础与核心/02-ChatClient与对话模型 §19/40]；#11-20 ↔ [教程 00-基础与核心/00-Agent核心概念 §28/31]；#21-26 ↔ [教程 08-架构师进阶/00-上下文工程]；#27-29 ↔ [教程 00-基础与核心/04-记忆与会话管理 §42/44]
- 实践层：会话引擎骨架是"管控分离"的单机内缩影（[教程 04-企业级架构主干/00-管控分离架构]）；Guardian 转译 = "AI 审 AI"受限子会话模式；JSONL 恢复与事件溯源方法论互证
- API 纪律：表内涉及 Spring API 处均为已实证基准（ChatClient/ToolCallback/Usage/entity spec）；harness 层自研部分标注「概念代码」

## 五、检验方式

- 逐条打勾：§三清单 9 项，你的项目缺哪项？
- 三大铁律自查：失败回填（#14）、持久化先于终态（#5）、fail-closed（#20）——多数自建 harness 缺至少一条。

**系列收官**：codex harness 的价值不在 Rust 实现，在于它把"LLM 之外的工程问题"切成了一组可独立迁移的机制——Java 生态按本手册逐条搬即可。

## 设计哲学与适用边界

**为什么这样组织**：

1. **按机制而非按代码映射**：机制（单写者/失败回填/投影层）可跨语言迁移，Rust 实现不可——取经的对象是"每个机制回答了什么工程问题"，30 条每条都是一条决策而非一段代码。
2. **三大铁律是兜底集**：失败回填（#14）、持久化先于终态（#5）、fail-closed（#20）共同的哲学是"**错误发生时不放大错误**"——终端产品的信任建立在响应正确上，铁律保证最坏情况下账实相符、会话不崩、权限不越。多数自建 harness 缺至少一条，正是因为它们解决的不是"正常时跑得快"，而是"出错时不出事"。
3. **裁剪表的存在本身是哲学**：codex 的克制（敢不做多 Agent、协议只留必要方法）说明 harness 复杂度该由真实需求拉出——少即是多，代码越少 bug 越少，预先抄满 30 条恰恰违背了原作精神。

**好处**：§三 9 项 cold start 清单可直接当验收单；§二 裁剪表给出"必抄/可省"的判断起点，把"全抄负担"变成"按形态取用"。

**代价**：机制表会随版本过时（codex 行号与 Spring API 均以实测为准）；「概念代码」条目必须自行 javap/round-trip 实证后才能上生产——手册给的是地图，不是路。

**适用边界**：自建 harness 前当 checklist 全量过一遍；已有会话引擎的团队用 §五 三大铁律做差距审计；纯 demo/POC 项目只抄 #1/2/14（单写者骨架、单点收尾、失败回填）三条即可起步，其余等需求出现再回来查表。
