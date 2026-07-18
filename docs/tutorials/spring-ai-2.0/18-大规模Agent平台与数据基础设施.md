# 17 大规模 Agent 平台与数据基础设施

> 本文合并自原 20「大规模 Agent 平台架构」+ 原 21「AI 数据基础设施」。
>
> 一篇搞定：把 AI 能力开放给整个公司（甚至外部客户）所需的平台化 + 数据基础设施。
>
> 前置：[`./17-AI原生系统设计.md`](./17-AI原生系统设计.md) + [`./09-RAG工程化实战.md`](./09-RAG工程化实战.md)
> 预计：3 天

---

## 0. 认知地图

```
单应用（L2）：自己的 Agent 跑起来
    ↓
平台化（本文上半）：一套基础设施服务 N 个业务方
    ├── 多租户（数据隔离 + 配额）
    ├── Agent 池化（生命周期管理）
    ├── 计费 + 熔断
    ├── SLO + Fallback 链
    └── Self-Service Portal
    +
数据基础设施（本文下半）：AI 应用的"原料"管道
    ├── 文档管道（接入 / 增量 / 元数据）
    ├── 向量库（选型 / 索引 / 多租户）
    ├── 会话管道（Memory 分层 + 归档）
    ├── 反馈管道（显式 + 隐式）
    └── 数据质量 / 安全 / 合规
```

**何时该做平台**：

- 公司内 5+ 团队都在搭自己的 Agent。
- 多个业务方共享同一套 LLM 配额（降本）。
- 需要统一审计、合规、安全。
- 需要把能力开放给外部客户。

---

# Part One：大规模 Agent 平台架构

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
// org.demo02.platform.tenant.TenantContext
// 本代码仅作学习材料参考

public class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void set(String tenantId) { CURRENT.set(tenantId); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}

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

### 3.3 数据库行级隔离（PostgreSQL RLS）

用 RLS（Row-Level Security）做硬隔离：

```sql
ALTER TABLE agent_call_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON agent_call_log
    USING (tenant_id = current_setting('app.current_tenant')::text);

-- 应用每次连接前设当前租户
SET app.current_tenant = 'tenant-001';
```

**好处**：即使 SQL 漏了 `WHERE tenant_id=`，数据库层也会过滤，不会跨租户泄漏。

### 3.4 Vector Store 多租户

pgvector 同样支持 RLS：

```sql
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_chunks
    USING (tenant_id = current_setting('app.current_tenant'));
```

或者用元数据过滤（独立 collection 路线）：

```java
public class TenantVectorStore {
    public void add(String tenantId, List<Document> docs) {
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
            instance.close();
        }
    }
}
```

### 4.3 Agent 配置 DB 化

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

        jdbc.update("""
            INSERT INTO billing_record (tenant_id, model, input_tokens,
                                       output_tokens, cost_usd, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            """, tenantId, usage.model(), usage.inputTokens(),
                usage.outputTokens(), cost);

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
    → 自托管 LLM（Llama 3 / Qwen）
    ↓ 全挂
    → 缓存命中或降级响应
```

```java
public class ResilientChatClient {
    public ChatResponse call(ChatRequest req) {
        return tryCall(primaryClient, req)
                .onErrorResume(e -> tryCall(secondaryClient, req))
                .onErrorResume(e -> tryCall(tertiaryClient, req))
                .onErrorResume(e -> cachedOrDegraded(req))
                .block();
    }
}
```

---

## 7. Self-Service Portal

让业务方自己管理 Agent，不用提工单。

**核心功能**：

- 注册 / 配置 Agent（system prompt、工具、模型选择）
- 申请 / 轮换 API Key
- 查实时用量 / 历史账单
- 查调用日志 / trace
- 配置 Webhook（接收 Agent 事件）

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
```

```sql
CREATE TABLE mcp_subscription (
    tenant_id VARCHAR(64),
    mcp_server_id VARCHAR(64),
    granted_tools JSONB,
    granted_at TIMESTAMP,
    granted_by VARCHAR(64),
    PRIMARY KEY (tenant_id, mcp_server_id)
);
```

详见 [`./05-MCP协议全解.md`](./05-MCP协议全解.md) §9。

---

## 9. 安全审计

所有敏感操作落审计：

```java
@Aspect
@Component
public class AuditAspect {

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        String tenantId = TenantContext.get();
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        AuditEntry entry = new AuditEntry(
                Instant.now(), tenantId, userId,
                audited.value(),
                serialize(pjp.getArgs()),
                null, null);
        try {
            Object result = pjp.proceed();
            auditRepo.save(entry.withResult(serialize(result)));
            return result;
        } catch (Throwable e) {
            auditRepo.save(entry.withError(e.getMessage()));
            throw e;
        }
    }
}
```

合规部门定期导出审计报告：

```sql
SELECT * FROM audit_log
WHERE tenant_id = ? AND created_at > NOW() - INTERVAL '30 days'
ORDER BY created_at;
```

---

# Part Two：AI 数据基础设施

## 10. 三套数据管道

```
传统应用数据：CRUD + 主从复制 + 缓存
    +
AI 应用新增：
    ├── 文档数据（PDF / HTML / Markdown） → 文档管道
    ├── 向量数据（embedding） → 向量库 + ANN 索引
    ├── 对话数据（session / memory） → 会话管道
    └── 反馈数据（👍/👎 / 重写） → 评估管道
```

| 管道 | 特点 | 频率 |
|------|------|------|
| 文档管道 | 批处理为主、量大、强一致 | 每小时 / 每天 |
| 事件管道 | 流式、低延迟、Event Sourcing | 秒级 |
| 反馈管道 | 来源分散、用于评估和优化 | 实时 + 批 |

---

## 11. 文档管道深入

### 11.1 多种接入来源

| 来源 | 接入方式 | 挑战 |
|------|---------|------|
| Confluence / Notion | REST API 同步 | 增量、版本、权限 |
| GitHub / GitLab | Webhook + clone | 大文件、二进制 |
| PDF / Word | 上传 | 表格、图片、OCR |
| 数据库（业务表） | JDBC 同步 | 结构化数据如何向量化 |
| 网页（爬虫） | Scheduled crawl | 反爬、JS 渲染 |
| 邮件 | IMAP 拉取 | 附件、隐私 |

### 11.2 增量索引

新文档来了，怎么知道哪些 chunk 要重建？

**方案 1：基于 hash**（推荐）

```java
public void ingest(Document doc) {
    String newHash = sha256(doc.getText());
    String existingHash = docRepo.findHash(doc.getId());

    if (newHash.equals(existingHash)) {
        return;  // 没变
    }

    vectorStore.delete(List.of(doc.getId()));
    List<Document> chunks = splitter.split(doc);
    vectorStore.add(chunks);
    docRepo.updateHash(doc.getId(), newHash);
}
```

**方案 2：基于时间戳**：简单但不可靠（内容改了又改回仍会重算）。

**方案 3：基于版本号**：每个文档维护 version 字段，跳过更早版本。

### 11.3 PDF 表格抽取

```java
// org.demo02.data.pdf.PdfTableExtractor
// 本代码仅作学习材料参考

@Component
public class PdfTableExtractor {

    public List<Document> extract(Reader reader) {
        PdfDocument pdf = new PdfDocument(reader);
        List<Document> docs = new ArrayList<>();

        for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
            String text = PdfTextExtractor.getText(pdf.getPage(i));
            docs.add(new Document(text, Map.of("page", i, "type", "text")));

            List<Table> tables = extractTables(pdf.getPage(i));
            for (int j = 0; j < tables.size(); j++) {
                String tableMarkdown = tableToMarkdown(tables.get(j));
                docs.add(new Document(tableMarkdown,
                        Map.of("page", i, "type", "table", "table_index", j)));
            }
        }
        return docs;
    }
}
```

### 11.4 元数据规范

强制每个文档带这些元数据：

```java
Map<String, Object> standardMetadata = Map.of(
        "source", "confluence",
        "source_url", "https://...",
        "department", "hr",
        "tenant_id", "tenant-001",
        "doc_id", "doc-abc",
        "version", "v3",
        "ingested_at", Instant.now().toString(),
        "language", "zh",
        "permissions", List.of("hr-read")
);
```

---

## 12. 向量库选型

### 12.1 主流向量库对比

| 向量库 | 类型 | 优势 | 劣势 | 适合 |
|-------|------|------|------|------|
| **pgvector** | PostgreSQL 扩展 | 一套 DB 搞定关系 + 向量；RLS；事务 | 性能不如专用 | 中小规模（< 10M 向量）；强一致性需求 |
| **Milvus** | 专用 | 高性能、可水平扩展 | 部署复杂 | 大规模（亿级）；纯向量场景 |
| **Qdrant** | 专用 | Rust 实现，性能强 | 生态小 | 中大规模；性能优先 |
| **Weaviate** | 专用 | 内置 hybrid search | 学习曲线 | 混合检索需求 |
| **Elasticsearch** | 搜索引擎 | 已有 ES 团队直接复用 | 向量性能中等 | 已用 ES 的团队 |
| **Chroma** | 嵌入式 | 简单；开发期方便 | 不适合生产 | 原型开发 |

### 12.2 决策树

```
你的向量规模 < 100 万？
├── 是 → pgvector（一个 DB 搞定，简单）
└── 否 →
    你的向量规模 < 1000 万？
    ├── 是 →
    │   有 PostgreSQL 运维经验？
    │   ├── 是 → pgvector（依然够用）
    │   └── 否 → Qdrant（独立但简单）
    └── 否 →
        需要 hybrid search（向量 + BM25）？
        ├── 是 → Weaviate / Elasticsearch
        └── 否 → Milvus（大规模纯向量）
```

### 12.3 Spring AI 接入

```java
// pgvector
// 注意：PgDistanceType / PgIndexType 是 PgVectorStore 的内部枚举，必须加 PgVectorStore. 前缀。
@Bean
public VectorStore vectorStore(JdbcTemplate jdbc, EmbeddingModel embedding) {
    return PgVectorStore.builder(jdbc, embedding)
            .dimensions(1024)
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
            .indexType(PgVectorStore.PgIndexType.HNSW)
            .build();
}

// Milvus
// 注意：Milvus 客户端类型是 io.milvus.client.MilvusServiceClient（不是 MilvusClient）。
@Bean
public VectorStore vectorStore(MilvusServiceClient client, EmbeddingModel embedding) {
    return MilvusVectorStore.builder(client, embedding)
            .collectionName("documents")
            .databaseName("default")
            .initializeSchema(true)
            .build();
}
```

### 12.4 索引类型

| 索引 | 召回率 | 延迟 | 适合 |
|------|--------|------|------|
| **暴力搜索（无索引）** | 100% | 慢 | 小数据（< 10万） |
| **HNSW** | 高（>95%） | 快 | 通用首选 |
| **IVF** | 中 | 中 | 大规模、可接受稍低召回 |
| **PQ（乘积量化）** | 中低 | 极快、省内存 | 超大规模、内存受限 |

**推荐**：HNSW（99% 场景适用），大规模再上 IVF + PQ。

---

## 13. 会话数据管理

### 13.1 Memory 存储分层

```
Hot Memory（Redis）：当前活跃会话，TTL 1 小时
    ↓ 不活跃
Warm Memory（PostgreSQL）：30 天内的会话，可查
    ↓ 过期
Cold Memory（对象存储）：归档，长期保留
```

### 13.2 ChatMemory JDBC 持久化

Spring AI 2.0 把 ChatMemory 拆成两层：

- **`ChatMemoryRepository`**：纯存储抽象（`saveAll` / `findByConversationId` / `deleteByConversationId`），`JdbcChatMemoryRepository` / `CassandraChatMemoryRepository` / `MongoChatMemoryRepository` / `Neo4jChatMemoryRepository` / `RedisChatMemoryRepository` 等是它的实现。
- **`ChatMemory`**：在 Repository 上加窗口/裁剪策略，`MessageWindowChatMemory`（默认 20 条）是它的标准实现。

> 注意：**`ChatMemory` Bean 是上层**，单独 `@Bean ChatMemoryRepository` 不够，还要用 `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(N).build()` 包一层，否则 advisor 拿不到 `ChatMemory`。Spring Boot starter 在 classpath 上有 JDBC starter 时会自动配置 `JdbcChatMemoryRepository` Bean，但 `MessageWindowChatMemory` 默认 `maxMessages=20`。

```java
// 本代码仅作学习材料参考
@Bean
public ChatMemoryRepository chatMemoryRepository(JdbcTemplate jdbc) {
    return JdbcChatMemoryRepository.builder()
            .jdbcTemplate(jdbc)
            .dialect(new PostgresChatMemoryRepositoryDialect())
            .build();
}

@Bean
public ChatMemory chatMemory(ChatMemoryRepository repository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
}
```

```sql
CREATE TABLE spring_ai_chat_memory (
    conversation_id VARCHAR(36) NOT NULL,
    "timestamp"    BIGINT NOT NULL,
    assistant_json JSONB NOT NULL,
    user_json      JSONB NOT NULL,
    PRIMARY KEY (conversation_id, "timestamp")
);

CREATE INDEX idx_memory_conv ON spring_ai_chat_memory(conversation_id);
```

> 2.0.0 引入 `sequence_id`/`"timestamp"` 列，支持基于 turn 边界的窗口裁剪（不再粗暴按消息条数截断）。具体表结构以官方 `JdbcChatMemoryRepositoryDialect` 实现为准。
>
> **重要限制**：JDBC / Cassandra / MongoDB 三个内置 Repository 实现目前**不支持持久化 ToolCall / ToolResponse 消息**（会被静默过滤）。如果你的 Agent 跨会话恢复后要继续推进工具调用，请用 Spring AI Session 项目（`spring-ai-session`，事件溯源、可重放）。

### 13.3 会话归档

```java
@Scheduled(cron = "0 0 3 * * *")
public void archiveOldSessions() {
    LocalDate threshold = LocalDate.now().minusDays(30);
    List<String> oldSessions = jdbc.queryForList(
            "SELECT conversation_id FROM spring_ai_chat_memory WHERE updated_at < ?",
            String.class, threshold.atStartOfDay());

    for (String sessionId : oldSessions) {
        String content = jdbc.queryForObject(
                "SELECT messages::text FROM spring_ai_chat_memory WHERE conversation_id = ?",
                String.class, sessionId);
        objectStorage.put("archive/" + sessionId + ".json", content);
        jdbc.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?", sessionId);
    }
}
```

---

## 14. 反馈数据管道

### 14.1 显式反馈

```java
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    @PostMapping
    public void record(@RequestBody FeedbackRequest req) {
        FeedbackEvent event = new FeedbackEvent(
                UUID.randomUUID().toString(),
                req.sessionId(), req.messageId(), req.userId(),
                req.type(),        // LIKE / DISLIKE / REPORT
                req.comment(), Instant.now());
        feedbackRepo.save(event);
        kafka.send("feedback-events", event);
    }
}

public enum FeedbackType { LIKE, DISLIKE, REPORT, REWRITE }
```

### 14.2 隐式反馈

```java
public class ImplicitFeedbackTracker {

    public void recordMessageShown(String messageId, String userId) { }

    public void recordCopyAction(String messageId) {
        // 强烈正面信号
    }

    public void recordRegenerate(String sessionId) {
        // 负面信号
    }

    public void recordDwellTime(String messageId, long millis) {
        // < 1s 可能不相关，> 30s 可能认真阅读
    }
}
```

### 14.3 反馈转化为训练数据

1. **构造评估集**：DISLIKE 的 case 加入评估集做回归。
2. **Few-shot 优化**：LIKE 的 case 加入 prompt 的 few-shot。
3. **模型微调**：（高级）收集 pair（差答案 → 好答案）做 RLHF。

---

## 15. 数据质量

### 15.1 质量维度

| 维度 | 检查 |
|------|------|
| 完整性 | 必填字段非空 |
| 一致性 | 多源数据匹配 |
| 时效性 | 数据及时更新 |
| 准确性 | 数据真实反映现实 |
| 唯一性 | 没有重复 |
| 合规性 | PII 脱敏、保留期 |

### 15.2 质量监控

```java
@Component
public class DataQualityMonitor {

    public DataQualityReport check() {
        DataQualityReport report = new DataQualityReport();

        report.setMissingMetadataRate(
                checkRate("SELECT COUNT(*) FROM document_chunks WHERE metadata->>'source' IS NULL"));
        report.setDuplicateRate(
                checkRate("SELECT COUNT(*) - COUNT(DISTINCT content_hash) FROM document_chunks"));
        report.setStaleRate(
                checkRate("SELECT COUNT(*) FROM docs WHERE updated_at < NOW() - INTERVAL '7 days'"));

        if (report.getMissingMetadataRate() > 0.05) {
            alertService.notify("Missing metadata rate too high");
        }
        return report;
    }
}
```

### 15.3 数据血缘

```sql
CREATE TABLE data_lineage (
    chunk_id VARCHAR(64),
    source_type VARCHAR(32),     -- 'pdf' / 'wiki' / 'db'
    source_id VARCHAR(64),
    source_url TEXT,
    extracted_at TIMESTAMP,
    ingested_at TIMESTAMP,
    transformed_by VARCHAR(64),
    PRIMARY KEY (chunk_id)
);
```

---

## 16. 数据安全与合规

### 16.1 PII 识别与脱敏

入库前自动识别 PII（规则方式，便宜）：

```java
// org.demo02.data.pii.PiiFilter
// 本代码仅作学习材料参考

@Component
public class PiiFilter {

    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.-]+@[\\w.-]+\\.\\w+");

    public String mask(String text) {
        text = ID_CARD.matcher(text).replaceAll(m -> maskKeep(m.group(), 4, 4));
        text = PHONE.matcher(text).replaceAll(m -> maskKeep(m.group(), 3, 4));
        text = EMAIL.matcher(text).replaceAll(m -> {
            String email = m.group();
            int at = email.indexOf('@');
            return maskKeep(email.substring(0, at), 1, 1) + email.substring(at);
        });
        return text;
    }

    private String maskKeep(String s, int prefix, int suffix) {
        if (s.length() <= prefix + suffix) return "***";
        return s.substring(0, prefix) + "*".repeat(s.length() - prefix - suffix)
                + s.substring(s.length() - suffix);
    }
}
```

或者用 LLM 做 PII 识别（更准但贵）：

```java
public String llmMask(String text) {
    return judgeClient.prompt()
            .system("""
                把文本中的 PII（身份证、电话、邮箱、姓名、地址）替换为 [REDACTED]。
                保留其他内容不变。直接输出脱敏后的文本，不要解释。
                """)
            .user(text)
            .call().content();
}
```

### 16.2 数据保留期

```sql
CREATE OR REPLACE FUNCTION cleanup_old_data() RETURNS void AS $$
BEGIN
    DELETE FROM chat_messages WHERE created_at < NOW() - INTERVAL '90 days';
    DELETE FROM audit_log WHERE created_at < NOW() - INTERVAL '365 days';
    DELETE FROM feedback WHERE created_at < NOW() - INTERVAL '180 days';
END;
$$ LANGUAGE plpgsql;

SELECT pg_cron.schedule('0 3 * * *', 'SELECT cleanup_old_data()');
```

### 16.3 数据删除权（GDPR）

```java
@Service
public class UserDataDeletionService {

    @Transactional
    public void deleteAll(String userId) {
        jdbc.update("DELETE FROM spring_ai_chat_memory WHERE conversation_id IN " +
                "(SELECT id FROM conversations WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM feedback WHERE user_id = ?", userId);
        // 审计日志：合规可能要求保留
        jdbc.update("DELETE FROM document_chunks WHERE metadata->>'uploaded_by' = ?", userId);
        objectStorage.deleteAll("user-uploads/" + userId + "/");
    }
}
```

---

## 17. 实战避坑

### 17.1 "多租户数据意外泄漏"

**症状**：A 租户能看到 B 租户的对话历史。

**根因**：Memory 按 sessionId 隔离但 sessionId 没带 tenantId；SQL 漏了 `WHERE tenant_id=`；Vector Store 检索没过滤 tenant。

**解决**：用 PostgreSQL RLS 兜底；自动化测试加跨租户访问 case；Code Review 重点关注任何跨表查询。

### 17.2 "Agent 池内存泄漏"

**症状**：长时间运行后 OOM。

**根因**：池里堆积了过期 Agent（Agent 内的 ChatClient 持有大对象）。

**解决**：池里设最大空闲时间（如 30 分钟），过期清掉；每天低峰期 reset 整个池。

### 17.3 "配额扣了但 LLM 调用失败"

**解决**：配额扣减和 LLM 调用放在同一事务（成功才扣）；或者扣减后失败回滚（保证幂等）。

### 17.4 "Agent 配置热更新出 bug"

**解决**：加载前 schema 校验；加载失败保留旧版本；灰度更新（10% 流量先验证）。

### 17.5 "审计日志爆炸"

**解决**：热数据保留 30 天（PostgreSQL）；冷数据归档到对象存储（S3 / OSS）；大字段（如完整 prompt）单独存对象，日志里只存引用。

### 17.6 "向量维度不匹配"

**症状**：换 embedding 模型后，向量库报维度错。

**原因**：bge-large-zh 是 1024 维，text-embedding-3-large 是 3072 维，不能混用。

**解决**：一开始就用一致维度；换模型时全量重建索引（reindex）；多模型并存时建多个 collection。

### 17.7 "文档更新但向量没更新"

**解决**：见 §11.2，hash 或版本号校验。

### 17.8 "向量库数据膨胀"

**症状**：1 亿条 chunk，查询延迟从 50ms 涨到 2s。

**解决**：上 HNSW 索引；分片（按部门 / 租户拆 collection）；删除过期 / 无效 chunk。

### 17.9 "Memory 内存爆炸"

**症状**：长会话累积，prompt 超出 context。

**解决**：用 `MessageWindowChatMemory` 限制最大消息数；超出窗口时调 LLM 生成摘要，保留摘要 + 最近 N 条。

### 17.10 "Feedback 数据被埋点淹没"

**解决**：区分关键事件（COPY / DISLIKE）和弱信号（停留时间）；用 LLM-as-Judge 给每个 case 算质量分，弱信号只作辅助。

---

## 18. 实战任务

**平台部分**：

1. 给你的应用加 `TenantContext` + `TenantFilter`，所有数据带 tenant_id。
2. 启用 PostgreSQL RLS，做硬隔离测试（A 租户不能查 B 租户数据）。
3. 实现 `AgentPool`，给每个 Agent 一个 10 实例的池。
4. 实现配额三层：日 token、月 cost、单次 max。
5. 实现 Fallback 链：主 LLM → 备 LLM → 自托管 → 降级响应。
6. 给所有敏感操作加 `@Audited` AOP，记录到审计表。
7. （进阶）做一个最小 Self-Service Portal。
8. （选做）实现 MCP Hub 的权限模型。

**数据部分**：

9. 给你的 RAG 系统加增量索引：文档 hash 变化才重建。
10. 实现 PDF 文档的表格抽取。
11. 给 ChatMemory 接 JDBC 持久化，并实现 30 天后会话归档。
12. 实现 `FeedbackController`，记录 LIKE/DISLIKE 并推送到 Kafka。
13. 实现 `PiiFilter`，入库前自动脱敏身份证、电话、邮箱。
14. 实现数据质量监控看板：缺失元数据率、重复率、过期率。
15. （进阶）实现数据删除权（GDPR）。
16. （选做）评估 pgvector vs Milvus：100 万文档查询延迟对比。

---

## 19. 理解检查

1. 平台架构 vs 单应用架构的核心差异是什么？
2. 三种多租户隔离级别各自适合什么场景？
3. Agent 池化解决什么问题？怎么避免内存泄漏？
4. 配额扣减和 LLM 调用的事务怎么保证一致性？
5. 多层 Fallback 链的设计原则？
6. MCP Hub 的权限模型怎么设计？
7. AI 应用需要哪三套数据管道？各自特点？
8. pgvector vs Milvus 各自适合什么场景？
9. HNSW / IVF / PQ 索引各自特点？默认选哪个？
10. 文档增量索引怎么做？hash / 时间戳 / 版本号各自的优劣？
11. 显式反馈 vs 隐式反馈，哪个更可靠？怎么结合？
12. PII 脱敏的两种方式（规则 vs LLM）各自的优劣？

---

## 20. 相关文档

- [`./17-AI原生系统设计.md`](./17-AI原生系统设计.md) —— 系统设计基础
- [`./09-RAG工程化实战.md`](./09-RAG工程化实战.md) —— RAG 数据基础
- [`./05-MCP协议全解.md`](./05-MCP协议全解.md) —— MCP Hub 实现
- [`./15-可观测性与成本治理.md`](./15-可观测性与成本治理.md) —— 平台可观测性 + 计费
- [`./14-安全工程与红队.md`](./14-安全工程与红队.md) —— 多租户安全深入
- [pgvector 文档](https://github.com/pgvector/pgvector)
- [Milvus 文档](https://milvus.io/docs)
- [Building LLM Apps for Production](https://www.oreilly.com/library/view/building-llm-apps/)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
