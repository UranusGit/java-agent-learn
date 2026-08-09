# AgentOps Sprint 2 · 可视化（从最简版开始）

> **目标**：从"一个 JSON 接口"开始，一步步长成时间线 + Mermaid 调用图 + 统计面板
> **前置**：Sprint 1 历史持久化（V3 三层表结构）

---

## V1：20 分钟——一个 JSON 接口

> **思路**：先不写前端。把 Sprint 1 存的数据用 JSON 返回，用浏览器或 curl 就能看。

### Step 1：原始数据接口

```java
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryPersistenceService history;

    /**
     * V1 极简版：直接返回数据库原始数据
     *
     * 问题：JSON 没有格式化、工具调用和 LLM 调用混在一起、前端要自己解析
     * 但它是最快让"数据可见"的方式。
     */
    @GetMapping("/sessions/{sessionId}")
    public HistoryPersistenceService.SessionHistory getSession(
            @PathVariable String sessionId) {
        return history.getSessionHistory(sessionId);
    }
}
```

```bash
# 跑一下
curl http://localhost:8080/api/history/sessions/abc123 | jq

# {
#   "session": { "id": "abc123", "tenant_id": "tenant-a", ... },
#   "llmCalls": [
#     { "turn_number": 1, "model": "deepseek-chat",
#       "user_message": "帮我查天气",
#       "assistant_response": "我来帮你查...",
#       "prompt_tokens": 150, "completion_tokens": 80, ... }
#   ],
#   "toolCalls": [
#     { "tool_name": "get_weather", "input_params": {"city":"北京"}, ... }
#   ]
# }
```

> ✅ V1 的价值：5 行代码让数据可见。
>
> ❌ V1 的问题：原始 JSON 不直观，时间关系不清晰，没有统计。

---

## V2：1 天——时间线 + 统计

> **V1 的问题**：原始 JSON 看不出时间顺序、看不出工具调用和 LLM 调用的关系。
> **V2 的目标**：把数据组织成时间线格式 + 提供统计接口。

### Step 2.1：时间线服务

```java
package com.agentops.viz;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * V2：把三层表数据组织成时间线
 *
 * V1 返回的是三组分离的列表，V2 合并成一个按时间排序的时间线。
 */
@Service
public class TimelineService {

    private final HistoryPersistenceService history;

    /**
     * 构建时间线
     */
    public List<TimelineEntry> buildTimeline(String sessionId) {
        var sessionHistory = history.getSessionHistory(sessionId);

        List<TimelineEntry> timeline = new ArrayList<>();

        for (var llmCall : sessionHistory.llmCalls()) {
            timeline.add(new TimelineEntry(
                llmCall.createdAt(),
                "LLM_CALL",
                "Turn " + llmCall.turnNumber(),
                llmCall.model(),
                llmCall.latencyMs(),
                llmCall.promptTokens() + llmCall.completionTokens(),
                Map.of(
                    "user", llmCall.userMessage(),
                    "assistant", llmCall.assistantResponse()
                )
            ));

            sessionHistory.toolCalls().stream()
                .filter(tc -> tc.turnNumber() == llmCall.turnNumber())
                .forEach(tc -> timeline.add(new TimelineEntry(
                    tc.createdAt(),
                    "TOOL_CALL",
                    tc.toolName(),
                    tc.toolName(),
                    tc.latencyMs(),
                    0,
                    Map.of(
                        "input", tc.inputParams(),
                        "output", tc.outputResult(),
                        "success", tc.success()
                    )
                )));
        }

        timeline.sort(Comparator.comparing(TimelineEntry::timestamp));
        return timeline;
    }

    /**
     * V2 新增：工具使用统计
     */
    public Map<String, ToolStats> getToolStats(String sessionId) {
        var sessionHistory = history.getSessionHistory(sessionId);

        return sessionHistory.toolCalls().stream()
            .collect(Collectors.groupingBy(
                ToolCallRecord::toolName,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> new ToolStats(
                        list.size(),
                        list.stream().filter(ToolCallRecord::success).count(),
                        list.stream().mapToLong(ToolCallRecord::latencyMs).average().orElse(0)
                    )
                )
            ));
    }

    public record TimelineEntry(
        Instant timestamp, String type, String label,
        String source, long latencyMs, int tokens,
        Map<String, Object> details
    ) {}

    public record ToolStats(long totalCalls, long successCount, double avgLatencyMs) {
        public double successRate() { return (double) successCount / totalCalls; }
    }
}
```

### Step 2.2：时间线接口

```java
@RestController
@RequestMapping("/api/viz")
public class VisualizationController {

    private final TimelineService timelineService;

    @GetMapping("/timeline/{sessionId}")
    public List<TimelineEntry> timeline(@PathVariable String sessionId) {
        return timelineService.buildTimeline(sessionId);
    }

    @GetMapping("/stats/{sessionId}")
    public Map<String, TimelineService.ToolStats> stats(@PathVariable String sessionId) {
        return timelineService.getToolStats(sessionId);
    }
}
```

> ✅ V2 的价值：结构化时间线、工具统计。
>
> ❌ V2 的问题：还是 JSON，没有可视化界面。

---

## V3：2 天——HTML 时间线 + Mermaid 调用图

> **V2 的问题**：JSON 不直观。
> **V3 的目标**：HTML 时间线可视化 + Mermaid 自动生成调用关系图。

### Step 3.1：Mermaid 调用图生成

```java
@Service
public class MermaidGraphBuilder {

    /**
     * V3 新增：把时间线转换成 Mermaid 序列图
     */
    public String buildMermaidGraph(List<TimelineEntry> timeline) {
        StringBuilder sb = new StringBuilder();
        sb.append("sequenceDiagram\n");
        sb.append("    participant User\n");
        sb.append("    participant Agent\n");

        Set<String> tools = timeline.stream()
            .filter(e -> e.type().equals("TOOL_CALL"))
            .map(TimelineEntry::source)
            .collect(Collectors.toSet());
        tools.forEach(t -> sb.append("    participant ").append(t).append("\n"));

        for (var entry : timeline) {
            if (entry.type().equals("LLM_CALL")) {
                sb.append("    User->>Agent: Turn ").append(entry.label()).append("\n");
                sb.append("    Agent-->>User: ")
                  .append(truncate(entry.details().get("assistant").toString(), 50))
                  .append("\n");
            } else if (entry.type().equals("TOOL_CALL")) {
                sb.append("    Agent->>").append(entry.source()).append(": ")
                  .append(entry.details().get("input")).append("\n");
                boolean success = (boolean) entry.details().get("success");
                sb.append("    ").append(entry.source()).append("-->>Agent: ")
                  .append(success ? "✅ " : "❌ ")
                  .append(truncate(entry.details().get("output").toString(), 50))
                  .append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
```

### Step 3.2：HTML 可视化页面

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>Agent 调用可视化</title>
    <script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
    <style>
        body { margin: 0; padding: 20px; background: #0d1117; color: #c9d1d9;
               font-family: -apple-system, sans-serif; }
        .input-bar { display: flex; gap: 10px; margin-bottom: 20px; }
        input { background: #161b22; border: 1px solid #30363d; color: #c9d1d9;
                padding: 8px 12px; border-radius: 6px; flex: 1; }
        button { background: #238636; color: white; border: none; padding: 8px 20px;
                 border-radius: 6px; cursor: pointer; }
        .section { background: #161b22; border: 1px solid #30363d; border-radius: 8px;
                   padding: 20px; margin-bottom: 20px; }
        .section h3 { color: #58a6ff; font-size: 13px; text-transform: uppercase; }
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th { text-align: left; color: #8b949e; padding: 8px; border-bottom: 1px solid #30363d; }
        td { padding: 8px; border-bottom: 1px solid #21262d; }
        .timeline-item { padding: 10px; border-left: 3px solid #30363d; margin-bottom: 8px; }
        .llm-call { border-color: #58a6ff; }
        .tool-call { border-color: #3fb950; }
        .type-badge { font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: bold; }
        .badge-llm { background: #58a6ff22; color: #58a6ff; }
        .badge-tool { background: #3fb95022; color: #3fb950; }
    </style>
</head>
<body>
    <h1>📊 Agent 调用可视化</h1>
    <div class="input-bar">
        <input id="session-id" placeholder="输入 Session ID">
        <button onclick="load()">查看</button>
    </div>

    <div class="section">
        <h3>🔄 调用关系图</h3>
        <div id="mermaid-graph"></div>
    </div>
    <div class="section">
        <h3>⏱️ 时间线</h3>
        <div id="timeline"></div>
    </div>
    <div class="section">
        <h3>📈 工具统计</h3>
        <table id="stats-table">
            <thead><tr><th>工具</th><th>调用次数</th><th>成功率</th><th>平均延迟</th></tr></thead>
            <tbody></tbody>
        </table>
    </div>

    <script>
        mermaid.initialize({ startOnLoad: false, theme: 'dark' });

        async function load() {
            const sid = document.getElementById('session-id').value;
            const [timeline, stats, graph] = await Promise.all([
                fetch(`/api/viz/timeline/${sid}`).then(r => r.json()),
                fetch(`/api/viz/stats/${sid}`).then(r => r.json()),
                fetch(`/api/viz/mermaid/${sid}`).then(r => r.text())
            ]);

            document.getElementById('timeline').innerHTML = timeline.map(e => `
                <div class="timeline-item ${e.type === 'LLM_CALL' ? 'llm-call' : 'tool-call'}">
                    <span class="type-badge ${e.type === 'LLM_CALL' ? 'badge-llm' : 'badge-tool'}">
                        ${e.type === 'LLM_CALL' ? 'LLM' : 'TOOL'}
                    </span>
                    <strong>${e.label}</strong>
                    <span style="color:#8b949e">${e.latencyMs}ms</span>
                </div>
            `).join('');

            document.querySelector('#stats-table tbody').innerHTML =
                Object.entries(stats).map(([tool, s]) => `
                    <tr><td>${tool}</td><td>${s.totalCalls}</td>
                    <td>${(s.successRate * 100).toFixed(0)}%</td>
                    <td>${s.avgLatencyMs.toFixed(0)}ms</td></tr>
                `).join('');

            const { svg } = await mermaid.render('graph', graph);
            document.getElementById('mermaid-graph').innerHTML = svg;
        }
    </script>
</body>
</html>
```

> ✅ V3 的价值：HTML 可视化时间线、Mermaid 调用关系图、工具统计面板。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 JSON | V2 时间线 | V3 可视化 |
|------|---------|---------|----------|
| **展示** | 原始 JSON | 结构化时间线 | HTML + Mermaid |
| **统计** | 无 | 工具调用统计 | 可视化面板 |
| **调用图** | 无 | 无 | Mermaid 序列图 |

---

## 验收检查

- [ ] V1：JSON 接口能返回会话数据
- [ ] V2：时间线按时间排序、工具统计可用
- [ ] V3：HTML 页面能渲染时间线 + Mermaid 图

---

## 下一步

→ [Sprint 3：配置灰度](Sprint3-配置灰度.md)
