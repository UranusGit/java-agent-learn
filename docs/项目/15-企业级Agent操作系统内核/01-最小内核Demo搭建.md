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

## 四、测试与验证

```bash
# 1. spawn 两个进程 → /proc 看到 CREATED→RUNNING→EXITED 迁移
# 2. 某进程抛异常 → exit=1，内核与其他进程正常
# 3. 退出码与耗时可查
```

## 五、本迭代痛点

① 进程是"裸跑"的——工具直连、无配额（→03 syscall）② 记忆在进程内自管（→04 记忆FS）③ 无法挂起恢复（→02/06）

## 六、验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 生命周期 | spawn→run→exit 状态可见 | ✅ |
| 隔离 | 进程崩内核不崩 | ✅ |
| /proc | 进程表可查 | ✅ |

**下一篇**：[02-迭代一-进程模型与生命周期](02-迭代一-进程模型与生命周期.md)。
