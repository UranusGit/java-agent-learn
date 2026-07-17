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

| 传输 | starter 关键字 | 适用场景 | 优劣 |
|------|--------------|---------|------|
| **stdio** | `spring-ai-starter-mcp-server`（不配 type） | Claude Desktop 本地启动子进程 | 简单但只能本机 |
| **SSE**（Server-Sent Events） | `...-webmvc` / `...-webflux` + `type: SYNC/ASYNC` | 远程服务（旧） | 单向流，需长连接，扩缩容难 |
| **Streamable HTTP**（2.0 推荐） | 同上 | 远程服务，推荐 | 双向、无状态、可扩缩容 |
| **Stateless Streamable HTTP** | 同上 + 配置开 stateless | 无状态函数计算（Lambda / Cloud Run） | 无 session，请求即终止 |

**2026 年现状**：Streamable HTTP 是 MCP 官方和 Spring AI 双方推荐；SSE 仍可用但官方文档已建议新项目走 Streamable HTTP。无状态变体适合 Serverless。

---

## 3. 最小可运行的 MCP Server

### 3.1 pom 依赖

> ⚠️ Spring AI 2.0 starter 命名规范已统一为 `spring-ai-starter-*`（与 1.0 的 `spring-ai-*-spring-boot-starter` 不同）。MCP Server/Client 同样如此。MCP transport 模块（`mcp-spring-webmvc` / `mcp-spring-webflux`）在 2.0 从 `io.modelcontextprotocol.sdk` 迁移到了 `org.springframework.ai`，groupId 也要改。需要 MCP Java SDK 1.0.0+。

```xml
<!-- 进程内最小 MCP Server（不需要 web） -->
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
        type: WEBMVC
        sse-message-endpoint: /mcp/message
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

    @Bean
    public ToolCallbackProvider timeTools() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new TimeTools())
                .build();
    }
}

public class TimeTools {
    @Tool(description = "获取服务器当前时间")
    public String currentTime() {
        return new Date().toString();
    }
}
```

**注意**：和 02 篇的 `@Tool` 是同一个注解 —— **你写的工具可以同时被 ChatClient（进程内）和 MCP Client（跨进程）使用**。

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

    @Tool(description = "根据工号查询员工信息，返回姓名、部门、岗位、入职时间")
    public Employee getEmployee(
            @ToolParam(description = "工号，如 E001") String employeeId
    ) {
        return hrService.findById(employeeId);
    }

    @Tool(description = "根据姓名模糊查询员工列表")
    public List<Employee> searchEmployees(
            @ToolParam(description = "姓名关键字") String nameKeyword
    ) {
        return hrService.searchByName(nameKeyword);
    }

    public record Employee(String id, String name, String department,
                           String position, LocalDate hireDate) {}
}

@Component
public class OrderTools {

    @Tool(description = "提交退款申请")
    public String submitRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因") String reason,
            @ToolParam(description = "退款金额") double amount,
            ToolContext context
    ) {
        String userId = (String) context.getContext().get("userId");
        if (!authService.canRefund(userId, amount)) {
            throw new SecurityException("No permission to refund " + amount);
        }
        return orderService.submitRefund(orderId, reason, amount);
    }
}

@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider companyTools(
            HrTools hr, OrderTools order, OpsTools ops) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(hr, order, ops)
                .build();
    }
}
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
@Tool(description = "提交退款申请")
public String submitRefund(..., ToolContext context) {
    String userId = (String) context.getContext().get("userId");
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
    public Object audit(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
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
        sse:
          connections:
            company-hr:
              url: https://hr.internal.company.com/mcp/sse
            company-ops:
              url: https://ops.internal.company.com/mcp/sse
        stdio:
          servers-configuration: classpath:mcp-local-servers.json
```

### 6.3 注入到 ChatClient

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      ToolCallbackProvider mcpToolProvider) {
    return builder
            .defaultTools(mcpToolProvider)   // 自动包含所有连接的 MCP Server 工具
            .build();
}
```

**这是 MCP 最神奇的地方**：你的 ChatClient 突然就有了 HR、Ops、Order 所有能力，跨进程、跨语言、跨团队。

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

    @McpResource(uri = "company://employees/{id}", description = "单个员工详情")
    public String employeeById(@McpUriVariable String id) {
        return employeeRepo.findById(id).toString();
    }
}
```

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

    @McpPrompt(description = "生成员工绩效评估草稿")
    public String performanceReview(
            @McpPromptArg(description = "员工姓名") String name,
            @McpPromptArg(description = "评估周期") String period
    ) {
        return "请为员工 %s 生成 %s 的绩效评估草稿...".formatted(name, period);
    }
}
```

Claude Desktop 用户在对话框上方会看到"绩效评估"按钮。

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
3. 为什么 @Tool 注解可以同时用于进程内和 MCP Server？
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
