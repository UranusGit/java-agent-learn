# 07-遗忘权与 Token 预算召回

> **定位**：让记忆中枢**合法（GDPR 被遗忘权）且省钱（召回 fit 进 Token 预算）**。核心：**① 用户删除记忆全链路清理（含向量/元数据/级联）② 强制/自定义保留期 ③ Token 预算内分层召回**。呼应 [58-历史记录持久化与合规](../../教程/04-企业级架构主干/05-历史记录持久化与合规.md)、[95-工业级记忆架构](../../教程/09-前沿专题/06-工业级记忆架构.md) §6-7、[27-成本治理](../../教程/04-企业级架构主干/07-成本治理与Token计量.md)。前置阅读：[06-演化衰减与冲突消解](06-演化衰减与冲突消解.md)。
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

### 一.1 本节核对（四问）

- [ ] 四问口径齐全；三条本迭代验收（全链路清 / 保留期 / 预算不爆窗）与 §二/§三/§四 一一对应
- [ ] 对应 00"量化验收④被遗忘权删除后全链路清干净、⑤召回 fit 预算不爆窗"

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

**一致性**：`@Transactional` 保证三处删除**同事务**，无"库里借删但向量残留"的合规漏洞；删除本身记录审计（呼应 [25-合规留存](../../教程/04-企业级架构主干/05-历史记录持久化与合规.md)）。

### 二.1 本节测试与验证（全链路遗忘权）

**前置条件**：`eraseUser(tenantId, userId)`（`@Transactional`，四步：`clear` 短 / `deleteByConversationId` 长 / `vectorStore.delete` / `auditLog`）已实现；被测用户已有分散在短/长/向量三层的数据。

**材料——删除后核对语句**：

```sql
-- 长期层该 user 已清
SELECT COUNT(*) FROM memory_store WHERE scope LIKE 'tenantA:%';
SELECT COUNT(*) FROM vector_store;
-- 审计留痕
SELECT * FROM audit_log WHERE action='ERASE';
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 触发 `eraseUser(tenantA, userX)`（该用户已有三层数据） | 短期 `clear`、长期 `deleteByConversationId`、向量 `delete`、元数据全部清空，无残留 |
| 2 | make 删除中途异常（如向量 delete 抛错） | 同事务回滚：短期/长期/向量均未被部分清空，无"库里删了向量残留"的合规漏洞 |
| 3 | 核对审计日志 | `audit_log` 有 `ERASE` 记录（who/ts），删除动作可追溯 |

**失败排查**：①残留→`@Transactional` 未覆盖三步删除，或 `vectorStore.delete` 的 filter 未带 tenant/user；②无审计→`auditLog.recordErase` 未落或未带 who/ts；③误删他用户→过滤条件未锁 tenant/user。

## 三、保留期策略

- **默认保留期**（如 180 天）后自动衰减归档（06 的归档存储，非删除）。
- **用户可要求删除**（GDPR）→ 立即全链路清除（上面）。
- **合规敏感**（如金融审批记忆）→ 强制更长保留（DOMAIN_POLICY 覆盖默认）。

### 三.1 本节核对（保留期策略）

- [ ] 三种保留策略（默认归档 / GDPR 立即删 / 覆盖强制长留）能复述，并说清各自触发方与去向（归档 vs 删除）
- [ ] "归档 ≠ 删除"的语义差异能说明（归档在系统侧降权、删除是用户合规主张）

## 四、Token 预算召回闸门

```java
// 概念代码：预算内分层召回(承接 02 窗口 + 04 混合)
public MemoryPacket recallBudgeted(String scope, String query, int tokenBudget) {
    List<Message> shortWin = trimToWindow(shortMem.get(conversationId), tokenBudget/3); // 短记忆占1/3
    int left = tokenBudget - estimateTokens(shortWin);
    List<MemoryRecord> longHit = hybridRecall(scope, query, budgetToTopK(left/2));       // 长期占 1/3
    int left2 = left - estimateTokens(longHit);
    List<Document> ragHit = vectorStore.similaritySearch(
            SearchRequest.builder().query(query).topK(budgetToTopK(left2)).build());   // RAG 占 1/3（Spring AI 2.0.0）
    return new MemoryPacket(shortWin, longHit, ragHit);
}
```

**要点**：三层记忆递归切分 Token 预算（呼应 [77-上下文工程](../../教程/08-架构师进阶/00-上下文工程.md) 的五层拼接 + [27-成本](../../教程/04-企业级架构主干/07-成本治理与Token计量.md)），**绝不因召回无限撑爆上下文**。

### 四.1 本节测试与验证（Token 预算召回闸门）

**前置条件**：`recallBudgeted(scope, query, tokenBudget)` 已实现（短 `trimToWindow` + 长 `hybridRecall` + RAG `similaritySearch` 递归切预算）；三层均可构造大数据量。

**材料——预算核对**（换大 budget 看是否超窗，无固定端点）：

```text
压入不同 tokenBudget：2048 / 4096 / 8192
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `recallBudgeted(scope, q, 2048)`（三层数据充足） | 返回 `MemoryPacket` 三层合计 `estimateTokens ≤ 2048`，绝不超过预算 |
| 2 | 逐步增大 budget（4096/8192） | 各层 `topK` 随之放大（预算→topK 联动），但仍 `≤ budget` |
| 3 | 极端：某一层单条就超预算 | 该层被截断/稀释，整体仍 fit 预算不爆窗 |

**失败排查**：①总超预算→`left`/`left2` 剩余预算未正确递减，或 `estimateTokens` 低估；②某一层把预算吃光→切分比例（预算/3 等）未覆盖或未对单条上限做保护。

## 五、全篇回归验证

> §二.1（遗忘权）、§三.1（保留期）、§四.1（预算召回）均通过后的整体验收。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 某用户请求遗忘权 | 短期/长期/向量/元数据全链路清，审计留痕（§二.1），对应 00 验收④ |
| 2 | 触发默认保留期衰减 / 敏感覆盖 | 归档而非删除；敏感记忆被 `DOMAIN_POLICY` 强制留存（§三.1） |
| 3 | 大数据量下 `recallBudgeted` | 严格 fit 预算不爆窗、topK 按预算联动（§四.1），对应 00 验收⑤ |

**回归失败排查**：任一步 FAIL 按 §二.1/§三.1/§四.1 排查项回溯（事务未覆盖 / 归档 vs 删除混淆 / 预算切分）。

> **下一步**：主体迭代（01-07）完成，记忆中枢已能：读写、窗口、持久化、语义召回、隔离、演化、遗忘、预算。08 起进入**进阶**：先做**记忆演化飞轮**（让记忆质量越跑越准）。
