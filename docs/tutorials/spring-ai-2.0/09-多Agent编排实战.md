# L2 多 Agent 编排实战（Spring AI 2.0）

> 本文回答：**什么时候需要多 Agent？怎么编排？单 Agent 什么时候会撞墙？**
>
> Spring AI 2.0 本身不带多 Agent 框架，但生态里 `Spring AI Alibaba Graph` 是首选编排工具。
>
> 前置：[`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) + [`./05-MCP协议全解.md`](./05-MCP协议全解.md)
> 预计：1.5 天

---

## 0. 认知地图

```
单 Agent（一个 ChatClient + N 个 Tool）
    ↓
能力边界：工具数量爆炸（10+）→ 决策变差；prompt 太长；上下文炸
    ↓
多 Agent（按职责拆分，互相协作）
    ├── 路由模式（Router）
    ├── 管道模式（Pipeline）
    ├── 协作模式（Collaborator）
    └── 监督模式（Supervisor）
```

---

## 1. 什么时候需要多 Agent

### 1.1 单 Agent 的天花板

一个 Agent 的能力受限于：

1. **Prompt 容量**：工具描述 + system prompt + memory 加起来不能超模型 context。
2. **决策质量**：Anthropic 实测，工具数 > 10 时 LLM 选错工具的概率显著上升。
3. **专业深度**：一个 prompt 想兼顾"客服话术"+"代码生成"+"数据分析"，每个都不精。
4. **可观测性**：所有逻辑挤一个 prompt，出错难定位。

### 1.2 多 Agent 的代价

- **复杂度上升**：状态管理、错误处理、超时控制都要自己写。
- **延迟翻倍**：Agent 间通信每次都是一次 LLM 调用。
- **Token 翻倍**：每个 Agent 都要重新喂 context。
- **调试困难**：流程非线性的，复现问题难。

**结论**：**优先单 Agent + Tool**，撞墙了再上多 Agent。

### 1.3 该上多 Agent 的信号

| 信号 | 说明 |
|------|------|
| 工具超过 15 个 | 工具 schema 已经塞爆 prompt，决策变差 |
| 业务流程明确分阶段 | 例如"采集 → 分析 → 报告"，每个阶段用不同 prompt |
| 多角色协作 | 客服 / 工程师 / 经理各有专长 |
| 需要并行 | 同时调多个 LLM 拿不同视角的答案 |
| 子任务可重用 | 同一个"摘要 Agent"被多个流程用 |

---

## 2. 四种主流编排模式

### 2.1 Router（路由）

```
用户请求 → [Router Agent] ──┬─→ [客服 Agent]
                            ├─→ [技术 Agent]
                            └─→ [销售 Agent]
```

Router 用 LLM 判断该走哪条路。

**适用**：业务领域边界清晰。

**陷阱**：Router 判断错就全错。Router 用便宜模型 + few-shot 强化。

### 2.2 Pipeline（管道）

```
[Agent A: 采集] → [Agent B: 分析] → [Agent C: 报告]
```

串行流水线，每个 Agent 输出是下一个的输入。

**适用**：阶段明确（如 ETL、报告生成）。

**陷阱**：上游错下游全错。每一步要有 schema 校验。

### 2.3 Collaborator（协作）

```
[Planner] ←→ [Coder] ←→ [Reviewer]
```

多 Agent 循环协作，互相评审。

**适用**：高质量要求场景（如代码生成 + code review）。

**陷阱**：可能死循环。设最大轮数 + 终止条件。

### 2.4 Supervisor（监督）

```
[Supervisor] ──┬─→ [Worker 1]
              ├─→ [Worker 2]
              ├─→ [Worker 3]
              └─→ (汇总)
```

Supervisor 是大脑，动态分配任务给 Worker，收集结果再决策下一步。

**适用**：复杂任务（如多源数据采集 + 综合）。

**陷阱**：Supervisor 自己也是 LLM，可能做出离谱决策。

---

## 3. Spring AI Alibaba Graph：编排框架

Spring AI 2.0 自身不带多 Agent 编排，但同生态的 **Spring AI Alibaba Graph**（基于 Alibaba 的 AgentScope 思路）是 Java 生态最成熟的选择。

### 3.1 核心概念

| 概念 | 类比 | 含义 |
|------|------|------|
| **State** | 流程的"内存" | 整个图的共享数据，每个节点可读写 |
| **Node** | 一个 Agent | 接收 state，处理后返回 state 的增量 |
| **Edge** | 状态机迁移 | 决定下一步去哪个节点（固定 or 条件） |
| **Graph** | 整个流程 | 节点 + 边构成的有向图 |

### 3.2 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-graph</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.3 最小例子：Router 模式

```java
// org.demo02.agent.graph.RouterGraphConfig
// 本代码仅作学习材料参考

@Configuration
public class RouterGraphConfig {

    @Bean
    public StateGraph routerGraph(ChatClient chatClient) throws GraphStateException {

        // 1. 定义 State 结构（什么字段会被节点读写）
        StateKey<String> USER_QUERY = StateKey.of("user_query");
        StateKey<String> ROUTE = StateKey.of("route");
        StateKey<String> ANSWER = StateKey.of("answer");

        // 2. 定义节点：路由判断
        AsyncNodeAction routerNode = state -> {
            String query = state.value(USER_QUERY).orElse("");
            String route = chatClient.prompt()
                    .system("""
                        判断用户问题属于哪个域。只输出一个词：
                        - billing（账单/支付/退款）
                        - tech（技术故障）
                        - sales（产品咨询/购买）
                        """)
                    .user(query)
                    .call()
                    .content()
                    .trim()
                    .toLowerCase();
            return Map.of("route", route);
        };

        // 3. 定义节点：客服 Agent
        AsyncNodeAction billingNode = state -> {
            String answer = chatClient.prompt()
                    .system("你是客服，处理账单问题。")
                    .user(state.value(USER_QUERY).orElse(""))
                    .call()
                    .content();
            return Map.of("answer", answer);
        };

        AsyncNodeAction techNode = state -> {
            String answer = chatClient.prompt()
                    .system("你是技术支持，处理故障问题。")
                    .user(state.value(USER_QUERY).orElse(""))
                    .call()
                    .content();
            return Map.of("answer", answer);
        };

        AsyncNodeAction salesNode = state -> {
            String answer = chatClient.prompt()
                    .system("你是销售顾问，介绍产品。")
                    .user(state.value(USER_QUERY).orElse(""))
                    .call()
                    .content();
            return Map.of("answer", answer);
        };

        // 4. 条件边：根据 route 字段决定下一步
        AsyncEdgeAction guard = state -> {
            String route = state.value(ROUTE).orElse("tech");
            return switch (route) {
                case "billing" -> "billing";
                case "sales" -> "sales";
                default -> "tech";
            };
        };

        // 5. 组装图
        return StateGraph.graph("customer-service-router")
                .addNode("router", routerNode)
                .addNode("billing", billingNode)
                .addNode("tech", techNode)
                .addNode("sales", salesNode)
                .addEdge(START, "router")
                .addConditionalEdges("router", guard)
                .addEdge("billing", END)
                .addEdge("tech", END)
                .addEdge("sales", END)
                .compile();
    }
}
```

### 3.4 调用图

```java
@RestController
@RequestMapping("/demo02/agent")
public class AgentController {

    private final StateGraph routerGraph;

    @GetMapping("/route")
    public String route(@RequestParam String q) throws Exception {
        Map<String, Object> result = routerGraph.invoke(
                Map.of("user_query", q)
        ).get();
        return (String) result.get("answer");
    }
}
```

测试：

```bash
curl "http://127.0.0.1:8080/demo02/agent/route?q=我上个月的账单怎么多了10块"
# → 走 billing 节点
```

---

## 4. 实战场景：技术报告生成 Pipeline

### 4.1 场景描述

输入：一个技术主题（如 "Spring AI 2.0 的 Advisor 链"）。

输出：一份结构化报告（含大纲、各章节、参考文献）。

流程：
```
[Outliner 拆大纲] → [Writer 写章节] ×N → [Reviewer 审稿] → [Formatter 格式化]
```

### 4.2 完整代码（参考）

```java
// org.demo02.agent.graph.ReportPipelineGraph
// 本代码仅作学习材料参考

@Configuration
public class ReportPipelineGraph {

    private final ChatClient chatClient;

    public ReportPipelineGraph(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Bean
    public StateGraph reportGraph() throws GraphStateException {

        // Outliner：根据主题生成大纲
        AsyncNodeAction outliner = state -> {
            String topic = state.value(StateKey.of("topic")).orElse("");
            String outline = chatClient.prompt()
                    .system("""
                        你是技术大纲设计专家。把主题拆成 3-5 个章节，
                        输出 JSON 数组，每个元素是 {"chapter": "标题", "key_points": ["要点1","要点2"]}
                        """)
                    .user(topic)
                    .call()
                    .content();
            return Map.of("outline", outline);
        };

        // Writer：循环写每一章节
        AsyncNodeAction writer = state -> {
            String topic = state.value(StateKey.of("topic")).orElse("");
            String outline = state.value(StateKey.of("outline")).orElse("[]");
            int currentChapter = state.value(StateKey.of("current_chapter")).orElse(0);

            List<Chapter> chapters = parseOutline(outline);
            if (currentChapter >= chapters.size()) {
                return Map.of("done", true);
            }

            Chapter ch = chapters.get(currentChapter);
            String content = chatClient.prompt()
                    .system("""
                        你是技术作者，写一章节内容。
                        要求：800-1500 字，有代码示例，结构清晰。
                        """)
                    .user("主题：%s\n章节：%s\n要点：%s".formatted(
                            topic, ch.title(), String.join("; ", ch.keyPoints())))
                    .call()
                    .content();

            return Map.of(
                    "current_chapter", currentChapter + 1,
                    "chapter_content_" + currentChapter, content,
                    "next_node", "writer"  // 自循环
            );
        };

        // Reviewer：审稿，发现问题返回修复
        AsyncNodeAction reviewer = state -> {
            String fullContent = assembleContent(state);
            String review = chatClient.prompt()
                    .system("""
                        你是技术审稿人。检查报告：
                        1. 技术准确性
                        2. 章节连贯性
                        3. 代码示例正确性
                        输出 JSON：{"pass": true/false, "issues": [...]}
                        """)
                    .user(fullContent)
                    .call()
                    .content();
            return Map.of("review", review);
        };

        // Formatter：Markdown 格式化输出
        AsyncNodeAction formatter = state -> {
            String content = assembleContent(state);
            String formatted = chatClient.prompt()
                    .system("把内容整理为 Markdown，添加目录、标题层级、代码块标记")
                    .user(content)
                    .call()
                    .content();
            return Map.of("final_report", formatted);
        };

        // 边：条件判断 writer 是否完成
        AsyncEdgeAction afterWriter = state -> {
            boolean done = state.value(StateKey.of("done")).orElse(false);
            return done ? "reviewer" : "writer";
        };

        // 边：审稿是否通过
        AsyncEdgeAction afterReview = state -> {
            String review = state.value(StateKey.of("review")).orElse("");
            boolean pass = review.contains("\"pass\": true");
            return pass ? "formatter" : "writer";  // 不过返回 writer 重写
        };

        return StateGraph.graph("report-pipeline")
                .addNode("outliner", outliner)
                .addNode("writer", writer)
                .addNode("reviewer", reviewer)
                .addNode("formatter", formatter)
                .addEdge(START, "outliner")
                .addEdge("outliner", "writer")
                .addConditionalEdges("writer", afterWriter)
                .addConditionalEdges("reviewer", afterReview)
                .addEdge("formatter", END)
                .compile();
    }

    // 省略 parseOutline / assembleContent 等辅助方法
    record Chapter(String title, List<String> keyPoints) {}
}
```

---

## 5. 状态机思维：把 Agent 流程当状态机设计

### 5.1 设计步骤

```
1. 列出所有节点（Agent）和它们的输入/输出 schema
2. 列出所有可能的转移条件
3. 画状态转移图
4. 每个节点设定幂等性（重试不会出问题）
5. 设全局超时 + 最大轮数
6. 设计 State 结构（字段命名清晰）
```

### 5.2 State 设计原则

```java
// ❌ 反模式：state 塞太多东西
record BadState(
    String query, String answer, Map<String, Object> memory,
    List<String> history, int retryCount, Date timestamp,
    String userId, String tenantId, String sessionId,
    // ... 一堆字段
) {}

// ✅ 正模式：分组
record GoodState(
    // 输入区（只读）
    InputContext input,
    // 中间结果区（节点间传递）
    IntermediateResults intermediate,
    // 输出区（最终结果）
    Output output,
    // 元数据区（监控用）
    Metadata meta
) {}
```

### 5.3 错误处理

每个节点都要考虑：

```java
AsyncNodeAction safeNode = state -> {
    try {
        String result = chatClient.prompt()...call().content();
        return Map.of("answer", result, "node_status", "success");
    } catch (Exception e) {
        // 1. 记录错误
        log.error("Node failed", e);
        // 2. 返回降级结果或转 error 节点
        return Map.of("node_status", "error",
                "error_message", e.getMessage(),
                "next_node", "error_handler");
    }
};
```

---

## 6. 从单 Agent 升级到多 Agent 的路径

### 6.1 渐进式升级

```
阶段 1：单 Agent + 5 个 Tool
        ↓ 工具数膨胀到 15
阶段 2：单 Agent + ToolSearchToolCallingAdvisor（自动检索相关工具）
        ↓ 还是不够，业务领域分明
阶段 3：Router 模式，3 个领域 Agent
        ↓ 流程变复杂，需要多步
阶段 4：Pipeline 模式
        ↓ 需要反馈循环
阶段 5：Collaborator / Supervisor 模式
```

### 6.2 不要一上来就上多 Agent

新手最容易踩的坑：**第一次学多 Agent 就把所有逻辑拆成 5 个 Agent**。结果：

- 每个 Agent 之间的 context 传递混乱。
- 一次请求 10+ 次 LLM 调用，延迟 30s+。
- 一个 Agent 出错整个流程崩。

**建议**：先用单 Agent 跑通业务，遇到具体痛点（如某个工具集和另一个完全无关）再拆。

---

## 7. 调试与可观测

### 7.1 每个节点记录 trace

```java
AsyncNodeAction tracedNode(String name, AsyncNodeAction delegate) {
    return state -> {
        long start = System.currentTimeMillis();
        Map<String, Object> result = delegate.apply(state);
        long duration = System.currentTimeMillis() - start;
        // 记录到 trace 系统（如 Zipkin / Langfuse）
        tracer.span(name).tag("duration_ms", duration).record(result);
        return result;
    };
}
```

### 7.2 可视化 Graph

Spring AI Alibaba Graph 提供 `GraphVisualizer`：

```java
String mermaid = graph.toMermaid();  // 输出 Mermaid 流程图语法
```

把 mermaid 贴到 markdown 渲染，能直观看到整个图。

### 7.3 状态回放

把每次 invoke 的 state 序列化到数据库（Event Sourcing 思路），出问题时能精确回放哪一步出错：

```java
// 每次 state 更新都落盘
state.persistTo(stateRepo);
```

---

## 8. 实战避坑

### 8.1 "Agent 死循环"

**症状**：reviewer 总说"不通过"，writer 一直重写。

**原因**：reviewer 的"通过"标准太严，或 writer 永远达不到。

**解决**：

- 加全局最大轮数（maxIterations=3）。
- reviewer 给出具体可执行的修改建议而非模糊要求。
- 用计数器，达到 N 次直接走兜底节点。

### 8.2 "Router 判断错"

**症状**：用户问"我账单错了"被路由到 tech Agent。

**原因**：Router 用了廉价模型，理解能力不够。

**解决**：

- Router 用更强的模型（如 GPT-4），Worker 用便宜的。
- Router prompt 加 few-shot 例子。
- 多个候选 Router 投票。

### 8.3 "State 字段冲突"

**症状**：两个节点都写 `result` 字段，互相覆盖。

**解决**：

- 字段命名加前缀（`router_result` / `writer_result`）。
- State 用结构化对象而非裸 Map。

### 8.4 "延迟翻倍"

**原因**：4 个节点串行 = 4 次 LLM 调用，延迟 4 倍。

**解决**：

- 能并行的节点用 `parallel` 而非串行。
- 简单节点（如 Router）用便宜小模型。
- 缓存中间结果（同 query 不重算）。

### 8.5 "Token 爆炸"

**原因**：每个 Agent 都把全部 context 喂一遍。

**解决**：

- Agent 间只传必要字段（摘要 + 关键信息），不传完整对话历史。
- 长文本先用 summarizer 压缩再传。

---

## 9. 选型对比

| 框架 | 语言 | 特点 | 适合 |
|------|------|------|------|
| **Spring AI Alibaba Graph** | Java | Spring 生态深度集成，状态机式 | Java 工程师首选 |
| **LangGraph** | Python | 业界标杆，思路最完整 | Python 项目 |
| **LangChain4j** | Java | 自己的编排思路 | 已经在用 LangChain4j 的项目 |
| **手撸** | Java | 自己写 if/else + 状态 | 极简场景（< 3 节点） |

**推荐**：Java 项目用 Spring AI Alibaba Graph，可以无缝接 Spring AI 的 ChatClient。

---

## 10. 实战任务

1. 实现 Router 模式：3 个领域 Agent（客服/技术/销售），用 LLM 路由。
2. 扩展 Router：加 fallback 节点，路由置信度低时走通用 Agent。
3. 实现 Pipeline 模式：技术报告生成（Outliner → Writer → Reviewer → Formatter）。
4. 给 Pipeline 加最大轮数限制 + 错误兜底。
5. 把 Graph 输出为 Mermaid 图，贴到 markdown 检查流程。
6. （进阶）实现 Supervisor 模式：Supervisor 动态决定调用哪个 Worker。
7. （选做）接入 Langfuse，让多 Agent 流程可视化呈现。

---

## 11. 理解检查

1. 单 Agent 在什么场景下应该升级到多 Agent？
2. Router / Pipeline / Collaborator / Supervisor 四种模式各适合什么场景？
3. 多 Agent 的代价是什么（延迟、Token、复杂度）？
4. State 字段设计的最佳实践是什么？
5. 怎么防止 Agent 死循环？
6. 为什么建议从单 Agent 渐进升级而非一开始就拆？

---

## 12. 进 L2 下一篇之前的能力确认

完成本篇你应该能：

- [ ] 说出单 Agent 撞墙的 4 个信号
- [ ] 画出 4 种编排模式的状态图
- [ ] 用 Spring AI Alibaba Graph 写一个 Router 图
- [ ] 设计合理的 State 结构
- [ ] 处理 Agent 死循环、Router 误判等常见问题
- [ ] 解释为什么不要一开始就上多 Agent

---

## 13. 相关文档

- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— 单 Agent 基础
- [`./05-MCP协议全解.md`](./05-MCP协议全解.md) —— 跨进程 Agent 协作
- [`./14-可观测性与成本治理.md`](./14-可观测性与成本治理.md) —— 多 Agent trace
- [Spring AI Alibaba Graph 文档](https://java2ai.com/docs/dev/graph/overview/) —— 官方文档

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
