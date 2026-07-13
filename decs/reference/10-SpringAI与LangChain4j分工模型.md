# 附录 - Spring AI 与 LangChain4j 分工模型

> 一句话定位：**Spring AI 负责"接入"和"兜底"，LangChain4j 负责"思考"和"编排"。**
>
> 这不是简单的"二选一"，而是大型 Java AI 项目里两个框架的**职责分工模型**。本节是对 `04-Java与AI融合架构.md` 第 12 节和 `spring-ai/07-与LangChain4j对比.md` 第 12 节的展开。

---

## 1. 为什么要分工

两个框架各自有"舒适区"：

| 框架 | 舒适区 | 不舒适区 |
|------|--------|----------|
| **Spring AI** | 接入 Spring 生态、Advisor 链、Web 层、Tool 注入业务 Bean | 复杂状态机、多 Agent 协作、ReAct 循环细节控制 |
| **LangChain4j** | AiServices 声明式 Agent、ChatMemory 灵活装配、LangGraph4j 状态机 | Spring 容器集成、Web 鉴权限流审计、生产级横切关注点 |

**核心洞察**：两者的舒适区**几乎不重叠**，因此可以共存而不是竞争。

---

## 2. "接入"与"兜底" —— Spring AI 的职责

### 2.1 "接入"指什么

Spring AI 负责**把 LLM 能力接入到 Spring 业务系统**：

```
HTTP 请求
  ↓
Spring Security（鉴权）
  ↓
Controller（Spring AI ChatClient）
  ↓
Advisor 链：
  ├─ 限流 Advisor（Bucket4j + Redis）
  ├─ 审计 Advisor（落库 prompt/response）
  ├─ 多租户 Advisor（选知识库）
  ├─ RAG Advisor（QuestionAnswerAdvisor）
  └─ Memory Advisor（会话记忆）
  ↓
ChatModel（调用 LLM）
  ↓
Flux<String> 流式返回
```

**关键能力**：
- `ChatClient.Builder` 全局默认配置（system prompt、advisors、tools）
- `@Tool` Bean 直接注入业务 Service（`@Transactional`、`@Cacheable` 都能用）
- `Advisor` 链是**横切关注点**的天然位置
- 与 Spring Security / Cloud / Data / Actuator 无缝集成

### 2.2 "兜底"指什么

Spring AI 负责**生产环境的兜底机制**：

| 兜底场景 | 实现方式 |
|---------|---------|
| 主模型超时 | `ChatClient` 配置 fallback Model Bean |
| 流式中断 | `Flux` 的 `onErrorResume` 切备用模型 |
| Tool 调用失败 | Advisor 捕获异常，返回降级响应 |
| 配额超限 | 限流 Advisor 直接拒绝，不走 LLM |
| 敏感词 | Advisor 前置过滤，prompt 不发出去 |
| 审计追溯 | Advisor 后置落库，所有调用可回放 |

**为什么 Spring AI 适合兜底**：Advisor 链是 Spring AOP 的 AI 版，所有横切关注点都能在这里统一处理，**不需要侵入业务代码**。

---

## 3. "思考"与"编排" —— LangChain4j 的职责

### 3.1 "思考"指什么

LangChain4j 负责**单次 LLM 调用的思考过程**：

```java
// AiServices 声明式 —— 接口签名即契约
interface Analyst {
    @SystemMessage("你是数据分析师，按 schema 输出")
    AnalysisResult analyze(@MemoryId String sessionId,
                          @UserMessage String question);
}

Assistant agent = AiServices.builder(Analyst.class)
    .chatLanguageModel(model)
    .contentRetriever(retriever)         // RAG
    .tools(queryTools, calcTools)        // Tool 集合
    .chatMemoryProvider(id -> ...)       // 记忆
    .build();
```

**关键能力**：
- `AiServices` 接口驱动，**类型安全**，IDE 提示完整
- `@SystemMessage` / `@UserMessage` 注解管理 prompt
- 返回类型直接映射结构化输出（不用 `.entity(Class)`）
- `ChatMemory` 装配式组合（窗口策略、Token 策略、自定义 Store）

### 3.2 "编排"指什么

LangChain4j 负责**多步骤、多 Agent 的编排**：

```
用户："帮我分析上周销售数据并生成报告"

LangChain4j 编排流程：
  ├─ Agent A（数据查询）：调 Tool 取数据
  ├─ Agent B（数据分析）：ReAct 循环算指标
  ├─ Agent C（报告生成）：汇总 + 写报告
  └─ 状态机：控制 Agent 之间的跳转
```

**核心工具**：
- **LangGraph4j**（`langchain4j-graph`）：状态机式多 Agent 编排
- **ReAct 循环**：思考 → 行动 → 观察 → 再思考
- **Chain of Responsibility**：链式调用多个 Agent
- **自定义 `ContentRetriever`**：复杂 RAG 策略（混合检索、重排序）

**为什么 LangChain4j 适合编排**：它的 API 设计**更接近 Python LangChain**，概念对应清晰，编排逻辑可以直接复用 Python 生态的成熟模式。

---

## 4. 分工模型架构图

```
┌──────────────────────────────────────────────────────────┐
│                     前端 / 第三方                          │
└──────────────────────────┬───────────────────────────────┘
                           │ HTTP / SSE
┌──────────────────────────▼───────────────────────────────┐
│  Spring AI 层【接入 + 兜底】                               │
│  ──────────────────────────────────────                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────────┐     │
│  │ Controller │→ │ Advisor 链  │→ │  ChatClient    │     │
│  │  (Web)     │  │ - 鉴权      │  │  (统一入口)    │     │
│  └────────────┘  │ - 限流      │  └───────┬────────┘     │
│                  │ - 审计      │          │              │
│                  │ - 多租户    │          │ 路由          │
│                  │ - 降级      │          ↓              │
│                  └────────────┘  ┌──────────────────┐    │
│                                  │ 简单请求直接处理  │    │
│                                  │ (单轮 RAG + Tool) │    │
│                                  └────────┬─────────┘    │
│                                           │ 复杂请求      │
└───────────────────────────────────────────┼──────────────┘
                                            ↓
┌──────────────────────────────────────────────────────────┐
│  LangChain4j 层【思考 + 编排】                            │
│  ──────────────────────────────────────                  │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────┐   │
│  │ AiServices     │  │  LangGraph4j   │  │ ReAct    │   │
│  │ (声明式 Agent) │→ │  (状态机编排)  │← │ (思考循环)│  │
│  └────────────────┘  └────────────────┘  └──────────┘   │
│         │                    │                  │        │
│         └────────────────────┴──────────────────┘        │
│                           │                              │
│                  ┌────────▼─────────┐                    │
│                  │ ChatMemory +     │                    │
│                  │ ContentRetriever │                    │
│                  └──────────────────┘                    │
└──────────────────────────────────────────────────────────┘
```

---

## 5. 边界划分规则

### 5.1 用 Spring AI 做

- **Web 接入层**：Controller、SSE、WebSocket
- **横切关注点**：鉴权、限流、审计、敏感词、多租户
- **简单 LLM 调用**：单轮 RAG 问答、单 Tool 调用
- **Tool 的业务实现**：`@Component` + `@Tool`，注入 Spring Bean
- **降级与兜底**：fallback model、超时、重试策略
- **可观测性**：Micrometer + Prometheus 指标

### 5.2 用 LangChain4j 做

- **复杂 Agent**：需要 ReAct 循环、多步推理
- **多 Agent 协作**：LangGraph4j 状态机
- **结构化输出密集场景**：AiServices 接口签名即契约
- **复杂 RAG 策略**：混合检索、重排序、多路召回
- **离线批处理**：不依赖 Spring 容器的脚本任务
- **ChatMemory 精细控制**：自定义 Store、Token 窗口策略

### 5.3 边界争议场景

| 场景 | 推荐归属 | 理由 |
|------|---------|------|
| 单轮 RAG 问答 | Spring AI | Advisor 链足够，无需 LangChain4j |
| 多轮对话 + Tool | 两者皆可 | Spring AI 用 Memory Advisor；LangChain4j 用 AiServices |
| 多 Agent 工单系统 | LangChain4j | 状态机编排是 LangChain4j 强项 |
| 流式聊天 | Spring AI | `Flux<String>` 与 Web 层天然契合 |
| 离线文档处理 | LangChain4j | 不需要 Spring 容器 |

---

## 6. 两层之间的通信

### 6.1 同进程调用（推荐起步）

Spring AI 层直接注入 LangChain4j 的 `AiServices` Bean：

```java
// LangChain4j 侧：定义 Agent 接口
public interface AnalysisAgent {
    String analyze(@MemoryId String sessionId, @UserMessage String question);
}

// Spring AI 侧：作为 Bean 注入
@Configuration
class AgentConfig {
    @Bean
    AnalysisAgent analysisAgent(ChatLanguageModel model,
                                  ContentRetriever retriever) {
        return AiServices.builder(AnalysisAgent.class)
                .chatLanguageModel(model)
                .contentRetriever(retriever)
                .build();
    }
}

// Spring AI Controller 调用
@RestController
@RequiredArgsConstructor
class AnalysisController {
    private final AnalysisAgent agent;  // LangChain4j Bean

    @PostMapping("/analyze")
    String analyze(@RequestBody Req req) {
        return agent.analyze(req.sessionId(), req.question());
    }
}
```

**优点**：零网络开销、调试简单。
**缺点**：两层耦合，无法独立扩缩容。

### 6.2 跨进程调用（生产级）

Spring AI 层通过 HTTP/gRPC 调用 LangChain4j 服务：

```
┌─────────────┐    HTTP/gRPC    ┌──────────────────┐
│ Spring AI   │ ──────────────→ │ LangChain4j      │
│ (Web 层)    │                 │ (Agent 服务)     │
│ 多实例      │                 │ 多实例            │
└─────────────┘                 └──────────────────┘
```

**适用场景**：
- Agent 服务需要独立扩容（推理密集 vs IO 密集）
- 团队分工（Web 团队 vs AI 团队）
- Agent 服务复用给多个上游

---

## 7. 一个完整的分工示例

**需求**：企业内部知识库助手，支持多轮对话 + Tool 调用 + 复杂工单生成。

### 7.1 Spring AI 层（接入 + 兜底）

```java
@Configuration
class SpringAiConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          VectorStore vs,
                          ChatMemory memory) {
        return builder
            .defaultSystem("你是企业内部助手")
            .defaultAdvisors(
                new SecurityAdvisor(),           // 鉴权
                new RateLimitAdvisor(),          // 限流
                new AuditAdvisor(),              // 审计
                new MessageChatMemoryAdvisor(memory),
                new QuestionAnswerAdvisor(vs)    // RAG
            )
            .build();
    }
}

@RestController
@RequiredArgsConstructor
class AssistantController {
    private final ChatClient client;
    private final AnalysisAgent complexAgent;  // LangChain4j

    @PostMapping("/chat")
    Flux<String> chat(@RequestBody ChatReq req) {
        // 简单请求：Spring AI 直接处理
        if (req.isSimple()) {
            return client.prompt()
                .user(req.message())
                .stream()
                .content();
        }
        // 复杂请求：交给 LangChain4j 编排
        return Flux.fromCallable(() ->
            complexAgent.process(req.sessionId(), req.message())
        );
    }
}
```

### 7.2 LangChain4j 层（思考 + 编排）

```java
public interface AnalysisAgent {
    String process(@MemoryId String sessionId, @UserMessage String task);
}

@Configuration
class LangChain4jConfig {
    @Bean
    AnalysisAgent analysisAgent(ChatLanguageModel model,
                                  @Qualifier("hybridRetriever")
                                  ContentRetriever retriever,
                                  EmployeeTools empTools,
                                  OrderTools orderTools) {
        return AiServices.builder(AnalysisAgent.class)
            .chatLanguageModel(model)
            .contentRetriever(retriever)        // 混合检索
            .tools(empTools, orderTools)        // 业务 Tool
            .chatMemoryProvider(id ->
                MessageWindowChatMemory.builder()
                    .maxMessages(20)
                    .id(id)
                    .build())
            .build();
    }
}
```

### 7.3 分工收益

| 维度 | 收益 |
|------|------|
| 关注点分离 | Web 层不关心 Agent 内部逻辑；Agent 层不关心鉴权限流 |
| 可测试性 | Spring AI 层 mock 掉 Agent；LangChain4j 层独立单测 |
| 可演进 | Agent 逻辑变化不影响 Web 层；Web 层加 Advisor 不影响 Agent |
| 团队分工 | Web 工程师改 Spring AI 层；AI 工程师改 LangChain4j 层 |

---

## 8. 常见反模式

### 8.1 ❌ 在 Spring AI 层写复杂编排

```java
// 反模式：在 Controller 里写 ReAct 循环
@PostMapping("/chat")
String chat(@RequestBody Req req) {
    String thought = client.prompt().user(req.q() + " 先思考").call().content();
    String action = client.prompt().user("根据" + thought + "选工具").call().content();
    String result = callTool(action);
    String answer = client.prompt().user(thought + result + " 总结").call().content();
    return answer;
}
```

**问题**：编排逻辑散落在 Web 层，无法复用、无法测试、无法独立演进。
**正解**：交给 LangChain4j 的 AiServices 或 LangGraph4j。

### 8.2 ❌ 在 LangChain4j 层做鉴权限流

```java
// 反模式：在 AiServices 的 Tool 里做鉴权
public class EmployeeTools {
    @Tool
    String queryEmployee(String name, String authToken) {  // ❌ token 不该到这
        if (!authService.check(authToken)) throw ...;
        return ...;
    }
}
```

**问题**：横切关注点侵入业务 Tool，每个 Tool 都要重复鉴权逻辑。
**正解**：Spring AI 的 Advisor 链统一处理。

### 8.3 ❌ 起步就搞分工架构

新项目第一天就分两层，是过度设计。
**正解**：先用一个框架（推荐 Spring AI）跑通 MVP，等编排逻辑复杂到 Advisor 链 hold 不住时，再引入 LangChain4j。

---

## 9. 演进路径建议

```
阶段 1（MVP）
  └─ 单框架（Spring AI 或 LangChain4j）跑通核心功能

阶段 2（生产化）
  └─ Spring AI 层加 Advisor 链（鉴权、限流、审计、降级）
  └─ 简单 Agent 直接在 Spring AI 层用 ChatClient + Tool

阶段 3（编排复杂化）
  └─ 引入 LangChain4j 处理复杂 Agent（多步推理、多 Agent 协作）
  └─ Spring AI 层通过 Bean 注入或 HTTP 调用 LangChain4j Agent

阶段 4（团队规模化）
  └─ 两层独立部署、独立扩缩容
  └─ 接口契约化（OpenAPI / Protobuf）
```

---

## 10. 自检清单

读完本节后，你应该能回答：

- [ ] "接入"和"兜底"分别指什么？为什么 Spring AI 适合？
- [ ] "思考"和"编排"分别指什么？为什么 LangChain4j 适合？
- [ ] 两层之间同进程和跨进程两种通信方式各有什么取舍？
- [ ] 哪些场景不该用分工模型（应单框架解决）？
- [ ] 在你当前的项目里，哪些逻辑属于"接入/兜底"，哪些属于"思考/编排"？

---

## 11. 相关文档

- `04-Java与AI融合架构.md` —— Java 与 AI 融合的整体架构（本节是其第 12 节的展开）
- `tutorials/spring-ai/07-与LangChain4j对比.md` —— 两框架核心差异对比（本节是其第 12 节的展开）
- `09-心智模型与决策树.md` —— 何时用啥的决策树
