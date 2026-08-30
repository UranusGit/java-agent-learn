# 01-最小 Demo：单任务 Checkpoint + 断点续跑

> **定位**：用不到百行造出长任务引擎的最小骨架：**一个长任务 + Redis CheckpointStore + 模拟崩溃后续跑**。验证四件事：状态能落库、崩溃能恢复、恢复时跳过已完成步骤、预算字段存在。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 08-架构师进阶/06-长任务持久化与中断恢复](../../教程/08-架构师进阶/06-长任务持久化与中断恢复.md)。
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

### 一.1 本节核对（四问与本迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，无空答；"上一版痛点=无（起点）"表述自洽 |
| 2 | 本迭代验收可度量 | ①崩溃续跑不重跑 ②预算字段可读 ③结果一致——三项均是可判定动作，非空话 |

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

### 二.1 本节测试与验证（Checkpoint 断点续跑最小闭环）

**前置条件**：`AgentTaskCheckpoint`/`WorkflowEngine`/`CheckpointStore`（本迭代 Redis/JSONB 版本，承教程 05-Observation可观测/07-指标治理：Token计量、SLO与基数熔断）已按上文代码手写并编译通过；可构造一个三步任务（拉数据→清洗→写库）实例。

**材料——三步任务与恢复脚本**：任务步骤 `steps` 固定三步；崩溃注入点在跑完第 2 步后手动抛异常/终止进程。

```bash
mvn test -Dtest=CheckpointResumeTest        # 按 §二 代码手写三步任务/崩溃注入/续跑用例后执行
# 预期输出（节选）：
#   Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
#   续跑从 completedStep=2 之后开始（第 1-2 步不重跑）
#   BUILD SUCCESS
```


**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 正常 `runWithResume` 跑完 3 步 | 三步都执行，`stepResults` 含三步结果；`usedTokens` 为累计值 |
| 2 | 第 2 步完成后、"第 3 步执行前"模拟崩溃（抛异常/中断） | `store.load(taskId)` 的 `completedStep==3`? 否——应为 `==2`，即第 2 步写完已落库 |
| 3 | 重启后再次 `runWithResume`（load 续跑） | 从 completedStep（第 3 步）续跑，第 1-2 步**不重跑**，最终结果与不崩溃时一致 |
| 4 | 读取 Checkpoint | `usedTokens` 字段可读且为正（为 04 预算铺路） |
| 5 | 崩溃点在后两步各测一次 | 每次续跑都从"已持久化的该步之后"开始，无重复执行（幂等由 03 迭代补） |

**失败排查**：①续跑时从第 1 步重跑→`store.save` 未在每步完成即调用（或 call 内未 `merge` 保留旧 stepResults）；②崩溃后 `completedStep` 未推进→save 在 execute 抛异常后才调用（应提前）；③结果不一致→load 后 `cp.completedStep()` 与 steps.size() 边界错位（for 循环条件写错）。

## 三、验证矩阵

| 输入 | 期望 |
|------|------|
| 正常跑完 3 步 | 三步都执行，返回结果 |
| 第 2 步后崩溃 | 重启后从第 3 步续跑，1-2 不重跑 |
| Checkpoint 预算字段 | 可读到 usedTokens（为 04 预算铺路） |

### 三.1 本节核对（验证矩阵）

- [ ] 矩阵三行（正常跑完/第 2 步后崩溃/预算字段）在本节 §二.1 的步骤与断言表中均有落地断言项，一一对应，无矩阵行被悬空
- [ ] "第 2 步后崩溃续跑"的判据是本迭代验收项①的收口，未被弱化为只测正常路径

## 四、最小版的两处"偷懒"（下一步补）

1. **重试不幂等**：若第 2 步是"写库"，崩溃在保存前 → 重启后步骤 2 重跑，可能重复写（→ 03 迭代幂等）。
2. **预算裸奔**：只有 usedTokens 字段，无闸门（→ 04 迭代三层预算 + 死循环防护）。

> **下一步**：断点续跑已通，但任务状态管理散乱（哪步在跑、并行吗、状态机在哪）→ 02 迭代做 **Actor 任务核 + 任务生命周期**；重试幂等与预算在 03/04 补。

### 四.1 本节核对（两处偷懒与下一步）

> 本节核对（一句话）：两处偷懒（重试不幂等→03、预算裸奔→04）与下一步（状态管理散乱→02）三条与后续迭代文件标题一一对应，无搁置项即 PASS。

## 五、全篇回归验证

> 各节断言已上移至 §二.1（Checkpoint 断点续跑最小闭环）；本表为整篇迭代的回归验收，不重复材料。

| # | 验收项（断言） | 标准 | 复验方式 |
|---|---------------|------|---------|
| 1 | 正常跑完 | 三步都执行，`usedTokens` 累计 | 复验：执行 §二.1 核对命令 |
| 2 | 崩溃续跑不重做 | 第 2 步后崩溃，重启从第 3 步续跑，1-2 不重跑 | §二.1 步骤2-3 |
| 3 | 结果一致性 | 恢复结果与不崩溃时一致 | §二.1 步骤3 |
| 4 | 预算字段就位 | `usedTokens` 可读且为正（为 04 铺路） | §二.1 步骤4 |
| 5 | 崩溃点泛化 | 任一步后崩溃均无重复执行 | §二.1 步骤5 |

**回归失败排查**：按 §二.1 失败排查逐条回溯（save 未每步落库/边界错位）。

## 六、验收对照

> 00-需求分析量化验收①（崩溃后断点续跑 ≥95%）在本迭代以单任务最小闭环首次收口。

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 崩溃续跑不重做 | 崩溃点后续跑、已完成步不重跑 | ✅（§二.1 步骤2-3） |
| 结果一致性 | 恢复结果与不崩溃一致 | ✅（§二.1 步骤3） |
| 预算字段就位 | Checkpoint 含 `usedTokens` 可读 | ✅（§二.1 步骤4） |
