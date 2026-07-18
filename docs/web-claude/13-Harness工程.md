# 13 - Harness 工程：长程任务的工程脚手架

> 本章把 11 章扁平化的"长程任务"还原成它本来的样子：**Harness 工程**。
> Harness 不是一个 feature，而是一层独立工程，它定义了"如何让 Agent 在跨多个 session 的情况下仍然能可靠完成长程任务"。
> 依据：Anthropic *Effective Harnesses for Long-Running Agents* + 调研笔记 §3.3。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §1 | 11 章跑通但靠 Agent 自觉——它会作弊、跨 feature、谎报完成 |
| ② 五层模型 | §2–§6 | Initializer / Scaffold / Guardrails / Protocol / Multi-Agent |
| ③ 验证 | §9 | 用五层组件跑一遍 todo app，对照 11 章的差异 |
| ④ 对照 | §0（章末）| 11 章扁平化 vs 13 章 Harness 化的可靠性差距 |
| ⑤ 避坑 | §11 | 测试作弊 / 跨 feature / progress 漂移 / agent 自评 |

> **本文是"工程设计文档"型**——本章不给完整代码（Harness 是个体系，代码分散在 11 / 14 / 15 / 18 / 20 等章），而是给"五层模型 + 每层的设计原则 + 验收清单"。读完知道每层管什么、怎么验收就够了。

---

## 1. 为什么单独拉一章

11 章实现的是 "feature_list + Cron + 自动续跑" 这条**最具体的执行路径**。但 Anthropic 的 Harness 范式抽象层级更高，它包含五个相互独立、可组合的工程组件：

| 组件 | 11 章覆盖情况 | 本章补完 |
|------|--------------|---------|
| Initializer Agent | ✓ | — |
| Scaffold Artifacts（feature_list / progress / init.sh）| 部分 | ✓ |
| Guardrails（强约束、禁删测试）| 部分 | ✓ |
| Protocol（每会话起步流程）| 部分 | ✓ |
| Multi-Agent 扩展（QA / 测试 / 清理 agent）| ✗ | ✓ |

**核心论断（Harness 文章原文）**：

> "Long-running agents must work in discrete sessions, each new session starts with no memory."

→ 工程含义：Harness = 把"无记忆的多个 session"用外部状态串起来，**并保证串联的可靠性**。

如果只做 Cron + feature_list（11 章的样子），agent 仍然会失败在以下地方：
- 删测试作弊；
- 一个 session 偷偷改多个 feature；
- 跨 session 进度对不上；
- 测试没真过就标 passes=true；
- 没人验收，agent 跑完就报"完成"。

Harness 工程就是为了堵这些漏洞。

---

## 2. Harness 工程的五层模型

```
┌─────────────────────────────────────────────────────────┐
│  Layer 5: Multi-Agent Topology                          │
│    Planner / Coder / Tester / Reviewer / Cleaner        │
├─────────────────────────────────────────────────────────┤
│  Layer 4: Session Protocol                              │
│    起步流程 / 终止流程 / 增量推进规则                     │
├─────────────────────────────────────────────────────────┤
│  Layer 3: Guardrails（强约束）                           │
│    禁删测试 / 单 session 单 feature / 必须验证            │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Scaffold Artifacts                            │
│    feature_list.json / progress.txt / init.sh / git     │
├─────────────────────────────────────────────────────────┤
│  Layer 1: Initializer Agent                             │
│    首次运行的脚手架生成器                                 │
└─────────────────────────────────────────────────────────┘
```

每一层都可独立替换、可测试、可观测。本章逐层展开。

---

## 3. Layer 1：Initializer Agent

### 2.1 职责

首次运行时：
1. 把用户的自然语言需求**展开**为可执行的功能清单；
2. 生成 scaffold artifacts（feature_list / progress / init.sh）；
3. 把空骨架放进 git，第一个 commit 让后续 session 有基线。

### 2.2 与普通 Coding Agent 的关键差异

| 维度 | Initializer | Coding Agent |
|------|-------------|--------------|
| 运行次数 | 1 次 | N 次 |
| 目标 | 生成"任务规约" | 按"任务规约"执行 |
| 输出 | feature_list / progress / init.sh / scaffold | 单个 feature 的实现 + 测试 + commit |
| Prompt 长度 | 较长（包含生成规约的规则）| 较短（按规约执行） |
| 验收 | feature_list 合法性 + scaffold 可启动 | feature 测试通过 |

### 2.3 Initializer 的输出契约

```
workspace/
├── .harness/                       ← harness 专属元数据目录
│   ├── feature_list.json           ← 任务规约（200+ features）
│   ├── progress.txt                ← 进度日志（append-only）
│   ├── init.sh                     ← 基线启动脚本
│   ├── constraints.json            ← 强约束清单（不可删测试、不许作弊等）
│   └── meta.json                   ← Initializer 元数据（版本、生成时间等）
├── src/                            ← scaffold 代码
├── tests/                          ← scaffold 测试
└── README.md
```

`.harness/` 目录的存在让 Harness 与普通 workspace 区分开 —— 任何 agent 进入 workspace 都能识别这是"长程任务"模式。

---

## 4. Layer 2：Scaffold Artifacts

### 3.1 feature_list.json（核心）

**字段升级**（比 11 章更严格）：

```json
{
  "task_id": "uuid",
  "title": "todo app",
  "version": "1.0",
  "generated_by": "initializer",
  "generated_at": "2026-07-18T10:00:00Z",
  "features": [
    {
      "id": "F001",
      "title": "用户注册",
      "priority": 1,
      "category": "auth",
      "passes": false,
      "test_cmd": "pytest tests/test_register.py -v",
      "test_type": "unit",
      "acceptance_criteria": [
        "邮箱格式校验",
        "密码 bcrypt 存储",
        "重复注册返回 409"
      ],
      "dependencies": [],
      "estimate_tokens": 5000,
      "notes": "..."
    }
  ],
  "meta": {
    "strong_instructions": [
      "禁止删除或修改任何 tests/ 下的文件",
      "禁止把测试断言改成空操作或 assert True",
      "禁止一次 session 修改多个 feature",
      "测试必须真实通过才能把 passes 改为 true",
      "禁止直接编辑 feature_list.json 改 passes，必须通过 harness API"
    ]
  }
}
```

**关键设计**：
- `test_type`（unit / integration / e2e / browser）—— 让调度器知道该用哪种沙箱执行；
- `acceptance_criteria` —— 给 QA agent 验收用；
- `dependencies` —— 形成 DAG，调度器据此选可执行 feature；
- `estimate_tokens` —— 给成本预算用。

### 3.2 progress.txt（append-only）

```text
# DO NOT EDIT HISTORY. APPEND ONLY.
[2026-07-18T10:00:00Z][initializer] task initialized, 247 features
[2026-07-18T10:30:00Z][session-uuid-1][F001] start
[2026-07-18T10:45:00Z][session-uuid-1][F001] test passed, committed as abc1234
[2026-07-18T11:00:00Z][session-uuid-2][F002] start
...
```

**为什么必须 append-only**：
- agent 不能"撤销"自己说过的话；
- 任何 session 都能看到完整历史，不依赖 LLM 记忆；
- 出问题后能审计。

实现：`O_APPEND` + 文件锁。

### 3.3 init.sh（基线脚本）

```bash
#!/bin/bash
# init.sh - 启动开发环境 + 跑基线测试
set -e

cd /workspace

# 启动依赖
docker compose up -d db redis 2>/dev/null || true

# 安装依赖
pnpm install --frozen-lockfile

# 启动 dev server（后台）
pnpm dev &
DEV_PID=$!
sleep 3

# 跑基线 smoke 测试
pnpm test:smoke
SMOKE_EXIT=$?

# 清理
kill $DEV_PID 2>/dev/null || true

exit $SMOKE_EXIT
```

每个 Coding Agent session 开头跑这个，确认基线 OK 才开始做新 feature。如果 init.sh 失败 → 立即终止 session，避免在坏基线上加东西。

### 3.4 constraints.json（强约束的机器可读版）

```json
{
  "immutable_paths": ["tests/**", ".harness/**", "package-lock.json", "pnpm-lock.yaml"],
  "max_features_per_session": 1,
  "required_test_types": ["unit"],
  "min_acceptance_criteria_hits": 1,
  "forbidden_actions": [
    "edit_feature_list_directly",
    "skip_tests",
    "merge_multiple_features"
  ]
}
```

这是 Guardrails 层（Layer 3）的输入。

---

## 5. Layer 3：Guardrails（强约束）

### 4.1 为什么需要

Harness 文章里反复强调的两种**致命失败模式**：

| 失败模式 | 没有护栏的表现 | 加护栏后 |
|---------|--------------|---------|
| 一次性做完跑爆上下文 | 跨 session 进度对不上 | 单 session 单 feature |
| 过早宣布完成 | 测试没过就报 done | 必须真测通过 |
| 作弊 | 改断言、删测试、把 passes 改成 true | immutable_paths + 测试实际执行 |
| 偷改规约 | 直接编辑 feature_list | harness API 才能改 |

### 4.2 实现：HarnessGuard

新建 `src/main/java/org/demo02/webclaude/harness/HarnessGuard.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.harness;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.List;

@Component
public class HarnessGuard {

    private final ConstraintsLoader loader;

    /**
     * 在 Write/Edit 工具执行前调用。
     * 返回 null 表示放行，返回字符串表示拒绝原因。
     */
    public String checkWrite(Path workspaceRoot, String relPath, Constraints c) {
        Path full = workspaceRoot.resolve(relPath).normalize();
        String rel = workspaceRoot.relativize(full).toString();

        for (String glob : c.immutablePaths()) {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            if (m.matches(Path.of(rel))) {
                return "immutable path violated: " + rel + " (matches " + glob + ")";
            }
        }
        return null;
    }

    /**
     * 在 Bash 工具执行前调用。
     * 拦截危险命令模式。
     */
    public String checkBash(String command, Constraints c) {
        String low = command.toLowerCase();
        if (low.contains("git") && (low.contains("reset --hard") || low.contains("push --force"))) {
            return "forbidden git action";
        }
        if (low.matches(".*rm\\s+-rf\\s+(tests|\\.harness).*")) {
            return "forbidden destructive rm";
        }
        if (low.contains("feature_list.json") && low.matches(".*echo.*>.*|.*tee.*")) {
            return "must use harness API to modify feature_list";
        }
        return null;
    }

    /**
     * 在 session 结束前调用。
     * 检查本 session 是否符合"单 feature 单 session"约束。
     */
    public String checkSessionBoundary(SessionLog log, Constraints c) {
        List<String> touched = log.featuresTouched();
        if (touched.size() > c.maxFeaturesPerSession()) {
            return "touched " + touched.size() + " features in one session, max is " + c.maxFeaturesPerSession();
        }
        return null;
    }
}
```

### 4.3 接入 PermissionMiddleware

`Write` / `Edit` 工具调用前先过 `HarnessGuard.checkWrite`，命中约束直接 DENY。这是护栏的"硬执行"层。

### 4.4 测试真的跑了吗：TestVerifier

防止 agent 用 `assert True` 偷懒：

```java
// 本代码仅作学习材料参考
@Component
public class TestVerifier {

    public VerifyResult verify(String testCmd, Path workspace) {
        // 1. 真正执行 test_cmd
        ExecResult r = sandbox.exec(sessionId, new String[]{"bash", "-c", testCmd}, 120);
        if (r.exitCode() != 0) {
            return VerifyResult.fail("test exit code = " + r.exitCode());
        }

        // 2. 检查输出里是否有"假通过"特征
        String out = r.stdout().toLowerCase();
        if (out.contains("0 tests") || out.contains("no tests ran") || out.contains("0 passed")) {
            return VerifyResult.fail("no tests actually ran");
        }
        if (out.contains("skipped: 100%") || out.contains("100% skipped")) {
            return VerifyResult.fail("all tests skipped");
        }

        // 3. 必须有 "N passed" 且 N > 0
        if (!out.matches(".*\\d+\\s+passed.*")) {
            return VerifyResult.fail("no 'N passed' in output");
        }

        return VerifyResult.ok();
    }
}
```

只有 TestVerifier.ok() 才允许 harness API 把 feature 的 passes 改成 true。

---

## 6. Layer 4：Session Protocol（协议）

### 5.1 起步流程（Coding Agent 每个 session 开头必须做）

```
1. pwd
2. git status                # 必须是 clean
3. git log --oneline -20     # 了解前序进展
4. cat .harness/progress.txt | tail -50
5. jq '.features[] | select(.passes == false) | {id, priority}' .harness/feature_list.json | sort by priority
6. 选 priority 最高且 dependencies 全部已 passes 的 feature
7. bash init.sh              # 必须返回 0
8. 开始实现
```

每一步失败都要在 progress.txt 追加日志并 abort session，**不允许跳过**。

### 5.2 终止流程（结束 session 前）

```
1. 跑 feature.test_cmd
2. TestVerifier.verify()    # 必须返回 ok
3. Harness API markPasses(feature_id)
4. git add -A
5. git commit -m "feat(F0XX): ..."
6. progress.txt 追加 "[完成][F0XX] summary"
7. 写 session summary 给下一次 session 看
8. session 状态 → end_turn
```

### 5.3 协议状态机

```
session.created
  ↓
protocol.booting
  ↓ (pwd, git status, log, progress, init.sh)
protocol.ready
  ↓
protocol.working
  ↓ (实现 + 测试)
protocol.verifying
  ↓ TestVerifier
protocol.committing
  ↓
session.end_turn
```

每一步都有 timeout（booting 5 分钟、working 30 分钟、verifying 5 分钟）。超时 → abort。

---

## 7. Layer 5：Multi-Agent Topology

### 6.1 单 agent 模式（11 章做法，harness 文章里也提到了它的局限）

```
用户提需求 → Initializer → 循环（Coding Agent session × N）→ 完成
```

只有 1 个角色（除 Initializer 外），简单但容易"自我安慰"完成。

### 6.2 三角色模式（harness 文章 v2 推荐）

```
                    ┌──────────────────┐
                    │  Planner Agent   │  （读需求，维护 feature_list）
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              ↓              ↓              ↓
       ┌────────────┐ ┌────────────┐ ┌────────────┐
       │ Coder Agent│ │ Tester Agent│ │ Reviewer   │
       │  实现       │ │  自动化测试 │ │  验收 + Lint │
       └────────────┘ └────────────┘ └────────────┘
```

**Planner Agent 职责**：
- 维护 feature_list（依赖、优先级、动态调整）；
- 选下一个该做的 feature；
- 接收 Reviewer 的反馈，决定是否返工。

**Tester Agent 职责**：
- 用 Playwright 做 e2e 测试；
- 生成新的测试用例覆盖 acceptance_criteria；
- 跑回归测试。

**Reviewer Agent 职责**：
- 审查 Coder 的 diff；
- 检查是否破坏 immutable_paths；
- 给出 accept / reject 决定。

### 6.3 五角色模式（v3 完整版）

```
       Planner
       ↓ ↑
   ┌───┴───┐
   ↓       ↑
 Coder ← Cleaner
   ↓
 Tester
   ↓
 Reviewer
```

新增 **Cleaner Agent**：在 Coder 提交后跑 lint/format/refactor，保持代码质量。

### 6.4 角色间通信协议

通过 `.harness/messages/` 目录异步通信：

```
.harness/messages/
├── planner.outbox.jsonl     # Planner → 所有
├── coder.inbox.jsonl
├── tester.inbox.jsonl
├── reviewer.outbox.jsonl    # Reviewer → Planner / Coder
└── ...
```

每个角色 session 启动时读自己的 inbox，结束时写其他人的 inbox。消息格式：

```json
{
  "from": "coder",
  "to": "reviewer",
  "type": "review_request",
  "feature_id": "F012",
  "commit_sha": "abc1234",
  "diff_summary": "added register.py + test",
  "at": "2026-07-18T11:00:00Z"
}
```

---

## 8. 与 11 章的关系

11 章 = 本章 **Layer 1 + Layer 2 + 部分 Layer 4** 的最简实现。

要升级到完整 Harness：

| 改造点 | 章节 | 难度 |
|--------|------|------|
| 加 `.harness/` 目录结构 | 本章 §2.3 | 简单 |
| 升级 feature_list schema | 本章 §3.1 | 简单 |
| 加 constraints.json + HarnessGuard | 本章 §4 | 中等 |
| 加 TestVerifier | 本章 §4.4 | 中等 |
| 协议状态机 + 超时 | 本章 §5 | 中等 |
| Planner / Tester / Reviewer 多 agent | 本章 §6 | 大 |

建议路线：
1. **v1.2**：加 constraints + TestVerifier + 协议状态机（本章 §3-5）→ 单 agent 但带护栏；
2. **v1.3**：加 Tester Agent（自动化 e2e 覆盖）；
3. **v1.4**：加 Reviewer Agent；
4. **v2.0**：完整 5 角色 + Planner 维护 feature_list 动态调整。

---

## 9. 设计文档与代码的关系

| 文档 | 实现层 |
|------|--------|
| `00-调研笔记.md` §3.3 | Harness 范式的来源 |
| `01-项目设计.md` | Harness 作为独立模块（需回补）|
| `11-长程任务.md` | Harness 最简实现（Layer 1-2 部分）|
| **`13-Harness工程.md`**（本文档）| Harness 完整模型 |

---

## 10. 验证：Harness 的可观测性

### 9.1 关键指标

```
harness_task_completion_rate          # 任务最终完成率
harness_feature_throughput            # 单位时间完成 feature 数
harness_session_abort_rate            # session 中途 abort 比例
harness_guard_violation_total{type}   # 各种 guard 命中次数
harness_test_fake_pass_caught         # TestVerifier 抓到的"假通过"次数
harness_protocol_phase_duration{phase}  # 各协议阶段耗时
harness_multiagent_message_lag        # 多 agent 模式下消息处理延迟
```

### 9.2 审计

- 所有 `passes` 字段变更必须留审计日志（who / when / by which agent / with what test result）；
- immutable_paths 的访问尝试（即使被拒）也要记录；
- 每个 session 的 protocol 状态机阶段全部进 trace。

---

## 11. 避坑：Harness 工程常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| Agent 删测试作弊 | feature 看似过实则没过 | Layer 3 TestVerifier（独立跑测试，不信 agent）|
| 一个 session 跨 feature | feature 间互相污染 | Layer 4 Protocol：每 session 显式宣告当前 feature |
| progress.txt 漂移 | 多 session 改写不一致 | 单 writer + 乐观锁 / append-only event log |
| Agent 自评"完成" | 没人验收就上报 done | Layer 5 Reviewer agent + 人工 gate |
| Guardrail 太严 | Agent 动不了 | 区分 hard（不可破）/ soft（warning）|
| Multi-agent 抢同一文件 | 互相覆盖 | 每子 agent 独占 worktree |
| Initializer 拆错粒度 | feature 互相依赖，没法并行 | Layer 1 加依赖分析 |
| Protocol 太僵化 | Agent 状态机死循环 | 加 fallback 状态 + max retry |
| Scaffold 文件结构变 | Coding agent 误认旧结构 | Layer 2 用 schema 校验 |
| 测试假阳性 | mock 了真实依赖 | Layer 3 强制 integration test |

## 12. 本章产出

```
概念：
  ✅ Harness 五层模型
  ✅ 与 11 章的关系明确
  ✅ Multi-Agent Topology 三档（单 / 三 / 五）

待实现的代码骨架：
  ✅ HarnessGuard（写/执行/session 边界三层校验）
  ✅ TestVerifier（防假通过）
  ✅ Constraints 加载器
  ✅ Protocol 状态机骨架
  ✅ 多 agent 消息总线
```

## 13. 下一步

- 若已完成 11 章 → 回去补 §3-5 的护栏与协议状态机；
- 若刚起步 → 先把 02-11 章跑通，再回头加 harness 完整模型；
- 多 agent 拓扑建议在 v1.4+，需要先有 13 章《多 Agent 协作》基础。
