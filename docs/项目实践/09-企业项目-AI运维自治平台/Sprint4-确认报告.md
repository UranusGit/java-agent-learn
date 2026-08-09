# AIOps Sprint 4 · 确认、报告与部署（从最简版开始）

> **目标**：从"调查完直接打印"开始，一步步长成人工确认 + 多通道通知 + 自动报告
> **前置**：Sprint 1-3 全部完成

---

## V1：30 分钟——调查完直接打印结论

> **思路**：先不搞确认机制、不搞通知。调查完了直接输出结论文本。

### Step 1：直接输出

```java
@RestController
@RequestMapping("/api/investigate")
public class InvestigationController {

    private final InvestigationWorkflow workflow;

    /**
     * V1 极简版：调查完直接返回结论
     *
     * 问题：没有确认机制（Agent 直接就修了？）、没有通知、没有报告
     */
    @PostMapping("/{alertId}")
    public String investigate(@PathVariable String alertId) {
        var alert = alertService.findById(alertId);
        String conclusion = workflow.investigate(alert);
        return conclusion;
    }
}
```

```bash
curl -X POST http://localhost:8080/api/investigate/alert-123

# 返回纯文本：
# "根因：数据库连接池配置错误。建议：将连接池改回 100。"
```

> ✅ V1 的价值：能拿到调查结论。
>
> ❌ V1 的问题：谁都能看到结论但没通知到人；如果要修复，没人确认。

---

## V2：1 天——人工确认 + 邮件通知

> **V1 的问题**：修复操作没人确认、结论没有通知到人。
> **V2 的目标**：修复需要人工确认 + 邮件通知结论。

### Step 2.1：待确认队列

```java
package com.aiops.approval;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/**
 * V2：待确认队列
 *
 * Agent 调查完后，修复建议进入待确认队列。
 * 人确认后才执行。
 */
@Service
public class ApprovalService {

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    public PendingApproval submit(String investigationId, String action, String reason) {
        PendingApproval approval = new PendingApproval(
            UUID.randomUUID().toString(),
            investigationId, action, reason,
            ApprovalStatus.PENDING,
            Instant.now()
        );
        pending.put(approval.id(), approval);
        return approval;
    }

    public boolean approve(String approvalId) {
        PendingApproval a = pending.get(approvalId);
        if (a == null || a.status() != ApprovalStatus.PENDING) return false;

        a.setStatus(ApprovalStatus.APPROVED);
        // 执行修复
        executeAction(a.action());
        return true;
    }

    public boolean reject(String approvalId) {
        PendingApproval a = pending.get(approvalId);
        if (a == null) return false;
        a.setStatus(ApprovalStatus.REJECTED);
        return true;
    }

    public List<PendingApproval> getPending() {
        return pending.values().stream()
            .filter(a -> a.status() == ApprovalStatus.PENDING)
            .toList();
    }

    private void executeAction(String action) {
        System.out.println("✅ 执行修复：" + action);
    }

    public record PendingApproval(
        String id, String investigationId,
        String action, String reason,
        ApprovalStatus status, Instant createdAt
    ) {
        // mutable status
    }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
}
```

### Step 2.2：邮件通知

```java
@Service
public class EmailNotificationService {

    private final JavaMailSender mailSender;

    public void sendConclusion(String to, String subject, String conclusion) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("[AIOps] 调查完成：" + subject);
        msg.setText(conclusion);
        mailSender.send(msg);
    }

    public void sendApprovalRequest(String to, PendingApproval approval) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(to);
        msg.setSubject("[AIOps] 待确认：" + approval.action());
        msg.setText("""
            需要您确认以下操作：

            操作：%s
            原因：%s

            确认链接：http://aiops.example.com/approve/%s
            拒绝链接：http://aiops.example.com/reject/%s
            """.formatted(approval.action(), approval.reason(),
                approval.id(), approval.id()));
        mailSender.send(msg);
    }
}
```

### Step 2.3：整合流程

```java
@PostMapping("/{alertId}")
public String investigate(@PathVariable String alertId) {
    // 1. 调查
    String conclusion = workflow.investigate(alert);

    // 2. 邮件通知结论
    emailService.sendConclusion("oncall@example.com",
        "告警 " + alertId, conclusion);

    // 3. 如果有修复建议 → 进入待确认
    if (conclusion.contains("建议：")) {
        String action = extractAction(conclusion);
        var approval = approvalService.submit(alertId, action, conclusion);
        emailService.sendApprovalRequest("oncall@example.com", approval);
    }

    return conclusion;
}
```

> ✅ V2 的价值：修复有人确认、结论有邮件通知。
>
> ❌ V2 的问题：只有邮件通知（夜里没人看邮件）；报告是纯文本没有结构化。

---

## V3：2 天——多通道通知 + 结构化报告 + 部署

> **V2 的问题**：只有邮件、报告没结构化、没有 Docker 部署。
> **V3 的目标**：Slack/钉钉/SSE 多通道 + LLM 生成结构化事故报告 + Docker 全栈部署。

### Step 3.1：多通道通知

```java
@Service
public class NotificationService {

    /**
     * V3 新增：多通道通知
     *
     * V2 只有邮件，V3 支持 Slack/钉钉/SSE。
     */
    public void notify(String channel, String title, String content) {
        switch (channel) {
            case "slack" -> sendSlack(title, content);
            case "dingtalk" -> sendDingTalk(title, content);
            case "sse" -> sendSse(title, content);
            case "email" -> sendEmail(title, content);
        }
    }

    private void sendSlack(String title, String content) {
        // Slack Webhook
        slackClient.post("/webhook", Map.of(
            "text", "*" + title + "*\n" + content
        ));
    }

    private void sendDingTalk(String title, String content) {
        // 钉钉 Webhook
        dingTalkClient.post("/robot/send", Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("title", title, "text", content)
        ));
    }

    private void sendSse(String title, String content) {
        // SSE 推送到前端看板（单向推送，原生重连）
        sseEmitterManager.broadcast(Map.of("title", title, "content", content));
    }
}
```

### Step 3.2：结构化事故报告

```java
@Service
public class IncidentReportService {

    private final ChatClient chatClient;

    /**
     * V3 新增：LLM 生成结构化事故报告
     */
    public IncidentReport generate(InvestigationResult investigation) {
        String reportPrompt = """
            基于以下调查结果，生成一份结构化的事故报告。

            调查结果：
            %s

            输出 JSON 格式：
            {
              "summary": "一句话摘要",
              "rootCause": "根因",
              "timeline": [{"time": "...", "event": "..."}],
              "impact": "影响范围",
              "resolution": "解决方案",
              "actionItems": ["改进建议1", "改进建议2"],
              "mttaMinutes": 5,
              "mttrMinutes": 30
            }
            """.formatted(investigation.conclusion());

        String json = chatClient.prompt()
            .user(reportPrompt)
            .call().content();

        return parseReport(json);
    }

    public record IncidentReport(
        String summary, String rootCause,
        List<TimelineEntry> timeline,
        String impact, String resolution,
        List<String> actionItems,
        int mttaMinutes, int mttrMinutes
    ) {
        public record TimelineEntry(String time, String event) {}
    }
}
```

### Step 3.3：Docker Compose 全栈部署

```yaml
version: '3.8'
services:
  app:
    build: .
    ports: ["8083:8080"]
    environment:
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/aiops
      - TEMPORAL_HOST=temporal:7233
    depends_on: [postgres, temporal]

  temporal:
    image: temporalio/auto-setup:1.23
    ports: ["7233:7233", "8088:8088"]  # UI
    environment:
      - DB=postgresql
      - POSTGRES_USER=temporal
      - POSTGRES_PWD=temporal
      - POSTGRES_SEEDS=postgres

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: aiops
      POSTGRES_USER: aiops
      POSTGRES_PASSWORD: aiops
    ports: ["5436:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  prometheus:
    image: prom/prometheus
    ports: ["9093:9090"]

  loki:
    image: grafana/loki
    ports: ["3101:3100"]

  jaeger:
    image: jaegertracing/all-in-one
    ports: ["16688:16686"]

volumes:
  pgdata:
```

### Step 3.4：端到端测试

```bash
# 1. 启动
docker-compose up -d

# 2. 发告警
curl -X POST http://localhost:8083/api/alerts \
  -d '{"service":"order-service","severity":"critical","description":"error rate > 5%"}'

# 3. Agent 自主调查（ReAct + Temporal）
# → 收集数据 → 分析 → 生成结论

# 4. 通知发送
# → Slack/钉钉/SSE

# 5. 修复建议进入待确认
curl http://localhost:8083/api/approvals/pending

# 6. 确认修复
curl -X POST http://localhost:8083/api/approvals/{id}/approve

# 7. 生成事故报告
curl http://localhost:8083/api/reports/{investigationId}
```

> ✅ V3 的价值：多通道通知、结构化报告、全栈 Docker 部署。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 直接打印 | V2 确认+邮件 | V3 多通道+报告 |
|------|-----------|------------|-------------|
| **确认机制** | 无 | 待确认队列 | 同 V2 |
| **通知** | 无 | 邮件 | Slack/钉钉/WS |
| **报告** | 纯文本 | 纯文本 | LLM 结构化 |
| **部署** | 无 | 无 | Docker 全栈 |

---

## 项目总结 & 简历描述

```
AI 运维自治平台（AIOps）

采用 V1→V2→V3 演进式开发，构建企业级 AI SRE 平台：
- 告警自动分诊（P0-P3 分级）+ 四源工具集（日志/指标/Trace/变更）
- ReAct 多步推理引擎，结构化假设追踪
- Temporal Durable Execution，崩溃自动恢复调查过程
- MCP Hub 统一工具管理，动态注册发现
- 人工确认机制 + 多通道通知（Slack/钉钉/SSE）
- LLM 自动生成事故报告（MTTA/MTTR 量化）
```

---

## 验收检查

- [ ] V1：调查完能输出结论
- [ ] V2：修复有人确认、邮件能通知
- [ ] V3：多通道通知、结构化报告、Docker 部署

→ 返回 [项目实践总览](../00-项目实践总览.md)
