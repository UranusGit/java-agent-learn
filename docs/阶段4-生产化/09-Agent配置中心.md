# 09 · Agent 配置中心与灰度发布（补充篇）

> 阶段：4 生产化 · 难度：⭐⭐⭐⭐ · 预计：2 天
> 前置：[08 管控分离架构](08-管控分离架构.md)
> 产出：掌握 Agent 系统的配置管理、版本控制、灰度发布

---

## Agent 配置中心

### 为什么 Agent 需要独立的配置中心

| 传统应用配置 | Agent 专属配置 |
|-------------|--------------|
| 数据库连接 | **System Prompt**（每周都在调） |
| 线程池大小 | **模型选择**（不断有新模型） |
| 超时时间 | **Temperature/maxTokens**（按场景调） |
| 日志级别 | **工具开关**（A/B 测试新工具） |
| | **防护参数**（maxTurns/budget） |
| | **特性开关**（灰度发布新功能） |

### 完整实现

```java
package com.example.platform.config;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 配置中心
 *
 * 配置层次（高优先级覆盖低优先级）：
 * 1. 全局默认配置
 * 2. 租户级配置（覆盖全局）
 * 3. 会话级配置（覆盖租户）
 *
 * 配置变更触发：
 * - 通知所有活跃 Agent 重新加载
 * - 记录变更历史（谁在什么时候改了什么）
 * - 支持回滚到任意版本
 */
@Service
public class AgentConfigCenter {

    private final Map<String, ConfigSnapshot> globalConfig = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConfigSnapshot>> tenantConfig = new ConcurrentHashMap<>();
    private final List<ConfigChange> changeHistory = Collections.synchronizedList(new ArrayList<>());

    /**
     * 获取生效配置（全局 + 租户覆盖）
     */
    public ConfigSnapshot getConfig(String tenantId) {
        ConfigSnapshot base = globalConfig.getOrDefault("default", ConfigSnapshot.defaults());

        Map<String, String> merged = new HashMap<>(base.values());

        // 租户级覆盖
        Map<String, ConfigSnapshot> tenantOverrides = tenantConfig.get(tenantId);
        if (tenantOverrides != null) {
            ConfigSnapshot tenantActive = tenantOverrides.get("active");
            if (tenantActive != null) {
                merged.putAll(tenantActive.values());
            }
        }

        return new ConfigSnapshot(tenantId, merged, System.currentTimeMillis());
    }

    /**
     * 更新配置（带审计）
     */
    public void updateConfig(String tenantId, String key, String value, String operator) {
        ConfigChange change = new ConfigChange(
            UUID.randomUUID().toString(),
            tenantId, key,
            getConfig(tenantId).values().get(key),  // old value
            value,                                    // new value
            operator,
            System.currentTimeMillis()
        );
        changeHistory.add(change);

        // 应用变更
        tenantConfig.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .merge("active", new ConfigSnapshot(tenantId, Map.of(key, value), System.currentTimeMillis()),
                (old, update) -> {
                    Map<String, String> merged = new HashMap<>(old.values());
                    merged.putAll(update.values());
                    return new ConfigSnapshot(tenantId, merged, System.currentTimeMillis());
                });

        // 通知活跃 Agent
        notifyAgents(tenantId, key, value);
    }

    /**
     * 配置回滚
     */
    public void rollback(String changeId) {
        ConfigChange change = changeHistory.stream()
            .filter(c -> c.id().equals(changeId))
            .findFirst().orElseThrow();

        updateConfig(change.tenantId(), change.key(), change.oldValue(), "rollback");
    }

    /**
     * 获取配置变更历史
     */
    public List<ConfigChange> getHistory(String tenantId, int limit) {
        return changeHistory.stream()
            .filter(c -> c.tenantId().equals(tenantId))
            .limit(limit)
            .toList();
    }

    private void notifyAgents(String tenantId, String key, String value) {
        // 通过 Redis pub/sub 或 SSE 通知活跃 Agent
    }

    // Records
    public record ConfigSnapshot(String tenantId, Map<String, String> values, long timestamp) {
        static ConfigSnapshot defaults() {
            return new ConfigSnapshot("default", Map.of(
                "system.prompt", "你是一个智能助手。",
                "model.name", "deepseek-chat",
                "model.temperature", "0.7",
                "model.maxTokens", "2000",
                "guard.maxTurns", "20",
                "guard.budgetUsd", "0.5",
                "feature.cache", "true",
                "feature.multiAgent", "false"
            ), System.currentTimeMillis());
        }
    }

    public record ConfigChange(
        String id, String tenantId, String key,
        String oldValue, String newValue,
        String operator, long timestamp
    ) {}
}
```

---

## 灰度发布

### 灰度策略

```java
package com.example.platform.canary;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 灰度发布管理器
 *
 * Agent 变更（新 Prompt/新模型/新工具）的渐进发布：
 *
 * 阶段 1: 内部测试（0% 外部流量）
 *   ↓ 验证通过
 * 阶段 2: Canary 5%（小流量验证）
 *   ↓ 指标正常
 * 阶段 3: 25%（扩大流量）
 *   ↓ 指标正常
 * 阶段 4: 50%（半量）
 *   ↓ 指标正常
 * 阶段 5: 100%（全量）
 *   ↓ 任何阶段指标异常 → 自动回滚
 */
@Component
public class CanaryReleaseManager {

    /**
     * 灰度规则：决定每个请求用哪个版本
     */
    public String selectVersion(String tenantId, String userId,
                                 List<ReleaseVersion> versions) {
        for (ReleaseVersion version : versions) {
            if (version.status() != ReleaseStatus.ACTIVE) continue;

            // 按租户灰度
            if (version.targetTenants() != null
                && version.targetTenants().contains(tenantId)) {
                return version.versionId();
            }

            // 按用户哈希灰度（确保同一用户始终同一版本）
            if (version.trafficPercentage() > 0) {
                int hash = Math.abs(userId.hashCode()) % 100;
                if (hash < version.trafficPercentage()) {
                    return version.versionId();
                }
            }
        }

        // 默认返回 stable 版本
        return versions.stream()
            .filter(v -> v.status() == ReleaseStatus.STABLE)
            .map(ReleaseVersion::versionId)
            .findFirst()
            .orElseThrow();
    }

    /**
     * 自动评估灰度版本的健康度
     */
    public CanaryHealth assess(String versionId, CanaryMetrics metrics) {
        List<String> alerts = new ArrayList<>();

        // 检查错误率
        if (metrics.errorRate() > 0.05) {  // 5% 阈值
            alerts.add("错误率 %.1f%% 超过阈值 5%%".formatted(metrics.errorRate() * 100));
        }

        // 检查延迟
        if (metrics.p99LatencyMs() > 10000) {  // 10s 阈值
            alerts.add("P99 延迟 %dms 超过阈值 10s".formatted(metrics.p99LatencyMs()));
        }

        // 检查质量评分
        if (metrics.evalScore() < 0.8) {
            alerts.add("Eval 评分 %.2f 低于阈值 0.80".formatted(metrics.evalScore()));
        }

        // 检查成本
        if (metrics.costPerSessionUsd() > metrics.baselineCostUsd() * 1.5) {
            alerts.add("单次会话成本 $%.4f 超过基线 50%%".formatted(metrics.costPerSessionUsd()));
        }

        if (!alerts.isEmpty()) {
            return CanaryHealth.rollback(alerts);
        }
        return CanaryHealth.healthy();
    }

    public record ReleaseVersion(
        String versionId,
        ReleaseStatus status,
        List<String> targetTenants,    // 定向租户
        int trafficPercentage,          // 流量百分比 0-100
        ConfigSnapshot config,          // 该版本的配置
        String description
    ) {}

    public enum ReleaseStatus { STABLE, ACTIVE, PAUSED, ROLLED_BACK }

    public record CanaryMetrics(
        double errorRate, long p99LatencyMs,
        double evalScore, double costPerSessionUsd,
        double baselineCostUsd
    ) {}

    public record CanaryHealth(boolean healthy, List<String> alerts, boolean shouldRollback) {
        static CanaryHealth healthy() { return new CanaryHealth(true, List.of(), false); }
        static CanaryHealth rollback(List<String> alerts) { return new CanaryHealth(false, alerts, true); }
    }

    public record ConfigSnapshot(Map<String, String> values) {}
}
```

---

## 版本管理

```java
package com.example.platform.versioning;

import java.util.*;

/**
 * Agent 版本管理
 *
 * 三个维度版本化：
 * 1. Prompt 版本（system prompt 的每次修改都是新版本）
 * 2. Tool 版本（工具接口变更）
 * 3. Config 版本（模型参数变更）
 *
 * 版本号：语义版本 (MAJOR.MINOR.PATCH)
 * - MAJOR: Prompt 大改 / Tool 接口不兼容
 * - MINOR: 新增能力 / 向后兼容
 * - PATCH: 调参 / Bug 修复
 */
public class AgentVersionManager {

    /**
     * Prompt 版本
     */
    public record PromptVersion(
        String versionId,           // "v2.1.0"
        String systemPrompt,
        List<String> changeNotes,   // ["增加退款处理规则", "修改语气为更专业"]
        PromptVersion baseline,     // 基线版本（A/B 测试的对照组）
        String createdBy,
        long createdAt
    ) {}

    /**
     * 版本对比（用于 A/B 测试报告）
     */
    public VersionComparison compare(
        PromptVersion versionA, PromptVersion versionB,
        EvalResult resultA, EvalResult resultB
    ) {
        return new VersionComparison(
            versionA.versionId(), versionB.versionId(),
            resultA.score(), resultB.score(),
            resultB.score() - resultA.score(),  // improvement
            resultB.score() > resultA.score() ? versionB.versionId() : versionA.versionId()
        );
    }

    public record EvalResult(double score, int totalCases, int passed) {}

    public record VersionComparison(
        String versionA, String versionB,
        double scoreA, double scoreB,
        double improvement,
        String winner
    ) {}
}
```

---

## 验收检查

- [ ] 能实现配置中心（全局/租户/会话三级覆盖）
- [ ] 理解灰度发布五阶段流程
- [ ] 能实现按租户/按用户哈希的灰度路由
- [ ] 能实现灰度自动评估和回滚
- [ ] 理解 Prompt/Tool/Config 三维度版本化

---

## 下一步

→ 下一篇：[10 Agent 生命周期管理](../阶段5-架构师/07-Agent生命周期管理.md)
