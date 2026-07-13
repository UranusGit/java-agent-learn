# Spring AI 09 - MCP 接入实战

> 目标：从零跑通 MCP Client 消费第三方 Server，再用 `@McpTool` 暴露一个企业内部 MCP Server。
> 前置：已完成 [08-升级到SpringAI2.0](./08-升级到SpringAI2.0.md)（Spring AI 2.0 已 GA）。
>
> 理论基础：[`reference/生产化与运营/14-MCP协议与生态.md`](../../reference/生产化与运营/14-MCP协议与生态.md)

---

## 0. 本教程产出

完成本教程后，你会有：
1. 一个能消费官方 filesystem MCP Server 的 Spring AI 应用
2. 一个用 `@McpTool` 暴露企业能力的 MCP Server
3. 一个 Client + Server 闭环的可复用模板

---

## 1. Part 1：消费官方 filesystem MCP Server（30 分钟）

### 1.1 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-client-spring-boot-starter</artifactId>
</dependency>
```

### 1.2 application.yml

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

### 1.3 配置 MCP Server（src/main/resources/mcp-servers.json）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/tmp/ai-workspace"
      ]
    }
  }
}
```

**前置**：本机装 Node.js（npx 可用）+ 创建 `/tmp/ai-workspace` 目录。

### 1.4 测试调用

```java
@RestController
@RequiredArgsConstructor
class McpController {
    private final ChatClient chatClient;

    @PostMapping("/mcp-chat")
    String chat(@RequestBody String question) {
        return chatClient.prompt()
            .user(question)
            .call()
            .content();
        // filesystem 的 read_file / write_file / list_files 等 tool 自动注入
        // LLM 决定何时调用，应用代码无感
    }
}
```

**测试**：
```bash
curl -X POST http://localhost:8080/mcp-chat \
  -H "Content-Type: text/plain" \
  -d "在 /tmp/ai-workspace 下创建 hello.txt，内容是 Hello MCP"
```

LLM 会调用 filesystem 的 `write_file` tool，文件实际被创建。

### 1.5 查看效果

```bash
ls /tmp/ai-workspace/
# hello.txt
```

---

## 2. Part 2：暴露企业内部 MCP Server（1-2 小时）

### 2.1 场景

把"查询员工信息"暴露成 MCP Server，公司内任意 AI 应用都能消费（Spring AI / LangChain4j / Claude Desktop / Cursor）。

### 2.2 依赖

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-webmvc-spring-boot-starter</artifactId>
</dependency>
```

### 2.3 application.yml

```yaml
spring:
  ai:
    mcp:
      server:
        name: employee-mcp-server
        version: 1.0.0
        type: SYNC
        sse-message-endpoint: /mcp/messages
```

### 2.4 暴露 Tool

```java
@Component
@RequiredArgsConstructor
public class EmployeeMcpServer {

    private final EmployeeService service;

    @McpTool(description = "按姓名查员工基本信息")
    public Employee queryByName(String name) {
        return service.findByName(name);
    }

    @McpTool(description = "按部门列出所有员工")
    public List<Employee> listByDepartment(String department) {
        return service.findByDepartment(department);
    }

    @McpTool(description = "更新员工邮箱")
    public Employee updateEmail(Long id, String newEmail) {
        return service.updateEmail(id, newEmail);
    }
}
```

### 2.5 暴露 Resource

```java
@McpResource(uri = "employee://{id}", description = "获取员工详情")
public Employee getEmployeeResource(String id) {
    return service.findById(Long.valueOf(id));
}
```

### 2.6 暴露 Prompt

```java
@McpPrompt(description = "员工绩效评审模板")
public String performanceReview(String employeeName, String quarter) {
    return """
        请为 %s 同学生成 %s 绩效评审报告，包含：
        1. 关键产出
        2. 协作表现
        3. 改进建议
        """.formatted(employeeName, quarter);
}
```

### 2.7 启动验证

启动后访问 `http://localhost:8080/mcp/sse`，应看到 SSE 连接建立。用官方 inspector 验证：

```bash
npx @modelcontextprotocol/inspector http://localhost:8080/sse
```

---

## 3. Part 3：Client + Server 闭环（30 分钟）

### 3.1 新建 demo 项目，同时配 Client 和自己的 Server

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            employee-server:
              url: http://localhost:8081/sse
```

### 3.2 在 ChatClient 中消费

```java
@RestController
class OrchestratorController {
    private final ChatClient chatClient;

    @PostMapping("/ask")
    String ask(@RequestBody String question) {
        return chatClient.prompt()
            .user(question)
            .call()
            .content();
        // 框架自动从 employee-server 发现 tools，LLM 决定调用
    }
}
```

### 3.3 测试

```bash
curl -X POST http://localhost:8080/ask \
  -H "Content-Type: text/plain" \
  -d "查一下张三的邮箱"
```

LLM 会调用 `queryByName("张三")` 然后返回邮箱。

---

## 4. 生产化要点

### 4.1 鉴权

MCP 协议本身无鉴权（设计为应用级）。生产环境用 Spring Security 在 HTTP 层鉴权：

```java
@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain mcpFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/mcp/**")
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
    }
}
```

### 4.2 可观测性

Spring AI 2.0 已为 MCP 调用打 Micrometer span。配合 OpenTelemetry：

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: prometheus
```

### 4.3 错误处理

```java
@Component
class McpErrorHandler implements McpErrorAdvisor {
    @Override
    public ToolResult handleError(ToolCall call, Throwable ex) {
        // 不抛异常，返回结构化错误让 LLM 自我修复
        return ToolResult.error(ex.getMessage());
    }
}
```

---

## 5. 常见坑

### 5.1 npx 启动慢 / 失败

官方 MCP Server 都是 Node.js 包，首次 npx 下载慢。预热：
```bash
npx -y @modelcontextprotocol/server-filesystem /tmp/test
```

### 5.2 stdio vs SSE 选哪个

| Transport | 适用 | 优缺点 |
|-----------|------|--------|
| **stdio** | 本地工具（Claude Desktop 同机） | 简单，但必须本地起进程 |
| **SSE** | 远程服务、企业内部 | 跨网络，标准 HTTP/SSE |

企业内部 MCP Server 用 SSE，开发/本地工具用 stdio。

### 5.3 Tool 描述写得不好 LLM 不调用

MCP Tool 同 Function Calling，描述写不好 LLM 就不会调。**描述必须包含"何时用"+"输入格式"+"返回格式"**。

---

## 6. 验收清单

- [ ] Part 1：filesystem MCP Server 能被消费，LLM 能创建文件
- [ ] Part 2：employee-mcp-server 启动，inspector 能看到 3 个 Tool
- [ ] Part 3：闭环跑通，Client 应用能调用 Server 的 Tool
- [ ] 至少 1 个 MCP 调用被 Micrometer span 记录
- [ ] 能讲清 MCP 与传统 Function Calling 的区别

---

## 7. 下一步

- 把阶段 4 的所有 Tool 改造成 MCP Server（参考 [`reference/生产化与运营/14-MCP协议与生态.md` §7.3](../../reference/生产化与运营/14-MCP协议与生态.md)）
- 接入更多官方 Server（git / github / slack）
- 把企业内部 MCP Server 发布到 Maven 仓库供团队复用

---

## 8. 相关文档

- [`reference/生产化与运营/14-MCP协议与生态.md`](../../reference/生产化与运营/14-MCP协议与生态.md) —— MCP 理论与生态全景
- [08-升级到SpringAI2.0](./08-升级到SpringAI2.0.md) —— 升级前置
- [`reference/生产化与运营/12-ClaudeCode源码启示录.md`](../../reference/生产化与运营/12-ClaudeCode源码启示录.md) —— Claude Code 大量使用 MCP
