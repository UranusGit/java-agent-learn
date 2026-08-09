# AIOps Sprint 1 · 告警接入与 SRE 工具集（从最简版开始）

> **目标**：从"一个 webhook 接收告警"开始，一步步长成告警分诊 + 多源工具集
> **时间**：1 周

---

## V1：30 分钟——接收一条告警

> **思路**：先不搞分诊、不搞多个工具。最简单的 AIOps 就是接收一条告警并打印出来。

### Step 1：Webhook 接口

```java
package com.aiops.alert;

import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    /**
     * V1 极简版：接收告警，打印到控制台
     *
     * 问题：不分类、不存储、不触发任何动作
     * 但它验证了"告警能进来"这个最基本的链路。
     */
    @PostMapping
    public String receive(@RequestBody AlertPayload payload) {
        System.out.println("🚨 收到告警：");
        System.out.println("  服务：" + payload.service());
        System.out.println("  级别：" + payload.severity());
        System.out.println("  描述：" + payload.description());
        System.out.println("  时间：" + payload.timestamp());

        return "ACK";
    }

    public record AlertPayload(
        String service, String severity,
        String description, String timestamp
    ) {}
}
```

```bash
# 模拟一条告警
curl -X POST http://localhost:8080/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "service": "order-service",
    "severity": "critical",
    "description": "Error rate > 5%",
    "timestamp": "2026-08-09T10:30:00Z"
  }'

# 控制台输出：
# 🚨 收到告警：
#   服务：order-service
#   级别：critical
#   描述：Error rate > 5%
```

> ✅ V1 的价值：验证了告警接入链路。
>
> ❌ V1 的问题：告警进来就忘了、不区分严重程度、不能查任何数据。

---

## V2：1 天——告警分诊 + 第一个工具

> **V1 的问题**：告警进来就丢了，没有分级，Agent 没有工具可调。
> **V2 的目标**：存告警 + 自动分级 + 一个日志查询工具。

### Step 2.1：告警实体 + 自动分级

```java
package com.aiops.alert;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * V2：告警分诊
 *
 * V1 只打印不存储，V2 持久化 + 自动分级 P0-P3。
 */
@Service
public class AlertTriageService {

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    public Alert triage(Alert raw) {
        // 自动分级规则
        AlertSeverity severity = classify(raw);
        Alert alert = new Alert(
            UUID.randomUUID().toString(),
            raw.service(), raw.description(),
            severity,
            AlertStatus.OPEN,
            Instant.now()
        );
        alerts.add(alert);
        return alert;
    }

    private AlertSeverity classify(Alert raw) {
        String desc = raw.description().toLowerCase();
        // P0：服务完全不可用
        if (desc.contains("down") || desc.contains("unavailable")
            || desc.contains("OOM".toLowerCase())) {
            return AlertSeverity.P0;
        }
        // P1：错误率飙升 / 延迟翻倍
        if (desc.contains("error rate") || desc.contains("latency")
            || raw.severity().equalsIgnoreCase("critical")) {
            return AlertSeverity.P1;
        }
        // P2：资源使用率高
        if (desc.contains("cpu") || desc.contains("memory")
            || desc.contains("disk")) {
            return AlertSeverity.P2;
        }
        // P3：其他
        return AlertSeverity.P3;
    }

    public List<Alert> getOpenAlerts() {
        return alerts.stream()
            .filter(a -> a.status() == AlertStatus.OPEN)
            .toList();
    }

    public record Alert(
        String id, String service, String description,
        AlertSeverity severity, AlertStatus status, Instant createdAt
    ) {}

    public enum AlertSeverity { P0, P1, P2, P3 }
    public enum AlertStatus { OPEN, INVESTIGATING, RESOLVED }
}
```

### Step 2.2：第一个 SRE 工具——日志查询

```java
package com.aiops.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * V2：日志查询工具
 *
 * Agent 的第一个"眼睛"——能看日志才能诊断问题。
 */
@Component
public class LogQueryTool {

    /**
     * V2 先用模拟数据（后面 V3 再接真实的 Loki）
     */
    @Tool(description = "查询指定服务的最近日志。service 是服务名，"
        + "level 可选 ERROR/WARN/INFO，lines 是返回行数（默认50）。")
    public String queryLogs(String service, String level, int lines) {
        // V2 模拟——V3 会接 Loki
        if (level == null || level.isBlank()) level = "ERROR";
        if (lines <= 0) lines = 50;

        return String.format("""
            📋 %s 最近 %d 条 %s 日志（模拟）：
            [2026-08-09 10:31:22] ERROR %s - Connection refused: database.example.com:5432
            [2026-08-09 10:31:20] ERROR %s - Failed to execute query: timeout after 30s
            [2026-08-09 10:31:18] WARN  %s - Connection pool 90%% full
            """, service, lines, level, service, service, service);
    }
}
```

### Step 2.3：升级 Controller——分诊 + 工具

```java
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertTriageService triage;
    private final ChatClient chatClient;

    @PostMapping
    public AlertTriageService.Alert receive(@RequestBody AlertPayload payload) {
        // 1. 分诊
        var alert = triage.triage(payload);
        System.out.println("🚨 告警分诊完成：" + alert.severity());

        // 2. P0/P1 自动触发初步调查
        if (alert.severity() == AlertTriageService.AlertSeverity.P0
            || alert.severity() == AlertTriageService.AlertSeverity.P1) {
            String initialAnalysis = chatClient.prompt()
                .user("服务 " + alert.service() + " 告警：" + alert.description()
                    + "\n请先用 queryLogs 工具查看最近的错误日志。")
                .tools(logQueryTool)
                .call().content();
            System.out.println("🤖 初步分析：\n" + initialAnalysis);
        }

        return alert;
    }

    @GetMapping("/open")
    public List<AlertTriageService.Alert> open() {
        return triage.getOpenAlerts();
    }
}
```

```bash
# 发一条 P1 告警
curl -X POST http://localhost:8080/api/alerts \
  -d '{"service":"order-service","severity":"critical","description":"error rate > 5%"}'

# 控制台：
# 🚨 告警分诊完成：P1
# 🤖 初步分析：
#   我查看了 order-service 的日志，发现数据库连接超时...
```

> ✅ V2 的价值：告警自动分级 + Agent 能用工具查日志。
>
> ❌ V2 的问题：只有一个工具、查的是模拟数据。

---

## V3：2 天——多源工具集（真实集成）

> **V2 的问题**：只有一个日志工具，且是模拟数据。
> **V3 的目标**：接真实的 Prometheus + Loki + Jaeger，给 Agent 四只"眼睛"。

### Step 3.1：指标查询工具（真实 Prometheus）

```java
@Component
public class MetricQueryTool {

    private final RestClient prometheusClient;  // http://prometheus:9090

    @Tool(description = "查询 Prometheus 指标。query 是 PromQL 表达式，"
        + "如 'rate(http_requests_total{status=\"5xx\"}[5m])'")
    public String queryMetric(String query) {
        try {
            String result = prometheusClient.get()
                .uri("/api/v1/query?query={q}", query)
                .retrieve().body(String.class);

            return formatPrometheusResult(result);
        } catch (Exception e) {
            return "⚠️ 查询失败：" + e.getMessage();
        }
    }

    private String formatPrometheusResult(String json) {
        // 解析 Prometheus JSON 响应，格式化为人类可读
        // ...
        return json;
    }
}
```

### Step 3.2：Trace 查询工具（真实 Jaeger）

```java
@Component
public class TraceQueryTool {

    private final RestClient jaegerClient;  // http://jaeger:16686

    @Tool(description = "查询 Jaeger 链路追踪。service 是服务名，"
        + "查找错误或慢链路")
    public String queryTraces(String service) {
        try {
            String result = jaegerClient.get()
                .uri("/api/traces?service={svc}&limit=20", service)
                .retrieve().body(String.class);

            return analyzeTraces(result);
        } catch (Exception e) {
            return "⚠️ Trace 查询失败：" + e.getMessage();
        }
    }

    private String analyzeTraces(String json) {
        // 找出错误 trace 和慢 trace
        // ...
        return json;
    }
}
```

### Step 3.3：变更查询工具

```java
@Component
public class ChangeQueryTool {

    @Tool(description = "查询服务最近的变更（部署/配置修改）。"
        + "用于判断问题是否由最近变更引起。")
    public String queryRecentChanges(String service) {
        // 查 CI/CD 系统（Jenkins/GitLab CI）的部署记录
        // 或查 K8s ConfigMap 变更
        return String.format("""
            📦 %s 最近变更：
            - 2026-08-09 10:15 部署 v2.3.1 (镜像更新)
            - 2026-08-09 09:50 配置变更 (数据库连接池调整)
            """, service);
    }
}
```

### Step 3.4：升级 LogQueryTool（接真实 Loki）

```java
@Component
public class LogQueryTool {

    private final RestClient lokiClient;  // http://loki:3100

    @Tool(description = "查询服务日志。service 是服务名，level 是日志级别")
    public String queryLogs(String service, String level, int lines) {
        if (level == null || level.isBlank()) level = "ERROR";

        String logql = String.format("{app=\"%s\"} |= \"%s\"", service, level);

        try {
            String result = lokiClient.get()
                .uri("/loki/api/v1/query_range?query={q}&limit={l}",
                    logql, lines > 0 ? lines : 50)
                .retrieve().body(String.class);

            return formatLokiResult(result);
        } catch (Exception e) {
            return "⚠️ 日志查询失败：" + e.getMessage();
        }
    }
}
```

> ✅ V3 的价值：Agent 有四个真实数据源——日志+指标+Trace+变更，能做真正的诊断。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 Webhook | V2 分诊+日志 | V3 多源 |
|------|-----------|------------|--------|
| **告警** | 打印 | 分级+存储 | 分级+存储 |
| **工具数** | 0 | 1（模拟日志） | 4（真实集成） |
| **数据源** | 无 | 模拟 | Loki/Prometheus/Jaeger/CI |

---

## 验收检查

- [ ] V1：webhook 能接收告警
- [ ] V2：告警能自动分级、Agent 能用日志工具
- [ ] V3：四个工具都能查到真实数据

---

## 下一步

→ [Sprint 2：Agent 循环](Sprint2-Agent循环.md)
