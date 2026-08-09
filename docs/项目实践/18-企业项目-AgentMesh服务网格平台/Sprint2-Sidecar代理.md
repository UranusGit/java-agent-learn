# Sprint 2: Sidecar 代理

> **目标**：Sidecar 代理统一处理限流、熔断、重试。

---

## Sidecar 架构

```mermaid
flowchart LR
    Agent["Agent 容器"] <-->|"localhost<br/>透明代理"| Sidecar["Sidecar Proxy"]
    Sidecar --> Limit["限流器<br/>QPS/TPM"]
    Sidecar --> Breaker["熔断器<br/>Circuit Breaker"]
    Sidecar --> Retry["重试器<br/>指数退避"]
    Sidecar --> Target["目标 Agent"]

    style Sidecar fill:#2196f3,color:#fff
```

---

## V1: 限流器

```java
@Component
public class SidecarRateLimiter {

    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public boolean tryAcquire(String serviceName, int permits) {
        RateLimiter limiter = limiters.computeIfAbsent(serviceName,
            k -> RateLimiter.create(config.getRateLimit(serviceName)));
        return limiter.tryAcquire(permits);
    }

    public Response handle(Request request) {
        String target = request.targetService();

        // 1. QPS 限流
        if (!tryAcquire(target, 1)) {
            return Response.tooManyRequests();
        }

        // 2. TPM 限流（Agent 特有：Token/分钟）
        int estimatedTokens = estimateTokens(request);
        if (!tokenLimiter.tryAcquire(target, estimatedTokens)) {
            return Response.tooManyRequests("TPM 超限");
        }

        // 3. 转发
        return forward(request);
    }
}
```

---

## V2: 熔断器

```java
@Component
public class SidecarCircuitBreaker {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public Response handle(Request request) {
        CircuitBreaker cb = breakers.computeIfAbsent(
            request.targetService(),
            k -> CircuitBreaker.builder()
                .failureRateThreshold(50)       // 失败率 > 50% 触发
                .slowCallRateThreshold(60)      // 慢调用 > 60% 触发
                .slowCallDurationThreshold(5, SECONDS)
                .waitDurationInOpenState(30, SECONDS)
                .slidingWindowSize(20)
                .build());

        return cb.executeSupplier(() -> forward(request));
    }
}
```

---

## V3: 重试 + 指数退避

```mermaid
flowchart TD
    Req["请求"] --> Attempt1["第 1 次"]
    Attempt1 --> Q1{"成功？"}
    Q1 -->|"是"| Done["返回 ✅"]
    Q1 -->|"429/503"| Wait1["退避 200ms"]
    Wait1 --> Attempt2["第 2 次"]
    Attempt2 --> Q2{"成功？"}
    Q2 -->|"是"| Done
    Q2 -->|"429/503"| Wait2["退避 400ms"]
    Wait2 --> Attempt3["第 3 次"]
    Attempt3 --> Q3{"成功？"}
    Q3 -->|"是"| Done
    Q3 -->|"否"| Fail["失败 ❌"]

    style Done fill:#4caf50,color:#fff
    style Fail fill:#f44336,color:#fff
```

```java
@Component
public class SidecarRetryHandler {

    public Response handleWithRetry(Request request) {
        int maxRetries = config.getMaxRetries(request.targetService());
        long baseDelay = 200; // ms

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Response response = forward(request);

                // 可重试的状态码
                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    if (attempt < maxRetries) {
                        long delay = baseDelay * (1L << attempt); // 指数退避
                        Thread.sleep(delay + jitter(delay));
                        continue;
                    }
                }

                return response;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries && isRetryable(e)) {
                    try {
                        Thread.sleep(baseDelay * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("重试耗尽", lastError);
    }

    private long jitter(long delay) {
        return ThreadLocalRandom.current().nextLong(delay / 2);
    }
}
```

---

## 三层防护组合

```mermaid
flowchart TD
    Req["出站请求"] --> Limit{"限流通过？"}
    Limit -->|"否"| R429["返回 429"]
    Limit -->|"是"| CB{"熔断器开？"}
    CB -->|"开"| R503["返回 503"]
    CB -->|"关"| Send["发送请求"]
    Send --> Q1{"成功？"}
    Q1 -->|"是"| CB_OK["记录成功"]
    Q1 -->|"可重试失败"| Retry["重试"]
    Q1 -->|"不可重试失败"| CB_FAIL["记录失败"]
    Retry --> Q2{"重试成功？"}
    Q2 -->|"是"| CB_OK
    Q2 -->|"否"| CB_FAIL

    style R429 fill:#ff9800,color:#fff
    style R503 fill:#f44336,color:#fff
    style CB_OK fill:#4caf50,color:#fff
```

| 防护层 | 保护什么 | 触发条件 | 恢复方式 |
|--------|---------|---------|---------|
| 限流 | 下游过载 | QPS/TPM 超限 | 滑动窗口 |
| 熔断 | 连锁故障 | 失败率 > 阈值 | 定时探测 |
| 重试 | 偶发失败 | 429/503 | 指数退避 |
