# Sprint 6 详细实现：生产化（可靠性 + 成本 + 可观测）

> 目标：Agent 敢上线——可靠性保护、成本优化、全链路可观测
> 时间：1.5 周 · 前置：Sprint 5 完成

---

## Day 1-3：可靠性工程

### Step 1：幂等工具改造

```java
@Component
public class NotificationTool extends SafeTool {

    private final StringRedisTemplate redis;

    @Tool(description = "发送通知给用户。幂等：相同收件人+内容不会重复发送。")
    public String sendNotification(String to, String content) {
        String key = "notify:" + UUID.nameUUIDFromBytes(
            (to + "|" + content).getBytes());

        // Redis SETNX 原子操作：不存在才设置
        Boolean first = redis.opsForValue().setIfAbsent(key, "sent", Duration.ofDays(7));
        if (!Boolean.TRUE.equals(first)) {
            return "⏭️ 通知已发送过（幂等跳过）";
        }

        // 实际发送（模拟）
        return "✅ 已发送通知给 " + to;
    }
}
```

### Step 2：Resilience4j 三层熔断

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      llmCall:          # LLM 调用层
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
      toolCall:         # 工具调用层
        failureRateThreshold: 60
        waitDurationInOpenState: 10s
      systemLevel:      # 系统入口层
        failureRateThreshold: 70
        waitDurationInOpenState: 60s
  timelimiter:
    instances:
      llmCall:
        timeoutDuration: 30s
      toolCall:
        timeoutDuration: 10s
```

```java
@Service
public class ResilientChatService {

    @CircuitBreaker(name = "systemLevel")
    @TimeLimiter(name = "systemLevel")
    public CompletableFuture<String> chat(String q, String tenantId, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            TenantContext.set(tenantId, sessionId);
            try {
                return orchestrator.handle(q, tenantId, sessionId);
            } finally {
                TenantContext.clear();
            }
        });
    }
}
```

### Step 3：Fallback

```java
@CircuitBreaker(name = "llmCall", fallbackMethod = "chatFallback")
public String callLlm(String prompt) {
    return chatClient.prompt().user(prompt).call().content();
}

public String chatFallback(String prompt, Exception e) {
    // 降级：返回缓存或友好提示
    log.warn("LLM 熔断，降级处理", e);
    return "AI 服务暂时繁忙，您的消息已记录，客服稍后会回复。";
}
```

---

## Day 4-5：成本工程

### Step 4：SemanticCache

```java
@Component
public class SemanticCache {

    private final EmbeddingModel embeddingModel;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final double THRESHOLD = 0.92;

    record CacheEntry(float[] vector, String reply, long timestamp) {}

    public Optional<String> get(String query) {
        float[] qv = embeddingModel.embed(query);
        return cache.values().stream()
            .map(e -> Map.entry(e, cosineSimilarity(qv, e.vector())))
            .filter(e -> e.getValue() >= THRESHOLD)
            .max(Map.Entry.comparingByValue())
            .map(e -> e.getKey().reply());
    }

    public void put(String query, String reply) {
        cache.put(UUID.randomUUID().toString(),
            new CacheEntry(embeddingModel.embed(query), reply, System.currentTimeMillis()));
    }
}
```

### Step 5：ModelRouter

```java
@Component
public class ModelRouter {

    public enum Tier { CHEAP, STANDARD, PREMIUM }

    public Tier route(String query) {
        if (query.length() < 10) return Tier.CHEAP;
        if (query.contains("翻译") || query.contains("总结")) return Tier.CHEAP;
        if (query.contains("代码") || query.contains("架构")) return Tier.PREMIUM;
        return Tier.STANDARD;
    }
}
```

### Step 6：成本看板接口

```java
@RestController
@RequestMapping("/api/admin/cost")
public class CostDashboardController {

    @GetMapping("/report")
    public Map<String, Object> report(@RequestHeader("X-Tenant-Id") String tenantId) {
        return Map.of(
            "today", billing.getDailyReport(tenantId, LocalDate.now()),
            "thisWeek", billing.getWeeklyReport(tenantId),
            "byModel", billing.getByModelBreakdown(tenantId),
            "cacheHitRate", semanticCache.getHitRate(tenantId)
        );
    }
}
```

---

## Day 6-7：可观测性

### Step 7：Micrometer 指标

```java
@Component
public class AiMetrics {

    private final MeterRegistry registry;

    public void recordTokenUsage(String model, long input, long output) {
        registry.counter("gen_ai.client.token.usage",
            "model", model, "type", "input").increment(input);
        registry.counter("gen_ai.client.token.usage",
            "model", model, "type", "output").increment(output);
    }

    public Timer.StartSample startRequest() { return Timer.start(registry); }

    public void recordLatency(Timer.StartSample sample, String operation) {
        sample.stop(Timer.builder("gen_ai.client.operation")
            .tag("operation", operation).register(registry));
    }
}
```

### Step 8：步骤级 Trace

```java
// 给每个工具加 @Observed
@Tool(description = "搜索知识库")
@Observed(name = "tool.searchKB", contextualName = "tool-call")
public String searchKnowledgeBase(String query) { ... }
```

---

## Day 8-10：测试 + 上下文工程

### Step 9：四层测试

```java
// 单元测试
@Test
void testRouterAgent_classify() {
    assertThat(routerAgent.classify("我的工单到哪了"))
        .isEqualTo(RouterAgent.Intent.TICKET);
}

// Eval 回归
@Test
void evalRegression() {
    var result = evalRunner.evaluate("test-tenant");
    assertThat(result.recallAt5()).isGreaterThanOrEqualTo(0.80);
}
```

### Step 10：上下文裁剪

```java
@Component
public class ContextTrimmingService {

    public String trimIfNeeded(List<Message> history, int maxTokens) {
        int current = estimateTokens(history);
        if (current <= maxTokens) return null;

        // 超过 10 轮时压缩旧消息
        if (history.size() > 10) {
            String summary = chatClient.prompt()
                .system("把以下对话总结成关键信息（200字以内）")
                .user(history.subList(0, 5).stream()
                    .map(Message::getText).collect(Collectors.joining("\n")))
                .call().content();
            return summary;
        }
        return null;
    }
}
```

---

## Sprint 6 验收

- [ ] 所有写操作工具有幂等保护
- [ ] Resilience4j 三层熔断可验证
- [ ] 语义缓存命中率 > 20%（重复问题）
- [ ] Micrometer 指标能看到 token/延迟
- [ ] 步骤级 trace 可查（每个工具调用一个 span）
- [ ] 四层测试覆盖
- [ ] Context Engineering 生效（长对话不爆窗口）

---

## 下一步

→ [Sprint 7：部署 + 管理后台 + 文档](企业项目-Sprint7-部署交付.md)
