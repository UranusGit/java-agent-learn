# 01-最小 Demo：单任务 Checkpoint + 断点续跑

> **定位**：用不到百行造出长任务引擎的最小骨架：**一个长任务 + Redis CheckpointStore + 模拟崩溃后续跑**。验证四件事：状态能落库、崩溃能恢复、恢复时跳过已完成步骤、预算字段存在。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 40-长任务持久化与中断恢复](../../教程/40-长任务持久化与中断恢复.md)。
>
> **铁律 0**：引擎自研「概念代码」（承教程40）；仅模拟崩溃用单测触发。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①一个多步长任务（如三步：拉数据→清洗→写库）②CheckpointStore 持久化每步状态 ③模拟崩溃后 load 续跑，跳过已完成步 |
| **影响了哪些模块** | 单体 WorkflowEngine + CheckpointStore |
| **架构如何演进** | 从无到有：先证明"状态可落库、崩溃可变接续" |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①任务跑到第 2 步时"崩溃"，重启后从第 3 步续跑（不重跑 1-2）②Checkpoint 含预算字段可读 ③恢复结果与不崩溃时一致。

## 二、Checkpoint + 断点续跑（最小闭环）

```java
// 概念代码：承教程40的 CheckpointStore 范式
public record AgentTaskCheckpoint(String taskId, int completedStep,
                                  Map<String, Object> stepResults, int usedTokens) {}

public class WorkflowEngine {
    private final CheckpointStore store;   // Redis/JSONB 持久化（教程40）

    // 一个三步开任务：拉数据→清洗→写库（每步幂等由 03 迭代补，本步仅状态）
    public TaskResult runWithResume(String taskId, List<Step> steps) {
        var cp = store.load(taskId).orElse(new AgentTaskCheckpoint(taskId, 0, Map.of(), 0));
        for (int i = cp.completedStep(); i < steps.size(); i++) {
            Step s = steps.get(i);
            Object res = s.execute(cp.stepResults());          // 每步执行
            cp = new AgentTaskCheckpoint(taskId, i + 1,
                    merge(cp.stepResults(), s.name(), res), cp.usedTokens() + s.tokens());
            store.save(taskId, cp);                            // 每步完成即落库
        }
        return new TaskResult(cp.stepResults(), cp.usedTokens());
    }
}
```

**关键**：`store.save` 在**每步完成后调用**——这样任何一步崩溃，`completedStep` 已持久化，重启后 `load` 到已完成步继续。**崩溃点落在已完成步之后**，所以不重复执行（这是最小版的断点续跑；重复执行副作用的风险由 03 幂等解决）。

## 三、验证矩阵

| 输入 | 期望 |
|------|------|
| 正常跑完 3 步 | 三步都执行，返回结果 |
| 第 2 步后崩溃 | 重启后从第 3 步续跑，1-2 不重跑 |
| Checkpoint 预算字段 | 可读到 usedTokens（为 04 预算铺路） |

## 四、最小版的两处"偷懒"（下一步补）

1. **重试不幂等**：若第 2 步是"写库"，崩溃在保存前 → 重启后步骤 2 重跑，可能重复写（→ 03 迭代幂等）。
2. **预算裸奔**：只有 usedTokens 字段，无闸门（→ 04 迭代三层预算 + 死循环防护）。

> **下一步**：断点续跑已通，但任务状态管理散乱（哪步在跑、并行吗、状态机在哪）→ 02 迭代做 **Actor 任务核 + 任务生命周期**；重试幂等与预算在 03/04 补。
