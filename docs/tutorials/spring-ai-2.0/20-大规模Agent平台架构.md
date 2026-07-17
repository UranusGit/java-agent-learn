# L3 大规模 Agent 平台架构（Spring AI 2.0）

> 本文回答：**当你想把 AI 能力开放给整个公司（甚至外部客户），平台该怎么搭？**
>
> 一个内部 Agent 平台：多租户、Agent 池化、配额计费、SLO、可观测、安全审计 —— 工程化的天花板。
>
> 前置：[`./19-AI原生系统设计.md`](./19-AI原生系统设计.md)
> 预计：2 天

---

## 0. 认知地图

```
L2：自己的 Agent 跑起来
    ↓
L3：
    ├── 单系统设计（19）
    ├── 平台化（本文）         ← 一套基础设施服务 N 个业务方
    ├── 数据基础设施（21）
    └── 可观测性（22）
```

**何时该做平台**：

- 公司内 5+ 团队都在搭自己的 Agent。
- 多个业务方共享同一套 LLM 配额（降本）。
- 需要统一审计、合规、安全。
- 需要把能力开放给外部客户。

---

## 1. 平台 vs 应用的差异

| 维度 | 单应用 | 平台 |
|------|--------|------|
| 用户模型 | 单一业务方 | 多租户（多业务方） |
| 部署 | 业务方自己跑 | 平台跑，业务方接 API |
| 计费 | 公司内部免费 | 按租户计费、配额 |
| Agent 管理 | 业务方自己写 | 平台提供 SDK + 模板 |
| 数据隔离 | 共用 DB | 严格租户隔离 |
| 可演进 | 改自己代码 | 保证 SDK 向下兼容 |

---

## 2. 平台的核心能力

```
┌─────────────────────────────────────────────────────┐
│ Self-Service Portal                                 │
│ - 注册 Agent / 查用量 / 管理密钥 / 查日志            │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│ Gateway（统一入口）                                  │
│ - 鉴权 / 路由 / 限流 / 计费 / 熔断                   │
└──────────────────────┬──────────────────────────────┘
                       ↓
┌──────────────────┬──────────────────┬──────────────┐
│ Agent Pool       │ RAG Service      │ MCP Hub      │
│ - 实例池化        │ - 共享知识库      │ - 工具市场   │
│ - 调度            │ - 隔离索引        │ - 鉴权代理   │
└──────────────────┴──────────────────┴──────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│ Shared Infrastructure                               │
│ - LLM Gateway / Vector DB / Event Store / Cache    │
└─────────────────────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────┐
│ Observability                                       │
│ - Metrics / Logs / Traces / Eval / Audit           │
└─────────────────────────────────────────────────────┘
```

---

## 3. 多租户：架构的核心

### 3.1 三种隔离级别

| 级别 | 实现 | 隔离强度 | 成本 |
|------|------|---------|------|
| **共享一切** | 同 DB 同表，tenant_id 区分 | 弱 | 最低 |
| **共享 DB 独立 schema** | 同 DB 不同 schema | 中 | 中 |
| **独立 DB / 独立部署** | 每租户一套 | 强 | 最高 |

**典型选择**：

- 内部团队（互相认识）：共享一切。
- 中小客户：共享 DB 独立 schema。
- 大客户 / 合规要求高：独立 DB / 独立部署。

### 3.2 tenant_id 的传播

所有数据带 tenant_id：

```java
public class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void set(String tenantId) { CURRENT.set(tenantId); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}

// Filter 中解析租户
@Component
public class TenantFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, ...) {
        String tenantId = req.getHeader("X-Tenant-Id");
        if (tenantId == null || !tenantService.exists(tenantId)) {
            resp.sendError(401, "Invalid tenant");
            return;
        }
        TenantContext.set(tenantId);
        try {
            chain.doFilter(req, resp);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### 3.3 数据库行级隔离

用 PostgreSQL RLS（Row-Level Security）做硬隔离：

```sql
ALTER TABLE agent_call_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON agent_call_log
    USING (tenant_id = current_setting('app.current_tenant')::text);

-- 应用每次连接前设当前租户
SET app.current_tenant = 'tenant-001';
```

**好处**：即使 SQL 漏了 `WHERE tenant_id=`，数据库层也会过滤，不会跨租户泄漏。

### 3.4 Vector Store 多租户

PostgreSQL pgvector 也支持 RLS：

```sql
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_chunks
    USING (tenant_id = current_setting('app.current_tenant'));
```

检索时自动按租户过滤。

或者用独立 collection（每个租户一个 namespace）：

```java
public class TenantVectorStore {
    public void add(String tenantId, List<Document> docs) {
        // 用 tenantId 做 namespace
        docs.forEach(d -> d.getMetadata().put("tenant_id", tenantId));
        delegate.add(docs);
    }

    public List<Document> search(String tenantId, String query, int topK) {
        return delegate.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .filterExpression("tenant_id == '" + tenantId + "'")
                        .topK(topK)
                        .build());
    }
}
```

---

## 4. Agent 池化

### 4.1 为什么需要池化

每个租户的 Agent 是一个有状态对象（含 ChatClient 配置、Memory、工具集）。如果每次请求都创建，开销大。如果共享，会污染状态。

**池化**：每个租户一个 Agent 池，请求来时从池里借一个。

### 4.2 实现

```java
// org.demo02.platform.agent.AgentPool
// 本代码仅作学习材料参考

@Component
public class AgentPool {

    private final ConcurrentHashMap<String, BlockingQueue<AgentInstance>> pools = new ConcurrentHashMap<>();
    private final AgentFactory factory;

    public AgentInstance acquire(String agentId, String tenantId) {
        String key = tenantId + ":" + agentId;
        BlockingQueue<AgentInstance> pool = pools.computeIfAbsent(key,
                k -> new ArrayBlockingQueue<>(10));
        
        AgentInstance instance = pool.poll();
        if (instance == null) {
            instance = factory.create(agentId, tenantId);
        }
        return instance;
    }

    public void release(String agentId, String tenantId, AgentInstance instance) {
        String key = tenantId + ":" + agentId;
        BlockingQueue<AgentInstance> pool = pools.get(key);
        if (pool != null && !pool.offer(instance)) {
            // 池满了，丢弃
            instance.close();
        }
    }
}

// 使用
public class AgentExecutor {
    public String execute(String agentId, String tenantId, String input) {
        AgentInstance agent = pool.acquire(agentId, tenantId);
        try {
            return agent.run(input);
        } finally {
            pool.release(agentId, tenantId, agent);
        }
    }
}
```

### 4.3 Agent 配置加载

每个 Agent 配置存 DB，按需加载：

```sql
CREATE TABLE agent_definition (
    agent_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255),
    system_prompt TEXT,
    model_config JSONB,
    tools JSONB,
    memory_config JSONB,
    version VARCHAR(32)
);
```

```java
@Component
public class AgentFactory {

    private final JdbcTemplate jdbc;

    public AgentInstance create(String agentId, String tenantId) {
        AgentDefinition def = loadDefinition(agentId);
        ChatModel model = modelRegistry.get(def.modelConfig().modelId());
        ChatClient client = ChatClient.builder(model)
                .defaultSystem(def.systemPrompt())
                .defaultTools(loadTools(def.tools(), tenantId))
                .defaultAdvisors(buildAdvisors(tenantId))
                .build();
        return new AgentInstance(client, def, tenantId);
    }
}
```

---

## 5. 配额与计费

### 5.1 多层配额

```sql
CREATE TABLE quota (
    tenant_id VARCHAR(64),
    resource_type VARCHAR(32),     -- 'llm_tokens' / 'agent_calls' / 'rag_queries'
    scope VARCHAR(32),              -- 'daily' / 'monthly'
    limit_value BIGINT,
    used_value BIGINT DEFAULT 0,
    period_start TIMESTAMP,
    period_end TIMESTAMP,
    PRIMARY KEY (tenant_id, resource_type, scope, period_start)
);
```

**典型配额**：

- 每租户每天 LLM token 上限
- 每租户每月 Agent 调用次数
- 每租户每秒最大 RPS
- 单次请求最大 token 数

### 5.2 计费

```java
// org.demo02.platform.billing.BillingService
// 本代码仅作学习材料参考

@Service
public class BillingService {

    public void record(String tenantId, Usage usage) {
        BigDecimal cost = pricingCalculator.compute(usage);
        
        // 记到明细
        jdbc.update("""
            INSERT INTO billing_record (tenant_id, model, input_tokens, 
                                       output_tokens, cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            """, tenantId, usage.model(), usage.inputTokens(),
                usage.outputTokens(), cost);

        // 累加到配额
        quotaService.consume(tenantId, "llm_tokens", 
                usage.inputTokens() + usage.outputTokens());
        quotaService.consume(tenantId, "cost_usd", cost.doubleValue());
    }

    public BigDecimal monthlyBill(String tenantId, YearMonth month) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(cost_usd), 0) FROM billing_record
            WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
            """, BigDecimal.class,
                tenantId,
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
    }
}
```

### 5.3 实时熔断

```java
@Component
public class QuotaGuard {
    
    public void check(String tenantId, int estimatedTokens) {
        if (!quotaService.tryConsume(tenantId, "llm_tokens", estimatedTokens)) {
            throw new QuotaExceededException("Daily token quota exceeded");
        }
        if (!quotaService.tryConsume(tenantId, "cost_usd", 
                pricingCalculator.estimate(estimatedTokens))) {
            throw new QuotaExceededException("Daily cost quota exceeded");
        }
    }
}
```

---

## 6. SLO 与可用性

### 6.1 SLO 定义

| 指标 | 目标 |
|------|------|
| API 可用性 | 99.9%（每月停机 < 43 分钟） |
| P95 延迟 | < 5s |
| 错误率 | < 0.5% |
| Token 计费准确率 | 100% |

### 6.2 多层 Fallback

```
正常路径：主 LLM → 主 RAG → 主 Vector Store
    ↓ 主 LLM 挂
    → 备 LLM（同档不同供应商）
    ↓ 备 LLM 也挂
    → 自托管 LLM（Llama 3）
    ↓ 全挂
    → 缓存命中或降级响应（"服务繁忙，请稍后重试"）
```

```java
public class ResilientChatClient {
    
    public ChatResponse call(ChatRequest req) {
        // 链式 fallback
        return tryCall(primaryClient, req)
                .onErrorResume(e -> tryCall(secondaryClient, req))
                .onErrorResume(e -> tryCall(tertiaryClient, req))
                .onErrorResume(e -> cachedOrDegraded(req))
                .block();
    }
}
```

### 6.3 健康检查

```java
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping("/llm-providers")
    public Map<String, Boolean> checkLlmProviders() {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, ChatModel> entry : providers.entrySet()) {
            try {
                entry.getValue().call("ping");
                result.put(entry.getKey(), true);
            } catch (Exception e) {
                result.put(entry.getKey(), false);
            }
        }
        return result;
    }
}
```

---

## 7. 自助门户（Self-Service Portal）

让业务方自己管理 Agent，不用提工单。

### 7.1 核心功能

- 注册 / 配置 Agent（system prompt、工具、模型选择）
- 申请 / 轮换 API Key
- 查实时用量 / 历史账单
- 查调用日志 / trace
- 配置 Webhook（接收 Agent 事件）

### 7.2 配置示例

```yaml
# 业务方在 Portal 填写，平台存 DB
agent:
  name: "客服助手 - 在线零售"
  system_prompt: |
    你是 XX 公司的客服，处理订单、退款、物流问题...
  model:
    primary: claude-sonnet-4-5
    fallback: deepseek-v3
  tools:
    - order_tools          # 平台预置
    - shipping_tools
    - custom_tools         # 业务方自己注册
  memory:
    type: window
    max_messages: 20
  rag:
    knowledge_base: "retail-kb-001"
  rate_limit:
    rps: 10
    daily_tokens: 5000000
```

业务方提交后，平台热加载 Agent 配置，无需重启。

---

## 8. MCP Hub：工具市场

### 8.1 概念

平台维护一个"工具市场"，所有 MCP Server 注册在 Hub 上，业务方按需订阅。

```
┌──────────────────────────────────┐
│ MCP Hub                          │
│ ┌─────────┐ ┌─────────┐         │
│ │ HR Tool │ │ OA Tool │  ...    │
│ └─────────┘ └─────────┘         │
└──────────┬───────────────────────┘
           ↓ 通过权限代理
┌──────────────────────────────────┐
│ Agent of Tenant A                │
│ - 订阅：HR Tool, OA Tool         │
└──────────────────────────────────┘
           ↓
┌──────────────────────────────────┐
│ Agent of Tenant B                │
│ - 订阅：OA Tool                   │
└──────────────────────────────────┘
```

### 8.2 权限模型

```sql
CREATE TABLE mcp_subscription (
    tenant_id VARCHAR(64),
    mcp_server_id VARCHAR(64),
    granted_tools JSONB,    -- 允许调用哪些 tool
    granted_at TIMESTAMP,
    granted_by VARCHAR(64),
    PRIMARY KEY (tenant_id, mcp_server_id)
);
```

Agent 调用工具时，Hub 先检查订阅状态。

---

## 9. 安全审计

### 9.1 所有敏感操作落审计

```java
@Aspect
@Component
public class AuditAspect {

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        String tenantId = TenantContext.get();
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        String action = audited.value();
        
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                tenantId, userId,
                action,
                serialize(pjp.getArgs()),
                null,   // result，记录后再填
                null    // error
        );
        
        try {
            Object result = pjp.proceed();
            entry = entry.withResult(serialize(result));
            auditRepo.save(entry);
            return result;
        } catch (Throwable e) {
            entry = entry.withError(e.getMessage());
            auditRepo.save(entry);
            throw e;
        }
    }
}
```

### 9.2 审计日志的查询与导出

```sql
-- 合规导出：某租户近 30 天所有敏感操作
SELECT * FROM audit_log
WHERE tenant_id = ? AND created_at > NOW() - INTERVAL '30 days'
ORDER BY created_at;
```

合规部门定期导出审计报告。

---

## 10. 实战避坑

### 10.1 "多租户数据意外泄漏"

**症状**：A 租户能看到 B 租户的对话历史。

**根因**：

- Memory 按 sessionId 隔离但 sessionId 没带 tenantId。
- SQL 漏了 `WHERE tenant_id=`。
- Vector Store 检索没过滤 tenant。

**解决**：

- 用 PostgreSQL RLS 兜底（见 §3.3）。
- 自动化测试加跨租户访问 case。
- Code Review 重点关注任何跨表查询。

### 10.2 "Agent 池内存泄漏"

**症状**：长时间运行后 OOM。

**根因**：池里堆积了过期 Agent（Agent 内的 ChatClient 持有大对象）。

**解决**：

- 池里设最大空闲时间（如 30 分钟），过期清掉。
- 每天低峰期 reset 整个池。

### 10.3 "配额扣了但 LLM 调用失败"

**症状**：用户配额被扣但没收到结果。

**解决**：

- 配额扣减和 LLM 调用放在同一事务（成功才扣）。
- 或者扣减后失败回滚（要保证幂等）。

### 10.4 "Agent 配置热更新出 bug"

**症状**：业务方改了 prompt，平台加载失败，整个 Agent 不可用。

**解决**：

- 加载前 schema 校验。
- 加载失败保留旧版本。
- 灰度更新（10% 流量先验证）。

### 10.5 "审计日志爆炸"

**症状**：每天 GB 级审计日志，存储成本高。

**解决**：

- 热数据保留 30 天（PostgreSQL）。
- 冷数据归档到对象存储（S3 / OSS）。
- 大字段（如完整 prompt）单独存对象，日志里只存引用。

---

## 11. 实战任务

1. 给你的应用加 `TenantContext` + `TenantFilter`，所有数据带 tenant_id。
2. 启用 PostgreSQL RLS，做硬隔离测试（A 租户不能查 B 租户数据）。
3. 实现 `AgentPool`，给每个 Agent 一个 10 实例的池。
4. 实现配额三层：日 token、月 cost、单次 max。
5. 实现 Fallback 链：主 LLM → 备 LLM → 自托管 → 降级响应。
6. 给所有敏感操作加 `@Audited` AOP，记录到审计表。
7. （进阶）做一个最小 Self-Service Portal，业务方可以在线编辑 Agent 配置。
8. （选做）实现 MCP Hub 的权限模型（订阅 + 工具级粒度）。

---

## 12. 理解检查

1. 平台架构 vs 单应用架构的核心差异是什么？
2. 三种多租户隔离级别各自适合什么场景？
3. Agent 池化解决什么问题？怎么避免内存泄漏？
4. 配额扣减和 LLM 调用的事务怎么保证一致性？
5. 多层 Fallback 链的设计原则？
6. MCP Hub 的权限模型怎么设计？

---

## 13. 进 L3 下一篇之前的能力确认

完成本篇你应该能：

- [ ] 设计多租户隔离方案
- [ ] 实现 Agent 池化（带生命周期管理）
- [ ] 设计配额与计费体系
- [ ] 实现 Fallback 链保证 SLO
- [ ] 实现审计日志 + 合规导出
- [ ] 思考 Self-Service Portal 的产品设计

---

## 14. 相关文档

- [`./19-AI原生系统设计.md`](./19-AI原生系统设计.md) —— 系统设计基础
- [`./15-MCP-Server端实战.md`](./15-MCP-Server端实战.md) —— MCP Hub 的实现
- [`./18-多模型路由与成本治理.md`](./18-多模型路由与成本治理.md) —— 计费的细节
- [`./22-Agent可观测性完整栈.md`](./22-Agent可观测性完整栈.md) —— 平台可观测性
- [Building LLM Apps for Production](https://www.oreilly.com/library/view/building-llm-apps/)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
