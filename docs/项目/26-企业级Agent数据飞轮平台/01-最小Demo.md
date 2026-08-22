# 01-最小 Demo：单业务四环最小闭环

> **定位**：用不到百行造出飞轮的最小骨架：**① 采一条差例 ② 跑一次新旧对比评估 ③ 生成一个 Prompt 修复项 ④ 模拟灰度放行**——四环各转一格。验证四件事：数据能进、评估能比、修复能出、灰度能守。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 53](../../教程/53-企业级数据飞轮闭环.md)。
>
> **铁律 0**：评估用实证 `entity()` 判分思想；管道自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①差例采集（手工提交）②双版本对比评估（旧 vs 修复版 Prompt）③改进项生成 ④模拟灰度判定 |
| **影响了哪些模块** | 单体 FlywheelLoop + 内存存储 |
| **架构如何演进** | 从无到有：先证明四环能咬合 |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①差例入库 ②修复版在差例上胜出、金标上不回归 ③改进项带血缘 ④灰度判定输出 放行/拦截。

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点（无，起点）四行均有且自洽 |
| 2 | 四项验收可判定 | 差例入库/修复版胜出不回归/改进项带血缘/灰度判定放行拦截——均是可观察动作 |

## 二、四环最小闭环

```java
// 概念代码：飞轮最小循环
@Component
public class FlywheelLoop {
    // ①采集环: 差例入湖(带最小血缘)
    public String ingest(DiffCase c) {
        String id = lake.save(c.withMeta(Map.of("agentVersion", c.agentVersion(),
                "promptVersion", c.promptVersion())));   // 最小血缘
        return id;
    }

    // ②评估环: 新旧 Prompt 在 差例集+金标 上对比
    public Verdict evaluate(List<DiffCase> cases, PromptPatch candidate) {
        int fixed = runOn(cases, candidate).passedCount();        // 修复差例数
        int regressed = runOn(goldenSet(), candidate).failedCount(); // 金标回归数
        return new Verdict(fixed, regressed, fixed > 0 && regressed == 0);
    }

    // ③④优化+灰度环: 改进项注册 → 灰度守门
    public ReleaseDecision close(String caseId, PromptPatch patch, Verdict v) {
        var item = registry.register(ImprovementItem.of(caseId, patch, v));  // 带血缘
        return v.ok() ? ReleaseDecision.CANARY_10PCT : ReleaseDecision.BLOCKED;
    }
}
```

### 二.1 本节测试与验证（四环最小闭环）

**前置条件**：`FlywheelLoop`+内存存储（lake/registry）可编译运行；测试差例与金标样例就绪。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 提交 1 条差例（带 agentVersion/promptVersion） | `ingest` 返回非空 id，`lake` 中数据带最小血缘 |
| 2 | 用修复版 Prompt 对差例集+金标集跑 `evaluate` | 差例修复数 >0 且金标回归数 =0 → `Verdict.ok()` 为真 |
| 3 | `close()` 对 `v.ok()` 的改进项 | 返回 `CANARY_10PCT`，`registry` 中改进项带 caseId→patch→verdict 链 |
| 4 | 构造修复引入金标回归的 Verdict（`regressed>0`） | 返回 `BLOCKED`，不放行 |

**失败排查**：①`Verdict.ok()` 恒真→`runOn` 金标判断未按"修复差例数>0 && 金标回归=0"编码；②改进项查不到链→`registry.register` 未保存 verdict 字段；③入库无血缘→`ingest` 未写 agentVersion/promptVersion 到 meta。

## 三、最小版的两处"偷懒"（后续补）

1. **单业务单源**：只有手工差例——多源接入/清洗/标注分级在 02 补。
2. **无防污染**：差例可能是恶意的——07 补免疫系统。

### 三.1 本节核对（两处偷懒）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 偷懒点→后续迭代 | 单业务单源→02 采集管道；无防污染→07 免疫，两处偷懒都有后续对应，无搁置 |
| 2 | 边界明确 | 最小版刻意不做多源与免疫，与"最小闭环能转"的定位一致 |

## 四、全篇回归验证

> 回归断言在 §二.1（四环闭环）通过后，整体验收四件事是否全部成立：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 提交 3 条差例 | 入湖带血缘 |
| 2 | 修复版 Prompt 评估 | 差例转好、金标不回归 → 放行 10% |
| 3 | 修复引入回归的场景 | 灰度拦截 |
| 4 | 查改进项 | 可查（caseId→patch→verdict 链） |

**回归失败排查**：任一步 FAIL 按 §二.1 对应排查项回溯（Verdict 判定 / registry 血缘链）。

> **下一步**：四环能转但采集只有手工单源。02 迭代做**采集管道**——多源接入/清洗/标注/来源分级。
