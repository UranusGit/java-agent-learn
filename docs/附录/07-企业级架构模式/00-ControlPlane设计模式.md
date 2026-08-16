# Control Plane 设计模式：Agent 系统的管控分离架构

> 「本文是对 [教程 20-管控分离] 的深入展开」

> **定位**：系统讲解 Agent 系统中 Control Plane（管控面）与 Data Plane（数据面/执行面）的分离设计——架构职责划分、控制流与数据流的解耦、策略下发机制、以及 Spring AI 2.0 中的实现模式。
>
> **读者画像**：正在设计多 Agent 编排系统或需要为 Agent 系统添加治理层（限流、审计、策略执行）的架构师。

---

## 1. 为什么 Agent 系统需要管控分离

### 1.1 单体 Agent 的问题

初学者构建的 Agent 系统通常是一个"大泥球"——所有逻辑混在一起：

```mermaid
flowchart TB
    subgraph Monolith["单体 Agent（问题明显）"]
        direction TB
        REQ["接收请求"] --> AUTH["认证授权"]
        AUTH --> POLICY["策略检查"]
        POLICY --> ROUTE["路由决策"]
        ROUTE --> LLM["调用 LLM"]
        LLM --> TOOL["执行工具"]
        TOOL --> LOG["审计日志"]
        LOG --> RESP["返回响应"]
        RESP --> METRIC["指标上报"]
    end

    style Monolith fill: #ffcdd2
```

问题清单：

- **无法独立扩缩容**：LLM 调用瓶颈和审计日志瓶颈绑在一起
- **策略变更需要重新部署**：修改限流规则需要修改业务代码
- **测试困难**：无法单独测试策略执行逻辑
- **多团队协作冲突**：安全团队、业务团队、平台团队修改同一个代码库

### 1.2 管控分离的核心思想

借鉴网络设备（SDN）和服务网格（Istio）的成熟经验，将 Agent 系统拆分为两个平面：

```mermaid
flowchart TB
    subgraph CP["Control Plane（管控面）"]
        direction TB
        PM["策略管理<br/>限流/配额/熔断规则"]
        RM["路由管理<br/>模型选择/Agent 分发"]
        OM["可观测性<br/>指标/日志/追踪"]
        SM["安全管理<br/>认证/授权/审计"]
        CFG["配置中心<br/>动态下发"]
    end

    subgraph DP["Data Plane（数据面 / 执行面）"]
        direction TB
        PROXY["Agent Sidecar<br/>策略执行点"]
        AGENT1["Agent 实例 A"]
        AGENT2["Agent 实例 B"]
        AGENT3["Agent 实例 C"]
    end

    CP -->|"策略下发"| DP
    DP -->|"指标上报"| CP

    style CP fill: #e1f5fe
    style DP fill: #fff3e0
```

| 维度 | Control Plane | Data Plane |
|------|--------------|------------|
| 职责 | 决策"应该做什么" | 执行"具体怎么做" |
| 变更频率 | 高（策略经常调整） | 低（执行逻辑稳定） |
| 状态 | 有状态（存储策略、指标） | 尽量无状态 |
| 部署密度 | 少量实例（1-3 个） | 大量实例（按负载扩缩） |
| 延迟要求 | 秒级可接受 | 毫秒级关键路径 |

---

## 2. Control Plane 的核心组件

### 2.1 组件全景

```mermaid
flowchart TB
    subgraph CP["Control Plane 内部架构"]
        direction TB

        subgraph API["接入层"]
            REST["REST API<br/>管理接口"]
            GRPC["gRPC API<br/>内部通信"]
        end

        subgraph CORE["核心服务"]
            PS["Policy Service<br/>策略引擎"]
            RS["Routing Service<br/>路由决策"]
            MS["Model Registry<br/>模型注册中心"]
            QS["Quota Service<br/>配额管理"]
        end

        subgraph STORE["存储层"]
            DB[("策略库<br/>PostgreSQL")]
            CACHE[("热缓存<br/>Redis")]
            TSDB[("时序数据<br/>指标存储")]
        end

        subgraph OBS["可观测性"]
            METRICS["Metrics Collector"]
            TRACE["Trace Aggregator"]
            AUDIT["Audit Logger"]
        end

        API --> CORE
        CORE --> STORE
        CORE --> OBS
    end

    style CORE fill: #e3f2fd
    style STORE fill: #f3e5f5
```

### 2.2 Policy Service：策略引擎

策略引擎是 Control Plane 的核心——它定义"什么操作在什么条件下被允许"：

```java
@Service
public class PolicyService {

    private final PolicyRepository policyRepo;
    private final RedisTemplate<String, Policy> policyCache;

    /**
     * 评估请求是否满足策略。
     * @return Allow / Deny / RateLimit / RequireApproval
     */
    public PolicyDecision evaluate(AgentRequest request) {
        List<Policy> policies = policyRepo.findByAgentAndAction(
            request.getAgentId(), request.getAction());

        for (Policy policy : policies) {
            PolicyResult result = policy.evaluate(request);
            switch (result.getDecision()) {
                case DENY -> {
                    auditLog.recordDenial(request, policy);
                    return PolicyDecision.deny(result.getReason());
                }
                case RATE_LIMIT -> {
                    if (rateLimiter.tryAcquire(request.getTenantId(), policy.getLimit())) {
                        continue;
                    }
                    return PolicyDecision.rateLimited(policy.getLimit());
                }
                case REQUIRE_APPROVAL -> {
                    return PolicyDecision.requireApproval(policy.getApprover());
                }
            }
        }
        return PolicyDecision.allow();
    }
}
```

### 2.3 Routing Service：智能路由

根据请求特征（成本预算、延迟要求、租户等级）选择最优模型或 Agent：

```mermaid
flowchart LR
    REQ["Agent 请求"] --> RS["Routing Service"]

    RS --> R1{"租户等级?"}
    R1 -->|"Enterprise"| M1["Claude Opus<br/>高质量优先"]
    R1 -->|"Pro"| M2["Claude Sonnet<br/>平衡"]
    R1 -->|"Free"| M3["Qwen2.5<br/>成本优先"]

    RS --> R2{"延迟要求?"}
    R2 -->|"< 2s"| M4["带 Prompt Cache<br/>跳过前缀处理"]
    R2 -->|"< 10s"| M5["标准调用"]

    RS --> R3{"上下文长度?"}
    R3 -->|"> 100K"| M6["Claude (200K)<br/>或 Gemini (2M)"]
    R3 -->|"< 32K"| M7["任意模型"]

    style RS fill: #e3f2fd
```

---

## 3. Data Plane 设计

### 3.1 Agent Sidecar 模式

借鉴 Service Mesh 的 Sidecar 模式，每个 Agent 实例旁部署一个轻量级策略执行代理：

```mermaid
flowchart TB
    subgraph Pod["Agent Pod（容器编排单元）"]
        direction LR
        subgraph App["Agent 容器"]
            AL["Agent Logic<br/>业务逻辑"]
            AS["Agent SDK<br/>Spring AI Client"]
        end

        subgraph Sidecar["Sidecar 容器"]
            SEP["策略执行点<br/>拦截所有请求"]
            TELEM["遥测采集<br/>Metrics/Trace/Log"]
            CB["熔断器<br/>Circuit Breaker"]
            RL["本地限流器<br/>令牌桶"]
        end

        App <-->|"localhost"| Sidecar
    end

    Sidecar -->|"策略同步"| CP["Control Plane"]
    Sidecar -->|"指标上报"| CP

    style Sidecar fill: #fff9c4
```

```java
// Agent SDK 中集成的 Sidecar 客户端
@Component
public class ControlPlaneInterceptor implements Advisor {

    private final ControlPlaneClient cpClient;
    private final CircuitBreaker circuitBreaker;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request,
                                          CallAdvisorChain chain) {
        // 1. 向 Control Plane 请求策略评估
        PolicyDecision decision = cpClient.evaluate(
            request.agentId(), request.action(), request.tenantId());

        // 2. 执行策略
        switch (decision.getType()) {
            case DENY -> throw new PolicyDeniedException(decision.getReason());
            case RATE_LIMITED -> throw new RateLimitedException(decision.getLimit());
            case REDIRECT -> request = request.mutate()
                // javap 实证：Builder 无 .model()——改模型需重建 Prompt 的 ChatOptions
                .prompt(new Prompt(request.prompt().getInstructions(),
                        OpenAiChatOptions.builder().model(decision.getTargetModel()).build()))
                .build();
        }

        // 3. 通过熔断器执行（javap 实证：链方法是 nextCall/nextStream，无 nextAround）
        return circuitBreaker.executeSupplier(() -> chain.nextCall(request));
    }
}
```

### 3.2 Data Plane 的关键设计原则

```mermaid
mindmap
  root((Data Plane<br/>设计原则))
    无状态优先
      所有状态存入 Control Plane
      本地仅保留热缓存
      崩溃后可快速重建
    快速失败
      策略检查 < 5ms
      超时直接降级
      不可阻塞业务流
    可观测
      每次调用都有 Trace
      关键事件审计
      实时指标暴露
    优雅降级
      Control Plane 不可用时
      使用本地缓存策略
      降级为宽松模式
```

---

## 4. 控制流与数据流的解耦

### 4.1 解耦前后的对比

```mermaid
sequenceDiagram
    participant C as 客户端
    participant DP as Data Plane
    participant CP as Control Plane
    participant M as Model Provider

    Note over C,M: 传统模式（耦合）
    C->>DP: 请求
    DP->>CP: 查策略（同步阻塞）
    CP-->>DP: 策略结果
    DP->>M: 调用模型
    M-->>DP: 响应
    DP-->>C: 返回

    Note over C,M: 解耦模式（异步策略同步）
    C->>DP: 请求
    DP->>DP: 本地策略缓存检查
    Note right of DP: 策略已由 CP 预下发
    DP->>M: 调用模型
    M-->>DP: 响应
    DP-->>C: 返回
    DP-)CP: 异步上报指标
```

### 4.2 策略同步机制

Control Plane 的策略变更需要高效同步到所有 Data Plane 实例：

```java
@Service
public class PolicySyncService {

    private final PolicyRepository policyRepo;
    private final SimpMessagingTemplate websocket;

    /**
     * 策略变更时推送到所有 Data Plane 实例。
     */
    @EventListener
    public void onPolicyChanged(PolicyChangedEvent event) {
        Policy policy = policyRepo.findById(event.getPolicyId());

        // 方式一：WebSocket 实时推送（低延迟）
        websocket.convertAndSend("/topic/policies", policy);

        // 方式二：写入共享缓存（最终一致）
        redisTemplate.opsForValue().set(
            "policy:" + policy.getId(), policy, Duration.ofMinutes(5));

        // 方式三：记录变更日志（审计追溯）
        auditLog.recordPolicyChange(event);
    }
}
```

---

## 5. 多租户治理

### 5.1 租户隔离模型

```mermaid
flowchart TB
    subgraph CP["Control Plane"]
        TM["Tenant Manager"]
        QM["Quota Manager"]
        BM["Billing Manager"]
    end

    subgraph TenantA["租户 A（Enterprise）"]
        TA1["配额：100K tokens/day"]
        TA2["模型：Claude Opus 可用"]
        TA3["SLA：99.9%"]
    end

    subgraph TenantB["租户 B（Free）"]
        TB1["配额：1K tokens/day"]
        TB2["模型：仅 Qwen"]
        TB3["SLA：best-effort"]
    end

    CP --> TenantA
    CP --> TenantB

    style CP fill: #e1f5fe
    style TenantA fill: #c8e6c9
    style TenantB fill: #fff3e0
```

### 5.2 配额执行链

```java
@Component
public class QuotaEnforcementAdvisor implements CallAdvisor {

    private final QuotaService quotaService;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request,
                                          CallAdvisorChain chain) {
        String tenantId = (String) request.context().get("tenantId");
        // javap 实证：ChatClientRequest 无 estimatedTokens()/metadata()/nextAround()——
        // 估算需自行实现（基于 request.prompt().getContents()）
        int estimatedTokens = estimateTokens(request);

        // 1. 检查配额
        if (!quotaService.tryConsume(tenantId, estimatedTokens)) {
            // 短路：直接返回 ChatClientResponse（不调用 chain）
            ChatResponse error = ChatResponse.builder()
                    .generations(List.of(new Generation(
                            new AssistantMessage("配额不足，请升级套餐或等待配额刷新"))))
                    .build();
            return ChatClientResponse.builder()
                    .chatResponse(error)
                    .context(request.context())
                    .build();
        }

        // 2. 执行请求
        ChatClientResponse response = chain.nextCall(request);

        // 3. 回补实际消耗（估算 vs 实际）——Usage.getTotalTokens()（javap 实证）
        Usage usage = response.chatResponse().getMetadata().getUsage();
        if (usage != null) {
            quotaService.adjust(tenantId, estimatedTokens, usage.getTotalTokens());
        }

        return response;
    }

    private int estimateTokens(ChatClientRequest request) {
        return estimate(request.prompt().getContents());  // 业务自实现
    }

    @Override
    public String getName() { return "QuotaEnforcementAdvisor"; }
}
}
```

---

## 6. 可观测性架构

### 6.1 三支柱统一

```mermaid
flowchart TB
    subgraph DP["Data Plane"]
        A1["Agent 实例"]
        A2["Agent 实例"]
        A3["Agent 实例"]
    end

    A1 --> M["Metrics<br/>(Prometheus)"]
    A1 --> T["Traces<br/>(OpenTelemetry)"]
    A1 --> L["Logs<br/>(Structured JSON)"]

    M --> GRAF["Grafana Dashboard"]
    T --> JAEGER["Jaeger / Tempo"]
    L --> LOKI["Loki / ELK"]

    GRAF --> ALERT["告警规则"]
    ALERT --> PAGER["PagerDuty / 钉钉"]

    style M fill: #e8f5e9
    style T fill: #e3f2fd
    style L fill: #fff9c4
```

### 6.2 关键指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `agent.request.total` | Counter | 请求总数 |
| `agent.request.duration` | Histogram | 请求延迟分布 |
| `agent.token.consumed` | Counter | Token 消耗总量 |
| `agent.policy.denied` | Counter | 策略拒绝次数 |
| `agent.model.error_rate` | Gauge | 模型调用错误率 |
| `agent.circuit.state` | Gauge | 熔断器状态（0=关闭, 1=打开） |

---

## 7. 参考架构：Spring AI 2.0 实现

```mermaid
flowchart TB
    subgraph Gateway["API Gateway"]
        GW["Spring Cloud Gateway"]
    end

    subgraph CP["Control Plane"]
        PS["Policy Service<br/>@Service"]
        RS["Routing Service<br/>@Service"]
        QS["Quota Service<br/>@Service"]
        OS["Observability Service<br/>@Service"]
    end

    subgraph DP["Data Plane"]
        AC1["ChatClient + Advisor Chain<br/>Agent Pod 1"]
        AC2["ChatClient + Advisor Chain<br/>Agent Pod 2"]
    end

    subgraph Infra["基础设施"]
        PG[("PostgreSQL")]
        RD[("Redis")]
        VS[("Vector Store")]
        LLM["LLM Provider"]
    end

    GW --> DP
    DP <--> CP
    DP --> Infra
    CP --> Infra
```

---

## 8. 总结

Control Plane 设计模式是 Agent 系统从"能跑"走向"企业级可治理"的关键架构升级。本文核心要点：

1. **管控分离的本质**——将"决策"（策略、路由、配额）与"执行"（LLM 调用、工具执行）解耦，各自独立演进和扩缩容。
2. **Control Plane 四大核心服务**——策略引擎（Policy Service）、路由服务（Routing Service）、模型注册中心（Model Registry）、配额服务（Quota Service）。
3. **Data Plane 设计原则**——无状态优先、快速失败（< 5ms 策略检查）、优雅降级（CP 不可用时用本地缓存）。
4. **策略同步机制**——WebSocket 实时推送 + Redis 缓存 + 审计日志，保证最终一致性。
5. **多租户隔离**——通过配额管理和模型路由，实现不同租户等级的资源隔离和差异化服务。
6. **可观测性三支柱**——Metrics（指标监控）、Traces（分布式追踪）、Logs（结构化日志）统一采集，支持全链路审计。

在 [教程 20-管控分离] 中，这些架构模式被应用于具体的 Agent 编排场景，本文提供了设计原则和组件级实现的完整蓝图。
