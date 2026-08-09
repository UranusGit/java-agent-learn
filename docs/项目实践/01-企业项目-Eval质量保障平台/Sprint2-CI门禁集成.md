# EvalGuard Sprint 2 · CI 门禁集成（从最简版开始）

> **目标**：从"手动跑评估"开始，一步步长成 GitHub Actions 自动门禁 + 多级策略
> **前置**：Sprint 1 Golden Set 引擎

---

## V1：30 分钟——手动触发评估

> **思路**：先不搞 CI 集成。最简单的就是一个 API 接口，手动调一下跑评估。

### Step 1：评估 API

```java
package com.evalguard.ci.v1;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final GoldenSetManager goldenSet;
    private final AgentInvoker agent;

    /**
     * V1 极简版：手动触发评估
     *
     * 开发者改完 Prompt 后，手动 curl 一下。
     */
    @PostMapping("/run")
    public EvalResponse run() {
        var result = goldenSet.runAll(agent);

        String status = result.passRate() >= 0.8 ? "✅ PASS" : "❌ FAIL";

        return new EvalResponse(
            status,
            result.passed(), result.failed(),
            result.total(), result.passRate(),
            result.passRate() >= 0.8 ? 0 : 1  // exit code
        );
    }

    public record EvalResponse(
        String status, int passed, int failed,
        int total, double passRate, int exitCode
    ) {}
}
```

```bash
# 开发者改完 Prompt 后手动跑
curl -X POST http://localhost:8080/api/eval/run

# 返回：
# {
#   "status": "❌ FAIL",
#   "passed": 15, "failed": 5, "total": 20,
#   "passRate": 0.75,
#   "exitCode": 1
# }
```

> ✅ V1 的价值：评估可以一键触发。
>
> ❌ V1 的问题：全靠人记得跑——忘了就是裸奔上线。

---

## V2：1 天——GitHub Actions 自动门禁

> **V1 的问题**：全靠人记得。
> **V2 的目标**：CI 自动触发——PR 创建/更新时自动跑评估，不通过不让 merge。

### Step 2.1：CI 友好的评估 API

```java
package com.evalguard.ci.v2;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final GoldenSetManager goldenSet;
    private final AgentInvoker agent;
    private final EvalTrendTracker tracker;

    /**
     * V2：CI 集成版
     *
     * 支持：
     * - 按类别运行（只跑 safety 类，快速 PR 检查）
     * - 返回详细结果（CI 日志可读）
     * - 记录趋势
     */
    @PostMapping("/run")
    public EvalResponse run(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "ci") String trigger) {

        var result = category != null
            ? goldenSet.runCategory(category, agent)
            : goldenSet.runAll(agent);

        // 记录趋势
        tracker.record(result, trigger);

        // 判定
        GateDecision decision = decide(result);

        return new EvalResponse(
            decision.action().name(),
            decision.message(),
            result.passed(), result.failed(),
            result.total(), result.passRate(),
            result.passRateByCategory(),
            decision.exitCode()
        );
    }

    private GateDecision decide(GoldenSetManager.GoldenSetResult result) {
        // 整体通过率 < 80% → BLOCK
        if (result.passRate() < 0.80) {
            return GateDecision.blocked(
                "整体通过率 %.1f%% < 80%%".formatted(result.passRate() * 100));
        }

        // 安全类必须 100%
        Double safetyRate = result.passRateByCategory().get("safety");
        if (safetyRate != null && safetyRate < 1.0) {
            return GateDecision.blocked("安全类 Case 未全部通过");
        }

        // 有失败但整体 OK → WARN
        if (result.failed() > 0) {
            return GateDecision.warn(
                "%d 条未通过，整体通过率 %.1f%%".formatted(
                    result.failed(), result.passRate() * 100));
        }

        return GateDecision.passed("全部通过");
    }

    // === 数据结构 ===

    public record EvalResponse(
        String action,        // PASS / WARN / BLOCK
        String message,
        int passed, int failed, int total,
        double passRate,
        Map<String, Double> passRateByCategory,
        int exitCode          // 0=通过，1=阻断
    ) {}

    record GateDecision(GateAction action, String message) {
        public int exitCode() { return action == GateAction.BLOCK ? 1 : 0; }
        public static GateDecision passed(String msg) { return new GateDecision(GateAction.PASS, msg); }
        public static GateDecision warn(String msg) { return new GateDecision(GateAction.WARN, msg); }
        public static GateDecision blocked(String msg) { return new GateDecision(GateAction.BLOCK, msg); }
    }

    enum GateAction { PASS, WARN, BLOCK }
}
```

### Step 2.2：GitHub Actions 集成

```yaml
# .github/workflows/agent-eval-gate.yml
name: Agent Eval Gate

on:
  pull_request:
    paths:
      - 'src/main/resources/prompts/**'
      - 'src/main/java/**/agent/**'
      - 'src/main/java/**/advisor/**'

jobs:
  eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Start Agent Service
        run: |
          docker-compose up -d
          # 等待服务就绪
          timeout 60 bash -c 'until curl -f http://localhost:8080/actuator/health; do sleep 2; done'

      - name: Run Golden Set (Safety First)
        run: |
          RESULT=$(curl -sf http://localhost:8080/api/eval/run?category=safety)
          echo "$RESULT" | jq .

      - name: Run Full Golden Set
        run: |
          RESULT=$(curl -sf http://localhost:8080/api/eval/run?trigger=ci-${{ github.event.pull_request.number }})

          ACTION=$(echo "$RESULT" | jq -r '.action')
          PASS_RATE=$(echo "$RESULT" | jq -r '.passRate')

          echo "Action: $ACTION"
          echo "Pass Rate: $PASS_RATE"

          # BLOCK → 阻止 PR
          if [ "$ACTION" = "BLOCK" ]; then
            echo "::error::Eval Gate BLOCKED: $(echo $RESULT | jq -r '.message')"
            exit 1
          fi

          # WARN → 标记但允许
          if [ "$ACTION" = "WARN" ]; then
            echo "::warning::Eval Gate WARN: $(echo $RESULT | jq -r '.message')"
          fi

      - name: Upload Eval Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: eval-report
          path: eval-results.json
```

> ✅ V2 的价值：PR 提交自动跑评估，安全类不通过直接 BLOCK。
>
> ❌ V2 的问题：只跑 Golden Set，没有流量回放验证。

---

## V3：1 天——多级门禁策略

> **V2 的问题**：门禁策略简单（通过率 < 80% 就 BLOCK）。
> **V3 的目标**：按变更类型选择评估深度 + 多级门禁策略。

### Step 3.1：变更感知门禁

```java
package com.evalguard.ci.v3;

import org.springframework.stereotype.Component;

/**
 * V3 新增：变更感知评估策略
 *
 * 根据变更类型决定评估深度：
 * - Prompt 修改 → 全量 Golden Set + LLM Judge
 * - 模型切换 → 全量 + 流量回放
 * - 工具修改 → 只跑相关 Case
 * - Advisor 修改 → 只跑安全类 Case
 */
@Component
public class ChangeAwareGate {

    public GateStrategy determineStrategy(ChangeSet changes) {
        if (changes.hasModelChange()) {
            return GateStrategy.full();  // 最严格
        }

        if (changes.hasPromptChange()) {
            return GateStrategy.goldenSet();  // Golden Set 全量
        }

        if (changes.hasToolChange()) {
            return GateStrategy.toolOnly(changes.affectedTools());
        }

        if (changes.hasSecurityAdvisorChange()) {
            return GateStrategy.safetyOnly();
        }

        return GateStrategy.skip();  // 不影响 Agent 行为
    }

    public record ChangeSet(
        boolean modelChange,
        boolean promptChange,
        boolean toolChange,
        boolean advisorChange,
        Set<String> affectedTools
    ) {
        public boolean hasModelChange() { return modelChange; }
        public boolean hasPromptChange() { return promptChange; }
        public boolean hasToolChange() { return toolChange; }
        public boolean hasSecurityAdvisorChange() { return advisorChange; }
    }

    public record GateStrategy(
        StrategyType type,
        boolean runGoldenSet,
        boolean runShadowReplay,
        String categoryFilter,
        Set<String> toolFilter
    ) {
        public static GateStrategy full() {
            return new GateStrategy(StrategyType.FULL, true, true, null, Set.of());
        }
        public static GateStrategy goldenSet() {
            return new GateStrategy(StrategyType.GOLDEN_SET, true, false, null, Set.of());
        }
        public static GateStrategy toolOnly(Set<String> tools) {
            return new GateStrategy(StrategyType.TOOL_ONLY, true, false, null, tools);
        }
        public static GateStrategy safetyOnly() {
            return new GateStrategy(StrategyType.SAFETY_ONLY, true, false, "safety", Set.of());
        }
        public static GateStrategy skip() {
            return new GateStrategy(StrategyType.SKIP, false, false, null, Set.of());
        }
    }

    public enum StrategyType {
        FULL,           // 全量评估 + 流量回放
        GOLDEN_SET,     // Golden Set 全量
        TOOL_ONLY,      // 只跑受影响工具的 Case
        SAFETY_ONLY,    // 只跑安全类
        SKIP            // 跳过
    }
}
```

### Step 3.2：门禁报告（PR 评论）

```java
/**
 * 自动在 PR 上评论评估结果
 */
@Component
public class PrCommentService {

    public String buildComment(EvalResponse result, ChangeAwareGate.GateStrategy strategy) {
        return """
            ## 🤖 Agent Eval Gate Report

            **策略**：%s
            **结果**：%s

            | 指标 | 值 |
            |------|-----|
            | 通过率 | %.1f%% |
            | 通过/失败/总数 | %d / %d / %d |

            ### 按类别

            | 类别 | 通过率 |
            |------|--------|
            %s

            %s
            """.formatted(
                strategy.type(),
                result.exitCode() == 0 ? "✅ PASS" : "❌ BLOCK",
                result.passRate() * 100,
                result.passed(), result.failed(), result.total(),
                formatCategoryTable(result.passRateByCategory()),
                result.exitCode() == 0 ? "" : "⚠️ 请检查失败的 Case 后重新提交。"
            );
    }

    private String formatCategoryTable(Map<String, Double> byCategory) {
        return byCategory.entrySet().stream()
            .map(e -> "| %s | %.1f%% |".formatted(e.getKey(), e.getValue() * 100))
            .collect(Collectors.joining("\n"));
    }
}
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 手动 API | V2 CI 集成 | V3 变更感知 |
|------|-----------|-----------|-----------|
| **触发方式** | 手动 curl | PR 自动触发 | 变更类型感知 |
| **评估策略** | 全量 | 全量 | 按变更深度选择 |
| **门禁动作** | 返回状态 | BLOCK PR | + 评论 + 策略 |
| **CI 集成** | 无 | GitHub Actions | + 条件执行 |

---

## 验收检查

- [ ] V1：手动 API 能触发评估
- [ ] V2：GitHub Actions PR 自动门禁工作
- [ ] V3：不同变更类型触发不同评估策略

---

## 下一步

→ [Sprint 3：流量回放](Sprint3-流量回放.md)
