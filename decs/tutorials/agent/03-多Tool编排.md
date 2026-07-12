# Agent 03 - 多 Tool 编排

> 当 Tool 数量超过 10 个，简单 Agent 就 hold 不住了。
> 本节讲怎么让 Agent 在多 Tool 场景下依然准确、高效。

---

## 1. 为什么需要编排

### 1.1 简单 Agent 的瓶颈

```
20 个 Tool 注册到一个 Agent
   ↓
LLM 每次都要看 20 个 Tool 的 schema（消耗 token）
   ↓
LLM 选择准确率断崖式下降（经常选错）
```

### 1.2 解决方案

```
方案 A：Tool 路由 Agent
   一个 Router Agent 先判断意图，转给专门的 Sub-Agent

方案 B：状态机 Agent
   显式定义 Agent 工作流（LangGraph4j）

方案 C：并行调用
   多个无依赖 Tool 并行执行（节省时间）
```

---

## 2. Tool 路由模式

### 2.1 架构

```
用户请求
   ↓
Router Agent（看意图）
   ├── "人事相关" → HR Agent (3 个 HR Tool)
   ├── "IT 相关"  → IT Agent (3 个 IT Tool)
   └── "其他"     → 通用 Agent
```

### 2.2 LangChain4j 实现

#### Router Agent

```java
public interface RouterAgent {

    @SystemMessage("""
        你是意图分类器，判断用户问题属于哪个领域：
        - HR: 员工、部门、入职、离职
        - IT: 服务器、K8s、Prometheus、日志
        - GENERAL: 其他
        
        只返回领域代码（HR/IT/GENERAL），不要其他文字。
        """)
    String classify(String userMessage);
}
```

#### Sub-Agents

```java
public interface HrAgent {
    @SystemMessage("你是 HR 助手")
    String chat(@MemoryId String sessionId, @UserMessage String msg);
}

public interface ItAgent {
    @SystemMessage("你是 IT 运维助手")
    String chat(@MemoryId String sessionId, @UserMessage String msg);
}
```

#### 装配

```java
RouterAgent router = AiServices.builder(RouterAgent.class)
        .chatLanguageModel(model)
        .build();

HrAgent hrAgent = AiServices.builder(HrAgent.class)
        .chatLanguageModel(model)
        .tools(employeeTools, deptTools)
        .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
        .build();

ItAgent itAgent = AiServices.builder(ItAgent.class)
        .chatLanguageModel(model)
        .tools(k8sTools, prometheusTools)
        .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
        .build();
```

#### 入口

```java
@Service
public class SmartRouter {

    private final RouterAgent router;
    private final HrAgent hrAgent;
    private final ItAgent itAgent;
    private final GeneralAgent generalAgent;

    public String chat(String userId, String msg) {
        String category = router.classify(msg);
        return switch (category.trim().toUpperCase()) {
            case "HR" -> hrAgent.chat(userId, msg);
            case "IT" -> itAgent.chat(userId, msg);
            default -> generalAgent.chat(userId, msg);
        };
    }
}
```

### 2.3 Spring AI 实现

```java
@Service
public class SmartRouter {

    private final ChatClient classifier;
    private final ChatClient hrClient;
    private final ChatClient itClient;
    private final ChatClient generalClient;

    public String chat(String userId, String msg) {
        String category = classifier.prompt()
                .system("判断意图：HR / IT / GENERAL")
                .user(msg)
                .call()
                .content();

        return switch (category.trim().toUpperCase()) {
            case "HR" -> hrClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
            case "IT" -> itClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
            default -> generalClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
        };
    }
}
```

### 2.4 各 ChatClient 配不同 Tool

```java
@Bean("hrClient")
public ChatClient hrClient(ChatClient.Builder builder, HrTools hr) {
    return builder.defaultTools(hr).build();
}

@Bean("itClient")
public ChatClient itClient(ChatClient.Builder builder, ItTools it) {
    return builder.defaultTools(it).build();
}
```

---

## 3. 状态机模式（LangGraph4j）

### 3.1 何时用状态机

| 场景 | 推荐模式 |
|------|---------|
| 工具数量多 | 路由 |
| **明确工作流** | **状态机** |
| 完全开放对话 | 单 Agent |

### 3.2 LangGraph4j 示例

> 注：LangGraph4j 是 LangChain4j 的扩展，专门做状态机式 Agent。

```java
// pom.xml: dev.langchain4j:langchain4j-langgraph
import dev.langchain4j.langgraph.*;

// 定义状态
record AgentState(String input, String category, String result) {}

// 定义节点
class RouterNode implements Node<AgentState> {
    @Override
    public AgentState apply(AgentState state) {
        String category = classify(state.input());
        return new AgentState(state.input(), category, null);
    }
}

class HrNode implements Node<AgentState> { ... }
class ItNode implements Node<AgentState> { ... }

// 构建图
var graph = StateGraph.<AgentState>builder()
        .addNode("router", new RouterNode())
        .addNode("hr", new HrNode())
        .addNode("it", new ItNode())
        .addEdge(START, "router")
        .addConditionalEdge("router", state ->
            switch (state.category()) {
                case "HR" -> "hr";
                case "IT" -> "it";
                default -> END;
            })
        .addEdge("hr", END)
        .addEdge("it", END)
        .compile();

// 执行
AgentState result = graph.invoke(new AgentState("张三工位在哪", null, null));
```

### 3.3 状态机优势

- **可视化**：能画出来
- **可测试**：每个 Node 单元测试
- **可重放**：记录中间状态
- **可中断**：任何 Node 后暂停

---

## 4. 并行 Tool 调用

### 4.1 何时有用

用户问"查张三的工位和李四的工位"时，可以并行调两次 queryEmployee。

### 4.2 LLM 原生支持

主流模型（GPT-4、Claude、DeepSeek）支持**单次响应输出多个 tool_calls**：

```json
"tool_calls": [
  {"name": "queryEmployee", "arguments": {"name": "张三"}},
  {"name": "queryEmployee", "arguments": {"name": "李四"}}
]
```

### 4.3 框架处理

LangChain4j / Spring AI 都已经实现：
- 收到多个 tool_calls
- **并行执行**（或顺序执行，看版本）
- 把所有结果收集后发给 LLM

### 4.4 性能对比

| 模式 | 5 个 Tool 耗时 |
|------|--------------|
| 顺序 | 5 × 单次时间 |
| 并行 | max(单次时间) |

---

## 5. 子 Agent 协作

### 5.1 何时需要

复杂任务，单个 Agent 搞不定，需要多个角色协作：
- 产品经理 Agent：理解需求
- 架构师 Agent：设计方案
- 开发 Agent：写代码

### 5.2 简单实现

```java
@Service
public class CollaborativeAgent {

    public String generateFeature(String requirement) {
        // 1. PM 分析需求
        String spec = pmAgent.analyzeRequirement(requirement);

        // 2. 架构师设计
        String design = archAgent.design(spec);

        // 3. 开发写代码
        String code = devAgent.implement(design);

        return code;
    }
}
```

### 5.3 复杂协作（用 AutoGen / CrewAI 风格）

参考 `reference/07-多模态与多Agent.md`。

---

## 6. Tool 链式调用

### 6.1 链式（Agent 自己串联）

```
用户："张三工位在哪？"

LLM 推理：
  1. 调 queryEmployee("张三") → 工号 10086
  2. 调 queryWorkstation("10086") → 5 楼 A03
  
返回：张三在 5 楼 A03
```

**这是 Agent 的核心能力**，无需额外配置。LLM 看到 Tool 描述，自己就能串联。

### 6.2 显式链（避免 LLM 错误）

如果 LLM 经常跳步骤，可以**强制链**：

```java
public String findWorkstation(String name) {
    // 强制两步，不让 LLM 跳过
    EmployeeInfo info = queryEmployee(name);
    return queryWorkstation(info.id());
}
```

但这就退化为"函数调用"，失去了 Agent 的灵活性。

### 6.3 平衡

| 选择 | 适用 |
|------|------|
| LLM 自主串联 | 信任 LLM，追求灵活 |
| 显式链 | 关键业务流程，要求稳定 |
| 混合 | 关键步骤显式，辅助步骤自主 |

---

## 7. Tool 数据共享

### 7.1 通过 ToolContext（Spring AI）

```java
@Tool("查询当前用户的订单")
public List<Order> queryMyOrders(ToolContext ctx) {
    String userId = (String) ctx.getContext().get("userId");
    return orderRepo.findByUserId(userId);
}
```

### 7.2 通过 ThreadLocal

```java
public class UserContext {
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    public static void set(String userId) { CURRENT_USER.set(userId); }
    public static String get() { return CURRENT_USER.get(); }
}

@Tool("查询当前用户订单")
public List<Order> queryMyOrders() {
    return orderRepo.findByUserId(UserContext.get());
}
```

### 7.3 通过 ChatMemory 中的 SystemMessage

```java
// 会话开始时把用户信息塞进 system message
chatMemory.add(SystemMessage.from("当前用户：张三（工号 10086）"));
```

Tool 不需要拿 userId，LLM 在调用 Tool 时会带上。

---

## 8. 编排实战：智能客服路由

### 8.1 场景

客服 Agent 处理三类问题：
- 售前咨询（产品信息）→ RAG
- 售后服务（订单、退款）→ Tool（查订单）
- 技术支持（产品手册）→ RAG + Tool

### 8.2 架构

```
Router Agent
   ├── 售前 → RAG Agent (VectorStore: 产品手册)
   ├── 售后 → Order Agent (OrderTools, RefundTools)
   └── 技术 → Tech Agent (VectorStore + DiagnosticTools)
```

### 8.3 代码骨架

```java
@Service
public class CustomerService {

    public String chat(String userId, String msg) {
        String intent = classifier.classify(msg);

        return switch (intent) {
            case "PRE_SALES" -> ragClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
            case "AFTER_SALES" -> orderClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
            case "TECH" -> techClient.prompt()
                    .user(msg)
                    .advisors(spec -> spec.param(CONVERSATION_ID, userId))
                    .call().content();
            default -> fallbackClient.prompt().user(msg).call().content();
        };
    }
}
```

---

## 9. 常见问题

### 9.1 Router Agent 分类不准

**原因**：分类 system prompt 不清晰。
**解决**：加 few-shot 示例。

```java
@SystemMessage("""
    判断意图：HR / IT / GENERAL
    
    示例：
    "张三工位" → HR
    "服务器 CPU" → IT
    "讲个笑话" → GENERAL
    """)
```

### 9.2 Sub-Agent 之间信息丢失

**原因**：每个 Sub-Agent 独立 ChatMemory。
**解决**：用共享 Session（Redis 存储用户上下文），Router 把分类结果 + 原始问题传给 Sub-Agent。

### 9.3 性能问题（多次 LLM 调用）

**症状**：Router 一次 + Sub-Agent 一次 = 2 倍延迟。
**解决**：
- Router 用便宜小模型
- Sub-Agent 用大模型
- 或合并 Router 和 Sub-Agent（用 Tool 路由的 Agent）

---

## 10. 理解检查

1. 什么时候用 Tool 路由？什么时候用状态机？
2. 并行 Tool 调用是 LLM 的能力还是框架的能力？
3. Sub-Agent 协作时，怎么共享上下文？
4. Router Agent 用便宜模型还是贵模型？
5. 显式 Tool 链和 LLM 自主串联，分别什么时候用？

---

## 11. 练习任务

1. 把 6 个 Tool 按业务分组，实现 Router 模式
2. 测试：在 HR 问题上，Router 是否能正确路由到 HR Agent
3. 用 LangGraph4j 实现一个简单状态机（即使只 2 个 Node）
4. 测试并行调用：让 LLM 同时查 3 个城市天气，看耗时是否是 max(单次)
5. 实现"客服路由"实战项目骨架

完成后进入 [04-实战项目-个人助理](./04-实战项目-个人助理.md)。
