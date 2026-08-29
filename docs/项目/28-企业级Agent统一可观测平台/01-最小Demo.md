# 01-最小 Demo：全链路 Span 聚合查询

> **定位**：用不到百行造出可观测中枢的最小骨架：**① 一次执行产生全链路 Span（LLM+工具+检索）② 按 traceId 聚合查出完整链路 ③ 渲染一条时间线**。验证三件事：Span 全、可聚合、人能读。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[附录 21](../../附录/18-可观测平台实践/00-OTel管道与gen_ai语义.md)。
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

### 一.1 本节核对（四问与迭代验收）

- [ ] 四问（新增需求/影响模块/架构演进/上版痛点）与"从无到有先证明链路能聚合成一条线"的定位一致
- [ ] 本迭代三条验收（4+ Span 落库 / traceId 查全链 / 时间线含耗时·状态）与 `## 二` 代码的能力对得上

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

### 二.1 本节测试与验证（全链路 Span 聚合）

**前置条件**：已接入 Observation 导出（span 落 SpanStore）；一次能产生 LLM+检索+工具调用的可执行用例。

**材料——核对命令**：

```bash
# 发起一次"带检索+2工具+LLM"的执行，随后按返回的 traceId/会话 ID 查询聚合
curl "http://localhost:8080/obs/trace?traceId=<上一步返回的 traceId>"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 触发一次带工具/检索的执行 | 落库 Span 数 ≥ 4（LLM + 2 工具 + 检索），kind 覆盖 LLM/TOOL/RETRIEVAL |
| 2 | 按该执行的 traceId 调用聚合查询 | 返回完整链路，节点按 startTs 升序，无缺段 |
| 3 | 抽查每个节点 | 含 durationMs、status、`gen_ai.*` 语义属性（可读） |
| 4 | 含异常的执行 | 失败节点 status 标记为 ERROR，其余正常节点不受影响 |

**失败排查**：①Span 数不足 → 命名/检索或工具未真正调用（核对用例是否真走全链路）；②查询乱序 → `sorted(comparing(Span::startTs))` 未生效（核对 Span 时间来源）；③节点缺属性 → Observation 端未导出 `gen_ai.*`，回查 02 管道/采集配置。

## 三、全篇回归验证

**回归断言**（`## 二.1` 本节测试通过后整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 连续触发两次不同变更的执行，各查其 traceId | 两条链路互不串扰，各自完整有序 |

**失败排查**：串号 → SpanStore 关联键用错（traceId 而非会话字段），核对采集时的关联字段。

> **下一步**：单链能查了，但**遥测散、含敏感 Prompt、量大**。02 迭代做 **OTel 管道**——脱敏/采样/多路导出（附录21 落地）。
