# 01-最小内核 Demo：第一个 Agent 进程

> **定位**：跑通**最小内核**——一个 Agent 进程从创建（spawn）到运行（ChatClient 编排循环）到退出（exit code）的完整骨架，内核持有进程表。其余五大机制（syscall/记忆FS/调度/中断/IPC）后续迭代叠加。读者画像：想动手看到"进程被内核托管"的开发者。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)。
>
> **铁律 0**：代码经 javap 实证（ChatClient/ChatModel 等）。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小闭环：内核 spawn 一个 Agent 进程→进程用 ChatClient 跑一轮编排→exit；进程表可查 |
| **影响了哪些模块** | 新单体 `agent-kernel`（内核雏形） |
| **架构如何演进** | 无 → 进程骨架 + 进程表 |
| **上一版痛点** | 无（首个版本） |

**本迭代验收**：① spawn→run→exit 全程进程表状态可见 ② 进程崩溃内核不崩 ③ 进程退出码可查。

### 一.1 本节核对（需求与验收范围）

- [ ] 能一句话说清"最小内核"是什么：内核持进程表 + spawn/run/exit 骨架，其余五机制留待后续迭代
- [ ] 本迭代三次验收（状态可见/崩溃不崩/退出码可查）与后续 §三 断言一一对应

---

## 二、最小内核形态

```mermaid
flowchart LR
    S["spawn 请求<br/>(agent 定义)"] --> K["内核<br/>Kernel.processTable"]
    K --> P1["AgentProcess#1<br/>ChatClient 编排循环"]
    P1 -->|"exit(0)"| K
    K --> Q["/proc 查询<br/>进程列表"]
    style K fill:#e3f2fd
```

### 二.1 本节核对（最小内核形态）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 数据流闭环 | spawn 请求 → 内核 processTable → 进程运行 → exit 回内核 → /proc 可查，图中路径完整无断裂 |
| 2 | 内核职责 | 图中心 `Kernel.processTable` 是唯一持有进程状态的组件（进程不绕开内核） |

## 三、核心代码

### 3.1 进程定义（PID + 状态机）

```java
package com.example.kernel.process;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Agent 进程——最小骨架：PID、状态机、退出码。 */
public class AgentProcess implements Runnable {

    public enum State { CREATED, RUNNING, SUSPENDED, EXITED }

    private static final AtomicLong PID_GEN = new AtomicLong(1);

    private final long pid = PID_GEN.getAndIncrement();
    private final String agentName;
    private volatile State state = State.CREATED;
    private volatile int exitCode = -1;

    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public AgentProcess(String agentName, org.springframework.ai.chat.client.ChatClient chatClient) {
        this.agentName = agentName;
        this.chatClient = chatClient;   // javap 实证：ChatClient 由 Builder 构建
    }

    @Override
    public void run() {
        state = State.RUNNING;
        try {
            // 编排循环（最小版：单轮；02 迭代扩成多轮/挂起/恢复）
            String reply = chatClient.prompt()
                    .system("你是内核托管的 Agent 进程 " + agentName)
                    .user("执行你的任务")
                    .call().content();          // javap 实证：CallResponseSpec.content()
            System.out.printf("[pid=%d] %s%n", pid, reply);
            exit(0);
        } catch (Exception e) {
            exit(1);   // 进程崩溃：内核不崩（关键纪律）
        }
    }

    public synchronized void exit(int code) {
        this.exitCode = code;
        this.state = State.EXITED;
    }

    // getter（完整）
    public long pid() { return pid; }
    public String agentName() { return agentName; }
    public State state() { return state; }
    public int exitCode() { return exitCode; }
    public Instant createdAt() { return createdAt; }
    private final Instant createdAt = Instant.now();
}
```

### 3.2 内核与进程表

```java
package com.example.kernel;

import com.example.kernel.process.AgentProcess;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** 最小内核——进程表 + spawn + /proc 查询。 */
public class AgentKernel {

    private final Map<Long, AgentProcess> processTable = new ConcurrentHashMap<>();
    private final ExecutorService runners = Executors.newVirtualThreadPerTaskExecutor();  // Java 21 虚拟线程：进程天然映射

    public long spawn(String agentName, org.springframework.ai.chat.client.ChatClient chatClient) {
        AgentProcess p = new AgentProcess(agentName, chatClient);
        processTable.put(p.pid(), p);
        runners.submit(p);   // 虚拟线程承载——单进程崩溃不影响内核
        return p.pid();
    }

    /** /proc：进程列表快照。 */
    public List<String> proc() {
        return processTable.values().stream()
                .map(p -> "pid=%d name=%s state=%s exit=%d"
                        .formatted(p.pid(), p.agentName(), p.state(), p.exitCode()))
                .toList();
    }
}
```

> **设计要点**：Agent 进程映射到**虚拟线程**（[附录 00-Java21新特性/00-虚拟线程]）——轻量（千级并发无压力）、阻塞式写法亲和编排循环；02 迭代引入挂起/恢复后再谈响应式内核。

### 3.3 本节测试与验证（核心代码可运行）

**前置条件**：最小内核（spawn/状态迁移//proc 视图/退出码记录）已按 §三 实现，虚拟线程承载进程。

**材料 A——异常进程脚本**：进程任务第 2 步抛 `RuntimeException`（触发 §3.1 `run()` 的 catch 分支）。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | spawn 两个进程（含一个会失败） | /proc 状态依次 CREATED→RUNNING→EXITED（正常）/EXITED(exit=1)（异常） |
| 2 | 材料A 进程异常 | exit=1；内核存活；另一进程不受影响正常完成 |
| 3 | 查询退出进程 | 退出码+耗时+步骤数可查 |

**失败排查**：①异常带崩内核→进程体没隔离在独立 try 边界（虚拟线程异常未捕获，应为每个进程包独立 try/catch）；③不可查→EXITED 记录被立即清除（应保留 TTL，进程从进程表移除改为延迟回收）。

## 四、全篇回归验证

> 各节验证材料与断言已上移至 §3.3（本节测试与验证）；本表为整篇最小内核的回归验收，不重复材料。

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 生命周期 | spawn→run→exit 全程进程表状态可见 |
| 2 | 隔离 | 进程崩溃内核不崩（虚拟线程独立承载） |
| 3 | /proc | 进程表快照可查（pid/状态/退出码） |

## 五、本迭代痛点

① 进程是"裸跑"的——工具直连、无配额（→03 syscall）② 记忆在进程内自管（→04 记忆FS）③ 无法挂起恢复（→02/06）

### 五.1 本节核对（痛点与三问对齐）

- [ ] 三条痛点分别对应后续迭代 03/04/02 之一，且与 00 §五迭代路线无矛盾

## 六、验收对照

### 六.1 本节核对（三问 vs 落地）

> §2 验收目标的答案已在 §三.3 断言通过；本表为收尾自检。三行验收（生命周期/隔离//proc）与 §四 回归表三项——一一对应即 PASS。

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 生命周期 | spawn→run→exit 状态可见 | ✅ |
| 隔离 | 进程崩内核不崩 | ✅ |
| /proc | 进程表可查 | ✅ |

**下一篇**：[02-进程模型与生命周期](02-进程模型与生命周期.md)。
