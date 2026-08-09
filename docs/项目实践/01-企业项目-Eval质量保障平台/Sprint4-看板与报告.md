# EvalGuard Sprint 4 · 看板与报告（从最简版开始）

> **目标**：从"终端打印"开始，一步步长成 Web 看板 + 趋势分析 + 自动告警
> **前置**：Sprint 1-3 评估引擎 + CI 门禁 + 流量回放

---

## V1：30 分钟——终端报告

> **思路**：先不搞前端。最简单的报告就是格式化输出到控制台。

### Step 1：格式化终端报告

```java
package com.evalguard.dashboard.v1;

import org.springframework.stereotype.Service;

/**
 * V1 极简版：终端格式化报告
 */
@Service
public class TerminalReportService {

    public String generate(GoldenSetManager.GoldenSetResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("╔════════════════════════════════════════╗\n");
        sb.append("║       Agent Eval Report                ║\n");
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append("║ Total: %-4d  Passed: %-4d  Failed: %-4d ║\n".formatted(
            result.total(), result.passed(), result.failed()));
        sb.append("║ Pass Rate: %s%-29s║\n".formatted(
            result.passRate() >= 0.8 ? "✅ " : "❌ ",
            String.format("%.1f%%", result.passRate() * 100)));
        sb.append("╠════════════════════════════════════════╣\n");

        // 按类别
        sb.append("║ By Category:                           ║\n");
        for (var entry : result.passRateByCategory().entrySet()) {
            sb.append("║   %-12s %s %-24s║\n".formatted(
                entry.getKey(),
                entry.getValue() >= 0.8 ? "✅" : "❌",
                String.format("%.1f%%", entry.getValue() * 100)));
        }

        sb.append("╠════════════════════════════════════════╣\n");

        // 失败详情
        if (result.failed() > 0) {
            sb.append("║ Failed Cases:                          ║\n");
            result.results().stream()
                .filter(r -> !r.passed())
                .forEach(r -> sb.append("║   ❌ [%s] %s...%.20s║\n".formatted(
                    r.category(), r.input(), r.input())));
        }

        sb.append("╚════════════════════════════════════════╝\n");

        return sb.toString();
    }
}
```

> ✅ V1 的价值：评估结果可读。
>
> ❌ V1 的问题：终端输出不直观、没有历史趋势、没有可视化。

---

## V2：1 天——Web API + HTML 看板

> **V1 的问题**：终端文本不直观。
> **V2 的目标**：REST API + 自动刷新的 HTML 看板。

### Step 2.1：看板 API

```java
package com.evalguard.dashboard.v2;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final GoldenSetManager goldenSet;
    private final EvalTrendTracker trendTracker;
    private final ShadowResultCollector shadowCollector;

    /**
     * 总览数据
     */
    @GetMapping("/overview")
    public OverviewData overview() {
        var latestResult = goldenSet.runAll(agent);
        var trend = trendTracker.getTrend(30);
        var shadowStats = shadowCollector.getStatistics();

        return new OverviewData(
            latestResult.total(),
            latestResult.passed(),
            latestResult.failed(),
            latestResult.passRate(),
            latestResult.passRateByCategory(),
            trend,
            shadowStats.totalSamples(),
            shadowStats.regressionRate()
        );
    }

    /**
     * 趋势数据（图表用）
     */
    @GetMapping("/trend")
    public List<TrendPoint> trend(@RequestParam(defaultValue = "30") int days) {
        return trendTracker.getTrend(days);
    }

    /**
     * 按类别详情
     */
    @GetMapping("/category/{category}")
    public CategoryDetail category(@PathVariable String category) {
        var result = goldenSet.runCategory(category, agent);
        return new CategoryDetail(category, result.total(),
            result.passed(), result.failed(),
            result.results().stream()
                .filter(r -> !r.passed())
                .toList()
        );
    }

    // === 数据结构 ===

    public record OverviewData(
        int totalCases, int passed, int failed,
        double passRate,
        Map<String, Double> passRateByCategory,
        List<EvalTrendTracker.TrendPoint> trend,
        int shadowSamples, double shadowRegressionRate
    ) {}

    public record CategoryDetail(
        String category, int total,
        int passed, int failed,
        List<GoldenSetManager.CaseResult> failedCases
    ) {}
}
```

### Step 2.2：HTML 看板

```html
<!DOCTYPE html>
<html>
<head>
    <title>EvalGuard Dashboard</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
</head>
<body>
    <h1>🛡️ EvalGuard Dashboard</h1>

    <div id="overview">
        <h2>总览</h2>
        <table border="1">
            <tr><th>指标</th><th>值</th></tr>
            <tr><td>通过率</td><td id="passRate">-</td></tr>
            <tr><td>通过/失败/总数</td><td id="counts">-</td></tr>
            <tr><td>影子回退率</td><td id="shadowRate">-</td></tr>
        </table>
    </div>

    <div>
        <h2>质量趋势（30 天）</h2>
        <canvas id="trendChart" width="600" height="200"></canvas>
    </div>

    <div>
        <h2>按类别</h2>
        <table border="1">
            <thead>
                <tr><th>类别</th><th>通过率</th></tr>
            </thead>
            <tbody id="categoryTable"></tbody>
        </table>
    </div>

    <script>
    // 自动刷新
    async function refresh() {
        const res = await fetch('/api/dashboard/overview');
        const data = await res.json();

        document.getElementById('passRate').textContent =
            (data.passRate * 100).toFixed(1) + '%';
        document.getElementById('counts').textContent =
            `${data.passed} / ${data.failed} / ${data.totalCases}`;
        document.getElementById('shadowRate').textContent =
            (data.shadowRegressionRate * 100).toFixed(1) + '%';

        // 类别表格
        const tbody = document.getElementById('categoryTable');
        tbody.innerHTML = '';
        for (const [cat, rate] of Object.entries(data.passRateByCategory)) {
            tbody.innerHTML += `<tr><td>${cat}</td><td>${(rate*100).toFixed(1)}%</td></tr>`;
        }

        // 趋势图
        const trendRes = await fetch('/api/dashboard/trend?days=30');
        const trend = await trendRes.json();
        renderTrendChart(trend);
    }

    function renderTrendChart(trend) {
        const ctx = document.getElementById('trendChart');
        new Chart(ctx, {
            type: 'line',
            data: {
                labels: trend.map(t => t.date),
                datasets: [{
                    label: 'Pass Rate',
                    data: trend.map(t => t.avgPassRate * 100),
                    borderColor: 'rgb(75, 192, 192)'
                }]
            }
        });
    }

    refresh();
    setInterval(refresh, 30000);  // 30 秒刷新
    </script>
</body>
</html>
```

> ✅ V2 的价值：可视化看板 + 自动刷新。
>
> ❌ V2 的问题：没有告警、没有深入分析。

---

## V3：1 天——告警 + 深入分析

> **V2 的问题**：只看数据，没人盯着看板。
> **V3 的目标**：异常自动告警 + 按类别深入分析。

### Step 3.1：质量告警

```java
package com.evalguard.dashboard.v3;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V3 新增：质量异常自动告警
 */
@Component
public class QualityAlertService {

    /**
     * 每小时检查一次
     */
    @Scheduled(fixedRate = 3600_000)
    public void checkQuality() {
        // 1. 检测回退
        Optional<String> regression = trendTracker.detectRegression();
        regression.ifPresent(msg ->
            alertService.notify("QUALITY_REGRESSION", msg));

        // 2. 检查安全类
        var safetyResult = goldenSet.runCategory("safety", agent);
        if (safetyResult.failed() > 0) {
            alertService.page("🚨 安全类 Case 失败 %d 条！".formatted(
                safetyResult.failed()));
        }

        // 3. 影子模式回退
        var shadowStats = shadowCollector.getStatistics();
        if (shadowStats.regressionRate() > 0.1) {
            alertService.notify("SHADOW_REGRESSION",
                "影子模式回退率 %.1f%%".formatted(
                    shadowStats.regressionRate() * 100));
        }
    }
}
```

### Step 3.2：Docker 部署

```yaml
version: '3.8'
services:
  evalguard:
    build: .
    ports: ["8090:8080"]
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/evalguard
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
    depends_on: [postgres]

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: evalguard
      POSTGRES_USER: evalguard
      POSTGRES_PASSWORD: evalguard
    ports: ["5438:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

volumes:
  pgdata:
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 终端 | V2 Web 看板 | V3 告警+分析 |
|------|--------|-----------|------------|
| **展示** | 控制台文本 | HTML + 图表 | + 告警通知 |
| **刷新** | 手动触发 | 30s 自动 | 实时 + 定时 |
| **趋势** | 无 | 30 天折线图 | + 回退检测 |
| **部署** | 无 | 无 | Docker Compose |

---

## 项目总结 & 简历描述

```
Agent 评估质量保障平台（EvalGuard）

采用 V1→V2→V3 演进式开发，构建 AI 时代的 CI 门禁系统：
- 分类化 Golden Set 管理（routine/edge-case/safety/regression）
- LLM as Judge 多维度评估（事实准确/完整/安全/格式/语气）
- GitHub Actions CI 自动门禁（变更感知策略 + 安全一票否决）
- 流量录制回放 + 在线影子模式 + 自动放量决策
- 质量趋势看板 + 异常自动告警
```

---

## 验收检查

- [ ] V1：终端格式化报告可读
- [ ] V2：Web 看板能展示总览 + 趋势 + 类别
- [ ] V3：告警能自动触发，Docker 能一键部署

→ 返回 [项目实践总览](../00-项目实践总览.md)
