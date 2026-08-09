# AIOps Sprint 2 · SRE Agent 循环（从最简版开始）

> **目标**：从"Agent 回答一次就结束"开始，一步步长成多轮 ReAct 推理引擎
> **前置**：Sprint 1 工具集（日志/指标/Trace/变更）

---

## V1：30 分钟——单次问答

> **思路**：先不搞循环。Agent 收到告警 → 调一次工具 → 给一个回答。

### Step 1：单次调用

```java
package com.aiops.agent;

import org.springframework.stereotype.Service;

/**
 * V1 极简版：单次调用
 *
 * 问题：Agent 只调一次工具就给结论，不能多步调查
 * 但它验证了"告警→工具→分析"这条链路能跑通。
 */
@Service
public class SimpleSREAgent {

    private final ChatClient chatClient;
    private final LogQueryTool logTool;
    private final MetricQueryTool metricTool;

    public String investigate(Alert alert) {
        return chatClient.prompt()
            .system("""
                你是 SRE Agent。收到告警后用工具调查原因。
                可用工具：queryLogs, queryMetric
                """)
            .user("""
                告警：服务 %s 发生 %s
                请调查原因并给出初步判断。
                """.formatted(alert.service(), alert.description()))
            .tools(logTool, metricTool)
            .call().content();
    }
}
```

```bash
# 发一条告警
curl -X POST http://localhost:8080/api/alerts \
  -d '{"service":"order-service","description":"error rate > 5%"}'

# Agent 回答：
# "我查看了日志，发现数据库连接超时。建议检查数据库状态。"
```

> ✅ V1 的价值：验证了 Agent 能用工具做基本调查。
>
> ❌ V1 的问题：只调一次工具——"数据库连接超时"是症状，不是根因。Agent 需要继续追问。

---

## V2：2 天——多轮 ReAct 循环

> **V1 的问题**：一步到位，不深入。真实排障需要"发现→假设→验证→结论"多步推理。
> **V2 的目标**：实现 ReAct 循环——Agent 能多轮调查。

### Step 2.1：ReAct 循环引擎

```java
package com.aiops.agent;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * V2：ReAct 循环
 *
 * V1 只调用一次 LLM，V2 在循环中反复调用，
 * 让 Agent 每轮：Thought → Action（工具调用） → Observation → 下一步
 */
@Service
public class SREAgentLoop {

    private final ChatClient chatClient;
    private final List<Object> tools;  // logTool, metricTool, traceTool, changeTool

    private static final int MAX_STEPS = 10;  // 防止死循环

    public InvestigationResult investigate(Alert alert) {
        List<StepRecord> steps = new ArrayList<>();
        String accumulatedContext = "告警：服务 " + alert.service()
            + " 发生 " + alert.description();

        for (int step = 1; step <= MAX_STEPS; step++) {
            // 让 Agent 决定下一步
            String instruction = buildInstruction(accumulatedContext, step);

            String response = chatClient.prompt()
                .system(SRE_SYSTEM_PROMPT)
                .user(instruction)
                .tools(tools.toArray())
                .call().content();

            steps.add(new StepRecord(step, response));

            // 检查 Agent 是否认为调查完成
            if (response.contains("[CONCLUSION]")) {
                return new InvestigationResult(
                    alert, steps, extractConclusion(response), true
                );
            }

            accumulatedContext += "\n\nStep " + step + " 结果：\n" + response;
        }

        // 达到最大步数仍未完成
        return new InvestigationResult(alert, steps, "调查超时", false);
    }

    private String buildInstruction(String context, int step) {
        return """
            当前调查上下文：
            %s

            %s
            请使用工具继续调查，或在有足够证据时输出 [CONCLUSION]。
            """.formatted(context,
                step == 1 ? "请开始第一步调查。"
                         : "这是第 " + step + " 步，请根据之前的发现继续。");
    }

    private String extractConclusion(String response) {
        int idx = response.indexOf("[CONCLUSION]");
        return idx >= 0 ? response.substring(idx + 12).trim() : response;
    }

    private static final String SRE_SYSTEM_PROMPT = """
        你是 SRE Agent。你的任务是调查告警的根因。

        工作方式（ReAct）：
        1. Thought：分析当前信息，形成假设
        2. Action：调用工具验证假设
        3. Observation：根据工具返回结果更新判断
        4. 重复直到有足够证据

        输出格式：
        Thought: [你的推理]
        Action: [调用的工具或"不需要更多工具"]
        Observation: [工具返回的关键信息]

        调查完成时输出：
        [CONCLUSION]
        根因：...
        证据：...
        建议：...
        """;

    public record StepRecord(int step, String content) {}
    public record InvestigationResult(
        Alert alert, List<StepRecord> steps,
        String conclusion, boolean completed
    ) {}
}
```

### Step 2.2：调查示例

```java
@RestController
@RequestMapping("/api/investigate")
public class InvestigationController {

    private final SREAgentLoop agent;
    private final AlertTriageService triage;

    @PostMapping("/{alertId}")
    public SREAgentLoop.InvestigationResult investigate(@PathVariable String alertId) {
        var alert = triage.findById(alertId);
        return agent.investigate(alert);
    }
}
```

```bash
curl -X POST http://localhost:8080/api/investigate/alert-123

# 返回：
# {
#   "steps": [
#     {"step": 1, "content": "Thought: 先看错误日志\nAction: queryLogs...\nObservation: 数据库连接超时"},
#     {"step": 2, "content": "Thought: 查数据库指标\nAction: queryMetric...\nObservation: 连接数 200/200 满了"},
#     {"step": 3, "content": "Thought: 查最近变更\nAction: queryChanges...\nObservation: 连接池从 100 改成了 200"},
#     {"step": 4, "content": "[CONCLUSION]\n根因：连接池配置错误..."}
#   ],
#   "conclusion": "根因：连接池配置错误",
#   "completed": true
# }
```

> ✅ V2 的价值：Agent 能多步推理，深入到根因。
>
> ❌ V2 的问题：调查过程在内存里，服务崩了就丢了。

---

## V3：1 天——假设追踪器 + 安全护栏

> **V2 的问题**：Agent 的推理过程没有结构化记录；没有护栏防止 Agent 做危险操作。
> **V3 的目标**：结构化假设追踪 + SRE 专用安全约束。

### Step 3.1：假设追踪器

```java
package com.aiops.agent;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V3 新增：假设追踪器
 *
 * V2 的推理过程是纯文本，V3 结构化为：
 * Hypothesis（假设）→ Evidence（证据）→ Status（已确认/已排除）
 */
@Component
public class HypothesisTracker {

    private final List<Hypothesis> hypotheses = new ArrayList<>();

    public Hypothesis propose(String description, double initialConfidence) {
        Hypothesis h = new Hypothesis(
            UUID.randomUUID().toString(),
            description, initialConfidence,
            HypothesisStatus.INVESTIGATING,
            new ArrayList<>(),
            Instant.now()
        );
        hypotheses.add(h);
        return h;
    }

    public void addEvidence(String hypothesisId, String evidence, boolean supports) {
        hypotheses.stream()
            .filter(h -> h.id().equals(hypothesisId))
            .findFirst()
            .ifPresent(h -> {
                h.evidence().add(new Evidence(evidence, supports, Instant.now()));
                updateConfidence(h);
            });
    }

    public void confirm(String hypothesisId) {
        hypotheses.stream()
            .filter(h -> h.id().equals(hypothesisId))
            .findFirst()
            .ifPresent(h -> {
                h.setStatus(HypothesisStatus.CONFIRMED);
                // 排除其他竞争假设
                hypotheses.stream()
                    .filter(other -> other.id() != hypothesisId)
                    .filter(other -> other.status() == HypothesisStatus.INVESTIGATING)
                    .forEach(other -> other.setStatus(HypothesisStatus.REJECTED));
            });
    }

    private void updateConfidence(Hypothesis h) {
        long supporting = h.evidence().stream().filter(Evidence::supports).count();
        long total = h.evidence().size();
        if (total > 0) {
            double confidence = (double) supporting / total;
            h.setConfidence(confidence);
        }
    }

    public List<Hypothesis> getAll() { return new ArrayList<>(hypotheses); }

    public record Hypothesis(
        String id, String description, double confidence,
        HypothesisStatus status, List<Evidence> evidence, Instant createdAt
    ) {
        // mutable for updates
        private HypothesisStatus fStatus;
        private double fConfidence;
        // 简化实现——实际应该用可变字段
    }

    public record Evidence(String content, boolean supports, Instant timestamp) {}

    public enum HypothesisStatus { INVESTIGATING, CONFIRMED, REJECTED }
}
```

### Step 3.2：SRE 安全护栏

```java
@Component
public class SREGuardAdvisor implements CallAdvisor {

    /**
     * V3 新增：SRE 专用安全约束
     *
     * SRE Agent 比 普通 Agent 更危险——它有运维工具权限。
     * 严格禁止自动执行修复操作（只允许调查）。
     */
    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        String userInput = request.userText();

        // 检查 Agent 是否在尝试自动修复
        if (userInput.toLowerCase().matches(".*(restart|delete|deploy|scale).*")) {
            if (!userInput.contains("[HUMAN_APPROVED]")) {
                // 拦截危险操作
                return blockResponse("⚠️ SRE Agent 禁止自动执行修复操作。"
                    + "请只做调查和建议，修复操作需要人工确认。");
            }
        }

        return chain.nextCall(request);
    }

    @Override
    public int getOrder() { return -100; }
}
```

> ✅ V3 的价值：假设结构化追踪（可以回溯推理链）、安全护栏防止 Agent 擅自修复。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 单次 | V2 ReAct | V3 假设+护栏 |
|------|--------|---------|------------|
| **推理轮数** | 1 轮 | 多轮（≤10） | 多轮 |
| **过程记录** | 无 | 文本步骤 | 结构化假设 |
| **安全约束** | 无 | 无 | 禁止自动修复 |

---

## 验收检查

- [ ] V1：Agent 能调一次工具回答
- [ ] V2：ReAct 循环能多步深入到根因
- [ ] V3：假设能追踪确认/排除、护栏能拦危险操作

---

## 下一步

→ [Sprint 3：Durable + MCP](Sprint3-DurableMCP.md)
