# Agent 异常检测与自治修复

> **一句话**：Agent 上线后最大的问题不是"挂了"——是"没挂但开始输出垃圾"——这种隐性异常比显性崩溃危险 100 倍。

---

## Agent 异常分类

```mermaid
mindmap
  root((Agent 异常))
    显性异常
      服务崩溃
        OOM / 线程池满
      API 报错
        429 限流 / 500 服务器错误
      超时
        LLM 响应超时 / 工具调用超时
    隐性异常 ★危险
      质量退化
        回答跑题 / 幻觉增加
      行为漂移
        工具调用频率异常变化
      成本飙升
        Token 消耗突然增长 3x
      安全退化
        开始输出有害内容
      循环异常
        Agent 开始重复相同操作
```

**显性异常**容易被监控发现（5xx 率、超时率）。

**隐性异常**是 Agent 独有的——模型没有挂，但输出质量在悄悄下降。如果不做专门检测，可能几天后才发现。

---

## 异常检测架构

```mermaid
flowchart TD
    Metrics["实时指标流"] --> Detectors{"异常检测器组"}

    Detectors --> D1["统计检测器<br/>均值/方差突变"]
    Detectors --> D2["趋势检测器<br/>缓慢退化"]
    Detectors --> D3["规则检测器<br/>硬阈值告警"]
    Detectors --> D4["ML 检测器<br/>异常聚类"]

    D1 --> Alert{"异常？"}
    D2 --> Alert
    D3 --> Alert
    D4 --> Alert

    Alert -->|"是"| Severity{"严重程度"}
    Severity -->|"LOW"| Notify["通知值班人"]
    Severity -->|"MEDIUM"| AutoFix["自动修复尝试"]
    Severity -->|"HIGH"| Page["立即告警 + 降级"]

    AutoFix --> Result{"修复成功？"}
    Result -->|"是"| Resolved["恢复正常"]
    Result -->|"否"| Page

    style Page fill:#f44336,color:#fff
    style AutoFix fill:#ff9800,color:#fff
    style Resolved fill:#4caf50,color:#fff
```

---

## 核心实现

### 1. 多维度异常检测器

```java
package com.enterprise.anomaly;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 异常检测中心
 *
 * 多维度实时检测 Agent 的隐性和显性异常
 */
@Component
public class AnomalyDetectionCenter {

    private final List<AnomalyDetector> detectors;
    private final Map<String, AgentMetrics> metricsHistory = new ConcurrentHashMap<>();

    /**
     * 实时检测
     */
    public List<Anomaly> detect(String agentId, AgentMetrics current) {
        AgentMetrics baseline = getBaseline(agentId);
        List<Anomaly> anomalies = new ArrayList<>();

        for (AnomalyDetector detector : detectors) {
            Anomaly anomaly = detector.check(current, baseline);
            if (anomaly != null) {
                anomalies.add(anomaly);
            }
        }

        // 记录当前指标到历史
        metricsHistory.computeIfAbsent(agentId, k -> new AgentMetrics())
                      .update(current);

        return anomalies;
    }

    private AgentMetrics getBaseline(String agentId) {
        return metricsHistory.getOrDefault(agentId, AgentMetrics.empty());
    }
}
```

### 2. 统计异常检测器

```java
package com.enterprise.anomaly;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 统计异常检测器
 *
 * 基于历史均值和标准差检测突变
 * 核心原理：3-Sigma 规则（超出 3 倍标准差 = 99.7% 概率是异常）
 */
@Component
public class StatisticalDetector implements AnomalyDetector {

    @Override
    public Anomaly check(AgentMetrics current, AgentMetrics baseline) {
        List<Anomaly> anomalies = new ArrayList<>();

        // 1. Token 消耗突变
        double tokenZ = zScore(current.avgTokensPerRequest(),
                               baseline.avgTokensPerRequest(),
                               baseline.tokenStdDev());
        if (Math.abs(tokenZ) > 3) {
            return new Anomaly(
                AnomalyType.COST_SPIKE,
                Severity.MEDIUM,
                String.format("Token 消耗突变: z=%.2f (均值 %.0f → %.0f)",
                    tokenZ, baseline.avgTokensPerRequest(),
                    current.avgTokensPerRequest()),
                Map.of("zScore", tokenZ)
            );
        }

        // 2. 延迟突变
        double latencyZ = zScore(current.p95LatencyMs(),
                                 baseline.p95LatencyMs(),
                                 baseline.latencyStdDev());
        if (latencyZ > 3) {
            return new Anomaly(
                AnomalyType.LATENCY_SPIKE,
                Severity.HIGH,
                String.format("P95 延迟突变: z=%.2f (%.0fms → %.0fms)",
                    latencyZ, baseline.p95LatencyMs(), current.p95LatencyMs()),
                Map.of("zScore", latencyZ)
            );
        }

        // 3. 质量评分突变
        double qualityZ = zScore(current.avgQualityScore(),
                                  baseline.avgQualityScore(),
                                  baseline.qualityStdDev());
        if (qualityZ < -3) {
            return new Anomaly(
                AnomalyType.QUALITY_DEGRADATION,
                Severity.CRITICAL,
                String.format("质量评分暴跌: z=%.2f (%.2f → %.2f)",
                    qualityZ, baseline.avgQualityScore(),
                    current.avgQualityScore()),
                Map.of("zScore", qualityZ)
            );
        }

        // 4. 工具调用频率突变
        double toolZ = zScore(current.toolCallsPerRequest(),
                              baseline.toolCallsPerRequest(),
                              baseline.toolCallStdDev());
        if (toolZ > 3) {
            return new Anomaly(
                AnomalyType.BEHAVIOR_DRIFT,
                Severity.MEDIUM,
                String.format("工具调用频率突变: z=%.2f (%.1f → %.1f)",
                    toolZ, baseline.toolCallsPerRequest(),
                    current.toolCallsPerRequest()),
                Map.of("zScore", toolZ)
            );
        }

        return null;  // 正常
    }

    /**
     * Z-Score = (当前值 - 均值) / 标准差
     */
    private double zScore(double current, double mean, double stdDev) {
        if (stdDev == 0) return 0;
        return (current - mean) / stdDev;
    }
}
```

### 3. 自治修复引擎

```java
package com.enterprise.anomaly;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 自治修复引擎
 *
 * 检测到异常后，自动尝试修复（不需要人介入）
 */
@Component
public class AutoRemediationEngine {

    private final Map<AnomalyType, RemediationStrategy> strategies = new EnumMap<>(AnomalyType.class);

    public AutoRemediationEngine() {
        strategies.put(AnomalyType.COST_SPIKE, new CostSpikeRemediation());
        strategies.put(AnomalyType.LATENCY_SPIKE, new LatencySpikeRemediation());
        strategies.put(AnomalyType.QUALITY_DEGRADATION, new QualityDegradationRemediation());
        strategies.put(AnomalyType.LOOP_DETECTION, new LoopRemediation());
        strategies.put(AnomalyType.SAFETY_VIOLATION, new SafetyViolationRemediation());
    }

    /**
     * 尝试自动修复
     */
    public RemediationResult remediate(Anomaly anomaly) {
        RemediationStrategy strategy = strategies.get(anomaly.type());
        if (strategy == null) {
            return RemediationResult.noStrategy();
        }

        // 只有 LOW 和 MEDIUM 可以自动修复
        if (anomaly.severity() == Severity.HIGH
            || anomaly.severity() == Severity.CRITICAL) {
            return RemediationResult.escalate("严重程度超过自动修复阈值");
        }

        return strategy.execute(anomaly);
    }

    // --- 修复策略 ---

    /**
     * 成本飙升修复：切换到小模型
     */
    static class CostSpikeRemediation implements RemediationStrategy {
        @Override
        public RemediationResult execute(Anomaly anomaly) {
            // 将模型从 deepseek-reasoner 切换到 deepseek-chat
            configStore.updateModel("deepseek-chat");
            return RemediationResult.fixed(
                "切换到经济模型 deepseek-chat",
                RemeditionAction.MODEL_DOWNGRADE
            );
        }
    }

    /**
     * 延迟飙升修复：增加缓存 + 降级
     */
    static class LatencySpikeRemediation implements RemediationStrategy {
        @Override
        public RemediationResult execute(Anomaly anomaly) {
            // 策略 1: 开启激进缓存
            configStore.setCacheThreshold(0.85);  // 降低相似度阈值
            // 策略 2: 减少最大 Token
            configStore.setMaxTokens(500);  // 从 1000 降到 500

            return RemediationResult.fixed(
                "开启激进缓存 + 减少 maxTokens",
                RemeditionAction.CACHE_AND_LIMIT
            );
        }
    }

    /**
     * 质量退化修复：回滚到上一版本
     */
    static class QualityDegradationRemediation implements RemediationStrategy {
        @Override
        public RemediationResult execute(Anomaly anomaly) {
            String previousVersion = versionManager.getPreviousVersion();
            versionManager.rollback(previousVersion);

            return RemediationResult.fixed(
                "回滚到上一版本: " + previousVersion,
                RemeditionAction.VERSION_ROLLBACK
            );
        }
    }

    /**
     * 循环检测修复：终止当前会话
     */
    static class LoopRemediation implements RemediationStrategy {
        @Override
        public RemediationResult execute(Anomaly anomaly) {
            String sessionId = (String) anomaly.context().get("sessionId");
            sessionManager.forceTerminate(sessionId, "检测到循环异常");

            return RemediationResult.fixed(
                "终止异常会话: " + sessionId,
                RemeditionAction.SESSION_TERMINATE
            );
        }
    }

    /**
     * 安全违规修复：紧急停止 + 隔离
     */
    static class SafetyViolationRemediation implements RemediationStrategy {
        @Override
        public RemediationResult execute(Anomaly anomaly) {
            agentManager.emergencyStop();
            securityTeam.notify(anomaly);

            return RemediationResult.fixed(
                "紧急停止 Agent + 通知安全团队",
                RemeditionAction.EMERGENCY_STOP
            );
        }
    }

    public interface RemediationStrategy {
        RemediationResult execute(Anomaly anomaly);
    }

    public record RemediationResult(
        boolean fixed, boolean escalated,
        String action, RemeditionAction actionType,
        String reason
    ) {
        static RemediationResult fixed(String action, RemeditionAction type) {
            return new RemediationResult(true, false, action, type, null);
        }
        static RemediationResult escalate(String reason) {
            return new RemediationResult(false, true, null, null, reason);
        }
        static RemediationResult noStrategy() {
            return new RemediationResult(false, false, null, null, "无修复策略");
        }
    }

    public enum RemeditionAction {
        MODEL_DOWNGRADE,      // 模型降级
        CACHE_AND_LIMIT,      // 缓存+限制
        VERSION_ROLLBACK,     // 版本回滚
        SESSION_TERMINATE,    // 终止会话
        EMERGENCY_STOP        // 紧急停止
    }
}
```

---

## 异常类型与修复策略矩阵

```mermaid
flowchart TD
    A1["成本飙升"] --> R1["自动降级模型<br/>→ 小模型"]
    A2["延迟飙升"] --> R2["开启激进缓存<br/>→ 降低 maxTokens"]
    A3["质量退化"] --> R3["自动回滚<br/>→ 上一版本"]
    A4["循环异常"] --> R4["终止当前会话<br/>→ 通知用户"]
    A5["安全违规"] --> R5["紧急停止<br/>→ 通知安全团队"]
    A6["行为漂移"] --> R6["通知 + 标记<br/>→ 人工分析"]

    style R1 fill:#4caf50,color:#fff
    style R2 fill:#4caf50,color:#fff
    style R3 fill:#ff9800,color:#fff
    style R4 fill:#4caf50,color:#fff
    style R5 fill:#f44336,color:#fff
    style R6 fill:#ff9800,color:#fff
```

| 异常类型 | 检测方式 | 严重程度 | 自动修复 |
|---------|---------|---------|---------|
| 成本飙升 | Z-Score > 3 | MEDIUM | 模型降级 |
| 延迟飙升 | Z-Score > 3 | HIGH | 缓存+限制 |
| 质量退化 | Z-Score < -3 | CRITICAL | 版本回滚 |
| 循环异常 | transitionReason 重复 | HIGH | 终止会话 |
| 安全违规 | 内容安全检测 | CRITICAL | 紧急停止 |
| 行为漂移 | 趋势分析 | MEDIUM | 人工分析 |

---

## 渐进式自治模型

```mermaid
flowchart TD
    Level1["自治 Level 1: 检测 + 告警<br/>发现问题，通知人类"]
    Level2["自治 Level 2: 检测 + 建议<br/>发现问题，给出修复建议"]
    Level3["自治 Level 3: 检测 + 自动修复<br/>低风险自动修，高风险人工确认"]
    Level4["自治 Level 4: 全自治<br/>检测 + 修复 + 验证<br/>完整自愈闭环"]

    Level1 --> Level2 --> Level3 --> Level4

    style Level1 fill:#ffc107,color:#000
    style Level2 fill:#8bc34a,color:#fff
    style Level3 fill:#4caf50,color:#fff
    style Level4 fill:#2196f3,color:#fff
```

→ 返回 [阶段4 目录](../00-README.md)
