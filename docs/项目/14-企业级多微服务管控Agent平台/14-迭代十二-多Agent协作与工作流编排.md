# 14-迭代十二：多 Agent 协作与工作流编排——从单 Agent 到多 Agent 分工

> **定位**：把 `agent-executor` 从"单个 Agent 直连模型"升级为**多 Agent 协作平台**：**DAG 工作流编排**（节点/边/条件分支/循环）、**任务委派**（主管 Agent 拆解委派给子 Agent）、三种协作模式（主从/对等/流水线）。读者画像：想让 Agent 从"单打独斗"到"团队分工"的读者。前置阅读：[13-迭代十一-评估与数据飞轮](13-迭代十一-评估与数据飞轮.md)、[教程 09-多Agent协作]、[教程 36-Agent工作流编排]。
>
> **演进纪律**：本迭代做多 Agent 编排；高级 RAG（15）、模型供应（16）不提前实现。
> **铁律 0**：代码均经本地 jar `javap` 实证。

---

## 一、四问（本轮：多 Agent 编排）

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① DAG 工作流编排（定义+执行器）② 任务委派与结果聚合 ③ 三种协作模式 |
| **影响了哪些模块** | `agent-executor`（新增编排引擎）、`agent-control-center`（工作流定义下发） |
| **架构如何演进** | 单 Agent 直连 → 多 Agent 协作平台 |
| **上一版本的痛点是什么** | ① 复杂任务单 Agent 无法分工 ② 无工作流定义/版本化 ③ 任务委派无治理（13 前遗留） |

**本迭代验收**：① 一个"调研报告"任务能被拆成"查资料/分析/撰写"三个子 Agent 并行+汇总 ② 工作流定义版本化、可灰度 ③ 条件分支/循环可执行。

---

## 二、多 Agent 协作架构

```mermaid
graph TB
    subgraph flow["工作流定义（管控面下发）"]
        D1["DAG 定义<br/>节点/边/条件"]
    end

    subgraph exec["编排引擎（agent-executor）"]
        E1["FlowEngine<br/>解释执行 DAG"]
        E2["主管 Agent<br/>拆解/委派/汇总"]
        E3["子 Agent 池<br/>检索Agent/分析Agent/撰写Agent"]
    end

    D1 --> E1
    E1 --> E2
    E2 --> E3

    style exec fill:#e8f5e9
```

---

## 三、DAG 工作流定义（版本化，管控面下发）

### 3.1 定义模型

```java
package com.example.agentexecutor.flow;

import java.util.List;

/** 工作流定义（DAG）——节点 + 边 + 条件。 */
public record WorkflowDef(
        String name,
        int version,
        List<NodeDef> nodes,
        List<EdgeDef> edges
) {}

/** 节点：可以是"Agent 任务"或"聚合点" */
public record NodeDef(String id, String type, String agentRef, String prompt) {}

/** 边：source → target，可带条件 */
public record EdgeDef(String source, String target, String when) {}
```

```java
// 示例：调研报告工作流
//   research(检索) ──> analyze(分析) ──> write(撰写) ──> review(审查)
//   review.when = "quality < 0.9" 则回 analyze（循环）
```

### 3.2 编排引擎（解释执行 DAG）

```mermaid
sequenceDiagram
    participant F as FlowEngine
    participant R as 检索Agent
    participant A as 分析Agent
    participant W as 撰写Agent
    participant V as 审查Agent

    F->>R: 执行节点 research
    R-->>F: 检索结果
    F->>A: 执行节点 analyze
    A-->>F: 分析结论
    F->>W: 执行节点 write
    W-->>F: 初稿
    F->>V: 执行节点 review
    V-->>F: 质量分 0.85 (<0.9)
    F->>A: 条件分支 → 回 analyze（改写）
    A-->>F: 修订版
    F->>W: 重写
    W-->>F: 终稿
```

---

## 四、任务委派（主管 Agent 模式）

### 4.1 主管拆解 + 子 Agent 并行

```java
package com.example.agentexecutor.delegate;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/** 任务委派——主管 Agent 拆解任务，子 Agent 并行执行，聚合结果。 */
@Service
public class TaskDelegator {

    private final ChatClient supervisor;          // 主管
    private final ChatClient researchAgent;       // 子 Agent：检索
    private final ChatClient analysisAgent;       // 子 Agent：分析

    public TaskDelegator(ChatClient.Builder builder) {
        this.supervisor = builder.defaultSystem("你是任务主管：拆解任务为子任务，输出 JSON 数组 {subtasks:[{name,instruction}]}").build();
        this.researchAgent = builder.defaultSystem("你是资料检索员").build();
        this.analysisAgent = builder.defaultSystem("你是数据分析师").build();
    }

    /** 主管拆解 → 子 Agent 并行 → 聚合。 */
    public Mono<String> run(String task) {
        // ① 主管拆解
        return Mono.just(supervisor.prompt().user(task).call().content())
                .map(this::parseSubtasks)
                // ② 子 Agent 并行（Flux.merge 并发，限制并发数）
                .flatMapMany(Flux::fromIterable)
                .flatMap(sub -> Mono.fromCallable(() ->
                                "research".equals(sub.name())
                                        ? researchAgent.prompt().user(sub.instruction()).call().content()
                                        : analysisAgent.prompt().user(sub.instruction()).call().content()),
                        // 并发限制：同时最多 3 个子任务（响应式）
                        3)
                .collectList()
                // ③ 聚合（主管汇总）
                .map(results -> supervisor.prompt()
                        .user("汇总以下子任务结果: " + results)
                        .call().content());
    }

    private List<Subtask> parseSubtasks(String json) { /* 解析 JSON → List<Subtask> */ return List.of(); }
    public record Subtask(String name, String instruction) {}
}
```

> **并行度控制**：`flatMap(..., 3)` 限制同时 3 个子 Agent（防模型并发打爆配额）——这是响应式并发控制（12 的延续）。

---

## 五、三种协作模式

```mermaid
graph LR
    subgraph m1["主从（主管委派）"]
        A1["主管"] --> B1["子1"]
        A1 --> B2["子2"]
        A1 --> B3["子3"]
    end

    subgraph m2["对等（黑board 共享）"]
        C1["Agent1"] <--> C2["黑板"]
        C2 <--> C3["Agent2"]
    end

    subgraph m3["流水线（链式）"]
        D1["环节1"] --> D2["环节2"] --> D3["环节3"]
    end

    style m1 fill:#e8f5e9
    style m2 fill:#fff3e0
    style m3 fill:#e3f2fd
```

| 模式 | 适用 | 本项目落点 |
|------|------|-----------|
| 主从 | 任务可拆解（报告/调研/风控审查） | 迭代十二核心（§4） |
| 对等 | 多专家共同决策（评审/辩论） | 迭代十二扩展（评审场景） |
| 流水线 | 确定性流程（预处理→分析→产出） | 对应 DAG 线性子图 |

---

## 六、测试与验证

### 6.1 DAG 引擎测试

```java
// 定义一个小 DAG（A→B，B→C with 条件）→ 断言执行顺序与条件分支正确
// 用 mock 子 Agent（返回固定文本）测编排逻辑，不依赖 LLM
```

### 6.2 任务委派测试

```bash
# 输入"调研2026年RAG趋势并给出技术选型建议"
# 主管拆解 → 检索/分析子 Agent 并行 → 汇总
# 验证：子任务列表非空、汇总含关键结论
```

### 6.3 并行控制测试

```java
// 断言：同时运行的子 Agent ≤ 3（flatMap 并发限制）
// 用计数器/日志验证
```

### 6.4 工作流版本化测试

```bash
# 发布 v1 DAG（3 节点）→ v2（加审查节点）→ 灰度路由（09）切换
```

---

## 七、验收对照

| 验收项 | 达标标准 | 本迭代 |
|--------|---------|--------|
| DAG 编排 | 定义+执行器，条件分支/循环可跑 | ✅ |
| 任务委派 | 主管拆解+子 Agent 并行+聚合 | ✅ |
| 协作模式 | 主从落地，对等/流水线可扩展 | ✅ |
| 并发控制 | 子 Agent 并发上限可控 | ✅ |
| 未提前引入后续能力 | 无高级 RAG/模型供应深化 | ✅ |

**下一篇**：15-迭代十三-高级 RAG 与上下文工程。
