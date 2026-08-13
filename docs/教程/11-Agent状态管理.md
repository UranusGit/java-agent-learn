# 11-Agent 状态管理

> **定位**：讲透 Agent 为什么需要状态管理、Agent 状态机的概念、会话生命周期与上下文管理、Spring AI 中基于 ChatMemory + Advisor + 会话 ID 的完整状态管理实现。读完这篇，你能设计出健壮的多轮 Agent 会话系统。
>
> **读者画像**：已经掌握记忆系统和工具调用，需要理解 Agent 运行时状态流转的开发者。
>
> **前置阅读**：[04-记忆与会话管理](04-记忆与会话管理.md)。

---

## 1. 为什么 Agent 需要状态管理

一个没有状态管理的 Agent，就像一个失忆的服务员——每上一道菜都忘了之前上过什么。但 Agent 的状态远不止"记忆"这么简单。

### 1.1 无状态 Agent 的问题

```mermaid
graph TB
    subgraph 无状态Agent["❌ 无状态 Agent"]
        U1["用户：帮我查一下 ORD-001"] --> A1["Agent：查询中..."]
        A1 --> R1["Agent：订单已发货"]
        U2["用户：那帮我退款"] --> A2["Agent：请问哪个订单？"]
        Note1["Agent 不知道"退款"<br/>指的是 ORD-001"]
    end

    subgraph 有状态Agent["✅ 有状态 Agent"]
        U3["用户：帮我查一下 ORD-001"] --> S1["状态：等待用户指令"]
        S1 --> A3["Agent：查询中..."]
        A3 --> S2["状态：已知订单 ORD-001<br/>上下文：已发货"]
        U4["用户：那帮我退款"] --> S2
        S2 --> A4["Agent：为 ORD-001 发起退款流程"]
        Note2["Agent 知道"退款"<br/>指的是 ORD-001"]
    end

    style 无状态Agent fill:#ffcdd2
    style 有状态Agent fill:#c8e6c9
```

### 1.2 Agent 状态的三个层次

Agent 的状态不仅仅是"对话历史"。完整的 Agent 状态包含三个层次：

```mermaid
graph TB
    subgraph 状态层次["Agent 状态的三个层次"]
        L1["会话状态（Session）<br/>会话 ID、用户身份、会话开始时间"]
        L2["对话状态（Conversation）<br/>消息历史、当前话题、工具调用记录"]
        L3["任务状态（Task）<br/>当前执行步骤、中间结果、待办事项"]
    end

    L1 --> L2
    L2 --> L3

    style 状态层次 fill:#e3f2fd
```

| 状态层次 | 内容 | 生命周期 | Spring AI 实现 |
|---------|------|---------|---------------|
| **会话状态** | 会话 ID、用户 ID、租户 ID | 从会话开始到结束 | `CONVERSATION_ID` 参数 |
| **对话状态** | 消息历史、工具调用记录 | 滑动窗口内的消息 | `ChatMemory` |
| **任务状态** | 当前步骤、中间变量、待办 | 单次任务执行期间 | Advisor 上下文 / 外部存储 |

---

## 2. Agent 状态机

Agent 的运行本质上是一个状态机。每次用户输入会触发状态迁移，Agent 根据当前状态决定如何响应。

### 2.1 核心状态定义

```mermaid
stateDiagram-v2
    [*] --> 空闲

    空闲 --> 思考 : 用户发送消息
    
    思考 --> 执行工具 : LLM 决定调用工具
    思考 --> 回复 : LLM 直接回答
    
    执行工具 --> 等待结果 : 工具异步执行
    等待结果 --> 思考 : 工具返回结果<br/>（进入下一轮推理）
    
    执行工具 --> 思考 : 工具同步返回
    
    回复 --> 空闲 : 回复完成
    回复 --> 思考 : 用户追问
    
    空闲 --> [*] : 会话结束

    note right of 思考
        LLM 推理阶段
        消耗 Token，可能耗时较长
    end note

    note right of 执行工具
        Java 代码执行阶段
        可能调用外部 API
        可能产生副作用
    end note

    note right of 等待结果
        异步等待阶段
        Agent 可能在此阶段
        被中断或超时
    end note
```

### 2.2 每个状态的详细职责

| 状态 | 职责 | 触发条件 | 退出条件 |
|------|------|---------|---------|
| **空闲** | 等待用户输入 | 会话初始化 / 回复完成 | 用户发送消息 |
| **思考** | LLM 推理，决定下一步行动 | 用户输入 / 工具结果返回 | LLM 返回回复或工具调用 |
| **执行工具** | 执行 LLM 决定调用的工具方法 | LLM 返回工具调用请求 | 工具执行完成或失败 |
| **等待结果** | 等待异步工具执行完成 | 工具需要异步处理 | 工具返回结果 |
| **回复** | 生成最终回复并发送给用户 | LLM 返回文本回复 | 回复发送完成 |

### 2.3 状态转换中的数据流

```mermaid
sequenceDiagram
    participant U as 用户
    participant S as 状态管理器
    participant M as ChatMemory
    participant L as LLM（DeepSeek）
    participant T as 工具

    U->>S: "帮我查 ORD-001 然后退款"
    Note over S: 状态：空闲 → 思考

    S->>M: 检索会话历史
    M-->>S: [之前的消息]
    
    S->>L: Prompt + 历史 + 工具定义
    Note over S: 状态：思考

    L-->>S: 工具调用：queryOrder("ORD-001")
    Note over S: 状态：思考 → 执行工具

    S->>T: queryOrder("ORD-001")
    Note over S: 状态：执行工具 → 等待结果
    T-->>S: 订单详情（已发货，金额 299）
    Note over S: 状态：等待结果 → 思考

    S->>M: 存储工具调用记录
    S->>L: 工具结果 + 历史
    L-->>S: 工具调用：createRefund("ORD-001", 299)
    Note over S: 状态：思考 → 执行工具

    S->>T: createRefund("ORD-001", 299)
    T-->>S: 退款已创建，工单 RF-2024-001
    Note over S: 状态：执行工具 → 思考

    S->>L: 工具结果 + 历史
    L-->>S: "已为 ORD-001 创建退款，工单号 RF-2024-001"
    Note over S: 状态：思考 → 回复

    S->>M: 存储用户消息和 AI 回复
    S-->>U: "已为 ORD-001 创建退款，工单号 RF-2024-001"
    Note over S: 状态：回复 → 空闲
```

---

## 3. 会话生命周期

一个完整的会话从创建到销毁，经历多个阶段。理解生命周期是设计状态管理的基础。

### 3.1 会话生命周期全貌

```mermaid
stateDiagram-v2
    [*] --> 创建 : 用户发起对话

    创建 --> 活跃 : 初始化完成<br/>分配会话 ID<br/>加载用户 Profile

    活跃 --> 活跃 : 多轮对话<br/>（思考→工具→回复循环）

    活跃 --> 挂起 : 用户离开<br/>（未关闭会话）
    挂起 --> 活跃 : 用户回来<br/>（超时窗口内）
    挂起 --> 过期 : 超过 TTL<br/>（如 30 分钟）

    活跃 --> 结束 : 用户主动关闭<br/>或任务完成
    过期 --> 结束 : 清理资源

    结束 --> 归档 : 存储到长期存储<br/>（审计/回放用）
    归档 --> [*]

    note right of 创建
        初始化操作：
        - 生成 conversationId
        - 加载用户偏好
        - 设置 System Message
    end note

    note right of 归档
        归档操作：
        - 序列化会话历史
        - 提取关键信息到长期记忆
        - 清理内存中的临时状态
    end note
```

### 3.2 会话创建

```java
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

@RestController
public class SessionController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final UserProfileService profileService;

    // Spring AI 2.0.0 — 会话初始化
    @PostMapping("/session/create")
    public SessionInfo createSession(@RequestBody CreateSessionRequest request) {
        String conversationId = UUID.randomUUID().toString();

        // 加载用户 Profile 作为长期记忆
        UserProfile profile = profileService.getProfile(request.userId());

        // 将关键信息注入 System Message
        String systemPrompt = """
                你是企业客服助手。当前用户信息：
                - 姓名：%s
                - 等级：%s
                - 偏好：%s
                """.formatted(
                profile.name(),
                profile.level(),
                profile.preferences()
        );

        // 初始化会话的 System Message
        chatMemory.add(conversationId, new SystemMessage(systemPrompt));

        return new SessionInfo(conversationId, "会话已创建");
    }
}
```

### 3.3 会话恢复

用户可能离开后回来，需要恢复之前的会话上下文：

```java
// Spring AI 2.0.0 — 会话恢复
@PostMapping("/session/{conversationId}/resume")
public String resumeSession(
        @PathVariable String conversationId,
        @RequestBody String userMessage
) {
    return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
    // MessageChatMemoryAdvisor 自动从存储中加载历史消息
}
```

### 3.4 会话过期与清理

```java
// Spring AI 2.0.0 — 会话过期处理
@Scheduled(fixedRate = 300_000) // 每 5 分钟检查一次
public void expireIdleSessions() {
    List<String> expiredSessions = sessionStore.findExpired(Duration.ofMinutes(30));
    
    for (String conversationId : expiredSessions) {
        // 1. 将关键信息提取到长期记忆
        List<Message> history = chatMemory.get(conversationId);
        memoryExtractionService.extractAndStore(conversationId, history);
        
        // 2. 归档完整对话历史
        sessionArchiveService.archive(conversationId, history);
        
        // 3. 清理内存中的会话数据
        chatMemory.clear(conversationId);
        
        // 4. 标记会话状态为已归档
        sessionStore.updateStatus(conversationId, SessionStatus.ARCHIVED);
    }
}
```

---

## 4. 上下文管理

Agent 的上下文（Context）是每次 LLM 调用时实际发送的全部信息。上下文管理决定了 Agent 的推理质量和 Token 成本。

### 4.1 上下文的组成

```mermaid
graph TB
    subgraph 上下文["每次 LLM 调用的上下文"]
        SM["System Message<br/>Agent 人格 + 用户 Profile"]
        HM["历史消息<br/>（滑动窗口内的消息）"]
        CM["当前消息<br/>用户的最新输入"]
        TR["工具调用记录<br/>（当前轮次的）"]
        ER["额外上下文<br/>（RAG 检索结果等）"]
    end

    SM --> TOKEN["Token 预算<br/>（模型上下文窗口）"]
    HM --> TOKEN
    CM --> TOKEN
    TR --> TOKEN
    ER --> TOKEN

    style 上下文 fill:#e3f2fd
    style TOKEN fill:#ffcdd2
```

### 4.2 Token 预算分配

模型的上下文窗口是有限的（DeepSeek 通常 64K-128K Token）。必须合理分配 Token 预算：

```java
import org.springframework.ai.chat.client.advisor.TokenBudgetAdvisor;

// Spring AI 2.0.0 — Token 预算分配
@Bean
ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
    return builder
            .defaultAdvisors(
                    // 记忆 Advisor：管理历史消息
                    MessageChatMemoryAdvisor.builder(chatMemory)
                            .build(),
                    // Token 预算 Advisor：自动控制上下文长度
                    TokenBudgetAdvisor.builder()
                            .tokenBudget(4096)  // 限制历史消息最多 4096 Token
                            .build()
            )
            .build();
}
```

### 4.3 上下文压缩策略

当对话过长时，直接丢弃旧消息会丢失重要信息。更智能的策略是**压缩摘要**：

```mermaid
graph LR
    subgraph 压缩前["压缩前：20 条消息（5000 Token）"]
        M1["消息 1-15"]
        M2["消息 16-20"]
    end

    subgraph 压缩后["压缩后：6 条消息（1500 Token）"]
        S1["摘要（由 LLM 生成）<br/>涵盖消息 1-15 的要点"]
        M3["消息 16-20（原文保留）"]
    end

    M1 -->|"LLM 摘要"| S1
    M2 -->|"原样保留"| M3

    style 压缩前 fill:#fff9c4
    style 压缩后 fill:#c8e6c9
```

```java
// Spring AI 2.0.0 — 上下文压缩 Advisor
@Component
public class ContextCompressionAdvisor implements BaseAdvisor {

    private static final int COMPRESS_THRESHOLD = 15;

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        List<Message> messages = request.messages();
        
        if (messages.size() > COMPRESS_THRESHOLD) {
            // 取出最早的消息进行摘要
            List<Message> toCompress = messages.subList(0, COMPRESS_THRESHOLD - 5);
            List<Message> toKeep = messages.subList(COMPRESS_THRESHOLD - 5, messages.size());
            
            // 用 LLM 生成摘要
            String summary = summarizeMessages(toCompress);
            
            // 用摘要替换原始消息
            List<Message> compressed = new ArrayList<>();
            compressed.add(new SystemMessage("之前的对话摘要：" + summary));
            compressed.addAll(toKeep);
            
            return request.mutate().messages(compressed).build();
        }
        
        return request;
    }

    private String summarizeMessages(List<Message> messages) {
        // 调用 LLM 对历史消息进行摘要
        return chatClient.prompt()
                .system("将以下对话压缩为简洁摘要，保留关键信息")
                .user(messages.stream().map(Message::getText).collect(Collectors.joining("\n")))
                .call()
                .content();
    }
}
```

---

## 5. Spring AI 中的状态管理实现

Spring AI 2.0 没有提供显式的"状态机"抽象，但通过 `ChatMemory` + `Advisor` + `CONVERSATION_ID` 三者的组合，构成了完整的状态管理体系。

### 5.1 状态管理的三要素

```mermaid
graph TB
    subgraph SpringAI状态管理["Spring AI 状态管理三要素"]
        CID["CONVERSATION_ID<br/>会话唯一标识<br/>驱动状态隔离"]
        MEM["ChatMemory<br/>对话状态存储<br/>消息历史管理"]
        ADV["Advisor<br/>状态流转控制<br/>前置/后置处理"]
    end

    CID --> MEM
    MEM --> ADV
    ADV --> CID

    style SpringAI状态管理 fill:#e3f2fd
```

### 5.2 完整的状态管理实现

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentStateConfig {

    // Spring AI 2.0.0 — 完整的 Agent 状态管理配置
    @Bean
    ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)  // 短期记忆：最近 20 条消息
                .build();
    }

    @Bean
    ChatClient agentClient(
            ChatClient.Builder builder,
            ChatMemory chatMemory
    ) {
        return builder
                .defaultSystem("你是企业级智能客服助手，回答专业、简洁、准确。")
                .defaultAdvisors(
                        // Memory Advisor：自动管理对话历史
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
}
```

### 5.3 多用户并发状态隔离

在 Web 应用中，多个用户同时与 Agent 对话。状态隔离的关键是 `CONVERSATION_ID`。

```java
@RestController
public class AgentController {

    private final ChatClient chatClient;

    public AgentController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // Spring AI 2.0.0 — 多用户并发，每个用户独立会话状态
    @PostMapping("/agent/chat")
    public Flux<String> chat(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam String message
    ) {
        // 用 userId 作为 conversationId，确保每个用户的会话独立
        String conversationId = "user-" + userId;

        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    // Spring AI 2.0.0 — 同一用户多个独立会话
    @PostMapping("/agent/chat/{sessionId}")
    public Flux<String> chatWithSession(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String sessionId,
            @RequestParam String message
    ) {
        // userId + sessionId 组合，支持同一用户的多会话
        String conversationId = "user-" + userId + "-session-" + sessionId;

        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
```

```mermaid
graph TB
    subgraph 并发会话["多用户并发会话隔离"]
        UA["用户 A<br/>conversationId: user-A"] 
        UB["用户 B<br/>conversationId: user-B"]
        UC["用户 C<br/>conversationId: user-C"]
    end

    UA --> MA["ChatMemory[user-A]<br/>[消息历史 A...]"]
    UB --> MB["ChatMemory[user-B]<br/>[消息历史 B...]"]
    UC --> MC["ChatMemory[user-C]<br/>[消息历史 C...]"]

    MA --> LLM["LLM 推理"]
    MB --> LLM
    MC --> LLM

    Note["三个会话完全隔离<br/>历史消息互不可见<br/>共享同一个 ChatClient 实例"]

    style 并发会话 fill:#e3f2fd
```

### 5.4 Advisor 上下文传递

Agent 在执行过程中可能需要在 Advisor 之间传递中间状态。Spring AI 使用 `Advisor` 的上下文 Map 来实现：

```java
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

// Spring AI 2.0.0 — 自定义 Advisor 用于状态传递
@Component
public class TaskStateAdvisor implements BaseAdvisor {

    public static final String CURRENT_TASK = "currentTask";
    public static final String TASK_STEP = "taskStep";
    public static final String TOOL_RESULTS = "toolResults";

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        // 前置处理：从上下文中读取任务状态
        String task = request.context().get(CURRENT_TASK);
        Integer step = request.context().get(TASK_STEP);

        // 将任务状态注入 Prompt
        if (task != null) {
            return request.mutate()
                    .system("当前任务：" + task + "，执行步骤：" + (step != null ? step : 0))
                    .build();
        }

        return request;
    }

    @Override
    public AdvisedResponse after(AdvisedResponse response) {
        // 后置处理：更新任务状态
        AdvisedResponse.AdvisedResponseBuilder builder = response.mutate();

        // 记录工具调用结果到上下文
        if (response.response().hasToolCalls()) {
            builder.contextValue(TOOL_RESULTS, response.response().getToolCalls());
        }

        return builder.build();
    }
}
```

---

## 6. 长任务的状态持久化

Agent 可能执行长时间运行的任务（多步骤、需要等待外部事件）。任务状态需要持久化到外部存储，支持中断恢复。

```mermaid
graph TB
    subgraph 长任务状态["长任务状态持久化"]
        direction TB
        T1["步骤 1：查询订单 ✅"]
        T2["步骤 2：验证退款条件 ✅"]
        T3["步骤 3：创建退款工单 ⏳<br/>（当前执行到这里）"]
        T4["步骤 4：通知用户（待执行）"]
        T5["步骤 5：关闭订单（待执行）"]
    end

    STORE["持久化存储<br/>（数据库 / Redis）"]
    T3 -.->|"每步完成后<br/>保存检查点"| STORE

    RECOVER["中断恢复<br/>从步骤 3 继续"]
    STORE -.->|"读取检查点"| RECOVER

    style 长任务状态 fill:#e3f2fd
    style STORE fill:#fff9c4
```

```java
// Spring AI 2.0.0 — 长任务状态持久化
@Service
public class LongRunningTaskService {

    private final ChatClient chatClient;
    private final TaskCheckpointRepository checkpointRepo;

    public LongRunningTaskService(ChatClient chatClient, 
                                   TaskCheckpointRepository checkpointRepo) {
        this.chatClient = chatClient;
        this.checkpointRepo = checkpointRepo;
    }

    public String executeTask(String conversationId, String taskDescription) {
        // 检查是否有未完成的检查点
        TaskCheckpoint checkpoint = checkpointRepo.findByConversationId(conversationId);

        int startStep = (checkpoint != null) ? checkpoint.getLastCompletedStep() : 0;

        // Agent 执行任务
        String result = chatClient.prompt()
                .system("你是一个任务执行 Agent。当前任务：" + taskDescription 
                        + "，从步骤 " + (startStep + 1) + " 开始。")
                .user("执行任务")
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("startStep", startStep))
                .call()
                .content();

        // 保存检查点
        checkpointRepo.save(new TaskCheckpoint(conversationId, taskDescription, startStep + 1));

        return result;
    }
}
```

> **遇到阻塞？→ [教程 35-长任务持久化与中断恢复]**：长任务的完整持久化方案——检查点机制、故障恢复、幂等设计。

---

## 7. 适用场景与不适用场景

### 适用场景

- 多轮对话场景（客服、咨询、辅导），需要保持上下文连贯
- 多步骤任务执行（需要跟踪当前执行到哪一步）
- 多用户并发（需要会话隔离，互不干扰）
- 长时间运行的 Agent（需要状态持久化，支持中断恢复）
- 个性化服务（根据用户 Profile 定制 Agent 行为）

### 不适用场景

- 一次性问答（无状态的 API 调用，不需要历史上下文）
- 简单的文本生成（如翻译、摘要，不涉及状态）
- 对上下文窗口极度敏感的场景（历史消息会消耗 Token，可能不适合）
- 严格无状态的微服务架构（每个请求必须完全独立）

---

## 8. 本章总结

| 概念 | 一句话 |
|------|--------|
| **Agent 状态** | 三个层次：会话状态、对话状态、任务状态 |
| **状态机** | 空闲 → 思考 → 执行工具 → 等待结果 → 回复 → 空闲 |
| **会话生命周期** | 创建 → 活跃 → 挂起/过期 → 结束 → 归档 |
| **CONVERSATION_ID** | 会话唯一标识，驱动状态隔离，每个请求必填 |
| **ChatMemory** | 对话状态的存储抽象，支持内存/JDBC/Redis 持久化 |
| **Advisor** | 状态流转的拦截器，前置注入上下文，后置更新状态 |
| **上下文管理** | Token 预算分配 + 上下文压缩，平衡推理质量与成本 |
| **长任务持久化** | 检查点机制，每步完成后保存状态，支持中断恢复 |

---

> **想深入？→ [教程 04-记忆与会话管理]**：ChatMemory API 的完整使用细节和持久化方案。
> **遇到阻塞？→ [教程 35-长任务持久化与中断恢复]**：检查点恢复、幂等设计、故障转移的完整方案。
> **想深入？→ [教程 13-Advisor链与拦截器.md]**：Advisor 链的执行机制和自定义 Advisor 的完整实现。
