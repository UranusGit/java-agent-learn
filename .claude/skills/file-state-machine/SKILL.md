---
name: file-state-machine
description: |
  文件状态机管理 Skill。通过磁盘扫描（非对话记忆）推断当前文件模式，
  在 CLAUDE.md / PLAN.md 的草稿（Draft）与正式（Production）之间进行
  互斥锁定、合法性校验、原子定稿、滚动备份与逆向回滚。
  当用户提到"编辑草稿"、"修改计划"、"定稿"、"发布"、"应用草稿"、
  "放弃草稿"、"重置草稿"、"回滚"、"当前什么模式"等操作时触发。
allowed-tools: Read, Write, Edit, Glob, Bash
---

# 文件状态机管理 Skill

## 0. 角色与边界

你是一个 **无状态文件状态机控制器**。每次被调用时：

1. **禁止依赖对话记忆** — 必须通过 `Glob` + `Read` 扫描磁盘实际文件，动态计算当前状态。
2. **草稿互斥锁定** — 处于草稿模式时，`Edit`/`Write` 工具**只能**作用于 `*.draft.md` 文件。若用户要求直接修改 `CLAUDE.md` 或 `PLAN.md`，必须礼貌拒绝并引导其修改对应的 Draft。
3. **安全底线** — 覆盖正式文件前必须通过结构化合法性校验，校验失败则中止。

---

## 1. 文件常量定义

| 变量名 | 文件名 | 用途 |
|---|---|---|
| `PRODUCTION_MAIN` | `CLAUDE.md` | 正式指令文件 |
| `PRODUCTION_PLAN` | `PLAN.md` | 正式计划文件 |
| `DRAFT_MAIN` | `CLAUDE.draft.md` | 草稿指令文件 |
| `DRAFT_PLAN` | `PLAN.draft.md` | 草稿计划文件 |
| `BACKUP_MAIN` | `CLAUDE.bak.md` | 滚动备份（仅保留一份） |

所有文件均相对于**项目根目录**（即 `.claude/skills/` 的上两级）。

---

## 2. 状态推断逻辑（每次调用必执行）

**入口函数 `entry()`** — 每次 Skill 被触发时，第一步必须执行以下扫描：

```
步骤 1: 用 Glob 扫描项目根目录下是否存在以下文件：
        CLAUDE.md, PLAN.md, CLAUDE.draft.md, PLAN.draft.md, CLAUDE.bak.md

步骤 2: 根据扫描结果判定模式（多级判定法）：

  ┌─ DRAFT_MAIN 存在 OR DRAFT_PLAN 存在？
  │   ├─ YES → 判定为「草稿模式」
  │   │         └─ 仅存在一个 Draft 文件？
  │   │             ├─ YES → 子模式：「脏草稿模式」（需隐式补全）
  │   │             └─ NO  → 子模式：「完整草稿模式」
  │   └─ NO
  │       ├─ PRODUCTION_MAIN 存在 OR PRODUCTION_PLAN 存在？
  │       │   ├─ YES → 判定为「使用模式」
  │       │   └─ NO  → 判定为「空仓模式」
```

**判定汇总表：**

| 模式 | DRAFT_MAIN | DRAFT_PLAN | PRODUCTION_MAIN | PRODUCTION_PLAN |
|---|---|---|---|---|
| 空仓模式 | ✗ | ✗ | ✗ | ✗ |
| 使用模式 | ✗ | ✗ | ≥1 存在 | ≥1 存在 |
| 脏草稿模式 | 仅其一存在 | | 任意 | |
| 完整草稿模式 | ✓ | ✓ | 任意 | 任意 |

---

## 3. 闭环执行逻辑

### 模式 A：定稿 / 发布（Publish）

**触发词**：`定稿`、`发布`、`应用草稿`、`同步`

**执行步骤**：

1. **存在性检查**：用 Glob 确认 `DRAFT_MAIN` 和 `DRAFT_PLAN` 是否存在。

2. **自动补全（破解单草案死锁）**：
   - 若 `DRAFT_MAIN` 存在但 `DRAFT_PLAN` 缺失：
     - 基于 `DRAFT_MAIN` 的内容上下文生成最小化 `DRAFT_PLAN`：
       ```markdown
       # Plan

       > 基于 CLAUDE.draft.md 自动生成

       - [ ] 待拆解
       ```
     - 若 `DRAFT_MAIN` 中可识别出功能模块，则将其拆解为具体任务项。
   - 若 `DRAFT_PLAN` 存在但 `DRAFT_MAIN` 缺失：
     - 从 `PRODUCTION_MAIN` 复制内容作为 `DRAFT_MAIN` 起点。
     - 若 `PRODUCTION_MAIN` 也不存在，生成标准模板。
   - 告知用户：「⚠️ 检测到脏草稿，已自动补全缺失的 [PLAN/CLAUDE] 草稿，请确认后继续。」

3. **合法性校验（结构化）**：

   对 `DRAFT_MAIN`（即将成为 `CLAUDE.md`）：
   - ✅ 检查是否以 YAML Frontmatter 开头（首行为 `---` 且存在结束的 `---`）。
   - ✅ 检查是否包含至少一个一级标题（`# `）。
   - 失败时记录：`校验失败：CLAUDE.draft.md 第 N 行 — 缺少 [YAML Frontmatter / 一级标题]`。

   对 `DRAFT_PLAN`（即将成为 `PLAN.md`）：
   - ✅ 检查是否包含 YAML Frontmatter（同上）。
   - ✅ 检查是否包含任务拆解结构，匹配以下任一模式：
     - `- [ ]` 或 `- [x]`（Markdown checkbox）
     - `1.` `2.` 等（有序列表）
     - `## Step` 或 `### Step`（分步骤标题）
     - 正则：`[-\*\d]\.?\s.*` 或 `\[ \]`
   - 失败时记录：`校验失败：PLAN.draft.md 第 N 行 — 缺少任务拆解结构`。

   **校验失败处理**：
   - 高亮出错的文件名、行号及错误类型。
   - **中止定稿**，输出错误摘要并给出修复建议。
   - 不执行任何写操作。

4. **滚动备份**：
   - 若 `PRODUCTION_MAIN`（`CLAUDE.md`）存在，用 Bash 将其复制为 `BACKUP_MAIN`（`CLAUDE.bak.md`），覆盖旧备份。
   - 命令：`cp CLAUDE.md CLAUDE.bak.md`（在项目根目录执行）。

5. **原子写入**：
   - 将 `DRAFT_MAIN` 内容复制到 `PRODUCTION_MAIN`。
   - 将 `DRAFT_PLAN` 内容复制到 `PRODUCTION_PLAN`。
   - 使用 Bash `cp` 命令确保原子性。

6. **保留草稿**：不删除 Draft 文件（便于回溯）。

7. **输出环境解耦提示**：
   ```
   ✅ 已定稿！

   📋 变更摘要：
     • CLAUDE.md  [已更新 / 新建]
     • PLAN.md    [已更新 / 新建]
     • CLAUDE.bak.md [已备份上一版本 / 无需备份]

   💡 请根据当前 IDE 环境手动刷新上下文（如：重启会话、重载窗口或点击刷新按钮）以使变更生效。
   ```

---

### 模式 B：进入 / 编辑草稿（Edit）

**触发词**：`编辑草稿`、`修改计划`、`我要改需求`、`进入草稿模式`

**执行步骤（智能分流）**：

1. **情况一（全新初始化）**：双 Draft 均不存在
   - 基于 `PRODUCTION_MAIN` 内容生成 `DRAFT_MAIN`（若 Production 存在则复制，否则用模板）。
   - 基于 `PRODUCTION_PLAN` 内容生成 `DRAFT_PLAN`（若 Production 存在则复制，否则用模板）。
   - 模板见下方「附录 A」。

2. **情况二（基于正式版派生）**：部分 Draft 缺失
   - `DRAFT_MAIN` 缺失 → 从 `PRODUCTION_MAIN` 单向复制。若 Production 也缺失 → 生成模板。
   - `DRAFT_PLAN` 缺失 → 从 `PRODUCTION_PLAN` 单向复制。若 Production 也缺失 → 生成模板。

3. **情况三（修复脏草稿）**：仅存在一个 Draft
   - 自动生成缺失的另一个 Draft：
     - 缺 `DRAFT_PLAN`：基于现有 `DRAFT_MAIN` 推理生成任务拆解，或生成最小占位模板。
     - 缺 `DRAFT_MAIN`：从 `PRODUCTION_MAIN` 复制，或生成模板。
   - 告知用户：「⚠️ 已自动补全缺失的 [PLAN/CLAUDE] 草稿，请完善后定稿。」

4. **红线锁定**：
   - 进入草稿模式后，所有后续 `Edit`/`Write` 操作**必须且只能**作用于 `*.draft.md` 文件。
   - 若用户要求修改 `CLAUDE.md` 或 `PLAN.md`（正式文件），必须回复：
     > 🔒 当前处于草稿模式，修改已被锁定到 `CLAUDE.draft.md` / `PLAN.draft.md`。
     > 请修改对应的 Draft 文件，完成后使用「定稿」命令同步到正式版本。

---

### 模式 C：逆向回滚（Rollback）

**触发词**：`放弃草稿`、`重置草稿`、`回滚正式版`、`回滚`

**三个子操作**：

#### C-1：放弃草稿
- **触发**：`放弃草稿`、`丢弃草稿`、`取消编辑`
- **执行**：删除所有 `*.draft.md` 文件，切回使用模式。
- **输出**：「🗑️ 已删除所有草稿文件，已切回使用模式。正式文件未受影响。」

#### C-2：重置草稿
- **触发**：`重置草稿`、`草稿从头来`
- **执行**：
  - 将 `PRODUCTION_MAIN` 复制到 `DRAFT_MAIN`（覆盖现有草稿）。
  - 将 `PRODUCTION_PLAN` 复制到 `DRAFT_PLAN`（覆盖现有草稿）。
  - 若 Production 文件不存在，则生成模板。
- **输出**：「🔄 已将草稿重置为正式版本的内容，草稿改动已丢弃。」

#### C-3：回滚正式版
- **触发**：`回滚正式版`、`恢复上一版本`、`还原 CLAUDE.md`
- **前置检查**：`BACKUP_MAIN`（`CLAUDE.bak.md`）是否存在。
- **执行**：若存在，将 `BACKUP_MAIN` 复制为 `PRODUCTION_MAIN`（覆盖）。
- **输出**：「⏪ 已将 CLAUDE.md 回滚至上一备份版本。」
- **若备份不存在**：「❌ 未找到 CLAUDE.bak.md 备份文件，无法回滚。」

---

## 4. 状态查询（Status）

**触发词**：`当前什么模式`、`查看状态`、`状态查询`

**执行步骤**：

1. 运行 `entry()` 状态推断，输出当前模式。

2. 列出所有相关文件的存在性、修改时间：
   ```
   📊 文件状态机 — 当前模式：[模式名]

   | 文件 | 状态 | 最后修改 |
   |---|---|---|
   | CLAUDE.md      | ✅ 存在 / ❌ 不存在 | 2024-01-15 10:30 |
   | PLAN.md        | ... | ... |
   | CLAUDE.draft.md| ... | ... |
   | PLAN.draft.md  | ... | ... |
   | CLAUDE.bak.md  | ... | ... |
   ```

3. **差异摘要（Diff）**：若同名的 Draft 和 Production 同时存在，尝试输出差异：
   - 使用 Bash `diff --brief` 判断是否有差异。
   - 若有差异且 `diff` 可用，使用 `diff --unified=3` 输出摘要（限制输出行数，避免刷屏）。
   - 若 `diff` 不可用，对比 YAML Frontmatter 中的 `version` 或 `updated_at` 字段。
   - 输出格式：
     ```
     📝 差异摘要（CLAUDE.draft.md ↔ CLAUDE.md）：
       • 版本：draft=v0.3 → prod=v0.2
       • 更新时间：draft=2024-01-15 → prod=2024-01-10
       • diff 行数统计：+15 / -8
     ```

---

## 5. 模板定义

### 附录 A：全新模板

**`CLAUDE.draft.md` 模板**：
```markdown
---
version: "0.1"
updated_at: "<自动填充当前日期>"
status: "draft"
---

# Project Instructions

## 概述
<项目描述>

## 技术栈
- <技术 1>
- <技术 2>

## 编码规范
- <规范 1>
```

**`PLAN.draft.md` 模板**：
```markdown
---
version: "0.1"
updated_at: "<自动填充当前日期>"
status: "draft"
---

# Plan

> 本文件由 file-state-machine skill 自动生成

- [ ] 待拆解
```

---

## 6. 执行约束清单

| 约束 | 规则 |
|---|---|
| 无状态 | 每次 `entry()` 必须重新扫描磁盘，禁止依赖对话记忆 |
| 互斥锁定 | 草稿模式下 Edit/Write 只能作用于 `*.draft.md` |
| 合法性校验 | 定稿前必须通过 YAML + 结构校验，失败则中止 |
| 滚动备份 | 定稿时若存在旧 Production，必须备份为 `.bak.md`（仅保留一份） |
| 草稿保留 | 定稿后不删除 Draft 文件 |
| 环境解耦 | 所有提示语禁止硬编码 IDE 快捷键，统一用泛化表述 |
| 原子写入 | 使用 `cp` 命令进行文件复制，避免读写竞态 |

---

## 7. 触发词速查表

| 意图 | 触发词 | 目标模式 |
|---|---|---|
| 进入/编辑草稿 | `编辑草稿`、`修改计划`、`我要改需求`、`进入草稿模式` | 模式 B |
| 定稿/发布 | `定稿`、`发布`、`应用草稿`、`同步` | 模式 A |
| 放弃草稿 | `放弃草稿`、`丢弃草稿`、`取消编辑` | 模式 C-1 |
| 重置草稿 | `重置草稿`、`草稿从头来` | 模式 C-2 |
| 回滚正式版 | `回滚正式版`、`恢复上一版本` | 模式 C-3 |
| 查询状态 | `当前什么模式`、`查看状态`、`状态查询` | 状态查询 |
