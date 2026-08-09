# 附录：GitOps 与 Argo CD 速成

> 这不是主线文档。当你在 Agent CI/CD 和部署管理时卡壳时，回来查阅。

---

## 什么是 GitOps

GitOps 是**以 Git 仓库为唯一真相源（Single Source of Truth）**的部署运维方法论——你把基础设施配置、应用配置、部署清单全部存入 Git，由自动化工具确保实际环境与 Git 声明的状态一致。

```mermaid
flowchart LR
    subgraph Git仓库["Git 仓库（真相源）"]
        AppCode["应用代码"]
        InfraConfig["基础设施配置<br/>K8s YAML"]
        AppConfig["应用配置<br/>Prompt / 模型参数"]
        Policy["部署策略<br/>灰度规则 / 回滚条件"]
    end

    subgraph CI["CI 管线"]
        Build["构建镜像"]
        Test["评估门禁"]
        Push["推送镜像"]
    end

    subgraph CD["CD 管线（GitOps）"]
        ArgoCD["Argo CD<br/>监听 Git 变更"]
        Sync["自动同步<br/>→ 生产环境"]
    end

    subgraph 生产["生产环境"]
        K8s["K8s 集群<br/>实际运行状态"]
    end

    AppCode --> Build --> Test --> Push
    InfraConfig --> ArgoCD
    AppConfig --> ArgoCD
    Push --> ArgoCD
    Policy --> ArgoCD
    ArgoCD --> Sync --> K8s
    K8s -.->|"状态反馈<br/>Drift Detection"| ArgoCD
```

---

## GitOps vs 传统 CI/CD

| 维度 | 传统 CI/CD | GitOps |
|------|-----------|--------|
| 部署触发 | CI 管线直接 `kubectl apply` | Git 变更触发 Argo CD 同步 |
| 真相源 | 集群实际状态 | Git 仓库声明状态 |
| 回滚 | 手动重新部署 | `git revert` 自动回滚 |
| 审计 | CI 日志 | Git 历史（天然审计日志） |
| 配置漂移 | 难检测 | Argo CD 自动检测并修复 |
| 权限 | CI 需要 K8s 凭证 | CI 不需要集群访问权限 |

---

## Argo CD 架构

```mermaid
flowchart TD
    Git["Git 仓库<br/>声明式配置"] --> ArgoCD

    subgraph ArgoCD["Argo CD"]
        API["API Server<br/>UI + CLI + API"]
        Repo["Repository Server<br/>拉取 Git 配置"]
        AppCtrl["Application Controller<br/>比较 & 同步"]
    end

    ArgoCD --> Cluster["目标 K8s 集群"]

    subgraph 同步流程["同步流程"]
        S1["检测 Git 变更"] --> S2{"比较<br/>Git vs 集群"}
        S2 -->|"不一致"| S3["执行同步<br/>Apply YAML"]
        S2 -->|"一致"| S4["等待下一次检测"]
        S3 --> S5{"健康检查"}
        S5 -->|"健康"| S6["同步成功"]
        S5 -->|"不健康"| S7["自动回滚"]
    end

    ArgoCD --> 同步流程
```

---

## Agent 应用的 GitOps 实践

### Prompt 版本管理

```mermaid
flowchart LR
    Dev["开发者修改 Prompt"] --> PR["Pull Request"]
    PR --> CI1["CI 评估门禁<br/>Golden Set 评估"]
    CI1 -->|"达标"| Merge["合并到 main"]
    CI1 -->|"不达标"| Reject["拒绝 PR"]
    Merge --> ConfigUpdate["更新 ConfigMap YAML<br/>在 GitOps 仓库"]
    ConfigUpdate --> ArgoCD["Argo CD 检测变更"]
    ArgoCD --> Sync["同步到 K8s<br/>ConfigMap 热更新"]
    Sync --> Agent["Agent Pod 无需重启<br/>自动获取新 Prompt"]
```

### 目录结构

```
gitops-repo/
├── apps/
│   ├── agent-service/
│   │   ├── base/                    # 基础配置
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   ├── configmap.yaml       # Prompt 模板
│   │   │   └── kustomization.yaml
│   │   └── overlays/                # 环境差异
│   │       ├── staging/             # 预发环境
│   │       │   ├── configmap-patch.yaml
│   │       │   └── kustomization.yaml
│   │       └── production/          # 生产环境
│   │           ├── configmap-patch.yaml
│   │           └── kustomization.yaml
│   └── rag-service/
├── infra/                           # 基础设施
│   ├── redis/
│   ├── qdrant/
│   └── postgres/
└── policies/                        # 部署策略
    ├── sync-policy.yaml             # 自动同步规则
    └── rollback-policy.yaml         # 回滚规则
```

### Argo CD Application 定义

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: agent-service
spec:
  source:
    repoURL: https://github.com/company/agent-gitops
    path: apps/agent-service/overlays/production
    targetRevision: main
  destination:
    server: https://kubernetes.default.svc
    namespace: production
  syncPolicy:
    automated:
      prune: true           # 删除 Git 中已删除的资源
      selfHeal: true        # 自动修复配置漂移
    syncOptions:
    - CreateNamespace=true
    # Agent 专属：渐进式同步
  strategy:
    type: RollingSync        # 滚动同步而非一次性全量
```

---

## 灰度发布与 Argo Rollouts

```mermaid
flowchart TD
    NewVersion["新版本部署"] --> Canary["Canary 策略"]
    
    Canary --> Step1["5% 流量<br/>持续 5 分钟"]
    Step1 --> Check1{"SLO 达标?<br/>质量/延迟/成本"}
    Check1 -->|"是"| Step2["25% 流量<br/>持续 10 分钟"]
    Check1 -->|"否"| Rollback1["自动回滚"]
    
    Step2 --> Check2{"SLO 达标?"}
    Check2 -->|"是"| Step3["50% 流量<br/>持续 10 分钟"]
    Check2 -->|"否"| Rollback1
    
    Step3 --> Check3{"SLO 达标?"}
    Check3 -->|"是"| Step4["100% 流量"]
    Check3 -->|"否"| Rollback1
    
    Step4 --> Done["发布完成"]
    
    style Rollback1 fill:#ffcdd2
    style Done fill:#c8e6c9
```

### Argo Rollouts 定义

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: agent-service
spec:
  replicas: 10
  strategy:
    canary:
      steps:
      - setWeight: 5
      - pause: { duration: 5m }
      - setWeight: 25
      - pause: { duration: 10m }
      - setWeight: 50
      - pause: { duration: 10m }
      - setWeight: 100
      analysis:              # 自动分析 SLO
        templates:
        - templateName: agent-slo-check
```

---

## 常用命令

```bash
# Argo CD CLI
argocd app create agent-service \
  --repo https://github.com/company/agent-gitops \
  --path apps/agent-service/overlays/production \
  --dest-server https://kubernetes.default.svc \
  --dest-namespace production

argocd app sync agent-service         # 手动触发同步
argocd app history agent-service      # 查看部署历史
argocd app rollback agent-service <id> # 回滚到历史版本
argocd app diff agent-service         # 查看 Git vs 集群差异
```

---

## 相关文档

- [阶段4-生产化/06-LLMOps与CICD](../阶段4-生产化/06-LLMOps与CICD.md)
- [阶段4-生产化/09-Agent配置中心](../阶段4-生产化/09-Agent配置中心.md)
- [阶段4-生产化/23-Agent版本兼容性管理](../阶段4-生产化/23-Agent版本兼容性管理.md)
- [附录/Docker与Kubernetes速成](Docker与Kubernetes速成.md)
