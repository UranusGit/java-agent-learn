# 第三阶段 - Java 与 AI 生态融合【核心桥梁】

> 这是你的**主战场**，也是你超越纯 Python 工程师的爆发点。
> 本节最重要，建议反复精读。

---

## 1. 黄金架构原则

```
┌──────────────────────────────────────────────────────┐
│         前端 / 第三方调用方                           │
└─────────────────┬────────────────────────────────────┘
                  │ HTTP / WebSocket / SSE
┌─────────────────▼────────────────────────────────────┐
│   Java 业务编排层（Spring Boot）                     │
│   ─ 鉴权 / 限流 / 配额                               │
│   ─ 会话管理 / 多租户                                │
│   ─ 业务流程编排（RAG → Agent → 后处理）             │
│   ─ 数据持久化（MySQL / Redis / VectorDB）           │
│   ─ 审计 / 监控 / 链路追踪                           │
└─────┬───────────────────────┬────────────────────────┘
      │ HTTP/gRPC             │ HTTP/gRPC
┌─────▼─────────┐    ┌────────▼────────────────────────┐
│ AI 能力层 A    │    │ AI 能力层 B                     │
│ vLLM 服务      │    │ Python FastAPI + LangGraph      │
│ (OpenAI 兼容)  │    │ (复杂 Agent 编排，复用 Python   │
│                │    │  生态)                          │
└────────────────┘    └─────────────────────────────────┘
```

---

## 2. 何时 Python / 何时 Java（关键决策树）

| 场景 | 选择 |
|------|------|
| 需要 LangGraph 复杂状态机、GraphRAG、微调 pipeline | **Python** |
| 业务编排、CRUD、鉴权、消息队列、定时任务 | **Java** |
| 简单 RAG、单 Agent、工具调用 | **纯 Java**（Spring AI / LangChain4j） |
| 复杂多 Agent 协作 | **Python**，Java 调用结果 |

---

## 3. Java ↔ Python 通信模式

| 模式 | 用途 | 工具 |
|------|------|------|
| HTTP REST | 简单请求/响应 | Spring `WebClient` / `RestClient` |
| **SSE (Server-Sent Events)** | **流式 token 输出** | Spring `WebClient` + `Flux<ServerSentEvent>` |
| WebSocket | 双向实时 | Spring WebSocket |
| gRPC | 高性能、低延迟 | grpc-java + protobuf |
| **消息队列 (Kafka/RocketMQ)** | 异步长任务、解耦 | Spring Kafka |

### Java 调用 LLM 流式的标准模板（Spring AI）

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chat(@RequestParam String userMsg) {
    return chatClient.prompt()
        .user(userMsg)
        .stream()
        .content();
}
```

---

## 4. Spring AI 深入路径

### 4.1 官方核心模块

1. `ChatClient` —— 模型对话抽象
2. `EmbeddingModel` / `VectorStore` —— 向量化与存储抽象
3. `Advisor` —— 拦截器链（类似 Spring AOP），可做 RAG 注入、日志、敏感词过滤
4. `ChatMemory` —— 会话记忆抽象
5. `FunctionCallback` —— Tool 定义
6. `ETL`（Extract-Transform-Load）管道 —— 文档加载和分块

### 4.2 Spring AI 关键代码模式（生产级）

```java
@Configuration
class AiConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, VectorStore vs) {
        return builder
            .defaultSystem("你是公司内网助手")
            .defaultAdvisors(
                new MessageChatMemoryAdvisor(chatMemory),  // 记忆
                new QuestionAnswerAdvisor(vs),              // RAG
                new SimpleLoggerAdvisor()                   // 日志
            )
            .build();
    }
}

// Controller 直接注入即可
@RestController
class ChatController {
    private final ChatClient client;

    @PostMapping("/ask")
    String ask(@RequestBody AskReq req) {
        return client.prompt().user(req.q()).call().content();
    }
}
```

---

## 5. LangChain4j 深入路径

**特别推荐 Java 工程师从 LangChain4j 起步** —— API 设计最像传统 Java 框架。

### 5.1 核心概念

| 概念 | 说明 |
|------|------|
| `AiServices` | 类似 MyBatis Mapper 的声明式接口 |
| `@Tool` / `@P` | Tool 定义 |
| `ContentRetriever` | RAG 抽象 |
| `ChatMemoryStore` | 自定义会话存储（Redis/DB） |

### 5.2 声明式 Agent 写法

```java
interface Assistant {
    String chat(String userMessage);
}

Assistant agent = AiServices.builder(Assistant.class)
    .chatLanguageModel(model)
    .contentRetriever(retriever)   // RAG
    .tools(employeeTools, k8sTools)
    .chatMemoryProvider(memoryId ->
        MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build())
    .build();

// 直接当 Java 方法用
String answer = agent.chat("张三工位在几楼？");
```

---

## 6. 推荐资料（这一节最关键，建议收藏）

### Spring AI
- 官方文档 `docs.spring.io/spring-ai/reference/`（精读）
- 官方 GitHub 示例 `github.com/spring-projects/spring-ai-examples`
- Spring 官方油管 *"Spring Tips: Spring AI"* 系列

### LangChain4j
- 官方文档 `docs.langchain4j.dev`（极其详细）
- 创始人 Dmytro Liubarskyi 油管频道（英文）
- GitHub 示例 `github.com/langchain4j/langchain4j-examples`

### 中文社区
- 阿里通义千问团队博客（PoffyZhang 等）有 Java AI 实战文章
- LangChain4j 中文社区（公众号 / 知乎专栏）

### 书
- 《LangChain实战》虽然讲 Python，但概念直接复用
- 《Spring AI 实战》（待出，关注机械工业 / 人民邮电的新书动态）

---

## 7. 实操项目：企业智能客服（Java 主导 + Python 辅助）

### 架构

**Java 侧**：Spring Boot + Spring Security + MySQL + Redis + Milvus
- 鉴权、会话管理、配额限流（用 Redis + Bucket4j）
- 用 **Spring AI 或 LangChain4j** 实现主对话 Agent
- 集成 RAG（产品手册、FAQ 知识库）

**Python 侧（可选）**：FastAPI + LangGraph
- 实现复杂工单生成 Agent（需要多个角色协作）
- Java 通过 WebClient 调用

### 关键工程能力训练

1. **流式响应**：SSE 推 token 到前端
2. **会话隔离**：`conversation_id` 维度的 ChatMemory，存 Redis
3. **多租户**：每个租户独立知识库 + 独立 prompt 模板
4. **限流**：基于用户 ID + token 数双维度
5. **降级**：主模型超时 → 切换备用模型
6. **审计**：所有 prompt 和 response 落库，便于事后分析
7. **可观测**：每个 LLM 调用记录耗时、token、cost，对接 Micrometer + Prometheus

---

## 8. 避坑点

- **超时设置必须激进**：LLM 调用默认 WebClient 超时是 30s，可能不够，但要设上限 60s。
- **重试要谨慎**：LLM 调用不是幂等的，重试会双倍扣费；区分"网络层重试"和"业务层重试"。
- **JSON 解析**：LLM 可能输出非法 JSON，用 **OpenAI Structured Output** 或 **JSON Mode**，Java 侧用 lenient 的 Jackson 配置。
- **prompt 模板版本化**：用 Git 管理 prompt 文件，不要写死在代码里。
- **token 计费要监控**：用户一个长 prompt 就能烧几块钱，必须有告警。

---

## 9. 学习检查点

> 能设计出"用户请求 → Java 网关 → Java Agent → 流式响应"的完整生产架构，并说清楚每一步的工程考虑。
