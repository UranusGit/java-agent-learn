# AIOps Sprint 3 · Durable Execution + MCP Hub（从最简版开始）

> **目标**：从"调查过程存数据库"开始，一步步长成 Temporal 持久化 + MCP Hub 统一工具管理
> **前置**：Sprint 2 Agent 循环

---

## V1：30 分钟——把调查过程存数据库

> **思路**：先不搞 Temporal。最简单的"持久化"就是把调查步骤存到数据库，崩了能查到之前查到哪了。

### Step 1：调查记录表 + 检查点

```sql
CREATE TABLE investigations (
    id              VARCHAR(64) PRIMARY KEY,
    alert_id        VARCHAR(64) NOT NULL,
    service         VARCHAR(64) NOT NULL,
    status          VARCHAR(32) DEFAULT 'IN_PROGRESS',  -- IN_PROGRESS / COMPLETED / FAILED
    current_step    INT DEFAULT 0,
    context_snapshot TEXT,      -- JSON：当前调查上下文快照
    conclusion      TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
```

```java
package com.aiops.durable;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * V1 极简版：数据库检查点
 *
 * 问题：需要手动管理 checkpoint、恢复逻辑自己写
 * 但它验证了"调查过程可以持久化和恢复"。
 */
@Service
public class SimpleCheckpointService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 保存检查点
     */
    public void checkpoint(String investigationId, int step, String context) {
        jdbc.update("""
            UPDATE investigations
            SET current_step = ?, context_snapshot = ?, updated_at = NOW()
            WHERE id = ?
            """, step, context, investigationId);
    }

    /**
     * 恢复调查
     */
    public InvestigationState resume(String investigationId) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM investigations WHERE id = ?", investigationId);

        return new InvestigationState(
            (String) row.get("id"),
            (int) row.get("current_step"),
            (String) row.get("context_snapshot"),
            (String) row.get("status")
        );
    }

    public record InvestigationState(
        String id, int currentStep,
        String contextSnapshot, String status
    ) {}
}
```

### Step 2：Agent 循环集成的检查点

```java
@Service
public class CheckpointedAgentLoop {

    private final SREAgentLoop agent;
    private final SimpleCheckpointService checkpoint;

    public InvestigationResult investigate(Alert alert) {
        String investigationId = createInvestigation(alert);

        // 每完成一步就 checkpoint
        for (int step = 1; step <= 10; step++) {
            var result = agent.investigateStep(alert, step);

            // 保存检查点
            checkpoint.checkpoint(investigationId, step, result.context());

            // 如果崩溃了，下次从这里恢复
            // resume(investigationId) → 从 step 继续
        }

        return finalResult;
    }
}
```

> ✅ V1 的价值：调查过程可持久化、可恢复。
>
> ❌ V1 的问题：手动管理 checkpoint 很繁琐、恢复逻辑复杂、不优雅。

---

## V2：2 天——Temporal Durable Execution

> **V1 的问题**：手动 checkpoint，代码侵入性大。
> **V2 的目标**：用 Temporal 自动管理持久化——崩溃自动恢复，代码里不需要手写 checkpoint。

### Step 2.1：Workflow 定义

```java
package com.aiops.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * V2：Temporal Workflow
 *
 * Temporal 自动持久化每一步——你的代码看起来像同步代码，
 * 但它能在任何时刻崩溃并在恢复后继续执行。
 */
@WorkflowInterface
public interface InvestigationWorkflow {

    @WorkflowMethod
    String investigate(AlertPayload alert);
}

@ActivityInterface
public interface InvestigationActivities {

    @ActivityMethod
    String collectData(String service);          // 收集数据

    @ActivityMethod
    String analyzeData(String data);             // 分析数据

    @ActivityMethod
    String generateConclusion(String analysis);  // 生成结论
}
```

### Step 2.2：Workflow 实现

```java
public class InvestigationWorkflowImpl implements InvestigationWorkflow {

    private final InvestigationActivities activities =
        Workflow.newActivityStub(InvestigationActivities.class,
            ActivityOptions.newBuilder()
                .setStartTimeout(Duration.ofSeconds(5))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setMaximumAttempts(3).build())
                .build());

    @Override
    public String investigate(AlertPayload alert) {
        // Temporal 保证这些步骤会按顺序执行，
        // 即使进程崩溃，重启后从上一步继续。

        // Step 1
        String data = activities.collectData(alert.service());

        // Step 2
        String analysis = activities.analyzeData(data);

        // Step 3
        String conclusion = activities.generateConclusion(analysis);

        return conclusion;
    }
}
```

### Step 2.3：Activity 实现

```java
@Component
public class InvestigationActivitiesImpl implements InvestigationActivities {

    private final ChatClient chatClient;
    private final LogQueryTool logTool;
    private final MetricQueryTool metricTool;

    @Override
    public String collectData(String service) {
        // 调用工具收集数据
        String logs = logTool.queryLogs(service, "ERROR", 50);
        String metrics = metricTool.queryMetric("rate(http_requests_total{app=\"" + service + "\"}[5m])");
        return logs + "\n" + metrics;
    }

    @Override
    public String analyzeData(String data) {
        return chatClient.prompt()
            .system("你是 SRE。分析以下数据找出根因。")
            .user(data)
            .call().content();
    }

    @Override
    public String generateConclusion(String analysis) {
        return chatClient.prompt()
            .system("基于分析结果生成结构化的根因结论。")
            .user(analysis)
            .call().content();
    }
}
```

### Step 2.4：崩溃恢复测试

```bash
# 1. 启动调查（Temporal 会跟踪状态）
curl -X POST http://localhost:8080/api/investigate/durable \
  -d '{"alertId":"alert-123","service":"order-service"}'
# 返回 workflowId

# 2. 在调查进行中 kill 进程
kill -9 <pid>

# 3. 重启服务
# Temporal 自动恢复，从上一步继续执行

# 4. 查看结果——调查完成了！
curl http://localhost:8080/api/investigate/durable/{workflowId}/result
```

> ✅ V2 的价值：代码无需手写 checkpoint，崩溃自动恢复。
>
> ❌ V2 的问题：工具是直接注入的，没有统一管理。

---

## V3：2 天——MCP Hub 统一工具管理

> **V2 的问题**：工具直接硬编码在 Activity 里，加一个工具要改代码。
> **V3 的目标**：用 MCP Hub 统一管理工具——工具作为 MCP Server 注册，动态发现。

### Step 3.1：MCP Hub

```java
package com.aiops.mcp;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V3：MCP Hub
 *
 * V2 工具硬编码，V3 支持动态注册和发现。
 * 每个数据源是一个 MCP Server，Hub 统一管理。
 */
@Component
public class McpHub {

    private final Map<String, McpServerRegistration> servers = new ConcurrentHashMap<>();

    /**
     * 注册一个 MCP Server
     */
    public void register(McpServerRegistration reg) {
        servers.put(reg.name(), reg);
        System.out.println("[MCP Hub] 注册：" + reg.name()
            + " tools=" + reg.tools());
    }

    /**
     * 发现所有可用工具
     */
    public List<McpToolInfo> discoverTools() {
        return servers.values().stream()
            .filter(McpServerRegistration::enabled)
            .flatMap(s -> s.tools().stream()
                .map(t -> new McpToolInfo(s.name(), t)))
            .toList();
    }

    /**
     * 代理调用工具
     */
    public String callTool(String toolName, Map<String, Object> params) {
        McpServerRegistration server = findByTool(toolName);
        if (server == null) throw new IllegalArgumentException("工具不存在：" + toolName);

        // 健康检查
        if (!isHealthy(server)) {
            throw new RuntimeException("MCP Server 不可用：" + server.name());
        }

        // 代理调用
        return proxyCall(server, toolName, params);
    }

    private McpServerRegistration findByTool(String toolName) {
        return servers.values().stream()
            .filter(s -> s.tools().contains(toolName))
            .findFirst().orElse(null);
    }

    private boolean isHealthy(McpServerRegistration server) {
        // TCP/HTTP 健康检查
        return true; // 简化
    }

    private String proxyCall(McpServerRegistration server,
                            String toolName, Map<String, Object> params) {
        // 通过 HTTP/gRPC 调用 MCP Server
        // ...
        return "result";
    }

    public record McpServerRegistration(
        String name, String endpoint,
        List<String> tools, boolean enabled
    ) {}

    public record McpToolInfo(String serverName, String toolName) {}
}
```

### Step 3.2：配置 MCP Servers

```yaml
aiops:
  mcp:
    servers:
      - name: prometheus-mcp
        endpoint: http://prometheus-mcp:8090
        tools: [queryMetric, queryRange, alertStatus]
        enabled: true

      - name: loki-mcp
        endpoint: http://loki-mcp:8091
        tools: [queryLogs, tailLogs]
        enabled: true

      - name: jaeger-mcp
        endpoint: http://jaeger-mcp:8092
        tools: [queryTraces, getTrace]
        enabled: true

      - name: deploy-mcp
        endpoint: http://deploy-mcp:8093
        tools: [queryChanges, rollback]
        enabled: true
```

```java
@PostConstruct
public void init() {
    // 启动时自动注册所有 MCP Servers
    for (var serverConfig : mcpConfig.getServers()) {
        mcpHub.register(new McpHub.McpServerRegistration(
            serverConfig.getName(),
            serverConfig.getEndpoint(),
            serverConfig.getTools(),
            serverConfig.isEnabled()
        ));
    }
}
```

> ✅ V3 的价值：工具动态发现，加工具不改代码，只需注册新的 MCP Server。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 手动检查点 | V2 Temporal | V3 MCP Hub |
|------|-------------|------------|-----------|
| **持久化** | 手动存 DB | 自动（Temporal） | 自动 |
| **崩溃恢复** | 手动 resume | 自动 | 自动 |
| **工具管理** | 硬编码注入 | 硬编码注入 | 动态注册发现 |
| **代码侵入** | 高（每步 checkpoint） | 低（像同步代码） | 低 |

---

## 验收检查

- [ ] V1：调查步骤能存数据库和恢复
- [ ] V2：Temporal 能崩溃自动恢复
- [ ] V3：MCP Hub 能动态发现工具

---

## 下一步

→ [Sprint 4：确认报告](Sprint4-确认报告.md)
