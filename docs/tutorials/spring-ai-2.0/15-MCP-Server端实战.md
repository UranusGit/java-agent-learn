# L2 MCP Server 端实战（Spring AI 2.0）

> 本文回答：**怎么把自己写的 Java 能力暴露成 MCP Server，让 Claude Desktop / Cursor / 其他 Agent 都能用？**
>
> MCP（Model Context Protocol）是 Anthropic 2024 年 11 月推出的开放协议，把"工具发现"标准化了。Spring AI 2.0 提供完整的 Server / Client 实现。
>
> 前置：[`./02-初级-ToolCallingAdvisor.md`](./02-初级-ToolCallingAdvisor.md)
> 预计：1 天

---

## 0. 认知地图

```
L1 基础：进程内的 Tool 调用（@Tool / ToolCallback）
    ↓
L2 工程化
    ├── RAG（13）
    ├── 评估闭环（14）
    ├── MCP Server（本文）  ← 把工具能力跨进程暴露
    └── 多 Agent（16）
```

**为什么需要 MCP**：

| 场景 | 用 @Tool | 用 MCP Server |
|------|---------|--------------|
| 工具和 ChatClient 同一进程 | ✅ | 不必要 |
| 工具要给 Claude Desktop / Cursor 用 | ❌ | ✅ |
| 工具是公司内部多 Agent 共享 | 麻烦 | ✅ |
| 工具是独立团队维护（如 DBA 团队管 DB 工具） | 难拆 | ✅ 自然拆分 |
| 工具要支持非 Java 客户端（Python Agent） | ❌ | ✅ |

---

## 1. MCP 协议心智模型

### 1.1 一张图理解 MCP

```
┌──────────────────┐                ┌──────────────────┐
│  MCP Client      │                │  MCP Server      │
│ (Claude Desktop, │ ◄── stdio / ──►│ (你的 Java App)  │
│  Cursor, Agent)  │     SSE / HTTP │                  │
│                  │                │                  │
│  - 调用 tools    │                │  - 暴露 tools    │
│  - 读取 resources│                │  - 暴露 resources│
│  - 使用 prompts  │                │  - 暴露 prompts  │
└──────────────────┘                └──────────────────┘
```

**MCP 三类能力**：

1. **Tools**：可执行的函数（GET/POST 等副作用操作）—— 最常用。
2. **Resources**：可读取的数据（文件、DB 查询结果）—— 类似 GET，只读。
3. **Prompts**：预定义的 prompt 模板 —— Claude Desktop 可以快速调用。

### 1.2 三种传输方式

| 传输 | 适用场景 | 优劣 |
|------|---------|------|
| **stdio** | Claude Desktop 本地启动子进程 | 简单但只能本机 |
| **SSE（Server-Sent Events）** | 远程服务 | 单向流，需长连接 |
| **Streamable HTTP**（2.0 新） | 远程服务，推荐 | 双向、无状态、可扩缩容 |

**2026 年现状**：Streamable HTTP 是官方推荐，SSE 在逐步淘汰。

---

## 2. 最小可运行的 MCP Server

### 2.1 pom 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
</dependency>
<!-- 如果要做 WebMVC（HTTP）传输 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-webmvc-spring-boot-starter</artifactId>
</dependency>
```

### 2.2 application.yaml

```yaml
spring:
  ai:
    mcp:
      server:
        name: demo02-mcp-server
        version: 1.0.0
        # 传输方式三选一：stdio / sse / webmvc
        type: WEBMVC
        sse-message-endpoint: /mcp/message
```

### 2.3 第一个工具

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

### 2.4 启动 + 验证

```bash
mvn spring-boot:run
```

应用启动后会在 `http://localhost:8080/` 暴露 MCP endpoint。你可以用官方 inspector 验证：

```bash
npx @modelcontextprotocol/inspector http://localhost:8080
```

浏览器打开 inspector UI，应该能看到 `currentTime` 工具并能调用。

---

## 3. 把 Claude Desktop 接入你的 MCP Server

### 3.1 编辑 Claude Desktop 配置

macOS：`~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "demo02-time": {
      "command": "java",
      "args": ["-jar", "/path/to/demo02-mcp-server.jar"],
      "transport": "stdio"
    }
  }
}
```

或者 HTTP 传输：

```json
{
  "mcpServers": {
    "demo02-time": {
      "url": "http://localhost:8080/sse",
      "transport": "sse"
    }
  }
}
```

### 3.2 重启 Claude Desktop

打开对话框，应该能看到工具图标。问它"现在服务器几点了？"，Claude 会自动调你的 MCP Server。

---

## 4. 真实场景：企业内部 MCP Server

### 4.1 场景：把公司内网能力暴露给所有 Agent

假设公司有这些能力：
- 查询员工信息（HR 系统）
- 查询订单（ERP）
- 提交工单（ITSM）
- 查询监控指标（Prometheus）

**做法**：建一个 `company-mcp-server`，把这些能力暴露成 tools。所有内部 Agent（客服 Agent、运维 Agent、HR 助手 Agent）都通过 MCP Client 调用它。

**好处**：
- 工具能力统一维护，改一次到处生效。
- 不同语言（Python/Java/Node）的 Agent 都能用。
- 鉴权统一在 MCP Server 层做。

### 4.2 完整工具集（参考）

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

    private final OrderService orderService;

    @Tool(description = "查询订单状态")
    public OrderStatus getOrderStatus(
            @ToolParam(description = "订单号") String orderId
    ) {
        return orderService.findStatus(orderId);
    }

    @Tool(description = "提交退款申请")
    public String submitRefund(
            @ToolParam(description = "订单号") String orderId,
            @ToolParam(description = "退款原因") String reason,
            @ToolParam(description = "退款金额") double amount
    ) {
        return orderService.submitRefund(orderId, reason, amount);
    }
}

@Component
public class OpsTools {

    private final PrometheusClient prom;

    @Tool(description = "查询 Prometheus 指标")
    public double queryMetric(
            @ToolParam(description = "PromQL 表达式") String query
    ) {
        return prom.query(query);
    }
}
```

### 4.3 统一注册

```java
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

### 5.1 威胁模型

MCP Server 一旦暴露到网络，就可能被：
- 未授权访问（任意客户端调用你的工具）
- 工具滥用（恶意 LLM 一直调 `submitRefund`）
- 数据泄露（`getEmployee` 返回敏感字段）

### 5.2 三层防御

**1. 传输层：HTTPS + Token**

```yaml
spring:
  ai:
    mcp:
      server:
        sse-message-endpoint: /mcp/message
# 用 Spring Security 给 /mcp/** 加拦截器
```

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
public String submitRefund(
        @ToolParam(description = "订单号") String orderId,
        @ToolParam(description = "退款原因") String reason,
        @ToolParam(description = "退款金额") double amount,
        ToolContext context     // MCP Client 透传的上下文
) {
    String userId = (String) context.getContext().get("userId");
    if (!authService.canRefund(userId, amount)) {
        throw new SecurityException("No permission to refund " + amount);
    }
    return orderService.submitRefund(orderId, reason, amount);
}
```

**3. 审计层：所有调用记录**

```java
@Aspect
@Component
public class McpToolAuditAspect {

    private final AuditLogRepository auditLog;

    @Around("@annotation(tool)")
    public Object audit(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            auditLog.save(AuditEntry.success(
                    pjp.getSignature().getName(),
                    Arrays.toString(pjp.getArgs()),
                    result != null ? result.toString() : "null",
                    System.currentTimeMillis() - start));
            return result;
        } catch (Throwable e) {
            auditLog.save(AuditEntry.failure(
                    pjp.getSignature().getName(),
                    Arrays.toString(pjp.getArgs()),
                    e.getMessage(),
                    System.currentTimeMillis() - start));
            throw e;
        }
    }
}
```

---

## 6. 在 Spring AI 中消费 MCP Server

你的 Spring AI 应用也可以作为 MCP Client，调用其他 MCP Server。

### 6.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-client-spring-boot-starter</artifactId>
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
          # 也可以连本地 stdio 的 MCP Server
          servers-configuration: classpath:mcp-local-servers.json
```

### 6.3 注入到 ChatClient

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      List<McpClient> mcpClients,
                      ToolCallbackProvider mcpToolProvider) {
    return builder
            .defaultTools(mcpToolProvider)   // 自动包含所有连接的 MCP Server 工具
            .build();
}
```

**这是 MCP 最神奇的地方**：你的 ChatClient 突然就有了 HR、Ops、Order 所有能力，跨进程、跨语言、跨团队。

---

## 7. Resources：暴露只读数据

Resources 是 MCP 的另一类能力 —— 给客户端提供可读取的"资源"，类似 GET 请求。

### 7.1 场景

- 暴露公司通讯录（`company://employees`）
- 暴露数据库表结构（`db://schema/employees`）
- 暴露日志片段（`log://app-server/2026-07-17`）

### 7.2 实现示例

```java
// org.demo02.mcp.CompanyResources
// 本代码仅作学习材料参考

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

### 7.3 Resources vs Tools 怎么选

| 特征 | 用 Resource | 用 Tool |
|------|------------|--------|
| 只读 | ✅ | 也行 |
| 有副作用 | ❌ | ✅ |
| 客户端主动获取 | ✅ | ❌（LLM 决定调用） |
| 大数据量（如全表） | ✅ 分页 | ❌ |

---

## 8. Prompts：暴露预定义模板

MCP 的第三类能力 —— 提供给客户端的 prompt 模板，用户在 Claude Desktop 里点一下就能用。

```java
@Component
public class CompanyPrompts {

    @McpPrompt(description = "生成员工绩效评估草稿")
    public String performanceReview(
            @McpPromptArg(description = "员工姓名") String name,
            @McpPromptArg(description = "评估周期，如 2026Q2") String period
    ) {
        return """
            请为员工 %s 生成 %s 的绩效评估草稿。
            评估维度：
            1. 工作成果
            2. 团队协作
            3. 学习成长
            4. 改进建议
            """.formatted(name, period);
    }
}
```

Claude Desktop 用户在对话框上方会看到"绩效评估"按钮，点击就触发这个模板。

---

## 9. 调试与运维

### 9.1 用官方 Inspector 调试

```bash
npx @modelcontextprotocol/inspector http://localhost:8080
```

打开浏览器可以看到：
- 所有注册的 tools / resources / prompts
- 在线调用 tool 测试
- 查看请求/响应 JSON

### 9.2 日志

```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    io.modelcontextprotocol: DEBUG
```

### 9.3 监控指标

```java
@Component
public class McpMetrics {

    private final MeterRegistry meters;

    public void recordToolCall(String toolName, long durationMs, boolean success) {
        meters.timer("mcp.tool.call", "tool", toolName, "result",
                success ? "success" : "error")
              .record(Duration.ofMillis(durationMs));
    }
}
```

监控看板关键指标：
- 每个 tool 的 QPS
- tool 调用延迟分位（P50/P95/P99）
- tool 调用错误率
- 在线 client 数量

---

## 10. 实战避坑

### 10.1 "工具在 Claude Desktop 看不到"

**原因 1**：`claude_desktop_config.json` 路径不对（macOS 是 `~/Library/Application Support/Claude/`，Windows 是 `%APPDATA%\Claude\`）。

**原因 2**：JSON 格式错误，用 [JSONLint](https://jsonlint.com/) 校验。

**原因 3**：Java 应用启动失败，Claude Desktop 启动子进程时 stderr 被吞掉。**先在终端手动跑** `java -jar`，确认能起来。

### 10.2 "工具参数 schema 不合法"

**症状**：Claude Desktop 报"unable to parse tool definition"。

**原因**：方法参数太复杂（嵌套 Map、泛型通配符），Spring AI 生成的 JSON Schema 不规范。

**解决**：参数尽量用简单类型 + record，避免 Map<String, Object> / 嵌套泛型。

### 10.3 "SSE 连接频繁断开"

**原因**：代理（Nginx/Cloudflare）有 buffer，把 SSE 流缓冲了。

**解决**：

```nginx
location /mcp/ {
    proxy_pass http://backend;
    proxy_buffering off;       # 关键
    proxy_cache off;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_read_timeout 24h;
}
```

### 10.4 "工具调用超时"

**原因 1**：MCP Server 调外部服务慢（如查 DB 30s）。

**解决**：在 yaml 设超时：

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          request-timeout: 60s
```

**原因 2**：LLM 调用工具循环死循环。

**解决**：在 `ToolCallingAdvisor` 设最大轮数（默认是 10）。

### 10.5 "多个 MCP Server 有同名工具"

**症状**：两个 MCP Server 都有 `search`，LLM 不知道调哪个。

**解决**：在工具命名加前缀（`hr_search`、`order_search`），不要裸 `search`。

### 10.6 "stdio 模式下 jar 启动慢"

**原因**：Claude Desktop 每次启动都拉起 JVM，冷启动慢。

**解决**：

- 用 GraalVM Native Image 编译成原生可执行文件（启动 <100ms）。
- 或者改用 HTTP 传输，让 Server 常驻。

---

## 11. MCP vs 直接 HTTP API

**反方观点**：我已经有 REST API 了，为啥要再封一层 MCP？

**正方观点**：

| 对比项 | REST API | MCP |
|--------|---------|-----|
| 客户端集成 | 每个 Agent 都要手写 REST 调用 | Agent 自动发现，零代码集成 |
| 工具 schema | 手写 OpenAPI，可能过时 | 框架自动生成，永远同步 |
| 跨 Agent 复用 | 各自封装 | 一处实现，处处可用 |
| 与 Claude Desktop / Cursor 集成 | 不支持 | 原生支持 |
| 长期演进 | API 改了要通知所有客户端 | MCP 协议自带版本协商 |

**结论**：

- 只给自己的 Agent 用：直接 HTTP / 进程内 Tool 都行。
- 要给生态用（Claude Desktop、Cursor、第三方 Agent）：上 MCP。

---

## 12. 安全红线

MCP Server 是把"你的能力"暴露给"任意的 LLM"。**这等价于把工具暴露给互联网**。务必：

1. **不暴露高危工具**：`dropTable`、`deleteUser`、`grantPermission` 绝不上 MCP。
2. **写操作要二次确认**：敏感操作返回"请用户确认"而不是直接执行。
3. **限流**：每个 client 每分钟最多 N 次调用。
4. **审计**：所有调用留日志，可追溯到调用方。
5. **脱敏**：返回结果前过滤 PII / 密钥 / 内部 ID。

> 详细的安全设计见 [`./17-安全工程与红队.md`](./17-安全工程与红队.md)。

---

## 13. 实战任务

1. 把你 L1 写的 `TimeTools` + `CalculatorTools` 包装成 MCP Server，用 stdio 传输。
2. 在 Claude Desktop 配置接入你的 MCP Server，验证能调用工具。
3. 把 13 篇的 RAG 系统暴露成 `rag_query` 工具，让 Claude Desktop 能用自然语言查你的知识库。
4. 实现一个 `Resource`：`db://schema/{table}`，返回表的字段定义。
5. 给你的 MCP Server 加 token 鉴权 + AOP 审计日志。
6. （进阶）用 GraalVM 编译成 Native Image，观察启动时间变化。
7. （选做）配置你的 Spring AI 应用同时作为 MCP Client（连别人的 Server）和 Server（暴露自己的能力）。

---

## 14. 理解检查

1. MCP 三类能力（Tools / Resources / Prompts）分别解决什么问题？
2. stdio / SSE / Streamable HTTP 各自适用场景？
3. 为什么 @Tool 注解可以同时用于进程内和 MCP Server？
4. MCP Server 上线前必须做的三件事是什么（鉴权 / 审计 / 限流）？
5. 怎么调试 MCP Server（inspector / 日志 / 监控）？
6. MCP 相比直接 REST API 的核心优势是什么？

---

## 15. 进 L2 下一篇之前的能力确认

完成本篇你应该能：

- [ ] 用 Spring AI 2.0 写一个最小 MCP Server
- [ ] 在 Claude Desktop 配置并验证连接
- [ ] 区分 Tools / Resources / Prompts 三类能力的适用场景
- [ ] 给 MCP Server 加鉴权、审计、限流
- [ ] 用 inspector 调试 MCP Server
- [ ] 解释 MCP 解决了什么问题、什么时候该用

---

## 16. 相关文档

- [`./02-初级-ToolCallingAdvisor.md`](./02-初级-ToolCallingAdvisor.md) —— @Tool 注解基础
- [`./17-安全工程与红队.md`](./17-安全工程与红队.md) —— MCP 安全深入
- [MCP 协议规范](https://modelcontextprotocol.io/) —— 官方协议文档
- [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot.html)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
