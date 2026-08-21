# 02-Actor 任务核与任务生命周期

> **定位**：把"散乱的任务状态"收敛为**单写者 Actor 任务核 + 统一任务生命周期**。核心：**① 每个长任务一个 Actor（单写者、有界命令）② 统一生命周期状态机（RUNNING→PAUSING→PAUSED/COMPLETED/CANCELLED/DEGRADED）③ 命令具备暂停/恢复/取消钩子**。呼应 [22-会话引擎 Actor核](../../项目/22-企业级Agent会话引擎/02-Actor核与任务生命周期.md)。前置阅读：[01-最小Demo](01-最小Demo.md)、[12-教程状态管理](../../教程/12-Agent状态管理.md)。
>
> **铁律 0**：Actor 核自研「概念代码」；仅模型/工具调用用实证基准。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①单任务 Actor 核（单写者循环）②任务生命周期状态机 ③暂停/恢复/取消命令（三段取消语义） |
| **影响了哪些模块** | WorkflowEngine → 拆出 TaskActor（单写者）+ TaskStateMachine |
| **架构如何演进** | 任务从"for 循环"演进为"有状态生命周期 + 可暂停/可取消" |
| **上一版痛点** | 任务状态裸散在各步；无法暂停/取消；多命令互相覆盖 |

**本迭代验收**：①每任务单写者，命令有界队列不冲突 ②生命周期状态机清晰 ③可暂停/恢复/取消（取消触发后正在执行的步骤安全收尾）。

## 二、任务生命周期状态机

```mermaid
stateDiagram-v2
    [*] --> QUEUED
    QUEUED --> RUNNING: 调度
    RUNNING --> PAUSING: 收到暂停
    RUNNING --> CANCELLING: 收到取消
    RUNNING --> COMPLETED: 全步骤完成
    PAUSING --> PAUSED: 当前步安全停
    PAUSED --> RUNNING: 恢复
    CANCELLING --> CANCELLED: 已收尾
    RUNNING --> DEGRADED: 预算/死循环触发
    COMPLETED --> [*]
    CANCELLED --> [*]
    DEGRADED --> [*]
```

## 三、Actor 任务核（单写者）

```java
// 概念代码：单写者 + 有界命令（适配虚拟线程/Reactor）
@Component
public class TaskActor {
    // 有界命令队列：暂停/恢复/取消 有反压
    private final Sinks.Many<TaskCommand> cmdSink  = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);

    public void submit(TaskCommand cmd) { cmdSink.tryEmitNext(cmd); }  // 入队（反压）

    // 单消费者循环：串行处理命令，避免多命令互相覆盖
    public Flux<TaskEvent> runLoop(String taskId) {
        return cmdSink.asFlux()
            .concatMap(cmd -> switch (cmd) {
                case PAUSE   p -> pause(p);
                case RESUME  r -> resume(r);
                case CANCEL  c -> cancel(c);   // 三段取消：标记→安全收尾→完结
                case FETCH_STEP s -> executeStepSafely(s);
            })
            .onErrorResume(e -> handleDegraded(e));   // 异常 → DEGRADED（04/05 深化）
    }
}
```

**意义**：任务生命周期由 Actor 单写者保证，任何命令（暂停/取消/新步骤）不会并发打架，状态迁移可控、可观测——这是长任务可靠性的第一块拼图。

## 四、暂停/取消的"安全收尾"

- **暂停**：正在执行的步骤**允许跑完**（不硬杀，避免半途写一半），然后停到 PAUSED。
- **取消**：三段——①标记 CANCELLING ②当前步安全收尾 + 幂等闭合（03 补）③持久化为 CANCELLED。
- 绝不在步骤执行中途强杀（破坏 Checkpoint / 副作用一致性）。

## 五、验收

| 测试 | 期望 |
|------|------|
| 高并发命令 | 单写者串行，状态一致 |
| 暂停 | 当前步跑完后 PAUSED |
| 恢复 | 从 PAUSED 续跑（利用 Checkpoint） |
| 取消 | CANCELLING→安全收尾→CANCELLED |

> **下一步**：任务可暂停/取消了，但**重试的副作用重复**隐患仍在（最小 Demo 偷懒点①）。03 迭代做**幂等重试**——长任务可靠性的命门。
