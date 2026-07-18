# 05 MCP 协议入门 + Client 通关

> 本文是 MCP 三部曲的**起点**：完全没接触过 MCP 的同学从这里开始。
>
> 读完这一篇你能做到：把一个**已经存在的** MCP Server（自己写的、同事写的、社区开源的）接入 Spring AI 应用，让 ChatClient 自动调用其中的工具，跨进程、跨语言、跨团队。
>
> 三部曲路线：
> - **05（本文）= 入门 + Client 通关**：先用起来，理解协议
> - [06 = MCP Server 开发实战](./06-MCP-Server开发实战.md)：自己造一个 Server
> - [07 = 端到端整合 + 生态进阶](./07-MCP-Server高阶与生态.md)：把 05 和 06 拼起来跑通完整链路，再做 Hub/跨语言/性能/安全
>
> 前置：[02-Tool与AgentLoop.md](./02-Tool与AgentLoop.md) + [03-Advisor链全解.md](./03-Advisor链全解.md)
> 预计：1 天

---

## 0. 这篇怎么读

如果你完全没听过 MCP：按 §1→§2→§3 顺序读。
如果你已经知道 MCP 是什么、只想看怎么在 Spring AI 里当 Client：跳到 §4。

---

## 1. 为什么会有 MCP（10 分钟讲清）

### 1.1 进程内 Tool 的天花板

在 [02 篇](./02-Tool与AgentLoop.md) 里你学会了用 `@Tool` 给 ChatClient 加工具：

```java
@Component
public class TimeTools {
    @Tool(description = "获取当前时间")
    public String currentTime() { return new Date().toString(); }
}
```

这能解决 80% 的问题，但有四类场景它搞不定：

| 场景 | 进程内 `@Tool` | MCP Server |
|------|---------------|-----------|
| Claude Desktop / Cursor 要用你的工具 | ❌ 工具在 JVM 里，外部进不来 | ✅ |
| 公司多个 Agent 共享同一套工具（HR、订单、运维） | 每个 Agent 各写一份 | ✅ 一处实现处处可用 |
| 工具是另一个团队/另一种语言写的（Python 算法、Go 中台） | 不行 | ✅ 协议跨语言 |
| 工具是独立部署、独立升级、独立鉴权 | 难拆 | ✅ 天然拆分 |

**MCP（Model Context Protocol，Anthropic 2024-11 推出）解决的就是"把工具从进程内搬到跨进程"**。本质是一套标准化的 RPC 协议，规定 Client 怎么发现 Server 提供的工具、怎么调用、怎么传上下文。

### 1.2 一个类比

把 MCP 想成"LLM 的 USB 协议"：
- USB 协议让任意外设（鼠标、键盘、硬盘）插上电脑就能用
- MCP 协议让任意工具服务（HR、订单、地图）接上 LLM 就能用

你的 Agent 不再需要为每个工具写适配代码，**只要 MCP Server 暴露出来，Client 自动发现并接入**。

---

## 2. MCP 协议心智模型

### 2.1 一张图

```
┌──────────────────────┐                    ┌──────────────────────┐
│   MCP Client         │                    │   MCP Server         │
│ (你的 Spring AI App) │  ◄── stdio / ──►   │ (工具服务的提供方)    │
│                      │      HTTP          │                      │
│  ChatClient          │                    │  @McpTool            │
│   ↓ 调用工具         │  ① listTools       │   - getEmployee      │
│   ↓ 拿到结果         │  ② callTool        │   - searchOrders     │
│                      │  ③ readResource    │  @McpResource        │
│                      │  ④ ...             │   - company://...    │
└──────────────────────┘                    └──────────────────────┘
```

关键点：
- Client 和 Server 是**两个独立进程**（要么 stdio 子进程，要么 HTTP 服务）
- 通信内容是 JSON-RPC 2.0 消息
- Server 可以暴露三类能力（见 §2.2）

### 2.2 三类能力

| 能力 | 中文 | 类比 | LLM 是否决策调用 |
|------|------|------|-----------------|
| **Tools** | 工具 | RPC 里的 POST（有副作用） | ✅ LLM 决定 |
| **Resources** | 资源 | RPC 里的 GET（只读数据） | ❌ Client 用户/代码决定 |
| **Prompts** | 提示词模板 | 一键触发的预设 prompt | ❌ Client 用户决定 |

**作为 Client 端开发者，你最关心的是 Tools**——这是 ChatClient 自动调用的。Resources 和 Prompts 更多是给 Claude Desktop 这种带 UI 的客户端用。

### 2.3 三种传输方式

"传输"= Client 和 Server 之间的字节流怎么走。Spring AI 2.0 三选一：

| 传输 | 适用 | 是否推荐 |
|------|------|---------|
| **stdio** | Client 拉起 Server 作为子进程，本地用 | 仅本地开发 / Claude Desktop |
| **SSE**（Server-Sent Events） | HTTP 长连接单向流 | ❌ **2.0 起 `@Deprecated`** |
| **Streamable HTTP** | HTTP 双向、无状态、可扩缩容 | ✅ **生产推荐** |

无状态变体 **Stateless Streamable HTTP** 适合 Serverless（Lambda / Cloud Run）。

> 2026 年现状：Streamable HTTP 是 MCP 官方与 Spring AI 共同推荐的新协议；SSE 仅作过渡兼容，新项目别用。

### 2.4 协议一次调用长什么样

Client 调用 Server 的 `getEmployee` 工具，背后发生的事情：

```
Client                                    Server
  │                                          │
  │── initialize (handshake) ───────────────►│  协议版本、能力协商
  │◄────────── capabilities ─────────────────│
  │                                          │
  │── tools/list ───────────────────────────►│  "你有哪些工具？"
  │◄── [getEmployee, searchOrders, ...] ─────│
  │                                          │
  │   (此时 LLM 看到工具列表，决定调用 getEmployee)
  │                                          │
  │── tools/call(name=getEmployee,           │
  │              args={id:"E001"}) ─────────►│
  │                                          │  执行业务逻辑
  │◄── result={name:"张三", dept:"HR"} ──────│
  │                                          │
  │   (LLM 拿到结果，组织自然语言返回给用户)   │
```

整个过程**你都不用写代码**——Spring AI 的 MCP Client starter 全自动处理。你只需要：配置 Server 地址 + 把工具列表注入 ChatClient。

---

## 3. 学习路线建议

如果你按 05 → 06 → 07 顺序学：

```
05（本文）              06                     07
入门 + 当 Client        当 Server 作者          端到端 + 进阶
─────────────          ─────────────          ─────────────
用现成 Server           自己造一个 Server       Client 和 Server
跑通第一次调用          暴露 4 类能力           拼起来跑通
理解协议                学会鉴权/限流/可观测    多租户/Hub/跨语言
```

**为什么不先学 Server**：先当用户再当作者，能更快建立"这玩意能干啥"的直觉。等自己造 Server 时，知道每一步对应的 Client 行为是什么。

---

## 4. 5 分钟第一个 MCP Client

我们从最小可运行示例开始：写一个 Spring AI 应用，作为 MCP Client 接入一个**同仓库的、最小化的 MCP Server**。

> 这个最小 Server 的完整开发见 [06 篇 §2](./06-MCP-Server开发实战.md)。本章先用它的成品。如果你只想跑 Client，可以**先把 06 篇 §2 那个 Server clone 跑起来**（一行 `mvn spring-boot:run`），再回到本章。

### 4.1 准备：起一个 Server（30 秒）

按 [06 篇 §2](./06-MCP-Server开发实战.md) 起一个 Server，监听 `http://localhost:8081/mcp`，暴露一个 `currentTime` 工具。本章 Client 就连它。

> 如果你嫌麻烦，也可以用任意一个社区 MCP Server，只要它支持 Streamable HTTP 即可。把下面 yaml 里的 URL 换掉就行。

### 4.2 Client 工程结构

```
mcp-client-demo/
├── pom.xml
└── src/main/java/org/demo02/mcp/client/
    ├── McpClientApplication.java
    └── ClientRunner.java
```

### 4.3 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 本代码仅作学习材料参考 -->
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.0</version>
    </parent>

    <groupId>org.demo02</groupId>
    <artifactId>mcp-client-demo</artifactId>
    <version>0.1.0</version>

    <properties>
        <spring-ai.version>2.0.0</spring-ai.version>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- MCP Client starter（默认走 Streamable HTTP / SSE） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>

        <!-- ChatClient 用的 OpenAI 模型（也可以换 Anthropic/Ollama） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

> ⚠️ 2.0 starter 命名规范已统一为 `spring-ai-starter-*`（1.0 的 `spring-ai-*-spring-boot-starter` 已废弃）。

### 4.4 application.yaml

```yaml
spring:
  ai:
    # ChatClient 用的 OpenAI 模型（按你的实际配置改 apiKey / baseUrl / model）
    openai:
      chat:
        options:
          api-key: ${OPENAI_API_KEY}
          base-url: ${OPENAI_BASE_URL:https://api.openai.com}
          model: gpt-4o-mini

    # MCP Client 配置
    mcp:
      client:
        enabled: true
        name: mcp-client-demo
        version: 0.1.0
        type: SYNC                      # SYNC 注入 McpSyncClient；ASYNC 注入 McpAsyncClient
        request-timeout: 20s
        initialized: true               # 启动时主动 initialize
        streamable-http:                # ← Streamable HTTP 传输（2.0 推荐）
          connections:
            time-server:                # 给这个连接起个名字（任意）
              url: http://localhost:8081/mcp
```

关键点：
- `spring.ai.mcp.client.enabled=true`：开启 Client 自动配置
- `type: SYNC`：用同步客户端（新手友好）。ASYNC 注入 `McpAsyncClient`，返回 `Mono`/`Flux`
- `streamable-http.connections.<name>.url`：Server 的 MCP 端点。每条 connection 都会自动产生一个 `McpSyncClient` Bean 和一个 `McpToolCallbackProvider` Bean

### 4.5 主程序

```java
// 本代码仅作学习材料参考
package org.demo02.mcp.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }
}
```

### 4.6 把 MCP 工具注入 ChatClient 并调用

```java
// 本代码仅作学习材料参考
package org.demo02.mcp.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class ClientRunner {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ToolCallbackProvider[] providers) {  // 框架为每个连接注入一个 provider
        // 把所有 provider 的 ToolCallback 平铺，作为 ChatClient 的默认工具集
        ToolCallback[] all = Arrays.stream(providers)
                .map(ToolCallbackProvider::getToolCallbacks)
                .flatMap(Arrays::stream)
                .toArray(ToolCallback[]::new);

        System.out.println("已注册 MCP 工具：");
        for (ToolCallback t : all) {
            System.out.println("  - " + t.getToolDefinition().name()
                    + ": " + t.getToolDefinition().description());
        }

        return builder.defaultTools(all).build();
    }

    @Bean
    CommandLineRunner ask(ChatClient chatClient) {
        return args -> {
            String answer = chatClient.prompt()
                    .user("现在几点？")
                    .call()
                    .content();
            System.out.println("LLM 回答：" + answer);
        };
    }
}
```

> ⚠️ 注入的是 `ToolCallbackProvider[]`（一个数组），**不是** `List<McpToolCallbackProvider>`。Spring AI 2.0 没有名为 `McpToolCallbackProvider` 的具体类型——每个连接产生的 Provider Bean 实现的是通用接口 `ToolCallbackProvider`，用数组接收即可。

### 4.7 启动 + 看到什么

```bash
# 终端 1：先起 Server
cd geo-mcp-server && mvn spring-boot:run

# 终端 2：再起 Client
cd mcp-client-demo && mvn spring-boot:run
```

Client 启动后你会看到：

```
已注册 MCP 工具：
  - currentTime: 获取服务器当前时间
LLM 回答：根据工具返回，当前服务器时间是 Mon Jul 18 14:23:11 CST 2026
```

**这一刻你的 Spring AI 应用调到了另一个进程里的工具**——这就是 MCP 的全部魔法。

---

## 5. Client 三种传输逐一对照

§4 用了 Streamable HTTP，下面把另外两种也走一遍。**改的全是 yaml，业务代码（`ClientRunner`）一行不动**。

### 5.1 stdio：把 Server 当子进程拉起

适合 Server 是本地命令行工具（如 Claude Desktop 启动一个 Java jar）：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

`src/main/resources/mcp-servers.json`：

```json
{
  "mcpServers": {
    "time-server": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/time-mcp-server.jar"],
      "env": {
        "SPRING_PROFILES_ACTIVE": "stdio"
      }
    }
  }
}
```

Client 启动时会**自己 fork 这个进程**、建立 stdin/stdout 管道。适合：本地工具、不想跑 HTTP 端口。

### 5.2 SSE（旧版 Server 兼容）

如果你的 Server 还是 Spring AI 1.x 或第三方老 Server，只能 SSE：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        sse:
          connections:
            legacy-server:
              url: http://legacy.internal:8082
```

> **不要给新 Server 用 SSE**。2.0 起新项目一律 Streamable HTTP。

### 5.3 三种传输对比

| | stdio | SSE | Streamable HTTP |
|---|------|-----|-----------------|
| 跨机器 | ❌ | ✅ | ✅ |
| 扩缩容 | ❌（进程绑定） | 难（长连接粘性） | ✅（无状态） |
| Serverless 友好 | ❌ | ❌ | ✅ |
| 2.0 推荐 | 仅本地 | ❌ 弃用 | ✅ |

---

## 6. 多 Server 配置

实际项目里 Client 一般会同时连好几个 Server（HR、订单、运维）：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        type: SYNC
        request-timeout: 20s
        streamable-http:
          connections:
            hr-server:
              url: http://hr.internal/mcp
            order-server:
              url: http://order.internal/mcp
            ops-server:
              url: http://ops.internal/mcp
```

每条 connection 自动产生：
- 一个 `McpSyncClient` Bean（连那个 Server）
- 一个 `ToolCallbackProvider` Bean（暴露那个 Server 的所有工具）

注入 `ToolCallbackProvider[]`（如 §4.6 所示）会拿到全部 3 个 Server 的所有工具。

### 6.1 同名工具冲突怎么办

两个 Server 都有 `search` 工具？默认情况 LLM 会看到两个 `search`，可能调错。两种解决方式：

**方式一**：开启自动加前缀（按 Server 名）

```java
// 本代码仅作学习材料参考
@Bean
McpToolNamePrefixGenerator prefixGenerator() {
    // 默认实现：工具名变成 hr-server__search / order-server__search
    return new DefaultMcpToolNamePrefixGenerator();
}
```

**方式二**：用 `McpToolFilter` 过滤/改名

```java
// 本代码仅作学习材料参考
@Bean
McpToolFilter filter() {
    return callbacks -> callbacks.stream()
            .map(c -> c.getToolDefinition().name().equals("search")
                    ? renameTo(c, "search_v2")
                    : c)
            .toList();
}
```

---

## 7. ChatClient 调用 MCP 工具的完整时序

这一节解决一个核心困惑：**"LLM 是怎么知道有 MCP 工具、又是怎么调用的？"**

```
用户输入 "查 E001 员工"
        │
        ▼
┌─────────────────────────────────────────┐
│ ChatClient.prompt().user(...).call()    │
└─────┬───────────────────────────────────┘
      │ 把 prompt 发给 LLM，附上工具列表
      │ （工具列表来自 §4.6 的 defaultTools(all)）
      ▼
┌─────────────────────────────────────────┐
│ LLM（OpenAI / Anthropic）                │
│  - 看到工具 getEmployee(id)              │
│  - 决定调用，返回 tool_call              │
└─────┬───────────────────────────────────┘
      │ Spring AI 拦截到 tool_call
      ▼
┌─────────────────────────────────────────┐
│ ToolCallingManager 路由到对应的 Callback │
│  - 进程内 @Tool → MethodToolCallback     │
│  - MCP 工具     → SyncMcpToolCallback    │
└─────┬───────────────────────────────────┘
      │ SyncMcpToolCallback.call(args)
      ▼
┌─────────────────────────────────────────┐
│ McpSyncClient.callTool(name, args)       │
│  - 通过 Streamable HTTP 发 tools/call    │
└─────┬───────────────────────────────────┘
      │ 跨进程 HTTP
      ▼
┌─────────────────────────────────────────┐
│ MCP Server (另一个进程)                  │
│  - @McpTool getEmployee 执行             │
│  - 返回 Employee JSON                    │
└─────┬───────────────────────────────────┘
      │ 原路返回
      ▼
   LLM 拿到结果 → 组织自然语言 → 返回给用户
```

**关键认知**：
- LLM **不知道** MCP 协议存在。它只看到"工具列表"（每个工具都有 name + description + JSON Schema），决定调用哪个、传什么参数。
- "工具是进程内的还是 MCP 的"对 LLM 透明。Spring AI 在 `ToolCallingManager` 里路由。
- 真正跨进程调用的代码在 `SyncMcpToolCallback.call()` 里。你**不需要自己写**——`spring-ai-starter-mcp-client` 全自动。

---

## 8. 验证工具被调到的几种办法

新手最大的困惑是"我不知道工具到底被调用了没有"。三种验证手段：

### 8.1 启动日志

§4.6 的代码会在启动时打印所有 MCP 工具名。如果列表是空的，说明 Client 没连上 Server，先检查：
- Server 是否在跑（`curl http://localhost:8081/mcp`）
- yaml 里 `url` 是否正确
- 是否开了 `spring.ai.mcp.client.enabled=true`

### 8.2 调用日志

开启 MCP 包的 debug 日志：

```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    io.modelcontextprotocol: DEBUG
```

你会看到：

```
DEBUG o.s.ai.mcp.client.McpClient - Initializing sync client for time-server
DEBUG i.m.spec.McpClientSchema - Sending: tools/call {name=currentTime, args={}}
DEBUG i.m.spec.McpClientSchema - Received: {result: "Mon Jul 18 ..."}
```

### 8.3 在 Server 端打断点

最直观的办法：在 Server 的 `@McpTool` 方法第一行打断点。Client 发起调用时 JVM 会停下来——证明请求确实跨进程过来了。

---

## 9. 多租户上下文：ToolContext → McpMeta 透传链路

**生产场景**：一个 Server 给多个租户用。Client 调用工具时要把"当前是哪个租户、哪个用户"告诉 Server。MCP 协议里这个上下文叫 **McpMeta**。

链路图：

```
Client 侧（你的 Spring AI App）              Server 侧
─────────────────────────────────             ─────────
chatClient.prompt()
  .user("查我的订单")
  .toolContext(Map.of(                       Map<String, Object> McpMeta
    "userId", "u001",              ─────►      { userId: "u001",
    "tenantId", "acme" }))                       tenantId: "acme" }
  .call();
                                              @McpTool
                                              public List<Order> myOrders(
                                                  McpMeta meta) {
                                                  String uid = (String) meta.get("userId");
                                                  // 用 uid 查询...
                                              }
```

**关键点**：
- Client 侧用 `toolContext()` 传 Map
- 中间由 Spring AI 的 `ToolContextToMcpMetaConverter` 自动转换（默认一对一映射）
- Server 侧方法签名加 `McpMeta meta` 参数，框架自动注入

`McpMeta` 是 Map 类型，LLM **看不到**它的内容——只是给工具方法用的元信息。这就是多租户 MCP Server 的基础。

> Server 端怎么写、`McpMeta` 之外还有哪些特殊参数（`McpSyncRequestContext`、`McpTransportContext` 等），见 [06 篇 §5.1](./06-MCP-Server开发实战.md)。

---

## 10. Client 侧扩展点速查

需要更精细控制时用这些接口（按需选学）：

| 接口 / 注解 | 作用 |
|------------|------|
| `McpSyncClientCustomizer` / `McpAsyncClientCustomizer` | 创建 client 时做定制（加 HTTP header、改 clientInfo） |
| `McpToolFilter` | 过滤 / 改名从 Server 拿到的工具 |
| `McpToolNamePrefixGenerator` | 给来自不同 Server 的工具加前缀（防命名冲突） |
| `ToolContextToMcpMetaConverter` | 把 ChatClient 调用时的 `ToolContext` 转成 MCP 的 `McpMeta`（§9） |
| `TransportContextExtractor` | 从 HTTP 请求中抽取鉴权/租户信息塞进 `McpTransportContext` |

Client 端还可在 Bean 方法上加这些注解订阅 Server 推送的事件（一般用不到，知道有就行）：

- `@McpLogging`：接收 Server 日志
- `@McpSampling`：Server 反向请求 LLM 采样
- `@McpElicitation`：Server 反向请求用户输入
- `@McpProgress`：进度通知
- `@McpToolListChanged` / `@McpResourceListChanged` / `@McpPromptListChanged`：列表变更通知

---

## 11. Client 端常见报错

| 报错 | 原因 | 解决 |
|------|------|------|
| `Connection refused: localhost/127.0.0.1:8081` | Server 没起 | 先起 Server（见 [06 篇 §2](./06-MCP-Server开发实战.md)） |
| `MCP server did not respond to initialize within timeout` | Server 鉴权失败 / 网络断 | `curl` 验证 Server 是否可达 |
| 启动后工具列表为空 | `enabled: false` / yaml 缩进错 | 检查 yaml + 看 debug 日志 |
| LLM 没调用工具 | 工具 description 写得不清楚 | 把 description 写得更像"什么时候该用它" |
| `McpErrorException: -32000` | Server 内部异常 | 看 Server 日志（[06 篇 §11](./06-MCP-Server开发实战.md)） |
| 调用一次后挂起 | `request-timeout` 太短 / Server 长任务没回 | 改 `request-timeout: 60s` |
| 中文工具名报错 | LLM 对中文工具名支持差 | 工具名用英文，description 中文 |

更多报错速查见 [07 篇附录 C](./07-MCP-Server高阶与生态.md)。

---

## 12. 实战任务

1. **跑通 §4**：5 分钟 Client demo 跑起来，看到工具被调用的日志。
2. **加第二个工具**：让 06 篇的 Server 多暴露一个 `add(a, b)` 工具，在 Client 端验证 LLM 能算 `12+34`。
3. **多 Server**：起两个 Server（不同端口），Client 同时连，验证两个工具都被注册。
4. **多租户**：在 Client 调用 `prompt().toolContext(Map.of("userId", "u001"))`，在 Server 打印 `McpMeta` 验证透传成功。
5. **切传输**：把 §4 的 Streamable HTTP 改成 stdio（§5.1），看 Client 启动时是否 fork 了 Server 子进程。
6. **加前缀**：起两个 Server 各有一个 `search` 工具，用 `DefaultMcpToolNamePrefixGenerator` 区分，验证 LLM 能调到正确的。
7. **进阶**：写一个 `McpToolFilter`，把名字以 `internal_` 开头的工具隐藏掉。

---

## 13. 理解检查

1. MCP 解决了进程内 `@Tool` 的什么局限？请举 2 个具体场景。
2. 协议三类能力（Tools / Resources / Prompts）分别是什么？作为 Client 端开发者，你最关心哪一类？为什么？
3. stdio / SSE / Streamable HTTP 各自适用什么场景？为什么 SSE 在 2.0 被弃用？
4. 写出"ChatClient 调用 MCP 工具"的完整时序（至少 5 步）。
5. 为什么注入的是 `ToolCallbackProvider[]` 而不是 `List<McpToolCallbackProvider>`？
6. `McpMeta` 是什么？它和 LLM 是什么关系？怎么把 ChatClient 的 `ToolContext` 传给 Server？
7. 启动日志里 MCP 工具列表是空的，怎么排查？
8. 两个 Server 都有 `search` 工具，怎么让 LLM 不调错？

---

## 14. 下一步

读到这里你已经能"用"MCP。下一步：
- **自己造一个 Server**：[06-MCP-Server开发实战.md](./06-MCP-Server开发实战.md)
- **把 05 和 06 拼成完整链路**：[07-MCP-Server高阶与生态.md](./07-MCP-Server高阶与生态.md)（强烈推荐，§1 就是端到端整合实战）

---

## 15. 相关文档

- [02-Tool与AgentLoop.md](./02-Tool与AgentLoop.md) —— @Tool 注解基础（进程内）
- [03-Advisor链全解.md](./03-Advisor链全解.md) —— ToolCallingAdvisor
- [06-MCP-Server开发实战.md](./06-MCP-Server开发实战.md) —— 自己写 Server
- [07-MCP-Server高阶与生态.md](./07-MCP-Server高阶与生态.md) —— 端到端整合 + 生态
- [14-安全工程与红队.md](./14-安全工程与红队.md) —— MCP 安全深入
- [MCP 协议规范](https://modelcontextprotocol.io/)
- [Spring AI MCP Client Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot.html)

---

回到 [00-目录索引.md](./00-目录索引.md)。
