# 07-迭代六：IPC 与命名空间——Agent 间通信 / 租户隔离 / 共享黑板

> **定位**：完成内核六大机制的最后一块：**IPC**（进程间通信——管道 pipe/共享黑板 blackboard/信箱 mailbox 三形态）与**命名空间**（ns——租户视图隔离：同租户进程互相可见、跨租户不可见；配额组与 ns 绑定）。读者画像：要多 Agent 协作又要硬隔离的读者。前置阅读：[06-迭代五-中断与信号系统](06-迭代五-中断与信号系统.md)、[教程 09-多Agent协作]、[教程 26-多租户隔离与资源治理]。
>
> **铁律 0**：SEND_MSG 经 03 syscall；机制自研。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① IPC 三形态（pipe 点对点/mailbox 异步信箱/blackboard 共享黑板）② 命名空间（进程/记忆/工具/IPC 四类 ns 按租户隔离）③ 跨 ns 边界=显式网关（租户间仅经受控通道）④ IPC 与信号联动（消息到达=SIG_MSG 唤醒） |
| **影响了哪些模块** | `ipc-ns`（通道/ns 注册表）；SEND_MSG syscall 实现；memory-fs/工具表挂 ns 维度 |
| **架构如何演进** | 单进程孤岛 → 同 ns 协作 + 跨 ns 隔离 |
| **上一版痛点** | 进程无法协作、租户边界靠散落的过滤（06 §七） |

**本迭代验收**：① pipe 双工通信+背压 ② 跨租户 SEND_MSG EPERM ③ 黑板变更通知订阅者 ≤50ms ④ 同 ns 协作任务（主管+两 worker）跑通。

---

## 二、IPC 三形态

```mermaid
graph TB
    subgraph ipc["ipc-ns（同命名空间内）"]
        direction TB
        P["pipe（点对点）<br/>父子进程流式双工<br/>（02 父子天然配对）"]
        MB["mailbox（异步信箱）<br/>每进程收件箱<br/>发送即返回,接收阻塞"]
        BB["blackboard（共享黑板）<br/>ns 级 KV+订阅<br/>多 Agent 共享任务状态"]
    end
    A["Agent A"] --> P
    B["Agent B"] --> P
    A --> MB
    C["Agent C"] -->|"publish/subscribe"| BB
    B --> BB

    style BB fill:#c8e6c9
```

| 形态 | 适用 | 背压/可靠性 |
|------|------|------------|
| pipe | 父子流式（主管↔worker） | 有界缓冲，满则阻塞发送 |
| mailbox | 异步任务分发 | 持久信箱（进程挂起不丢信）+ SIG_MSG 唤醒 |
| blackboard | 群体协作状态（[教程 09] 黑板模式） | 变更日志（append-only）+ 订阅通知 |

```java
package com.example.kernel.ipc;

import java.util.concurrent.*;

/** 黑板——ns 级共享 KV + 订阅通知。 */
public class Blackboard {

    private final ConcurrentMap<String, String> entries = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Subscriber>> subs = new ConcurrentHashMap<>();

    public void put(String key, String value, String ns) {
        entries.put(nsKey(ns, key), value);
        subs.getOrDefault(nsKey(ns, key), new CopyOnWriteArrayList<>())
            .forEach(s -> s.onChange(key, value));   // 通知 → 触发 SIG_MSG 唤醒订阅进程
    }

    public String get(String key, String ns) { return entries.get(nsKey(ns, key)); }

    private String nsKey(String ns, String key) { return ns + ":" + key; }

    public interface Subscriber { void onChange(String key, String value); }
}
```

## 三、命名空间（四类隔离）

```mermaid
flowchart TB
    subgraph nsA["ns: tenant-A"]
        PA1["进程(只可见同ns)"]
        MA1["memory /mem/.../A/**"]
        TA1["工具表(A 授权)"]
        BA1["blackboard-A"]
    end
    subgraph nsB["ns: tenant-B"]
        PB1["进程"]
        MB1["memory-B"]
        TB1["工具表(B)"]
    end
    GW["跨 ns 网关<br/>（唯一受控通道:显式契约+审计）"]
    nsA -.-> GW
    nsB -.-> GW

    style GW fill:#ffcdd2
```

| ns 类型 | 隔离对象 | 实现 |
|---------|---------|------|
| pid ns | 进程可见性（/proc 只见同租户） | 进程表挂 ns 过滤 |
| memory ns | memory-fs 路径前缀（04 已有 scope） | `/mem/*/tenant/**` 强制前缀 |
| tool ns | 工具表按租户授权子集 | 能力表 ∩ 租户工具集 |
| ipc ns | pipe/信箱/黑板按租户分域 | 通道名带 ns 校验 |

```java
// SEND_MSG syscall 实现：先查同 ns → 跨 ns 走网关（契约注册+审计）→ 未注册 EPERM
```

## 四、多 Agent 协作示例（同 ns）

```bash
# 主管进程 spawn 检索/分析两个 worker（02 父子）
# 主管把任务写黑板 → worker 订阅唤醒 → 完成写回 + 信箱通知主管 → 主管 wait 收结果聚合
# 全程：进程模型(02) + syscall(03) + 黑板/信箱(本迭代) + 信号(06) 六机制齐用
```

## 五、测试与验证

```bash
# 1. pipe：父子流式传结果，有界缓冲满时发送阻塞（背压生效）
# 2. 隔离：租户A进程 SEND_MSG 到租户B → EPERM；跨 ns 网关注册后可通且审计留痕
# 3. 黑板：put → 订阅者 ≤50ms 收到通知并唤醒
# 4. 端到端：主管+两 worker 协作任务（黑板+信箱+wait）完整跑通
```

## 六、本迭代痛点（内核完成度自评）

六大机制齐了，但关键代码散在各篇 → 08 核心代码讲解统一复盘。

## 七、验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| IPC 三形态 | pipe/mailbox/blackboard | ✅ |
| ns 四类隔离 | 跨 ns EPERM+网关 | ✅ |
| 通知延迟 | 黑板 ≤50ms | ✅ |
| 协作端到端 | 主管+worker 跑通 | ✅ |

**下一篇**：[11-核心代码讲解](11-核心代码讲解.md)。
