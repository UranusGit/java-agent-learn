# Sprint 1 · 混沌实验引擎（从最简版开始）

> **目标**：从一个"能超时的假 LLM"开始，一步步长成完整的混沌实验引擎
> **预计**：5-7 天
> **技术**：Spring Boot + Advisor + 自定义注入框架

---

## 为什么 Agent 需要混沌测试

传统混沌工程验证基础设施韧性（网络分区/节点宕机），Agent 混沌工程还需要验证：

| Agent 特有故障 | 注入方式 | 验证什么 |
|-------------|---------|---------|
| LLM 超时 | 拦截调用，延迟 60 秒 | 超时处理 + 用户体验 |
| LLM 返回空 | 拦截返回，替换为空字符串 | 空响应处理 |
| 工具调用失败 | 拦截工具，抛出异常 | 降级处理 |
| Token 消耗爆炸 | 返回超长内容 | 预算保护 |

---

## V1：30 分钟——让 LLM "假装超时"

> **思路**：先不搞复杂的框架。写一个最简单的 Advisor，硬编码一个 50% 概率超时，验证你的 Agent 能不能扛住。

### Step 1：写一个最简单的混沌 Advisor

```java
package com.example.reliability.chaos;

import org.springframework.ai.chat.client.advisor.*;
import org.springframework.stereotype.Component;

/**
 * V1 极简版：50% 概率让 LLM 调用超时
 *
 * 目标：验证你的 Agent 在 LLM 超时时会不会优雅降级
 * 问题：硬编码、没法控制开关、只支持超时一种故障
 */
@Component
public class SimpleChaosAdvisor implements CallAdvisor {

    private boolean enabled = false;  // 手动开关

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        if (enabled && Math.random() < 0.5) {
            try {
                Thread.sleep(60000); // 假装超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Simulated timeout [CHAOS]");
        }
        return chain.nextCall(request);
    }

    @Override
    public int getOrder() { return 100; }

    // 通过 API 手动开关
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
}
```

### Step 2：加一个开关接口

```java
@RestController
@RequestMapping("/api/chaos")
public class SimpleChaosController {

    private final SimpleChaosAdvisor advisor;

    @PostMapping("/enable")
    public String enable() {
        advisor.setEnabled(true);
        return "混沌模式已开启（50% 超时）";
    }

    @PostMapping("/disable")
    public String disable() {
        advisor.setEnabled(false);
        return "混沌模式已关闭";
    }
}
```

### Step 3：跑一下，看你的 Agent 会怎样

```bash
# 开启混沌
curl -X POST http://localhost:8080/api/chaos/enable

# 发 10 个请求，看有几个成功
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/chat \
    -H "Content-Type: application/json" \
    -d '{"message":"你好"}' | head -c 100
  echo " [request $i]"
done
```

**你大概率会看到**：一半请求报 500 错误。这说明你的 Agent 没有任何容错设计。

> ✅ V1 的价值：用 30 行代码证明了"你的系统在故障面前是脆弱的"。

---

## V2：2 天——可控的故障注入器

> **V1 的问题**：硬编码 50% 超时、只有一种故障、没有持续时间、没法指定目标。
> **V2 的目标**：能创建一个"实验"，定义注入什么故障、注入多长时间、注入到哪个 Agent。

### Step 2.1：定义实验参数（替代硬编码）

```java
package com.example.reliability.chaos;

import java.time.Duration;

/**
 * V2：可配置的故障注入参数
 *
 * V1 只有 boolean enabled，现在变成一个结构化的参数对象。
 */
public class FaultConfig {

    // 注入什么故障
    private FaultType faultType = FaultType.LLM_TIMEOUT;

    // 注入概率（0.0-1.0）
    private double injectionRate = 1.0;

    // 注入到哪个目标（null = 全局）
    private String targetAgentType;

    // 故障参数
    private Duration timeoutDuration = Duration.ofSeconds(60);
    private String garbledContent = "└§├☼♀♂";

    public enum FaultType {
        LLM_TIMEOUT,          // 超时
        LLM_EMPTY_RESPONSE,   // 空回复
        LLM_GARBLED_RESPONSE, // 乱码
        LLM_RATE_LIMITED,     // 429
        TOKEN_EXPLOSION       // 超长回复
    }

    // getters/setters ...
    public FaultType getFaultType() { return faultType; }
    public void setFaultType(FaultType t) { this.faultType = t; }
    public double getInjectionRate() { return injectionRate; }
    public void setInjectionRate(double r) { this.injectionRate = r; }
    public String getTargetAgentType() { return targetAgentType; }
    public void setTargetAgentType(String t) { this.targetAgentType = t; }
    public Duration getTimeoutDuration() { return timeoutDuration; }
    public void setTimeoutDuration(Duration d) { this.timeoutDuration = d; }
}
```

### Step 2.2：实验管理器（替代手动开关）

```java
package com.example.reliability.chaos;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * V2 实验管理器
 *
 * V1 是 boolean 开关，现在是"开始实验/结束实验"。
 * 一个实验 = 一组 FaultConfig + 一个持续时间。
 */
@Component
public class ChaosExperimentManager {

    // 当前活跃的故障配置
    private volatile FaultConfig activeConfig = null;

    // 实验结束的定时器
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    /**
     * 开始一个实验
     */
    public String startExperiment(FaultConfig config, Duration duration) {
        stopExperiment(); // 先停掉之前的

        this.activeConfig = config;
        String experimentId = UUID.randomUUID().toString();

        // 定时自动结束
        scheduler.schedule(() -> {
            activeConfig = null;
            System.out.println("[CHAOS] 实验 " + experimentId + " 已自动结束");
        }, duration.toSeconds(), TimeUnit.SECONDS);

        System.out.println("[CHAOS] 实验 " + experimentId + " 开始："
            + config.getFaultType() + " rate=" + config.getInjectionRate()
            + " target=" + config.getTargetAgentType()
            + " duration=" + duration.getSeconds() + "s");

        return experimentId;
    }

    /**
     * 手动停止
     */
    public void stopExperiment() {
        activeConfig = null;
    }

    /**
     * 获取当前故障配置（被 Advisor 调用）
     */
    public Optional<FaultConfig> getActiveConfig() {
        return Optional.ofNullable(activeConfig);
    }
}
```

### Step 2.3：升级 Advisor——支持多种故障

```java
package com.example.reliability.chaos;

import org.springframework.ai.chat.client.advisor.*;
import org.springframework.stereotype.Component;

/**
 * V2 混沌 Advisor
 *
 * V1 只会超时，现在支持 5 种故障。
 * 从 ExperimentManager 获取配置，不再硬编码。
 */
@Component
public class ChaosAdvisor implements CallAdvisor {

    private final ChaosExperimentManager manager;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        Optional<FaultConfig> configOpt = manager.getActiveConfig();
        if (configOpt.isEmpty()) {
            return chain.nextCall(request); // 无实验
        }

        FaultConfig config = configOpt.get();

        // 检查目标过滤
        String agentType = request.context().getOrDefault("agentType", "default");
        if (config.getTargetAgentType() != null
                && !config.getTargetAgentType().equals(agentType)) {
            return chain.nextCall(request); // 目标不匹配
        }

        // 概率判断
        if (Math.random() > config.getInjectionRate()) {
            return chain.nextCall(request); // 本次不注入
        }

        // 注入故障
        return injectFault(config, request, chain);
    }

    private AdvisedResponse injectFault(FaultConfig config,
            AdvisedRequest request, CallAdvisorChain chain) {

        switch (config.getFaultType()) {
            case LLM_TIMEOUT -> {
                System.out.println("[CHAOS] 注入超时");
                try {
                    Thread.sleep(config.getTimeoutDuration().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Simulated timeout [CHAOS]");
            }

            case LLM_EMPTY_RESPONSE -> {
                System.out.println("[CHAOS] 注入空回复");
                return mockResponse("");
            }

            case LLM_GARBLED_RESPONSE -> {
                System.out.println("[CHAOS] 注入乱码");
                return mockResponse(config.getGarbledContent());
            }

            case LLM_RATE_LIMITED -> {
                System.out.println("[CHAOS] 注入 429");
                throw new RuntimeException("429 Too Many Requests [CHAOS]");
            }

            case TOKEN_EXPLOSION -> {
                System.out.println("[CHAOS] 注入 Token 爆炸");
                return mockResponse("x".repeat(50000));
            }

            default -> {
                return chain.nextCall(request);
            }
        }
    }

    private AdvisedResponse mockResponse(String content) {
        // 构造 mock 响应——根据 Spring AI 版本调整
        // ...
        throw new UnsupportedOperationException("TODO: 根据你的 Spring AI 版本实现");
    }

    @Override
    public int getOrder() { return 100; }
}
```

### Step 2.4：升级 Controller

```java
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private final ChaosExperimentManager manager;

    /**
     * 创建实验
     */
    @PostMapping("/experiments")
    public String create(@RequestBody CreateExperimentRequest request) {
        FaultConfig config = new FaultConfig();
        config.setFaultType(FaultConfig.FaultType.valueOf(request.faultType()));
        config.setInjectionRate(request.injectionRate());
        config.setTargetAgentType(request.targetAgentType());

        Duration duration = Duration.ofSeconds(request.durationSeconds());
        return manager.startExperiment(config, duration);
    }

    /** 停止实验 */
    @PostMapping("/experiments/stop")
    public String stop() {
        manager.stopExperiment();
        return "已停止";
    }

    /** 查看当前状态 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "active", manager.getActiveConfig().isPresent(),
            "config", manager.getActiveConfig().orElse(null)
        );
    }
}
```

### Step 2.5：测试场景

```bash
# 实验 1：主模型 100% 超时，持续 2 分钟
curl -X POST http://localhost:8080/api/chaos/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "faultType": "LLM_TIMEOUT",
    "injectionRate": 1.0,
    "durationSeconds": 120
  }'

# 观察：你的 Agent 会不会降级？用户看到什么？

# 实验 2：50% 返回空回复
curl -X POST http://localhost:8080/api/chaos/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "faultType": "LLM_EMPTY_RESPONSE",
    "injectionRate": 0.5,
    "durationSeconds": 300
  }'
```

> ✅ V2 的价值：能按需创建实验，支持 5 种故障类型，可控制目标、概率、持续时间。
>
> ❓ V2 的问题：同时只能跑一个实验；没有实验历史记录；不知道系统"是否如预期般扛住了"。

---

## V3：3 天——企业级混沌引擎

> **V2 的问题**：单实验、无记录、无评估。
> **V3 的目标**：多实验并行、完整实验记录、韧性评估报告。

### Step 3.1：实验实体（支持多实验 + 历史）

```java
package com.example.reliability.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * V3：完整的实验实体
 *
 * V2 的 FaultConfig 只是参数，V3 把实验本身变成一个有生命周期的实体。
 */
public class ChaosExperiment {

    private String id;
    private String name;
    private String description;
    private ChaosTarget target;
    private FaultConfig.FaultType faultType;
    private Map<String, Object> faultConfig;
    private double injectionRate;
    private Duration duration;
    private ExperimentStatus status;
    private ResilienceExpectation expectation;  // V3 新增：预期行为
    private Instant startedAt;
    private Instant endedAt;
    private ExperimentResult result;            // V3 新增：评估结果

    public enum ExperimentStatus {
        SCHEDULED, RUNNING, COMPLETED, ABORTED, FAILED
    }

    public record ChaosTarget(
        TargetType type,  // GLOBAL / AGENT / TOOL / MODEL
        String name
    ) {
        public enum TargetType { GLOBAL, AGENT, TOOL, MODEL }
    }

    /**
     * V3 新增：韧性的预期标准
     * 实验结束后对照预期判定 PASS/FAIL
     */
    public record ResilienceExpectation(
        boolean shouldDegradeGracefully,
        boolean shouldFailover,
        Duration maxRecoveryTime,
        boolean noDataLoss
    ) {}

    /**
     * V3 新增：实验评估结果
     */
    public record ExperimentResult(
        boolean passed,
        String actualBehavior,
        Duration actualRecovery,
        List<String> issues
    ) {}

    // getters/setters ...
}
```

### Step 3.2：支持多实验的引擎

```java
package com.example.reliability.chaos;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * V3 混沌引擎
 *
 * V2 用 volatile 单变量，V3 用 Map 支持多实验并行。
 * V3 新增：自动评估、历史记录、事件通知。
 */
@Component
public class ChaosEngine {

    // 活跃实验：id → experiment
    private final Map<String, ChaosExperiment> activeExperiments = new ConcurrentHashMap<>();

    // 历史记录
    private final List<ChaosExperiment> history = Collections.synchronizedList(new ArrayList<>());

    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2);

    /**
     * 启动实验
     */
    public ChaosExperiment start(ChaosExperiment experiment) {
        experiment.setId(UUID.randomUUID().toString());
        experiment.setStatus(ChaosExperiment.ExperimentStatus.RUNNING);
        experiment.setStartedAt(Instant.now());
        activeExperiments.put(experiment.getId(), experiment);

        // 定时结束
        scheduler.schedule(() -> stop(experiment.getId()),
            experiment.getDuration().toSeconds(), TimeUnit.SECONDS);

        return experiment;
    }

    /**
     * 停止并评估
     */
    public ChaosExperiment stop(String experimentId) {
        ChaosExperiment exp = activeExperiments.remove(experimentId);
        if (exp == null) return null;

        exp.setStatus(ChaosExperiment.ExperimentStatus.COMPLETED);
        exp.setEndedAt(Instant.now());

        // V3 新增：对照预期评估
        ExperimentResult result = evaluate(exp);
        exp.setResult(result);

        history.add(exp);
        return exp;
    }

    /**
     * V3 核心：评估系统在实验期间的表现
     */
    private ChaosExperiment.ExperimentResult evaluate(ChaosExperiment exp) {
        ResilienceExpectation expectation = exp.getExpectation();
        if (expectation == null) {
            return new ChaosExperiment.ExperimentResult(
                true, "无预期标准，默认通过", Duration.ZERO, List.of()
            );
        }

        List<String> issues = new ArrayList<>();

        // 检查 1：是否有 5xx 错误（应该降级而非崩溃）
        long errorCount = getErrorCount(exp.getStartedAt(), exp.getEndedAt());
        if (expectation.shouldDegradeGracefully() && errorCount > 0) {
            issues.add("期间有 " + errorCount + " 次 5xx 错误，未优雅降级");
        }

        // 检查 2：是否发生了故障切换
        if (expectation.shouldFailover()) {
            boolean didFailover = checkFailoverOccurred(exp);
            if (!didFailover) {
                issues.add("预期发生故障切换，但未检测到切换行为");
            }
        }

        // 检查 3：恢复时间
        Duration actualRecovery = calculateRecoveryTime(exp);
        if (actualRecovery.compareTo(expectation.maxRecoveryTime()) > 0) {
            issues.add("恢复时间 " + actualRecovery.toSeconds() + "s 超过预期 "
                + expectation.maxRecoveryTime().toSeconds() + "s");
        }

        boolean passed = issues.isEmpty();
        return new ChaosExperiment.ExperimentResult(
            passed,
            passed ? "系统按预期表现" : "发现 " + issues.size() + " 个问题",
            actualRecovery,
            issues
        );
    }

    // 从监控系统获取指标（对接 Prometheus/Spring Actuator）
    private long getErrorCount(Instant from, Instant to) { /* ... */ return 0; }
    private boolean checkFailoverOccurred(ChaosExperiment exp) { /* ... */ return false; }
    private Duration calculateRecoveryTime(ChaosExperiment exp) { /* ... */ return Duration.ZERO; }

    /**
     * 查询活跃实验
     */
    public Collection<ChaosExperiment> getActive() {
        return activeExperiments.values();
    }

    /**
     * 查询历史
     */
    public List<ChaosExperiment> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * 根据请求目标查找适用的实验
     */
    public Optional<FaultConfig.FaultType> findApplicableFault(String targetKey) {
        for (ChaosExperiment exp : activeExperiments.values()) {
            if (!matchesTarget(exp, targetKey)) continue;
            if (Math.random() < exp.getInjectionRate()) {
                return Optional.of(exp.getFaultType());
            }
        }
        return Optional.empty();
    }

    private boolean matchesTarget(ChaosExperiment exp, String targetKey) {
        if (exp.getTarget().type() == ChaosExperiment.ChaosTarget.TargetType.GLOBAL)
            return true;
        return exp.getTarget().name().equals(targetKey);
    }
}
```

### Step 3.3：预置实验场景

```java
/**
 * V3 新增：标准化的预置实验
 * 不用每次手写 JSON，直接选一个场景。
 */
public class PredefinedExperiments {

    /** 场景 1：主模型完全宕机，预期自动切换备选 */
    public static ChaosExperiment modelOutage() {
        ChaosExperiment exp = new ChaosExperiment();
        exp.setName("主模型宕机测试");
        exp.setTarget(new ChaosExperiment.ChaosTarget(
            ChaosExperiment.ChaosTarget.TargetType.MODEL, "deepseek-chat"));
        exp.setFaultType(FaultConfig.FaultType.LLM_TIMEOUT);
        exp.setInjectionRate(1.0);
        exp.setDuration(Duration.ofMinutes(5));
        exp.setExpectation(new ChaosExperiment.ResilienceExpectation(
            true,   // 应该降级
            true,   // 应该故障切换
            Duration.ofSeconds(15), // 15 秒内恢复
            true    // 不应丢数据
        ));
        return exp;
    }

    /** 场景 2：50% 请求返回乱码，测试解析容错 */
    public static ChaosExperiment garbledResponse() {
        ChaosExperiment exp = new ChaosExperiment();
        exp.setName("LLM 返回乱码测试");
        exp.setFaultType(FaultConfig.FaultType.LLM_GARBLED_RESPONSE);
        exp.setInjectionRate(0.5);
        exp.setDuration(Duration.ofMinutes(3));
        exp.setExpectation(new ChaosExperiment.ResilienceExpectation(
            true, false, Duration.ofSeconds(2), true
        ));
        return exp;
    }

    /** 场景 3：Token 消耗爆炸，测试预算保护 */
    public static ChaosExperiment tokenExplosion() {
        ChaosExperiment exp = new ChaosExperiment();
        exp.setName("Token 爆炸测试");
        exp.setFaultType(FaultConfig.FaultType.TOKEN_EXPLOSION);
        exp.setInjectionRate(0.1);
        exp.setDuration(Duration.ofMinutes(3));
        exp.setExpectation(new ChaosExperiment.ResilienceExpectation(
            true, false, Duration.ofSeconds(1), true
        ));
        return exp;
    }
}
```

### Step 3.4：升级 Controller——支持预置场景和历史

```java
@RestController
@RequestMapping("/api/chaos")
public class ChaosController {

    private final ChaosEngine engine;

    /** 从预置场景启动 */
    @PostMapping("/predefined/{scenario}")
    public ChaosExperiment startPredefined(@PathVariable String scenario) {
        ChaosExperiment exp = switch (scenario) {
            case "model-outage" -> PredefinedExperiments.modelOutage();
            case "garbled" -> PredefinedExperiments.garbledResponse();
            case "token-explosion" -> PredefinedExperiments.tokenExplosion();
            default -> throw new IllegalArgumentException("未知场景：" + scenario);
        };
        return engine.start(exp);
    }

    /** 自定义实验 */
    @PostMapping("/experiments")
    public ChaosExperiment create(@RequestBody CreateExperimentRequest req) { /* ... */ }

    /** 停止 */
    @PostMapping("/experiments/{id}/stop")
    public ChaosExperiment stop(@PathVariable String id) {
        return engine.stop(id);
    }

    /** 活跃实验列表 */
    @GetMapping("/experiments/active")
    public Collection<ChaosExperiment> active() {
        return engine.getActive();
    }

    /** 实验历史 + 评估结果 */
    @GetMapping("/experiments/history")
    public List<ChaosExperiment> history() {
        return engine.getHistory();
    }
}
```

### Step 3.5：验证端到端流程

```bash
# 1. 启动模型宕机实验
curl -X POST http://localhost:8080/api/chaos/predefined/model-outage

# 2. 等待 5 分钟（实验自动结束）

# 3. 查看评估结果
curl http://localhost:8080/api/chaos/experiments/history | jq '.[-1].result'
# {
#   "passed": true,
#   "actualBehavior": "系统按预期表现",
#   "actualRecovery": "PT8S",
#   "issues": []
# }
```

> ✅ V3 的价值：多实验并行、完整历史记录、自动韧性评估、预置标准场景。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 极简版 | V2 可控版 | V3 企业版 |
|------|----------|----------|----------|
| **故障类型** | 1 种（超时） | 5 种 | 5 种 + 自定义 |
| **控制方式** | boolean 开关 | 参数对象 + 定时器 | 完整实验实体 + 生命周期 |
| **并发实验** | 1 个 | 1 个 | 多个并行 |
| **目标过滤** | 无 | 按 Agent 类型 | 按 Agent/工具/模型/全局 |
| **历史记录** | 无 | 无 | 完整记录 + 评估结果 |
| **韧性评估** | 无 | 无 | 对照预期自动判定 |
| **预置场景** | 无 | 无 | 3 个标准场景 |
| **代码量** | ~30 行 | ~200 行 | ~500 行 |

> 这就是企业级代码的演进方式：**不是一上来写 500 行，而是先 30 行跑通核心逻辑，再一步步加功能。每一步都是可运行、可测试的。**

---

## 验收检查

- [ ] V1：能开启/关闭混沌，验证系统是否脆弱
- [ ] V2：能创建自定义实验（故障类型+概率+目标+持续时间）
- [ ] V3：能运行预置场景、查看评估报告
- [ ] 理解"为什么先写 V1 而不是直接写 V3"——演进式开发思维

---

## 下一步

→ [Sprint 2：故障切换与成本](Sprint2-故障切换与成本.md)
