# 07 · 项目 P4：智能运维 Agent

> 阶段：4 生产化 · 难度：⭐⭐⭐⭐ · 预计：5 天
> 前置：[06 LLMOps 与 CICD](06-LLMOps与CICD.md)
> 产出：一个生产级运维 Agent——自然语言查 K8s + 流式 + 熔断 + 可观测

---

## 项目目标

```
用户：查一下 default 命名空间下所有 Pod 的状态
Agent：正在查询... → [调用 K8s API] → 返回 Pod 列表 + 健康状态

用户：nginx-pod 的日志最后 50 行有什么异常？
Agent：[调用日志查询] → 分析日志 → 发现 OOM 异常 → 给出建议
```

**复用前面学的能力**：
- P3 的五大 Workflow（分析日志 = Parallelization + Evaluator）
- 阶段 4 全部：上下文工程 + 可靠性 + 可观测 + 成本 + 测试

---

## 核心组件

```
src/main/java/demo/demo04/
├── tools/
│   ├── K8sTools.java           # 查 Pod/Service/日志
│   └── MetricsTools.java       # 查 CPU/内存指标
├── guard/
│   ├── BudgetGuardAdvisor.java # 预算保护
│   └── CircuitGuardAdvisor.java# 三层熔断
├── obs/
│   ├── AiMetrics.java          # Micrometer 指标
│   └── TraceAdvisor.java       # 全链路 trace
├── cost/
│   ├── SemanticCache.java      # 语义缓存
│   └── ModelRouter.java        # 模型路由
└── controller/
    └── OpsAgentController.java  # 运维 Agent 入口
```

---

## 验收检查（P4 项目标准）

- [ ] 自然语言查 Pod / 日志 / 指标
- [ ] 流式输出正常
- [ ] 重启后会话不丢（持久化恢复）
- [ ] 全链路 trace 可查
- [ ] 成本看板可看
- [ ] Resilience4j 三层熔断验证通过
- [ ] 四层测试覆盖

---

## 🎉 阶段 4 完成

你的 Agent 现在**敢上线**了——有可靠性保护、成本优化、全链路可观测、CI 评估门禁。接下来阶段 5 是毕业项目。

---

## 下一步

→ 进入 [阶段 5 架构师](../阶段5-架构师/01-多Agent编排.md)
