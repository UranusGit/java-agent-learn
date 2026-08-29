# 事件驱动 Agent 架构

> 「本文是对 [教程 30-微服务拆分与Agent部署 §2-§5] 的深入展开；Kafka 中间件层机制（生产者/消费者/事务/存储/运维）全面下钻见 [教程 67-Kafka全景与核心概念]」

> **定位**：系统讲解事件驱动架构（EDA）在 Agent 系统中的应用——Event Sourcing、Saga 模式、异步任务编排、多 Agent 通信，以及 Spring Boot + Kafka/RabbitMQ 的实现方案。
>
> **读者画像**：正在设计多 Agent 协作系统或长耗时 Agent 任务的架构师，需要理解事件驱动模型如何解决同步调用的瓶颈。

---

## 1. 为什么 Agent 需要事件驱动

### 1.1 同步调用的瓶颈

```mermaid
graph TB
    subgraph SYNC["同步调用模型"]
        U1["用户请求"] --> A1["Agent"]
        A1 -->|"阻塞 30s"| LLM1["LLM 推理"]
        A1 -->|"阻塞 10s"| T1["工具执行"]
        A1 -->|"阻塞 15s"| LLM2["第二轮 LLM"]
        LLM2 --> R1["返回结果<br/>总耗时：55s"]

        U2["其他用户"] -.->|"等待"| A1
    end

    style SYNC fill:#ffcdd2
    style R1 fill:#fff9c4
```

**问题**：
- 单个请求耗时 30s-5min
- 线程/连接被长时间占用
- 无法取消、无法重试、无法并行
- 多 Agent 协作时延迟叠加

### 1.2 事件驱动的优势

```mermaid
graph TB
    subgraph EDA["事件驱动模型"]
        U1["用户请求"] --> QUEUE["事件队列"]
        QUEUE --> A1["Agent（异步消费）"]
        A1 -->|"事件: LLM_Requested"| LLMQ["LLM 队列"]
        LLMQ --> LLM1["LLM Worker"]
        LLM1 -->|"事件: LLM_Completed"| A1
        A1 -->|"事件: Tool_Requested"| TOOLQ["工具队列"]
        TOOLQ --> T1["工具 Worker"]
        T1 -->|"事件: Tool_Completed"| A1
        A1 -->|"事件: Task_Completed"| NOTIFY["通知用户"]
    end

    U2["其他用户"] --> QUEUE

    style EDA fill:#c8e6c9
    style QUEUE fill:#e1bee7
```

---

## 2. 核心模式

### 2.1 Agent 事件分类

```mermaid
graph TB
    EVENTS["Agent 事件类型"]

    EVENTS --> LIFECYCLE["生命周期事件"]
    LIFECYCLE --> L1["TaskCreated"]
    LIFECYCLE --> L2["TaskStarted"]
    LIFECYCLE --> L3["TaskCompleted"]
    LIFECYCLE --> L4["TaskFailed"]
    LIFECYCLE --> L5["TaskCancelled"]

    EVENTS --> EXECUTION["执行事件"]
    EXECUTION --> E1["LLMCallRequested"]
    EXECUTION --> E2["LLMCallCompleted"]
    EXECUTION --> E3["ToolCallRequested"]
    EXECUTION --> E4["ToolCallCompleted"]

    EVENTS --> COLLAB["协作事件"]
    COLLAB --> C1["AgentMessageSent"]
    COLLAB --> C2["AgentMessageReceived"]
    COLLAB --> C3["TaskDelegated"]
    COLLAB --> C4["TaskDelegationAccepted"]

    EVENTS --> OBSERVABILITY["可观测性事件"]
    OBSERVABILITY --> O1["TokenUsageRecorded"]
    OBSERVABILITY --> O2["LatencyMeasured"]
    OBSERVABILITY --> O3["ErrorOccurred"]

    style LIFECYCLE fill:#c8e6c9
    style EXECUTION fill:#bbdefb
    style COLLAB fill:#fff9c4
    style OBSERVABILITY fill:#ffe0b2
```

### 2.2 事件结构

```java
public abstract class AgentEvent {
    private String eventId;          // UUID
    private String taskId;           // 任务 ID
    private String agentId;          // Agent ID
    private String tenantId;         // 租户 ID
    private Instant timestamp;       // 时间戳
    private int version;             // 事件版本（兼容性）
    private Map<String, String> metadata; // 追踪信息
}

public record TaskCreatedEvent(
    String eventId, String taskId, String agentId,
    String tenantId, Instant timestamp,
    String userId, String userInput,
    TaskPriority priority,
    Map<String, String> metadata
) extends AgentEvent {}

public record LLMCallCompletedEvent(
    String eventId, String taskId, String agentId,
    String tenantId, Instant timestamp,
    String responseId,
    int promptTokens, int completionTokens,
    long latencyMs,
    String modelUsed,
    Map<String, String> metadata
) extends AgentEvent {}
```

---

## 3. Event Sourcing

### 3.1 核心思想

不存储当前状态，而是存储**所有状态变更事件**。当前状态通过回放事件得到。

```mermaid
graph TB
    subgraph TRADITIONAL["传统状态存储"]
        T1["UPDATE tasks SET status='completed' WHERE id=123"]
        T2["之前的状态丢失"]
    end

    subgraph ES["Event Sourcing"]
        E1["APPEND events: TaskCreated → Started → LLMCalled → Completed"]
        E2["当前状态 = 回放所有事件"]
        E3["完整审计轨迹"]
    end

    style TRADITIONAL fill:#ffcdd2
    style ES fill:#c8e6c9
```

### 3.2 任务状态重建

```java
@Entity
@Table(name = "agent_events")
public class AgentEventEntity {
    @Id @GeneratedValue
    private Long id;
    private String taskId;
    private String eventType;
    private String eventData;     // JSON
    private Instant timestamp;
    private Long sequenceNumber;  // 顺序号
}

@Service
public class TaskStateReconstructor {

    public TaskState reconstruct(String taskId) {
        List<AgentEventEntity> events = eventRepository
            .findByTaskIdOrderBySequenceNumberAsc(taskId);

        TaskState state = TaskState.initial();
        for (AgentEventEntity event : events) {
            state = apply(state, deserialize(event));
        }
        return state;
    }

    private TaskState apply(TaskState state, AgentEvent event) {
        return switch (event) {
            case TaskCreatedEvent e -> state.withStatus(Status.CREATED)
                                            .withInput(e.userInput());
            case TaskStartedEvent e -> state.withStatus(Status.RUNNING)
                                            .withStartedAt(e.timestamp());
            case LLMCallCompletedEvent e -> state.addTokenUsage(
                                            e.promptTokens() + e.completionTokens());
            case TaskCompletedEvent e -> state.withStatus(Status.COMPLETED)
                                            .withResult(e.result());
            case TaskFailedEvent e -> state.withStatus(Status.FAILED)
                                          .withError(e.errorMessage());
            default -> state;
        };
    }
}
```

### 3.3 Snapshot 优化

```java
// 每 100 个事件存一个快照，避免全量回放
@Entity
@Table(name = "task_snapshots")
public class TaskSnapshot {
    @Id
    private String taskId;
    private Long atSequenceNumber;
    private String stateJson;  // 序列化的 TaskState
    private Instant createdAt;
}

public TaskState reconstruct(String taskId) {
    // 1. 找到最近的快照
    Optional<TaskSnapshot> snapshot = snapshotRepository
        .findLatestByTaskId(taskId);

    TaskState state;
    long fromSequence;
    if (snapshot.isPresent()) {
        state = deserialize(snapshot.get().getStateJson());
        fromSequence = snapshot.get().getAtSequenceNumber();
    } else {
        state = TaskState.initial();
        fromSequence = 0;
    }

    // 2. 只回放快照之后的事件
    List<AgentEventEntity> events = eventRepository
        .findByTaskIdAndSequenceNumberAfter(taskId, fromSequence);

    for (AgentEventEntity event : events) {
        state = apply(state, deserialize(event));
    }

    return state;
}
```

---

## 4. Saga 模式

### 4.1 多 Agent 协作的分布式事务

```mermaid
graph TB
    SAGA["Saga: "分析并报告用户数据""]

    SAGA --> S1["Step 1: DataAgent 提取数据"]
    S1 --> S2["Step 2: AnalysisAgent 分析数据"]
    S2 --> S3["Step 3: ReportAgent 生成报告"]
    S3 --> S4["Step 4: NotifyAgent 通知用户"]

    S1 -.->|"失败"| C1["补偿: 记录失败原因"]
    S2 -.->|"失败"| C2["补偿: 清理已提取数据"]
    S3 -.->|"失败"| C3["补偿: 丢弃分析结果"]

    style SAGA fill:#e1bee7
    style S1 fill:#c8e6c9
    style S2 fill:#c8e6c9
    style C1 fill:#ffcdd2
    style C2 fill:#ffcdd2
```

### 4.2 编排式 Saga

```java
@Service
public class DataAnalysisSaga {

    private final EventPublisher eventPublisher;

    public Mono<Void> execute(SagaRequest request) {
        String sagaId = UUID.randomUUID().toString();

        // 启动 Saga
        eventPublisher.publish(new SagaStartedEvent(sagaId, request));

        return Flux.just(
            new SagaStep("extract-data", "data-agent",
                new ExtractDataCommand(request.dataSource())),
            new SagaStep("analyze", "analysis-agent",
                new AnalyzeCommand()),
            new SagaStep("report", "report-agent",
                new GenerateReportCommand()),
            new SagaStep("notify", "notify-agent",
                new NotifyCommand(request.userId()))
        )
        .concatMap(step -> executeStep(sagaId, step))
        .then()
        .doOnSuccess(v -> eventPublisher.publish(new SagaCompletedEvent(sagaId)))
        .doOnError(e -> eventPublisher.publish(new SagaFailedEvent(sagaId, e.getMessage())));
    }

    private Mono<Void> executeStep(String sagaId, SagaStep step) {
        return agentClient.sendCommand(step.agentId(), step.command())
            .timeout(Duration.ofMinutes(5))
            .flatMap(response -> {
                if (response.success()) {
                    eventPublisher.publish(new SagaStepCompletedEvent(sagaId, step.name()));
                    return Mono.empty();
                } else {
                    eventPublisher.publish(new SagaStepFailedEvent(sagaId, step.name()));
                    return Mono.error(new SagaStepException(step.name(), response.error()));
                }
            });
    }
}
```

### 4.3 补偿事务

```java
@Service
public class SagaCompensator {

    public void compensate(String sagaId, String failedStep) {
        // 获取已完成的步骤（逆序）
        List<SagaStep> completedSteps = getSagaSteps(sagaId).stream()
            .takeWhile(step -> !step.name().equals(failedStep))
            .sorted(Comparator.reverseOrder())
            .toList();

        // 逆序执行补偿
        for (SagaStep step : completedSteps) {
            CompensationCommand comp = getCompensation(step);
            try {
                agentClient.sendCommand(step.agentId(), comp)
                    .block(Duration.ofMinutes(2));
                log.info("补偿成功: {}", step.name());
            } catch (Exception e) {
                log.error("补偿失败: {}", step.name(), e);
                // 补偿失败需要人工介入
                alertService.send("Saga 补偿失败: " + sagaId);
            }
        }
    }
}
```

---

## 5. 异步任务编排

### 5.1 DAG 任务图

```mermaid
graph TB
    TASK["用户请求：完整数据分析报告"]

    TASK --> FETCH["数据获取<br/>（并行）"]
    FETCH --> F1["API 1"]
    FETCH --> F2["API 2"]
    FETCH --> F3["数据库"]

    F1 --> MERGE["数据合并"]
    F2 --> MERGE
    F3 --> MERGE

    MERGE --> ANALYSIS["分析"]
    ANALYSIS --> A1["统计分析"]
    ANALYSIS --> A2["趋势预测"]

    A1 --> REPORT["报告生成"]
    A2 --> REPORT

    REPORT --> SEND["发送报告"]

    style FETCH fill:#c8e6c9
    style ANALYSIS fill:#bbdefb
    style REPORT fill:#fff9c4
```

### 5.2 实现

```java
@Service
public class DAGTaskOrchestrator {

    public Mono<TaskResult> execute(TaskGraph graph) {
        return Mono.create(sink -> {
            Map<String, TaskNodeState> states = new ConcurrentHashMap<>();
            AtomicInteger remainingTasks = new AtomicInteger(graph.totalNodes());

            // 拓扑排序 + 并行执行
            for (TaskNode node : graph.getRoots()) {
                executeNode(node, graph, states, remainingTasks, sink);
            }
        });
    }

    private void executeNode(TaskNode node, TaskGraph graph,
            Map<String, TaskNodeState> states,
            AtomicInteger remaining, MonoSink<TaskResult> sink) {

        // 等待所有依赖完成
        List<Mono<NodeResult>> deps = node.getDependencies().stream()
            .map(depId -> waitForCompletion(depId, states))
            .toList();

        Mono.zip(deps)
            .flatMap(results -> {
                // 执行当前节点
                states.put(node.getId(), TaskNodeState.running());
                return agentClient.execute(node.getAgentId(),
                    node.getCommand(), results.stream().toList());
            })
            .subscribe(result -> {
                states.put(node.getId(), TaskNodeState.completed(result));

                if (remaining.decrementAndGet() == 0) {
                    // 所有节点完成
                    sink.success(aggregateResults(states));
                }

                // 触发后继节点
                for (TaskNode successor : graph.getSuccessors(node)) {
                    if (allDependenciesMet(successor, states)) {
                        executeNode(successor, graph, states, remaining, sink);
                    }
                }
            }, error -> {
                sink.error(error);
            });
    }
}
```

---

## 6. Kafka 集成

### 6.1 主题设计

```mermaid
graph TB
    TOPICS["Kafka 主题设计"]

    TOPICS --> CMD["agent.commands<br/>命令主题"]
    CMD --> CMD1["Partition: agentId<br/>保证同一 Agent 命令有序"]

    TOPICS --> EVT["agent.events<br/>事件主题"]
    EVT --> EVT1["Partition: taskId<br/>同一任务的事件有序"]

    TOPICS --> COLLAB["agent.collaboration<br/>协作主题"]
    COLLAB --> C1["Agent 间消息传递"]

    TOPICS --> DLQ["agent.dlq<br/>死信队列"]
    DLQ --> D1["处理失败的事件"]

    style CMD fill:#c8e6c9
    style EVT fill:#bbdefb
    style DLQ fill:#ffcdd2
```

### 6.2 Spring Boot 配置

```java
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, AgentEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        config.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, AgentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}

@Service
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, AgentEvent> kafka;

    @Override
    public void publish(AgentEvent event) {
        kafka.send("agent.events", event.taskId(), event);
    }
}

@Component
public class AgentEventConsumer {

    @KafkaListener(topics = "agent.commands", groupId = "${agent.id}")
    public void handleCommand(AgentCommand command) {
        // 处理命令
        commandHandler.handle(command)
            .subscribe(
                result -> eventPublisher.publish(result),
                error -> eventPublisher.publish(new ErrorEvent(error))
            );
    }
}
```

---

## 7. 长耗时任务管理

### 7.1 任务状态机

```mermaid
stateDiagram-v2
    [*] --> Pending: 用户提交
    Pending --> Queued: 入队
    Queued --> Running: Agent 拾取
    Running --> Paused: 用户暂停
    Paused --> Running: 用户恢复
    Running --> WaitingTool: 调用工具
    WaitingTool --> Running: 工具返回
    Running --> WaitingLLM: 调用 LLM
    WaitingLLM --> Running: LLM 返回
    Running --> Completed: 成功完成
    Running --> Failed: 错误
    Running --> Cancelled: 用户取消
    Running --> TimedOut: 超时
    Completed --> [*]
    Failed --> [*]
    Cancelled --> [*]
    TimedOut --> [*]
```

### 7.2 任务恢复

```java
@Service
public class TaskRecoveryService {

    /**
     * 系统重启后，恢复所有中断的任务
     */
    @PostConstruct
    public void recoverInterruptedTasks() {
        List<TaskState> interrupted = taskStore.findByStatusIn(
            Status.RUNNING, Status.WAITING_TOOL, Status.WAITING_LLM
        );

        for (TaskState task : interrupted) {
            // 检查是否真的中断（超时）
            if (task.updatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
                log.info("恢复中断的任务: {}", task.taskId());
                resumeTask(task);
            }
        }
    }

    private void resumeTask(TaskState task) {
        // 通过 Event Sourcing 重建状态
        TaskState current = stateReconstructor.reconstruct(task.taskId());

        // 从中断点继续
        if (current.status() == Status.WAITING_LLM) {
            // 重新发起 LLM 调用
            eventPublisher.publish(new LLMCallRequestedEvent(
                current.taskId(), current.lastPrompt()
            ));
        }
    }
}
```

---

## 8. CQRS 模式

### 8.1 读写分离

```mermaid
graph TB
    subgraph WRITE["写模型（Command）"]
        CMD["用户命令"] --> HANDLER["Command Handler"]
        HANDLER --> ES["Event Store<br/>（追加写入）"]
        ES --> PUBLISH["发布事件"]
    end

    subgraph READ["读模型（Query）"]
        SUBSCRIBE["订阅事件"] --> PROJ["投影到读模型"]
        PROJ --> DB["查询数据库<br/>（优化查询的结构）"]
        DB --> QUERY["用户查询"]
    end

    WRITE -.->|"事件"| READ

    style WRITE fill:#c8e6c9
    style READ fill:#bbdefb
```

```java
// 写模型：命令处理
@Service
public class TaskCommandHandler {

    public Mono<Void> handle(StartTaskCommand cmd) {
        // 1. 验证
        // 2. 生成事件
        TaskStartedEvent event = new TaskStartedEvent(
            UUID.randomUUID().toString(),
            cmd.taskId(), cmd.agentId(), cmd.tenantId(),
            Instant.now(), cmd.input()
        );
        // 3. 持久化事件
        return eventStore.append(event)
            .then(eventPublisher.publish(event).then());
    }
}

// 读模型：投影
@Service
public class TaskProjection {

    @KafkaListener(topics = "agent.events")
    public void project(AgentEvent event) {
        switch (event) {
            case TaskStartedEvent e ->
                queryRepository.save(new TaskView(
                    e.taskId(), e.agentId(), "RUNNING",
                    e.timestamp(), null, null
                ));
            case TaskCompletedEvent e -> {
                TaskView view = queryRepository.findById(e.taskId()).orElseThrow();
                view.setStatus("COMPLETED");
                view.setCompletedAt(e.timestamp());
                queryRepository.save(view);
            }
            // ...
        }
    }
}
```

---

## 9. 总结

事件驱动架构是复杂 Agent 系统的**核心架构选择**：

1. **事件驱动解耦**——Agent 之间通过事件通信，无直接依赖。
2. **Event Sourcing 提供完整审计**——所有状态变更可追溯。
3. **Saga 管理多 Agent 协作**——分布式事务 + 补偿机制。
4. **DAG 编排并行任务**——拓扑排序 + 并行执行 + 依赖等待。
5. **Kafka 是生产级选择**——高吞吐、持久化、分区有序。
6. **CQRS 优化读写**——写用 Event Store，读用投影视图。
7. **长耗时任务需要恢复机制**——重启后从中断点继续。

事件驱动架构的代价是**复杂度增加**——调试困难、最终一致性、需要幂等处理。但对于多 Agent 协作、长耗时任务、高吞吐场景，这是值得的权衡。
