# 26 CI/CD for AI（模型 / Prompt / 数据 三套版本化）

> 传统 CI/CD 只管代码。AI 系统还要管 prompt、模型、数据三套"会变的东西"。本文给出一套可落地的 AI CI/CD 流水线。
>
> 前置：[`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md) + [`./25-AI工程的SRE实践.md`](./25-AI工程的SRE实践.md)
> 预计：1.5 天

---

## 0. 认知地图

```
传统 CI/CD
    ├── 代码版本：Git
    ├── 构建产物：Docker image
    └── 部署：Helm + ArgoCD

AI CI/CD 加 3 套
    ├── Prompt 版本：PromptRepo（11 篇）+ diff eval
    ├── 模型版本：model registry（vLLM / OpenAI snapshot）
    └── 数据版本：DVC / LakeFS（retrieval corpus / eval set）
```

---

## 1. 三套版本化的"为什么"

### 1.1 Prompt 是代码还是配置？

```
Prompt 当代码：
  + git 版本化、PR review、diff 可见
  + 强制跑 eval
  - 改 prompt 要发版（慢）

Prompt 当配置：
  + 配置中心秒级生效
  + A/B 容易
  - 没法 review / 没 eval gate
```

**结论**：双轨制——

- Prompt **schema / 重大改动** 走代码（Git + PR + eval gate）
- Prompt **内容微调** 走配置中心（Langfuse / 自建 PromptRegistry）

### 1.2 模型版本的特殊性

模型不是"升级就好"，每次升级都可能是行为 breaking change：

- OpenAI 的 `gpt-4o` 是 alias，背后会切 snapshot
- Anthropic `claude-sonnet-4-5` 类似
- 本地 vLLM 升级（Qwen2 → Qwen2.5）也变行为

**必须**：固定到具体 snapshot，并对每次升级做回归。

### 1.3 数据版本的特殊性

RAG 的知识库会变：

- 文档新增 / 修改 / 删除
- chunk 策略调整
- embedding 模型升级 → 全量重建

**必须**：知识库用 DVC / LakeFS 版本化，每次变更触发 reindex + eval。

---

## 2. 仓库结构

推荐 monorepo + sub-directory：

```
ai-platform/
├── apps/
│   ├── customer-service/      # 客服 Agent 应用代码
│   └── ops-agent/
├── prompts/                   # Prompt 仓库（独立版本化）
│   ├── customer-service/
│   │   ├── refund-writer.v1.yaml
│   │   └── refund-writer.v2.yaml
│   └── ...
├── eval/                      # 评估集（DVC 管理）
│   ├── customer-service/
│   │   ├── golden.jsonl
│   │   └── hard-cases.jsonl
│   └── ...
├── data/                      # RAG 知识库（DVC 管理）
│   ├── product-docs/
│   └── faq/
├── infra/
│   ├── helm/
│   └── terraform/
└── .github/workflows/         # CI/CD pipeline
```

---

## 3. Prompt CI/CD

### 3.1 Prompt 作为 YAML

```yaml
# prompts/customer-service/refund-writer.v2.yaml
id: refund-writer
version: v2
model: claude-sonnet-4-5-20251101
temperature: 0.3
max_tokens: 800
system: |
  你是退款助手。请按以下规则写回复：
  - 7 天内全额退款
  - 7-30 天折旧退款
  - 超过 30 天拒绝
  ...
user_template: |
  用户问题：{question}
  订单信息：{order}
```

### 3.2 PR 模板

```markdown
## Prompt Change

- [ ] 关联 Jira: ...
- [ ] eval 已跑：baseline X.X → new X.X（不退步）
- [ ] 影响范围：customer-service 全量 / 仅某场景
- [ ] 回滚方案：feature flag `prompt.refund_writer.v2`

## Evaluation Report

| 指标 | Baseline | New | Diff |
|------|---------|-----|------|
| answer_correctness | 0.85 | 0.88 | +0.03 |
| faithfulness | 0.92 | 0.91 | -0.01 |
| avg_tokens | 420 | 380 | -40 |
```

### 3.3 CI Step：自动 diff eval

```yaml
# .github/workflows/prompt-pr.yml
name: Prompt Eval
on:
  pull_request:
    paths:
      - 'prompts/**'
jobs:
  eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup Java
        uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: Run diff eval
        run: |
          ./gradlew eval --baseline origin/main \
            --prompt-changed ${{ github.event.pull_request.changed_files }}
      - name: Upload report
        uses: actions/upload-artifact@v4
        with:
          name: eval-report
          path: build/eval/
```

eval 任务做：

1. 对 PR 改动的 prompt 用新版跑 eval 集
2. 对同一 eval 集用旧版（baseline）跑一遍
3. 对比，差异超阈值 fail

### 3.4 Deploy：渐进发布

```yaml
# .github/workflows/prompt-deploy.yml
name: Prompt Deploy
on:
  push:
    branches: [main]
    paths: ['prompts/**']
jobs:
  deploy:
    steps:
      - name: Sync to PromptRegistry
        run: ./scripts/sync-prompts.sh production
      - name: Enable 10% traffic
        run: ./scripts/feature-flag.sh prompt.refund_writer.v2 --rollout 10
      - name: Wait 1 hour, check SLO
        run: ./scripts/check-slo.sh --since 1h --slo answer_correctness
      - name: Promote to 100%
        if: success()
        run: ./scripts/feature-flag.sh prompt.refund_writer.v2 --rollout 100
```

---

## 4. 模型版本管理

### 4.1 Model Registry

不要在代码里写死 `"gpt-4o"`，通过 registry 解析：

```yaml
# config/models.yaml
models:
  customer_service_chat:
    active: claude-sonnet-4-5-20251101
    fallback:
      - gpt-4o-2024-11-20
      - deepseek-chat-v3
  embedding:
    active: bge-m3
    fallback: []
```

```java
// 本代码仅作学习材料参考
@ConfigurationProperties("models")
public record ModelRegistry(Map<String, ModelEntry> models) {
    public String resolve(String key) {
        return models.get(key).active();
    }
}
```

### 4.2 Snapshot 升级流程

```
1. 订阅 provider 的 changelog
2. 新 snapshot 上线后，在 staging 跑全量 eval（1 天观察）
3. 灰度 5% 流量，看 1 周
4. 全量后保留旧 snapshot 1 个月作为 fallback
5. 更新 models.yaml，PR + eval gate
```

### 4.3 自托管模型版本

vLLM / TGI 部署的模型：

```yaml
# helm/values.yaml
image:
  repository: registry.internal/vllm
  tag: qwen2.5-72b-2026-07-01
model:
  name: Qwen/Qwen2.5-72B-Instruct
  revision: abc123def456...
```

每次升级：

- image tag 改（带日期）
- model revision 锁定到具体 commit
- 跑回归 eval
- 蓝绿部署

---

## 5. 数据版本管理

### 5.1 DVC（Data Version Control）

```bash
# 初始化
dvc init

# 添加 RAG 知识库
dvc add data/product-docs
git add data/product-docs.dvc .gitignore
git commit -m "data: import product docs v1"

# 更新文档
# (修改 data/product-docs 下的文件)
dvc add data/product-docs
git commit -m "data: update product docs for July release"

# 回滚到旧版本
git checkout HEAD~1 data/product-docs.dvc
dvc checkout
```

### 5.2 LakeFS（Git for object storage）

数据量大（TB 级）时 DVC 不够，用 LakeFS：

```bash
lakectl branch create lakefs://rag/main-v2
# 在 v2 分支上传新数据
# 跑 eval 验证
# merge 回 main
```

### 5.3 触发 reindex 的 CI

```yaml
# .github/workflows/data-change.yml
name: Reindex on data change
on:
  push:
    branches: [main]
    paths: ['data/**']
jobs:
  reindex:
    steps:
      - name: Trigger reindex job
        run: |
          kubectl apply -f infra/jobs/reindex.yaml
      - name: Wait for completion
        run: kubectl wait job/reindex --for=condition=complete --timeout=3600s
      - name: Run retrieval eval
        run: ./gradlew eval --suite retrieval
```

---

## 6. Eval gate 完整流水线

```yaml
# .github/workflows/full-release.yml
name: Full Release
on:
  push:
    branches: [main]
jobs:
  # 1. 代码 + Prompt + 模型 一起评估
  eval:
    steps:
      - run: ./gradlew test                   # 单元 / 集成测试
      - run: ./gradlew eval --full            # 全量 eval 集
      - run: ./gradlew eval --a/b 1h          # 影子流量 A/B 1 小时
  
  # 2. 构建产物
  build:
    needs: eval
    steps:
      - run: docker build -t app:${{ github.sha }} .
      - run: docker push registry.internal/app:${{ github.sha }}
  
  # 3. 部署到 staging
  deploy-staging:
    needs: build
    steps:
      - run: helm upgrade --install app infra/helm -f staging.yaml
  
  # 4. Smoke test
  smoke:
    needs: deploy-staging
    steps:
      - run: ./scripts/smoke-test.sh staging.internal
  
  # 5. 手动审批
  approve:
    needs: smoke
    environment: production-approval
  
  # 6. 蓝绿部署到 prod
  deploy-prod:
    needs: approve
    steps:
      - run: helm upgrade --install app infra/helm -f prod.yaml --wait
      - run: ./scripts/blue-green-switch.sh
```

---

## 7. ArgoCD / Flux GitOps

```
Git 仓库（desired state）
    ↓ ArgoCD 同步
K8s 集群（actual state）
    ↓ drift detection
告警 / 自愈
```

K8s manifest 全部走 GitOps，CI/CD 只改 Git，不直接 kubectl apply。

AI 特有的 GitOps 资源：

```yaml
# infra/argocd-apps/llm-gateway.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  source:
    path: infra/helm/llm-gateway
    targetRevision: main
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

---

## 8. 回滚策略

### 8.1 三层回滚

```
应用代码回滚 → ArgoCD one-click（秒级）
Prompt 回滚 → FeatureFlag 翻开关（秒级）
模型回滚 → 更新 models.yaml + ArgoCD 同步（分钟级）
数据回滚 → DVC/LakeFS 切版本 + reindex（小时级）
```

### 8.2 不可回滚的变更

- 数据库 schema 改动（向前不兼容）
- 向量库 embedding 模型升级（要重建）
- 知识库删除（要重新导入）

这些必须先备份 + 双写过渡。

---

## 9. CI/CD 的安全红线

### 9.1 不自动部署

- Prompt 变更（必须人工 review）
- 模型 snapshot 升级（必须人工 review + 1 周观察）
- 数据 schema 改动（必须 DBA review）

### 9.2 自动 gate

- eval 退步 > 5% 阻断 merge
- 单元测试覆盖率不下降
- 关键路径必须有集成测试
- prompt diff 必须 attached eval report

### 9.3 Secret 管理

```yaml
# 不要写死
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}   # 从 K8s Secret / Vault 注入
```

CI 里用 OIDC token + 短期凭证，不持久化 key。

---

## 10. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| Prompt 写代码里没版本 | 无法 A/B / 回滚 | PromptRepo |
| 用 alias 不锁 snapshot | 行为静默漂移 | 锁 snapshot |
| Eval 集 git 不管 | 每次"评估"不可复现 | eval 入仓 + DVC |
| 一改 prompt 立刻全量 | 不可逆错误 | 灰度 + shadow |
| 数据改动不触发 reindex | RAG 召回陈旧 | data change → reindex CI |
| 没有 fallback 链 | 单点故障 | 多模型 fallback |
| CI 跑 eval 用真 LLM | 慢 + 贵 + 不稳 | WireMock LLM + 真实 LLM 集成测试单独跑 |
| 没有 ArgoCD 直接 kubectl | 配置漂移 | GitOps |

---

## 11. 实战任务

1. 把项目所有 Prompt 提取到 `prompts/` 目录，加版本号。
2. 写一个 GitHub Action，PR 时自动跑 diff eval。
3. 配置 ModelRegistry，让代码不直接依赖 model name。
4. 用 DVC 管理 `data/faq` 目录，模拟一次"添加新文档 → 触发 reindex → 跑 retrieval eval"。
5. 配置 ArgoCD 把应用 manifest 同步起来。
6. （进阶）实现"prompt 蓝绿部署"：同时跑 v1/v2，shadow traffic 对比，自动决策是否提升。
7. （选做）调研 Langfuse / Promptflow 的 prompt CI 集成，对比本文方案。

---

## 12. 理解检查

1. Prompt 是代码还是配置？为什么用双轨制？
2. 为什么模型 alias 不安全？必须锁到 snapshot？
3. DVC 和 LakeFS 各自适用什么规模的数据？
4. ArgoCD GitOps 相比直接 kubectl 的优势？
5. 三个回滚层次（应用 / Prompt / 模型）各自的回滚时长？
6. CI 跑 eval 用真 LLM 有什么问题？怎么解决？

---

## 13. 相关文档

- [`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md) —— Prompt 版本化
- [`./12-测试工程化.md`](./12-测试工程化.md) —— WireMock LLM
- [`./25-AI工程的SRE实践.md`](./25-AI工程的SRE实践.md) —— SRE + 变更管理
- [`./27-AI项目的Git工作流.md`](./27-AI项目的Git工作流.md) —— Git 工作流配套
- [DVC](https://dvc.org/)
- [LakeFS](https://lakefs.io/)
- [ArgoCD](https://argo-cd.readthedocs.io/)
- [Langfuse Prompt Management](https://langfuse.com/docs/prompts)
- [PromptFlow](https://microsoft.github.io/promptflow/)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
