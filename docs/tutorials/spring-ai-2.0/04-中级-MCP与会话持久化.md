# L4 中级 - MCP 与会话持久化

> 把工具跨进程化（MCP）+ 把会话落库（event-sourced）。
>
> 前置：[`./03-中级-Advisor2.0与结构化校验.md`](./03-中级-Advisor2.0与结构化校验.md)
> 预计：1-2 天

---

## 1. MCP（Model Context Protocol）是什么

### 1.1 一句话

Anthropic 2024-11 推出的开放协议，让 AI 应用通过**统一标准**调用**跨进程、跨语言**的工具、资源、Prompt。

### 1.2 解决什么问题

| 1.0 时代的痛点 | MCP 怎么解决 |
|---------------|------------|
| 工具必须是 Java 进程内的 Bean | MCP Server 可以是 Python / Node / Go / 任何语言 |
| 工具升级要重新部署 AI 应用 | MCP Server 独立部署，热升级 |
| 多个 AI 应用复用工具难 | MCP Server 集中暴露，所有 Client 都能调 |
| 企业工具生态割裂 | MCP 是工具的"USB-C 接口" |

### 1.3 MCP 三大资源

| 类型 | 含义 | 用法 |
|------|------|------|
| **Tool** | 可执行的函数 | LLM 决定调用 |
| **Resource** | 可读取的数据源（文件、DB 行） | LLM 主动读 |
| **Prompt** | 可复用的 Prompt 模板 | 用户或 Advisor 触发 |

---

## 2. Spring AI 2.0 的 MCP 体系

### 2.1 三种角色

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│ MCP Client   │ ←stdio→ │ MCP Server   │ ←HTTP→  │ MCP Server   │
│ （Spring AI  │         │ （本地 Java）│         │ （远程 Py）  │
│  你的应用）  │         │              │         │              │
└──────────────┘         └──────────────┘         └──────────────┘
```

| 角色 | 谁来做 | 用什么 |
|------|--------|-------|
| Client | 你的 Spring AI 应用 | `spring-ai-mcp-client-spring-boot-starter` |
| Server | 暴露工具的一方 | `spring-ai-mcp-server-webflux-spring-boot-starter` 或其他 MCP 实现 |
| 协议 | stdio / SSE / HTTP | MCP 规范 |

### 2.2 引入 MCP Client

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### 2.3 配置 MCP Server 地址

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

`mcp-servers.json`：
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"
      }
    }
  }
}
```

> 这里调用的是 Node.js 写的官方 MCP Server。Spring AI Client 会通过 stdio 启动这些子进程并和它们通信。

### 2.4 自动集成到 ChatClient

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      List<McpToolCallback> mcpTools) {
    return builder
            .defaultTools(mcpTools.toArray(new ToolCallback[0]))   // MCP 工具变成本地工具
            .build();
}
```

启动后，所有 MCP Server 暴露的工具**自动**变成 Spring AI 的 `ToolCallback`，跟本地 `@Tool` 一样使用。

---

## 3. 自己写 MCP Server（Java 版）

### 3.1 引入依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

### 3.2 暴露 Tool

```java
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMcpServer {

    @McpTool(description = "根据工号查询员工信息")
    public Employee findByEmployeeId(String employeeId) {
        return employeeService.findById(employeeId);
    }

    @McpTool(description = "查询所有部门")
    public List<Department> listDepartments() {
        return departmentService.findAll();
    }

    @McpResource(uri = "employee://all", description = "全员列表 JSON")
    public String allEmployees() {
        return objectMapper.writeValueAsString(employeeService.findAll());
    }
}
```

### 3.3 启动配置

```yaml
spring:
  ai:
    mcp:
      server:
        name: employee-mcp-server
        version: 1.0.0
        type: SYNC   # 或 ASYNC
```

### 3.4 部署

打包成 jar / Docker，独立部署在 `http://your-mcp-server:8081`。

### 3.5 其他 AI 应用接入

你的 Spring AI Server、Python LangChain、Node.js AI Agent 都能通过 MCP 协议访问这套工具——**这就是"USB-C"的价值**。

---

## 4. 会话持久化：spring-ai-session

### 4.1 1.0 的痛点

`MessageWindowChatMemory` 是内存版：
- 进程重启 → 会话丢
- 多实例 → 用户连不同实例历史不一样
- 无法回放"上次走到第几轮"

### 4.2 2.0 的 spring-ai-session

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-session</artifactId>
</dependency>
```

**核心**：基于 Spring Boot 4 的 `SessionRepository` + event-sourcing。每个对话动作（user message、assistant message、tool call、tool result）都是一个事件，落到 DB。

### 4.3 配置

```yaml
spring:
  ai:
    session:
      repository-type: jdbc    # jdbc / redis / mongo
      timeout: 30m             # 会话超时
```

JDBC schema 自动生成（Spring Boot 4 默认会建表）。

### 4.4 使用

```java
@Service
public class ConversationService {

    private final ChatClient chatClient;
    private final SessionRepository sessionRepository;

    public String chat(String sessionId, String userMessage) {
        // 加载历史
        Session session = sessionRepository.findById(sessionId)
                .orElseGet(() -> sessionRepository.create(sessionId));

        String answer = chatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        return answer;
    }

    public void replay(String sessionId) {
        // event-sourced：可以回放任意时刻的状态
        Session session = sessionRepository.findById(sessionId).orElseThrow();
        List<SessionEvent> events = session.events();
        events.forEach(e -> System.out.println(e.timestamp() + " " + e.type() + " " + e.payload()));
    }
}
```

---

## 5. Memory Advisor 与 Session 的协作

### 5.1 标准装配

```java
@Bean
ChatMemory chatMemory(SessionRepository sessionRepository) {
    return SessionBackedChatMemory.builder()
            .sessionRepository(sessionRepository)
            .build();
}

@Bean
MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
    return MessageChatMemoryAdvisor.builder(chatMemory)
            .conversationId("default")
            .build();
}

@Bean
ChatClient chatClient(ChatClient.Builder builder, MessageChatMemoryAdvisor advisor) {
    return builder.defaultAdvisors(advisor).build();
}
```

### 5.2 多租户会话隔离

```java
@GetMapping("/chat")
public String chat(@RequestParam String q, @RequestParam String userId) {
    String conversationId = "user-" + userId;
    return chatClient.prompt()
            .user(q)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```

不同 userId → 不同 conversationId → 不同会话记录 → DB 里完全隔离。

### 5.3 会话窗口策略

```java
@Bean
ChatMemory chatMemory(SessionRepository repo) {
    return SessionBackedChatMemory.builder()
            .sessionRepository(repo)
            .windowStrategy(WindowStrategy.LAST_N_MESSAGES.with(20))   // 最近 20 条
            .build();
}
```

或基于 token：
```java
.windowStrategy(WindowStrategy.LAST_N_TOKENS.with(2000))
```

---

## 6. 实战：可恢复的多轮对话 Agent

### 6.1 完整流程

```
用户：/chat?userId=u1&q=查一下我的订单
    ↓
构造 conversationId = "user-u1"
    ↓
SessionBackedChatMemory 从 DB 加载该 conversationId 的历史事件
    ↓
拼接成 prompt 发给 LLM
    ↓
LLM 调用 queryMyOrders(userId=u1)
    ↓
ToolCallingAdvisor 执行工具
    ↓
LLM 生成响应
    ↓
SessionBackedChatMemory 把这轮的 UserMessage + AssistantMessage + ToolCall + ToolResult 全部 append 到 session
    ↓
返回给用户
```

### 6.2 重启后

进程崩了重启 → 用户继续问 `/chat?userId=u1&q=刚才那个订单到哪了`：
- 从 DB 加载历史
- LLM 看到上下文"上次查了订单 #12345"
- 自然衔接

### 6.3 审计价值

```sql
SELECT * FROM session_events
WHERE session_id LIKE 'user-u1-%'
ORDER BY timestamp;
```

每个动作都有时间戳，可回溯。**合规场景必备**。

---

## 7. MCP + Session 的组合架构

```
┌──────────────────────────────────────────────┐
│ 你的 Spring AI 2.0 应用                       │
│ ┌──────────────────────────────────────────┐ │
│ │ ChatClient                                │ │
│ │  ├─ MemoryAdvisor（Session-backed）       │ │
│ │  ├─ ToolCallingAdvisor                    │ │
│ │  │   ├─ 本地 @Tool Bean                   │ │
│ │  │   ├─ MCP Client → filesystem (Node)   │ │
│ │  │   ├─ MCP Client → github (Node)        │ │
│ │  │   └─ MCP Client → employee-mcp (Java) │ │
│ │  └─ StructuredOutputValidation            │ │
│ └──────────────────────────────────────────┘ │
│                       ↓                       │
│ ┌──────────────────────────────────────────┐ │
│ │ Session Repository                        │ │
│ │  ↓ JDBC / Redis / Mongo                   │ │
│ └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

价值：
- 工具**可独立升级**（MCP Server 改了不影响你的 AI 应用）
- 会话**可恢复**（进程崩了不丢数据）
- 历史**可审计**（event-sourced 完整记录）

---

## 8. 常见错误

### 8.1 MCP Server 连不上

**症状**：`McpError: server not reachable`。

**排查**：
1. MCP Server 进程是否启动（`ps aux | grep mcp`）
2. 配置文件路径是否正确（`classpath:mcp-servers.json`）
3. 命令是否在 PATH 里（如 `npx` 需要先 `npm install -g npx`）

### 8.2 MCP 工具没自动注入

**症状**：`List<McpToolCallback>` 注入失败或为空。

**原因**：MCP Server 启动了但没暴露工具。

**排查**：开 DEBUG 看 MCP 协议握手日志：
```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
```

### 8.3 Session 加载慢

**症状**：每次 chat 都要全量读历史，500+ 条时延迟明显。

**解决**：
- 用 token 窗口策略（只加载最近 2000 token）
- 给 `session_id` 加索引
- 考虑 Redis 后端（更快）

### 8.4 MCP Server 升级后 Client 报错

**原因**：工具签名变了。

**解决**：MCP 协议支持版本协商，但工具签名变化仍要 Client 端代码适配。建议 MCP Server 工具签名变更走"加新工具 + 弃用旧工具"两步走。

---

## 9. 理解检查

1. MCP 跟本地 `@Tool` 的本质区别是什么？
2. `spring-ai-session` 跟 `MessageWindowChatMemory` 的区别？
3. 为什么要做"会话可恢复"？业务价值在哪？
4. 多租户场景下 conversationId 怎么设计？
5. MCP Server 独立部署有哪些好处？

---

## 10. 练习任务

1. 引入 MCP Client，配置一个 Node.js 的 filesystem MCP Server，让 LLM 能读 `/tmp` 下的文件
2. 自己写一个 Java MCP Server 暴露 2 个 `@McpTool`，独立部署在 8081 端口
3. 让你的 Spring AI 应用同时用本地 Tool + MCP Tool，对比调用差异
4. 引入 `spring-ai-session`，配置 JDBC 后端，让会话能跨重启恢复
5. 用 SQL 查询某个用户的完整会话历史
6. 实现一个 endpoint `/chat/replay?sessionId=xxx`，回放会话事件

---

## 11. 进 L5 之前的能力确认

完成本篇你应该能：
- [ ] 跑通 MCP Client + Server 全链路
- [ ] 独立写一个 Java MCP Server 并部署
- [ ] 用 `spring-ai-session` 让会话跨进程重启可恢复
- [ ] 设计多租户会话隔离方案
- [ ] 从 DB 查询和回放会话历史

完成后进入 [`./05-进阶-五大Workflow模式Advisor实现.md`](./05-进阶-五大Workflow模式Advisor实现.md) ⭐（核心篇）。
