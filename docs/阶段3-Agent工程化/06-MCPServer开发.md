# 06 · MCP Server 开发

> 阶段：3 Agent 工程化 · 难度：⭐⭐⭐⭐ · 预计：2-3 天
> 前置：[05 MCP 协议入门](05-MCP协议入门.md)
> 产出：开发自己的 MCP Server，把企业内部 API 暴露为标准协议

---

## 你将学会

- 用 Spring AI 开发 MCP Server
- 把一个 HTTP API 包装成 MCP Server
- 三种暴露风格：注解式 / Provider 式 / 原生式
- 为 MCP Server 加鉴权限流

---

## 为什么需要这个

上一篇你消费了别人的 MCP Server。现在你要**自己开发 MCP Server**——把企业内部系统（ERP/工单/CRM）的 API 标准化暴露出来。

**价值**：
- 一次实现，全公司的 Agent 都能消费
- 跨语言（Java 写的 Server，Python 的 Agent 也能调）
- 简历差异化亮点（国内懂 MCP 的 Java 工程师极少）

---

## 动手实践

### Step 1：创建 MCP Server

```java
package demo.demo03.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.mcp.server.autoconfigure.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public TicketTools ticketTools() {
        return new TicketTools();
    }
}

// 把企业工单系统的 API 暴露为 MCP Server
public class TicketTools {

    private final RestClient client = RestClient.create("https://api.internal.company.com");

    @Tool(description = "根据工单ID查询工单详情。ticketId 是工单编号。")
    public String getTicket(@ToolParam(description = "工单编号") String ticketId) {
        try {
            return client.get()
                    .uri("/tickets/" + ticketId)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            return "查询失败：" + e.getMessage();
        }
    }

    @Tool(description = "创建新工单。title 是标题，description 是描述，priority 是优先级(高/中/低)")
    public String createTicket(
            @ToolParam(description = "工单标题") String title,
            @ToolParam(description = "工单描述") String description,
            @ToolParam(description = "优先级：高/中/低") String priority) {
        try {
            return client.post()
                    .uri("/tickets")
                    .body(Map.of("title", title, "description", description, "priority", priority))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            return "创建失败：" + e.getMessage();
        }
    }
}
```

### Step 2：配置 MCP Server

```yaml
# application.yml
spring:
  ai:
    mcp:
      server:
        name: company-ticket-server
        version: 1.0.0
        type: SYNC   # 同步模式
        sse-message-endpoint: /mcp/message  # SSE 传输端点
```

### Step 3：启动并测试

```bash
# 启动 MCP Server（端口 8082）
mvn spring-boot:run

# 从另一个 Agent 项目消费这个 MCP Server
# 在 Agent 的 application.yml 中配置：
# spring.ai.mcp.client.sse.endpoints: http://localhost:8082/sse
```

### Step 4：加鉴权限流

```java
// MCP Server 端加 API Key 鉴权
@Component
public class McpAuthFilter {

    private static final String VALID_KEY = System.getenv("MCP_SERVER_KEY");

    public void authenticate(String requestKey) {
        if (!VALID_KEY.equals(requestKey)) {
            throw new SecurityException("无效的 MCP API Key");
        }
    }
}
```

---

## 验收检查

- [ ] 能开发一个 MCP Server 暴露至少 2 个工具
- [ ] MCP Server 能被 Agent 消费
- [ ] 有鉴权保护
- [ ] 能解释"MCP Server 的价值"（一次实现，跨框架消费）

---

## 下一步

→ 下一篇：[07 工具错误处理规范](07-工具错误处理规范.md)
