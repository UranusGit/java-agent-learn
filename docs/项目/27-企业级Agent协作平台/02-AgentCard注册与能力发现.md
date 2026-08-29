# 02-AgentCard 注册与能力发现

> **定位**：把 01 的内存注册表工程化为**注册中心**：**① AgentCard 声明式注册/版本化 ② 心跳与健康（下线 Agent 不再被发现）③ 能力发现的匹配语义（精确/语义两级）**。呼应 [03-MCP网关注册中心](../../项目/03-MCP工具网关/00-需求分析与架构设计.md)、[教程 06-TraceId全链路追踪/08-存储工程：span数据落到哪怎么存怎么查 §2](../../教程/09-前沿专题/08-Agent间协作协议工程化.md)。前置阅读：[01-最小Demo](01-最小Demo.md)。
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

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，痛点（上线下线不知道/精确匹配漏）与需求对应 |
| 2 | 本迭代验收可度量 | ①注册/撤销/版本 ②心跳下线 ③语义命中，三项均可判定 |

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

### 二.1 本节测试与验证（注册模型与心跳下线）

**前置条件**：`RegisteredCard` / `sweepOffline()` 可编译运行；可手工驱动 heartbeat（或缩短 `OFFLINE_AFTER_MS` 便于观察）；一个管理入口可做注册/撤销/改版本。

**材料——核对状态**：注册卡片状态枚举 `ACTIVE / DEGRADED / OFFLINE`；下线阈值参数 `OFFLINE_AFTER_MS`。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 注册新 Agent 后查询 | 状态 ACTIVE，可被发现 |
| 2 | 撤销 Agent 后查询 | 不出现在发现结果 |
| 3 | 更新能力后查询 | 返回新版本 Card（version+1，能力变更可追溯） |
| 4 | 停止心跳超过 `OFFLINE_AFTER_MS` 后触发 sweep | 状态转 OFFLINE，不再出现在发现结果 |

**失败排查**：①取消后仍被发现→状态未落 OFFLINE 或发现查询未过滤 status；②心跳超时不下线→sweep 未周期性触发或比较用的是活跃时间而非最后心跳时间；③版本不变→更新未自增 `version`。

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

### 三.1 本节测试与验证（两级能力发现）

**前置条件**：`EmbeddingModel`（实证 `embed(String)`）可用；同一 Agent 的 AgentCard 能力名 `report.generate`、description「生成经营报告」。

**材料——语义对案例**：查询描述"出报表"→ 应与「生成经营报告」description 高相似（语义兜底），能力名不含 `出报表`。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `discover("report.generate")`（精确命中） | 直接返回，不触发 embedding（低成本路径） |
| 2 | `discover("出报表")`（精确 miss） | 走语义匹配，命中"生成经营报告" Agent，返回 top 命中 |
| 3 | 语义查询返回列表 | 按相似度降序，limit=3，只含 status=ACTIVE |
| 4 | 对比精确 vs 语义两路 | 精确优先：能力名一致时不依赖 embedding 判定 |

**失败排查**：①精确命中还走语义→未 `if (!exact.isEmpty()) return exact;`；②"出报表"查不到→embedding 相似度阈值/维度配置问题或 description 未参与嵌入；③语义结果含 OFFLINE→语义分支漏过滤 status。

## 四、全篇回归验证

> §二.1（注册/心跳）与 §三.1（两级发现）均通过后的整体验收。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 注册 Agent → 精确 + 语义各发现一次 | 两步都命中；新 Agent 立即可见 |
| 2 | 心跳超时置 OFFLINE → 再精确/语义发现 | 均不出现（两路都过滤下线） |
| 3 | 版本更新 → 发现返回新版本 | 精确与语义两路都返回最新 version Card，能力变更可追溯 |

**回归失败排查**：任一步 FAIL 按 §二.1/§三.1 排查项回溯（状态过滤 / 版本自增 / embedding 维度）；目标：对照 00 验收①「发现 top 匹配正确率 ≥95%」。

> **下一步**：Agent 可被发现了，但 01 的**同步委托**撑不住真实长任务。03 迭代做**任务委托与协商**——委托先谈妥"能做/多贵/多久"再执行，支持异步长任务。
