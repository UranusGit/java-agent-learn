> **定位**：本文是 DeepSeek Harness 代码分析系列的「Java 工程师全量取经手册」。前 12 篇按子系统分析了这套 TS 框架，本文把其中**每一个可借鉴的知识点**（设计哲学、架构模式、实现逻辑、安全、工程化）全部提炼出来，逐条翻译成 Java/Spring AI 的落地方式——**不漏任何知识点**。读者画像：中高级 Java 工程师，用 Spring AI 2.0 构建生产级 Agent。前置阅读：[11-设计哲学与架构模式]（先建整体心智）。
> **使用方式**：每个取经点都是「**取什么 → 为什么值得 → Java 怎么落地**」三段式。可按主题精读，也可当速查手册按需查阅。
> **全量声明**：本文覆盖本系列 12 篇（00-11）的全部核心知识点，按「哲学 → 架构 → 按子系统机制」分层收纳；遗漏即视为未覆盖，欢迎对照逐篇核查。

## 一、总览：五层取经地图

| 层级 | 覆盖 | 说明 |
|---|---|---|
| ① 设计哲学 | 5 条哲学 + 全部落地机制 | [11][01][02] |
| ② 架构模式 | 7 个全局架构模式 | [11][02][03][07][08] |
| ③ 核心引擎机制 | 12 个 | [02] |
| ④ 会话记忆机制 | 14 个 | [03] |
| ⑤ 能力与工具机制 | 11 个 | [04] |
| ⑥ 安全机制 | 13 个 | [05] |
| ⑦ 编排机制 | 12 个 | [06] |
| ⑧ 接入面机制 | 10 个 | [07] |
| ⑨ LLM 与类型机制 | 10 个 | [08] |
| ⑩ Python/原生机制 | 6 个 | [09] |
| ⑪ 工程化机制 | 10 个 | [10] |

> **核心心法**：绝大多数取经点是**语言无关的架构决策**——Java 里用 Bean/SPI/事件总线/Advisor 替代 Cordis 的 ctx/effect/事件即可，只是「机制」不同。

---

## 二、① 设计哲学：五条哲学（改变底层判断）

### P1. 会话日志即真相（Model-visible means logged）
- **取什么**：所有模型可见输入必须能从会话日志重建，且由运行时不变式守护（`ctx.invariants`）。
- **为什么**：「会话状态可信」的地基——崩溃恢复、审计、回放、fork 全免费。Spring AI 的 `ChatMemory` 只是内存态。
- **Java 落地**：`session_event` 追加表（`session_id, seq, type, time, data JSONB`）+ `append` 唯一写入口 + `deriveMessages()` 投影 + 运行时断言「请求中每个消息可重建」。

### P2. 一切皆插件（无特权核心）
- **取什么**：模型适配器/工具注册表/会话日志/主循环都是插件；注册是可逆 effect，卸载即回滚。
- **为什么**：消灭「核心 vs 扩展」边界博弈；热装、灰度、回滚成为内置能力。
- **Java 落地**：Spring `@Component` + `SmartLifecycle` + 自定义扩展注册表，让插件贡献「注册 + 注销」成对。**注意**：Java 无 TS 声明合并，事件词表扩展要用「接口 + 注册表」替代（见 §九 T1）。

### P3. 能力缝三分离（定义/提供者/消费者）
- **取什么**：一个能力拆成接口定义 + 可替换后端 + 模型面消费者，换提供者不换模型提问方式。
- **为什么**：Shell/文件系统/检索后端很可能变（本机→沙箱→远程）；缝让换后端零改动消费者。
- **Java 落地**：抽象类 `ShellExecutor extends Service`（定义）+ `@ConditionalOnProperty` 实现（提供者）+ `@Component ToolBash`（消费者）。**为什么定义用抽象类不用 interface**：承载词汇表类型与生命周期。

### P4. 状态由数据推导（不维护第二套状态机）
- **取什么**：inbox、Activation 驻留态、权限预设、压缩锁……全部从日志/数据折叠，不单独存储。
- **为什么**：第二套状态机 = 状态不一致温床；数据当唯一权威，重放即自洽。
- **Java 落地**：`fold(List<Event>) → State` 纯函数；内存态只是缓存而非真相。

### P5. fail-closed + 敌意 peer + 显式优先
- **取什么**：无法保证安全就拒绝；把外部进程/消息当作可能伪造；默认值属于实现，spec 全必填；空值处处视为不存在。
- **为什么**：Agent 框架面对不可信输入（模型输出、子进程、远端文件、MCP 工具）。
- **Java 落地**：工具默认 deny；审批无 answerer 默认拒绝；`null`/空白凭证 = 未配置；子进程环境默认 scrub（见 §七 S5）。

---

## 三、② 架构模式：七个全局模式

### A1. 事件三域 + 四分发
- **取什么**：事件分「持久事实（session/）」「实时信号（agent/*）」「能力策略（fs/*、tools/*）」三域；每事件按 `emit`/`waterfall`/`parallel`/`serial` 四种模式分发。
- **为什么**：扩展点分层清晰，选对域是大多数改动的第一个决策。
- **Java 落地**：

| dsh 分发模式 | Java 对应物 | 用途 |
|---|---|---|
| `emit` | `ApplicationEventPublisher`（异步观察） | 通知、投影、遥测 |
| `waterfall` | 责任链 + `next()` 委派 | 请求/工具策略中间件 |
| `parallel` | `CompletableFuture.allOf` | 并行投影/扇出 |
| `serial` | 顺序 for 分发 | 顺序处理 |

### A2. 事件溯源 + CQRS 单写多读
- **取什么**：写侧只有 `append` 一个入口，读侧是任意多个投影（模型历史/持久化/标题/搜索/遥测/统计）。
- **为什么**：加新视图不改写侧；投影互不干扰。
- **Java 落地**：`session_event` 写侧 + 多个投影表（title/stats/todos）读侧；读模型由事件折叠 + `stateVersion` 缓存失效（陈旧即弃非迁移）。

### A3. 双轨事件（durable vs live）
- **取什么**：持久事实走日志广播（可重放），实时控制走活信号（携带 Agent）。
- **为什么**：SDK 重放读前者、实时协调用后者，互不污染。
- **Java 落地**：重放/审计读「事件存储」，实时 UI 用 WebFlux 流/SSE——两个通道分开。

### A4. 能力缝 = 端口-适配器
- **取什么**：三角色通常三包；提供者注册「能力」而非工具；策略以事件门存在（删掉策略插件工具仍能裸跑）。
- **为什么**：治理不污染执行，策略可独立装卸。
- **Java 落地**：接口包 + 实现包 + 消费者包；策略用 Advisor 而非硬编码进工具（呼应 [教程 02-SpringAI核心机制/04-Advisor链与拦截器]）。

### A5. Control/Data 平面
- **取什么**：配置/治理/编排（控制面）与执行/检索（数据面）分离；接入面是传输无关网关。
- **为什么**：治理插桩不 import 执行，加一种接入形态 = 加一种载体。
- **Java 落地**：`@ConfigurationProperties` 配置域 + 审批/权限/沙箱策略服务（控制面），`ChatClient`/工具执行（数据面）。

### A6. 声明式组合 + 层叠 patch（profile/bundle）
- **取什么**：命名组合 + 层叠覆盖（bundle 顺序 → profile patch → 用户 patch → CLI overlay）；**dump = 实际 boot**（同一实现）。
- **为什么**：「能看到的不等于实际跑的」是配置系统头号隐患；同一实现消灭漂移。
- **Java 落地**：Spring Profile + `application-{profile}.yml` 覆盖 + 启动输出「实际生效配置树」供审计。

### A7. 类型系统驱动跨进程 RPC（Typert）
- **取什么**：TS 源码 → 编译无关模型 → Remote 描述符 → 网关调用；前后端契约编译期锁定，运行时零手写胶水。
- **为什么**：手写胶水的跨进程调用，契约漂移是必然；生成器把漂移变成编译期错误。
- **Java 落地**：Java 侧对应 OpenAPI 生成 + Feign/gRPC 契约优先（proto/OpenAPI 生成 client+server），或注解处理器生成 RPC 胶水。

---

## 四、③ 核心引擎机制（[02] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| C1 | **turn/step/round 三层循环** | step=一次模型请求+工具；turn=零或多个 step；round=外层策略轮 | 显式状态机区分「请求/迭代/策略轮次」（[教程 00-基础与核心/07-ReAct推理模式][教程 00-基础与核心/08-Plan-and-Execute模式]） |
| C2 | **inbox 双列表（next-turn/next-step）** | 两条有序待处理列表；`claim` 取全部 next-step + 边界时 1 条 next-turn | 每 Agent 一个 FIFO 队列 + 优先级（next-step > next-turn） |
| C3 | **max-tokens sticky** | 一旦某步 max-tokens，后到的正常完成步不得降级 turn 结果 | 回合终态取「最差完成度」，不被后续步骤覆盖 |
| C4 | **Data decides** | 工具结果带 `concludesTurn` 终止 turn；pre-step 用 `steer()` 续 turn——用数据而非 listener 顺序决定 | 结果 DTO 携带「是否结束回合」字段，决策由数据流驱动 |
| C5 | **工具三阶段管线 + 单调守卫** | pre（策略）/guards（只拒绝不可翻回）/execute（执行）/post（结果改写） | 四个拦截器；guards 只允许拒绝，deny 不可被后续翻回（[教程 02-SpringAI核心机制/04-Advisor链与拦截器]） |
| C6 | **executionMode fail-closed** | 未知/未声明/无效一律 exclusive（串行），只有显式声明才 parallel | 并行工具调用默认关，显式声明（如自定义 `@ConcurrencySafe` 注解）才开（[教程 08-架构师进阶/04-Agent性能优化]） |
| C7 | **取消语义分层** | `ABORTED_BEFORE_DISPATCH`（没跑）vs `ABORTED`（跑一半）；不 abandon 已启动 promise | 取消码区分「未启动/已启动」；启动后的任务 drain 而非丢弃 |
| C8 | **深冻结不可变请求** | 请求对象 `deepFreeze`，监听器只读不可改写 | 请求 DTO 不可变（record + 防御性拷贝），拦截器只能包装 |
| C9 | **提交即成功** | `append` 校验通过即成功，observer 失败逐监听器记日志不改返回值 | 事件写入先校验+持久化，观察者异常不影响提交结果 |
| C10 | **双轨事件（durable/live）** | 持久事实走 session/event，实时控制走 agent/* | 见 A3 |
| C11 | **initiator 因果归属** | `AsyncLocalStorage` 携带当前 initiator Agent，只做因果归属不是授权 | Java `ThreadLocal`/Reactor `Context` 传播调用方身份（注意 WebFlux 勿用 ThreadLocal，用 Reactor Context，见 [教程 08-架构师进阶/08-响应式错误处理]） |
| C12 | **invariants 运行时不变式** | 包属不变式注册服务，守护「日志可重建」「turn/step 嵌套」「工具 call/result 配对」 | 契约/断言测试 + 运行时自检（[附录 04-测试策略]） |
| C13 | **scope 作用域 + shadowing** | per-agent 注册；scoped 注册替换同名的全局孪生；restriction 过滤全局工具集 | 每会话一个「能力上下文」，会话级工具/提示隔离（[教程 04-企业级架构主干/06-多租户隔离与资源治理]） |
| C14 | **surface 分离模型可见/用户可见** | `replace` 遮蔽模型历史，`append-origin` 供人类 transcript | 模型历史可压缩改写，用户已见对话记录不可篡改——两条线分离 |
| C15 | **运行时上下文去重** | 动态上下文仅在内容变化时写快照，未变化零写 | 上下文注入前比较内容，避免每 step 重复注入 |

---

## 五、④ 会话记忆机制（[03] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| M1 | **事件溯源 + 无平行 schema** | `SessionEvent` 是唯一模型；SQLite 行 1:1，JSONL 可读可打包 | 事件表即会话模型，不做「日志 + 平行存储」双写 |
| M2 | **崩溃闭合孤儿 turn** | 恢复时把「未闭合 turn」合成 `turn/end {interrupted}`，闭合而非截断 | 事件存储恢复逻辑：检测孤儿 turn → 合成闭合事件（[教程 08-架构师进阶/06-长任务持久化与中断恢复]） |
| M3 | **write-behind 批量持久化** | 固定窗口批量写 + `flush()` 排空 + 崩溃安全原子写 | 批量 insert + 定时 flush + 事务边界（[教程 04-企业级架构主干/05-历史记录持久化与合规]） |
| M4 | **每会话串行化** | 同一 session 的操作绝不交叉（`serialize(id, op)` 链） | 按 session 加锁/串行队列，防并发写交错 |
| M5 | **宁可过度拒绝的格式策略** | `ignorable` 缺失即 required；未识别事件拒读 | 未知事件类型默认拒绝重建，防静默重建被掏空的会话 |
| M6 | **shadow-price 协议** | 压缩/剪枝事件「紧邻替换前定价被影范围」，纯消费者零状态扣减 | Token 归因事件携带被影范围，消费者无需状态即可核算（[教程 04-企业级架构主干/07-成本治理与Token计量]） |
| M7 | **投影「状态引用即变化信号」** | `Object.is` 门控变化通知；whole-value 事件规则 | 投影 fold 返回新引用才触发通知，避免 diff 约定 |
| M8 | **缓存永不超前日志** | projection cache 先 checkpoint 再 flush；message-feedback 先 flush 目标日志再写侧车 | 读缓存写前先保证对应日志已落盘 |
| M9 | **durability → memory → event** | 写先落介质，再改内存，最后发事件；被拒写内存不动 | 存储写入顺序固定，读永不偏离介质 |
| M10 | **冷读阶梯** | `cachedSnapshot → 缓存行 + tail replay → 全量重建`，列表读永不加载全日志 | 投影缓存 + 增量回放 + 全量重建三级降级 |
| M11 | **前缀缓存复用** | 压缩/命名辅助调用复用对话自身前缀，KV cache 不击穿 | 压缩摘要/标题生成复用同一 system+messages 前缀 |
| M12 | **遥测边界公理** | harness 止于 `emit()`，SDK 拥有 batching/retry/loss | 采集与交付分离，harness 不为遥测可靠性负责（[教程 04-企业级架构主干/02-全链路可观测性]） |
| M13 | **附件 persist-before-event** | `saveImage` 持久化后才发引用；读取每次重校验 | 附件先落盘再入事件，引用不可解析即拒绝 |
| M14 | **全文检索 live-preferred** | 查询优先读 live，持久行被 live 影盖则排除；revision+fingerprint 对账 | 搜索索引与 live 会话对账，避免读到过期状态 |

---

## 六、⑤ 能力与工具机制（[04] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| K1 | **Provider 注册能力而非工具** | 模型面 schema 唯一归属 tool-* 包；execute 细节永不漏到 wire | 工具 schema 与实现分离，模型只见干净调用面 |
| K2 | **error-as-field 而非 rejection** | `ShellRunResult`/`CodeRunResult` 正交结果独立上报 | 结果 DTO 用独立字段（timedOut/aborted/denied），非嵌套 if |
| K3 | **闭合判别联合 + assertNever** | 结果类型闭合联合，switch 后强制穷尽 | Java `sealed interface` + 穷尽 switch（Java 21）——新增变体编译器逼你处理全部分支 |
| K4 | **策略以事件门存在** | fs 策略以 waterfall 存在，删掉策略插件工具仍能裸跑 | Advisor 拦截工具调用，策略可独立装卸（[教程 02-SpringAI核心机制/04-Advisor链与拦截器]） |
| K5 | **hostile-peer 心态** | worker 消息逐字段重建、binding 名 null-prototype、env 先 scrub | 外部进程/消息默认不可信，解析代码防御式 |
| K6 | **explicit > implicit** | spec 全必填，默认值属于实现 config | 请求 DTO 必填校验，禁止隐式魔法值 |
| K7 | **env scrub** | 子进程环境剥 `KEY/PASSWORD/SECRET/TOKEN/DSH_*` | `ProcessBuilder` 默认 scrubbed，凭据显式 merge |
| K8 | **kill 升级阶梯** | `SIGTERM → graceMs → SIGKILL` 树级，重探存活防 pid 复用 | 终止流程分级 + 重探进程树 |
| K9 | **技能目录分层合并** | 全局 + scope 层链式合并，最近层遮蔽远层，层内 rank 裁决 | 多级技能目录（项目/用户/打包）按优先级合并 |
| K10 | **MCP 工具命名约定** | `mcp__<server>__<tool>` 桥进工具注册表 | 与 Spring AI MCP 工具前缀约定同构（[教程 02-SpringAI核心机制/01-MCP协议]） |
| K11 | **LSP 无逃生舱闭合联合** | 语义导航只暴露四种操作，无 JSON-RPC 逃生舱 | 缝合口用闭合接口，防 provider 泄漏协议细节 |

---

## 七、⑥ 安全机制（[05] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| S1 | **allow-list 沙箱** | 未授予即拒绝（Landlock 内核 allow-list）；fail-closed | 容器/seccomp 白名单；设计上「显式允许」而非黑名单（[教程 04-企业级架构主干/11-安全与权限控制]） |
| S2 | **per-call 而非 per-provider 策略** | `SandboxPolicy` 每次调用携带，两个消费方可不同边界 | 沙箱策略作为调用参数，支持同一执行器服务不同安全级 |
| S3 | **凭证不落地** | 配置只存引用；describe 永不暴露值；错误诊断不引用秘密；空值即不存在 | `CredentialProvider` 只返回 `{value, source}`；日志/错误码不含密钥 |
| S4 | **每操作解析凭证** | 消费方每请求 `resolve`，轮换零重启 | `CredentialProvider.resolve(ref)` 每次调用解析（[附录 05-SpringAI2-API基准]） |
| S5 | **审批审计对 + turn 包裹** | `approval/asked` + `approval/decided` 成对，必须 turn 包裹 | 审批开始/结束成对写事件存储，崩溃 tail 与审计可区分（[教程 04-企业级架构主干/08-Human-in-the-Loop与审批流]） |
| S6 | **`never` 策略不可绕过** | 确定性拒绝在 waterfall 之前判定，prepend 不能绕过 | 策略优先级静态决定，后注册拦截器不可越权放行 |
| S7 | **会话日志即安全状态** | sandbox/mode、approval/policy 都是 log-only 事件，折叠=重放 | 安全决策也走事件溯源，可审计可重放 |
| S8 | **严格变宽升级** | 升级表 `read-only → workspace-write → full-access` 可穷举，非变宽永不提示 | 权限升级用有限状态表，拒绝任意升级请求 |
| S9 | **stderr 方言分类契约** | `denialSignatures` 只报本 backend；`runnerFailureRules` 区分「没跑成」与「被拦住」 | 把「命令被拒绝」与「命令没跑成」区分，防掩盖安全事件 |
| S10 | **超时协作式而非硬杀** | derived signal 只通知，终止权在工具 | 超时信号传递 + 工具协作，而非强杀线程 |
| S11 | **重复调用咨询性提醒** | 只注入上下文不 veto；用户插话重置链 | 工具调用检测只提醒不拦截，防循环 |
| S12 | **hook 输出限定映射** | 外部 CLI decision 只接受 approve/block，越权值忽略——faithful-but-degraded | 外部集成不可靠时诚实降级，不假装支持不存在的语义 |
| S13 | **包含式回调** | 监听失败被记录不否决已提交变更 | 观察者异常不改变操作结果，逐监听器记日志 |

---

## 八、⑦ 编排机制（[06] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| O1 | **编排不侵入主循环** | 委托/后台/定时/目标全部监听 agent/* 事件，主循环零感知 | 编排用事件监听 + `agent.followup()`，不修改核心循环 |
| O2 | **具名多 provider 注册表 vs 单 executor** | 子代理用注册表（多运输方式共存）；工作流用单引擎（单执行语义） | 决策依据：能力可否并存——并存用注册表，否则单例 |
| O3 | **一次性 run vs 可续作 Activation 分开** | one-shot=结果 promise；continuable=manager 持 AgentHandle、inbox 唯一队列 | 长任务「一次委托」与「可续作会话」用不同抽象，避免中间状态机 |
| O4 | **Activation 驻留态从数据推导** | running/waiting/settled 从 quiescence + owned-child 推导，无第二状态机 | 会话驻留态由「是否空闲 + 子任务集」推导（呼应 P4） |
| O5 | **fail-loud** | 缺能力 `UNSUPPORTED_CAPABILITY` 拒绝；移除 provider 不撤销已接受 run | 委托失败大声拒绝，不悄悄降级 |
| O6 | **settlement 通知时机** | 子代理所有权释放之前通知，父在结构上不可能误判 settled | 生命周期顺序用「结构上不可能错」而非「运行时恰好对」 |
| O7 | **事件 observe-only + 数据快照** | workflow/* payload 不带 live run，不能获得 cancel/dispose | 观察与控制分开，控制走服务方法 |
| O8 | **Ralph = workflow + subagent 组合** | fresh-round + 结构化 handoff；共享工作区为权威 | 多轮反思用「新会话 + 结构化交接」，防上下文污染（[教程 08-架构师进阶/03-自我反思与Agent评估]） |
| O9 | **goal phase 与 activation 分离** | durable phase 可重放；进程内 activation 永不持久化，恢复须人工授权 | 长任务状态可重放、自动续作须人工确认（[教程 08-架构师进阶/06-长任务持久化与中断恢复]） |
| O10 | **后台任务 owner-relative 隔离** | controller/listener 只服务本 scope 的 agent | 后台任务按归属会话隔离，不跨会话互控 |
| O11 | **schedule 会话内定时** | 到点以普通 turn 回原会话，无外部通知渠道 | 定时唤醒用「回到会话的普通 turn」，简单可靠 |
| O12 | **整表替换 todo** | `todo_write` 每 call 完整列表，last-write-wins | 待办用整表快照而非部分更新，避免 diff 复杂度 |

---

## 九、⑧ 接入面机制（[07] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| I1 | **契约层零 Node 依赖** | api/ 四象限消息不依赖宿主，浏览器可导入 | RPC 契约层独立于实现，前后端共享（对应 A7） |
| I2 | **事件下行、unary 上行** | WebSocket downlink-only；上行走 POST | WebFlux SSE 下行 + REST 上行，避免双向长连接状态同步 |
| I3 | **断线指数退避重连** | 500/2x/10s cap；rpcId 回显校验 | WebClient/SSE 重连策略 + 幂等请求 id（[教程 04-企业级架构主干/04-多页面流式响应与会话管理]） |
| I4 | **profile 层叠同一实现** | composition/dump/boot 用同一 applyEntryPatches | 配置 dump 与实际 boot 同源，不漂移 |
| I5 | **冷会话 preset 从日志解析** | 历史产生于何种组合，重建必须一致 | 恢复会话按日志记录的组合重建，不信 header |
| I6 | **Remote 客户端不用 Proxy** | `Object.defineProperty` getter + $mount，卸载即 withdraw | RPC 客户端显式安装/卸载，生命周期可控 |
| I7 | **HMR stat 轮询** | 网络挂载无 inotify，stat 轮询跨文件系统可靠 | 文件监听在 NFS 等场景退化用轮询 |
| I8 | **rpcId 复用恢复基线** | mux 打开时推基线 + pending 帧，rpcId 原样复用 | 重连后恢复事件流基线，不丢 pending 审批/问题 |
| I9 | **动态插件沙箱** | host half 在 vm 沙箱跑，Node API 陷阱，明示「可合作不防逃逸」 | 动态代码执行用沙箱隔离 + 明示信任边界 |
| I10 | **PRIVILEGED_METHODS loopback 钉死** | settings/credentials/host 操作钉在 loopback | 敏感操作限制本机访问，即使有 trustedHosts |

---

## 十、⑨ LLM 与类型机制（[08] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| L1 | **适配器只产良构 chunk** | `LlmAdapter.stream()` 唯一必写；`BlockAssembler` 统一折叠 | 适配器统一产出流块，折叠归共享实现，杜绝各自为政（[教程 08-架构师进阶/10-多模型协作与供应策略]） |
| L2 | **单 provider 调用 = 单尝试** | 适配器不做库级重试，重试在上层 | 重试不包在适配器里，放请求失败恢复层（[教程 04-企业级架构主干/10-容错与弹性设计]） |
| L3 | **持久化重试边界** | 重试挂在「请求未发任何 chunk」处，计数基于日志 | 重试条件「未产出流」+ 计数落库，重启不归零 |
| L4 | **token-meter 回放感知** | provider usage 可信才复用，否则启发式重定价 | 无 provider usage 时的回退定价 + 压力判定（[教程 04-企业级架构主干/07-成本治理与Token计量]） |
| L5 | **settings 三层解析** | schema 默认 → 组合 base → 用户节；deepFreeze 解析值 | 配置分层（默认/部署/用户）+ 解析结果不可变 |
| L6 | **path-op 脱敏写** | 脱敏调用者用路径级写入，不能整体 replace（会静默删 secret） | 给「看过脱敏视图」的调用方一个不会误删密钥的写通道 |
| L7 | **设置热发布** | watcher + debounce；读失败保 last-good | 配置文件热加载 + 失败保留最后好快照 |
| L8 | **agent-preset standing mount** | preset 组合只装一次被多会话共享；isolate realm 防泄漏 | 会话能力组合复用 + 隔离域防全局服务泄漏 |
| L9 | **类型系统驱动 RPC（Typert）** | 源码 → 模型 → 描述符 → 网关 | 见 A7 |
| L10 | **身份匿名化** | 随机 UUID 持久化，绝不从 hostname/网络派生 | 匿名用户 id 用随机 UUID，不泄露机器指纹 |

---

## 十一、⑩ Python/原生机制（[09] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| N1 | **协议优先、双 SDK 孪生** | 多语言共享同一协议，客户端结构对齐 | 多语言 SDK 共享协议定义（proto/OpenAPI），避免各讲各的 |
| N2 | **独立进程 + 协议驱动** | 运行时当子进程拉起，stdio JSON-RPC | 引擎与调用方进程隔离，跨语言可驱动（[教程 04-企业级架构主干/00-管控分离架构]） |
| N3 | **运行区间定义** | 从 inbox 收据 → 整 agent 空闲 = 调用方真正拥有的区间 | 自动化调用方明确定义「运行区间」，不因果归因单消息 |
| N4 | **运行时强制显式配置** | 无配置即退出；zero-config 是包装层显式传参 | 运行时永远知道自己要跑什么，不搞隐藏回退 |
| N5 | **Landlock 极简审计面** | ~300 行 C11 + 本地定义 UAPI + 静态 musl | 原生安全边界最小化 + 本地定义 API 摆脱版本差异 |
| N6 | **真强制探测** | `--probe` 真实 restrict 而非版本检查 | 能力探测用「真执行」而非「版本判断」，防「有接口但拒绝强制」 |

---

## 十二、⑪ 工程化机制（[10] 全量）

| # | 取经点 | 核心逻辑 | Java 落地 |
|---|---|---|---|
| E1 | **生成式文档 + 校验闭环** | catalog 由源码生成，verify-* 字节比对，stale 即失败 | 从注解生成配置/工具文档 + CI 比对新鲜度 |
| E2 | **per-file 100% 覆盖** | 每文件每行都要覆盖，显式豁免才可逃 | JaCoCo 按文件阈值 + 豁免白名单 |
| E3 | **snapshot 三态** | replay（CI 只读）/record（本地）/refresh（回放刷新） | 快照测试 CI 只读，本地才允许更新 |
| E4 | **三层门禁分工** | 本地钩子（轻量）→ 聚合门禁（全量）→ CI（平台矩阵） | pre-commit 格式化；push 全量；CI 多 JDK/平台 |
| E5 | **vendoring 决策** | 完全拥有框架层 + 修改日志制度化 | 内嵌关键依赖时维护「本地 diff 清单」并进门禁 |
| E6 | **双 Program 隔离声明合并** | Host/Client 在同一 key 声明合并冲突 → 拆两个 Program | Java 侧对应「模块边界/编译单元」分离，防声明冲突 |
| E7 | **生成器 → 运行时共享事实** | persistence catalog 生成可编译 TS 源码 | 文档/运行时共享同一份「事实」，生成而非手写 |
| E8 | **doc-typecheck** | 编译文档中的代码 fence，ignore 比例守卫 | 文档代码可编译 + 逃生舱比例受限 |
| E9 | **worker 隔离 CPU 工作** | code-runtime/workflow 用 Worker Thread | CPU 密集工作在隔离线程/线程池，不阻塞事件循环（对应 [教程 08-架构师进阶/04-Agent性能优化]） |
| E10 | **constraints 门禁** | 遍历 Project Reference 图检查引用正确 leaf 配置 | 依赖方向约束（包只依赖接口包）用架构测试（ArchUnit） |

---

## 十三、逐文档取经优先级（哪个分册对你最有用）

| 分册 | Java 工程师最该带走 | 优先级 |
|---|---|---|
| [11-设计哲学与架构模式] | 五条哲学 + 四条完整旅程（整体心智） | ★★★ 先读 |
| [02-核心引擎] | turn/step 状态机、工具三阶段、surface、深冻结 | ★★★ 想设计 Agent 循环 |
| [03-会话记忆] | 事件溯源+CQRS、崩溃闭合、shadow-price、冷读阶梯 | ★★★ 想做会话持久化 |
| [05-安全] | allow-list、凭证不落地、审批审计对、严格变宽 | ★★★ 想做生产安全 |
| [01-Cordis] | 可逆注册、四分发、waterfall 短路 | ★★ 想设计插件化扩展 |
| [06-编排] | 编排不侵入主循环、一次性 vs 可续作、状态由数据推导 | ★★ 想设计多 Agent |
| [04-模型可见能力] | 能力缝、error-as-field、策略事件门、闭合联合 | ★★ 想组织工具 |
| [07-宿主API] | 传输无关网关、事件下行/unary、类型驱动 RPC | ★★ 想做 UI 接入 |
| [08-LLM类型] | 适配器契约、持久化重试、每操作解析凭证 | ★★ 想接多模型 |
| [10-工程化] | 生成式文档、per-file 覆盖、snapshot 三态 | ★★ 想守质量 |
| [09-Python原生] | 协议优先双 SDK、极简审计面、真强制探测 | ★ 跨语言接入参考 |

## 十四、移植路线图：8 步把精华移植到 Spring AI

| # | 工程实践 |
|---|----------|
| ① | 事件溯源会话日志（session_event 表 + append 唯一写入口） |
| ② | 崩溃闭合 + write-behind（孤儿 turn 合成 interrupted） |
| ③ | 能力缝重构（shell/fs/web 定义/提供者/消费者三分） |
| ④ | 工具三阶段管线（pre/guards/execute/post 拦截器） |
| ⑤ | 凭证不落地（CredentialProvider SPI + 每操作解析） |
| ⑥ | 持久化重试（请求失败恢复事件层重试） |
| ⑦ | 投影与冷读阶梯（title/stats/todos 投影 + 缓存降级） |
| ⑧ | 可逆注册 + 灰度（插件注册/注销成对 + profile 层叠） |

**每步 Java 验收标准**：
1. 会话日志：崩溃后能重建「模型看到的全部历史」；
2. 崩溃闭合：模拟 kill -9，恢复后无悬挂 turn；
3. 能力缝：shell 后端从 local 换 sandbox，工具 schema 零改动；
4. 工具管线：加审批策略不改工具代码；
5. 凭证：轮换 API Key 下一请求即生效；
6. 重试：进程重启后重试计数不归零；
7. 投影：列表页不加载全日志；
8. 灰度：按 profile 切两套 prompt，流量可切可回滚。

## 十五、总结

本文是系列的「全量取经手册」：**11 层 100+ 取经点**（5 哲学 + 7 架构 + 15 核心引擎 + 14 会话记忆 + 11 能力 + 13 安全 + 12 编排 + 10 接入 + 10 LLM 类型 + 6 Python 原生 + 10 工程化），覆盖本系列 12 篇的全部核心知识点，逐条映射 Java/Spring AI 落地，并给出一条 8 步移植路线图。

**最重要的一句话**：DeepSeek Harness 教给你的不是「用 TS 写 Agent」——而是**一套语言无关的生产级架构决策**：会话日志即真相、能力可整体替换、状态由数据推导、fail-closed、把纪律写进门禁。这些，你在 Spring AI 里完全可以照抄。

> **定位回顾**：本文是系列的「落地篇」。至此本系列闭环：横向（00-10 按子系统）+ 纵向（11 设计主线）+ 全量取经（本文）。下一站是你自己的 Spring AI 系统——把取经点变成代码。

## 适用场景与选型建议

**适用场景**：正在用 Spring AI 2.0 构建生产级 Agent，需要从「会用 API」升级到「会做架构决策」；正在做会话持久化、工具治理、多 Agent 编排、安全防线中的任意一项，想先看业界最完整的实现再动手；需要一份按主题速查的「架构决策 → Java 落地」对照表。

**不适用场景**：只想快速写一个能跑的 demo（取经点的仪式成本对 demo 是净负担）；想直接搬运 TS 源码（本文给的是语言无关的决策与 Java 映射，不是代码移植）；项目尚无会话持久化/多轮工具调用的基本盘（P1 日志即真相、P4 状态由数据推导等地基哲学无从落地）。

**该从哪开始**：不必通读——按 §一 的五层地图直取当前痛点对应的层；动手顺序直接用 §十四 的 8 步移植路线图与每步 Java 验收标准。
