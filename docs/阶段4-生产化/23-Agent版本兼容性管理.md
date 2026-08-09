# Agent 版本兼容性管理 · 平滑升级不中断

> **一句话**：你改了一个 Prompt 模板，上线后发现 30% 的旧会话"行为不一致"——因为正在进行的会话用的是旧版 Prompt，新 Prompt 不兼容旧上下文。

---

## 为什么 Agent 版本管理比传统软件更难？

```
传统软件版本：                    Agent 版本：
代码 v2.0 ←─ 完全自包含           Prompt v2 + Model v3 + Tools v4 + Context Schema v2
                                     ↑              ↑            ↑              ↑
                                     每个维度独立变化，组合爆炸
```

**Agent 版本 = Prompt 版本 × 模型版本 × 工具版本 × 上下文 Schema 版本**

---

## 版本组合矩阵

```java
package com.enterprise.versioning;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Agent 版本指纹
 *
 * 一个"Agent 版本"实际上是多个维度的组合：
 */
public record AgentVersionFingerprint(
    String promptVersion,      // prompt 模板版本（如 "v3.2.1"）
    String modelVersion,       // 模型版本（如 "deepseek-chat-2026-08"）
    String toolsVersion,       // 工具集版本（如 "tools-v5"）
    String contextSchema,      // 上下文格式版本（如 "ctx-v2"）
    String advisorChainVersion // Advisor 链配置版本
) {
    /**
     * 生成版本指纹 Hash
     */
    public String fingerprint() {
        return sha256(promptVersion + "|" + modelVersion + "|"
            + toolsVersion + "|" + contextSchema + "|" + advisorChainVersion)
            .substring(0, 12);
    }

    /**
     * 检查与另一个版本是否兼容
     */
    public boolean isCompatible(AgentVersionFingerprint other) {
        // 上下文 Schema 不兼容 = 完全不兼容
        if (!this.contextSchema.equals(other.contextSchema)) {
            return false;
        }
        // Prompt 版本 minor 兼容
        if (!majorVersionMatch(this.promptVersion, other.promptVersion)) {
            return false;
        }
        return true;
    }

    private boolean majorVersionMatch(String v1, String v2) {
        return v1.split("\\.")[0].equals(v2.split("\\.")[0]);
    }
}
```

---

## 会话版本锁定

```java
package com.enterprise.versioning;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 会话版本锁定
 *
 * 一个会话开始时用的 v3 版本，整个会话生命周期内都用 v3。
 * 新版本只对新会话生效。
 */
@Component
public class SessionVersionLock {

    /**
     * 会话创建时锁定版本
     */
    public String createSession(String userId, String tenantId) {
        String sessionId = generateSessionId();

        // 锁定当前版本指纹
        AgentVersionFingerprint currentVersion = versionRegistry.getCurrentVersion();
        String fp = currentVersion.fingerprint();

        // 存储版本锁定
        jdbc.update("""
            INSERT INTO session_version_lock
            (session_id, user_id, tenant_id,
             prompt_version, model_version, tools_version,
             context_schema, advisor_chain_version,
             fingerprint, locked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            """,
            sessionId, userId, tenantId,
            currentVersion.promptVersion(),
            currentVersion.modelVersion(),
            currentVersion.toolsVersion(),
            currentVersion.contextSchema(),
            currentVersion.advisorChainVersion(),
            fp);

        return sessionId;
    }

    /**
     * 获取会话锁定的版本
     */
    public AgentVersionFingerprint getVersion(String sessionId) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM session_version_lock WHERE session_id = ?",
            sessionId);

        return new AgentVersionFingerprint(
            (String) row.get("prompt_version"),
            (String) row.get("model_version"),
            (String) row.get("tools_version"),
            (String) row.get("context_schema"),
            (String) row.get("advisor_chain_version"));
    }

    /**
     * 检查会话是否需要迁移
     */
    public MigrationPlan checkMigration(String sessionId) {
        AgentVersionFingerprint locked = getVersion(sessionId);
        AgentVersionFingerprint current = versionRegistry.getCurrentVersion();

        if (locked.fingerprint().equals(current.fingerprint())) {
            return MigrationPlan.noMigration();
        }

        if (locked.isCompatible(current)) {
            return MigrationPlan.optional(
                "新版本兼容，可平滑迁移",
                locked, current);
        }

        return MigrationPlan.required(
            "新版本不兼容，需要迁移或开新会话",
            locked, current,
            determineMigrationSteps(locked, current));
    }

    private List<String> determineMigrationSteps(
            AgentVersionFingerprint from, AgentVersionFingerprint to) {
        List<String> steps = new ArrayList<>();
        if (!from.contextSchema().equals(to.contextSchema())) {
            steps.add("转换上下文格式：" + from.contextSchema() + " → " + to.contextSchema());
        }
        if (!from.toolsVersion().equals(to.toolsVersion())) {
            steps.add("更新工具引用：" + from.toolsVersion() + " → " + to.toolsVersion());
        }
        return steps;
    }

    public record MigrationPlan(
        boolean required,
        boolean possible,
        String message,
        AgentVersionFingerprint from, AgentVersionFingerprint to,
        List<String> steps
    ) {
        public static MigrationPlan noMigration() {
            return new MigrationPlan(false, true, "已是最新版本", null, null, List.of());
        }
        public static MigrationPlan optional(String msg, AgentVersionFingerprint from, AgentVersionFingerprint to) {
            return new MigrationPlan(false, true, msg, from, to, List.of());
        }
        public static MigrationPlan required(String msg, AgentVersionFingerprint from, AgentVersionFingerprint to, List<String> steps) {
            return new MigrationPlan(true, steps.isEmpty(), msg, from, to, steps);
        }
    }
}
```

---

## 版本注册中心

```java
package com.enterprise.versioning;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 版本注册中心
 *
 * 管理所有版本的元数据、发布状态、兼容性矩阵。
 */
@Component
public class VersionRegistry {

    private final List<AgentVersion> versions = new ArrayList<>();

    /**
     * 注册新版本
     */
    public void register(AgentVersion version) {
        versions.add(version);

        // 记录发布日志
        auditLog.log("VERSION_REGISTERED", version);
    }

    /**
     * 获取当前生产版本
     */
    public AgentVersionFingerprint getCurrentVersion() {
        return versions.stream()
            .filter(v -> v.status() == VersionStatus.PRODUCTION)
            .max(Comparator.comparing(AgentVersion::releasedAt))
            .map(AgentVersion::fingerprint)
            .orElseThrow();
    }

    /**
     * 获取版本历史
     */
    public List<AgentVersion> getHistory() {
        return versions.stream()
            .sorted(Comparator.comparing(AgentVersion::releasedAt).reversed())
            .toList();
    }

    /**
     * 回滚到指定版本
     */
    public void rollback(String targetFingerprint) {
        versions.stream()
            .filter(v -> v.fingerprint().fingerprint().equals(targetFingerprint))
            .findFirst()
            .ifPresent(v -> {
                // 将当前生产版本标记为 ROLLED_BACK
                versions.stream()
                    .filter(curr -> curr.status() == VersionStatus.PRODUCTION)
                    .forEach(curr -> curr.setStatus(VersionStatus.ARCHIVED));

                // 目标版本设为 PRODUCTION
                v.setStatus(VersionStatus.PRODUCTION);
            });
    }

    public record AgentVersion(
        String versionId,
        AgentVersionFingerprint fingerprint,
        String releaseNotes,
        VersionStatus status,
        Instant releasedAt,
        String releasedBy
    ) {}

    public enum VersionStatus {
        DRAFT,          // 草稿
        SHADOW,         // 影子模式运行中
        CANARY,         // 灰度运行中
        PRODUCTION,     // 生产版本
        ARCHIVED,       // 归档
        ROLLED_BACK     // 已回滚
    }
}
```

---

## 发布流程

```
1. DRAFT → 注册版本，附 Release Notes
2. SHADOW → 影子模式运行 3 天
3. CANARY → 5%-25%-50% 灰度
4. PRODUCTION → 全量发布
5. 旧版本 → ARCHIVED

回滚路径：
PRODUCTION → ROLLED_BACK（任何时候可以紧急回滚）
```

---

## 关键收获

| 挑战 | 对策 |
|------|------|
| 旧会话行为不一致 | 会话版本锁定 |
| Prompt + Model 组合爆炸 | 版本指纹 Hash |
| 上下文 Schema 变更 | 迁移计划自动生成 |
| 紧急回滚 | 版本注册中心一键回滚 |

→ 返回 [阶段4 目录](../00-README.md)
