# AgentOps Sprint 1 · 历史持久化（从最简版开始）

> **目标**：从"往控制台 println 一行日志"开始，一步步长成三层持久化 + 全链路 Trace
> **时间**：1 周

---

## V1：30 分钟——用文件存对话历史

> **思路**：先别管数据库、Trace。最简单的持久化就是把对话写到一个 JSON 文件里。

### Step 1：文件存储

```java
package com.agentops.history;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * V1 极简版：把对话存到 JSON 文件
 *
 * 问题：没法查询、并发写会冲突、没有结构化
 * 但它解决了最核心的问题：服务重启后对话不丢。
 */
@Service
public class FileHistoryService {

    private static final Path HISTORY_DIR = Path.of("data/history");

    /**
     * 保存一轮对话
     */
    public void save(String sessionId, String userMsg, String agentReply) {
        try {
            Files.createDirectories(HISTORY_DIR);
            Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");

            // 追加一行 JSON
            String line = String.format(
                """{"ts":"%s","user":"%s","agent":"%s"}%n""",
                Instant.now(), escape(userMsg), escape(agentReply)
            );
            Files.writeString(file, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("保存历史失败：" + e.getMessage());
        }
    }

    /**
     * 读取会话历史
     */
    public List<String> load(String sessionId) {
        try {
            Path file = HISTORY_DIR.resolve(sessionId + ".jsonl");
            if (!Files.exists(file)) return List.of();
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
```

### Step 2：用起来

```java
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final FileHistoryService history;

    @PostMapping("/chat")
    public String chat(@RequestParam String sessionId,
                       @RequestParam String message) {
        // 加载历史拼到 prompt 里
        var historyLines = history.load(sessionId);
        String context = String.join("\n", historyLines);

        String reply = chatClient.prompt()
            .system("之前的对话：\n" + context)
            .user(message)
            .call().content();

        // 保存这轮对话
        history.save(sessionId, message, reply);

        return reply;
    }
}
```

> ✅ V1 的价值：20 行代码让对话不丢失。
>
> ❌ V1 的问题：JSON 文件没法查询（"查某租户的所有会话"做不到）、并发写会丢数据、没有结构化字段。

---

## V2：2 天——数据库结构化存储

> **V1 的问题**：文件存储没法查询、没法聚合统计。
> **V2 的目标**：一张数据库表存所有对话，支持基本查询。

### Step 2.1：单表方案

```sql
-- V2：一张表存所有对话轮次
CREATE TABLE chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,  -- USER / ASSISTANT
    content     TEXT NOT NULL,
    model       VARCHAR(64),
    tokens      INT DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_messages_session ON chat_messages(session_id, created_at);
CREATE INDEX idx_messages_tenant_time ON chat_messages(tenant_id, created_at DESC);
```

```java
package com.agentops.history;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * V2：数据库存储
 *
 * V1 是文件追加，V2 是结构化查询。
 */
@Service
public class DbHistoryService {

    private final JdbcTemplate jdbc;

    public void save(String sessionId, String tenantId,
                     String role, String content,
                     String model, int tokens) {
        jdbc.update("""
            INSERT INTO chat_messages
                (session_id, tenant_id, role, content, model, tokens)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            sessionId, tenantId, role, content, model, tokens);
    }

    public List<MessageRecord> getSessionHistory(String sessionId) {
        return jdbc.query("""
            SELECT * FROM chat_messages
            WHERE session_id = ?
            ORDER BY created_at
            """,
            messageRowMapper, sessionId);
    }

    /**
     * V2 新增：可以按租户查了
     */
    public List<MessageRecord> getTenantHistory(String tenantId, int limit) {
        return jdbc.query("""
            SELECT * FROM chat_messages
            WHERE tenant_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """,
            messageRowMapper, tenantId, limit);
    }

    /**
     * V2 新增：统计租户的 token 用量
     */
    public long getTokenCount(String tenantId) {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(tokens), 0) FROM chat_messages WHERE tenant_id = ?",
            Long.class, tenantId);
    }

    private final RowMapper<MessageRecord> messageRowMapper = (rs, rowNum) ->
        new MessageRecord(
            rs.getLong("id"),
            rs.getString("session_id"),
            rs.getString("tenant_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("model"),
            rs.getInt("tokens"),
            rs.getTimestamp("created_at").toInstant()
        );

    public record MessageRecord(
        long id, String sessionId, String tenantId,
        String role, String content, String model,
        int tokens, Instant createdAt
    ) {}
}
```

### Step 2.2：用 Advisor 自动记录

```java
@Component
public class HistoryAdvisor implements CallAdvisor {

    private final DbHistoryService history;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        AdvisedResponse response = chain.nextCall(request);

        // 自动记录（V1 是手动在 Controller 里调的，V2 用 Advisor 自动化）
        var usage = response.response().getMetadata().getUsage();
        String sessionId = request.context().getOrDefault("sessionId", "default");
        String tenantId = request.context().getOrDefault("tenantId", "internal");
        String model = request.chatOptions().getModel();

        CompletableFuture.runAsync(() -> {
            history.save(sessionId, tenantId, "USER",
                request.userText(), model, usage.getPromptTokens());
            history.save(sessionId, tenantId, "ASSISTANT",
                response.response().getResult().getOutput().getText(),
                model, usage.getCompletionTokens());
        });

        return response;
    }

    @Override
    public int getOrder() { return 100; }
}
```

> ✅ V2 的价值：结构化存储、支持查询统计、Advisor 自动记录。
>
> ❓ V2 的问题：工具调用没有被记录、没有 Trace ID 关联、没有 session 级别元数据。

---

## V3：3 天——三层模型 + 全链路 Trace

> **V2 的问题**：所有信息挤在一张表里，工具调用的输入输出没地方存，无法关联 Trace。
> **V3 的目标**：三层模型（sessions → llm_calls → tool_calls）+ Trace ID 串联。

### Step 3.1：三层表结构

```sql
-- V3 第一层：会话（V2 没有 session 元数据，V3 补上）
CREATE TABLE sessions (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    agent_type      VARCHAR(64) DEFAULT 'general',
    status          VARCHAR(16) DEFAULT 'ACTIVE',
    title           VARCHAR(256),
    total_tokens    INT DEFAULT 0,
    message_count   INT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_sessions_tenant ON sessions(tenant_id, created_at DESC);

-- V3 第二层：LLM 调用（从 V2 的 chat_messages 升级）
CREATE TABLE agent_llm_calls (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          VARCHAR(64) NOT NULL REFERENCES sessions(id),
    turn_number         INT NOT NULL,
    model               VARCHAR(64),
    user_message        TEXT,
    assistant_response  TEXT,
    prompt_tokens       INT DEFAULT 0,
    completion_tokens   INT DEFAULT 0,
    latency_ms          BIGINT DEFAULT 0,
    trace_id            VARCHAR(64),  -- V3 新增：Trace 关联
    success             BOOLEAN DEFAULT TRUE,
    error_message       TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_llm_calls_session ON agent_llm_calls(session_id, turn_number);

-- V3 新增第三层：工具调用（V2 完全没记录工具调用）
CREATE TABLE agent_tool_calls (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL REFERENCES sessions(id),
    llm_call_id     BIGINT REFERENCES agent_llm_calls(id),
    turn_number     INT NOT NULL,
    tool_name       VARCHAR(64) NOT NULL,
    input_params    JSONB DEFAULT '{}',
    output_result   JSONB,
    success         BOOLEAN DEFAULT TRUE,
    error_message   TEXT,
    latency_ms      BIGINT DEFAULT 0,
    trace_id        VARCHAR(64),  -- 与 LLM 调用关联
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_tool_calls_session ON agent_tool_calls(session_id, turn_number);
```

### Step 3.2：三层记录服务

```java
package com.agentops.history;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * V3：三层记录服务
 *
 * V2 的 save() 只存消息文本。
 * V3 分三层：createSession → recordLlmCall → recordToolCall
 */
@Service
public class HistoryPersistenceService {

    private final JdbcTemplate jdbc;

    /** 第一层：创建会话 */
    public String createSession(String tenantId, String userId, String agentType) {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO sessions (id, tenant_id, user_id, agent_type)
            VALUES (?, ?, ?, ?)
            """, sessionId, tenantId, userId, agentType);
        return sessionId;
    }

    /** 第二层：记录 LLM 调用 */
    public Long recordLlmCall(String sessionId, int turn, String model,
            String userMsg, String agentReply,
            int promptTokens, int completionTokens,
            long latencyMs, String traceId) {
        return jdbc.queryForObject("""
            INSERT INTO agent_llm_calls
                (session_id, turn_number, model, user_message, assistant_response,
                 prompt_tokens, completion_tokens, latency_ms, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class,
            sessionId, turn, model, userMsg, agentReply,
            promptTokens, completionTokens, latencyMs, traceId);
    }

    /** 第三层：记录工具调用 */
    public void recordToolCall(String sessionId, Long llmCallId, int turn,
            String toolName, String inputParams, String outputResult,
            boolean success, String error, long latencyMs, String traceId) {
        jdbc.update("""
            INSERT INTO agent_tool_calls
                (session_id, llm_call_id, turn_number, tool_name,
                 input_params, output_result, success, error_message,
                 latency_ms, trace_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """,
            sessionId, llmCallId, turn, toolName,
            inputParams, outputResult, success, error, latencyMs, traceId);
    }

    /** 按会话查询完整历史（三层关联） */
    public SessionHistory getSessionHistory(String sessionId) {
        var session = jdbc.queryForMap(
            "SELECT * FROM sessions WHERE id = ?", sessionId);

        var llmCalls = jdbc.query("""
            SELECT * FROM agent_llm_calls
            WHERE session_id = ? ORDER BY turn_number
            """, llmCallRowMapper, sessionId);

        var toolCalls = jdbc.query("""
            SELECT * FROM agent_tool_calls
            WHERE session_id = ? ORDER BY turn_number
            """, toolCallRowMapper, sessionId);

        return new SessionHistory(session, llmCalls, toolCalls);
    }

    public record SessionHistory(
        Map<String, Object> session,
        List<LlmCallRecord> llmCalls,
        List<ToolCallRecord> toolCalls
    ) {}
}
```

### Step 3.3：全链路 Trace ID 串联

```java
/**
 * V3 新增：Trace ID 生成 + 串联
 *
 * 同一次 Agent 推理过程中的 LLM 调用和工具调用共享一个 Trace ID。
 * 拿着 Trace ID 可以在 Jaeger 里看到完整的调用链。
 */
@Component
public class TraceContext {

    private static final ThreadLocal<String> currentTraceId = new ThreadLocal<>();

    public String startTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        currentTraceId.set(traceId);
        return traceId;
    }

    public String currentTraceId() {
        return currentTraceId.get();
    }

    public void endTrace() {
        currentTraceId.remove();
    }
}
```

> ✅ V3 的价值：三层结构化存储、工具调用可见、Trace ID 串联全链路。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 文件 | V2 单表 | V3 三层 |
|------|--------|---------|--------|
| **存储** | JSON 文件 | 1 张表 | 3 张关联表 |
| **查询** | 读全文 | SQL 查 | SQL 查 + 关联 |
| **工具调用** | 不记录 | 不记录 | 独立记录 |
| **Trace** | 无 | 无 | Trace ID 串联 |
| **Token 统计** | 无 | 有 | 会话级+调用级 |
| **代码量** | ~30 行 | ~100 行 | ~250 行 |

---

## 验收检查

- [ ] V1：文件能存能读，重启不丢
- [ ] V2：数据库能查会话历史、能统计 Token
- [ ] V3：三层关联，工具调用可见，Trace ID 能串联

---

## 下一步

→ [Sprint 2：可视化](Sprint2-可视化.md)
