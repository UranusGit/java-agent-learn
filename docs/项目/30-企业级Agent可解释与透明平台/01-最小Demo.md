# 01-最小 Demo：轨迹→解释时间线最小投影

> **定位**：用不到百行造出可解释平台的最小骨架：**① 从 Trace 事件流读取一次执行 ② 投影成"人可读"解释时间线（模型步/动作步/证据步）**。验证两件事：Trace 能转解释、解释时间线人能看懂。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 56 §3](../../教程/56-Agent可解释与透明工程.md)。
>
> **铁律 0**：Trace 读取基于实证 Observation 体系（附录18）；投影逻辑自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①traceStore 读取（gen_ai/工具/检索 Span）②投影规则（Span→ 人可读 Step）③时间线渲染 |
| **影响了哪些模块** | 单体 Explainer + TraceReader |
| **架构如何演进** | 从无到有：先证明"机器 Trace 能变人话" |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①一次带检索+工具的执行能生成时间线 ②时间线按"模型/动作/证据"分类可读 ③生成 ≤3s（对照 00 验收②）。

## 二、最小投影

```java
// 概念代码：Trace → 解释时间线
@Component
public class Explainer {
    private final TraceReader traceReader;      // 读 Observation Span 流(实证基座)

    public ExplanationTimeline timeline(String runId) {
        return traceReader.events(runId).stream()
            .map(e -> switch (e) {
                case Retrieval r -> Step.evidence("检索: " + r.query(),
                        "命中 " + r.docs().size() + " 篇文档（" + topTitles(r.docs()) + "）");
                case ToolCall t  -> Step.action("调用工具 " + t.tool(),
                        digest(t.input()) + " → " + digest(t.output()));
                case LlmCall s   -> Step.model("模型推理", s.summary());
                default          -> Step.other(e.name());
            })
            .collect(ExplanationTimeline::of);
    }
}
```

## 三、为什么"投影"而不是"直接给 Trace"

Trace 面向机器（Span/属性/ID），解释面向**人**（做了什么/依据什么/结果怎样）——投影层做三件事：**分类**（证据/动作/推理）、**摘要**（输入输出 digest 而非全文）、**顺序叙事**（按时间讲清楚）。直接甩 Trace 给监管/用户等于没解释。

## 四、验收

| 输入 | 期望 |
|------|------|
| 一次 检索+2工具+3模型 调用 | 时间线 6 步全展示 |
| 检索步 | 显示查询词 + 命中文档标题 |
| 工具步 | 输入/输出摘要（非全文） |
| 生成延迟 | ≤3s |

> **下一步**：时间线能看懂"经历了什么"，但**结论依据什么**（归因）还没有。02 迭代工程化时间线，03 迭代做**证据归因**。
