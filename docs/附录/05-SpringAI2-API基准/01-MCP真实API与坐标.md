# 附录 05-01：MCP 真实 API 与依赖坐标基准

> **定位**：本文是对 [教程 02-SpringAI核心机制/01-MCP协议 §API] 的深入展开，也是全体系的 **MCP API 真实性基准**：真实 starter 坐标、真实客户端类型（`McpSyncClient` 而非虚构的 `org.springframework.ai.mcp.McpClient`）、`@Tool` 暴露为 MCP 工具的正确姿势、Streamable HTTP 传输。前置阅读：[教程 02-SpringAI核心机制/01-MCP协议]。

---

## 1. 真实依赖坐标（对照表）

| 用途 | 真实坐标（Spring AI 2.0.0 生态，javap/本地仓库实证） | 审计发现的错误坐标 |
|------|--------------------------------|-------------------|
| MCP 客户端（同步+HTTP） | `org.springframework.ai:spring-ai-starter-mcp-client`（其 POM 传递 `spring-ai-autoconfigure-mcp-client-httpclient` + `spring-ai-mcp` + `spring-ai-mcp-annotations`） | ❌ `spring-ai-mcp-client-spring-boot-starter` |
| MCP 客户端（WebFlux 传输） | `spring-ai-starter-mcp-client` + `org.springframework.ai:mcp-spring-webflux`（提供 `WebClientStreamableHttpTransport`/`WebFluxSseClientTransport`）——**不存在 `spring-ai-starter-mcp-client-webflux`** | ❌ `spring-ai-starter-mcp-client-webflux` |
| MCP 服务端（同步 Servlet） | `org.springframework.ai:spring-ai-starter-mcp-server` | ❌ `spring-ai-mcp-server-spring-boot-starter` |
| MCP 服务端（WebFlux） | `org.springframework.ai:spring-ai-starter-mcp-server-webflux` | 同上变体 |
| MCP 底层 SDK | `io.modelcontextprotocol.sdk:mcp`（2.0.0，Spring AI 传递引入） | - |

> 使用规则不变：均需在 pom.xml 中添加依赖；版本随 Spring AI BOM 2.0.0 管理。

## 2. 客户端：真实类型与注入方式

### 2.1 自动配置注入的是什么

```java
// Spring AI 2.0.0 —— 自动配置提供的是 MCP SDK 的客户端与聚合 Provider
// McpSyncClient 来自 MCP SDK 2.0.0：io.modelcontextprotocol.client.McpSyncClient
//（javap 实证 mcp-core-2.0.0.jar；不是 org.springframework.ai.mcp.McpClient，也不是 .sdk.mcp 包）
private final List<McpSyncClient> mcpClients;          // 多 Server 时注入 List

// 或直接注入框架组装好的工具 Provider（推荐，省去手工转换）
private final ToolCallbackProvider mcpToolProvider;    // 自动发现全部 MCP 工具
```

### 2.2 真实调用签名

```java
// MCP SDK 真实 API（对比虚构形态）
ListToolsResult tools = mcpSyncClient.listTools();     // ❌虚构: mcpClient.listTools() 返回 List<Tool>
CallToolResult result = mcpSyncClient.callTool(
        new CallToolRequest("order.query", Map.of("orderId", "SO-123")));  // ❌虚构: callTool(String, Map) 裸签名
```

### 2.3 聚合多 Server 的正确方式

```java
// 多个 MCP Server → 一个 ToolCallbackProvider
@Bean
public ToolCallbackProvider tools(List<McpSyncClient> clients) {
    return new SyncMcpToolCallbackProvider(clients);   // 真实构造: 接受 List
    // ❌虚构形态: new SyncMcpToolCallbackProvider(mcpClient) 单参
    // ❌虚构形态: 注入 Map<String, McpClient>（不存在的注册机制）
}

// ChatClient 侧使用（真实方法名）:
chatClient.prompt().toolCallbacks(mcpToolProvider)      // ❌虚构: defaultTools(toolProvider) 混写
// （.tools(...) 用于 @Tool 对象；ToolCallbackProvider 有专门入口，写法以所引版本为准）
```

## 3. 服务端：@Tool 暴露为 MCP 工具

> **审计教训**：`@Component + @Tool` **不会**自动注册到内建 MCP Server——必须显式声明 `ToolCallbackProvider` Bean。

```java
// Spring AI 2.0.0 —— 正确姿势（缺 Provider 不生效）
@Component
public class OrderTools {
    @Tool(description = "按订单号查询订单状态")
    public OrderStatus queryOrder(@ToolParam(description = "订单号") String orderId) {
        // 真实方法体:按订单号查库并返回(此处给最小可编译实现)
        return orderRepository.findByOrderId(orderId);
    }
}

@Configuration
public class McpServerConfig {
    @Bean
    public ToolCallbackProvider orderToolProvider(OrderTools tools, /* 其他工具对象 */) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools /*, moreTools */)
                .build();
    }
}
```

## 4. 传输协议：Streamable HTTP 是现行标准

| 传输 | 规范状态 | 说明 |
|------|---------|------|
| stdio | 稳定 | 本地子进程场景 |
| HTTP+SSE（旧） | **2025-03 规范后废弃** | 旧稿大量示例停留在它——需更新 |
| Streamable HTTP | **现行标准**（2025-03+） | 单端点 POST+mixin 流式响应，取代 HTTP+SSE |

```yaml
# 客户端连接配置（javap 实证 McpStreamableHttpClientProperties）
# 真实键结构：spring.ai.mcp.client.streamable-http.connections.<name>.{url, endpoint}
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            server1:
              url: https://tools.internal/mcp
        # ❌ 错误写法: spring.ai.mcp.client.streamable-http-connections（把前缀与字段名连写）
        # ❌ 虚构: spring.ai.mcp.client.servers 扁平列表
```

**旧稿必修点**（教程 01-WebFlux与响应式编程/00-WebFlux从零入门 / 前沿 04 / 项目 03）：传输层对照表补 Streamable HTTP 行；`HttpSseClientTransport` 的近似拼写更正为现行 SDK 类名（以所引版本 SDK 为准，建议统一走 starter 自动配置少手写传输类）。

## 5. MCP 高级能力的真实形态（简表）

| 能力 | 真实机制 | 本体系落点 |
|------|---------|-----------|
| OAuth 2.1 授权 | 规范定义的授权流（2025-04+ 修订）；Java 侧由 HTTP 客户端层集成 | [项目 08 v3] |
| Sampling | 服务端发起的补全请求，客户端授权代理 | [教程 01-WebFlux与响应式编程/00-WebFlux从零入门 §Sampling]、[项目 08 v3 管控] |
| Elicitation | 服务端向用户请求输入的表单机制 | [项目 08 v3 管控] |
| Roots | 客户端暴露文件系统根给服务端 | [教程 01-WebFlux与响应式编程/00-WebFlux从零入门 §Roots] |

## 6. 全局替换规则

| 从（虚构/过时） | 到（基准） |
|----------------|-----------|
| `org.springframework.ai.mcp.McpClient` / `.sdk.mcp.McpSyncClient` | `io.modelcontextprotocol.client.McpSyncClient`（MCP SDK 2.0.0，注入 List） |
| `callTool(name, argsMap)` | `callTool(new CallToolRequest(name, argsMap))` |
| `mcpClient.listTools()` 直返工具列表 | 返回 `ListToolsResult` 解包 |
| `spring-ai-mcp-*-spring-boot-starter` | `spring-ai-starter-mcp-*` |
| `defaultTools(toolProvider)` | `toolCallbacks(provider)` / 版本对应入口 |
| HTTP+SSE 传输表 | Streamable HTTP 为现行标准 |
| `@Tool` "自动注册" | 显式 `MethodToolCallbackProvider` Bean |

## 7. 总结

| 概念 | 一句话 |
|------|--------|
| 客户端类型 | MCP SDK 的 `McpSyncClient`，注入 List，聚合用 `SyncMcpToolCallbackProvider(List)` |
| 服务端暴露 | `@Tool` + 显式 `MethodToolCallbackProvider`，缺一不生效 |
| 坐标 | `spring-ai-starter-mcp-*` 家族 |
| 传输 | Streamable HTTP 是现行标准，HTTP+SSE 已废弃 |
| 存疑写法 | 以 starter 自动配置优先，手写传输类需对照 SDK 版本 |
