# 理论字典：Agent 范式

| 概念 | 一句话解释 |
|------|---------|
| **Agent** | 能自主规划多步骤、在步骤间做决策的 AI 系统 |
| **ReAct** | Reasoning + Acting：思考→行动→观察的循环 |
| **Agent Loop** | `while(true) { 决策(); 执行(); 观察(); }` |
| **Workflow** | 确定性步骤序列（DAG），不需要 LLM 自主决策 |
| **Workflow > Agent** | 能用确定性 Workflow 解决的，不用自主 Agent |
| **Tool Calling** | LLM 决定调用哪个工具，程序执行后返回结果 |
| **maxTurns** | Agent 循环次数硬上限（防无限循环） |
| **五大 Workflow 模式** | Chaining / Parallelization / Routing / Orchestrator-Workers / Evaluator-Optimizer |

## Agent vs Workflow 选型
```
流程固定可枚举 → Workflow
流程不确定需动态规划 → Agent
不确定 → 先 Workflow，不行再 Agent
```

## 相关文档
- Agent 循环：`阶段3-Agent工程化/01-Agent循环.md`
- 五大模式：`阶段3-Agent工程化/02-五大Workflow模式.md`
- 防失控：`阶段3-Agent工程化/03-Agent防失控.md`
- 多 Agent：`阶段5-架构师/01-多Agent编排.md`
