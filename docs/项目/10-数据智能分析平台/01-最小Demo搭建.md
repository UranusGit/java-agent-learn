# 项目 10：数据智能分析平台 — 01-最小 Demo 搭建

> **定位**：把"自然语言→SQL→结果"跑通的最小内核——单库（PostgreSQL 只读副本）、基础 Text2SQL、结果展示。本篇刻意不做护栏/语义层/权限（那是 v2 起的演进），先让"查数链路"立住。本文给出**完整可手写代码**（一行不省略）。
>
> 「遇到阻塞？→ [教程 13-结构化输出 §entity]、[教程 02-ChatClient与对话模型]；API 真实性以 [附录 05-SpringAI2-API基准] 为准」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的查数内核：输入自然语言 → LLM 生成 SQL → 对只读副本执行 → 返回结果 JSON + 依据 SQL |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → TextToSqlService(ChatClient) → SqlExecutor(JdbcTemplate)；Schema 全量注入 prompt |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**：SQL 裸奔、Schema 撑爆 token、黑盒不可信） |

## 2. 目标与量化验收

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 链路跑通 | 自然语言 → SQL → 结果全链路 < 15s（P95） |
| 2 | 基础准确 | 100 条简单问题（单表/单条件）SQL 执行成功率 ≥ 90% |
| 3 | 结果可追溯 | 每次返回带生成的 SQL |
| 4 | 只读约束 | 生成的 SQL 100% 是 SELECT（v1 靠 prompt 约束，v2 起强制） |

**本迭代明确不做**：SQL 安全护栏、schema 裁剪、语义层、权限、多源、缓存、成本治理。

## 3. 完整代码（照抄即可，一行不省略）

### 3.1 `pom.xml` 与 `application.yml`

pom.xml 基线见 [00-需求分析与架构设计 §5.1](00-需求分析与架构设计.md)；application.yml 基线见 [00 §5.2](00-需求分析与架构设计.md)。v1 不需要新增依赖。

### 3.2 `DataPlatApplication.java`

```java
package com.group.dataplat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DataPlatApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataPlatApplication.class, args);
    }
}
```

### 3.3 配置类 `DataQueryConfig.java`（ChatClient Bean）

```java
package com.group.dataplat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataQueryConfig {

    // spring-ai-starter-model-openai 已自动装配 ChatClient.Builder（DeepSeek 兼容 OpenAI 协议）
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

### 3.4 结构化输出 `GeneratedSql.java`

```java
package com.group.dataplat.dto;

import java.util.List;

/**
 * LLM 结构化输出：生成的 SQL + 无法映射的字段。
 * 用真实重载 entity(Class)（附录 05-02 §2 javap 实证：entity(Class, spec) 变体也真实存在，
 * spec 仅 useProviderStructuredOutput()/validateSchema() 两个方法）。
 */
public record GeneratedSql(String sql, List<String> missingFields) {}
```

### 3.5 结果载体 `QueryResult.java`

```java
package com.group.dataplat.dto;

import java.util.List;
import java.util.Map;

/**
 * 返回结构化结果：数据 + 生成它的 SQL（可追溯，带证据的答案）。
 */
public record QueryResult(
        List<Map<String, Object>> rows,   // 查询结果行
        String sql,                       // 生成的 SQL（用户/审计可见）
        int rowCount,                     // 行数
        long durationMs                   // 执行耗时
) {}
```

### 3.6 `SchemaProvider.java`（v1：全量 Schema 注入）

```java
package com.group.dataplat.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 读取表结构为 DDL 文本。v1 全量注入（200 表会撑爆 token——v2 起按问题裁剪）。
 */
@Service
public class SchemaProvider {

    private final JdbcClient jdbcClient;

    public SchemaProvider(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String loadSchema() {
        List<String> tables = jdbcClient.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """).query(String.class).list();

        StringBuilder sb = new StringBuilder();
        for (String table : tables) {
            sb.append(describeTable(table)).append('\n');
        }
        return sb.toString();
    }

    private String describeTable(String table) {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name || ' ' || data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = :table
                ORDER BY ordinal_position
                """).param("table", table).query(String.class).list();
        return "CREATE TABLE " + table + " (\n  " + String.join(",\n  ", columns) + "\n);";
    }
}
```

### 3.7 `SqlExecutor.java`（执行 + 耗时统计）

```java
package com.group.dataplat.service;

import com.group.dataplat.dto.GeneratedSql;
import com.group.dataplat.dto.QueryResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 执行 SQL（只读副本），返回结果与耗时。
 * 注意：JDBC 是阻塞 API，WebFlux 下必须 subscribeOn(boundedElastic)——EventLoop 上禁 block。
 */
@Service
public class SqlExecutor {

    private final JdbcTemplate jdbcTemplate;

    public SqlExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Mono<QueryResult> execute(GeneratedSql generated) {
        return Mono.fromCallable(() -> {
            long start = System.currentTimeMillis();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(generated.sql());
            long durationMs = System.currentTimeMillis() - start;
            return new QueryResult(rows, generated.sql(), rows.size(), durationMs);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
```

### 3.8 `TextToSqlService.java`（核心：NL → SQL）

```java
package com.group.dataplat.service;

import com.group.dataplat.dto.GeneratedSql;
import com.group.dataplat.dto.QueryResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * v1 直生 Text2SQL：全量 schema 注入 + entity(Class) 结构化输出。
 * 开放空间易幻觉（表选错/列猜错）——v3 语义层把"生成题"改成"选择题"。
 */
@Service
public class TextToSqlService {

    private final ChatClient chatClient;
    private final SchemaProvider schemaProvider;
    private final SqlExecutor sqlExecutor;

    public TextToSqlService(ChatClient chatClient,
                            SchemaProvider schemaProvider,
                            SqlExecutor sqlExecutor) {
        this.chatClient = chatClient;
        this.schemaProvider = schemaProvider;
        this.sqlExecutor = sqlExecutor;
    }

    public Mono<QueryResult> ask(String question, String userId) {
        String schema = schemaProvider.loadSchema();   // v1: 全部表 DDL（v2 起裁剪）

        return Mono.fromCallable(() -> chatClient.prompt()
                .system("""
                    你是数据分析助手。根据给定数据库 Schema，将自然语言问题转为 SQL。
                    规则：
                    1. 只输出 SQL，不输出解释
                    2. 只允许 SELECT 查询
                    3. 表名/列名必须严格来自 Schema，不得臆造
                    4. 不确定的字段归入 missing_fields
                    """)
                .user("问题：" + question + "\n\nSchema:\n" + schema)
                .call()
                .entity(GeneratedSql.class))            // 真实重载 entity(Class)，附录 05-02 §2
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(sqlExecutor::execute);
    }
}
```

「遇到阻塞？→ [教程 13-结构化输出 §entity()]——用 `entity(GeneratedSql.class)`（真实重载）；需要 provider 原生结构化输出或 schema 校验时，用 `entity(Class, spec -> ...)` 变体（spec 仅 `useProviderStructuredOutput()`/`validateSchema()`，附录 05-02 §2）」

### 3.9 `QueryController.java`（响应式入口）

```java
package com.group.dataplat.web;

import com.group.dataplat.dto.QueryResult;
import com.group.dataplat.service.TextToSqlService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final TextToSqlService textToSqlService;

    public QueryController(TextToSqlService textToSqlService) {
        this.textToSqlService = textToSqlService;
    }

    @PostMapping
    public Mono<QueryResult> ask(@RequestParam String userId, @RequestBody String question) {
        return textToSqlService.ask(question, userId);
    }
}
```

### 3.10 SQL DDL `db/schema-v1.sql`（种子表）

```sql
-- 订单表（口径示例：pay_amount 即"支付成功订单实付金额"，v3 语义层统一口径）
CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    region      VARCHAR(32)   NOT NULL,
    channel     VARCHAR(32)   NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    pay_amount  NUMERIC(12,2) NOT NULL,
    order_date  DATE          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS customers (
    customer_id BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    phone       VARCHAR(32) NOT NULL,
    region      VARCHAR(32) NOT NULL
);

-- v1 种子数据
INSERT INTO orders(user_id, region, channel, status, pay_amount, order_date) VALUES
(1, 'east',  'app',     'paid',   199.00, DATE '2026-06-01'),
(2, 'east',  'web',     'paid',   599.00, DATE '2026-06-02'),
(3, 'west',  'app',     'paid',    99.00, DATE '2026-06-01'),
(4, 'west',  'mini',    'refund', 399.00, DATE '2026-06-03');
```

### 3.11 运行与测试

```sh
export DEEPSEEK_API_KEY=sk-xxx
export DB_READONLY_USER=ai_analyst
export DB_READONLY_PASSWORD=change-me
mvn spring-boot:run

# 测试
curl -X POST "http://localhost:8080/api/query?userId=u1" \
  -H "Content-Type: text/plain" \
  -d "华东 6 月有多少笔支付成功的订单？"
```

### 验证包（手工测试与验证）

**前置条件**：上一迭代已通过；本迭代组件与样例数据就绪。

**材料**：一个文本分析 Agent + 一份样例数据表 + curl/hey 压测器。

**步骤与断言（由验收表转断言清单）**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 用材料构造「链路跑通」场景执行 | 自然语言 → SQL → 结果全链路 < 15s（P95） |
| 2 | 用材料构造「基础准确」场景执行 | 100 条简单问题（单表/单条件）SQL 执行成功率 ≥ 90% |
| 3 | 用材料构造「结果可追溯」场景执行 | 每次返回带生成的 SQL |
| 4 | 用材料构造「只读约束」场景执行 | 生成的 SQL 100% 是 SELECT（v1 靠 prompt 约束，v2 起强制） |
**失败排查**：断言不符先分层——入口日志（请求到没到）→ SQL 护栏/策略服务（为何拦/放）→ DB 层（只读强制是否在库层）；安全类失败优先验证"测试前置样本是否真的到达被测层"，再查实现。
## 4. ADR 演进决策

| # | 决策 | 理由 |
|---|------|------|
| ADR-500（最小 Demo） | 先跑通"问题→SQL→结果"链路 | 后续护栏/语义层/权限都在同一份链路上叠加 |
| ADR-501（v1 内） | 结构化输出用 `entity(Class)`；需要时用 `entity(Class, spec)`（spec 仅 `useProviderStructuredOutput()`/`validateSchema()`） | 附录 05-02 §2 javap 实证：两种形态均为真实 API |

## 5. v1 的痛点（驱动下一迭代）

跑通两周后，安全与业务团队联合反馈：

1. **SQL 裸奔**——prompt 只约束"只写 SELECT"，但 `SELECT 1; DROP TABLE` 这类注入、无 LIMIT 全表扫描、10 分钟超时查询**都能发生**；给业务用是灾难
2. **Schema 全量注入**——200 张表 DDL 塞进 prompt，token 爆炸 + LLM 记不住后面的表
3. **"这是对的吗？"**——业务不敢信黑盒 SQL

这三个痛点指向 **v2 SQL 安全护栏**。→ [02-迭代一-SQL安全护栏.md](02-迭代一-SQL安全护栏.md)
