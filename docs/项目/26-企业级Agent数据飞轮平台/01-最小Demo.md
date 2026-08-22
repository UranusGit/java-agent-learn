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

## 三、最小版的两处"偷懒"（后续补）

1. **单业务单源**：只有手工差例——多源接入/清洗/标注分级在 02 补。
2. **无防污染**：差例可能是恶意的——07 补免疫系统。

## 四、验收

| 输入 | 期望 |
|------|------|
| 提交 3 条差例 | 入湖带血缘 |
| 修复版 Prompt | 差例转好、金标不回归 → 放行 10% |
| 修复引入回归 | 灰度拦截 |
| 改进项 | 可查（caseId→patch→verdict 链） |

> **下一步**：四环能转但采集只有手工单源。02 迭代做**采集管道**——多源接入/清洗/标注/来源分级。
