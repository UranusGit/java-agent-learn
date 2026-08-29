# 09-多 Agent 共享记忆

> **定位**：让多个 Agent **共享记忆、分工记忆、不共享一切**。核心：**① 记忆可见域（私有/团队/全局 三级）② 跨 Agent 记忆引用与借读 ③ 共享记忆的冲突与权限**。呼应 [09-多Agent协作](../../教程/00-基础与核心/09-多Agent协作.md)、[02-多Agent协作平台](../../项目/02-多Agent协作平台/00-需求分析与架构设计.md)、[05-作用域隔离](05-作用域隔离与多租户.md)。前置阅读：[08-记忆演化飞轮](08-记忆演化飞轮.md)。
>
> **铁律 0**：可见域/共享机制在 05 的 scope 之上自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①记忆可见域（私有 agent / 团队 team / 全局 global）②跨 Agent 借读（有权限的只读引用）③共享记忆权限 + 冲突处理 |
| **影响了哪些模块** | scope → 扩展可见域；共享读 / 借读路由；新增 共享权限 |
| **架构如何演进** | 记忆从"单 Agent 隔离"演进为"可共享、分级可见" |
| **上一版痛点** | 05 只有严格隔离；Agent 间要协作记忆时无通道 |

**本迭代验收**：①私有/团队/全局三级可见 ②Agent B 可借读 Agent A 开放的记忆（只读）③越权借读被拦 + 审计。

### 一.1 本节核对（四问）

- [ ] 四问口径齐全；三条本迭代验收（三级可见 / 借读只读 / 越权拦截+审计）与 §二/§三/§四 一一对应
- [ ] 能说明与 05"写隔离"的分工（05 管写、09 管读开放，写默认私有读按需开放）

## 二、记忆可见域（三级）

```java
// 概念代码：可见域模型（在 05 scope 上扩展）
enum Visibility { PRIVATE, TEAM, GLOBAL }     // 私有/团队/全局

record MemoryObject(String id, MemoryScope owner,
                    String teamId /*null=PRIVATE*/, boolean globalPublic,
                    Visibility visibility) {}
// 可见域判定：GLOBAL 所有人 / TEAM 同 team / PRIVATE 仅 owner
```

**要点**：05 的 scope 管"写隔离"，可见域管"读开放"——**写默认私有，读按需开放**，天然满足"分工记忆、不共享一切"。

### 二.1 本节测试与验证（记忆可见域三级）

**前置条件**：`Visibility`（PRIVATE/TEAM/GLOBAL）与 `MemoryObject`（owner + teamId + globalPublic）判定已实现；构造分别属私有/团队/全局的记录。

**材料——可见域核对**（构造三级记录后按不同请求者读取，直查权限判定，无独立端点）：

```text
记录 M1=PRIVATE(owner A)  M2=TEAM(team t1)  M3=GLOBAL
请求者：A（同 team t1 的 B、全局请求者 C）
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | A 读 M1 | 可见（PRIVATE 仅 owner） |
| 2 | B（同 team t1）读 M2 | 可见（TEAM 同 team）；B 读 M1 → 不可见 |
| 3 | C 读 M3 | 可见（GLOBAL 所有人）；C 读 M2 → 不可见 |
| 4 | 写进入口 | 写默认私有，不自动对外开读（读开放按需显式设置） |

**失败排查**：①私有被他人读→`PRIVATE` 判定未锁定 owner；②团队跨 team 泄漏→`teamId` 比对错误；③全局误开→`globalPublic` 判定未限制。

## 三、跨 Agent 借读（只读引用）

```java
// 概念代码：借读（有权限的只读，不改原主人记忆）
public List<MemoryRecord> borrow(MemoryScope requester, String targetRecordId) {
    var obj = memoryStore.findById(targetRecordId);
    if (!canRead(requester, obj)) throw new AccessDenied("borrow denied");  // 权限校验
    return List.of(obj.asReadonly());          // 只读拷贝（不落写，不影响 owner 演化）
}
```

- **借读 vs 拷贝**：借读是"实时引用"（owner 更新，借读方看到新值）；高价值可**授权拷贝**（本地副本）。默认借读避免同步问题（呼应 [多 Agent 协作的引用语义](../../项目/02-多Agent协作平台/03-多Agent编排.md)）。

### 三.1 本节测试与验证（跨 Agent 借读）

**前置条件**：`borrow(requester, targetRecordId)` 已实现（`canRead` 校验 + `asReadonly` 返回）；存在有权限与无权限两类请求者。

**材料——借读核对**（构造开放给 B 的记录 M，分别由 A/B 借读）：

```text
记录 M（owner=A，开放给 TEAM，B∈同 team 且有权限）
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | B 借读 M（有权限） | 返回只读引用；后续 B 写该引用 → 被拒，不影响 owner 数据与演化（`asReadonly` 生效） |
| 2 | 无权限请求者 C（非 team、无 GLOBAL）借读 M | 抛 `AccessDenied`（越权借读被拦）并落审计 |
| 3 | `findById` 不存在的 targetRecordId | 返回空/异常 handled，不静默给错数据 |

**失败排查**：①无权限也能读→`canRead` 校验缺失或未覆盖 TEAM/owner；②借读可改原记忆→返回的引用未 `asReadonly` 包裹；③越权不审计→`AccessDenied` 处未记审计。

## 四、共享记忆的冲突

多个 Agent 共享记忆被不同 Agent 写入，可能冲突（呼应 06 冲突消解，这里是跨 Agent 维度）：

- **同域覆盖**：同 team 多人写同主题 → 由 06 时间/score 消解。
- **权限边界**：只读域被写 → 拒绝。
- **协作完整性**：跨 Agent 引用断裂（owner 删记忆）→ 借读方得到"已失效"标记（不静默错误）。

### 四.1 本节核对（共享记忆的冲突）

- [ ] 三类共享冲突（同域覆盖 / 权限边界 / 引用断裂）能复述，并说明各自处理手段（06 消解 / 拒绝 / 失效标记）
- [ ] "引用断裂不静默错误"的设计意图能说出（借读方需感知 owner 删除，避免读到脏引用）

## 五、全篇回归验证

> §二.1（可见域）、§三.1（借读）、§四.1（冲突）均通过后的整体验收。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 构造私有/团队/全局记录，A/B/C 分别读 | 读范围与三级可见一致、越权被拦+审计（§二.1/§三.1） |
| 2 | B 借读 A 开放记忆 | 只读可读，写被拒，不影响 owner 演化（§三.1） |
| 3 | 多 Agent 同主题写入 + owner 删除被借读记录 | 同域冲突按 06 消解；借读方得到"已失效"标记（§四.1） |

**回归失败排查**：任一步 FAIL 按 §二.1/§三.1/§四.1 排查项回溯（可见域判定 / 只读保护 / 权限边界）。

> **下一步**：记忆能跨 Agent 共享了，但**读写性能和审计**还没到位。10 进阶做**记忆审计 + 缓存提速**——可观测与性能。
