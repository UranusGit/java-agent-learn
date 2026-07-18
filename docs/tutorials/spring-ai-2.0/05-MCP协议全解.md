# 05 MCP 协议全解（Server + Client + 生态）

> 本文合并自原 04「MCP 与会话持久化」+ 原 10「MCP 生态与长期演进」+ 原 15「MCP Server 端实战」。
>
> 一篇搞定 MCP：协议本质、Server 端实战、Client 端消费、企业生态建设。
>
> 前置：[`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) + [`./03-Advisor链全解.md`](./03-Advisor链全解.md)
> 预计：1.5 天

---

## 0. 认知地图

```
进程内 Tool（@Tool / ToolCallback）
    ↓ 跨进程需求
MCP（Model Context Protocol，Anthropic 2024-11 推出）
    ├── 协议三类能力：Tools / Resources / Prompts
    ├── 三种传输：stdio / SSE / Streamable HTTP
    ├── Server 端实战（本文 §3-§5）
    ├── Client 端消费（本文 §6）
    └── 企业生态（本文 §8：MCP Hub、A2A、长期演进）
```

---

## 1. 为什么需要 MCP

| 场景 | 用 @Tool | 用 MCP Server |
|------|---------|--------------|
| 工具和 ChatClient 同一进程 | ✅ | 不必要 |
| 工具要给 Claude Desktop / Cursor 用 | ❌ | ✅ |
| 工具是公司内部多 Agent 共享 | 麻烦 | ✅ |
| 工具是独立团队维护（如 DBA 团队管 DB 工具） | 难拆 | ✅ 自然拆分 |
| 工具要支持非 Java 客户端（Python Agent） | ❌ | ✅ |

---

## 2. MCP 协议心智模型

### 2.1 一张图理解 MCP

```
┌──────────────────┐                ┌──────────────────┐
│  MCP Client      │                │  MCP Server      │
│ (Claude Desktop, │ ◄── stdio / ──►│ (你的 Java App)  │
│  Cursor, Agent)  │     SSE / HTTP │                  │
└──────────────────┘                └──────────────────┘
```

### 2.2 三类能力

1. **Tools**：可执行的函数（GET/POST 等副作用）—— 最常用。
2. **Resources**：可读取的数据（文件、DB 查询）—— 类似 GET，只读。
3. **Prompts**：预定义的 prompt 模板 —— Claude Desktop 可快速调用。

### 2.3 四种传输方式

| 传输 | 配置项 `spring.ai.mcp.server.protocol` | 适用场景 | 优劣 |
|------|---------------------------------------|---------|------|
| **stdio** | 配置 `spring.ai.mcp.server.stdio=true` | Claude Desktop 本地启动子进程 | 简单但只能本机 |
| **SSE**（Server-Sent Events） | `SSE` | 远程服务（**2.0 已弃用**） | 单向流，需长连接，扩缩容难 |
| **Streamable HTTP**（2.0 推荐） | `STREAMABLE` | 远程服务，**推荐** | 双向、可扩缩容，官方与 Spring AI 共同推荐 |
| **Stateless Streamable HTTP** | `STATELESS` | 无状态函数计算（Lambda / Cloud Run） | 无 session，请求即终止 |

> ⚠️ 注意区分两个配置项：
> - `spring.ai.mcp.server.protocol`：选传输方式（`SSE` / `STREAMABLE` / `STATELESS`）
> - `spring.ai.mcp.server.type`：选 Server API 类型（`SYNC` / `ASYNC`），决定注入 `McpSyncServerExchange` 还是 `McpAsyncServerExchange`。**与传输方式无关**。

**2026 年现状**：Streamable HTTP 是 MCP 官方和 Spring AI 双方推荐；**SSE 自 2.0.0 起标记 `@Deprecated`**，仅作为过渡兼容，新项目不要用。无状态变体适合 Serverless。

---

## 3. 最小可运行的 MCP Server

### 3.1 pom 依赖

> ⚠️ Spring AI 2.0 starter 命名规范已统一为 `spring-ai-starter-*`（与 1.0 的 `spring-ai-*-spring-boot-starter` 不同）。MCP Server/Client 同样如此。MCP 相关模块在 2.0 从 `io.modelcontextprotocol.sdk` 迁移到了 `org.springframework.ai`，groupId 也要改。底层 MCP SDK 已内联进 Spring AI BOM，**不需要再单独声明 MCP Java SDK 依赖**。

```xml
<!-- 进程内最小 MCP Server（stdio 传输，给 Claude Desktop 用） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
<!-- WebMVC 传输（Streamable HTTP / SSE，推荐远程部署用） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

可选：

```xml
<!-- WebFlux 传输（响应式，适合流式优先的部署） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

### 3.2 application.yaml

```yaml
spring:
  ai:
    mcp:
      server:
        name: demo02-mcp-server
        version: 1.0.0
        # 传输协议：STREAMABLE / SSE / STATELESS（2.0 推荐用 STREAMABLE）
        # 注意：stdio 不写在这里，stdio 见下面 stdio: true
        protocol: STREAMABLE
        # Server API 类型：SYNC（默认，注入 McpSyncServerExchange）
        #             或 ASYNC（注入 McpAsyncServerExchange）
        type: SYNC
        # 开启注解扫描（默认开启），扫描 @McpTool/@McpResource/@McpPrompt/@McpComplete
        annotation-scanner:
          enabled: true
        # Streamable HTTP 心跳，避免代理超时断连
        keep-alive-interval: 30s
        # 仅当走 stdio 传输时开启
        # stdio: true
```

### 3.3 第一个工具

```java
// org.demo02.mcp.TimeMcpServer
// 本代码仅作学习材料参考

@SpringBootApplication
public class TimeMcpServer {
    public static void main(String[] args) {
        SpringApplication.run(TimeMcpServer.class, args);
    }
    // 不需要手写 ToolCallbackProvider Bean：
    // 开启 annotation-scanner 后，容器里所有 @McpTool 方法会自动注册到 MCP Server。
}

@Component
public class TimeTools {

    @McpTool(description = "获取服务器当前时间")
    public String currentTime() {
        return new Date().toString();
    }
}
```

**关键变化**：
- **MCP Server 场景必须用 `@McpTool`，而不是 `@Tool`**。两者都是工具注解但扫描机制不同：`@Tool` 配合 `MethodToolCallbackProvider` 给进程内 ChatClient 用；`@McpTool` 由 MCP 注解扫描器自动注册到 MCP Server，跨进程暴露。
- 不需要再手写 `MethodToolCallbackProvider` Bean（除非你要把同一批 `@Tool` 工具同时暴露给进程内 ChatClient）。

### 3.4 启动 + 验证

```bash
mvn spring-boot:run
npx @modelcontextprotocol/inspector http://localhost:8080
```

---

## 4. 真实场景：企业内部 MCP Server

```java
// org.demo02.mcp.CompanyMcpServer
// 本代码仅作学习材料参考

@Component
public class HrTools {

    private final HrService hrService;

    @McpTool(description = "根据工号查询员工信息，返回姓名、部门、岗位、入职时间")
    public Employee getEmployee(
            @McpToolParam(description = "工号，如 E001") String employeeId
    ) {
        return hrService.findById(employeeId);
    }

    @McpTool(description = "根据姓名模糊查询员工列表")
    public List<Employee> searchEmployees(
            @McpToolParam(description = "姓名关键字") String nameKeyword
    ) {
        return hrService.searchByName(nameKeyword);
    }

    public record Employee(String id, String name, String department,
                           String position, LocalDate hireDate) {}
}

@Component
public class OrderTools {

    @McpTool(description = "提交退款申请")
    public String submitRefund(
            @McpToolParam(description = "订单号") String orderId,
            @McpToolParam(description = "退款原因") String reason,
            @McpToolParam(description = "退款金额") double amount,
            McpMeta meta   // MCP 注入的元信息，从客户端 ToolContext/Headers 透传过来
    ) {
        String userId = (String) meta.get("userId");
        if (!authService.canRefund(userId, amount)) {
            throw new SecurityException("No permission to refund " + amount);
        }
        return orderService.submitRefund(orderId, reason, amount);
    }
}

// 不需要 McpToolConfig 手写 ToolCallbackProvider Bean：
// annotation-scanner 默认开启，会自动收集容器内所有 @McpTool 方法并注册到 MCP Server。
```

---

## 5. 鉴权：防止 MCP Server 被滥用

### 5.1 三层防御

**1. 传输层：HTTPS + Token**

```java
@Configuration
@EnableWebSecurity
public class McpSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(new McpTokenFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

class McpTokenFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                     FilterChain chain) throws ServletException, IOException {
        String token = req.getHeader("X-MCP-Token");
        if (!validateToken(token)) {
            resp.sendError(401, "Invalid MCP token");
            return;
        }
        chain.doFilter(req, resp);
    }
}
```

**2. 工具层：敏感工具单独鉴权**

```java
@McpTool(description = "提交退款申请")
public String submitRefund(..., McpMeta meta) {
    String userId = (String) meta.get("userId");
    if (!authService.canRefund(userId, amount)) {
        throw new SecurityException("No permission");
    }
    return orderService.submitRefund(orderId, reason, amount);
}
```

**3. 审计层：所有调用记录**

```java
@Aspect
@Component
public class McpToolAuditAspect {

    @Around("@annotation(tool)")
    public Object audit(ProceedingJoinPoint pjp, McpTool tool) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            auditLog.save(AuditEntry.success(...));
            return result;
        } catch (Throwable e) {
            auditLog.save(AuditEntry.failure(...));
            throw e;
        }
    }
}
```

---

## 6. 在 Spring AI 中消费 MCP Server

### 6.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

需要 WebFlux 异步客户端时再加：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
</dependency>
```

### 6.2 配置多个 MCP Server

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: demo01-mcp-client
        version: 1.0.0
        type: SYNC           # 或 ASYNC，决定注入 McpSyncClient / McpAsyncClient
        request-timeout: 20s
        initialized: true    # 启动时主动 initialize
        # 推荐：Streamable HTTP 传输（2.0 起 SSE 已弃用）
        streamable-http:
          connections:
            company-hr:
              url: https://hr.internal.company.com/mcp
            company-ops:
              url: https://ops.internal.company.com/mcp
              endpoint: /mcp  # 默认就是 /mcp，按需调整
        # 过渡期：仍需对接老 SSE Server 时（兼容保留）
        sse:
          connections:
            company-legacy:
              url: https://legacy.internal.company.com/mcp/sse
              sse-endpoint: /sse
        # 本机 stdio 子进程
        stdio:
          servers-configuration: classpath:mcp-local-servers.json
```

### 6.3 注入到 ChatClient

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      List<McpToolCallbackProvider> mcpToolProviders,
                      List<McpSyncClient> mcpSyncClients) {
    // spring-ai-starter-mcp-client 自动为每个连接的 MCP Server 注册一个 McpToolCallbackProvider
    // 把所有 provider 的 ToolCallback 平铺后塞给 ChatClient
    List<ToolCallback> all = mcpToolProviders.stream()
            .map(McpToolCallbackProvider::getToolCallbacks)
            .flatMap(Arrays::stream)
            .toList();
    return builder
            .defaultTools(all.toArray(new ToolCallback[0]))
            .build();
}
```

**这是 MCP 最神奇的地方**：你的 ChatClient 突然就有了 HR、Ops、Order 所有能力，跨进程、跨语言、跨团队。

### 6.4 Client 侧常用扩展点

| 接口 | 作用 |
|------|------|
| `McpSyncClientCustomizer` / `McpAsyncClientCustomizer` | 在 client 创建后做定制（加 header、改 clientInfo 等） |
| `McpToolFilter` | 过滤/改名从 MCP Server 拿到的工具 |
| `McpToolNamePrefixGenerator` | 给来自不同 Server 的工具加前缀，避免命名冲突（默认实现 `DefaultMcpToolNamePrefixGenerator`） |
| `ToolContextToMcpMetaConverter` | 把 ChatClient 调用时的 `ToolContext` 转成 MCP 的 `McpMeta`，反过来透传给 Server |
| `TransportContextExtractor` | 从 HTTP 请求中抽取鉴权/租户信息塞进 `McpTransportContext` |

Client 端还可在 Bean 方法上加以下注解订阅 Server 事件：
`@McpLogging`（接收 Server 日志）、`@McpSampling`（Server 反向请求 LLM 采样）、`@McpElicitation`（Server 反向请求用户输入）、`@McpProgress`（进度通知）、`@McpToolListChanged` / `@McpResourceListChanged` / `@McpPromptListChanged`（列表变更通知）。

---

## 7. Resources：暴露只读数据

```java
@Component
public class CompanyResources {

    private final EmployeeRepository employeeRepo;

    @McpResource(uri = "company://employees", description = "公司全员通讯录")
    public String allEmployees() {
        return employeeRepo.findAll().stream()
                .map(e -> e.id() + " " + e.name() + " " + e.department())
                .collect(Collectors.joining("\n"));
    }

    // URI 模板变量 {id} 通过"同名方法参数"自动绑定，不需要任何注解
    @McpResource(uri = "company://employees/{id}", description = "单个员工详情")
    public String employeeById(String id) {
        return employeeRepo.findById(id).toString();
    }
}
```

> ⚠️ 2.0 已**没有** `@McpUriVariable` 注解。URI 模板里的 `{var}` 按**方法参数名**匹配绑定，方法参数直接用同名 `String`（或 `Map<String, String>` 收所有变量）即可。

**Resources vs Tools 怎么选**：

| 特征 | 用 Resource | 用 Tool |
|------|------------|--------|
| 只读 | ✅ | 也行 |
| 有副作用 | ❌ | ✅ |
| 客户端主动获取 | ✅ | ❌（LLM 决定调用） |
| 大数据量 | ✅ 分页 | ❌ |

---

## 8. Prompts：暴露预定义模板

```java
@Component
public class CompanyPrompts {

    // 参数说明通过 @McpArg 注解直接写在方法参数上；@McpPrompt 只保留 name / title / description / metaProvider。
    @McpPrompt(name = "performanceReview", description = "生成员工绩效评估草稿")
    public String performanceReview(
            @McpArg(name = "name",   description = "员工姓名")                       String name,
            @McpArg(name = "period", description = "评估周期", required = true)       String period) {
        return "请为员工 %s 生成 %s 的绩效评估草稿...".formatted(name, period);
    }
}
```

Claude Desktop 用户在对话框上方会看到"绩效评估"按钮。

> ⚠️ 2.0 已**没有** `@McpPromptArg` 注解。Prompt 参数描述通过 `@McpArg(name=..., description=..., required=...)` 注解直接写在方法参数上（与 `@McpToolParam` 风格一致）。`@McpPrompt` 本身只保留 `name`、`title`、`description`、`metaProvider` 四个属性。

### 8.1 Completions：补全提示（2.0 新增）

`@McpComplete` 暴露资源 URI 或 prompt 参数的自动补全，给客户端更好的输入体验。

```java
@Component
public class CompanyCompletions {

    @McpComplete(uri = "company://employees/{id}")
    public List<String> completeEmployeeId(String partialId) {
        return employeeRepo.suggestIds(partialId);
    }
}
```

---

## 9. 企业 MCP 生态建设

### 9.1 MCP Hub：工具市场

平台维护一个"工具市场"，所有 MCP Server 注册在 Hub 上，业务方按需订阅：

```
┌──────────────────────────────────┐
│ MCP Hub                          │
│ ┌─────────┐ ┌─────────┐         │
│ │ HR Tool │ │ OA Tool │  ...    │
│ └─────────┘ └─────────┘         │
└──────────┬───────────────────────┘
           ↓ 通过权限代理
┌──────────────────────────────────┐
│ Agent of Tenant A                │
│ - 订阅：HR Tool, OA Tool         │
└──────────────────────────────────┘
```

```sql
CREATE TABLE mcp_subscription (
    tenant_id VARCHAR(64),
    mcp_server_id VARCHAR(64),
    granted_tools JSONB,
    granted_at TIMESTAMP,
    granted_by VARCHAR(64),
    PRIMARY KEY (tenant_id, mcp_server_id)
);
```

### 9.2 MCP vs 直接 HTTP API

| 对比项 | REST API | MCP |
|--------|---------|-----|
| 客户端集成 | 每个 Agent 都要手写 REST 调用 | Agent 自动发现，零代码集成 |
| 工具 schema | 手写 OpenAPI，可能过时 | 框架自动生成，永远同步 |
| 跨 Agent 复用 | 各自封装 | 一处实现，处处可用 |
| 与 Claude Desktop / Cursor 集成 | 不支持 | 原生支持 |

**结论**：

- 只给自己的 Agent 用：直接 HTTP / 进程内 Tool 都行。
- 要给生态用（Claude Desktop、Cursor、第三方 Agent）：上 MCP。

### 9.3 A2A（Agent-to-Agent）协议

MCP 解决"Agent ↔ Tool"，A2A 解决"Agent ↔ Agent"。Google 2025 年提出 A2A 协议，让不同框架的 Agent 互相通信。

Spring AI Alibaba 1.0 开始提供 A2A 支持。**当前状态（2026-07）**：协议草案阶段，尚未广泛部署，关注即可。

---

## 10. 安全红线

MCP Server 是把"你的能力"暴露给"任意的 LLM"。**这等价于把工具暴露给互联网**。务必：

1. **不暴露高危工具**：`dropTable`、`deleteUser`、`grantPermission` 绝不上 MCP。
2. **写操作要二次确认**：敏感操作返回"请用户确认"而不是直接执行。
3. **限流**：每个 client 每分钟最多 N 次调用。
4. **审计**：所有调用留日志，可追溯到调用方。
5. **脱敏**：返回结果前过滤 PII / 密钥 / 内部 ID。

详细安全设计见 [`./14-安全工程与红队.md`](./14-安全工程与红队.md)。

---

## 11. 调试与运维

### 11.1 用官方 Inspector 调试

```bash
npx @modelcontextprotocol/inspector http://localhost:8080
```

### 11.2 日志

```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    io.modelcontextprotocol: DEBUG
```

### 11.3 监控指标

```java
@Component
public class McpMetrics {

    public void recordToolCall(String toolName, long durationMs, boolean success) {
        meters.timer("mcp.tool.call", "tool", toolName, "result",
                success ? "success" : "error")
              .record(Duration.ofMillis(durationMs));
    }
}
```

监控看板关键指标：每个 tool 的 QPS、调用延迟分位、错误率、在线 client 数量。

---

## 12. 实战避坑

### 12.1 "工具在 Claude Desktop 看不到"

**原因 1**：`claude_desktop_config.json` 路径不对（macOS 是 `~/Library/Application Support/Claude/`）。

**原因 2**：JSON 格式错误，用 JSONLint 校验。

**原因 3**：Java 应用启动失败。先在终端手动跑 `java -jar` 确认。

### 12.2 "工具参数 schema 不合法"

**症状**：Claude Desktop 报"unable to parse tool definition"。

**解决**：参数尽量用简单类型 + record，避免 Map<String, Object> / 嵌套泛型。

### 12.3 "SSE 连接频繁断开"

**原因**：代理（Nginx/Cloudflare）有 buffer。

**解决**：

```nginx
location /mcp/ {
    proxy_buffering off;
    proxy_cache off;
    proxy_http_version 1.1;
    proxy_read_timeout 24h;
}
```

### 12.4 "stdio 模式下 jar 启动慢"

**原因**：Claude Desktop 每次启动都拉起 JVM。

**解决**：

- 用 GraalVM Native Image（启动 <100ms）。
- 或者改用 HTTP 传输，让 Server 常驻。

### 12.5 "多个 MCP Server 有同名工具"

**解决**：在工具命名加前缀（`hr_search`、`order_search`），不要裸 `search`。

---

## 13. 实战任务

1. 把 L1 写的 `TimeTools` + `CalculatorTools` 包装成 MCP Server，stdio 传输。
2. 在 Claude Desktop 配置接入你的 MCP Server，验证能调用工具。
3. 把 07 篇的 RAG 系统暴露成 `rag_query` 工具，让 Claude Desktop 能用自然语言查知识库。
4. 实现一个 `Resource`：`db://schema/{table}`，返回表的字段定义。
5. 给你的 MCP Server 加 token 鉴权 + AOP 审计日志。
6. 配置你的 Spring AI 应用同时作为 MCP Client 和 Server。
7. （进阶）用 GraalVM 编译成 Native Image。
8. （选做）调研 A2A 协议，跑通一个跨 Agent 通信的 demo。

---

## 14. 理解检查

1. MCP 三类能力（Tools / Resources / Prompts）分别解决什么问题？
2. stdio / SSE / Streamable HTTP 各自适用场景？
3. 为什么 MCP Server 必须用 `@McpTool` 而不是 `@Tool`？两者的扫描与注册机制有什么区别？
4. MCP Server 上线前必须做的三件事是什么？
5. MCP Hub 的权限模型怎么设计？
6. MCP 相比直接 REST API 的核心优势？A2A 解决什么？

---

## 15. 相关文档

- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— @Tool 注解基础
- [`./14-安全工程与红队.md`](./14-安全工程与红队.md) —— MCP 安全深入
- [`./18-大规模Agent平台与数据基础设施.md`](./18-大规模Agent平台与数据基础设施.md) —— MCP Hub 平台化
- [MCP 协议规范](https://modelcontextprotocol.io/)
- [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot.html)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
