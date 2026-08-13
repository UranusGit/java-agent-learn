# 10-MCP 协议

> **定位**：讲透 Model Context Protocol（MCP）——它是什么、为什么出现、架构如何设计、Spring AI 2.0 如何支持 MCP 客户端和服务端、MCP 与传统 Tool Calling 的本质区别。读完这篇，你能用 MCP 协议构建标准化的工具生态。
>
> **读者画像**：已经掌握 Tool Calling，需要理解或构建跨平台工具协议的开发者。
>
> **前置阅读**：[03-工具调用](03-工具调用.md)。

---

## 1. MCP 是什么

Model Context Protocol（MCP）是 Anthropic 于 2024 年 11 月开源的一项**标准化协议**，核心目标是解决一个日益严重的问题：每个 LLM 平台都有自己的工具接入方式，每个工具有自己的 API 格式，开发者被迫为不同平台写不同的适配代码。

一句话概括 MCP 的价值：**像 USB-C 统一了接口一样，MCP 统一了 LLM 与外部工具/数据源的连接方式**。

```mermaid
graph TB
    subgraph 没有MCP["❌ 没有 MCP 的世界"]
        L1["LLM A（OpenAI）"] -->|"专有格式"| T1["工具 1"]
        L1 -->|"专有格式"| T2["工具 2"]
        L2["LLM B（Anthropic）"] -->|"另一套格式"| T1
        L2 -->|"另一套格式"| T2
        L3["LLM C（DeepSeek）"] -->|"又一套格式"| T1
        L3 -->|"又一套格式"| T2
        Note1["N 个 LLM × M 个工具 = N×M 个适配器"]
    end

    subgraph 有MCP["✅ 有 MCP 的世界"]
        LL1["LLM A"] --> MCP["MCP 协议<br/>（统一标准）"]
        LL2["LLM B"] --> MCP
        LL3["LLM C"] --> MCP
        MCP --> TT1["工具 1<br/>（MCP Server）"]
        MCP --> TT2["工具 2<br/>（MCP Server）"]
        MCP --> TT3["工具 3<br/>（MCP Server）"]
        Note2["N + M 个适配器<br/>每个工具只实现一次"]
    end

    style 没有MCP fill:#ffcdd2
    style 有MCP fill:#c8e6c9
```

### 1.1 MCP 解决的核心痛点

| 痛点 | 没有 MCP | 有 MCP |
|------|---------|--------|
| **工具复用** | 为 GPT 写的 Function Calling 代码，换到 Claude 要重写 | 写一个 MCP Server，所有支持 MCP 的 LLM 都能用 |
| **工具发现** | 每次都要手动注册工具定义 | MCP Server 自描述能力，客户端自动发现可用工具 |
| **生态隔离** | OpenAI Plugins、Anthropic Tools、Gemini Functions 各搞各的 | 统一协议，工具生态跨平台共享 |
| **安全边界** | 工具直接嵌入 LLM 进程，安全边界模糊 | MCP Server 是独立进程，天然进程级隔离 |

### 1.2 MCP 不是什么

MCP **不是**一个 LLM 调用框架——它不关心你用哪个模型、怎么管理 Prompt、怎么处理流式输出。MCP 只关心一件事：**LLM 客户端如何以标准化的方式发现和调用外部能力**。

MCP 也不替代 Spring AI 的 `@Tool` 注解——`@Tool` 是进程内的工具调用，MCP 是跨进程的工具协议。两者是互补关系，不是替代关系。

---

## 2. MCP 架构：Host / Client / Server

MCP 协议定义了三个核心角色，构成一个清晰的分层架构。

```mermaid
graph TB
    subgraph Host层["Host 层（宿主应用）"]
        HOST["MCP Host<br/>例如：IDE、AI Agent 应用<br/>管理多个 MCP Client"]
    end

    subgraph Client层["Client 层（协议客户端）"]
        C1["MCP Client A<br/>连接文件系统 Server"]
        C2["MCP Client B<br/>连接数据库 Server"]
        C3["MCP Client C<br/>连接 GitHub Server"]
    end

    subgraph Server层["Server 层（能力提供方）"]
        S1["MCP Server: 文件系统<br/>暴露 read_file / write_file"]
        S2["MCP Server: 数据库<br/>暴露 query / execute"]
        S3["MCP Server: GitHub<br/>暴露 create_issue / merge_pr"]
    end

    HOST --> C1
    HOST --> C2
    HOST --> C3

    C1 -->|"stdio / SSE<br/>JSON-RPC 2.0"| S1
    C2 -->|"stdio / SSE<br/>JSON-RPC 2.0"| S2
    C3 -->|"stdio / SSE<br/>JSON-RPC 2.0"| S3

    style Host层 fill:#e8f5e9
    style Client层 fill:#e3f2fd
    style Server层 fill:#fff9c4
```

### 2.1 三个角色的职责

| 角色 | 职责 | 类比 |
|------|------|------|
| **Host** | 宿主应用，持有 LLM，管理 Client 生命周期，决定何时调用工具 | 操作系统 |
| **Client** | 协议客户端，与单个 Server 保持 1:1 连接，负责请求/响应的序列化 | 设备驱动 |
| **Server** | 能力提供方，独立进程，暴露 Tools / Resources / Prompts | USB 设备 |

**关键设计原则**：每个 Client 只连接一个 Server（1:1 关系）。Host 可以管理多个 Client，从而同时连接多个 Server。

### 2.2 MCP Server 暴露的三种能力

```mermaid
graph LR
    subgraph MCP能力["MCP Server 暴露的能力"]
        TOOLS["Tools（工具）<br/>可执行的函数<br/>例：read_file、query_db"]
        RESOURCES["Resources（资源）<br/>可读取的数据<br/>例：文件内容、数据库表结构"]
        PROMPTS["Prompts（提示模板）<br/>预定义的 Prompt<br/>例：code_review 模板"]
    end

    style MCP能力 fill:#e3f2fd
```

- **Tools**：有副作用的操作（写入文件、发送请求、修改数据库）。LLM 决定何时调用。
- **Resources**：只读数据源（文件内容、API 文档、数据库 Schema）。Host 决定何时提供给 LLM。
- **Prompts**：预定义的 Prompt 模板，用户可以通过 `/` 命令触发。

### 2.3 通信协议

MCP 使用 **JSON-RPC 2.0** 作为消息格式，支持两种传输方式：

```mermaid
graph TB
    subgraph 传输方式["MCP 传输方式"]
        STDIO["stdio（标准输入输出）<br/>适用：本地进程<br/>延迟低，适合开发调试"]
        SSE["HTTP + SSE<br/>适用：远程 Server<br/>支持网络传输，适合生产"]
    end

    style 传输方式 fill:#e3f2fd
```

**stdio 模式**的交互流程：

```mermaid
sequenceDiagram
    participant H as Host / Client
    participant S as MCP Server（子进程）

    H->>S: 启动 Server 进程
    H->>S: initialize 请求（JSON-RPC）
    S-->>H: initialize 响应（Server 能力声明）
    
    H->>S: tools/list 请求
    S-->>H: 返回工具列表（名称 + Schema）
    
    Note over H: LLM 决定调用某个工具
    H->>S: tools/call 请求（工具名 + 参数）
    S-->>H: 工具执行结果
    
    H->>S: shutdown 请求
    S-->>H: 连接关闭
```

---

## 3. Spring AI 2.0 的 MCP 客户端支持

Spring AI 2.0 提供了完整的 MCP 客户端实现，可以将外部 MCP Server 暴露的工具无缝接入 ChatClient 的工具调用链。

### 3.1 添加 MCP 客户端依赖

```xml
<!-- pom.xml -->
<!-- Spring AI 2.0.0 MCP 客户端 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-client-spring-boot-starter</artifactId>
</dependency>
```

### 3.2 配置 MCP Server 连接

```yaml
# application.yml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

`src/main/resources/mcp-servers.json`（MCP Server 配置文件）：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_TOKEN": "${GITHUB_TOKEN}"
      }
    }
  }
}
```

### 3.3 将 MCP 工具接入 ChatClient

```java
import org.springframework.ai.mcp.McpClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

@RestController
public class McpAgentController {

    private final ChatClient chatClient;

    // Spring AI 2.0.0 — 自动注入 McpClient，连接配置的 MCP Server
    public McpAgentController(
            ChatClient.Builder builder,
            McpClient mcpClient) {
        this.chatClient = builder
                // 将 MCP Server 暴露的所有工具注册为 ToolCallback
                .defaultTools(new SyncMcpToolCallbackProvider(mcpClient).getToolCallbacks())
                .build();
    }

    @GetMapping("/agent")
    public String agent(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

这段代码背后的工作流程：

```mermaid
sequenceDiagram
    participant U as 用户
    participant CC as ChatClient
    participant MC as McpClient
    participant MS as MCP Server（文件系统）
    participant L as LLM（DeepSeek）

    U->>CC: "帮我读 /data/config.yml 的内容"
    
    Note over MC: 启动时已完成 MCP 握手
    CC->>MC: 获取可用工具列表
    MC-->>CC: [read_file, write_file, list_dir...]

    CC->>L: Prompt + 工具定义（来自 MCP Server）
    L-->>CC: 工具调用：read_file("/data/config.yml")
    
    CC->>MC: 调用 read_file
    MC->>MS: tools/call（JSON-RPC）
    MS-->>MC: 文件内容
    MC-->>CC: 工具结果
    
    CC->>L: 工具结果 → 生成最终回复
    L-->>CC: "config.yml 的内容是..."
    CC-->>U: "config.yml 的内容是..."
```

### 3.4 流式 SSE 模式的 MCP 客户端

当 MCP Server 是远程服务时，使用 HTTP + SSE 传输：

```java
import org.springframework.ai.mcp.McpClient;
import org.springframework.ai.mcp.transport.HttpSseClientTransport;

// Spring AI 2.0.0 — 连接远程 MCP Server
@Bean
McpClient remoteMcpClient() {
    return McpClient.create(
            HttpSseClientTransport.builder("https://mcp.example.com")
                    .build()
    );
}
```

---

## 4. Spring AI 2.0 的 MCP 服务端支持

Spring AI 2.0 不仅支持 MCP 客户端（消费工具），还支持 MCP 服务端（提供工具）。这意味着你可以把自己的业务能力暴露为 MCP Server，供任何支持 MCP 的 LLM 客户端使用。

### 4.1 添加 MCP 服务端依赖

```xml
<!-- pom.xml -->
<!-- Spring AI 2.0.0 MCP 服务端 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
</dependency>
```

### 4.2 配置 MCP Server

```yaml
# application.yml
spring:
  ai:
    mcp:
      server:
        name: "my-enterprise-tools"
        version: "1.0.0"
        type: SYNC
        sse-message-endpoint: /mcp/message
```

### 4.3 定义 MCP 工具

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.mcp.server.autoconfigure.McpServerFeature;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseTools {

    private final OrderService orderService;
    private final ProductService productService;

    public EnterpriseTools(OrderService orderService, ProductService productService) {
        this.orderService = orderService;
        this.productService = productService;
    }

    // Spring AI 2.0.0 — @Tool 自动暴露为 MCP 工具
    @Tool(description = "根据订单号查询订单详情，包括金额、状态和物流")
    public OrderDetail queryOrder(
            @ToolParam(description = "订单号，格式 ORD-XXXXXX") String orderId
    ) {
        return orderService.queryDetail(orderId);
    }

    @Tool(description = "根据关键词搜索产品，返回价格、库存和规格信息")
    public List<Product> searchProducts(
            @ToolParam(description = "搜索关键词") String keyword
    ) {
        return productService.search(keyword);
    }
}
```

Spring AI 自动将这些 `@Tool` 方法注册到 MCP Server，外部 MCP 客户端连接后可以自动发现和调用这些工具。

```mermaid
graph LR
    subgraph MCP服务端["Spring AI MCP Server"]
        TOOLS["EnterpriseTools<br/>queryOrder()<br/>searchProducts()"]
        AUTO["自动注册<br/>生成 JSON Schema<br/>暴露为 MCP 工具"]
    end

    CLIENT1["Claude Desktop"] -->|"MCP"| AUTO
    CLIENT2["VS Code AI"] -->|"MCP"| AUTO
    CLIENT3["另一个 Spring AI Agent"] -->|"MCP"| AUTO

    AUTO --> TOOLS

    style MCP服务端 fill:#c8e6c9
```

---

## 5. 自定义 MCP Server：完整示例

假设我们要构建一个企业内部的工单系统 MCP Server，让所有 AI Agent 都能通过 MCP 协议创建和查询工单。

### 5.1 项目结构

```
mcp-ticket-server/
├── pom.xml
├── src/main/java/com/example/mcp/
│   ├── McpServerApplication.java
│   ├── config/
│   │   └── McpServerConfig.java
│   └── tools/
│       └── TicketTools.java
└── src/main/resources/
    └── application.yml
```

### 5.2 核心实现

```java
// McpServerApplication.java
@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
```

```java
// TicketTools.java
package com.example.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TicketTools {

    private final TicketService ticketService;

    public TicketTools(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // Spring AI 2.0.0 — MCP Server 自动暴露此方法
    @Tool(description = "创建售后工单，需要客户ID、问题描述和工单类型")
    public Ticket createTicket(
            @ToolParam(description = "客户 ID") String customerId,
            @ToolParam(description = "问题描述") String description,
            @ToolParam(description = "工单类型：退款、换货、维修、投诉") String type
    ) {
        return ticketService.create(customerId, description, type);
    }

    // Spring AI 2.0.0 — 查询工单状态
    @Tool(description = "根据工单号查询工单当前状态和处理进度")
    public TicketStatus queryTicket(
            @ToolParam(description = "工单号") String ticketId
    ) {
        return ticketService.queryStatus(ticketId);
    }

    // Spring AI 2.0.0 — 列出客户所有工单
    @Tool(description = "列出指定客户的所有工单")
    public List<Ticket> listTickets(
            @ToolParam(description = "客户 ID") String customerId
    ) {
        return ticketService.listByCustomer(customerId);
    }
}
```

```yaml
# application.yml
spring:
  ai:
    mcp:
      server:
        name: "enterprise-ticket-server"
        version: "1.0.0"
        type: SYNC
        sse-message-endpoint: /mcp/message
```

启动后，这个 Spring Boot 应用就是一个完整的 MCP Server，任何 MCP 客户端都能连接它，自动发现 `createTicket`、`queryTicket`、`listTickets` 三个工具。

---

## 6. MCP 与传统 Tool Calling 的关系和区别

这是开发者最常困惑的问题。MCP 和 `@Tool` 不是二选一，而是解决不同层面的问题。

### 6.1 本质区别

```mermaid
graph TB
    subgraph 传统Tool["传统 Tool Calling（@Tool）"]
        direction TB
        TC1["工具定义在 Java 代码中"]
        TC2["工具执行在同一个 JVM 内"]
        TC3["工具与 Agent 紧耦合"]
        TC4["换 Agent 框架 → 重写工具"]
    end

    subgraph MCP["MCP 协议"]
        direction TB
        MC1["工具定义在独立进程"]
        MC2["工具通过 JSON-RPC 调用"]
        MC3["工具与 Agent 完全解耦"]
        MC4["换 Agent 框架 → 工具零修改"]
    end

    style 传统Tool fill:#fff9c4
    style MCP fill:#c8e6c9
```

### 6.2 详细对比

| 维度 | @Tool（进程内） | MCP（跨进程） |
|------|----------------|--------------|
| **部署方式** | 工具与 Agent 在同一 JVM | 工具在独立进程/远程服务器 |
| **调用方式** | Java 方法直接调用 | JSON-RPC 2.0 协议调用 |
| **性能** | 微秒级（进程内方法调用） | 毫秒级（IPC 或网络开销） |
| **复用性** | 只能被当前 Spring AI 应用使用 | 可被任何 MCP 兼容客户端使用 |
| **安全隔离** | 无进程隔离 | 进程级隔离，权限可控 |
| **开发成本** | 低（注解即可） | 中高（需独立项目 + 协议处理） |
| **适用规模** | 小型项目，工具数量少 | 大型生态，工具跨团队/跨平台共享 |

### 6.3 何时用 @Tool，何时用 MCP

```mermaid
graph TB
    START["需要接入工具"] --> Q1{"工具是否需要<br/>被多个不同平台/Agent 复用？"}
    Q1 -->|"是"| MCP["使用 MCP Server"]
    Q1 -->|"否"| Q2{"工具是否需要<br/>独立部署和扩展？"}
    Q2 -->|"是"| MCP
    Q2 -->|"否"| Q3{"团队规模是否较大<br/>需要工具团队独立迭代？"}
    Q3 -->|"是"| MCP
    Q3 -->|"否"| TOOL["使用 @Tool 注解"]

    style MCP fill:#c8e6c9
    style TOOL fill:#e3f2fd
```

### 6.4 混合使用：最佳实践

实际企业项目中，通常是**混合使用**：核心业务工具用 `@Tool`（性能敏感），通用能力用 MCP Server（生态共享）。

```java
@RestController
public class HybridAgentController {

    private final ChatClient chatClient;

    // Spring AI 2.0.0 — 混合使用 @Tool 和 MCP 工具
    public HybridAgentController(
            ChatClient.Builder builder,
            McpClient mcpClient,
            OrderTools orderTools     // 进程内 @Tool
    ) {
        this.chatClient = builder
                // 进程内工具：订单查询（低延迟，核心业务）
                .defaultTools(orderTools)
                // MCP 工具：文件系统、GitHub 等（生态共享，通用能力）
                .defaultToolCallbacks(
                        new SyncMcpToolCallbackProvider(mcpClient).getToolCallbacks()
                )
                .build();
    }
}
```

从 LLM 的角度看，两种工具没有区别——都是工具列表中的一项。Spring AI 在底层透明地处理：进程内工具走 Java 方法调用，MCP 工具走 JSON-RPC。

---

## 7. MCP 生态现状与安全考量

### 7.1 MCP 生态

截至 2025 年，MCP 生态已有大量社区维护的 Server：

| MCP Server | 能力 | 来源 |
|-----------|------|------|
| filesystem | 文件读写、目录遍历 | 官方 |
| github | 创建 Issue、Merge PR、搜索代码 | 官方 |
| postgres | SQL 查询、Schema 探索 | 官方 |
| google-drive | 搜索和读取 Google Drive 文件 | 社区 |
| slack | 发送消息、搜索频道 | 社区 |
| puppeteer | 浏览器自动化 | 官方 |

### 7.2 安全风险

MCP Server 是独立进程，可以访问文件系统、网络、数据库。安全考量：

```mermaid
graph TB
    subgraph 安全防护["MCP 安全要点"]
        SEC1["1. 最小权限原则<br/>MCP Server 只暴露必要的工具"]
        SEC2["2. 环境变量管理<br/>API Token 通过 env 注入，不硬编码"]
        SEC3["3. 进程隔离<br/>MCP Server 运行在受限沙箱中"]
        SEC4["4. 审计日志<br/>记录每次 tools/call 的请求和响应"]
        SEC5["5. 人工审批<br/>高危工具（删除、转账）需 HITL"]
    end

    style 安全防护 fill:#ffcdd2
```

> **想深入？→ [附录 08-Agent安全深度/01-ToolPoisoning攻击.md]**：MCP Server 被篡改后的工具投毒攻击与防御方案。

---

## 8. 适用场景与不适用场景

### 适用场景

- 企业内部构建统一工具平台，多个 AI Agent 共享同一套工具
- 需要将工具暴露给第三方 LLM 客户端（如 Claude Desktop、VS Code AI）
- 工具团队与 Agent 团队分离，需要独立部署和迭代
- 开源工具生态贡献——构建 MCP Server 供社区使用
- 需要强安全隔离的场景——工具运行在独立沙箱进程中

### 不适用场景

- 小型项目，工具数量少，没有跨平台复用需求
- 对延迟极度敏感的场景（MCP 的 IPC/网络开销不可忽略）
- 工具逻辑简单，用 `@Tool` 一行注解就能解决
- 单体应用内部，所有工具都在同一个代码仓库

---

## 9. 本章总结

| 概念 | 一句话 |
|------|--------|
| **MCP 协议** | LLM 与外部工具/数据源的标准化连接协议，类比 USB-C |
| **Host** | 宿主应用，持有 LLM，管理多个 MCP Client |
| **Client** | MCP 协议客户端，与单个 Server 保持 1:1 连接 |
| **Server** | 能力提供方，独立进程，暴露 Tools / Resources / Prompts |
| **传输方式** | stdio（本地进程）或 HTTP + SSE（远程服务） |
| **消息格式** | JSON-RPC 2.0 |
| **Spring AI MCP Client** | `McpClient` + `SyncMcpToolCallbackProvider`，将 MCP 工具接入 ChatClient |
| **Spring AI MCP Server** | `@Tool` 自动暴露为 MCP 工具，独立部署后供外部客户端调用 |
| **MCP vs @Tool** | @Tool 是进程内调用（高性能），MCP 是跨进程协议（高复用性）——混合使用是最佳实践 |

---

> **想深入？→ [教程 14-管控分离架构]**：MCP Server 如何实现 Agent 内核与外部能力的完全解耦。
> **想深入？→ [教程 03-工具调用]**：回顾 Tool Calling 机制，理解 MCP 在底层复用了同一套调用循环。
> **想深入？→ [附录 08-Agent安全深度/01-ToolPoisoning攻击.md]**：MCP 安全风险与防御。
