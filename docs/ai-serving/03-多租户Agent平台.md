# 03 多租户 Agent 平台

> 把"一个 Agent"做成"100 个租户共享的 Agent 平台"。
>
> 前置：[`./01-推理网关.md`](./01-推理网关.md) + [`./02-向量库选型与治理.md`](./02-向量库选型与治理.md) + [`../tutorials/spring-ai-2.0/18-大规模Agent平台与数据基础设施.md`](../tutorials/spring-ai-2.0/18-大规模Agent平台与数据基础设施.md)
> 预计：3-4 周

---

## 0. 认知地图

```
多租户 Agent 平台
├── 隔离层
│   ├── 数据隔离（向量库 / ChatMemory / 文件）
│   ├── 资源隔离（quota / rate limit / 并发）
│   └── 模型隔离（per-tenant 模型权限）
│
├── 编排层
│   ├── Agent 注册中心
│   ├── Workflow 引擎
│   └── MCP Hub（连接所有工具）
│
├── 共享层
│   ├── 推理网关（01 篇）
│   ├── 向量库（02 篇）
│   └── Tool 仓库
│
└── 治理层
    ├── 配额与计费（04 篇）
    ├── 监控与告警（05 篇）
    └── 租户运营后台
```

**心法**：单租户系统是"做加法"——加功能；多租户系统是"做减法"——把任何会跨租户串数据的代码、任何会让一个租户拖垮全平台的资源都隔离掉。

---

## 1. 多租户的核心矛盾

### 1.1 矛盾 1：隔离 vs 共享

```
完全隔离（每租户独立部署）         完全共享（所有租户同一套）
├── 数据安全 ✅                   ├── 成本低 ✅
├── 性能稳定 ✅                   ├── 运维简单 ✅
├── 成本高 ❌                     ├── 数据串风险 ❌
└── 运维复杂 ❌                   └── 噪音邻居 ❌
```

**生产实践**：分层隔离。

- 大租户（KA）：独立 namespace / 独立向量库 collection / 独立 ChatMemory 表
- 中租户：共享向量库 + per-tenant partition
- 小租户：完全共享 + filter 隔离

### 1.2 矛盾 2：定制 vs 标准

租户会要求"加一个工具"、"换一个模型"、"改 prompt"。

**做法**：

- 平台提供**标准能力**（基础工具集 / 标准 prompt 模板 / 标准模型路由）
- 允许**租户级覆盖**（per-tenant 配置覆盖默认）
- 拒绝**租户级硬编码**（不要让某个租户的逻辑进核心代码）

### 1.3 矛盾 3：性能 vs 安全

性能优先会想去掉 filter，安全优先会强制 filter。

**做法**：安全永远胜出。性能优化做在索引层 / 隔离层，不在业务层"跳过 filter"。

---

## 2. 租户模型

### 2.1 租户实体

```sql
CREATE TABLE tenant (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(128),
    tier            VARCHAR(16),       -- KA / STANDARD / STARTUP / TRIAL
    status          VARCHAR(16),       -- ACTIVE / SUSPENDED / TERMINATED
    created_at      TIMESTAMP,
    settings        JSONB              -- per-tenant 配置覆盖
);

CREATE TABLE tenant_user (
    tenant_id       VARCHAR(64),
    user_id         VARCHAR(64),
    role            VARCHAR(16),       -- ADMIN / MEMBER / VIEWER
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE tenant_subscription (
    tenant_id       VARCHAR(64),
    module_code     VARCHAR(64),       -- 'rag' / 'agent' / 'mcp.hr' 等
    tier            VARCHAR(16),       -- BASIC / PRO / ENTERPRISE
    quota_daily     INT,               -- 每日 token 上限
    quota_monthly   INT,
    expires_at      TIMESTAMP,
    PRIMARY KEY (tenant_id, module_code)
);
```

### 2.2 Tier 体系（建议四档）

| Tier | 配置 | 适用 |
|------|------|------|
| **TRIAL** | 共享资源 / 1000 tokens/天 / 7 天到期 | 试用 |
| **STARTUP** | 共享资源 / 10 万 tokens/天 | 中小团队 |
| **STANDARD** | per-tenant partition / 100 万 tokens/天 | 一般企业 |
| **KA** | 独立资源 / 无限 token（按量付费）/ SLA 保障 | 大客户 |

**关键设计**：Tier 决定**隔离强度**，不决定**功能**。所有 Tier 都能用全部功能，区别在性能/容量。

### 2.3 上下文传递

```java
// 本代码仅作学习材料参考
public record TenantContext(
    String tenantId,
    String userId,
    String tier,
    Set<String> subscribedModules,
    Map<String, Object> settings,
    QuotaSnapshot quota
) {
    public static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    public static TenantContext current() {
        TenantContext ctx = HOLDER.get();
        if (ctx == null) throw new IllegalStateException("No tenant context");
        return ctx;
    }

    public static void set(TenantContext ctx) { HOLDER.set(ctx); }
    public static void clear() { HOLDER.remove(); }
}
```

**通过 Filter 注入**：

```java
// 本代码仅作学习材料参考
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantService tenantService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                     FilterChain chain) throws IOException, ServletException {
        String tenantId = extractTenant(req);   // 从 JWT / Header / Path
        if (tenantId == null) {
            resp.sendError(401, "Missing tenant");
            return;
        }

        TenantContext ctx = tenantService.loadContext(tenantId, userId(req));
        TenantContext.set(ctx);

        try {
            chain.doFilter(req, resp);
        } finally {
            TenantContext.clear();   // 关键：防止线程池串租户
        }
    }
}
```

**异步任务**也要传：

```java
// 本代码仅作学习材料参考
ExecutorService tenantAwareExecutor = new ThreadPoolExecutor(...) {
    @Override
    public void execute(Runnable command) {
        TenantContext ctx = TenantContext.current();
        super.execute(() -> {
            TenantContext.set(ctx);
            try {
                command.run();
            } finally {
                TenantContext.clear();
            }
        });
    }
};
```

**这是多租户系统最常见的坑**：忘了传 TenantContext，结果异步线程里查到别人的数据。

---

## 3. 数据隔离实现

### 3.1 三层隔离策略

```
应用层（强制 filter）  ← 第一道防线，永远不能省
        ↓
数据库层（partition / collection）  ← 性能优化，可选
        ↓
部署层（独立 DB / 独立服务）  ← KA 才上，最重
```

### 3.2 关系库隔离

```java
// 本代码仅作学习材料参考
@MappedSuperclass
public abstract class TenantAwareEntity {
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
}

// 所有业务 Entity 继承
@Entity
public class Agent extends TenantAwareEntity {
    @Id private String id;
    private String name;
    // ...
}

// Repository 自动加 filter
@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    @Query("SELECT a FROM Agent a WHERE a.tenantId = :tenantId")
    List<Agent> findAllForCurrentTenant(@Param("tenantId") String tenantId);
}
```

**更进一步**：用 Hibernate `@FilterDef` 自动注入：

```java
// 本代码仅作学习材料参考
@Entity
@FilterDef(name = "tenant", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenant", condition = "tenant_id = :tenantId")
public class Agent extends TenantAwareEntity { ... }

// 拦截器自动启用
@Component
public class TenantFilterEnabler {
    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object enableFilter(ProceedingJoinPoint pjp) throws Throwable {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("tenant").setParameter("tenantId", TenantContext.current().tenantId());
        return pjp.proceed();
    }
}
```

**好处**：所有查询自动加 `WHERE tenant_id = ?`，业务代码不用关心。

### 3.3 ChatMemory 隔离

Spring AI 的 `ChatMemoryRepository` 本来就支持 per-conversation：

```java
// 本代码仅作学习材料参考
@Bean
public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbc) {
    return new JdbcChatMemoryRepository(jdbc);
}

// conversation_id 用 "{tenantId}-{conversationId}" 命名，强制隔离
String conversationId = tenantId + "-" + userConversationId;
chatMemory.add(conversationId, message);
```

### 3.4 向量库隔离（详见 02 篇）

```java
// 本代码仅作学习材料参考
public class TenantAwareVectorStore implements VectorStore {

    private final VectorStore delegate;

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // 强制注入 tenantId filter
        SearchRequest tenantRequest = request.mutate()
            .filterExpression("tenant_id == '" + TenantContext.current().tenantId() + "'")
            .build();
        return delegate.similaritySearch(tenantRequest);
    }
}

// 包装所有 VectorStore
@Bean
public VectorStore vectorStore(VectorStore raw) {
    return new TenantAwareVectorStore(raw);
}
```

**关键**：**所有业务代码永远不直接调原始 VectorStore**，必须走 TenantAwareVectorStore。

### 3.5 文件 / 对象存储隔离

```
s3://platform-attachments/
├── tenant-aaa/        ← 每租户独立 prefix
│   ├── conversation-123/
│   │   └── upload-001.pdf
│   └── ...
├── tenant-bbb/
└── ...
```

签名 URL 时强制带 prefix：

```java
// 本代码仅作学习材料参考
public String signUploadUrl(String key) {
    String tenant = TenantContext.current().tenantId();
    if (!key.startsWith("tenant-" + tenant + "/")) {
        throw new SecurityException("Cross-tenant access denied");
    }
    return s3Client.presignPutObject(key);
}
```

---

## 4. 资源隔离

### 4.1 配额体系

```
租户
├── 日 token 配额（total）
├── 月 token 配额（total）
├── 每模型配额（避免某租户全用 GPT-4）
└── 每业务模块配额（rag / agent / mcp.*）
```

### 4.2 配额表

```sql
CREATE TABLE quota_usage (
    tenant_id       VARCHAR(64),
    resource        VARCHAR(64),    -- 'token.total' / 'token.gpt-4o' / 'mcp.hr'
    period_date     DATE,
    consumed        BIGINT,
    PRIMARY KEY (tenant_id, resource, period_date)
);
```

### 4.3 限流（per-tenant）

```java
// 本代码仅作学习材料参考
@Component
public class TenantRateLimiter {

    private final LoadingCache<String, RateLimiter> limiters = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build(tenantId -> RateLimiter.create(quotaFor(tenantId)));

    public void acquire(String tenantId) {
        if (!limiters.getUnchecked(tenantId).tryAcquire()) {
            throw new RateLimitedException(tenantId);
        }
    }
}

// 在网关入口拦截
@Around("execution(* gateway.*.*(..))")
public Object rateLimit(ProceedingJoinPoint pjp) throws Throwable {
    String tenantId = TenantContext.current().tenantId();
    rateLimiter.acquire(tenantId);
    return pjp.proceed();
}
```

### 4.4 并发隔离（防止噪音邻居）

```java
// 本代码仅作学习材料参考
@Configuration
public class TenantBulkheadConfig {

    // 每租户独立 Bulkhead，互不影响
    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(10)
            .maxWaitDuration(Duration.ofSeconds(1))
            .build());
    }
}

// 使用
public Response call(TenantContext ctx, Request req) {
    Bulkhead bulkhead = bulkheadRegistry.bulkhead(ctx.tenantId());
    return Bulkhead.executeSupplier(bulkhead, () -> doCall(req));
}
```

**KA 提到 50 并发，STARTUP 限 10**。

---

## 5. Agent 注册中心

### 5.1 为什么需要注册中心

单 Agent 系统里 Agent 是写死的。多租户平台需要：

- 业务方/租户**自助注册** Agent
- 运维**统一管控**（下线 / 限流 / 监控）
- Agent **复用**（A 租户的 Agent 模板能被 B 租户 fork）

### 5.2 Agent 元数据

```sql
CREATE TABLE agent (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    name            VARCHAR(128),
    description     TEXT,
    type            VARCHAR(32),      -- CHAT / WORKFLOW / AUTONOMOUS
    config          JSONB,            -- prompt / tools / model preference
    status          VARCHAR(16),      -- DRAFT / PUBLISHED / DEPRECATED
    version         INT,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP,
    INDEX idx_tenant_status (tenant_id, status)
);

CREATE TABLE agent_template (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(128),
    description     TEXT,
    config          JSONB,            -- 同 agent.config，作为 fork 基线
    is_official     BOOLEAN,          -- 平台官方模板
    created_at      TIMESTAMP
);
```

### 5.3 Agent 实体

```java
// 本代码仅作学习材料参考
public record AgentConfig(
    String systemPrompt,
    List<ToolBinding> tools,           // 绑定到哪些工具（注册过的）
    List<AdvisorSpec> advisors,        // RAG / Memory / Citation 等
    ModelPreference modelPreference,   // COST / LATENCY / QUALITY
    int maxTurns,
    Map<String, Object> metadata
) {}

public record ToolBinding(
    String toolId,                     // 工具在 ToolRegistry 的 ID
    boolean required,                  // 必备工具 vs 可选
    Map<String, String> configOverride // 覆盖默认配置
) {}
```

### 5.4 Agent 调用入口

```java
// 本代码仅作学习材料参考
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final AgentRepository agentRepo;
    private final ToolRegistry toolRegistry;
    private final LlmGateway gateway;
    private final ChatMemory chatMemory;

    public ChatResponse execute(String agentId, String conversationId, String userMessage) {
        Agent agent = agentRepo.findByIdAndTenant(agentId, TenantContext.current().tenantId());
        if (agent == null || agent.status() != PUBLISHED) {
            throw new AgentNotFoundException(agentId);
        }

        AgentConfig config = agent.config();

        // 组装工具
        List<ToolCallback> tools = config.tools().stream()
            .map(b -> toolRegistry.resolve(b.toolId(), b.configOverride()))
            .toList();

        // 组装 ChatClient
        ChatClient client = ChatClient.builder(gateway.chatModel())
            .defaultSystem(config.systemPrompt())
            .defaultTools(tools.toArray(new ToolCallback[0]))
            .defaultAdvisors(buildAdvisors(config, conversationId))
            .build();

        // 调用
        return client.prompt()
            .user(userMessage)
            .advisors(a -> a.param("tenantId", TenantContext.current().tenantId())
                           .param("agentId", agentId))
            .call()
            .chatResponse();
    }
}
```

---

## 6. MCP Hub 真实实现

> 详见 [`../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md`](../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md) §2 的设计层。本节聚焦落地。

### 6.1 Hub 在多租户平台中的定位

```
平台 Agent
   ↓ tools/call
MCP Hub（按租户订阅过滤 + 配额 + 审计）
   ↓ 路由
MCP Server Pool
├── hr-mcp（HR 部门维护）
├── erp-mcp（业务中台维护）
├── geo-mcp（公共）
├── tenantA-custom-mcp（租户自建）
└── ...
```

### 6.2 订阅模型

```sql
CREATE TABLE mcp_server (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(128) UNIQUE,
    description     TEXT,
    endpoint        VARCHAR(512),
    transport       VARCHAR(16),       -- SSE / STREAMABLE_HTTP
    owner_type      VARCHAR(16),       -- PLATFORM / TENANT
    owner_id        VARCHAR(64),       -- 平台级为 NULL，租户级为 tenantId
    status          VARCHAR(16),       -- ACTIVE / DEPRECATED
    health_check_url VARCHAR(512),
    registered_at   TIMESTAMP
);

CREATE TABLE mcp_subscription (
    tenant_id       VARCHAR(64),
    mcp_server_id   VARCHAR(64),
    granted_tools   JSONB,              -- NULL = 全部，数组 = 白名单
    daily_quota     INT,
    granted_at      TIMESTAMP,
    granted_by      VARCHAR(64),
    PRIMARY KEY (tenant_id, mcp_server_id)
);
```

### 6.3 Hub 服务

```java
// 本代码仅作学习材料参考
@Service
@RequiredArgsConstructor
public class McpHubService {

    private final McpServerRegistry registry;
    private final McpSubscriptionService subscription;
    private final McpProxyRouter router;
    private final QuotaService quota;

    public List<ToolSchema> listToolsForCurrentTenant() {
        TenantContext ctx = TenantContext.current();
        List<McpServerInfo> allowed = subscription.serversFor(ctx.tenantId());

        List<ToolSchema> all = new ArrayList<>();
        for (McpServerInfo server : allowed) {
            Set<String> whitelist = subscription.grantedTools(ctx.tenantId(), server.id());
            List<ToolSchema> tools = router.listToolsFrom(server);
            if (whitelist != null) {
                tools = tools.stream().filter(t -> whitelist.contains(t.name())).toList();
            }
            all.addAll(tools);
        }
        return all;
    }

    public CallToolResult callTool(String toolName, Map<String, Object> args) {
        TenantContext ctx = TenantContext.current();
        McpServerInfo server = registry.findByToolName(toolName);

        if (!subscription.canAccess(ctx.tenantId(), server.id(), toolName)) {
            throw new McpForbiddenException(ctx.tenantId(), server.id(), toolName);
        }

        quota.checkMcpQuota(ctx.tenantId(), server.id());

        AuditContext.set(ctx.tenantId(), server.id(), toolName, args);
        return router.forward(server, new CallToolRequest(toolName, args));
    }
}
```

### 6.4 路由器

```java
// 本代码仅作学习材料参考
@Component
public class McpProxyRouter {

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final CircuitBreakerRegistry breakers;

    public List<ToolSchema> listToolsFrom(McpServerInfo server) {
        return breakerFor(server).executeSupplier(() ->
            clientFor(server).listTools()
        );
    }

    public CallToolResult forward(McpServerInfo server, CallToolRequest req) {
        return breakerFor(server).executeSupplier(() ->
            clientFor(server).callTool(req)
        );
    }

    private McpClient clientFor(McpServerInfo server) {
        return clients.computeIfAbsent(server.id(), k -> createClient(server));
    }

    private CircuitBreaker breakerFor(McpServerInfo server) {
        return breakers.circuitBreaker("mcp-" + server.id());
    }
}
```

**关键设计**：

- 每个 MCP Server 单独熔断（一个挂不影响其他）
- Client 连接池化（避免每次重建）
- tools/list 结果缓存 60s（很少变）

### 6.5 租户自建 MCP Server

```java
// 本代码仅作学习材料参考
@PostMapping("/api/mcp/servers")
public McpServerInfo registerOwnServer(@RequestBody RegisterServerRequest req) {
    TenantContext ctx = TenantContext.current();

    if (ctx.tier() == TRIAL) {
        throw new ForbiddenException("Trial tenant cannot register custom MCP server");
    }

    McpServerInfo server = McpServerInfo.builder()
        .id(UUID.randomUUID().toString())
        .name(req.name())
        .endpoint(req.endpoint())
        .transport(req.transport())
        .ownerType(TENANT)
        .ownerId(ctx.tenantId())
        .status(PENDING_VALIDATION)
        .build();

    registry.save(server);

    // 自动给该租户订阅自己注册的 server
    subscription.grant(ctx.tenantId(), server.id(), null);

    return server;
}
```

---

## 7. Workflow 引擎

### 7.1 什么时候需要 Workflow

简单 Agent 用 ChatClient 一把梭。复杂业务需要：

- 多步骤（先查知识库 → 再查库存 → 再写订单）
- 条件分支（金额 < 1000 自动审批 / 否则人工）
- 人工介入（审批 / 确认）
- 长时间运行（小时 / 天）

这时候要上 Workflow 引擎。

### 7.2 选型

| 引擎 | 类型 | 适合 |
|------|------|------|
| **Spring AI Alibaba Graph** | 声明式 | 中等复杂度，与 Spring AI 集成好 |
| **Temporal** | 长事务 | 业务关键流程、要可恢复 |
| **LangGraph（Python）** | Agent 图 | 复杂 Agent 拓扑 |
| **自研状态机** | 灵活 | 简单场景（< 10 节点） |

**生产推荐**：Spring AI Alibaba Graph + Temporal（关键流程）双轨。

### 7.3 Workflow 元数据

```sql
CREATE TABLE workflow (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    name            VARCHAR(128),
    graph           JSONB,            -- DAG 定义
    status          VARCHAR(16),
    version         INT,
    created_at      TIMESTAMP
);

CREATE TABLE workflow_run (
    id              VARCHAR(64) PRIMARY KEY,
    workflow_id     VARCHAR(64),
    tenant_id       VARCHAR(64),
    status          VARCHAR(16),       -- RUNNING / COMPLETED / FAILED / TIMEOUT
    input           JSONB,
    output          JSONB,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    INDEX idx_tenant_status (tenant_id, status)
);

CREATE TABLE workflow_step_log (
    run_id          VARCHAR(64),
    step_id         VARCHAR(64),
    status          VARCHAR(16),
    input           JSONB,
    output          JSONB,
    error           TEXT,
    started_at      TIMESTAMP,
    duration_ms     INT
);
```

---

## 8. 共享层与治理层

### 8.1 共享资源管理

平台提供共享能力，每租户有独立"实例"或"配置"：

```
共享 Tool 仓库（500+ 工具）
    ↓ 按租户订阅分配
每租户的 Tool 集合
    ↓ 绑定到
每租户的 Agent
    ↓ 触发
Workflow / 单次调用
```

### 8.2 平台运营后台

必备功能：

| 模块 | 功能 |
|------|------|
| 租户管理 | 创建 / 冻结 / 配额 / Tier 升降 |
| Agent 管理 | 全租户 Agent 列表 / 强制下线 |
| MCP 管理 | 注册 / 健康检查 / 强制断连 |
| 工具管理 | 上下架 / 权限审批 |
| 配额管理 | 全局调整 / 单租户临时调整 |
| 成本看板 | 每租户消耗 / 全局趋势 / 异常告警 |
| 审计日志 | 跨租户追溯（管理员权限） |
| 监控告警 | SLO 告警 / 故障定位 |

---

## 9. 安全设计

### 9.1 跨租户攻击面

| 攻击 | 防御 |
|------|------|
| 用租户 A 的 token 调用租户 B 数据 | 每次请求强制验证 tenantId 一致 |
| 通过 prompt 注入获取其他租户数据 | 工具返回前再 filter tenant |
| 通过 MCP 工具跨租户 | MCP Hub 严格按订阅过滤 |
| 通过文件 URL 跨租户 | 签名 URL 带 tenantId，服务端二次校验 |
| 通过 ChatMemory 串数据 | conversationId 强制带 tenantId 前缀 |

### 9.2 强制最小权限

平台管理员有"上帝视角"很危险：

- 管理员查看用户数据要双因素 + 审计
- PII 数据库默认对管理员不可见，需走审批
- 关键操作（删除租户 / 导出数据）需要两人确认

### 9.3 红队测试清单

详见 [`../tutorials/spring-ai-2.0/14-安全工程与红队.md`](../tutorials/spring-ai-2.0/14-安全工程与红队.md)。多租户系统的红队特别关注：

1. **跨租户访问**（最重要的）
2. **跨租户注入**（prompt 里藏跨租户指令）
3. **配额绕过**（并发请求是否绕过配额检查）
4. **资源耗尽**（一个租户能否拖垮全平台）
5. **审计绕过**（哪些路径不写日志）

---

## 10. 部署架构

### 10.1 单机房部署（中小规模）

```
                         ┌─────────────┐
                         │  LB / Nginx │
                         └──────┬──────┘
                                ↓
                    ┌───────────────────────┐
                    │  API Gateway（多实例）│
                    └───────────┬───────────┘
                                ↓
   ┌────────────┬──────────────┬┴──────────────┬────────────┐
   ↓            ↓              ↓               ↓            ↓
Agent       Workflow        MCP Hub        LLM Gateway   Attachment
Service     Engine                          (01 篇)      Service
   ↓            ↓              ↓               ↓            ↓
PostgreSQL    Temporal      MCP Server Pool  Cloud LLM    S3
+ pgvector                                                   ↓
                                                       Redis (cache)
```

### 10.2 多机房部署（KA / 合规需求）

```
主机房（北京）
├── 全租户主数据
└── 主推理流量

备机房（上海）
├── 只读副本（异步复制）
└── 接管故障租户

异地灾备（深圳）
├── 冷数据备份
└── 仅故障时启用
```

**关键设计**：

- 数据按机房隔离（租户的 region 在订阅时确定）
- 跨机房只做容灾，不做业务读写
- KA 支持"指定 region"

---

## 11. 性能与容量

### 11.1 关键指标基线

| 指标 | 目标 |
|------|------|
| 单租户 QPS | 100+ |
| 单租户 ChatMemory 响应 | < 50ms |
| 单租户向量检索 p95 | < 100ms |
| MCP 工具调用 p95 | < 500ms（含下游） |
| 平台总 QPS | 5000+ |
| MCP Server 单实例 QPS | 1000+ |

### 11.2 容量规划

按 100 个租户、平均每租户 1000 文档、平均 1000 tokens/文档估算：

| 资源 | 估算 |
|------|------|
| 向量库存储 | 100 × 1000 × 6KB = 600 GB |
| ChatMemory 存储 | 100 × 100 × 50KB = 500 GB |
| 文件存储 | 100 × 10 GB = 1 TB |
| 推理 QPS | 100 × 10 = 1000 QPS |
| Token / 天 | 100 × 100万 = 1 亿 |

按上面估算部署：

- PostgreSQL：主从 + 64G 内存 + 1TB SSD
- Milvus / pgvector：64G 内存 + 2TB SSD
- Redis：32G 内存（缓存 + 限流）
- 应用服务：8C 16G × 10 实例（按微服务拆）
- 对象存储：S3 / OSS（按量）

---

## 12. 实战避坑

### 12.1 "异步线程串租户数据"

**原因**：忘了传 TenantContext。

**解决**：

- 用 §2.3 的 TenantAwareExecutor 包装所有线程池
- 单测覆盖：开两个租户并发跑同一接口，验证不串数据
- 红队测试：故意 Thread.sleep 后再查，看 TenantContext 是否还在

### 12.2 "向量库漏了 tenant filter"

**原因**：某段代码直接用了原始 VectorStore。

**解决**：

- 包装层（§3.4）注入强制 filter
- code review 强制查 "VectorStore" 关键字，所有直接调用都要审查
- 自动化扫描：CI 跑一个测试，搜全代码库的 `vectorStore.similaritySearch` 调用，所有都要经过 TenantAwareVectorStore

### 12.3 "一个租户把全平台打爆"

**原因**：没做 per-tenant Bulkhead，某租户发起 DDoS 拖垮共享池。

**解决**：

- 严格 per-tenant Bulkhead（§4.4）
- 网关层 per-tenant RateLimiter（§4.3）
- 监控告警：单租户 QPS / 错误率超阈值立即降级该租户

### 12.4 "MCP Hub 成了单点瓶颈"

**原因**：所有 tools/call 都走 Hub，Hub 转发慢。

**解决**：

- Hub 无状态，横向扩容
- tools/list 结果缓存 60s
- 热点工具直连（绕过 Hub，但失去统一治理）

### 12.5 "租户自建的 MCP Server 把数据带出去"

**原因**：租户注册恶意 MCP Server，被平台其他租户的 Agent 调用。

**解决**：

- 租户自建 MCP Server **默认只对自己租户可见**，不进入公共池
- 想进公共池要走平台审核
- 跨租户调用 Prompt Injection 防护（详见 [`../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md`](../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md) §6.1）

---

## 13. 实战任务

1. 设计租户表 + 订阅表 + 配额表，模拟 3 个 Tier。
2. 实现 TenantContextFilter + TenantAwareExecutor，单测验证不串租户。
3. 用 Hibernate @Filter 自动注入 tenant_id 到所有查询。
4. 实现 TenantAwareVectorStore 包装原始 VectorStore。
5. 实现 per-tenant RateLimiter + Bulkhead。
6. 搭建 Agent 注册中心：能注册、查询、按租户过滤。
7. 实现 Agent 调用入口：组装 tools + advisors + ChatMemory。
8. 实现最小 MCP Hub：注册、订阅、转发、审计。
9. 实现租户自建 MCP Server 流程（默认只对自己可见）。
10. 用 Spring AI Alibaba Graph 实现一个 5 节点 Workflow。
11. 实现平台运营后台（租户 / Agent / MCP / 配额管理）。
12. 写红队测试：覆盖跨租户访问 / 配额绕过 / 资源耗尽。

---

## 14. 理解检查

1. 多租户系统的三大核心矛盾是什么？怎么取舍？
2. TenantContext 的 ThreadLocal 在线程池场景下有什么坑？怎么解决？
3. 数据库层用 Hibernate @Filter 自动注入 tenant_id 比手写 SQL filter 强在哪？
4. per-tenant Bulkhead 和 RateLimiter 的差别？为什么都需要？
5. 租户 Tier 决定"隔离强度"还是"功能"？为什么这样设计？
6. MCP Hub 在多租户平台中的核心价值？为什么不能让 Agent 直连 MCP Server？
7. 租户自建 MCP Server 默认为什么只对自己可见？
8. 跨租户攻击的 5 个面是什么？分别怎么防？
9. 什么时候需要 Workflow 引擎？单 Agent 不够吗？
10. 多租户平台必备的运营后台模块有哪些？

---

## 15. 相关文档

- [`./01-推理网关.md`](./01-推理网关.md) —— 网关层
- [`./02-向量库选型与治理.md`](./02-向量库选型与治理.md) —— 向量层
- [`./04-成本治理.md`](./04-成本治理.md) —— 配额层
- [`./05-高可用与可观测.md`](./05-高可用与可观测.md) —— 可观测层
- [`../tutorials/spring-ai-2.0/14-安全工程与红队.md`](../tutorials/spring-ai-2.0/14-安全工程与红队.md) —— 红队
- [`../tutorials/spring-ai-2.0/17-AI原生系统设计.md`](../tutorials/spring-ai-2.0/17-AI原生系统设计.md) —— 领域 Agent
- [`../tutorials/spring-ai-2.0/18-大规模Agent平台与数据基础设施.md`](../tutorials/spring-ai-2.0/18-大规模Agent平台与数据基础设施.md) —— 平台设计层
- [`../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md`](../tutorials/spring-ai-2.0/07-MCP-Server高阶与生态.md) —— MCP Hub 设计层
- [`../tutorials/spring-ai-2.0/32-多源检索Agent与MCP生态整合.md`](../tutorials/spring-ai-2.0/32-多源检索Agent与MCP生态整合.md) —— MCP Hub 实战

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
