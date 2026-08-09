# Sprint 2 · 故障切换与成本追踪（从最简版开始）

> **目标**：从"一行 try-catch"开始，一步步长成多模型路由 + 成本追踪体系
> **预计**：5-7 天
> **前置**：Sprint 1 混沌引擎（用它注入故障来验证你的容错）

---

## V1：20 分钟——try-catch 兜底

> **思路**：先不管什么路由器、断路器。最简单的容错就是 try-catch + 一个备选模型。

### Step 1：硬编码双模型 try-catch

```java
package com.example.reliability;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * V1 极简版：主模型失败就用备选
 *
 * 问题：硬编码模型名、没有健康追踪、没有成本记录
 * 但它解决了最核心的问题：一个模型挂了不会全站不可用。
 */
@Service
public class SimpleFailoverChat {

    private final ChatClient primaryClient;   // DeepSeek
    private final ChatClient fallbackClient;  // OpenAI

    public SimpleFailoverChat(
            @Qualifier("chatClient") ChatClient primary,
            @Qualifier("fallbackClient") ChatClient fallback) {
        this.primaryClient = primary;
        this.fallbackClient = fallback;
    }

    public String chat(String message) {
        try {
            return primaryClient.prompt().user(message).call().content();
        } catch (Exception e) {
            System.out.println("⚠️ 主模型失败，切换备选：" + e.getMessage());
            return fallbackClient.prompt().user(message).call().content();
        }
    }
}
```

### Step 2：用 Sprint 1 的混沌引擎测试

```bash
# 开启混沌（主模型 100% 超时）
curl -X POST http://localhost:8080/api/chaos/predefined/model-outage

# 发请求——应该自动切到备选
curl -X POST http://localhost:8080/api/chat -d '{"message":"你好"}'
# 预期：虽然主模型挂了，但用户还是拿到了回复
```

**V1 的问题清单**：
- ❌ 主模型恢复后不知道，一直在用备选（贵）
- ❌ 如果两个都挂了呢？直接抛异常给用户
- ❌ 切换了多少次？花了多少成本？一无所知

> ✅ V1 的价值：10 行代码验证了"故障切换"这个概念是可行的。

---

## V2：2 天——多模型路由器 + 健康追踪

> **V1 的问题**：硬编码两个模型、主模型恢复后不知道切回来。
> **V2 的目标**：给每个模型维护健康状态，自动选择可用的模型。

### Step 2.1：模型健康状态（断路器）

```java
package com.example.reliability.failover;

import java.util.*;

/**
 * V2：模型健康追踪
 *
 * V1 完全不知道模型状态，V2 为每个模型维护一个"断路器"。
 */
public class ModelHealth {

    private boolean available = true;
    private int consecutiveFailures = 0;
    private long unavailableUntil = 0;

    public boolean isAvailable() {
        if (!available && System.currentTimeMillis() > unavailableUntil) {
            // 恢复了——重置状态
            available = true;
            consecutiveFailures = 0;
        }
        return available;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
        available = true;
    }

    public void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= 3) {
            // 连续 3 次失败 → 标记不可用 30 秒
            available = false;
            unavailableUntil = System.currentTimeMillis() + 30_000;
        }
    }
}
```

### Step 2.2：多模型路由器

```java
package com.example.reliability.failover;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V2：多模型路由器
 *
 * V1 只有 try-catch，V2 是一个路由器：
 * 1. 维护每个模型的健康状态
 * 2. 按优先级列表尝试，第一个可用的就用
 * 3. 主模型恢复后自动切回（因为有健康检查）
 */
@Component
public class ModelRouter {

    // 模型优先级列表
    private final List<ModelEntry> models;
    private final Map<String, ModelHealth> healthMap = new ConcurrentHashMap<>();

    public ModelRouter(
            @Qualifier("chatClient") ChatClient primary,
            @Qualifier("fallbackClient") ChatClient fallback,
            @Qualifier("economyClient") ChatClient economy) {
        this.models = List.of(
            new ModelEntry("primary", primary),
            new ModelEntry("fallback", fallback),
            new ModelEntry("economy", economy)  // 最便宜的，最后兜底
        );
    }

    /**
     * 执行调用，自动路由 + 故障切换
     */
    public RouterResult call(String message) {
        Exception lastError = null;

        for (ModelEntry entry : models) {
            ModelHealth health = healthMap.computeIfAbsent(
                entry.name(), k -> new ModelHealth());

            if (!health.isAvailable()) {
                continue; // 这个模型当前不可用，跳过
            }

            try {
                String result = entry.client().prompt()
                    .user(message).call().content();

                health.recordSuccess();
                boolean isPrimary = models.indexOf(entry) == 0;
                return new RouterResult(result, entry.name(), isPrimary);

            } catch (Exception e) {
                health.recordFailure();
                lastError = e;
                System.out.println("⚠️ 模型 " + entry.name()
                    + " 失败，尝试下一个：" + e.getMessage());
            }
        }

        throw new RuntimeException("所有模型均不可用", lastError);
    }

    /**
     * 查看各模型健康状态
     */
    public Map<String, Boolean> getHealthStatus() {
        Map<String, Boolean> status = new HashMap<>();
        healthMap.forEach((name, health) ->
            status.put(name, health.isAvailable()));
        return status;
    }

    private record ModelEntry(String name, ChatClient client) {}
    public record RouterResult(String content, String modelUsed, boolean isPrimary) {
        public boolean failedOver() { return !isPrimary; }
    }
}
```

### Step 2.3：体验自动恢复

```bash
# 1. 开启混沌——主模型挂了
curl -X POST http://localhost:8080/api/chaos/predefined/model-outage

# 2. 发 3 个请求 → 主模型连续失败 3 次 → 标记不可用 → 切到 fallback
for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/api/chat -d '{"message":"hi"}'
done

# 3. 查看健康状态
curl http://localhost:8080/api/failover/health
# {"primary":false,"fallback":true,"economy":true}

# 4. 混沌实验结束（5 分钟后或手动停止）
curl -X POST http://localhost:8080/api/chaos/experiments/stop

# 5. 等 30 秒（断路器恢复）后再发请求 → 自动切回 primary
```

> ✅ V2 的价值：模型健康追踪、有序故障切换、自动恢复。
>
> ❓ V2 的问题：没有成本意识——一直在用最贵的模型也不知道花了多少钱。

---

## V3：3 天——三级降级 + 成本追踪

> **V2 的问题**：没有降级策略（所有模型挂了怎么办？），没有成本追踪。
> **V3 的目标**：增加缓存兜底 + 预设回复，并自动记录每次调用的成本。

### Step 3.1：成本追踪（Advisor 自动记录）

```java
package com.example.reliability.cost;

import org.springframework.ai.chat.client.advisor.*;
import org.springframework.stereotype.Component;

/**
 * V3 新增：成本追踪 Advisor
 *
 * 不侵入业务代码，自动拦截每次 LLM 调用记录 token 用量。
 */
@Component
public class CostTrackingAdvisor implements CallAdvisor {

    private final CostRecordRepository repository;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        // 执行调用
        AdvisedResponse response = chain.nextCall(request);

        // 从响应中提取 token 用量
        var usage = response.response().getMetadata().getUsage();
        String model = request.chatOptions().getModel();
        String tenantId = request.context().getOrDefault("tenantId", "internal");

        // 异步记录（不阻塞主流程）
        CompletableFuture.runAsync(() -> {
            repository.save(new CostRecord(
                tenantId,
                model,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                calculateCost(model, usage),
                Instant.now()
            ));
        });

        return response;
    }

    private double calculateCost(String model, Usage usage) {
        // DeepSeek: $0.14/M input, $0.28/M output
        double input = (usage.getPromptTokens() / 1_000_000.0) * 0.14;
        double output = (usage.getCompletionTokens() / 1_000_000.0) * 0.28;
        return input + output;
    }

    @Override
    public int getOrder() { return 50; }
}
```

### Step 3.2：三级渐进降级

```java
package com.example.reliability.failover;

import org.springframework.stereotype.Component;

/**
 * V3 新增：三级降级
 *
 * V2 所有模型挂了就抛异常，V3 增加了缓存和预设回复兜底。
 */
@Component
public class DegradationManager {

    private final ModelRouter router;
    private final SemanticCache cache;  // 语义缓存

    public DegradationResult execute(String message, String sessionId) {
        // Level 0: 正常调用（路由器内部会做故障切换）
        try {
            var result = router.call(message);
            return DegradationResult.full(result);
        } catch (Exception allFailed) {
            // 所有模型都挂了
        }

        // Level 1: 语义缓存
        String cached = cache.lookup(message);
        if (cached != null) {
            return DegradationResult.cacheHit(cached);
        }

        // Level 2: 预设回复 + 排队
        return DegradationResult.fallback(
            "系统暂时繁忙，您的请求已加入队列，稍后会通知您。"
        );
    }

    public record DegradationResult(
        String content, String level, boolean fromCache
    ) {
        static DegradationResult full(ModelRouter.RouterResult r) {
            return new DegradationResult(r.content(), "FULL", false);
        }
        static DegradationResult cacheHit(String cached) {
            return new DegradationResult(cached, "CACHE", true);
        }
        static DegradationResult fallback(String message) {
            return new DegradationResult(message, "FALLBACK", false);
        }
    }
}
```

### Step 3.3：租户预算管理

```java
package com.example.reliability.cost;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 新增：租户预算
 *
 * 超预算时自动降级到便宜模型或拒绝。
 */
@Component
public class BudgetManager {

    private final CostRecordRepository repository;
    private final Map<String, Double> budgets = new ConcurrentHashMap<>();

    public BudgetStatus check(String tenantId) {
        Double budget = budgets.get(tenantId);
        if (budget == null) return BudgetStatus.OK;

        // 查当月已用
        double used = repository.sumCostThisMonth(tenantId);
        double ratio = used / budget;

        if (ratio >= 1.0) return BudgetStatus.EXCEEDED;
        if (ratio >= 0.95) return BudgetStatus.CRITICAL;
        if (ratio >= 0.80) return BudgetStatus.WARNING;
        return BudgetStatus.OK;
    }

    public void setBudget(String tenantId, double monthly) {
        budgets.put(tenantId, monthly);
    }

    public enum BudgetStatus { OK, WARNING, CRITICAL, EXCEEDED }
}
```

### Step 3.4：成本看板 API

```java
@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final CostRecordRepository repository;
    private final BudgetManager budgetManager;

    /** 租户月度成本 */
    @GetMapping("/tenant/{tenantId}")
    public Map<String, Object> tenantCost(@PathVariable String tenantId) {
        double total = repository.sumCostThisMonth(tenantId);
        long calls = repository.countThisMonth(tenantId);
        var status = budgetManager.check(tenantId);

        return Map.of(
            "tenantId", tenantId,
            "monthToDate", total,
            "totalCalls", calls,
            "budgetStatus", status.name()
        );
    }

    /** 全平台成本 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
            "totalThisMonth", repository.sumAllCostThisMonth(),
            "totalCalls", repository.countAllThisMonth(),
            "byTenant", repository.costByTenantThisMonth()
        );
    }

    /** 设置预算 */
    @PostMapping("/tenant/{tenantId}/budget")
    public void setBudget(@PathVariable String tenantId,
                          @RequestBody double monthlyBudget) {
        budgetManager.setBudget(tenantId, monthlyBudget);
    }
}
```

### Step 3.5：集成测试

```java
@SpringBootTest
class FailoverCostIntegrationTest {

    @Autowired ModelRouter router;
    @Autowired CostRecordRepository costRepo;
    @Autowired ChaosEngine chaos;

    @Test
    @DisplayName("主模型超时 → 切换 → 成本记录到备选模型")
    void failoverAndCostTracking() {
        // 1. 注入故障
        chaos.start(PredefinedExperiments.modelOutage());

        // 2. 发请求
        var result = router.call("hello");

        // 3. 验证切换
        assertThat(result.failedOver()).isTrue();
        assertThat(result.content()).isNotEmpty();

        // 4. 验证成本记录
        var records = costRepo.findRecent(1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).model()).isNotEqualTo("primary");

        // 5. 清理
        chaos.stopAll();
    }
}
```

> ✅ V3 的价值：三级降级保底、成本自动追踪、预算告警。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 极简版 | V2 路由器版 | V3 企业版 |
|------|----------|-----------|----------|
| **故障切换** | try-catch 两模型 | 有序列表 + 断路器 + 自动恢复 | 路由器 + 三级降级 |
| **健康追踪** | 无 | 有（连续失败→熔断→恢复） | 有 |
| **兜底策略** | 抛异常 | 抛异常 | 缓存 → 预设回复 |
| **成本追踪** | 无 | 无 | Advisor 自动记录 |
| **预算管理** | 无 | 无 | 四级预算状态 |
| **代码量** | ~15 行 | ~100 行 | ~300 行 |

---

## 验收检查

- [ ] V1：try-catch 两个模型能切换
- [ ] V2：断路器能标记模型不可用，恢复后自动切回
- [ ] V3：三级降级能兜底、成本 Advisor 能自动记录
- [ ] 混沌实验能触发真实的故障切换

---

## 下一步

→ [Sprint 3：安全与飞轮](Sprint3-安全与飞轮.md)
