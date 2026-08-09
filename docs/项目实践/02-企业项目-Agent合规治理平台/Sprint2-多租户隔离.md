# ComplyGuard Sprint 2 · 多租户数据隔离（从最简版开始）

> **目标**：从"一个 tenant_id 字段"开始，一步步长成三层隔离 + GDPR 被遗忘权
> **前置**：Sprint 1 数据分类

---

## V1：30 分钟——tenant_id 字段

> **思路**：先不搞框架级隔离。最简单的就是在每张表加一个 tenant_id 字段，查询时手动加 WHERE。

### Step 1：带租户的表 + 手动过滤

```java
package com.complyguard.v1;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V1 极简版：手动 tenant_id 过滤
 *
 * 问题：全靠开发者"记得加 WHERE tenant_id=?"
 * 忘了一次就泄露了。
 */
@Component
public class ManualTenantRepository {

    /**
     * 查询文档——开发者需要手动加 tenant_id 条件
     */
    public List<Document> queryByTenant(String tenantId, String keyword) {
        return jdbc.query("""
            SELECT * FROM documents
            WHERE tenant_id = ?
              AND content ILIKE ?
            ORDER BY created_at DESC
            """, (rs, i) -> new Document(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("content")
            ), tenantId, "%" + keyword + "%");
    }

    public record Document(String id, String tenantId, String content) {}
}
```

> ✅ V1 的价值：验证了行级隔离基本可行。
>
> ❌ V1 的问题：全靠人——漏一次 WHERE 就泄露。RAG 搜索更容易忘。

---

## V2：1 天——框架级强制隔离 + 向量隔离

> **V1 的问题**：全靠人记得。
> **V2 的目标**：框架层强制注入 tenant_id，开发者不需要手动加。

### Step 2.1：租户上下文

```java
package com.complyguard.v2;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.*;

/**
 * 租户上下文——每次请求自动提取 tenantId
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,
                              jakarta.servlet.http.HttpServletResponse resp,
                              Object handler) {
        // 从 JWT Token / Header 中提取
        String tenantId = req.getHeader("X-Tenant-Id");
        if (tenantId == null) {
            tenantId = extractFromJwt(req.getHeader("Authorization"));
        }

        if (tenantId == null) {
            resp.setStatus(403);
            return false;
        }

        TenantContext.set(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req,
                                 HttpServletResponse resp,
                                 Object handler, Exception ex) {
        TenantContext.clear();  // 清理 ThreadLocal
    }
}

class TenantContext {
    private static final ThreadLocal<String> current = new ThreadLocal<>();

    public static void set(String tenantId) { current.set(tenantId); }
    public static String get() {
        String t = current.get();
        if (t == null) throw new SecurityException("无租户上下文");
        return t;
    }
    public static void clear() { current.remove(); }
}
```

### Step 2.2：框架级隔离 Repository

```java
package com.complyguard.v2;

import org.springframework.stereotype.Component;

/**
 * V2：框架级隔离
 *
 * 开发者不需要手动加 WHERE tenant_id=?
 * 调用 query() 时自动注入。
 */
@Component
public class TenantSafeRepository {

    /**
     * 所有查询都经过这里——自动加 tenant_id
     */
    public <T> List<T> query(String sql, Object[] params,
                              RowMapper<T> mapper) {
        String tenantId = TenantContext.get();
        String safeSql = injectTenant(sql, tenantId);
        Object[] safeParams = appendParam(params, tenantId);
        return jdbc.query(safeSql, safeParams, mapper);
    }

    /**
     * SQL 自动注入 tenant_id 条件
     */
    String injectTenant(String sql, String tenantId) {
        // 简化——实际用 SQL Parser 做更安全
        String safe = sql.replaceAll("'", "''");  // 防注入
        String upper = sql.toUpperCase();

        if (upper.contains("WHERE")) {
            // 已有 WHERE → 在 WHERE 后追加
            return sql.replaceAll("(?i)WHERE",
                "WHERE tenant_id = ? AND");
        } else if (upper.contains("ORDER BY")) {
            return sql.replaceAll("(?i)ORDER BY",
                "WHERE tenant_id = ? ORDER BY");
        } else if (upper.contains("LIMIT")) {
            return sql.replaceAll("(?i)LIMIT",
                "WHERE tenant_id = ? LIMIT");
        } else {
            return sql + " WHERE tenant_id = ?";
        }
    }

    private Object[] appendParam(Object[] params, Object tenantId) {
        Object[] result = new Object[params.length + 1];
        // tenant_id 参数要放在 WHERE 子句最前面
        result[0] = tenantId;
        System.arraycopy(params, 0, result, 1, params.length);
        return result;
    }
}
```

### Step 2.3：RAG 向量隔离

```java
package com.complyguard.v2;

import org.springframework.stereotype.Component;

/**
 * V2 关键：RAG 向量搜索隔离
 *
 * RAG 是租户泄漏的重灾区：
 * 租户 A 的用户问问题 → 向量搜索命中租户 B 的文档 → 回答了
 */
@Component
public class TenantSafeVectorSearch {

    /**
     * 向量搜索——必须 pre-filter tenant_id
     */
    public List<Chunk> search(float[] queryVec, int topK) {
        String tenantId = TenantContext.get();

        return jdbc.query("""
            SELECT id, content, tenant_id,
                   embedding <=> ? AS distance
            FROM document_chunks
            WHERE tenant_id = ?
            ORDER BY embedding <=> ?
            LIMIT ?
            """, (rs, i) -> new Chunk(
                rs.getString("id"),
                rs.getString("content"),
                rs.getString("tenant_id"),
                rs.getFloat("distance")
            ),
            new PgVectorFloatArray(queryVec),
            tenantId,                         // ← 强制隔离
            new PgVectorFloatArray(queryVec),
            topK);
    }

    /**
     * 防御性验证——结果必须都属于当前租户
     */
    public List<Chunk> safeSearch(float[] vec, int topK) {
        List<Chunk> results = search(vec, topK);
        String tenantId = TenantContext.get();

        for (Chunk c : results) {
            if (!c.tenantId().equals(tenantId)) {
                throw new SecurityException(
                    "跨租户数据泄露！期望=" + tenantId + " 实际=" + c.tenantId());
            }
        }
        return results;
    }

    public record Chunk(String id, String content, String tenantId, float distance) {}
}
```

> ✅ V2 的价值：框架强制隔离 + RAG 向量 pre-filter。
>
> ❌ V2 的问题：没有 GDPR 被遗忘权。

---

## V3：1 天——GDPR 被遗忘权 + 审计

> **V2 的问题**：只有隔离，没有删除权。
> **V3 的目标**：用户要求删除数据时，端到端清除。

### Step 3.1：被遗忘权

```java
package com.complyguard.v3;

import org.springframework.stereotype.Component;

/**
 * V3 新增：GDPR Right to be Forgotten
 *
 * 用户要求删除时，清除：
 * 1. 对话历史
 * 2. 向量库中的文档
 * 3. Redis 缓存
 * 4. 审计日志（记录已删除，不保留内容）
 */
@Component
public class GdprDataService {

    /**
     * 删除用户数据
     */
    public DeletionResult deleteUserData(String tenantId, String userId) {
        int totalDeleted = 0;

        // 1. 删除对话消息
        totalDeleted += jdbc.update(
            "DELETE FROM chat_messages WHERE tenant_id = ? AND user_id = ?",
            tenantId, userId);

        // 2. 删除向量库文档
        totalDeleted += jdbc.update(
            "DELETE FROM document_chunks WHERE tenant_id = ? AND uploaded_by = ?",
            tenantId, userId);

        // 3. 删除文档元数据
        totalDeleted += jdbc.update(
            "DELETE FROM documents WHERE tenant_id = ? AND uploaded_by = ?",
            tenantId, userId);

        // 4. 清除 Redis 缓存
        Set<String> keys = redis.keys("session:" + tenantId + ":" + userId + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            totalDeleted += keys.size();
        }

        // 5. 删除 LLM 调用记录
        jdbc.update("""
            DELETE FROM agent_llm_calls
            WHERE message_id IN (
                SELECT id FROM chat_messages
                WHERE tenant_id = ? AND user_id = ?
            )
            """, tenantId, userId);

        // 6. 审计日志（记录已删除，不保留内容——GDPR 要求）
        jdbc.update("""
            INSERT INTO deletion_audit
            (id, tenant_id, user_id, deleted_count, deleted_at)
            VALUES (?, ?, ?, ?, NOW())
            """,
            UUID.randomUUID().toString(),
            tenantId, userId, totalDeleted);

        return new DeletionResult(userId, tenantId, totalDeleted, Instant.now());
    }

    /**
     * 数据导出（GDPR Right to Access）
     */
    public UserExport exportUserData(String tenantId, String userId) {
        return new UserExport(
            userId,
            jdbc.queryForList(
                "SELECT * FROM chat_messages WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId),
            jdbc.queryForList(
                "SELECT * FROM documents WHERE tenant_id = ? AND uploaded_by = ?",
                tenantId, userId)
        );
    }

    public record DeletionResult(
        String userId, String tenantId,
        int deletedRecords, Instant deletedAt
    ) {}

    public record UserExport(
        String userId,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> documents
    ) {}
}
```

### Step 3.2：数据保留策略

```java
/**
 * 自动数据保留策略——按合规要求自动过期
 */
@Component
public class DataRetentionService {

    /**
     * 每天执行——清理过期数据
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void enforceRetention() {
        // 不同租户不同保留期
        List<TenantRetentionPolicy> policies = loadRetentionPolicies();

        for (var policy : policies) {
            // 对话保留 90 天
            jdbc.update("""
                DELETE FROM chat_messages
                WHERE tenant_id = ?
                  AND created_at < NOW() - INTERVAL '%d' DAY
                """.formatted(policy.chatRetentionDays()),
                policy.tenantId());

            // 向量文档保留 365 天
            jdbc.update("""
                DELETE FROM document_chunks
                WHERE tenant_id = ?
                  AND created_at < NOW() - INTERVAL '%d' DAY
                """.formatted(policy.docRetentionDays()),
                policy.tenantId());
        }
    }

    public record TenantRetentionPolicy(
        String tenantId,
        int chatRetentionDays,   // 对话保留天数
        int docRetentionDays     // 文档保留天数
    ) {}
}
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 手动过滤 | V2 框架强制 | V3 + GDPR |
|------|-----------|-----------|----------|
| **DB 隔离** | 手动 WHERE | 自动注入 | + 自动过期 |
| **向量隔离** | 无 | pre-filter | 同 V2 |
| **删除权** | 无 | 无 | 端到端删除 + 审计 |
| **数据导出** | 无 | 无 | + Right to Access |
| **保留策略** | 无 | 无 | 按租户可配 |

---

## 验收检查

- [ ] V1：手动 tenant_id 查询能隔离
- [ ] V2：框架自动注入 + RAG 向量隔离
- [ ] V3：被遗忘权端到端删除 + 数据导出 + 保留策略

---

## 下一步

→ [Sprint 3：审计与报告](Sprint3-审计报告.md)
