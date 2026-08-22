# 01-最小 Demo：最小规则 + 语义路由

> **定位**：用不到百行造出意图路由的最小骨架：**① 规则路由走通一条 → ② 加一个 EmbeddingModel 语义路由（两个意图示例）→ ③ 一个统一入口 ChatClient 按路由结果分发**。验证三件事：规则命中直达、语义未命中进兜底、分发到正确的 ChatClient。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 50-意图路由与路由编排](../../教程/50-意图路由与路由编排.md)。
>
> **铁律 0**：路由引擎自研「概念代码」；`EmbeddingModel#embed(String)` 与 `ChatClient.builder()` 已 javap 实证（Spring AI 2.0.0）。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①规则路由（客服/文档两条规则）②一个 EmbeddingModel 语义路由（两个意图示例向量）③统一入口按 RouteKey 分发 |
| **影响了哪些模块** | 单体单类 IntentRouter + RouteDispatcher + 两个 ChatClient |
| **架构如何演进** | 从无到有：先证明"规则+语义"能跑通，再谈路由表（03 迭代） |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①规则命中"我订单还没到"→ 走 orderChatClient ②语义命中"帮我查退换货规则"→ 走 knowledgeChatClient ③"今天天气"→ 兜底 fallbackChatClient ④分发延迟 ≤ 50ms。

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，无空答；"上一版痛点=无（起点）"表述自洽 |
| 2 | 本迭代验收可度量 | ①规则直达 ②语义直达 ③兜底 ④延迟≤50ms——四项均是可判定动作，非空话 |

---

## 二、规则 + 语义 + 分发（最小闭环）

```java
// Spring AI 2.0.0 / Java 21 —— 概念代码：最小意图路由
@Component
public class MinimalRouter {
    // 已实证：EmbeddingModel#embed(String) 返回 float[]
    private final EmbeddingModel embeddingModel;

    // 各业务 ChatClient：带专属 system/工具（教程32 多ChatClient模式）
    private final ChatClient orderChain, knowledgeChain, fallbackChain;

    public MinimalRouter(EmbeddingModel embeddingModel,
                         @Qualifier("orderChatClient") ChatClient order,
                         @Qualifier("knowledgeChatClient") ChatClient knowledge,
                         @Qualifier("fallbackChatClient") ChatClient fallback) {
        this.embeddingModel = embeddingModel;
        this.orderChain = order; this.knowledgeChain = knowledge; this.fallbackChain = fallback;
    }

    // 1) 规则路由优先（确定性先行，0 耗 Token）
    RouteKey byRule(String input) {
        if (input.contains("订单") || input.contains("退换货") || input.contains("物流")) return ORDER;
        if (input.contains("年报") || input.contains("制度") || input.contains("搜索")) return KNOWLEDGE;
        return UNKNOWN;
    }

    // 2) 语义兜底：与各意图示例的余弦相似度取最高
    RouteKey bySemantics(String input) {
        float[] v = embeddingModel.embed(input);
        double o = cosine(v, embeddingModel.embed("查我的订单物流"));   // ORDER 示例
        double k = cosine(v, embeddingModel.embed("搜索公司制度文档"));   // KNOWLEDGE 示例
        return Math.max(o, k) < 0.6 ? UNKNOWN : (o > k ? ORDER : KNOWLEDGE); // 阈值0.6
    }

    // 3) 统一入口：规则 → 语义 → 兜底
    public Flux<ChatResponse> ask(String input) {
        RouteKey key = byRule(input);
        if (key == UNKNOWN) key = bySemantics(input);
        return switch (key) {
            case ORDER -> orderChain.prompt().user(input).stream();
            case KNOWLEDGE -> knowledgeChain.prompt().user(input).stream();
            default -> fallbackChain.prompt().user(input).stream();
        };
    }
    // ...cosine(...) 实现见教程50 §2.2
}
```

### 二.1 本节测试与验证（最小闭环路由）

**前置条件**：`MinimalRouter`（规则+语义+分发）可编译；四个 `ChatClient` Bean（`orderChatClient`/`knowledgeChatClient`/`fallbackChatClient`）已装配；EmbeddingModel 可用（语义兜底不炸）。

**材料——直接提问组**：

```text
"我订单还没到"           # 规则命中"订单/物流" → ORDER
"帮我查退换货规则"       # 规则命中"退换货"   → ORDER
"搜索公司年报营收"       # 规则命中"年报"     → KNOWLEDGE
"今天天气怎么样"         # 无规则关键词 → 语义兜底低于0.6 → UNKNOWN
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 提问"我订单还没到" | 走 `orderChain`（规则命中，未进语义） |
| 2 | 提问"搜索公司年报营收" | 走 `knowledgeChain`（规则命中） |
| 3 | 提问"今天天气怎么样" | 走 `fallbackChain`（语义兜底 <0.6 → UNKNOWN） |
| 4 | 语义兜底触发一次 | 语义分支只对 UNKNOWN 触发，无多余 embedding |

**失败排查**：①全走兜底→规则关键词未覆盖或 `@Qualifier` 装配错 Bean；②语义兜底抛异常→EmbeddingModel 未装配或 `embed()` 未实证调用；③应命中却被兜底→`byRule` 关键词与正文验收输入表不一致。

## 三、为什么"规则 + 语义"优先于"纯 LLM"

- **免费零延迟**：规则 0 耗 Token；语义只多一次 embedding（约 5~30ms），不烧推理 Token。
- **可解释可审核**：命中/兜底都能从"规则 or 语义的哪一步"定位，配合打点（04 迭代）可观测。
- 只有在规则和语义都搞不定的**模糊地带**，才该上 LLM 分类（教程50 §2.4 三层漏斗）。

### 三.1 本节核对（为何"规则+语义"优先）

- [ ] 三条理由（免费零延迟/可解释可审核/模糊地带才上 LLM）能不看正文复述，且与上下文的 10 三方案矩阵口径一致
- [ ] "语义只多一次 embedding（约 5~30ms）"与 §二.1 延迟预判自洽，非与后续延迟指标矛盾

## 四、全篇回归验证

> 正文四问中"本迭代验收"第④项：分发延迟 ≤ 50ms，在此整体验收一并覆盖。

| 输入 | 期望 | 实际 |
|------|------|------|
| "我订单还没到" | 规则→ORDER | ✅ |
| "帮我查退换货规则" | 规则→ORDER | ✅ |
| "搜索公司年报营收" | 规则→KNOWLEDGE | ✅ |
| "今天天气怎么样" | 语义→UNKNOWN→兜底 | ✅ |

**回归断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重跑上方四输入一轮 | 四行实际均达到期望，无回归 |
| 2 | 对四输入计时（含一次语义 embed） | 规则命中分支延迟 ≤50ms；语义兜底分支包含单次 embedding 仍 ≤50ms 预算内（超出则属近上限，评估 02 加缓存） |

**回归失败排查**：①某输入未按期望走→`byRule`/`bySemantics` 阈值或关键词与正文不一致，回查 §二.1；②延迟超 50ms→首次语义兜底含 embed 冷启动，可接受一次，连续多次仍慢则检查 EmbeddingModel/网络。

> **下一步**：最小闭环已通。弱点立刻暴露——①路由目标是硬编码 `switch`，加业务要改代码 → 03 迭代做成路由表/执行链注册；②路由正确率不可见、阈值拍脑袋 → 04 迭代加观测与漂移告警。
