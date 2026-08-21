# 09-多 Agent 共享记忆

> **定位**：让多个 Agent **共享记忆、分工记忆、不共享一切**。核心：**① 记忆可见域（私有/团队/全局 三级）② 跨 Agent 记忆引用与借读 ③ 共享记忆的冲突与权限**。呼应 [09-多Agent协作](../../教程/09-多Agent协作.md)、[02-多Agent协作平台](../../项目/02-多Agent协作平台/00-需求分析与架构设计.md)、[05-作用域隔离](05-作用域隔离与多租户.md)。前置阅读：[08-记忆演化飞轮](08-记忆演化飞轮.md)。
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

## 四、共享记忆的冲突

多个 Agent 共享记忆被不同 Agent 写入，可能冲突（呼应 06 冲突消解，这里是跨 Agent 维度）：

- **同域覆盖**：同 team 多人写同主题 → 由 06 时间/score 消解。
- **权限边界**：只读域被写 → 拒绝。
- **协作完整性**：跨 Agent 引用断裂（owner 删记忆）→ 借读方得到"已失效"标记（不静默错误）。

## 五、验收

| 测试 | 期望 |
|------|------|
| 三级可见 | 私有/团队/全局读范围正确 |
| 借读 | 有权限只读，无权限拒绝 |
| 越权借读 | 拦 + 审计 |
| 跨Agent冲突 | 06 消解 + 权限边界 |

> **下一步**：记忆能跨 Agent 共享了，但**读写性能和审计**还没到位。10 进阶做**记忆审计 + 缓存提速**——可观测与性能。
