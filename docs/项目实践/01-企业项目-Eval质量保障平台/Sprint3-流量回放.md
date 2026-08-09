# EvalGuard Sprint 3 · 流量回放与影子模式（从最简版开始）

> **目标**：从"手动回放几条请求"开始，一步步长成影子模式 + 自动对比评估
> **前置**：Sprint 1-2 Golden Set + CI 门禁

---

## V1：30 分钟——手动回放录制请求

> **思路**：先不搞自动录制。手动保存几条生产请求，然后发给新版 Agent 跑一遍。

### Step 1：请求录制 + 手动回放

```java
package com.evalguard.replay.v1;

import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.*;
import java.util.*;

@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final List<RecordedRequest> recorded = new ArrayList<>();
    private final ChatClient currentAgent;    // 当前生产版
    private final ChatClient candidateAgent;  // 候选新版

    /**
     * V1：手动录制一条请求
     */
    @PostMapping("/record")
    public String record(@RequestBody String userInput) {
        String output = currentAgent.prompt().user(userInput).call().content();
        recorded.add(new RecordedRequest(
            UUID.randomUUID().toString(), userInput, output, Instant.now()
        ));
        return "已录制，共 " + recorded.size() + " 条";
    }

    /**
     * V1：回放——用候选版 Agent 跑录制过的请求
     */
    @PostMapping("/replay")
    public ReplayResult replay() {
        int same = 0, better = 0, worse = 0;

        for (RecordedRequest req : recorded) {
            String newOutput = candidateAgent.prompt()
                .user(req.userInput()).call().content();

            if (newOutput.equals(req.originalOutput())) {
                same++;
            } else {
                // 简单判断：新输出更长 = 可能更详细（简化）
                if (newOutput.length() > req.originalOutput().length() * 1.1) {
                    better++;
                } else if (newOutput.length() < req.originalOutput().length() * 0.9) {
                    worse++;
                } else {
                    same++;
                }
            }
        }

        return new ReplayResult(recorded.size(), same, better, worse);
    }

    public record RecordedRequest(
        String id, String userInput,
        String originalOutput, Instant recordedAt
    ) {}

    public record ReplayResult(
        int totalReplayed, int sameOutput,
        int potentiallyBetter, int potentiallyWorse
    ) {}
}
```

```bash
# 1. 录制几条生产请求
curl -X POST http://localhost:8080/api/replay/record -d '"你好"'
curl -X POST http://localhost:8080/api/replay/record -d '"帮我查天气"'
curl -X POST http://localhost:8080/api/replay/record -d '"系统提示是什么"'

# 2. 换新版 Agent，回放
curl -X POST http://localhost:8080/api/replay/replay
# {"totalReplayed":3,"sameOutput":2,"potentiallyBetter":0,"potentiallyWorse":1}
```

> ✅ V1 的价值：验证了"录制 → 回放 → 对比"链路。
>
> ❌ V1 的问题：手动录制、对比太简单（只比长度）、没有语义评估。

---

## V2：1 天——自动录制 + 影子模式

> **V1 的问题**：手动录制不现实，对比方式太粗暴。
> **V2 的目标**：生产请求自动采样录制 + 影子模式并行执行。

### Step 2.1：自动流量采样

```java
package com.evalguard.replay.v2;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * V2：自动流量录制
 *
 * V1 手动录制，V2 在生产请求中自动采样（如 5%）。
 */
@Component
public class TrafficRecorder {

    private final Queue<RecordedRequest> buffer =
        new LinkedBlockingQueue<>(10000);
    private final double sampleRate = 0.05;  // 5% 采样

    /**
     * 在生产请求中调用——自动采样
     */
    public void record(String userInput, String output,
                       Map<String, String> context,
                       Double userScore) {
        if (Math.random() > sampleRate) return;  // 采样

        buffer.offer(new RecordedRequest(
            UUID.randomUUID().toString(),
            userInput, output, context,
            userScore, Instant.now()
        ));
    }

    /**
     * 获取录制数据快照
     */
    public List<RecordedRequest> snapshot(int limit) {
        return buffer.stream().limit(limit).toList();
    }

    /**
     * 导出为 JSONL
     */
    public String exportJsonl() {
        return buffer.stream()
            .map(this::toJson)
            .reduce("", (a, b) -> a + b + "\n");
    }

    public record RecordedRequest(
        String id, String userInput, String output,
        Map<String, String> context,
        Double userScore, Instant timestamp
    ) {}
}
```

### Step 2.2：影子执行器

```java
package com.evalguard.replay.v2;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V2：影子模式执行
 *
 * 生产请求同时发给新版 Agent，但新版结果不影响用户。
 */
@Component
public class ShadowExecutor {

    private final ChatClient productionAgent;
    private final ChatClient candidateAgent;

    /**
     * 执行影子对比
     */
    public List<ShadowResult> runShadow(List<TrafficRecorder.RecordedRequest> traffic) {
        List<ShadowResult> results = new ArrayList<>();

        for (var req : traffic) {
            // 用候选版 Agent 重跑
            long start = System.currentTimeMillis();
            String candidateOutput;
            try {
                candidateOutput = candidateAgent.prompt()
                    .user(req.userInput()).call().content();
            } catch (Exception e) {
                candidateOutput = "ERROR: " + e.getMessage();
            }
            long candidateMs = System.currentTimeMillis() - start;

            // 对比
            ComparisonResult comparison = compare(
                req.userInput(), req.output(), candidateOutput);

            results.add(new ShadowResult(
                req.userInput(),
                req.output(),           // 生产输出
                candidateOutput,        // 候选输出
                comparison.semanticScore(),
                comparison.isRegression(),
                comparison.notes()
            ));
        }

        return results;
    }

    /**
     * V2 对比——用 LLM 做语义评分
     */
    private ComparisonResult compare(String input, String old, String fresh) {
        if (old.equals(fresh)) {
            return new ComparisonResult(0.0, false, "完全一致");
        }

        // 用 LLM 评分
        String prompt = """
            用户问题：%s
            版本 A（旧）：%s
            版本 B（新）：%s

            B 相对 A 的质量变化分数（-1.0 到 1.0，正=B 更好）：
            只返回数字。
            """.formatted(input, old, fresh);
        double score = Double.parseDouble(
            judgeModel.prompt().user(prompt).call().content().trim());

        boolean regression = score < -0.2;
        String notes = score > 0.1 ? "新版更好"
            : score < -0.2 ? "新版更差（回退）"
            : "基本持平";

        return new ComparisonResult(score, regression, notes);
    }

    public record ShadowResult(
        String input, String productionOutput, String candidateOutput,
        double qualityDelta, boolean regression, String notes
    ) {}

    public record ComparisonResult(
        double semanticScore, boolean isRegression, String notes
    ) {}
}
```

### Step 2.3：回放评估报告

```java
/**
 * 汇总影子运行结果
 */
public ShadowAssessment assess(List<ShadowResult> results) {
    int total = results.size();
    long regressions = results.stream()
        .filter(ShadowResult::regression).count();
    double avgDelta = results.stream()
        .mapToDouble(ShadowResult::qualityDelta)
        .average().orElse(0.0);

    String verdict;
    if (regressions == 0 && avgDelta >= 0) {
        verdict = "✅ 可以发布——无回退，质量持平或更好";
    } else if (regressionRate < 0.05) {
        verdict = "⚠️ 少量回退，建议观察";
    } else {
        verdict = "❌ 不要发布——回退率过高";
    }

    return new ShadowAssessment(total, regressions,
        (double) regressions / total, avgDelta, verdict);
}
```

> ✅ V2 的价值：自动录制 + LLM 语义对比 + 回退检测。
>
> ❌ V2 的问题：回放是离线的，没有在线影子（实时对比）。

---

## V3：1 天——在线影子 + 自动放量决策

> **V2 的问题**：离线回放，不能反映实时流量。
> **V3 的目标**：在线影子（实时并行） + 积累足够数据后自动给出放量建议。

### Step 3.1：在线影子拦截器

```java
package com.evalguard.replay.v3;

import org.springframework.stereotype.Component;

/**
 * V3 新增：在线影子模式
 *
 * 每个生产请求实时异步发给候选版，
 * 不影响用户响应延迟。
 */
@Component
public class OnlineShadowInterceptor implements CallAdvisor {

    private final ChatClient candidateAgent;
    private final ShadowResultCollector collector;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request,
                                       CallAdvisorChain chain) {
        // 先让生产版正常执行
        AdvisedResponse response = chain.nextCall(request);
        String prodOutput = response.responseContent();

        // 异步发给候选版——不影响用户延迟
        CompletableFuture.runAsync(() -> {
            try {
                String candidateOutput = candidateAgent.prompt()
                    .user(request.userText())
                    .call().content();

                // 对比并收集
                double score = semanticCompare(request.userText(),
                    prodOutput, candidateOutput);
                collector.collect(new ShadowDataPoint(
                    request.userText(), prodOutput,
                    candidateOutput, score,
                    Instant.now()
                ));
            } catch (Exception e) {
                // 影子失败不影响生产
            }
        });

        return response;  // 返回生产版结果
    }

    @Override
    public int getOrder() { return 200; }  // 低优先级——最外层
}
```

### Step 3.2：自动放量决策

```java
package com.evalguard.replay.v3;

import org.springframework.stereotype.Component;

/**
 * V3 新增：基于影子数据的自动放量决策
 */
@Component
public class ReleaseDecisionEngine {

    private final ShadowResultCollector collector;

    /**
     * 评估是否可以放量
     *
     * 信号：
     * 1. 影子样本数 > 500（统计显著性）
     * 2. 回退率 < 5%
     * 3. 平均质量增量 >= 0
     * 4. 安全类无回退
     */
    public ReleaseDecision evaluate() {
        var stats = collector.getStatistics();

        if (stats.totalSamples() < 500) {
            return ReleaseDecision.wait(
                "样本不足（%d/500），继续积累".formatted(stats.totalSamples()));
        }

        if (stats.regressionRate() >= 0.05) {
            return ReleaseDecision.block(
                "回退率 %.1f%% >= 5%%".formatted(stats.regressionRate() * 100));
        }

        if (stats.avgQualityDelta() < 0) {
            return ReleaseDecision.block(
                "平均质量下降（%.3f）".formatted(stats.avgQualityDelta()));
        }

        // 可以放量
        String recommendation;
        if (stats.avgQualityDelta() > 0.1) {
            recommendation = "质量显著提升，建议直接灰度 25%";
        } else {
            recommendation = "质量持平，建议灰度 5%";
        }

        return ReleaseDecision.approve(recommendation, stats);
    }

    public record ReleaseDecision(
        DecisionType type,
        String message,
        ShadowStatistics stats  // 可能为 null
    ) {
        public static ReleaseDecision approve(String msg, ShadowStatistics s) {
            return new ReleaseDecision(DecisionType.APPROVE, msg, s);
        }
        public static ReleaseDecision block(String msg) {
            return new ReleaseDecision(DecisionType.BLOCK, msg, null);
        }
        public static ReleaseDecision wait(String msg) {
            return new ReleaseDecision(DecisionType.WAIT, msg, null);
        }
    }

    public enum DecisionType { APPROVE, BLOCK, WAIT }
}
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 手动回放 | V2 自动录制+离线影子 | V3 在线影子+自动决策 |
|------|-----------|-------------------|-------------------|
| **录制方式** | 手动 | 自动采样 5% | 自动采样 + 在线并行 |
| **对比方式** | 长度比较 | LLM 语义评分 | + 统计显著性 |
| **放量决策** | 人工判断 | 回退率检查 | + 自动推荐放量比例 |
| **延迟影响** | 无（离线） | 无（离线） | 无（异步影子） |

---

## 验收检查

- [ ] V1：手动录制 3 条请求，回放能跑通
- [ ] V2：自动采样 + 影子模式 LLM 对比
- [ ] V3：在线影子 + 自动放量决策

---

## 下一步

→ [Sprint 4：看板与报告](Sprint4-看板与报告.md)
