# 27 AI 项目的 Git 工作流（Feature Branch + Prompt Repo + GitOps）

> AI 项目同时管"代码 + prompt + 模型 + 数据 + 实验"，传统 Git Flow 不够。本文给出一套适配 AI 项目特点的 Git 工作流。
>
> 前置：[`./27-CICD-for-AI.md`](./27-CICD-for-AI.md)
> 预计：1 天

---

## 0. 认知地图

```
传统 Git Flow
    └── main / develop / feature/* / release/*
        + AI 项目特殊需求
            ├── 实验 branch（throwaway）
            ├── Prompt 独立 repo / sub-module
            ├── 数据 DVC / LakeFS
            ├── 实验 tracking（MLflow / Langfuse）
            └── GitOps（ArgoCD / Flux）
```

---

## 1. 仓库结构选型

### 1.1 三种方案

| 方案 | 适合 | 优劣 |
|------|------|------|
| **Monorepo** | 中小团队，单一产品 | 简单，但 repo 大 |
| **Multi-repo** | 大团队，多产品 | 隔离清晰，跨仓协调成本高 |
| **Hybrid**（推荐） | 中大团队 | 应用代码 monorepo + Prompt/数据 sub-repo |

### 1.2 推荐 Hybrid 结构

```
ai-platform/                    # 主仓库（代码）
├── apps/
├── prompts/                    # 内嵌 prompt 目录（同步到 Langfuse）
├── eval/
├── data/
├── infra/
└── .gitmodules                 # 可选：submodule 引用

prompt-registry/                # 独立 Prompt 仓库（可选）
└── 跨产品共享的 prompt

model-registry/                 # 独立 Model 配置仓库（可选）
└── models.yaml / model cards

infra-deployments/              # ArgoCD 仓库
└── GitOps state
```

### 1.3 什么时候拆 Prompt 仓库

- 多个产品 / Agent 共享同一套 prompt
- Prompt 变更频率远高于代码
- Prompt 由产品 / 运营人员维护（不是工程师）

否则 prompt 留在主仓库的 `prompts/` 目录足够。

---

## 2. 分支模型

### 2.1 推荐：Trunk-Based + Feature Branch

```
main
  │
  ├── feature/refund-flow      ← feature branch（1-3 天合并）
  ├── feature/v2-embedding     ← feature branch
  ├── experiment/qwen2.5-test  ← 实验 branch（throwaway）
  └── hotfix/crash-fix         ← 紧急修复
```

**原则**：

- main 始终可发布
- feature branch 寿命 ≤ 1 周（避免冲突）
- 实验 branch 可不合并，直接删
- hotfix cherry-pick 回 main

### 2.2 不推荐：Git Flow 的 develop

Git Flow 的 develop / release 分支模型是为"季度发布"设计的，AI 项目需要"日级发布"，Trunk-Based 更合适。

### 2.3 实验 branch 的特殊用法

```
experiment/try-cot-on-classifier
  ↓ 试了 CoT 提升不显著
  ↓ 删掉（不合并）

experiment/try-new-embedding-bge-m3
  ↓ 显著提升
  ↓ 改写为 feature/upgrade-embedding-bge-m3
  ↓ PR + eval + 合并
```

实验 branch 名字以 `experiment/` 开头，CI 不强制跑全量 eval（鼓励尝试）。

---

## 3. Commit 规范

### 3.1 Conventional Commits + AI 扩展

```
<type>(<scope>): <subject>

type:    feat / fix / docs / refactor / test / chore / exp / data / prompt / model
scope:   customer-service / ops-agent / rag / ...
```

例：

```
feat(customer-service): 添加退款流程的 Agent

fix(rag): 修复 chunk overlap 0 时的边界 NPE

prompt(refund-writer): v2 强化 30 天拒绝规则

model(customer-service): 升级到 claude-sonnet-4-5-20251101

data(faq): 添加 2026-07 退款政策更新

exp(rag): 尝试 Parent-Child chunking（提升 recall@10 +6pt，准备做正式 feature）
```

### 3.2 Prompt / Model / Data 变更的强制字段

```markdown
## prompt(refund-writer): v2 强化 30 天拒绝规则

变更内容：
- 把"超过 30 天建议联系客服"改为"超过 30 天拒绝退款"

Eval 结果：
- answer_correctness: 0.85 → 0.88
- faithfulness: 0.92 → 0.91（-0.01）
- 拒绝率: 5% → 8%

回滚方案：
- FeatureFlag `prompt.refund_writer.v2`

Jira: CS-1234
```

CI 检查 `prompt:` / `model:` / `data:` 类型的 commit 必须附 eval report（用 PR 模板字段）。

---

## 4. PR 流程

### 4.1 PR 模板

```markdown
## 这个 PR 做什么

[一句话描述]

## 类型

- [ ] 应用代码
- [ ] Prompt 变更
- [ ] 模型升级
- [ ] 数据变更
- [ ] 实验 / 调研

## Eval 报告（Prompt / Model / Data 类必须填）

| 指标 | Baseline | New | Diff |
|------|---------|-----|------|
| ... | ... | ... | ... |

## 影响范围

- [ ] 全量用户
- [ ] 灰度（X%）
- [ ] 仅 staging

## 回滚方案

[FeatureFlag / rollback command]

## Checklist

- [ ] 单测通过
- [ ] 集成测试通过
- [ ] eval 不退步
- [ ] 文档已更新
- [ ] 监控告警已加
```

### 4.2 Review 规则

| PR 类型 | Review 要求 |
|--------|----------|
| 应用代码 | 1 工程师 + CI 绿 |
| Prompt 变更 | 1 工程师 + 1 PM/运营 + eval 绿 |
| 模型升级 | 2 工程师 + 7 天观察 |
| 数据变更 | 1 工程师 + DBA 知会 |
| 实验 / 调研 | 自由 merge 到 main（不部署） |

### 4.3 Review checklist

Reviewer 必查：

- [ ] 是否引入新的 secret 硬编码
- [ ] 是否做了 PII 脱敏
- [ ] 是否触发全量 reindex
- [ ] 是否需要 feature flag
- [ ] 是否影响其他 prompt / agent

---

## 5. Prompt Repo 子工作流

如果 prompt 在独立仓库，用单独的轻量工作流：

### 5.1 Langfuse 作为 Prompt Repo

```bash
# 工程师本地
langfuse prompt pull refund-writer
# 编辑 / 调试
langfuse prompt push refund-writer --tag v2-candidate
# 在 Langfuse UI 上做 A/B
# 推到 production
```

### 5.2 CI 双向同步

主仓库 → Langfuse：

```yaml
# .github/workflows/sync-prompt-out.yml
on:
  push:
    branches: [main]
    paths: ['prompts/**']
jobs:
  sync:
    steps:
      - run: ./scripts/sync-prompt-to-langfuse.sh
```

Langfuse → 主仓库（每天 cron 备份）：

```yaml
# .github/workflows/sync-prompt-in.yml
on:
  schedule:
    - cron: '0 2 * * *'
jobs:
  backup:
    steps:
      - run: ./scripts/sync-prompt-from-langfuse.sh
      - run: |
          git config user.name "langfuse-bot"
          git config user.email "bot@example.com"
          git add prompts/
          git diff --cached --quiet || git commit -m "chore: sync prompts from Langfuse"
          git push
```

---

## 6. 实验 Tracking 与 Git 的关系

### 6.1 MLflow / Langfuse 实验 ≠ Git branch

```
Git branch：代码状态
MLflow run：用某段代码 + 某 hyper-param + 某数据版本 跑出的实验结果
```

两者结合：

```
git commit: abc123  "feat: try lr=0.001 on fine-tune"
  ↓
mlflow run logged with:
  - git_commit: abc123
  - dataset_version: data@v3
  - metrics: {recall@10: 0.87}
```

### 6.2 Reproducibility 三件套

要让 3 个月后能复现某个实验：

```
git checkout <commit>      # 代码
dvc checkout data@v3       # 数据
mlflow artifacts download  # 模型权重
```

三者必须关联（commit message / mlflow tag 里写明）。

---

## 7. 数据变更工作流

### 7.1 标准流程

```
1. 在 feature branch 修改 data/...
2. dvc add data/...
3. git commit data/... .dvc
4. PR + DBA review
5. merge 触发 reindex CI
6. reindex 完跑 retrieval eval
7. eval 绿才发版
```

### 7.2 大批量数据导入

- **批量任务**：单独的 K8s Job，不阻塞主应用部署。
- **增量同步**：CDC（Debezium）→ Kafka → Indexer service。

详见 [`./18-大规模Agent平台与数据基础设施.md`](./18-大规模Agent平台与数据基础设施.md)。

---

## 8. Release 流程

### 8.1 三种 release 节奏

| 节奏 | 适合 |
|------|------|
| 每周 release（应用代码） | 节奏稳，CI 自动 |
| 每天 release（Prompt 微调） | 配置中心秒级生效 |
| 按需 release（模型升级） | 谨慎，季度一次 |

### 8.2 Release notes 模板

```markdown
# Release 2026-07-17

## Features
- 客服 Agent 支持多轮退款（@张三）
- Ops Agent 接入 K8s 工具（@李四）

## Prompt Updates
- refund-writer v2（@产品-王五）
- ops-diagnose v1.1

## Model Updates
- 升级 claude-sonnet-4-5-20251101（@赵六）

## Data Updates
- FAQ 库新增 7 月政策（@运营-孙七）

## Fixes
- 修复 streaming chunk 丢内容的 bug

## Rollback
- ArgoCD: argocd app rollback customer-service <prev>
- Prompt: feature flag `prompt.refund_writer.v1`
- Model: models.yaml 切回旧 snapshot
```

---

## 9. Hotfix 流程

### 9.1 标准 hotfix

```bash
# 1. 从 main 切 hotfix
git checkout main
git checkout -b hotfix/crash-on-empty-tool-result

# 2. 修复 + 单测
# ...

# 3. PR + 紧急 review（1 工程师）
# 4. 合并后 ArgoCD 自动部署
# 5. cherry-pick 到当前 release branch（如有）
```

### 9.2 Prompt 紧急回滚

不需要走 Git，直接 Langfuse / FeatureFlag 翻开关：

```bash
curl -X POST https://langfuse.internal/api/v1/prompts/refund-writer/rollback \
  -d '{"toVersion": "v1"}'
```

5 分钟内生效。

---

## 10. 权限模型

### 10.1 CODEOWNERS

```
# .github/CODEOWNERS
/apps/customer-service/     @team-cs-eng
/prompts/customer-service/  @team-cs-eng @team-cs-pm
/models/                    @ml-platform
/data/                      @data-team @dba
/infra/                     @sre
```

### 10.2 分支保护规则

```
main:
  - 必须通过 CI
  - 必须 1+ review
  - 禁止 force push
  - 禁止直接 commit（只通过 PR）
  
release/*:
  - 同 main
  - 只允许 cherry-pick / merge from main
```

### 10.3 Prompt 仓库的轻量化

Prompt 仓库可以放宽：

- 允许 PM / 运营直接 push 到 `prompt-staging` 分支
- 自动 sync 到 staging 环境
- 推 production 仍需工程师 review

---

## 11. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| 直接 push main | 跳过 review | 分支保护 |
| Feature branch 活 2 周 | merge 冲突地狱 | 限制 1 周 |
| 不区分代码 / prompt / model commit | 历史乱 | Conventional Commits |
| Prompt 改完不发 PR | 不可追溯 | PromptRepo + sync |
| 实验 branch 不删 | 仓库膨胀 | 定期清理 experiment/* |
| Hotfix 不 cherry-pick 回 main | 下次 release 又 broken | 标准流程必走 |
| 数据改动不触发 reindex | RAG 召回陈旧 | CI 自动 |
| 模型升级不带 eval | 行为漂移 | 强制 eval gate |

---

## 12. 实战任务

1. 为你的项目选型：monorepo / multi-repo / hybrid，写一份 ADR。
2. 设计分支模型：feature / experiment / hotfix 的命名规范和生命周期。
3. 配置 CODEOWNERS 和分支保护。
4. 写 PR 模板（区分代码 / prompt / model / data）。
5. 配置 Langfuse ↔ 主仓库的双向 sync CI。
6. （进阶）实现"prompt 紧急回滚"脚本：5 分钟内切到旧版本。
7. （选做）调研 DVC + MLflow 联合管理实验，对比本文方案。

---

## 13. 理解检查

1. Trunk-Based 相比 Git Flow 在 AI 项目里的优势？
2. 实验 branch 和 feature branch 的区别？
3. Prompt 独立仓库 vs 主仓库子目录，各自适合什么？
4. MLflow 实验和 Git commit 的关系？
5. 三种 release 节奏（代码 / Prompt / 模型）为什么不一样？
6. Prompt 紧急回滚为什么不需要走 Git？

---

## 14. 相关文档

- [`./12-评估闭环与Prompt版本管理.md`](./12-评估闭环与Prompt版本管理.md) —— Prompt 版本化
- [`./19-自研vs框架的边界.md`](./19-自研vs框架的边界.md) —— ADR 模板
- [`./27-CICD-for-AI.md`](./27-CICD-for-AI.md) —— CI/CD 流水线
- [`./26-AI工程的SRE实践.md`](./26-AI工程的SRE实践.md) —— 变更管理 + 回滚
- [Trunk-Based Development](https://trunkbaseddevelopment.com/)
- [Conventional Commits](https://www.conventionalcommits.org/)
- [GitHub CODEOWNERS](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [Langfuse Prompt Management](https://langfuse.com/docs/prompts)
- [MLflow Model Registry](https://mlflow.org/docs/latest/model-registry.html)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
