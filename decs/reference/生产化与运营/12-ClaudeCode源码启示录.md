# 附录 - Claude Code 源码启示录（Java 工程师视角）

> 一句话定位：**Claude Code 把"一个不确定的 LLM"放进"确定的系统结构"里——可借鉴的不是它怎么做 Agent，而是它怎么做"边界管理"。**
>
> 本节源自对《Claude Code 源码学习》45 章的扫描（`/Volumes/data/远端代码仓/VibeCoding/agent-docs/claudecode源码分析/`），按"对当前 Java AI 项目的可迁移性"做了**严格分级**：
> - ✅ **现在就做**：阶段 4 Agent 阶段必须用到，不学后面会卡
> - ⏳ **阶段 5 做**：生产化时再做，不是现在
> - 🚫 **不做**：过早优化、或 Java 项目用不上
>
> 调研日期：2026-07-13。Claude Code 是 TypeScript/React + Ink 实现的终端 Agent，Java 工程师重点借鉴**设计模式**而非具体实现。

---

## 0. 最重要的元认知（先读这一段）

> **可靠性 = 边界管理，不是模型更聪明。**（ch45.9.8）

Claude Code 给出一条反常识的洞察：**别把精力花在"调出最强 prompt"上，要花在"模型错了/工具失败了/上下文溢出了，系统兜底是什么"上。**

这条结论与本仓库 [`reference/选型与对比/09`](./11-企业级Java-AI架构选型真相.md) 的核心结论完全一致——**Anthropic Workflow > Agent** 的本质就是边界管理：能用确定性 DAG（Workflow）解决的，不要用自主 Agent（不可控）。

**Java 工程师的优势**：你天然懂"边界管理"（接口契约、异常处理、事务、熔断、限流）。把这套工程能力搬到 AI 应用上，就是你区别于算法工程师/Python 工程师的核心竞争力。

---

## 1. ✅ 现在就做（阶段 4 必学）

### 1.1 Agentic Loop：transitionReason 防死循环（ch05）

**问题**：你现在的 demo01 是"单轮 ChatClient 调用"，**没有真正的 Agent 循环**。一旦要做多步推理（如 ReAct），裸 `while(true)` 会死循环。

**Claude Code 的解法**：
- 把循环状态封装在不可变的 `State` 对象里（`turnCount` + `transition` + `maxOutputTokensRecoveryCount`）
- `transition` 字段记录"上次为什么重试"——例如 `collapse_drain_retry` 这种状态如果连续出现，立即终止
- 五个终止条件：自然结束（无 `tool_use`）/ `maxTurns` / 用户中断（`AbortController`）/ **美元预算 `maxBudgetUsd`** / error/blocking_limit

**Java 项目怎么用**（自研，Spring AI 1.0 无原生）：

```java
public class AgentLoop {
    public Flux<AgentEvent> run(AgentRequest req) {
        return Flux.create(sink -> {
            var state = new AgentState(req.maxTurns(), req.budgetUsd());
            while (true) {
                if (state.turnCount() > state.maxTurns()) { sink.complete(); break; }
                if (state.costUsd() > state.budgetUsd()) {
                    sink.next(new BudgetExceededEvent(state.costUsd()));
                    sink.complete(); break;
                }
                if (state.isSameRetryReasonTwice()) {
                    sink.next(new LoopDetectedEvent(state.transition()));
                    sink.complete(); break;
                }
                // ... 调 ChatClient，处理工具调用，更新 state
            }
        });
    }
}
```

**关键点**：`transitionReason` 字段比简单的 `retryCount++` 安全得多——它知道"为什么重试"，能识别"重复犯同一个错"。

### 1.2 工具错误返回 `ToolResult.error()`，不 throw（ch39）

**问题**：你现在的 `@Tool` 大概率是 `throw new RuntimeException(...)`。Agent 一旦调错就崩，无法自我修复。

**Claude Code 的解法**：工具错误是**输出**而非异常——构造 `ShellError` 对象（含 stdout/stderr/exitCode）作为 `tool_result` 返回给 LLM，让 LLM 看到 stderr 后自己决定下一步（重试？换工具？放弃？）。

**Java 项目怎么用**：

```java
@Tool(description = "执行 Shell 命令")
public ToolResult executeShell(@ToolParam("cmd") String cmd) {
    try {
        Process p = new ProcessBuilder("sh", "-c", cmd).start();
        // ... 执行
        return ToolResult.success(stdout, stderr);
    } catch (Exception e) {
        // ❌ 错误：throw new RuntimeException(e)
        // ✅ 正确：返回结构化错误
        return ToolResult.error(exitCode, stderr, "执行失败: " + e.getMessage());
    }
}
```

**这条不学，Agent 跑两步就崩**。

### 1.3 Anthropic 5 大 Workflow 模式（已在阶段 4 计划里）

详见 [`reference/选型与对比/09` §4](./11-企业级Java-AI架构选型真相.md)。5 种模式全部用 Spring AI 1.0 单框架实现：
- Prompt Chaining（串联）
- Parallelization（并行/分段/投票）
- Routing（路由分流）
- Orchestrator-Workers（编排-工人）
- Evaluator-Optimizer（评估-优化循环）

**关键认知**：**Workflow > Agent**。能用确定性 DAG 解决的，不要用自主 Agent。

### 1.4 Shell 工具安全：元字符拒绝 + 超时（ch16）

**Java 项目最易踩坑的地方**。让 Agent 执行 Shell = 把钥匙给一个会犯错的孩子。

**Claude Code 的解法**（关键 4 条）：
1. **绝不用正则匹配危险命令**——`r''m'' -rf` 能绕过 `rm -rf` 的正则。用 AST（tree-sitter-bash）或**显式元字符拒绝列表**
2. **三层超时**：默认 120s + 用户可调 + `run_in_background` 移到后台
3. **输出三段式**：超 100K 字符截断头部 + 超大输出落盘返回路径 + 非零退出码语义解释（`grep` 无匹配返回 1 不是错误）
4. **危险前缀降级**：`python/node/npx/bash/sh/eval/exec/sudo/curl/wget` 自动降级为"询问用户"

**Java 项目怎么用**：

```java
private static final Set<String> DANGEROUS_PREFIXES = Set.of(
    "python", "node", "npx", "bash", "sh", "eval", "exec", "sudo", "curl", "wget"
);
private static final Set<String> FORBIDDEN_METACHARS = Set.of(
    ";", "&&", "||", "$(", "`", ">", "<", "|"
);

public ToolResult executeShell(String cmd) {
    // 1. 元字符拒绝（绝不用正则）
    for (String c : FORBIDDEN_METACHARS) {
        if (cmd.contains(c)) return ToolResult.error("forbidden metachar: " + c);
    }
    // 2. 危险前缀降级
    String first = cmd.split("\\s+")[0];
    if (DANGEROUS_PREFIXES.contains(first)) {
        return ToolResult.needsApproval("dangerous command: " + first);
    }
    // 3. 超时 + 后台
    Process p = new ProcessBuilder("sh", "-c", cmd).start();
    if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return ToolResult.error("timeout after 120s");
    }
    // 4. 输出三段式（截断 + 落盘）
    return truncateOrPersist(p.getInputStream());
}
```

### 1.5 静态/动态边界：Prompt Cache 优化（ch10-11）

**问题**：Spring AI 默认每次请求重新构造 system prompt + tool 列表，**Prompt Cache 命中率低**——Anthropic Prompt Cache 命中是 10 倍成本差异。

**Claude Code 的解法**：把 system prompt 按 Section 切分，标记"静态部分"（跨组织缓存）和"动态部分"（每会话变）。**N 个运行时变量产生 2^N 种缓存变体**——所以任何依赖运行时状态的 Section 必须放在边界后。

**Java 项目怎么用**：
- system prompt 拆成 `基础指令 + 项目信息 + 用户身份 + 当前任务`
- 前缀部分（基础指令 + 工具说明）跨请求稳定，**用 Anthropic API 的 `cache_control` 标记**
- 后缀部分（用户身份、当前任务）每请求变化，不要打 cache 标记
- 工具列表按名字稳定排序，作为连续前缀

**这条省下的钱，比你优化 prompt 收益大得多**。

---

## 2. ⏳ 阶段 5 生产化时再做

### 2.1 分层错误 + 熔断（ch39 → Resilience4j）

- **工具级**：异常 → `ToolResult.error()`（已在 1.2 学）
- **查询级**：流式失败降级为同步（`onErrorResume` 切备用模型）
- **系统级**：`@CircuitBreaker` + `@FallbackMethod`（Resilience4j）

**特别要点**：**Fast Mode 降级保护 Prompt Cache**——短等待保持同模型重试（命中缓存），长等待切模型降级（放弃缓存但保命）。

### 2.2 五维成本追踪 + 收益递减检测（ch40）

- **五维**：inputTokens / outputTokens / **promptCacheWriteTokens** / **promptCacheReadTokens（1/10 价）** / webSearchRequests
- **会话级成本持久化**：`@PreDestroy` 写 Redis，`--resume` 时恢复
- **收益递减检测**：连续 3 次续跑每次 <500 token → 自动停止（防止 Agent 烧钱空转）

**Java 实现**：用 `EnumMap<Model, BigDecimal[]>` 建一张 `ModelCostTable`，每次 API 响应解析 `usage` 累计。Spring AI 无原生，需自建 `TokenBudgetGuard`。

### 2.3 上下文压缩：85% 阈值 + 断路器（ch12）

- **触发链**：警告（~85%）→ 自动压缩（~90%）→ 错误 → 阻塞（渐进式压力响应）
- **双路径**：(1) 用结构化记忆文件替代历史（零 LLM 调用）；(2) 调 LLM 生成 9 段标准化摘要
- **断路器**：连续失败 3 次停止（线上烧钱重灾区）
- **递归防护**：检查 `querySource === 'compact'` 直接返回 false——压缩本身调 LLM 不会再触发压缩
- **API 不变量保护**：压缩后回溯调整截断点，不拆散 `tool_use/tool_result` 配对

**Java 实现**：用 Resilience4j `CircuitBreaker` 包裹压缩逻辑；9 段摘要 prompt 用结构化模板（Primary Request / Files / Errors / Pending Tasks / Current Work...）。

### 2.4 权限四级模型（ch20-22）

- **Allow / Ask / Deny / Yolo** 四级
- **Deny 优先** + **安全检查不可绕过**：即使 bypassPermissions 模式，对 `.git/ / .env / 权限配置文件`的修改仍强制 ASK
- **保护权限配置文件 > 一切**：如果 Agent 能改权限文件，整个安全体系崩塌（自我提权）
- **多路竞速决策**：分类器 + Hook + 用户 + 远程审批并发，`CompletableFuture.anyOf`

**最小可用安全集**（企业项目至少要有）：
1. `.env / .git / application.yml / 权限配置`的 bypass-immune 检查
2. Deny 优先的权限管线（`ToolCallingAdvisor` 拦截）
3. 操作审计日志（所有 prompt 落库）

### 2.5 后台任务 + Cron Jitter（ch27，Java 强项）

- **任务表 + 数据库行锁 claimTask**（`SELECT ... FOR UPDATE`）或 Redisson 分布式锁
- **确定性 Jitter**：用任务 ID hash 算 offset，防多实例整点同时打 LLM API（Spring `@Scheduled` 不带 jitter，需自定义）
- **ShedLock** 防双重触发（成熟方案，原理一致）

### 2.6 可观测性分层（ch42 → Micrometer + OpenTelemetry）

- **必建 meter**：`genai.client.ttft`（首 token 延迟）/ `genai.tokens.cached` / `genai.fallback.count`
- **网关指纹检测**：识别 litellm / helicone / cloudflare-ai-gateway，区分 Anthropic 问题 vs 网关问题
- **PII 双层路由**：敏感字段进有 ACL 的大数据存储，非敏感进通用监控

---

## 3. 🚫 现在不要做（过早优化）

| 模式 | 章节 | 不做的原因 |
|------|------|----------|
| 多 Agent / Swarm 协调者 | ch24 | 先跑通单 Agent 再说，多 Agent 协调成本极高 |
| Worktree 并行工作流 | ch25 | Java 后端项目通常不操作代码仓库 |
| Teleport 远程协作 | ch26 | 用不上，会话自包含可序列化思想可借鉴但不实操 |
| Plan Mode 双 ChatClient | ch08 | 等 Workflow 模式熟练后，作为"架构约束 > 提示词引导"的进阶练习 |
| 工具并发安全的动态判定 | ch07 | 先把单工具跑通，并发判定是 `isConcurrencySafe(Input)` 装饰器，需要时再加 |
| 跨会话记忆三层架构 | ch13 | 阶段 5 之后，先把单会话做好 |
| 子 Agent 委派（AgentTool） | ch18 | 阶段 7 编排引擎才考虑 |
| 终端 UI（Ink/React） | ch31-34 | 你是 Web/服务端项目，不适用 |

---

## 4. Java 工程师特别要警惕的 3 个陷阱

### 4.1 不要把 LLM 当数据库

LLM 是无状态的、有概率出错的远程 RPC。**不要相信它的输出结构**——任何 LLM 返回的 JSON，都要做 schema 校验后再用。Spring AI 的 `BeanOutputConverter` 会做这层，但你要知道它在做什么。

### 4.2 不要用 `try-catch` 兜底 LLM 的语义错误

LLM 能返回语法正确但语义错误的答案（比如把"销售额"理解成"利润"）。`try-catch` 抓不到这种错。**只能靠评估集 + RAG + Evaluator-Optimizer 循环**。

### 4.3 不要用 Spring 的"约定优于配置"思维

Spring Boot 的自动配置能帮你快速起步，但**生产环境 AI 应用必须显式配置**：
- 显式声明 `ChatClient` 的 `defaultSystem` / `defaultAdvisors` / `defaultTools`
- 显式声明 `ChatMemory` 的窗口大小、存储后端
- 显式声明 `VectorStore` 的索引类型、维度、距离度量

不要依赖 Spring 的默认值——LLM 应用的默认值往往是错的。

---

## 5. Spring 生态原生方案速查表

| Claude Code 机制 | 章节 | Spring 生态等价方案 | 是否需自研 |
|---|---|---|---|
| 三层错误 + 熔断 | ch39 | Resilience4j `@Retryable` + `@CircuitBreaker` + `@FallbackMethod` | 否 |
| OAuth 多源认证 | ch41 | Spring Security OAuth2 Client + 多 `AuthenticationProvider` | 否 |
| Token 刷新跨进程协调 | ch41 | Redis 分布式锁 + Redis pub/sub | 否 |
| Feature Flag | ch38 | Spring Cloud Config + Caffeine `refreshAfterWrite` (SWR) | 否 |
| 可观测性分层 | ch42 | Micrometer meters + OTel spans + SLF4J MDC | 否 |
| 工具注册表 | ch14 | Spring AI `@Tool` + `ToolCallbackProvider` | 否 |
| 权限中间件 | ch20-22 | Spring Security `SecurityFilterChain` + AOP `@Around` | 部分（需自定义 Advisor） |
| 启动状态机偏序 | ch44 | Spring `SmartLifecycle` + `@DependsOn` | 否 |
| **Agent Loop** | ch05 | **无** | **是** |
| **上下文预算/压缩** | ch10-12 | **无**（`MessageWindowChatMemory` 太简单） | **是** |
| **五维成本追踪** | ch40 | **无** | **是** |
| **收益递减检测** | ch40 | **无** | **是** |
| **Prompt Cache 感知重试** | ch39 | **无** | **是** |
| **多 Agent Actor** | ch24 | Spring Cloud Stream + Virtual Thread / Akka | 部分 |

**标红的"需自研"5 项**，是 AI Agent 特有、Spring 生态尚未覆盖的——这是企业 Java AI 项目的**差异化竞争点**。

---

## 6. 落地路线（与本仓库学习计划对齐）

| 阶段 | 借鉴 Claude Code 的哪些点 | 文档位置 |
|------|------------------------|---------|
| **阶段 4（Week 5-6）** | Agent Loop / 工具错误规范 / 5 大 Workflow 模式 / Shell 安全 | 本篇 §1 |
| **阶段 5（Week 7-9）** | 分层错误 + 熔断 / 五维成本 / 压缩断路器 / 权限四级 / 后台任务 / 可观测性 | 本篇 §2 |
| **阶段 6（综合项目）** | 把阶段 4-5 的工具箱直接用 | - |
| **阶段 7（可选）** | 多 Agent 协调者模式（如果选 Alibaba Graph） | 本篇 §3 标注"暂不做"的部分 |

---

## 7. 自检清单

读完本节后，你应该能回答：

- [ ] 为什么"可靠性 = 边界管理，不是模型更聪明"？
- [ ] Agentic Loop 的 5 个终止条件是什么？为什么 `transitionReason` 比 `retryCount++` 安全？
- [ ] 为什么工具错误要返回 `ToolResult.error()` 而不是 throw 异常？
- [ ] Shell 工具为什么不能用正则匹配危险命令？正确的做法是什么？
- [ ] Prompt Cache 命中能省多少钱？静态/动态边界怎么划？
- [ ] 哪 5 项是 Spring AI 没有原生、必须自研的？

---

## 8. 相关文档

- [`11-企业级Java-AI架构选型真相.md`](./11-企业级Java-AI架构选型真相.md) —— "Workflow > Agent" 的企业实战证据
- [`10-SpringAI与LangChain4j分工模型.md`](./10-SpringAI与LangChain4j分工模型.md) —— 理论分工模型
- [`09-心智模型与决策树.md`](./09-心智模型与决策树.md) —— LLM = 远程 RPC 的心智模型
- [`02-Agent原理.md`](./02-Agent原理.md) —— Agent 循环的理论基础

---

## 9. 参考资料来源

- 原始扫描：`/Volumes/data/远端代码仓/VibeCoding/agent-docs/claudecode源码分析/` （45 章，约 80 万字符）
- Anthropic《Building Effective Agents》(2024-12-19) —— Workflow 模式金科玉律
- Spring AI 1.0 Reference Documentation —— Java 侧 API 对照
