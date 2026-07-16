# L9 无敌 - 编排引擎与多 Agent 协作

> 当五大 Workflow Advisor 也撑不住时，上编排引擎。
>
> 前置：[`./08-高阶-多租户与防失控.md`](./08-高阶-多租户与防失控.md)
> 预计：3-5 天

---

## 0. 什么时候要上编排引擎

### 0.1 五大 Workflow Advisor 的极限

| 信号 | 说明 |
|------|------|
| Agent 之间需要**复杂状态共享** | 不止是 input/output，还要共享状态机 |
| 任务流程有**条件分支 + 循环 + 子流程** | DAG 表达不出来 |
| 需要**断点续跑** | 长任务中断后能恢复 |
| 需要**人工审批节点** | LLM 跑到一半要等人审 |
| 需要**多 Agent 协作** | 不同 Agent 有不同人格 / 工具集 |

满足 2 条以上 → 上编排引擎。

### 0.2 推荐引擎

| 引擎 | 语言 | 适合 |
|------|------|------|
| **LangGraph4j** | Java | LangChain4j 用户 |
| **Alibaba Graph (Spring AI Alibaba)** | Java | Spring AI 中文用户 |
| **Apache Camel + AI** | Java | 企业集成场景 |

---

## 1. 状态机基础

### 1.1 状态机三要素

| 要素 | 含义 |
|------|------|
| **State（节点）** | 一个执行单元（LLM 调用 / 工具调用 / 业务逻辑） |
| **Edge（边）** | 状态之间的转移条件 |
| **Context（上下文）** | 跨节点共享的状态对象 |

### 1.2 简单状态机示例

```
[开始]
  ↓
[分类] → [简单问题] → [直接回答] → [结束]
        → [复杂问题] → [拆解任务] → [并行执行] → [汇总] → [结束]
```

### 1.3 跟 Workflow Advisor 的区别

| 维度 | Workflow Advisor | 状态机 |
|------|-----------------|-------|
| 控制流 | 静态（编译时定） | 动态（运行时根据 state） |
| 状态共享 | 通过 Advisor context（半正式） | 显式 Context 对象 |
| 错误恢复 | 全部重跑 | 从 checkpoint 恢复 |
| 人工节点 | 不支持 | 支持（`interrupt()`） |
| 复杂度 | 低 | 中 |

---

## 2. LangGraph4j 实战

### 2.1 引入依赖

```xml
<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-spring-ai</artifactId>
    <version>1.0.0-beta1</version>
</dependency>
```

### 2.2 定义状态对象

```java
public record AgentState(
        String userInput,
        String category,
        List<String> subTasks,
        Map<String, String> subResults,
        String finalAnswer,
        int retryCount
) {}
```

### 2.3 定义节点

```java
public class ClassifierNode implements RunnableNode<AgentState> {

    private final ChatClient classifier;

    @Override
    public AgentState apply(AgentState state) {
        String category = classifier.prompt()
                .user(state.userInput())
                .call()
                .content()
                .trim();

        return new AgentState(
                state.userInput(), category, List.of(),
                Map.of(), null, state.retryCount()
        );
    }
}

public class TaskSplitterNode implements RunnableNode<AgentState> {

    private final ChatClient splitter;

    @Override
    public AgentState apply(AgentState state) {
        if (!"COMPLEX".equals(state.category())) return state;

        String json = splitter.prompt()
                .system("把复杂任务拆成 3-5 个子任务，输出 JSON 数组")
                .user(state.userInput())
                .call()
                .content();

        List<String> subTasks = parseJsonArray(json);
        return new AgentState(
                state.userInput(), state.category(), subTasks,
                Map.of(), null, state.retryCount()
        );
    }
}

public class WorkerNode implements RunnableNode<AgentState> {

    private final ChatClient worker;
    private final String taskDescription;

    public WorkerNode(ChatClient worker, String taskDescription) {
        this.worker = worker;
        this.taskDescription = taskDescription;
    }

    @Override
    public AgentState apply(AgentState state) {
        String result = worker.prompt()
                .system("子任务：" + taskDescription)
                .user(state.userInput())
                .call()
                .content();

        Map<String, String> newResults = new HashMap<>(state.subResults());
        newResults.put(taskDescription, result);

        return new AgentState(
                state.userInput(), state.category(), state.subTasks(),
                newResults, null, state.retryCount()
        );
    }
}
```

### 2.4 装配图

```java
public StateGraph<AgentState> buildGraph(
        ChatClient classifier,
        ChatClient splitter,
        ChatClient worker,
        ChatClient aggregator
) {
    return new StateGraph<>(AgentState.class, new AgentState(...))
            .addNode("classifier", new ClassifierNode(classifier))
            .addNode("splitter", new TaskSplitterNode(splitter))
            .addNode("worker_1", new WorkerNode(worker, "子任务 1 描述"))
            .addNode("worker_2", new WorkerNode(worker, "子任务 2 描述"))
            .addNode("worker_3", new WorkerNode(worker, "子任务 3 描述"))
            .addNode("aggregator", new AggregatorNode(aggregator))
            .addEdge(START, "classifier")
            .addConditionalEdge("classifier", state -> {
                if ("SIMPLE".equals(state.category())) return "aggregator";
                return "splitter";
            }, Map.of("aggregator", "aggregator", "splitter", "splitter"))
            .addEdge("splitter", "worker_1")
            .addEdge("splitter", "worker_2")
            .addEdge("splitter", "worker_3")
            .addEdge("worker_1", "aggregator")
            .addEdge("worker_2", "aggregator")
            .addEdge("worker_3", "aggregator")
            .addEdge("aggregator", END);
}
```

### 2.5 执行

```java
@Service
public class GraphExecutor {

    private final StateGraph<AgentState> graph;

    public String run(String userInput) {
        var compiled = graph.compile();
        var result = compiled.invoke(Map.of("userInput", userInput));
        return result.finalAnswer();
    }
}
```

---

## 3. Alibaba Graph (Spring AI Alibaba)

### 3.1 引入

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-graph</artifactId>
    <version>1.0.0.2</version>
</dependency>
```

### 3.2 节点定义

```java
@Bean
public GraphNode classifyNode(ChatClient classifier) {
    return GraphNode.of("classify", state -> {
        String category = classifier.prompt()
                .user(state.get("userInput").toString())
                .call().content();
        return Map.of("category", category);
    });
}

@Bean
public GraphNode workerNode(ChatClient worker) {
    return GraphNode.of("worker", state -> {
        String result = worker.prompt()
                .user(state.get("userInput").toString())
                .call().content();
        return Map.of("result", result);
    });
}
```

### 3.3 图装配

```java
@Bean
public StateGraph graph(
        GraphNode classifyNode,
        GraphNode workerNode,
        GraphNode aggregatorNode
) {
    return StateGraph.define()
            .addNode(classifyNode)
            .addNode(workerNode)
            .addNode(aggregatorNode)
            .addEdge(START, classifyNode)
            .addConditionalEdge(classifyNode, state -> {
                String cat = state.get("category").toString();
                return "SIMPLE".equals(cat) ? aggregatorNode.getName() : workerNode.getName();
            })
            .addEdge(workerNode, aggregatorNode)
            .addEdge(aggregatorNode, END)
            .compile();
}
```

---

## 4. 多 Agent 协作模式

### 4.1 三种常见模式

| 模式 | 描述 | 适合 |
|------|------|------|
| **Hierarchical**（层级） | 一个 Orchestrator Agent 协调多个 Worker Agent | 大型复杂任务 |
| **Sequential**（流水线） | Agent A → Agent B → Agent C | 内容生产流水线 |
| **Debate**（辩论） | 多个 Agent 针对同一问题独立回答，再辩论达成共识 | 高准确性场景 |

### 4.2 Hierarchical 模式实现

```java
public class OrchestratorAgent {

    private final ChatClient brain;
    private final Map<String, SubAgent> subAgents;

    public String execute(String task) {
        // 1. Orchestrator 决定要哪些 Agent 介入
        String plan = brain.prompt()
                .system("分析任务，输出要调用的子 Agent 列表 JSON：[\"coder\", \"reviewer\"]")
                .user(task)
                .call()
                .content();

        List<String> agentNames = parseAgents(plan);

        // 2. 依次调用子 Agent
        Map<String, String> results = new HashMap<>();
        for (String name : agentNames) {
            SubAgent agent = subAgents.get(name);
            String result = agent.execute(task, results);
            results.put(name, result);
        }

        // 3. Orchestrator 汇总
        return brain.prompt()
                .system("整合所有子 Agent 的结果")
                .user("任务：" + task + "\n\n结果：" + results)
                .call()
                .content();
    }
}
```

### 4.3 SubAgent 定义

```java
public interface SubAgent {
    String name();
    String execute(String task, Map<String, String> previousResults);
}

@Component
public class CoderAgent implements SubAgent {
    private final ChatClient coder;
    public String name() { return "coder"; }
    public String execute(String task, Map<String, String> prev) {
        return coder.prompt()
                .system("你是资深程序员，写代码解决问题")
                .user(task)
                .call().content();
    }
}

@Component
public class ReviewerAgent implements SubAgent {
    public String name() { return "reviewer"; }
    public String execute(String task, Map<String, String> prev) {
        String code = prev.get("coder");
        return coder.prompt()
                .system("你是代码评审员，评审上面的代码")
                .user(code)
                .call().content();
    }
}
```

### 4.4 Debate 模式

```java
public String debate(String question) {
    String pro = proponentAgent.chat(question);
    String con = opponentAgent.chat(question);
    String synthesis = judgeAgent.chat("正方：" + pro + "\n反方：" + con + "\n给出综合结论");
    return synthesis;
}
```

适合学术问题 / 战略决策。

---

## 5. 断点续跑（Checkpointing）

### 5.1 为什么需要

长任务（如几分钟的视频生成）中途崩了，不想从头跑。

### 5.2 LangGraph4j 实现

```java
var compiled = graph.compile()
        .withCheckpointSaver(new MemoryCheckpointSaver());

// 跑到一半中断
var state = compiled.invoke(Map.of("userInput", "..."));
// 抛异常中断

// 恢复
var resumed = compiled.resume(state.id(), Map.of());
```

### 5.3 持久化 Checkpoint

```java
var compiled = graph.compile()
        .withCheckpointSaver(new JdbcCheckpointSaver(dataSource));
```

跨进程重启也能恢复。

---

## 6. 人工审批节点（Human-in-the-Loop）

### 6.1 场景

- LLM 生成的 SQL 必须人工确认才能执行
- 邮件发送前要审批
- 转账类操作必须二次确认

### 6.2 实现

```java
public class HumanApprovalNode implements RunnableNode<AgentState> {

    @Override
    public AgentState apply(AgentState state) {
        // 把待审批内容入库
        approvalRepo.save(Approval.builder()
                .sessionId(state.sessionId())
                .content(state.generatedContent())
                .status("PENDING")
                .build());

        // 暂停图执行，等待审批回调
        throw new InterruptException("WAITING_FOR_APPROVAL");
    }
}

// 审批回调
@PostMapping("/approve/{sessionId}")
public String approve(@PathVariable String sessionId, @RequestParam boolean approved) {
    Approval ap = approvalRepo.findBySessionId(sessionId);
    ap.setStatus(approved ? "APPROVED" : "REJECTED");

    var compiled = graph.compile();
    var state = compiled.getState(sessionId);
    var resumed = compiled.resume(sessionId,
            approved ? Map.of() : Map.of("rejected", true));

    return resumed.finalAnswer();
}
```

---

## 7. 实战：智能运维 Agent（多 Agent 版）

### 7.1 角色

| Agent | 职责 | 工具 |
|-------|------|------|
| **Coordinator** | 接收用户问题，分发任务 | 无 |
| **Diagnoser** | 诊断问题（查日志、查指标） | K8sTools / PromTools |
| **Solver** | 给出解决方案 | 知识库 |
| **Executor** | 执行方案 | K8sTools（写操作） |
| **Reviewer** | 审查执行结果 | 无 |

### 7.2 流程

```
用户："user-service 502 了"
  ↓
Coordinator 分发给 Diagnoser
  ↓
Diagnoser 调 K8sTools 查 pod 状态、PromTools 查指标 → 报告"pod OOM"
  ↓
Coordinator 给 Solver，Solver 查知识库 → "建议扩大 memory limit"
  ↓
Coordinator 给 Executor，Executor 调 K8sTools 修改 deployment
  ↓
Coordinator 给 Reviewer，Reviewer 调 PromTools 确认恢复
  ↓
Coordinator 汇总回复用户
```

### 7.3 加上人工审批

Executor 执行写操作前，必须人工审批（避免误操作）。

---

## 8. 选型建议

### 8.1 不需要编排引擎

- 业务流程静态（开发期就能定下来）
- 子任务不超过 5 个
- 不需要断点续跑

→ 用 L5 的五大 Workflow Advisor。

### 8.2 需要编排引擎

- 流程动态（LLM 决定下一步）
- 多 Agent 协作
- 需要人工审批 / 断点续跑
- 跨流程状态共享

→ 用 LangGraph4j 或 Alibaba Graph。

### 8.3 需要分布式工作流引擎（Camel / Temporal）

- 跨企业系统集成（SAP / Oracle / 自研系统）
- 数小时 / 数天的长流程
- 严格的事务一致性

→ Apache Camel + AI extension。

---

## 9. 反模式警告

### 9.1 一上来就用编排引擎

> 大部分场景，五大 Workflow Advisor 够用。

引入编排引擎 = 增加复杂度 + 调试难度。先证明 Advisor 撑不住再升级。

### 9.2 编排引擎里塞太多业务逻辑

节点应该**只做编排**，业务逻辑（DB 操作 / API 调用）放在 Service 里。

### 9.3 多 Agent 没有协调机制

多个 Agent 各自为政 → 结果无法整合 / token 爆炸。

**解决**：必须有 Orchestrator 或 State 协调。

---

## 10. 理解检查

1. 五大 Workflow Advisor 撑不住的信号有哪些？
2. 状态机三要素是什么？
3. 多 Agent 协作的三种模式分别适合什么？
4. 人工审批节点怎么实现？
5. 什么时候上 Camel / Temporal 这种重型引擎？

---

## 11. 练习任务

1. 用 LangGraph4j 实现一个分类 → 工作流 → 聚合的状态机
2. 用 Alibaba Graph 实现同一个图，对比 API 差异
3. 实现 Hierarchical 多 Agent（Coordinator + 2 Worker）
4. 加 Checkpoint，让图能从崩溃中恢复
5. 实现一个 Human-in-the-Loop 节点，等用户审批后才执行下一步
6. （进阶）实现 Debate 模式，让 3 个 Agent 辩论同一个技术选型问题

---

## 12. 进 L10 之前的能力确认

完成本篇你应该能：
- [ ] 判断何时该上编排引擎
- [ ] 用 LangGraph4j 或 Alibaba Graph 实现状态机
- [ ] 设计多 Agent 协作架构
- [ ] 实现断点续跑和人工审批节点
- [ ] 避免编排引擎的三大反模式

完成后进入 [`./10-无敌-MCP生态与长期演进.md`](./10-无敌-MCP生态与长期演进.md)。
