# 01-最小 Demo：全链路 Span 聚合查询

> **定位**：用不到百行造出可观测中枢的最小骨架：**① 一次执行产生全链路 Span（LLM+工具+检索）② 按 traceId 聚合查出完整链路 ③ 渲染一条时间线**。验证三件事：Span 全、可聚合、人能读。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[附录 21](../../附录/21-可观测平台实践/00-OTel管道与gen_ai语义.md)。
>
> **铁律 0**：Span 产生=实证 Observation（Spring AI 自动 + 自定义扩展）；聚合查询自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①SpanStore（接收 Observation 导出）②traceId 聚合查询 ③链路时间线渲染 |
| **影响了哪些模块** | 单体 ObsQuery + SpanStore |
| **架构如何演进** | 从无到有：先证明"链路能聚合成一条线" |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①一次 检索+2工具+LLM 执行 → 4+ Span 落库 ②traceId 查询返回完整链路 ③时间线含耗时/状态。

## 二、最小聚合查询

```java
// 概念代码：全链路聚合
@Component
public class ObsQuery {
    private final SpanStore store;      // Observation → OTel → 存储

    public TraceTimeline trace(String traceId) {
        return store.byTraceId(traceId).stream()
            .sorted(comparing(Span::startTs))
            .map(s -> TimelineNode.of(s.name(),
                    s.durationMs(),
                    s.kind(),            // LLM / TOOL / RETRIEVAL / HTTP
                    s.status(),
                    s.attributes()))     // gen_ai.* 语义属性
            .collect(TraceTimeline::new);
    }
}
```

## 三、验收

| 输入 | 期望 |
|------|------|
| 一次带工具执行 | LLM/工具 Span 齐 |
| traceId 查询 | 时间线完整有序 |
| 每节点 | 耗时+状态+gen_ai 属性可见 |

> **下一步**：单链能查了，但**遥测散、含敏感 Prompt、量大**。02 迭代做 **OTel 管道**——脱敏/采样/多路导出（附录21 落地）。
