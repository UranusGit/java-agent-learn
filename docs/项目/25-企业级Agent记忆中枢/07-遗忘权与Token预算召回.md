# 07-遗忘权与 Token 预算召回

> **定位**：让记忆中枢**合法（GDPR 被遗忘权）且省钱（召回 fit 进 Token 预算）**。核心：**① 用户删除记忆全链路清理（含向量/元数据/级联）② 强制/自定义保留期 ③ Token 预算内分层召回**。呼应 [25-历史记录持久化与合规](../../教程/25-历史记录持久化与合规.md)、[52-工业级记忆架构](../../教程/52-工业级记忆架构.md) §6-7、[27-成本治理](../../教程/27-成本治理与Token计量.md)。前置阅读：[06-演化衰减与冲突消解](06-演化衰减与冲突消解.md)。
>
> **铁律 0**：删除用实证 `ChatMemory.clear(String)` / `ChatMemoryRepository.deleteByConversationId`；级联清理自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①用户级 PII 全链路删除（短期/长期/向量/元数据）②保留期策略（默认/自定义）③Token 预算召回闸门 |
| **影响了哪些模块** | MemoryHub → 回滚服务；召回 → 预算闸门；新增 合规清理器 |
| **架构如何演进** | 记忆从"存得进查得出"演进为"合规可删、召回可控" |
| **上一版痛点** | 用户要求删记忆时删不干净（向量残留）；召回无限可能超 Token |

**本迭代验收**：①删除某用户记忆 → 短期/长期/向量/元数据全清 ②支持保留期 ③召回严格 fit 预算不爆窗。

## 二、全链路遗忘权

```java
// 概念代码：级联删除（用户请求被遗忘权时的全链路清理）
@Transactional
public void eraseUser(String tenantId, String userId) {
    for (String convId : findConvsOfUser(tenantId, userId)) {
        shortMem.clear(convId);                                // ①官方短记忆(实证 clear)
        longRepository.deleteByConversationId(convId);         // ②官方接口(实证)
        vectorStore.delete(filter.tenant(tenantId).user(userId)); // ③向量索引(级联)
    }
    auditLog.recordErase(tenantId, userId, now());             // ④删操作审计
}
```

**一致性**：`@Transactional` 保证三处删除**同事务**，无"库里借删但向量残留"的合规漏洞；删除本身记录审计（呼应 [25-合规留存](../../教程/25-历史记录持久化与合规.md)）。

## 三、保留期策略

- **默认保留期**（如 180 天）后自动衰减归档（06 的归档存储，非删除）。
- **用户可要求删除**（GDPR）→ 立即全链路清除（上面）。
- **合规敏感**（如金融审批记忆）→ 强制更长保留（DOMAIN_POLICY 覆盖默认）。

## 四、Token 预算召回闸门

```java
// 概念代码：预算内分层召回(承接 02 窗口 + 04 混合)
public MemoryPacket recallBudgeted(String scope, String query, int tokenBudget) {
    List<Message> shortWin = trimToWindow(shortMem.get(conversationId), tokenBudget/3); // 短记忆占1/3
    int left = tokenBudget - estimateTokens(shortWin);
    List<MemoryRecord> longHit = hybridRecall(scope, query, budgetToTopK(left/2));       // 长期占 1/3
    int left2 = left - estimateTokens(longHit);
    List<Document> ragHit = vectorStore.similaritySearch(query, budgetToTopK(left2));    // RAG 占 1/3
    return new MemoryPacket(shortWin, longHit, ragHit);
}
```

**要点**：三层记忆递归切分 Token 预算（呼应 [34-上下文工程](../../教程/34-上下文工程.md) 的五层拼接 + [27-成本](../../教程/27-成本治理与Token计量.md)），**绝不因召回无限撑爆上下文**。

## 五、验收

| 测试 | 期望 |
|------|------|
| 删除某用户 | 短期/长期/向量/元数据全清 |
| 删除审计 | 记录何人何时删 |
| 保留期 | 默认期后归档，敏感强制留存 |
| 预算召回 | fit 预算，不撑爆 |

> **下一步**：主体迭代（01-07）完成，记忆中枢已能：读写、窗口、持久化、语义召回、隔离、演化、遗忘、预算。08 起进入**进阶**：先做**记忆演化飞轮**（让记忆质量越跑越准）。
