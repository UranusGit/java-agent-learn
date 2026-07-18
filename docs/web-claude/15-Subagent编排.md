# 15 - Subagent 编排：并行加速与角色分工

> 本章是长程任务**提速**的核心机制。CC 源码 ch13/14 + 调研笔记 §2.6 的 Multi-Agent Delegation 模式。
> 完成后：100 个 feature 的长程任务从顺序 50 小时 → 并行 10 小时。

---

## 0. 为什么需要 Subagent

### 0.1 单 agent 模式的瓶颈

11/13 章的长程任务，是**顺序执行**：

```
session-1 → F001（30min）→ session-2 → F002（30min）→ ... → F100（30min）
总耗时：50 小时
```

问题：
1. **慢**：feature 之间互相独立却串行；
2. **上下文浪费**：每个 session 都重新装载项目背景；
3. **错误扩散**：一个 feature 卡住，后面全卡；
4. **无法专业化**：一个 agent 既要写前端、又要写后端、还要写测试。

### 0.2 Subagent 的核心思路

主 agent（**Orchestrator**）把任务**委派**给若干子 agent，子 agent 各自独立工作，结果汇总回主 agent。

```
Orchestrator
   ├─→ Coder-Frontend  ─→ F001, F003, F005（并行）
   ├─→ Coder-Backend   ─→ F002, F004, F006（并行）
   ├─→ Tester          ─→ 跑所有 feature 的测试
   └─→ Reviewer        ─→ 审查 Coder 的提交
```

理论上 N=5 并行下，100 feature 任务 10 小时完成。

### 0.3 但 subagent 不是银弹

并行带来新的工程问题：
1. **上下文隔离**：子 agent 不能看到主 agent 的全部历史（否则就失去并行的意义）；
2. **结果聚合**：N 个子 agent 的输出怎么合并；
3. **依赖管理**：F002 依赖 F001 的实现，并行时怎么办；
4. **错误传播**：一个子 agent 失败要重试还是 abort 整个任务；
5. **资源竞争**：5 个 agent 同时改 git，冲突怎么办；
6. **成本爆炸**：N 并行 = N 倍 token 成本；
7. **可观测性**：用户怎么看到 5 个 agent 各自的进度。

本章逐一解决。

---

## 1. 四种编排模式

### 1.1 模式 A：顺序委派（最简单）

```
Orchestrator → Subagent-1 → done → Subagent-2 → done → ...
```

适用：F002 严格依赖 F001，无法并行。
速度：和单 agent 一样，但**子 agent 上下文隔离** → 不爆主 agent 的 context。

### 1.2 模式 B：并行委派（fan-out / fan-in）

```
Orchestrator
   ├──→ Subagent-1 ─┐
   ├──→ Subagent-2 ─┤
   ├──→ Subagent-3 ─┼─→ Orchestrator（聚合）
   └──→ Subagent-4 ─┘
```

适用：feature 之间相互独立（如不同模块、不同页面）。
速度：瓶颈是最慢的那个子 agent。

### 1.3 模式 C：流水线（pipeline）

```
Coder → Tester → Reviewer → (commit)
```

适用：单个 feature 的多角色协作。
速度：每个 stage 一旦空闲就接下一个 feature，类似 CPU 流水线。

### 1.4 模式 D：DAG 调度（最通用）

```
       F001
       /  \
    F002  F003
      |    |
    F004  F005
       \  /
       F006
```

依赖图，调度器按拓扑序执行，无依赖关系的并行跑。
速度：理论最优。

**v1 实现顺序**：A → B → D → C。本章主要讲 B 和 D。

---

## 2. 数据模型

### 2.1 subagent 表

新增 `V6__subagents.sql`：

```sql
-- 本代码仅作学习材料参考
CREATE TABLE subagents (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,         -- 父 session
    parent_subagent_id UUID,          -- 多级嵌套（一般 2 级足够）
    role VARCHAR(64),                 -- coder / tester / reviewer / explorer / planner
    task_snapshot JSONB,              -- 委派时分配的任务描述
    status VARCHAR(32) NOT NULL,      -- pending/running/waiting/done/failed/cancelled
    worktree_branch VARCHAR(255),     -- 独立 git 分支
    child_session_id UUID,            -- 关联到子 session（持久化用）
    parent_tool_call_id VARCHAR(64),  -- 主 agent 哪个 tool_use 触发的
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    result_summary TEXT,
    error_msg TEXT,
    tokens_used BIGINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_subagents_session ON subagents(session_id);
CREATE INDEX idx_subagents_status ON subagents(status);
```

### 2.2 DAG 依赖

```sql
CREATE TABLE subagent_dependencies (
    subagent_id UUID NOT NULL,
    depends_on_subagent_id UUID NOT NULL,
    PRIMARY KEY (subagent_id, depends_on_subagent_id)
);
```

---

## 3. 委派工具：Delegate

新增工具 `Delegate`，主 agent 调用：

```java
// 本代码仅作学习材料参考
@Component
public class DelegateTool implements Tool {

    @Override public String name() { return "Delegate"; }
    @Override public Tool.Kind kind() { return Tool.Kind.DELEGATE; }

    @Override
    public String description() {
        return """
            把任务委派给子 agent 并行执行。
            
            使用场景：
              - 多个相互独立的功能要同时推进；
              - 需要专业角色（前端 / 后端 / 测试 / 审查）；
              - 长任务需要并行加速。
            
            注意：
              - 子 agent 之间不能直接通信，必须通过主 agent；
              - 委派后子 agent 异步执行，本工具立即返回 subagent_id；
              - 用 QuerySubagent 工具查看结果。
            """;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "role", Map.of("type", "string", "enum",
                    List.of("coder", "tester", "reviewer", "explorer", "planner", "custom")),
                "task", Map.of("type", "string", "description", "委派给子 agent 的具体任务描述"),
                "role_instructions", Map.of("type", "string", "description", "角色专属指令"),
                "depends_on", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "依赖的其他 subagent_id（DAG 调度）"),
                "max_tokens", Map.of("type", "integer", "default", 50000),
                "timeout_sec", Map.of("type", "integer", "default", 1800)
            ),
            "required", List.of("role", "task")
        );
    }

    @Override
    public CompletableFuture<ToolResult> apply(Map<String, Object> input, ToolContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String role = (String) input.get("role");
            String task = (String) input.get("task");
            int maxTokens = (int) input.getOrDefault("max_tokens", 50000);

            // 1. 创建子 agent 记录
            UUID subagentId = UUID.randomUUID();
            String branch = "subagent/" + role + "/" + subagentId;

            // 2. 创建 git 分支（基于父 worktree 当前 HEAD）
            gitOps.createBranch(ctx.worktreePath(), branch);

            // 3. 启动子 session（独立沙箱）
            UUID childSession = sessionLauncher.launchSubagent(
                ctx.sessionId(), subagentId, role, task, branch, maxTokens
            );

            // 4. 立即返回 subagent_id（异步执行）
            return ToolResult.text(String.format(
                "{\"subagent_id\": \"%s\", \"child_session_id\": \"%s\", \"status\": \"running\", \"branch\": \"%s\"}",
                subagentId, childSession, branch
            ));
        });
    }
}
```

### 3.1 配套工具：QuerySubagent / CancelSubagent / ListSubagents

```java
// QuerySubagent：查子 agent 进度
// CancelSubagent：取消子 agent
// ListSubagents：列当前 session 所有子 agent
```

---

## 4. 子 Agent 的运行时

### 4.1 上下文隔离

子 agent 启动时**不继承**主 agent 的 messages，只拿到：
- `role_instructions`（角色专属）；
- `task`（具体任务）；
- `parent_context_summary`（主 agent 给的简短摘要，如"我们正在做 todo app，已完成 F001 注册"）；
- 项目级 CLAUDE.md（共享背景）。

这是隔离的本质：**子 agent 看不到主 agent 的对话历史**，只能看到主 agent 显式注入的摘要。

### 4.2 独立沙箱

每个子 agent 用独立 worktree（git branch）：

```
主 worktree:        /tmp/webclaude-wt-main/         (main branch)
子 worktree:        /tmp/webclaude-wt-sub-{id}/     (subagent/... branch)
```

子 agent 改动不影响主 worktree，完成后通过 merge 合入。

### 4.3 子 Agent 的 system prompt

```
你是 {role} agent。任务：{task}

工作约束：
  - 你看到的项目背景：{parent_context_summary}
  - 你只能在分支 {branch} 上工作
  - 完成后必须 commit
  - 你的输出会汇总给主 agent，简洁清晰
  - 你不能直接通信其他子 agent

开始工作。
```

### 4.4 子 Agent 的 termination

子 agent 的 Agent Loop 终止条件：
1. `task` 完成（输出明确的"done" + summary）；
2. `max_tokens` 用完；
3. `timeout_sec` 到点；
4. 主 agent 显式 CancelSubagent；
5. 父 session 结束 → 级联取消。

---

## 5. 结果聚合

### 5.1 同步聚合（模式 A）

子 agent 完成后，主 agent 通过 `QuerySubagent` 拉取结果：

```java
// 主 agent 的下一轮 prompt 注入：
"子 agent {id} 已完成 {task}。
 结果：{summary}
 改动文件：{file_list}
 commit sha: {sha}
 你可以基于此继续。"
```

### 5.2 异步聚合（模式 B/D）

主 agent 委派 5 个子 agent 后**继续做其他事**（或 end_turn），子 agent 完成时主 agent 被**唤醒**：

```java
// 本代码仅作学习材料参考
// 通过事件订阅
subagentEventBus.subscribe(parentSessionId, event -> {
    if (event.type() == SubagentEvent.Type.DONE) {
        // 把子 agent 结果注入主 agent 的下一轮
        parentLoop.injectMessage(parentSessionId, event.toResultMessage());
    } else if (event.type() == SubagentEvent.Type.FAILED) {
        parentLoop.injectMessage(parentSessionId, event.toErrorMessage());
    }
});
```

### 5.3 冲突解决

并行子 agent 改同一文件 → merge 冲突。处理：

1. **预防**：分解任务时确保文件级隔离（orchestrator 责任）；
2. **检测**：子 agent 完成后 dry-run merge；
3. **解决**：
   - 自动 merge（无冲突）；
   - 冲突 → 启动 Reviewer agent 解决；
   - 严重冲突 → abort 该子 agent，主 agent 重新委派。

---

## 6. DAG 调度器

### 6.1 拓扑排序

```java
// 本代码仅作学习材料参考
@Component
public class SubagentScheduler {

    public List<SubagentEntity> pickReady(List<SubagentEntity> all) {
        Map<UUID, SubagentEntity> byId = all.stream()
            .collect(Collectors.toMap(SubagentEntity::getId, s -> s));

        return all.stream()
            .filter(s -> s.status().equals("pending"))
            .filter(s -> dependenciesSatisfied(s, byId))
            .sorted(Comparator.comparingInt(SubagentEntity::priority))
            .limit(availableSlots())
            .toList();
    }

    private boolean dependenciesSatisfied(SubagentEntity s, Map<UUID, SubagentEntity> byId) {
        return s.dependencies().stream()
            .allMatch(depId -> {
                SubagentEntity dep = byId.get(depId);
                return dep != null && dep.status().equals("done");
            });
    }

    private int availableSlots() {
        // 根据租户配额、系统负载动态算
        return Math.max(0, maxConcurrency - runningCount());
    }
}
```

### 6.2 并发上限

- 全局：100 个并发子 agent；
- 单租户：根据 plan（free 5、pro 20、enterprise 100）；
- 单 session：根据父 session 的剩余预算。

### 6.3 优先级

- 用户标记的高优 task 先跑；
- 卡住的 DAG 路径优先（避免链路上某一点延迟扩散）；
- 长时间排队的 task 提优先级（防饥饿）。

---

## 7. 错误传播

| 子 agent 失败类型 | 处理 |
|------------------|------|
| 超时 | retry 1 次，仍失败 → 标 failed，主 agent 决定重派 / 跳过 / abort |
| max_tokens 用完 | 不 retry，直接 failed（task 太大） |
| 工具调用错误 | 子 agent 自己 retry（标准 Agent Loop 行为） |
| 主 agent abort | 级联 cancel 所有子 agent |
| 沙箱崩溃 | 自动迁移到新沙箱 retry |

---

## 8. 与 harness 多角色的整合（13 章）

13 章 Layer 5 定义了 5 角色（Planner / Coder / Tester / Reviewer / Cleaner）。本章是它们的**运行时实现**：

| harness 角色 | subagent role | 工具调用 |
|--------------|---------------|----------|
| Planner | orchestrator | 主 agent 自己 |
| Coder | coder | Delegate(role=coder, task=...) |
| Tester | tester | Delegate(role=tester, task=...) |
| Reviewer | reviewer | Delegate(role=reviewer, task=...) |
| Cleaner | cleaner | Delegate(role=cleaner, task=...) |

13 章的"消息总线"（`.harness/messages/`）= 本章的 `parent ↔ child` 注入机制。

---

## 9. 可观测性（接 17 章）

每个 subagent 都推送事件到主 session 的事件流：

```json
{"type": "subagent_started", "subagentId": "...", "role": "coder", "task": "..."}
{"type": "subagent_progress", "subagentId": "...", "currentStep": "..."}
{"type": "subagent_completed", "subagentId": "...", "summary": "...", "commits": [...]}
{"type": "subagent_failed", "subagentId": "...", "reason": "..."}
```

前端展示（详见 17 章）：subagent 拓扑图 + 每个子 agent 折叠的活动流。

---

## 10. 前端：子 Agent 视图

### 10.1 拓扑图

```tsx
// 本代码仅作学习材料参考
function SubagentTopology({ subagents }: { subagents: Subagent[] }) {
    return (
        <div className="topology">
            <div className="node orchestrator">主 Agent</div>
            <div className="edges">
                {subagents.map(s => (
                    <div key={s.id} className={`edge ${s.status}`}>
                        → [{s.role}] {s.task.slice(0, 30)}... ({s.status})
                    </div>
                ))}
            </div>
        </div>
    );
}
```

### 10.2 单个子 agent 详情

点拓扑图节点 → 展开该子 agent 的活动流（与主 agent 同样的事件流 UI）。

---

## 11. 加速效果评估

### 11.1 100 feature 任务示例

| 模式 | 并发 | 耗时 | 成本 |
|------|------|------|------|
| 单 agent 顺序 | 1 | 50 小时 | 1× |
| 模式 A（顺序委派）| 1 | 50 小时 | 1.1×（隔离开销）|
| 模式 B（并行 fan-out）| 5 | 10 小时 | 1.2×（聚合开销）|
| 模式 D（DAG）| 5 | 8 小时 | 1.15×（最优）|
| 模式 D（DAG）| 10 | 5 小时 | 1.3× |

注意：**不是线性加速**。瓶颈在：
- 串行依赖链（最长路径决定下限）；
- 共享资源竞争（git lock、模型 rate limit）；
- 聚合开销。

### 11.2 加速极限（Amdahl 定律）

如果 10% 的 feature 必须串行（共享文件、强依赖），N=10 并行实际加速 5× 左右。

---

## 12. 配额与成本

每个 subagent 占用：
- 1 个沙箱（容器）；
- 独立的 LLM 配额；
- 独立 worktree（磁盘）。

租户配额扩展（接 10 章的 ai-serving）：

```sql
ALTER TABLE tenant_quotas ADD COLUMN max_concurrent_subagents INT DEFAULT 5;
ALTER TABLE tenant_quotas ADD COLUMN max_subagents_per_session INT DEFAULT 20;
```

---

## 13. 安全

- 子 agent 沙箱独立，不能访问主 agent 的 secrets；
- 子 agent 的权限规则**继承**主 session，但**收紧**（默认 deny 更多）；
- 子 agent 不能创建新的子 agent（防递归爆炸），v2 可放开到 2 级。

---

## 14. 测试

### 14.1 单测

- DAG 调度器拓扑排序正确；
- 并发上限生效；
- 错误传播路径。

### 14.2 集成测试

```java
// 本代码仅作学习材料参考
@Test
void parallelDelegationCompletesFasterThanSequential() {
    long seq = time(() -> runFeaturesSequentially(5));
    long par = time(() -> runFeaturesInParallel(5, 5));
    assertThat(par).isLessThan(seq / 3);
}
```

### 14.3 故障注入

- 杀一个子 agent 的沙箱 → 主 agent 应收到 failed 事件，可重派；
- git lock 模拟冲突 → 自动解决路径触发。

---

## 15. 与已有章节的关系

| 章节 | 关系 |
|------|------|
| 04-Agent-Loop | 子 agent 用相同的 Loop |
| 06-沙箱接入 | 子 agent 用独立 worktree |
| 07-Session持久化 | 子 session 独立持久化 |
| 11-长程任务 | harness 任务可拆给多 subagent 并行 |
| 13-Harness工程 | 5 角色映射 |
| 17-全链路可观测 | subagent 事件并入主流 |
| 18-错误恢复 | 子 agent 失败的重试策略 |

---

## 16. 本章产出

```
后端：
  ✅ subagents 表 + 依赖表
  ✅ Delegate / QuerySubagent / CancelSubagent / ListSubagents 工具
  ✅ SubagentScheduler（DAG 拓扑）
  ✅ 子 agent 独立 worktree（git branch）
  ✅ 结果聚合（同步 + 异步）
  ✅ 冲突检测与解决
  ✅ 错误传播

前端：
  ✅ 拓扑图视图
  ✅ 子 agent 活动流详情
```

## 17. v2 路线

- 2 级嵌套（子 agent 再派子 agent）；
- 跨 session subagent（与长程任务结合）；
- 子 agent 池化（pre-warmed）；
- 自动任务分解（Planner agent 自动拆 feature → DAG）。

---

## 18. 下一步

进入 [16-MCP 协议集成](./16-MCP协议集成.md)，让 Tool Registry 能接入外部 MCP server 生态。
