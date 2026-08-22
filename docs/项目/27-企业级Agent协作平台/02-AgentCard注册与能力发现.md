# 02-AgentCard 注册与能力发现

> **定位**：把 01 的内存注册表工程化为**注册中心**：**① AgentCard 声明式注册/版本化 ② 心跳与健康（下线 Agent 不再被发现）③ 能力发现的匹配语义（精确/语义两级）**。呼应 [03-MCP网关注册中心](../../项目/03-MCP工具网关/00-需求分析与架构设计.md)、[教程 54 §2](../../教程/54-Agent间协作协议工程化.md)。前置阅读：[01-最小Demo](01-最小Demo.md)。
>
> **铁律 0**：AgentCard 结构为开放协议（无官方 SDK），注册中心自研「概念代码」；语义发现用实证 `EmbeddingModel#embed(String)`。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①注册/更新/撤销 AgentCard（版本化）②心跳与被动下线（不健康 Agent 不出现在发现结果）③能力发现两级匹配（精确 capability + 语义描述相似） |
| **影响了哪些模块** | AgentCardRegistry → 持久化注册中心；发现接口 → 精确+语义双路 |
| **架构如何演进** | 从"内存硬编码"演进为"可运维的注册中心" |
| **上一版痛点** | Agent 上线/下线注册表不知道；能力描述千变万化精确匹配漏 |

**本迭代验收**：①Agent 注册/撤销/版本更新生效 ②心跳超时的 Agent 不被发现 ③同义能力描述（"出报表"↔"生成经营报告"）语义发现命中。

## 二、AgentCard 注册模型（版本化 + 心跳）

```java
// 概念代码：注册中心核心模型
public record RegisteredCard(
        AgentCard card, int version,                 // 版本化：能力变更可追溯
        long lastHeartbeatMs, CardStatus status) {}  // ACTIVE / DEGRADED / OFFLINE

// 心跳：Agent 定期上报；超时标记 OFFLINE(不再出现在发现结果)
@Scheduled(fixedDelay = 10_000)
void sweepOffline() {
    long now = System.currentTimeMillis();
    cards.values().stream()
        .filter(c -> now - c.lastHeartbeatMs() > OFFLINE_AFTER_MS)
        .forEach(c -> cards.put(c.card().name(), c.withStatus(OFFLINE)));
}
```

**要点**：**被动下线优于显式注销**——Agent 崩溃不会发注销请求，只能靠心跳超时摘除（服务治理通例）。

## 三、能力发现两级匹配

```java
// 概念代码：精确 + 语义两级发现
public List<AgentCard> discover(String need) {
    // 一级：精确能力名命中(快,0成本)
    var exact = cards.values().stream()
        .filter(c -> c.status()==ACTIVE && c.card().capabilities().contains(need)).toList();
    if (!exact.isEmpty()) return exact;
    // 二级：语义匹配——需求描述 vs AgentCard.description 嵌入相似度(实证 embed)
    float[] q = embeddingModel.embed(need);
    return cards.values().stream()
        .filter(c -> c.status()==ACTIVE)
        .sorted(Comparator.comparingDouble(
            (RegisteredCard c) -> -cosine(q, cardVec(c)))).limit(3).toList();
}
```

**两级取舍**：精确优先（确定性、零成本），语义兜底"能力名对不上但描述相符"的长尾——与 [23-意图路由网关的三层漏斗](../../项目/23-企业级Agent意图路由网关/00-需求分析与架构设计.md) 同一方法论：**确定性先行、语义承接**。

## 四、验收

| 测试 | 期望 |
|------|------|
| 注册→发现 | 新 Agent 可被发现 |
| 心跳超时 | OFFLINE，不出现 |
| 能力版本更新 | 发现返回新版本 Card |
| "出报表"语义查询 | 命中"生成经营报告" Agent |

> **下一步**：Agent 可被发现了，但 01 的**同步委托**撑不住真实长任务。03 迭代做**任务委托与协商**——委托先谈妥"能做/多贵/多久"再执行，支持异步长任务。
