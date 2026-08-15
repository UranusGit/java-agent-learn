# 08-多 Agent 协作

> **定位**：讲透多 Agent 协作模式的完整体系——为什么需要多 Agent、三大协作拓扑（中心化编排、去中心化对等、分层委派）、Agent 间通信机制、Spring AI 2.0 中通过多个 ChatClient 实例编排实现。读完这篇，你能设计出多角色协作的 Agent 系统，让"专业的事交给专业的 Agent"。
>
> **读者画像**：已经掌握 ReAct 和 Plan-and-Execute 模式，需要面对单 Agent 无法胜任的复杂任务，设计多角色协作系统的开发者。
>
> **前置阅读**：[06-ReAct 推理模式](07-ReAct推理模式.md)、[07-Plan-and-Execute 模式](08-Plan-and-Execute模式.md)。

---

## 1. 为什么需要多 Agent 协作

### 1.1 单 Agent 的瓶颈

一个 Agent 塞进所有工具、所有指令、所有上下文——这在简单场景没问题，但随着任务复杂度增长，单 Agent 模式会撞上几堵墙：

```mermaid
graph TB
    subgraph 单Agent瓶颈["单 Agent 的四大瓶颈"]
        W1["🔧 工具爆炸<br/>一个 Agent 注册50+工具<br/>LLM 选择困难，准确率下降"]
        W2["🧠 指令冲突<br/>System Prompt 越写越长<br/>指令之间互相矛盾"]
        W3["📦 上下文爆炸<br/>对话历史越来越长<br/>超出上下文窗口"]
        W4["🎯 职责模糊<br/>什么都做<br/>什么都做不好"]
    end

    style 单Agent瓶颈 fill:#ffcdd2
```

举个例子：一个客服 Agent 同时负责售前咨询、订单查询、售后工单、技术支持、投诉升级——它需要注册几十个工具，System Prompt 需要覆盖所有场景的规则。结果是：LLM 在工具选择上频繁出错，回复质量随对话轮次下降，维护成本直线上升。

### 1.2 多 Agent 的核心优势

多 Agent 模式将复杂任务拆分给多个**职责单一的专职 Agent**，每个 Agent 只关注自己的领域：

```mermaid
graph TB
    subgraph 多Agent解决["多 Agent 的解决思路"]
        S1["✅ 职责分离<br/>每个 Agent 只负责一个领域"]
        S2["✅ 工具聚焦<br/>每个 Agent 只注册相关工具"]
        S3["✅ 上下文隔离<br/>每个 Agent 维护自己的对话"]
        S4["✅ 可独立调优<br/>每个 Agent 的 Prompt/模型可单独优化"]
    end

    style 多Agent解决 fill:#c8e6c9
```

### 1.3 现实类比

多 Agent 协作就像一个企业组织：

| 企业组织 | Agent 系统 |
|---------|-----------|
| 前台客服 | Router Agent（分流用户请求） |
| 销售专员 | Sales Agent（负责产品咨询） |
| 订单管理员 | Order Agent（负责查单/改单） |
| 技术支持 | Tech Agent（负责技术问题） |
| 客诉处理 | Escalation Agent（负责投诉升级） |
| 部门经理 | Orchestrator Agent（协调所有 Agent） |

---

## 2. 协作拓扑模式总览

多 Agent 协作的核心问题是**如何组织 Agent 之间的关系**。有三种基础拓扑模式。

```mermaid
graph TB
    subgraph 中心化["模式一：中心化编排（Hub-and-Spoke）"]
        HUB_C["🧩 Orchestrator<br/>（编排者）"]
        HUB_C --> A1["Agent A"]
        HUB_C --> A2["Agent B"]
        HUB_C --> A3["Agent C"]
    end

    subgraph 去中心化["模式二：去中心化对等（P2P）"]
        P1["Agent A"] <--> P2["Agent B"]
        P2 <--> P3["Agent C"]
        P1 <--> P3
    end

    subgraph 分层["模式三：分层委派（Hierarchical）"]
        ROOT["🧩 Root Agent"]
        ROOT --> M1["Manager A"]
        ROOT --> M2["Manager B"]
        M1 --> W1["Worker A1"]
        M1 --> W2["Worker A2"]
        M2 --> W3["Worker B1"]
        M2 --> W4["Worker B2"]
    end

    style 中心化 fill:#e3f2fd
    style 去中心化 fill:#fff9c4
    style 分层 fill:#c8e6c9
```

---

## 3. 模式一：中心化编排（Hub-and-Spoke）

### 3.1 架构

```mermaid
graph TB
    USER["用户请求"] --> ORCH["Orchestrator<br/>（编排者 Agent）"]
    ORCH --> ROUTE{"路由决策<br/>交给谁处理？"}

    ROUTE -->|"售前咨询"| SALES["Sales Agent<br/>（产品/价格/推荐）"]
    ROUTE -->|"订单问题"| ORDER["Order Agent<br/>（查单/改单/物流）"]
    ROUTE -->|"技术支持"| TECH["Tech Agent<br/>（故障诊断/文档）"]
    ROUTE -->|"投诉升级"| ESC["Escalation Agent<br/>（投诉/赔偿）"]

    SALES --> ORCH
    ORDER --> ORCH
    TECH --> ORCH
    ESC --> ORCH
    ORCH --> REPLY["汇总回复用户"]

    style ORCH fill:#e3f2fd
    style ROUTE fill:#fff9c4
```

### 3.2 工作原理

1. **用户请求先到 Orchestrator**——它不直接回答，而是分析用户意图
2. **Orchestrator 路由**——决定将请求转发给哪个专职 Agent
3. **专职 Agent 处理**——使用自己的工具和上下文生成回复
4. **回复经 Orchestrator 返回**——可选地做格式化或补充

### 3.3 Spring AI 实现

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

// Spring AI 2.0.0 — 中心化编排：Router + 专职 Agent

@Component
public class CustomerServiceOrchestrator {

    // Router：决定交给哪个 Agent
    private final ChatClient router;
    // 专职 Agent
    private final ChatClient salesAgent;
    private final ChatClient orderAgent;
    private final ChatClient techAgent;
    private final ChatClient escalationAgent;

    public CustomerServiceOrchestrator(
            ChatClient.Builder builder,
            SalesTools salesTools,
            OrderTools orderTools,
            TechTools techTools
    ) {
        // Router Agent：不做业务，只做路由决策
        this.router = builder
                .defaultSystem("""
                    你是客服系统的路由中心。分析用户意图，输出要转交的部门名称：
                    - SALES：产品咨询、价格、推荐
                    - ORDER：订单查询、物流、修改订单
                    - TECH：技术问题、故障排查、使用帮助
                    - ESCALATION：投诉、赔偿、升级处理

                    只输出部门名称，不要输出其他内容。
                    """)
                .build();

        // Sales Agent：只管销售
        this.salesAgent = builder
                .defaultSystem("你是销售顾问，负责产品推荐和价格咨询。")
                .defaultTools(salesTools)
                .build();

        // Order Agent：只管订单
        this.orderAgent = builder
                .defaultSystem("你是订单管理专员，负责查询和管理订单。")
                .defaultTools(orderTools)
                .build();

        // Tech Agent：只管技术
        this.techAgent = builder
                .defaultSystem("你是技术支持工程师，负责解答技术问题。")
                .defaultTools(techTools)
                .build();

        // Escalation Agent：只管投诉
        this.escalationAgent = builder
                .defaultSystem("你是客户关系经理，负责处理投诉和赔偿。")
                .build();
    }

    public String handle(String userMessage, String conversationId) {
        // 1. 路由决策
        String department = router.prompt()
                .user(userMessage)
                .call()
                .content()
                .trim();

        // 2. 转发给对应 Agent
        return switch (department) {
            case "SALES" -> salesAgent.prompt().user(userMessage).call().content();
            case "ORDER" -> orderAgent.prompt().user(userMessage).call().content();
            case "TECH" -> techAgent.prompt().user(userMessage).call().content();
            case "ESCALATION" -> escalationAgent.prompt().user(userMessage).call().content();
            default -> "抱歉，我无法理解您的需求，请尝试更详细地描述您的问题。";
        };
    }
}
```

### 3.4 优劣分析

| 维度 | 评价 |
|------|------|
| **实现难度** | 低——Orchestrator 做路由，Agent 独立工作 |
| **可扩展** | 高——新增 Agent 只需增加分支 |
| **Agent 耦合** | 低——Agent 之间互不感知 |
| **多步协作** | 弱——每次只交给一个 Agent |
| **上下文传递** | 需要 Orchestrator 显式管理 |
| **单点故障** | Orchestrator 挂了全系统停 |

---

## 4. 模式二：去中心化对等（P2P）

### 4.1 架构

```mermaid
graph LR
    A["Research Agent<br/>（研究员）"]
    B["Writer Agent<br/>（撰稿人）"]
    C["Reviewer Agent<br/>（审稿人）"]
    D["Fact-check Agent<br/>（事实核查员）"]

    A -- "提供素材" --> B
    B -- "提交初稿" --> C
    C -- "修改建议" --> B
    C -- "核查请求" --> D
    D -- "事实报告" --> C
    A -- "补充资料" --> D

    style A fill:#e3f2fd
    style B fill:#fff9c4
    style C fill:#c8e6c9
    style D fill:#f3e5f5
```

### 4.2 工作原理

没有中心编排者，每个 Agent 可以直接与任意其他 Agent 通信：

1. **Agent 之间直接对话**——通过消息传递（如将一个 Agent 的输出作为另一个的输入）
2. **动态协作**——Agent 根据需要决定找谁协作
3. **无单点控制**——每个 Agent 自主决策

### 4.3 Spring AI 实现

```java
import org.springframework.ai.chat.client.ChatClient;
import java.util.*;

// Spring AI 2.0.0 — 去中心化协作：Agent 之间通过"消息"通信

@Component
public class PeerToPeerCollaboration {

    private final ChatClient researcher;
    private final ChatClient writer;
    private final ChatClient reviewer;

    // 共享消息板——所有 Agent 可以读取彼此的输出
    private final Map<String, List<String>> messageBoard = new ConcurrentHashMap<>();

    public PeerToPeerCollaboration(ChatClient.Builder builder) {
        this.researcher = builder
                .defaultSystem("""
                    你是研究员。你收到一个主题后，列出关键事实和要点。
                    如果你需要数据支持，请求 Writer 或 Reviewer 协助。
                    """)
                .build();

        this.writer = builder
                .defaultSystem("""
                    你是撰稿人。基于研究员提供的素材，撰写文章。
                    如果不确定某些事实，请求 Reviewer 审核。
                    """)
                .build();

        this.reviewer = builder
                .defaultSystem("""
                    你是审稿人。检查文章的事实准确性、逻辑性和语言质量。
                    如发现问题，指出具体修改建议。
                    """)
                .build();
    }

    public String collaborate(String topic) {
        // 1. 研究员收集素材
        messageBoard.put("research", new ArrayList<>());
        String researchResult = researcher.prompt()
                .user("研究主题：" + topic + "\n列出关键事实和要点。")
                .call()
                .content();
        messageBoard.get("research").add(researchResult);

        // 2. 撰稿人基于素材写初稿
        String draft = writer.prompt()
                .user("主题：" + topic + "\n研究员素材：" + researchResult + "\n请撰写文章。")
                .call()
                .content();
        messageBoard.computeIfAbsent("drafts", k -> new ArrayList<>()).add(draft);

        // 3. 审稿人审核
        String review = reviewer.prompt()
                .user("请审核以下文章：\n" + draft)
                .call()
                .content();
        messageBoard.computeIfAbsent("reviews", k -> new ArrayList<>()).add(review);

        // 4. 如果审核不通过，撰稿人根据反馈修改（P2P 循环）
        if (review.contains("需要修改") || review.contains("建议")) {
            String revised = writer.prompt()
                    .user("审稿人反馈：" + review + "\n请修改文章。\n原稿：" + draft)
                    .call()
                    .content();
            return revised;
        }

        return draft;
    }
}
```

### 4.4 优劣分析

| 维度 | 评价 |
|------|------|
| **实现难度** | 中——需要设计 Agent 间的消息协议 |
| **灵活性** | 高——Agent 动态决定协作对象 |
| **多步协作** | 强——Agent 可以反复交互 |
| **可控性** | 低——行为路径不可预知 |
| **死循环风险** | 高——两个 Agent 可能互相踢皮球 |
| **调试难度** | 高——协作链路不透明 |

---

## 5. 模式三：分层委派（Hierarchical）

### 5.1 架构

```mermaid
graph TB
    USER["用户：<br/>'帮我做一次全面的数据分析'"] --> ROOT["Root Agent<br/>（CEO：任务拆解+委派）"]

    ROOT -->|"子任务1：数据采集"| M1["Data Manager<br/>（数据部经理）"]
    ROOT -->|"子任务2：分析建模"| M2["Analysis Manager<br/>（分析部经理）"]
    ROOT -->|"子任务3：报告输出"| M3["Report Manager<br/>（报告部经理）"]

    M1 --> W1["Extractor Agent<br/>（数据抽取）"]
    M1 --> W2["Cleaner Agent<br/>（数据清洗）"]

    M2 --> W3["Statistician Agent<br/>（统计分析）"]
    M2 --> W4["ML Engineer Agent<br/>（模型预测）"]

    M3 --> W5["Chart Agent<br/>（图表生成）"]
    M3 --> W6["Writer Agent<br/>（报告撰写）"]

    style ROOT fill:#e3f2fd
    style M1 fill:#fff9c4
    style M2 fill:#fff9c4
    style M3 fill:#fff9c4
    style W1 fill:#c8e6c9
    style W2 fill:#c8e6c9
    style W3 fill:#c8e6c9
    style W4 fill:#c8e6c9
    style W5 fill:#c8e6c9
    style W6 fill:#c8e6c9
```

### 5.2 工作原理

分层委派模拟企业组织架构：

1. **Root Agent（CEO）** 拆解大任务为子任务，委派给 Manager Agent
2. **Manager Agent（部门经理）** 进一步拆解子任务，分配给 Worker Agent
3. **Worker Agent（一线员工）** 执行具体工作，返回结果
4. 结果逐层**向上汇总**，最终由 Root Agent 输出

### 5.3 Spring AI 实现

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

// Spring AI 2.0.0 — 分层委派：Root → Manager → Worker

@Component
public class HierarchicalCollaboration {

    private final ChatClient rootAgent;
    private final ChatClient dataManager;
    private final ChatClient analysisManager;
    private final ChatClient reportManager;

    public HierarchicalCollaboration(
            ChatClient.Builder builder,
            DataTools dataTools,
            AnalysisTools analysisTools,
            ReportTools reportTools
    ) {
        // Root Agent：总指挥，只做任务拆解
        this.rootAgent = builder
                .defaultSystem("""
                    你是项目总指挥（CEO）。你的职责：
                    1. 理解用户的大任务
                    2. 拆解为子任务（数据/分析/报告）
                    3. 委派给对应的部门经理
                    4. 汇总各部门的结果，输出最终交付物

                    你不直接执行任何工具，只做协调和汇总。
                    """)
                .build();

        // Data Manager：数据部门
        this.dataManager = builder
                .defaultSystem("""
                    你是数据部经理。负责：
                    1. 数据抽取（从数据库/API）
                    2. 数据清洗（去重/补缺/格式化）
                    将处理好的数据返回给总指挥。
                    """)
                .defaultTools(dataTools)
                .build();

        // Analysis Manager：分析部门
        this.analysisManager = builder
                .defaultSystem("""
                    你是分析部经理。基于数据部提供的数据，负责：
                    1. 统计分析（描述性统计/对比分析）
                    2. 预测建模（趋势预测/分类）
                    将分析结论返回给总指挥。
                    """)
                .defaultTools(analysisTools)
                .build();

        // Report Manager：报告部门
        this.reportManager = builder
                .defaultSystem("""
                    你是报告部经理。基于分析结论，负责：
                    1. 生成可视化图表
                    2. 撰写分析报告
                    将最终报告返回给总指挥。
                    """)
                .defaultTools(reportTools)
                .build();
    }

    public String execute(String userTask) {
        // 第 1 层：Root Agent 拆解任务
        String plan = rootAgent.prompt()
                .user("""
                    任务：%s
                    
                    请将任务拆解为三个子任务，分别交给：
                    1. 数据部（DATA）
                    2. 分析部（ANALYSIS）
                    3. 报告部（REPORT）
                    
                    输出每个子任务的详细描述。
                    """.formatted(userTask))
                .call()
                .content();

        // 第 2 层：并行委派给 Manager（无依赖可并行）
        CompletableFuture<String> dataFuture = CompletableFuture.supplyAsync(() ->
                dataManager.prompt()
                        .user("执行数据部任务：" + plan)
                        .call()
                        .content()
        );

        // 等数据部完成（分析依赖数据）
        String dataResult = dataFuture.join();

        CompletableFuture<String> analysisFuture = CompletableFuture.supplyAsync(() ->
                analysisManager.prompt()
                        .user("基于以下数据执行分析任务：\n" + dataResult)
                        .call()
                        .content()
        );

        String analysisResult = analysisFuture.join();

        // 报告依赖分析
        String reportResult = reportManager.prompt()
                .user("基于以下分析结论生成报告：\n" + analysisResult)
                .call()
                .content();

        // 第 1 层：Root Agent 汇总
        return rootAgent.prompt()
                .user("""
                    汇总以下各部门结果，输出最终报告：
                    数据部：%s
                    分析部：%s
                    报告部：%s
                    """.formatted(dataResult, analysisResult, reportResult))
                .call()
                .content();
    }
}
```

### 5.4 优劣分析

| 维度 | 评价 |
|------|------|
| **实现难度** | 高——需要设计多层通信协议 |
| **可扩展** | 高——新增分支只需挂到某个 Manager 下 |
| **职责清晰** | 极高——每个 Agent 有明确层级和职责 |
| **可追踪** | 高——层级结构清晰，每层有明确输出 |
| **LLM 调用** | 多——每层至少一次调用 |
| **延迟** | 高——层层委派导致总时间长 |

---

## 6. 三大拓扑对比总结

```mermaid
graph TB
    subgraph 对比维度["协作拓扑对比"]
        C1["中心化编排"]
        C2["去中心化对等"]
        C3["分层委派"]
    end

    C1 --> C1F["✅ 简单可靠<br/>❌ 单点瓶颈<br/>适合：路由分发场景"]
    C2 --> C2F["✅ 灵活自主<br/>❌ 难以控制<br/>适合：创意协作场景"]
    C3 --> C3F["✅ 结构清晰<br/>❌ 延迟高<br/>适合：复杂分层任务"]

    style C1F fill:#e3f2fd
    style C2F fill:#fff9c4
    style C3F fill:#c8e6c9
```

| 维度 | 中心化编排 | 去中心化对等 | 分层委派 |
|------|-----------|-------------|---------|
| **复杂度** | 低 | 中 | 高 |
| **灵活性** | 低 | 高 | 中 |
| **可控性** | 高 | 低 | 高 |
| **可追踪** | 高 | 低 | 高 |
| **并行性** | 中（路由后可并行） | 高（Agent 自主发起） | 中（同层可并行） |
| **LLM 调用** | 少（1-2次） | 不定 | 多（每层至少1次） |
| **延迟** | 低 | 不定 | 高 |
| **死循环风险** | 低 | 高 | 中 |
| **适用规模** | 3-5 Agent | 2-4 Agent | 5-15 Agent |

---

## 7. Agent 间通信机制

多 Agent 协作的技术核心是**Agent 之间如何传递信息**。

### 7.1 通信方式对比

```mermaid
graph TB
    subgraph 通信方式["三种 Agent 通信方式"]
        M1["方式一：直接调用<br/>Agent A 的输出<br/>直接作为 Agent B 的输入"]
        M2["方式二：消息板<br/>共享空间<br/>Agent 读写消息"]
        M3["方式三：事件驱动<br/>Agent A 发布事件<br/>订阅者收到通知"]
    end

    style 通信方式 fill:#e3f2fd
```

| 方式 | 实现 | 适用场景 | 优劣 |
|------|------|---------|------|
| **直接调用** | A 的输出作为 B 的 Prompt 参数 | 顺序流水线 | 简单但紧耦合 |
| **消息板** | 共享 Map / Queue | P2P 协作 | 解耦但需同步机制 |
| **事件驱动** | Spring Events / Reactor | 异步通知 | 解耦但调试难 |

### 7.2 直接调用示例

最简单的方式——一个 Agent 的输出直接拼入另一个 Agent 的 Prompt：

```java
// Spring AI 2.0.0 — 直接调用通信
public String directCommunication(String userQuery) {
    // Agent A：信息收集
    String researchInfo = researchAgent.prompt()
            .user("收集关于以下问题的背景信息：" + userQuery)
            .call()
            .content();

    // Agent B：基于 A 的信息生成回复
    return responseAgent.prompt()
            .user("基于以下背景信息回答用户问题。\n背景：" + researchInfo + "\n问题：" + userQuery)
            .call()
            .content();
}
```

### 7.3 消息板示例

Agent 通过共享的"消息板"交换信息，实现松耦合：

```java
// Spring AI 2.0.0 — 消息板通信
public class AgentMessageBoard {

    // 按话题组织的消息存储
    private final Map<String, CopyOnWriteArrayList<AgentMessage>> topics = new ConcurrentHashMap<>();

    public void publish(String topic, String agentName, String content) {
        topics.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                .add(new AgentMessage(agentName, content, Instant.now()));
    }

    public List<AgentMessage> read(String topic) {
        return topics.getOrDefault(topic, List.of());
    }

    public List<AgentMessage> readSince(String topic, Instant timestamp) {
        return read(topic).stream()
                .filter(msg -> msg.timestamp().isAfter(timestamp))
                .toList();
    }

    public record AgentMessage(String agentName, String content, Instant timestamp) {}
}
```

```java
// 使用消息板
@Component
public class BoardBasedCollaboration {

    private final AgentMessageBoard board;
    private final ChatClient researcher;
    private final ChatClient writer;

    public void researchAndWrite(String topic) {
        // 研究员发布到消息板
        String research = researcher.prompt()
                .user("研究主题：" + topic)
                .call()
                .content();
        board.publish("research", "researcher", research);

        // 撰稿人从消息板读取
        var messages = board.read("research");
        String context = messages.stream()
                .map(AgentMessageBoard.AgentMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);

        writer.prompt()
                .user("基于以下研究素材撰写文章：\n" + context)
                .call()
                .content();
    }
}
```

### 7.4 事件驱动示例

利用 Spring 的 ApplicationEvent 机制，实现 Agent 间的异步通信：

```java
// Spring AI 2.0.0 — 事件驱动通信
public class AgentTaskEvent extends ApplicationEvent {
    private final String taskType;
    private final String payload;
    private final String requesterAgent;

    public AgentTaskEvent(Object source, String taskType, String payload, String requesterAgent) {
        super(source);
        this.taskType = taskType;
        this.payload = payload;
        this.requesterAgent = requesterAgent;
    }
    // getters...
}

@Component
public class TechAgentListener {

    private final ChatClient techAgent;

    @EventListener
    @Async
    public void onTechTask(AgentTaskEvent event) {
        if ("TECH".equals(event.getTaskType())) {
            String result = techAgent.prompt()
                    .user(event.getPayload())
                    .call()
                    .content();
            // 发布结果事件
            eventPublisher.publishEvent(
                    new AgentResultEvent(this, result, event.getRequesterAgent()));
        }
    }
}
```

---

## 8. 多 Agent 的上下文与记忆管理

### 8.1 上下文隔离原则

每个 Agent 应该有**独立的对话上下文和记忆**，避免互相污染：

```mermaid
graph TB
    subgraph 上下文隔离["每个 Agent 独立记忆"]
        R["Router Agent<br/>conversationId: router-xxx"]
        S["Sales Agent<br/>conversationId: sales-xxx"]
        O["Order Agent<br/>conversationId: order-xxx"]
        T["Tech Agent<br/>conversationId: tech-xxx"]
    end

    subgraph 共享层["共享层（可选）"]
        SHARED["全局会话摘要<br/>（只存关键信息）"]
    end

    R -.->|"写入摘要"| SHARED
    S -.->|"读取摘要"| SHARED
    O -.->|"读取摘要"| SHARED

    style 上下文隔离 fill:#e3f2fd
    style 共享层 fill:#fff9c4
```

### 8.2 实现方式

```java
// Spring AI 2.0.0 — 多 Agent 各自的 conversationId
@Component
public class MultiAgentMemory {

    private final ChatClient salesAgent;
    private final ChatClient orderAgent;

    public String handle(String userMessage, String sessionId) {
        // Router 决定交给谁
        String department = route(userMessage);

        // 每个 Agent 用独立的 conversationId，记忆互不干扰
        return switch (department) {
            case "SALES" -> salesAgent.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID,
                            "sales-" + sessionId))  // 独立记忆空间
                    .call()
                    .content();
            case "ORDER" -> orderAgent.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID,
                            "order-" + sessionId))  // 独立记忆空间
                    .call()
                    .content();
            default -> "无法处理";
        };
    }
}
```

> → [教程 04-记忆与会话管理]：ChatMemory 的完整机制、短期 vs 长期记忆。

---

## 9. 多 Agent 的容错与降级

### 9.1 Agent 不可用时的降级策略

```java
// Spring AI 2.0.0 — Agent 降级策略
@Component
public class ResilientOrchestrator {

    private final ChatClient specialistAgent;
    private final ChatClient generalAgent;

    public String handle(String userMessage) {
        try {
            // 尝试用专职 Agent
            return specialistAgent.prompt()
                    .user(userMessage)
                    .call()
                    .content();

        } catch (Exception e) {
            // 专职 Agent 不可用，降级到通用 Agent
            log.warn("Specialist agent failed, falling back to general agent", e);
            return generalAgent.prompt()
                    .user(userMessage)
                    .call()
                    .content();
        }
    }
}
```

### 9.2 超时控制

```java
// Spring AI 2.0.0 — Agent 超时控制
public String handleWithTimeout(String userMessage) {
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
            specialistAgent.prompt()
                    .user(userMessage)
                    .call()
                    .content()
    );

    try {
        return future.get(30, TimeUnit.SECONDS);  // 30秒超时
    } catch (TimeoutException e) {
        future.cancel(true);
        return "处理超时，请稍后重试";
    }
}
```

### 9.3 循环检测

多 Agent 协作中，Agent A 把任务推给 B，B 又推回 A——形成死循环。解决方法：

```java
// Spring AI 2.0.0 — 协作深度限制
// ⚠ 修正: 转交计数用请求上下文传递（Reactor Context 思想），而非 ThreadLocal——
// 多 Agent 协作常跨线程/跨服务，ThreadLocal 无法传递（见教程 37 §Context 传递）
public class CollaborationGuard {

    private static final int MAX_HANDOFF = 5;

    public String delegate(String task, ChatClient targetAgent, Map<String, Object> context) {
        int count = (Integer) context.getOrDefault("handoffCount", 0);
        if (count >= MAX_HANDOFF) {
            return "检测到过多转交，已达到最大协作深度。直接回复：" +
                    targetAgent.prompt().user(task).call().content();
        }
        context.put("handoffCount", count + 1);   // 随调用链传递
        return targetAgent.prompt().user(task).call().content();
    }
}
```

---

## 10. 管控分离架构预览

在企业级场景中，多 Agent 系统通常采用**管控分离**架构——将"管理面"（Agent 编排、监控、策略）和"数据面"（Agent 执行、工具调用）分离：

```mermaid
graph TB
    subgraph 管理面["管理面（Control Plane）"]
        POLICY["策略中心<br/>（路由规则/权限/限流）"]
        MONITOR["监控中心<br/>（调用链/指标/告警）"]
        REGISTRY["Agent 注册中心<br/>（服务发现/健康检查）"]
    end

    subgraph 数据面["数据面（Data Plane）"]
        R["Router Agent"]
        S1["Sales Agent"]
        S2["Order Agent"]
        S3["Tech Agent"]
    end

    POLICY -.->|"下发策略"| R
    MONITOR -.->|"采集指标"| S1
    MONITOR -.->|"采集指标"| S2
    REGISTRY -.->|"发现 Agent"| R

    R --> S1
    R --> S2
    R --> S3

    style 管理面 fill:#e3f2fd
    style 数据面 fill:#fff9c4
```

> → [教程 20-管控分离架构]：管理面/数据面的完整设计、服务发现、策略下发。

---

## 11. 协作模式选型决策

```mermaid
graph TB
    START["需要多 Agent 协作？"] --> Q1{"Agent 之间<br/>需要交互吗？"}
    Q1 -->|"否，各管各的"| HUB["✅ 中心化编排<br/>（路由分发）"]
    Q1 -->|"是"| Q2{"有明确的<br/>层级关系吗？"}
    Q2 -->|"是"| Q3{"任务可以<br/>分层分解吗？"}
    Q3 -->|"是"| HIER["✅ 分层委派"]
    Q3 -->|"否"| HUB
    Q2 -->|"否，平级协作"| Q4{"协作路径<br/>可预知吗？"}
    Q4 -->|"是"| PIPE["✅ 流水线<br/>（直接调用）"]
    Q4 -->|"否"| P2P["✅ 去中心化对等"]

    style HUB fill:#e3f2fd
    style HIER fill:#c8e6c9
    style PIPE fill:#fff9c4
    style P2P fill:#f3e5f5
```

---

## 12. 完整示例：多 Agent 旅行规划系统

```java
// Spring AI 2.0.0 — 旅行规划多 Agent 系统
@Configuration
public class TravelMultiAgentConfig {

    @Bean
    ChatClient router(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                    你是旅行规划系统的路由中心。分析用户需求，决定交给哪个专家：
                    - FLIGHT：航班查询和预订
                    - HOTEL：酒店查询和预订
                    - ATTRACTION：景点推荐和门票
                    - ITINERARY：行程编排

                    只输出专家名称。
                    """)
                .build();
    }

    @Bean
    ChatClient flightAgent(ChatClient.Builder builder, FlightTools tools) {
        return builder
                .defaultSystem("你是航班预订专家，帮助用户查询和预订航班。")
                .defaultTools(tools)
                .build();
    }

    @Bean
    ChatClient hotelAgent(ChatClient.Builder builder, HotelTools tools) {
        return builder
                .defaultSystem("你是酒店预订专家，帮助用户查询和预订酒店。")
                .defaultTools(tools)
                .build();
    }

    @Bean
    ChatClient itineraryAgent(ChatClient.Builder builder, AttractionTools tools) {
        return builder
                .defaultSystem("你是旅行规划师，根据航班、酒店、景点信息编排完整行程。")
                .defaultTools(tools)
                .build();
    }
}

@Component
public class TravelOrchestrator {

    private final ChatClient router;
    private final ChatClient flightAgent;
    private final ChatClient hotelAgent;
    private final ChatClient itineraryAgent;

    // 注入...

    public String plan(String userRequest) {
        // 复杂请求：多 Agent 串行协作
        if (userRequest.contains("行程") || userRequest.contains("规划")) {
            // 1. 查航班
            String flights = flightAgent.prompt()
                    .user("查询相关航班：" + userRequest)
                    .call()
                    .content();

            // 2. 查酒店
            String hotels = hotelAgent.prompt()
                    .user("查询相关酒店：" + userRequest)
                    .call()
                    .content();

            // 3. 规划师综合编排
            return itineraryAgent.prompt()
                    .user("""
                        用户需求：%s
                        
                        航班信息：%s
                        酒店信息：%s
                        
                        请编排完整的旅行行程。
                        """.formatted(userRequest, flights, hotels))
                    .call()
                    .content();
        }

        // 简单请求：路由到单一 Agent
        String department = router.prompt().user(userRequest).call().content().trim();
        return switch (department) {
            case "FLIGHT" -> flightAgent.prompt().user(userRequest).call().content();
            case "HOTEL" -> hotelAgent.prompt().user(userRequest).call().content();
            case "ATTRACTION", "ITINERARY" ->
                    itineraryAgent.prompt().user(userRequest).call().content();
            default -> "无法处理您的请求";
        };
    }
}
```

---

## 13. 适用场景与不适用场景

### 适用场景

- 企业级客服系统（售前/订单/技术/投诉多角色协作）
- 复杂分析任务（数据采集→分析→报告的分层委派）
- 内容创作（研究员→撰稿人→审稿人的流水线）
- 旅行/事件规划（多领域专家协作编排）
- 任何单 Agent 工具数超过 15+ 的场景（拆分为多 Agent 聚焦工具）
- 需要不同 Agent 用不同模型的场景（Router 用便宜模型，专家用强模型）

### 不适用场景

- 简单的一问一答（一个 Agent 足够）
- 任务只涉及单一领域（不需要协作）
- 对延迟极度敏感（多 Agent 协作增加延迟）
- Agent 之间需要共享大量上下文（消息传递开销大）
- 团队没有多 Agent 运维能力（复杂度高）

---

## 14. 本章总结

| 概念 | 一句话 |
|------|--------|
| **多 Agent 协作** | 将复杂任务拆分给多个职责单一的专职 Agent |
| **中心化编排** | Orchestrator 统一路由分发，Agent 独立工作——简单可靠 |
| **去中心化对等** | Agent 之间直接通信，自主协作——灵活但难控 |
| **分层委派** | Root→Manager→Worker 层层分解——结构清晰但延迟高 |
| **直接调用通信** | A 的输出作为 B 的输入——最简单 |
| **消息板通信** | 共享空间读写——松耦合 |
| **事件驱动通信** | 发布/订阅——异步解耦 |
| **上下文隔离** | 每个 Agent 维护独立 conversationId |
| **容错降级** | 专职 Agent 不可用时降级到通用 Agent |
| **循环检测** | 限制最大协作深度，防止死循环 |
| **管控分离** | 管理面（策略/监控/注册）与数据面（Agent执行）分离 |

**下一篇**：[09-SSE 流式通信](10-SSE流式通信.md) — WebFlux + ChatClient.stream() 的流式响应实现。

---

> → [教程 06-ReAct 推理模式]：ReAct 循环是单 Agent 内部的推理-执行-观察机制。
> → [教程 07-Plan-and-Execute 模式]：Plan-and-Execute 是分层委派的简化版（Planner=Manager, Executor=Worker）。
> → [教程 20-管控分离架构]：管理面/数据面的完整设计、服务发现、策略下发。
> 想深入？→ [附录 07-企业级架构模式/02-事件驱动Agent架构（协作拓扑的运行时形态）]：更多协作拓扑变体和学术论文索引。
> 遇到阻塞？→ [教程 22-全链路可观测性]：分布式 Trace、Agent 协作可视化。
