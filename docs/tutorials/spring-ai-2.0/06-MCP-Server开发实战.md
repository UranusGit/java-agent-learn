# 06 MCP Server 开发实战

> 这是 MCP 三部曲的中篇。
>
> 读完这一篇你能做到：**自己从零写一个 MCP Server**，暴露 Tools / Resources / Prompts / Completions 四类能力，并加上鉴权、限流、可观测性。
>
> 三部曲路线：
> - [05 = 入门 + Client 通关](./05-MCP协议全解.md)：先用起来
> - **06（本文）= Server 开发实战**：自己造一个 Server
> - [07 = 端到端整合 + 生态进阶](./07-MCP-Server高阶与生态.md)：把 05 的 Client 和本文的 Server 拼起来跑通完整链路
>
> 前置：[05-MCP协议全解.md](./05-MCP协议全解.md) + [02-Tool与AgentLoop.md](./02-Tool与AgentLoop.md)
>
> **本文只讲 Server 端**。Server 写好后怎么被 Client 调用、怎么和 ChatClient 整合、跨进程链路怎么调试 → 见 [07 篇 §1](./07-MCP-Server高阶与生态.md)。
>
> 预计：2-3 天

---

## 0. 这篇怎么读

如果你完全没接触过 MCP，**先读 [05 篇](./05-MCP协议全解.md)** 当一次 Client 用户，再回来造 Server。否则你会不知道自己写的 Server 谁来用。

```
L1 入门：跑起来
  ├── 三种实现风格（注解 / Provider / 原生）
  ├── Hello World（5 分钟）
  ├── WebMVC vs WebFlux 传输
  ├── 用 05 的 Client 验证（§2.6）
  └── Inspector 调试
        ↓
L2 进阶：API 表面全扫
  ├── 四类能力（Tools / Resources / Prompts / Completions）
  ├── 服务端通知（Progress / Logging）
  └── Capability 协商
        ↓
L3 生产化：能上线
  ├── 鉴权与多租户
  ├── 限流 / 熔断 / 配额
  ├── 可观测性（OTel GenAI）
  └── 错误处理与失败传播
        ↓
进 07 篇：端到端整合 + 架构与生态
```

**心法**：写一个能跑的 MCP Server 半小时；写一个**生产级**的 MCP Server 是另一个量级的事——本文要把这中间的鸿沟填平。

---

# L1 入门篇

## 1. 三种实现风格

Spring AI 2.0 提供三种写 MCP Server 的方式，**风格选择决定了项目长期维护成本**。

### 1.1 风格对比

| 风格 | 代码量 | 灵活性 | 适合 |
|------|-------|--------|------|
| **A. 注解驱动**（`@McpTool` / `@McpToolParam` / `@McpResource` / `@McpPrompt`） | 最少 | 中 | 95% 场景，工具集相对固定 |
| **B. 显式 Provider**（`ToolCallbackProvider` / `ToolCallback`） | 中 | 高 | 需要动态构造工具、按上下文决定暴露什么（注解扫不到的场景） |
| **C. 原生 SDK**（`McpServerFeatures.SyncToolSpecification`） | 最多 | 最高 | 协议级控制（自定义 capability、消息拦截） |

**强烈建议**：从 A 开始。开启 `spring.ai.mcp.server.annotation-scanner.enabled=true` 后，Bean 上的 `@McpTool`/`@McpResource`/`@McpPrompt`/`@McpComplete` 会被自动扫描注册，**不需要手写任何 `ToolCallbackProvider` Bean**。遇到 A 表达不了的再用 B，C 仅用于框架级开发。

### 1.2 同一个工具的三种写法对比

工具：根据工号查员工。

#### 风格 A：注解

```java
// 本代码仅作学习材料参考
// 注意：这里用的是 org.springframework.ai.mcp.annotation.McpTool，
// 不是 org.springframework.ai.tool.annotation.Tool（后者是给 ChatClient 进程内 Tool 用的）。
@Component
public class HrTools {
    private final HrService svc;

    @McpTool(description = "根据工号查员工")
    public Employee getEmployee(@McpToolParam(description = "工号") String id) {
        return svc.findById(id);
    }
}

// 不需要写任何 ToolCallbackProvider / MethodToolCallbackProvider Bean！
// spring.ai.mcp.server.annotation-scanner.enabled=true 开启后，
// @McpTool / @McpResource / @McpPrompt / @McpComplete 会被框架自动扫描注册。
```

#### 风格 B：显式 Provider

注解扫描覆盖不到的动态场景（运行时构造工具、按上下文决定暴露什么），用 `MethodToolCallbackProvider` 显式组装 `@Tool` 方法（注意这里**才**用 `@Tool`，因为底层还是 Spring AI 的 ToolCallback 体系）：

```java
// 本代码仅作学习材料参考
@Configuration
class McpConfig {
    @Bean
    ToolCallbackProvider hrTools(HrService svc) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new Object() {
                    @Tool(description = "根据工号查员工")
                    public Employee get(@ToolParam(description = "工号") String id) {
                        return svc.findById(id);
                    }
                })
                .build();
    }
}
```

或更底层——直接 `ToolCallback` 接口（动态 schema）：

```java
// 本代码仅作学习材料参考
// 2.0 没有内置 DynamicToolCallback，要实现"动态 schema 的 ToolCallback"，
// 自己实现 ToolCallback 接口（4 个方法），或者用 FunctionToolCallback。
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;

@Bean
ToolCallback getEmployeeTool(HrService svc) {
    return FunctionToolCallback.builder("get_employee", (String id) -> svc.findById(id).toString())
            .description("根据工号查员工")
            .inputType(String.class)
            .build();
}
```

#### 风格 C：原生 SDK

```java
// 本代码仅作学习材料参考
// 包路径来自 MCP Java SDK（io.modelcontextprotocol.*），2.0 没有把它内联到 Spring AI 自己的包。
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;

@Bean
List<SyncToolSpecification> hrTools(HrService svc) {
    var tool = McpSchema.Tool.builder(
            "get_employee",
            Map.of("type", "object",
                   "properties", Map.of("id", Map.of("type", "string")),
                   "required", java.util.List.of("id")))
        .description("根据工号查员工")
        .build();
    // 2.0 没有 SyncToolRequestHandler 接口，handler 直接是
    // BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
    return List.of(new SyncToolSpecification(tool, (exchange, req) -> {
        String id = (String) req.arguments().get("id");
        Employee e = svc.findById(id);
        return new CallToolResult(java.util.List.of(new TextContent(e.toString())), false);
    }));
}
```

> 风格 C 是 MCP Java SDK 原生 API。Spring AI 推荐用 A/B，C 留给"自己写 MCP 框架"或"协议级消息拦截"。

---

## 2. Hello World：5 分钟跑起来

> 这个简化版 Server 也是 [05 篇](./05-MCP协议全解.md) §4 "5 分钟第一个 MCP Client" 的对接目标。如果你按 05→06 顺序学，这里写完就能回到 05 验证。

### 2.1 项目结构

```
time-mcp-server/
├── pom.xml
└── src/main/java/org/demo02/mcp/time/
    ├── TimeMcpApplication.java
    └── TimeTools.java
```

完整企业级示例（geo-mcp-server，含鉴权 / 限流 / 可观测）见 §12。

### 2.2 pom.xml

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
    <artifactId>time-mcp-server</artifactId>
    <version>0.1.0</version>

    <properties>
        <spring-ai.version>2.0.0</spring-ai.version>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- MCP Server（WebMVC 传输：支持 Streamable HTTP + SSE） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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

> ⚠️ 2.0 starter 命名规范：`spring-ai-starter-mcp-server-*`（不带 `spring-boot-starter` 后缀）。

### 2.3 application.yaml

```yaml
server:
  port: 8081

spring:
  application:
    name: time-mcp-server
  ai:
    mcp:
      server:
        name: time-mcp-server
        version: 0.1.0
        # 注意两个配置项的区别：
        #  - type 选 Server API 类型（SYNC / ASYNC），决定注入 McpSyncServerExchange 还是 McpAsyncServerExchange。
        #    它不选传输方式，没有 WEBMVC / WEBFLUX 这种取值。
        #  - protocol 选传输（STREAMABLE / SSE / STATELESS）；2.0 推荐 STREAMABLE，SSE 自 2.0.0 起标记 @Deprecated。
        type: SYNC
        protocol: STREAMABLE
        annotation-scanner:
          enabled: true       # 默认开启，扫描 @McpTool/@McpResource/@McpPrompt/@McpComplete
        keep-alive-interval: 30s   # Streamable HTTP 心跳，避免代理超时
```

### 2.4 主程序

```java
// 本代码仅作学习材料参考
package org.demo02.mcp.time;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TimeMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(TimeMcpApplication.class, args);
    }
}
```

### 2.5 第一个工具

```java
// 本代码仅作学习材料参考
package org.demo02.mcp.time;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTools {

    @McpTool(description = "获取服务器当前时间，格式 yyyy-MM-dd HH:mm:ss")
    public String currentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @McpTool(description = "把时间字符串转换成另一种格式")
    public String formatTime(
            @McpToolParam(description = "原始时间，如 2026-07-18 14:00:00") String input,
            @McpToolParam(description = "目标格式，如 yyyy/MM/dd") String pattern
    ) {
        LocalDateTime t = LocalDateTime.parse(input, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return t.format(DateTimeFormatter.ofPattern(pattern));
    }
}
```

> ⚠️ 2.0 不需要为 MCP Server 工具写任何 `ToolCallbackProvider` / `MethodToolCallbackProvider` Bean。
> `spring.ai.mcp.server.annotation-scanner.enabled=true` 开启后，`@McpTool` 等注解会被自动扫描并注册到 MCP Server。

### 2.6 启动 + 三种验证

#### 验证 A：用 Inspector 看工具列表

```bash
mvn spring-boot:run

# 另一个终端
npx @modelcontextprotocol/inspector http://localhost:8081
```

打开 Inspector UI → Tools → 应该看到 `currentTime` 和 `formatTime`。

#### 验证 B：用 curl 探活

```bash
# MCP 协议是 JSON-RPC，单次 curl 不够完整，但能确认 Server 在跑
curl -i http://localhost:8081/mcp
# 期望：HTTP 200 或 400（带 method not allowed 之类），不是 Connection refused
```

#### 验证 C（推荐）：用 05 篇的 Client 连过来

回到 [05 篇 §4](./05-MCP协议全解.md)，按那篇的 yaml 配置 Client 连 `http://localhost:8081/mcp`。如果你在 05 已经写过 Client，**这一步直接 reuse，把 `url` 改成本 Server 的地址即可**。

期望日志：

```
已注册 MCP 工具：
  - currentTime: 获取服务器当前时间，格式 yyyy-MM-dd HH:mm:ss
  - formatTime: 把时间字符串转换成另一种格式
```

然后用 `chatClient.prompt().user("现在几点").call()` 调到 `currentTime` 工具。

**端到端整合的完整链路（多租户、跨进程 trace、调试技巧）见 [07 篇 §1](./07-MCP-Server高阶与生态.md)。**

---

## 3. 传输选择：WebMVC vs WebFlux

### 3.1 三种 starter

| starter | 栈 | 适合 |
|---------|----|----|
| `spring-ai-starter-mcp-server` | 无 web（stdio） | Claude Desktop 本地子进程 |
| `spring-ai-starter-mcp-server-webmvc` | Servlet | 现有 Spring Boot Web 项目 |
| `spring-ai-starter-mcp-server-webflux` | Reactive | 高并发流式、与 WebFlux 生态一致 |

### 3.2 选择决策

```
你的项目已经是 Spring MVC？
  → webmvc starter
你的项目已经是 WebFlux / 需要非阻塞？
  → webflux starter
只在 Claude Desktop 用？
  → 纯 server starter（stdio）
```

### 3.3 WebFlux 版本关键差异

把 starter 从 `spring-ai-starter-mcp-server-webmvc` 换成 `spring-ai-starter-mcp-server-webflux` 即可，**yaml 里的 `protocol: STREAMABLE` 保持不变**。`type` 仍然按"Server API 类型"选（`SYNC` 或 `ASYNC`），**不是用来切 web 栈的**——web 栈由 starter 决定。

```xml
<!-- 改这一行 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mcp:
      server:
        type: SYNC           # API 类型：仍可保留 SYNC；想完全异步可改 ASYNC
        protocol: STREAMABLE # 传输方式：不变
```

业务代码（`@McpTool` / `@McpResource` 方法）**完全不变**。SYNC server 只注册非响应式返回类型，ASYNC server 只注册 `Mono` / `Flux` 返回类型——两者会自动过滤，不会互相干扰。

---

## 4. Inspector 调试

### 4.1 启动 Inspector

```bash
# 远程 MCP Server
npx @modelcontextprotocol/inspector http://localhost:8081

# stdio MCP Server
npx @modelcontextprotocol/inspector -- java -jar target/time-mcp-server.jar
```

### 4.2 Inspector 能做什么

- 列出 Tools / Resources / Prompts
- 手动调一次工具，看返回
- 查看 schema 是否正确
- 看 server 日志
- 测试 Prompts 模板
- 测 Resources（URI 模板）

### 4.3 调试技巧

- **看不到工具**：检查 `spring.ai.mcp.server.annotation-scanner.enabled=true` 是否开启；带 `@McpTool` 的类是否被 Spring 扫描到（`@Component` / `@Configuration` 等）；SYNC server 只暴露非响应式返回的方法、ASYNC server 只暴露 `Mono`/`Flux` 返回的方法，确认签名匹配。
- **schema 错误**：Inspector 会高亮 JSON Schema 不合法的字段。
- **鉴权失败**：Inspector 在 headers 里加 token；如果走 OAuth 走 callback。

---

# L2 进阶篇

## 5. 四类能力深度

MCP Server 可以暴露四类能力，**很多团队只用 Tools**，浪费了协议的价值。

### 5.1 Tools：可执行的函数

最常用。前面已展示。重点补充：

#### MCP Server 端没有 `returnDirect`

> ⚠️ **重要**：`@McpTool` 在 2.0.0 **没有** `returnDirect` 属性。`returnDirect` 是 ChatClient 进程内 `@Tool` 的属性（见 `org.springframework.ai.tool.annotation.Tool` / `ToolMetadata#returnDirect`），MCP 协议层不暴露这种语义。
>
> 如果想让 MCP Server 工具结果"直接返给 Client、不再喂回 LLM 加工"，需要在 **MCP Client（ChatClient）侧** 控制——拿到工具返回值后决定是否再调 LLM。MCP Server 端只负责"执行 + 返回"，决定权在 Client。

#### McpMeta / RequestContext 跨层穿透

> ⚠️ MCP Server 侧**没有** `ToolContext` 这个参数类型。2.0 通过 `McpMeta`、`McpSyncRequestContext` / `McpAsyncRequestContext`、`McpTransportContext`、`@McpProgressToken`、`McpSyncServerExchange` / `McpAsyncServerExchange` 等特殊参数把请求上下文透传给工具方法。`ToolContext` 是 **ChatClient 侧**的概念——MCP Client 在调用工具时通过 `ToolContextToMcpMetaConverter` 把它转成 `McpMeta` 再发给 Server。

```java
// 本代码仅作学习材料参考
@McpTool(description = "查我的订单")
public List<Order> myOrders(McpMeta meta) {
    // meta 由 MCP Client 端通过 ToolContextToMcpMetaConverter 从 ToolContext 转换而来
    String userId = (String) meta.get("userId");
    String tenant = (String) meta.get("tenantId");
    return orderService.findByUser(userId, tenant);
}
```

需要更全面的请求能力（progress / logging / ping / elicitation / sampling）时，用 `McpSyncRequestContext`（异步用 `McpAsyncRequestContext`）：

```java
// 本代码仅作学习材料参考
@McpTool(description = "查我的订单")
public List<Order> myOrders(McpSyncRequestContext ctx) {
    Map<String, Object> meta = ctx.meta();
    String userId = (String) meta.get("userId");
    String tenant = (String) meta.get("tenantId");
    ctx.info("querying orders for tenant=%s user=%s".formatted(tenant, userId));
    return orderService.findByUser(userId, tenant);
}
```

ChatClient 侧调用时把 `ToolContext` 传进去，框架通过 `ToolContextToMcpMetaConverter` 默认转换为 `McpMeta` 透传给 Server：

```java
// 本代码仅作学习材料参考
chatClient.prompt()
        .user(q)
        .toolContext(Map.of("userId", "u001", "tenantId", "acme"))
        .call();
```

`ToolContext` / `McpMeta` 都不算 LLM 的参数——LLM 看不到，但工具方法能用。**这是多租户 MCP Server 的核心**。

> 完整的"ChatClient → MCP Client → MCP Server"上下文透传链路见 [07 篇 §1.5](./07-MCP-Server高阶与生态.md)。

#### 复杂对象返回

```java
// 本代码仅作学习材料参考
public record Poi(
    String name,
    String address,
    double latitude,
    double longitude,
    double rating,
    List<String> categories
) {}

@McpTool(description = "搜索 POI")
public List<Poi> searchPoi(@McpToolParam(description = "关键词") String keyword) {
    return client.search(keyword);
}
```

Spring AI 自动用 Jackson 序列化。LLM 收到 JSON。

⚠️ 注意：

- **避免循环引用**（`Order.customer.orders` 互相引用）
- **大对象警惕 token**（List 1000 条 → 几万 token，LLM 上下文爆）
- **字段名语义化**（`temperature` 比 `t` 友好）

### 5.2 Resources：暴露只读数据

Resources 是"客户端主动 GET"的数据，**LLM 不参与决策**。

#### 基础用法

```java
// 本代码仅作学习材料参考
@Component
public class GeoResources {

    private final RegionService regionService;

    @McpResource(
        uri = "geo://regions",
        description = "获取全部省级行政区列表"
    )
    public String allRegions() {
        return regionService.allProvinces().stream()
                .map(r -> r.code() + " " + r.name())
                .collect(Collectors.joining("\n"));
    }

    @McpResource(
        uri = "geo://regions/{code}",
        description = "按行政区划代码查具体区域"
    )
    public String regionByCode(String code) {
        return regionService.findByCode(code).toString();
    }
}
```

> ⚠️ 2.0 **没有** `@McpUriVariable` 注解。URI 模板里的 `{code}` 按**方法参数名**匹配绑定，参数直接用同名 `String`（或 `Map<String, String>` 收所有变量）即可。

Client 调用：

```python
# Python MCP Client
region = await session.read_resource("geo://regions/110000")  # 北京
```

#### Resources vs Tools

| 特征 | Resource | Tool |
|------|---------|------|
| 读写 | 只读 | 可写 |
| 触发 | Client 主动读 | LLM 决定调 |
| 适合 | 配置、字典、文档 | 业务操作 |
| 大数据 | ✅ 可分页 | ❌ 受 LLM 上下文限 |

**典型场景**：

- 把"产品目录"、"团队列表"、"配置项"做成 Resource，让 Client 缓存
- 把"执行查询"、"提交订单"做成 Tool

### 5.3 Prompts：暴露预定义模板

Server 把"专家写的 prompt"暴露给 Client，Client 用户（如 Claude Desktop）一键调用。

```java
// 本代码仅作学习材料参考
@Component
public class GeoPrompts {

    @McpPrompt(name = "planRoute", description = "生成行程规划")
    public String planRoute(
            @McpArg(name = "from", description = "出发城市")                   String from,
            @McpArg(name = "to",   description = "目的城市")                   String to,
            @McpArg(name = "days", description = "天数", required = false)     Integer days
    ) {
        int d = days == null ? 3 : days;
        return """
                请帮我规划从 %s 到 %s 的 %d 天行程。
                使用 searchPoi 工具查景点，使用 planRoute 工具算路径。
                输出格式：每日行程 + 总预算。
                """.formatted(from, to, d);
    }
}
```

> ⚠️ 2.0 **没有** `@McpPromptArg` 注解。Prompt 参数描述通过 `@McpArg(name=..., description=..., required=...)` 注解直接写在方法参数上（与 `@McpToolParam` 风格一致）。`@McpPrompt` 本身只保留 `name`、`title`、`description`、`metaProvider` 四个属性。

Claude Desktop 用户在对话框上方会看到"生成行程规划"按钮。

### 5.4 Completions：自动补全（常被忽略）

Client 在用户输入参数值时，可以让 Server 提供建议。

```java
// 本代码仅作学习材料参考
@Component
public class GeoCompletions {

    // 二选一：补全资源 URI 模板变量 → 用 uri
    @McpComplete(uri = "geo://regions/{code}")
    public List<String> completeRegionCode(String partial) {
        return regionService.allProvinces().stream()
                .map(r -> r.code())
                .filter(c -> c.startsWith(partial))
                .limit(20)
                .toList();
    }

    // 或者：补全某个 prompt 的参数 → 用 prompt（与 uri 互斥，不要同时写）
    // @McpComplete(prompt = "planRoute")
    // public List<String> completeCity(String partial) { ... }
}
```

> ⚠️ `@McpComplete` 在 2.0 **没有** `property` 属性。补全目标用 `uri` 或 `prompt` 二选一；方法入参就是要补全的那段文本（URI 模板变量或 prompt 参数的当前输入值）。

效果：Claude Desktop 用户输入 `11` 时，自动建议 `110000`（北京）、`110100`（北京市市辖区）...

**这是 MCP 区别于普通 OpenAPI 的关键能力之一**，让 Client UI 更智能。

---

## 6. 服务端通知

### 6.1 Progress：长任务的进度

进度通知通过 `McpSyncRequestContext`（异步用 `McpAsyncRequestContext`）发送，也可以用 `@McpProgressToken` 直接拿到底层 token 自行通知。

```java
// 本代码仅作学习材料参考
@McpTool(description = "批量地理编码（地址 → 坐标）")
public List<Coordinate> batchGeocode(
        @McpToolParam(description = "地址列表") List<String> addresses,
        McpSyncRequestContext ctx
) {
    var results = new ArrayList<Coordinate>();
    for (int i = 0; i < addresses.size(); i++) {
        results.add(geocodeOne(addresses.get(i)));
        int done = i + 1;
        int total = addresses.size();
        // 三种等价写法任选其一：
        ctx.progress(p -> p.progress(done).total(total).message("处理中 " + done + "/" + total));
        // 或简单百分比：ctx.progress(done * 100 / total);
    }
    return results;
}
```

> ⚠️ 2.0 **没有** `ctx.getProgressNotifier()` 这种 API。`ToolContext` 是 ChatClient 侧的概念，MCP Server 侧的特殊参数只能是 `McpSyncRequestContext` / `McpAsyncRequestContext` / `McpMeta` / `McpTransportContext` / `@McpProgressToken` / `McpSyncServerExchange` / `McpAsyncServerExchange` / `CallToolRequest`。注意 `Stateless` 类型 server 会过滤掉 `McpSyncRequestContext` 等带会话语义的参数（无 session 概念）。

Client UI 会看到进度条。**适合**：批量操作、长查询、流式处理。

### 6.2 Logging：日志推给 Client

日志也通过 `McpSyncRequestContext` 发送（封装了 MCP 协议的 `notifications/message`）。

```java
// 本代码仅作学习材料参考
@McpTool(description = "查 POI")
public List<Poi> searchPoi(String keyword, McpSyncRequestContext ctx) {
    ctx.info("开始搜索: {}", keyword);
    try {
        var result = client.search(keyword);
        ctx.debug("找到 {} 条", result.size());
        return result;
    } catch (Exception e) {
        ctx.error("搜索失败: {}", e.getMessage());
        throw e;
    }
}
```

> ⚠️ 2.0 **没有** `ctx.getLogger()` 这种 API。`McpSyncRequestContext` / `McpAsyncRequestContext` 直接提供 `debug` / `info` / `notice` / `warning` / `error` 等方法（对应 MCP Logging 等级）。也可以 `ctx.logging(l -> l.level(...).logger("geo").data(...))` 做更细的控制。

Client 端（Claude Desktop / Inspector）能看到这些日志，**比 Server 端控制台日志方便得多**——调试跨进程 MCP 必备。

---

## 7. Capability 协商

MCP 在 handshake 阶段让 Server 告诉 Client "我支持什么"。

### 7.1 默认 capability

2.0 通过 yaml 控制各能力开关（默认按是否注册了对应的 Bean 自动开启，也可显式关掉）：

```yaml
spring:
  ai:
    mcp:
      server:
        capabilities:
          tool: true
          resource: true
          prompt: true
          completion: true
          logging: true
```

### 7.2 listChanged：让 Client 感知变更

如果你的工具集是动态的（运行时加 / 删工具），开启 `listChanged`：

```java
// 本代码仅作学习材料参考
@Autowired
private McpSyncServerExchange exchange;

public void addDynamicTool(ToolCallback tool) {
    toolRegistry.register(tool);
    exchange.notifyToolsListChanged();   // Client 会重新 listTools()
}
```

### 7.3 何时该开 listChanged

- 工具是按订阅动态注入（见 [07 篇](./07-MCP-Server高阶与生态.md) MCP Hub）
- 工具来自配置中心，运行时刷新
- 多租户按权限动态暴露

如果工具是编译期固定的，**不要开**——增加协议开销。

---

# L3 生产化篇

## 8. 鉴权与多租户

### 8.1 三层鉴权

```
1. 传输层：HTTPS + Bearer Token（最外层，确认 client 身份）
2. 工具层：ABAC / RBAC（每个工具内部决定是否允许）
3. 数据层：行级权限（同一个工具，不同租户看不同数据）
```

### 8.2 传输层：Bearer Token

```java
// 本代码仅作学习材料参考
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain mcpFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/mcp/**")
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .addFilterBefore(new McpTokenFilter(), UsernamePasswordAuthenticationFilter.class)
            .csrf(c -> c.disable())   // MCP 协议不用 CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS));
        return http.build();
    }
}

class McpTokenFilter extends OncePerRequestFilter {
    private final TokenStore tokens;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws IOException, ServletException {
        String token = req.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            resp.sendError(401, "Missing token");
            return;
        }
        TenantIdentity id = tokens.verify(token.substring(7));
        if (id == null) {
            resp.sendError(401, "Invalid token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
            new McpAuthentication(id));
        chain.doFilter(req, resp);
    }
}
```

### 8.3 工具层：从 SecurityContext 取身份

```java
// 本代码仅作学习材料参考
@Component
public class OrderTools {

    @McpTool(description = "查我的订单")
    public List<Order> myOrders(McpMeta meta) {
        TenantIdentity id = McpSecurity.currentTenant();   // 从 SecurityContext 拿
        String userId = (String) meta.getOrDefault("userId", id.userId());

        if (!id.canRead("orders", userId)) {
            throw new McpForbiddenException("No permission to read orders of " + userId);
        }
        return orderService.findByUser(userId, id.tenantId());
    }
}
```

### 8.4 ABAC（属性级权限）

更细的颗粒度：

```java
// 本代码仅作学习材料参考
@McpTool(description = "提交退款")
public String submitRefund(
        @McpToolParam(description = "订单号") String orderId,
        @McpToolParam(description = "金额") double amount,
        McpMeta meta
) {
    TenantIdentity id = McpSecurity.currentTenant();

    // 属性检查
    if (amount > 10000 && !id.hasRole("refund_large")) {
        throw new McpForbiddenException("超过单次退款上限");
    }
    Order order = orderService.get(orderId);
    if (!order.tenantId().equals(id.tenantId())) {
        throw new McpForbiddenException("跨租户访问");
    }
    if (order.userId().equals(id.userId()) && !id.hasRole("refund_self")) {
        throw new McpForbiddenException("不能给自己退款");
    }
    return orderService.refund(orderId, amount);
}
```

### 8.5 OAuth 2.1（推荐生产）

MCP 协议官方推荐 OAuth 2.1。Spring AI 2.0 + Spring Security OAuth2 Resource Server 一行配置：

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.internal/realms/mcp
```

Client 在第一次握手时走 OAuth code flow，拿到 access_token 后所有请求带 Bearer。

---

## 9. 限流 / 熔断 / 配额

### 9.1 三层防护

```
1. 单 client 并发上限（防一个 Client 打爆 Server）
2. per-tenant rate limit（每分钟 QPS）
3. per-tenant 配额（每天总量）
```

### 9.2 Resilience4j Bulkhead

```java
// 本代码仅作学习材料参考
@Configuration
public class BulkheadConfig {
    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(50)
                .maxWaitDuration(Duration.ofMillis(100))
                .build());
    }
}

@Component
public class RateLimitedGeoTools {

    private final BulkheadRegistry bh;
    private final BaiduMapClient client;

    @McpTool(description = "搜索 POI")
    public List<Poi> searchPoi(@McpToolParam(description = "关键词") String keyword, McpMeta meta) {
        TenantIdentity id = McpSecurity.currentTenant();
        Bulkhead perTenant = bh.bulkhead("geo-" + id.tenantId(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(id.tier().equals("pro") ? 20 : 5)
                        .build());
        return perTenant.executeSupplier(() -> client.search(keyword));
    }
}
```

### 9.3 RateLimiter

```java
// 本代码仅作学习材料参考
@Bean
public RateLimiterRegistry rateLimiterRegistry() {
    return RateLimiterRegistry.of(RateLimiterConfig.custom()
            .limitForPeriod(10)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofMillis(50))
            .build());
}

private final RateLimiterRegistry rl;

@McpTool(description = "搜索 POI")
public List<Poi> searchPoi(String keyword) {
    TenantIdentity id = McpSecurity.currentTenant();
    RateLimiter limiter = rl.rateLimiter("geo-" + id.tenantId());
    return RateLimiter.decorateSupplier(limiter, () -> client.search(keyword)).get();
}
```

### 9.4 配额表

```sql
CREATE TABLE mcp_quota (
    tenant_id     VARCHAR(64),
    mcp_server_id VARCHAR(64),
    daily_limit   INT,
    monthly_limit INT,
    used_today    INT,
    used_this_month INT,
    reset_at      TIMESTAMP,
    PRIMARY KEY (tenant_id, mcp_server_id)
);
```

每次 tool call 前后更新 `used_today`。超额抛 `RequestNotPermitted`。

详见 [18-大规模Agent平台与数据基础设施.md](./18-大规模Agent平台与数据基础设施.md) §MCP Hub 计费。

---

## 10. 可观测性

### 10.1 OTel GenAI 标准 span

OpenTelemetry 2024 推出 GenAI Semantic Conventions，MCP 调用应该埋以下 attribute：

```
gen_ai.system: "mcp"
gen_ai.server.name: "geo-mcp-server"
gen_ai.tool.name: "searchPoi"
gen_ai.tool.call.id: "uuid"
gen_ai.tool.call.input: {...}      # 脱敏后
gen_ai.tool.call.output: {...}
gen_ai.usage.input_tokens: 0       # MCP tool 不直接用 LLM token
gen_ai.usage.output_tokens: 0
mcp.tenant.id: "acme"
mcp.client.id: "agent-cs-001"
```

Spring AI 2.0 自动埋点（Micrometer Observation）。自定义业务字段：

```java
// 本代码仅作学习材料参考
@Observed(name = "mcp.tool.searchPoi",
          contextualName = "searching POI",
          lowCardinalityKeyValue = "tool", "searchPoi")
@McpTool(description = "搜索 POI")
public List<Poi> searchPoi(String keyword) {
    Observation obs = Observation.start("mcp.tool.searchPoi", ObservationRegistry.create());
    return obs.observe(() -> {
        obs.lowCardinalityKeyValue("tool", "searchPoi");
        obs.highCardinalityKeyValue("tenant", McpSecurity.currentTenant().tenantId());
        obs.highCardinalityKeyValue("keyword_len", keyword.length());
        try {
            var r = client.search(keyword);
            obs.highCardinalityKeyValue("result_count", r.size());
            return r;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        }
    });
}
```

### 10.2 Prometheus 指标

```java
// 本代码仅作学习材料参考
@Component
@RequiredArgsConstructor
public class McpMetrics {
    private final MeterRegistry meters;

    public <T> T record(String tool, String tenant, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        try {
            T r = action.get();
            sample.stop(Timer.builder("mcp.tool.duration")
                    .tag("tool", tool).tag("tenant", tenant).tag("result", "success")
                    .register(meters));
            return r;
        } catch (Exception e) {
            sample.stop(Timer.builder("mcp.tool.duration")
                    .tag("tool", tool).tag("tenant", tenant).tag("result", "error")
                    .register(meters));
            throw e;
        }
    }
}
```

Grafana 关键看板：

- 每个 tool 的 QPS、p50/p95/p99
- 错误率
- per-tenant 调用量
- 在线 client 数

### 10.3 全链路 trace

```
[Span: agent.chat_client.call]
  └── [Span: mcp.client.callTool searchPoi]
        └── [Span: mcp.server.handleToolCall searchPoi]   ← 跨进程
              └── [Span: baidu_map.search]                ← 第三方 API
```

用 OTel + W3C Trace Context，跨进程透传 traceId。

---

## 11. 错误处理与失败传播

### 11.1 MCP 协议的错误格式

MCP 标准错误码：

| code | 含义 |
|------|------|
| -32700 | Parse error |
| -32600 | Invalid request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |

业务错误用 `-32000 ~ -32099`。

### 11.2 三种失败传播策略

| 策略 | 行为 | 适用 |
|------|------|------|
| **A. 抛异常** | Client 收到 error，停止 | 关键写操作（支付、删除） |
| **B. 返回 error result** | 把错误塞进 tool result，让 LLM 看到 | 大部分场景（参考 Claude Code） |
| **C. 静默重试** | Server 内部重试，对外透明 | 临时性故障（限流、超时） |

### 11.3 默认策略：返回 error result（让 LLM 看到）

```java
// 本代码仅作学习材料参考
@McpTool(description = "搜索 POI")
public Object searchPoi(String keyword) {
    try {
        return client.search(keyword);
    } catch (BaiduRateLimitException e) {
        // 把错误返给 LLM，让 LLM 自我修复（"等一下再试" / "换个关键词"）
        return ToolResult.error("百度地图限流，请稍后再试或换关键词");
    } catch (BaiduInvalidKeyException e) {
        // 这类错误 LLM 看到也救不了，抛出去
        throw new McpInternalException("Map provider unavailable", e);
    }
}
```

**为什么**：参考 Claude Code 设计——LLM 看到 stderr 会调整策略（[04-流式响应与Reactor深度.md](./04-流式响应与Reactor深度.md) 也讲过）。

### 11.4 全局异常处理器

```java
// 本代码仅作学习材料参考
@RestControllerAdvice
public class McpExceptionHandler {

    @ExceptionHandler(McpForbiddenException.class)
    public ResponseEntity<Object> forbidden(McpForbiddenException e) {
        return ResponseEntity.status(403).body(Map.of(
                "error", "forbidden",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Object> rateLimited(RequestNotPermitted e) {
        return ResponseEntity.status(429).body(Map.of(
                "error", "rate_limited",
                "message", "Too many requests, retry after 60s",
                "retry_after", 60
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> internal(Exception e) {
        log.error("MCP internal error", e);
        return ResponseEntity.status(500).body(Map.of(
                "error", "internal",
                "trace_id", MDC.get("traceId")
        ));
    }
}
```

### 11.5 超时与重试

```java
// 本代码仅作学习材料参考
@McpTool(description = "搜索 POI")
public List<Poi> searchPoi(String keyword) {
    return Retry.decorateCallable(retry, () ->
            circuitBreaker.executeCallable(() ->
                    TimeLimiter.executeCallable(
                            () -> client.search(keyword),
                            Duration.ofSeconds(5))
            )
    ).call();
}
```

三层防护：

- **超时**：单次调用 5 秒上限
- **重试**：临时错误重试 3 次（指数退避）
- **熔断**：连续失败开熔断器，快速失败

---

## 12. 完整企业级示例：geo-mcp-server

简化版 `time-mcp-server`（§2）只用来跑通链路。企业级示例的结构清单：

```
geo-mcp-server/
├── pom.xml
├── src/main/java/org/demo02/mcp/geo/
│   ├── GeoMcpApplication.java
│   ├── client/
│   │   ├── BaiduMapClient.java        ← HTTP 客户端（带超时/重试）
│   │   └── BaiduMapProperties.java    ← @ConfigurationProperties
│   ├── tools/
│   │   ├── GeoSearchTools.java        ← @McpTool POI 搜索
│   │   ├── GeoCodingTools.java        ← @McpTool 地址 ↔ 坐标
│   │   └── RouteTools.java            ← @McpTool 路径规划
│   ├── resources/
│   │   └── GeoResources.java          ← @McpResource 行政区字典
│   ├── prompts/
│   │   └── GeoPrompts.java            ← @McpPrompt 行程规划
│   ├── completions/
│   │   └── GeoCompletions.java        ← @McpComplete 区域代码自动补全
│   ├── dto/
│   │   ├── Poi.java
│   │   ├── Coordinate.java
│   │   └── Route.java
│   ├── config/
│   │   ├── SecurityConfig.java        ← OAuth + Filter
│   │   ├── ResilienceConfig.java      ← Bulkhead/RateLimiter
│   │   └── ObservedConfig.java        ← Observation
│   └── security/
│       ├── McpTokenFilter.java
│       └── TenantIdentity.java
└── src/main/resources/
    └── application.yaml
```

各模块代码前面 §5-§11 都已展开。把 §2 的简化版按这个结构补全，就是一个生产级 MCP Server。

> **客户端怎么连 geo-mcp-server**：见 [07 篇 §1 端到端整合实战](./07-MCP-Server高阶与生态.md)，那里有完整的两个进程配置 + ChatClient 调用 + 多租户 + 调试技巧。

---

## 13. L1-L3 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| 直接用原生 SDK（风格 C） | 代码量爆炸 | 用注解（风格 A） |
| 给 `@McpTool` 多此一举写 `MethodToolCallbackProvider` Bean | 重复注册，工具被注册两次 | 删掉 Bean，开 `annotation-scanner.enabled=true` |
| MCP Server 侧方法签名用 `@Tool` / `@ToolParam` | 注解扫描不到，工具丢失 | MCP Server 用 `@McpTool` / `@McpToolParam`；`@Tool` 是 ChatClient 进程内 Tool 用的 |
| MCP Server 侧用 `ToolContext` 当参数 | 类型不识别，工具无法注册 | 用 `McpMeta` / `McpSyncRequestContext` 等 MCP 特殊参数 |
| 工具返回巨大 List | LLM 上下文爆 | 分页 / 限 limit |
| 不区分 Resource 和 Tool | Client 缓存策略错 | 只读用 Resource |
| 没用 McpMeta / RequestContext 做多租户 | 跨租户串数据 | meta 传 tenantId |
| 工具异常直接抛 | LLM 看不到，无法自修复 | 返回 ToolResult.error |
| 不限流 | 一个 Client 打爆 Server | Bulkhead + RateLimiter |
| 不开 OTel | 出问题没法定位 | @Observed + Micrometer |
| 不区分 OAuth 和 API Key | 安全等级不够 | 生产用 OAuth 2.1 |
| 还在用 `type: WEBMVC` / `sse-message-endpoint` | yaml 解析失败 / SSE 已弃用 | `type` 选 SYNC/ASYNC，`protocol` 选 STREAMABLE |
| 不用 Inspector 验证就上线 | schema 错误发现晚 | 每次 PR 都过 Inspector |

---

## 14. L1-L3 实战任务

1. 用风格 A 跑通 Hello World（§2），Inspector 验证。
2. 用 [05 篇](./05-MCP协议全解.md)的 Client 连过来（§2.6 验证 C）。
3. 把风格 A 改成风格 B（显式 Provider），对比代码量。
4. 给 `searchPoi` 加 Resource 版本（`geo://search/{keyword}`），对比 Tool vs Resource。
5. 写一个 `@McpPrompt` 让 Claude Desktop 能一键调用"行程规划"。
6. 写一个 `@McpComplete` 给区域代码自动补全。
7. 加 Bearer Token 鉴权，Inspector 不带 token 应该 401。
8. 用 Resilience4j Bulkhead 限制单 tenant 5 并发，压测验证。
9. 接入 OAuth 2.1（用 Keycloak 做 IdP）。
10. 给所有 tool 加 `@Observed` + Prometheus，Grafana 看板。
11. 写一个全局异常处理器，把内部错误转换为 MCP 标准错误码。
12. （进阶）实现动态工具：运行时根据配置加 / 删工具，触发 `listChanged`。
13. （选做）对比 WebMVC 和 WebFlux 版本的性能（JMeter 压测）。
14. **必做**：跑通 [07 篇 §1 端到端整合](./07-MCP-Server高阶与生态.md)，把本文 Server 接到 Client 完整链路。

---

## 15. L1-L3 理解检查

1. 三种实现风格（注解 / Provider / 原生）的代码量与灵活性差异？默认应该选哪个？
2. WebMVC / WebFlux / stdio 三种传输 starter 各自适合什么场景？
3. Resource 和 Tool 的核心差别？什么时候选 Resource？
4. `@McpComplete` 解决什么问题？为什么 OpenAPI 做不到？
5. `McpMeta` 是 LLM 的参数吗？为什么是多租户 MCP Server 的核心？MCP Server 侧为什么不能用 `ToolContext`？
6. MCP 标准错误码有哪些？业务错误用什么范围？
7. 三种失败传播策略（抛异常 / 返回 error result / 静默重试）各自适用什么？
8. 为什么默认让 LLM 看到 tool 失败（返回 error result 而非抛）？
9. per-tenant Bulkhead 和 RateLimiter 的差别？
10. OTel GenAI Semantic Conventions 在 MCP 工具调用里应该埋哪些 attribute？

---

## 16. 下一步

- **必做**：[07 篇 §1 端到端整合实战](./07-MCP-Server高阶与生态.md) —— 把本文 Server 和 [05 篇](./05-MCP协议全解.md) Client 拼起来跑通完整链路
- **进阶**：[07 篇](./07-MCP-Server高阶与生态.md)其余章节 —— HTTP API → MCP / Hub / 跨语言 / 性能安全测试

---

## 17. 相关文档

- [02-Tool与AgentLoop.md](./02-Tool与AgentLoop.md) —— @Tool 注解基础
- [05-MCP协议全解.md](./05-MCP协议全解.md) —— 入门 + Client 通关（先读）
- [07-MCP-Server高阶与生态.md](./07-MCP-Server高阶与生态.md) —— 端到端整合 + 高阶与生态
- [14-安全工程与红队.md](./14-安全工程与红队.md) —— MCP 安全深入
- [15-可观测性与成本治理.md](./15-可观测性与成本治理.md) —— OTel + 可观测
- [18-大规模Agent平台与数据基础设施.md](./18-大规模Agent平台与数据基础设施.md) —— MCP Hub 多租户
- [32-多源检索Agent与MCP生态整合.md](./32-多源检索Agent与MCP生态整合.md) —— 综合压轴
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [Spring AI MCP Server Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot.html)
- [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
- [OTel GenAI Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)

---

回到 [00-目录索引.md](./00-目录索引.md)。
