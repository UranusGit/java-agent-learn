# MCP 协议与生态（Model Context Protocol）

> 一句话定位：**MCP 是 Anthropic 2024-11 提出的"AI 工具/资源/Prompt 的统一暴露协议"，正在成为跨框架复用的基础设施。Java 工程师投入 MCP 是当前最高 ROI 的单项投资。**
>
> 调研日期：2026-07-13。Spring AI 2.0 GA 已通过 MCP Java SDK 2.0 全面支持 MCP Client + Server。

---

## 1. MCP 是什么

### 1.1 一句话理解

**MCP = USB-C for AI Tools**。在 MCP 之前，每接一个工具（数据库/文件系统/Slack/Git）都要写专门的 Function Calling 包装；MCP 之后，工具按统一协议暴露，任何 MCP Client（Claude Desktop / Cursor / Spring AI / LangChain4j）都能消费。

### 1.2 Java 工程师的心智模型

| MCP 概念 | Java 类比 |
|---------|----------|
| **MCP Server** | Spring Boot 的 `@RestController`，暴露能力 |
| **MCP Client** | `RestTemplate` / `WebClient`，消费能力 |
| **Tool** | `@PostMapping` 的 endpoint，可被调用 |
| **Resource** | `@GetMapping` 的 endpoint，按 URI 读 |
| **Prompt** | 模板库（类似 Thymeleaf），按 ID 取 |
| **Transport** | stdio / SSE / HTTP，类似 RPC 的协议层 |

### 1.3 与传统 Function Calling 的关系

| 维度 | Function Calling（OpenAI 风格） | MCP |
|------|------------------------------|-----|
| 暴露方 | 应用代码内嵌 `@Tool` | 独立进程，按协议暴露 |
| 复用性 | 应用绑定，难复用 | 跨应用复用 |
| 协议 | 各家不同（OpenAI/Anthropic/Gemini） | 统一标准 |
| 工具发现 | 编译时静态 | 运行时动态（list_tools） |
| 跨语言 | 难（需桥接） | 原生（任何语言都能实现） |

---

## 2. MCP 三大能力

### 2.1 Tools（最常用）

```json
{
  "name": "query_employee",
  "description": "按姓名查员工信息",
  "inputSchema": {
    "type": "object",
    "properties": {
      "name": {"type": "string"}
    },
    "required": ["name"]
  }
}
```

LLM 看到工具列表，自主决定调用。等价于 Function Calling。

### 2.2 Resources（按需读）

```
resource://employee/12345
resource://docs/policy.md
```

URI 形式，LLM 通过读 resource 获取上下文。适合大文档/结构化数据，避免塞进 system prompt。

### 2.3 Prompts（模板库）

```json
{
  "name": "code_review",
  "description": "代码评审模板",
  "arguments": [
    {"name": "language", "required": true}
  ]
}
```

可复用的 prompt 模板，类似 Spring 的 `MessageTemplate`。

---

## 3. Spring AI 2.0 的 MCP 支持

### 3.1 依赖

```xml
<!-- MCP Client -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-client-spring-boot-starter</artifactId>
</dependency>

<!-- MCP Server（WebMVC） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-webmvc-spring-boot-starter</artifactId>
</dependency>
```

### 3.2 注解式暴露（Spring AI 2.0 独占优势）

```java
@Component
public class EmployeeMcpServer {

    @McpTool(description = "按姓名查员工")
    public Employee queryByName(String name) {
        return employeeService.findByName(name);
    }

    @McpResource(uri = "employee://{id}")
    public Employee getEmployee(String id) {
        return employeeService.findById(id);
    }

    @McpPrompt(description = "代码评审模板")
    public String codeReview(String language, String code) {
        return "请评审以下 %s 代码:\n%s".formatted(language, code);
    }
}
```

### 3.3 Client 端消费

```java
@Configuration
class McpClientConfig {
    @Bean
    McpSyncClient filesystemClient() {
        return McpClient.sync()
            .stdio("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
            .build();
    }
}

// Spring AI 自动把 MCP tools 注册到 ChatClient
// LLM 调用时透明走 MCP 协议
```

---

## 4. 官方/社区 MCP Server 生态（2026-07 快照）

| Server | 用途 | 来源 |
|--------|------|------|
| **filesystem** | 文件读写 | 官方 |
| **git** | Git 仓库操作 | 官方 |
| **github** | GitHub API | 官方 |
| **slack** | Slack 消息 | 官方 |
| **google-drive** | Drive 文件 | 官方 |
| **postgres** | 数据库查询 | 官方 |
| **puppeteer** | 浏览器自动化 | 官方 |
| **sequential-thinking** | 链式推理 | 官方 |
| **spring-ai-agent-utils** | Claude Code 同款（shell/grep/glob） | 社区（Spring AI 团队） |

完整列表：`github.com/modelcontextprotocol/servers`

---

## 5. 为什么是最高 ROI

### 5.1 ROI 五维分析

| 维度 | 评分 | 理由 |
|------|------|------|
| **学习成本** | ⭐⭐⭐⭐⭐ 低 | 协议简单，Spring AI 2.0 注解式封装 |
| **简历加分** | ⭐⭐⭐⭐⭐ 高 | 国内懂 MCP 的 Java 工程师极少 |
| **跨框架复用** | ⭐⭐⭐⭐⭐ 高 | 一次实现，Spring AI/LangChain4j/Koog 都能消费 |
| **未来潜力** | ⭐⭐⭐⭐⭐ 高 | Anthropic 主推，正在成为标准 |
| **生态成熟度** | ⭐⭐⭐ 中 | 2024-11 才发布，但官方/社区 Server 已近百 |

### 5.2 投入产出比

**投入**：1-2 周学习 + 实战（Spring AI 2.0 + MCP Java SDK）。
**产出**：
- 简历差异化亮点
- 企业内部 MCP Server 复用资产
- 押注未来"AI 工具市场"基础设施

---

## 6. MCP vs A2A（避免混淆）

| 协议 | 提出方 | 解决问题 | 成熟度 |
|------|-------|---------|--------|
| **MCP** | Anthropic | Tool/Resource/Prompt 暴露与消费 | ✅ Spring AI 2.0 GA |
| **A2A** | Google | Agent 跨进程通信 | ⚠️ 2-3 年才普及 |

**关键区别**：
- MCP 是"工具级"协议（应用 ↔ 工具）
- A2A 是"Agent 级"协议（Agent ↔ Agent）

**实战建议**：MCP 现在就投入，A2A 跟进不押注。

---

## 7. Java 工程师实战路线

### 7.1 阶段 1：消费方（1-2 天）
- [ ] 装 Claude Desktop / Cursor 体验官方 MCP Server
- [ ] Spring AI 2.0 接入 filesystem server
- [ ] 让 LLM 读写本地文件

### 7.2 阶段 2：生产方（3-5 天）
- [ ] 用 `@McpTool` 暴露一个企业内部能力（如查询工单系统）
- [ ] 写一个 MCP Client 消费自己的 Server，验证闭环
- [ ] 发布到企业内部 Maven 仓库

### 7.3 阶段 3：生态参与者（持续）
- [ ] 把常用 Tool 都按 MCP 协议重做
- [ ] 关注 MCP 规范迭代（每季度 review）
- [ ] 考虑开源自己的 MCP Server（简历加分）

---

## 8. 常见误区

### 8.1 ❌ 把 MCP 当 RPC 用

MCP 的核心价值是"LLM 友好"——描述清晰、Schema 标准、运行时发现。如果只是当 RPC 用，等于浪费它的全部优势。

### 8.2 ❌ 所有工具都改成 MCP Server

**应用内的工具**（仅供当前应用使用）继续用 `@Tool` 即可，无需 MCP。
**跨应用复用的工具**才需要 MCP Server。两者不冲突。

### 8.3 ❌ 等"标准"再投入

MCP 已被 Anthropic/OpenAI/Google 三家认可，Spring AI/LangChain 都已支持。**现在投入就是抢占先机**，等"完全成熟"再学反而落后。

---

## 9. 自检清单

- [ ] MCP 解决什么问题？与 Function Calling 的差异？
- [ ] MCP 三大能力（Tools/Resources/Prompts）分别是什么？
- [ ] 为什么说 MCP 是"USB-C for AI Tools"？
- [ ] Spring AI 2.0 的 `@McpTool`/`@McpResource`/`@McpPrompt` 怎么用？
- [ ] MCP vs A2A 的区别？为什么 A2A 暂不投入？
- [ ] 在你的企业里，哪个内部能力适合做成 MCP Server？

---

## 10. 相关文档

- [`选型与对比/10-SpringAI-vs-LangChain4j何时用何框架.md`](../选型与对比/10-SpringAI-vs-LangChain4j何时用何框架.md) —— MCP Server 是 Spring AI 独占优势
- [`生产化与运营/12-ClaudeCode源码启示录.md`](./12-ClaudeCode源码启示录.md) —— Claude Code 大量使用 MCP
- [`生产化与运营/11-LLMOps.md`](./11-LLMOps.md) —— MCP 接入的可观测性

---

## 11. 参考资料

1. **Model Context Protocol 规范** —— modelcontextprotocol.io
2. **MCP Java SDK 2.0** —— github.com/modelcontextprotocol/java-sdk
3. **Spring AI MCP Documentation** —— docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
4. **Anthropic MCP Launch Blog**（2024-11-25）
5. **MCP Servers 仓库** —— github.com/modelcontextprotocol/servers
