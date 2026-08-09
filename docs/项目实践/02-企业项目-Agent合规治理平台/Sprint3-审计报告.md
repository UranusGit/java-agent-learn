# ComplyGuard Sprint 3 · 审计与合规报告（从最简版开始）

> **目标**：从"打印日志"开始，一步步长成不可篡改的链式审计 + 自动合规报告
> **前置**：Sprint 1-2 数据分类 + 租户隔离

---

## V1：30 分钟——普通日志

> **思路**：先不搞链式 Hash。最简单的审计就是记录每次 Agent 决策。

### Step 1：决策日志

```java
package com.complyguard.audit.v1;

import org.springframework.stereotype.Component;

/**
 * V1 极简版：普通审计日志
 *
 * 记录 Agent 每次决策。
 *
 * 问题：日志可以被修改/删除——SOC2 审计不认可。
 */
@Component
public class SimpleAuditLogger {

    public void log(String tenantId, String userId,
                    String action, String detail) {
        System.out.printf("""
            [AUDIT] %s | tenant=%s user=%s action=%s detail=%s%n
            """, Instant.now(), tenantId, userId, action, detail);

        // 也写入数据库
        jdbc.update("""
            INSERT INTO audit_log
            (id, timestamp, tenant_id, user_id, action, detail)
            VALUES (?, NOW(), ?, ?, ?, ?)
            """,
            UUID.randomUUID().toString(),
            tenantId, userId, action, detail);
    }
}
```

> ✅ V1 的价值：Agent 决策有记录。
>
> ❌ V1 的问题：日志可被篡改——UPDATE audit_log SET detail='...' 就改了。

---

## V2：1 天——不可篡改链式审计

> **V1 的问题**：日志可被修改。
> **V2 的目标**：链式 Hash——每条日志包含前一条的 Hash，篡改任何一条链就断了。

### Step 2.1：链式 Hash 审计

```java
package com.complyguard.audit.v2;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * V2：不可篡改审计日志
 *
 * SOC2 Type II 要求：审计日志不可被修改或删除。
 * 实现方式：每条日志附带前一条的 Hash，形成链式结构（类似区块链）。
 *
 * 篡改任何一条 → Hash 不匹配 → 链断裂 → 可检测。
 */
@Component
public class ImmutableAuditLog {

    private String previousHash = "GENESIS";

    /**
     * 记录 Agent 决策（不可篡改）
     */
    public void log(AgentDecision decision) {
        String record = serialize(decision);
        String hash = sha256(record + "|" + previousHash);

        // 写入只追加表（INSERT ONLY——DB 层禁止 UPDATE/DELETE）
        jdbc.update("""
            INSERT INTO audit_log
            (id, timestamp, tenant_id, user_id, agent_type,
             decision, input_hash, output_hash,
             model_version, prompt_version,
             record_hash, previous_hash)
            VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID().toString(),
            decision.tenantId(), decision.userId(),
            decision.agentType(), decision.decision(),
            sha256(decision.input()).substring(0, 16),
            sha256(decision.output()).substring(0, 16),
            decision.modelVersion(), decision.promptVersion(),
            hash, previousHash);

        previousHash = hash;
    }

    /**
     * 验证审计链完整性
     *
     * 定期执行——如果返回 false，说明有人篡改了日志。
     */
    public boolean verifyChain() {
        var rows = jdbc.query(
            "SELECT record_hash, previous_hash, decision FROM audit_log ORDER BY timestamp",
            (rs, i) -> new String[]{
                rs.getString("record_hash"),
                rs.getString("previous_hash"),
                rs.getString("decision")
            });

        String prev = "GENESIS";
        for (String[] row : rows) {
            String expectedHash = sha256(row[2] + "|" + prev);

            if (!expectedHash.equals(row[0])) {
                // Hash 不匹配——篡改！
                return false;
            }
            if (!prev.equals(row[1])) {
                // 前一条 Hash 不匹配——篡改或删除
                return false;
            }
            prev = row[0];
        }
        return true;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String serialize(AgentDecision d) {
        return d.tenantId() + "|" + d.userId() + "|" + d.decision()
            + "|" + d.modelVersion() + "|" + d.promptVersion();
    }

    public record AgentDecision(
        String tenantId, String userId,
        String agentType, String decision,
        String input, String output,
        String modelVersion, String promptVersion
    ) {}
}
```

### Step 2.2：DB 层禁止 UPDATE/DELETE

```sql
-- PostgreSQL: 撤销普通用户的 UPDATE/DELETE 权限
REVOKE UPDATE, DELETE ON audit_log FROM app_user;

-- 只允许 INSERT 和 SELECT
GRANT INSERT, SELECT ON audit_log TO app_user;

-- 或者用触发器阻止修改
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only. Modification is not allowed.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER no_update_audit
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
```

> ✅ V2 的价值：审计日志不可篡改，SOC2 认可。
>
> ❌ V2 的问题：没有自动合规报告。

---

## V3：1 天——自动合规报告

> **V2 的问题**：审计数据有了但没人看。
> **V3 的目标**：自动生成月度合规报告 + 主动发现违规。

### Step 3.1：合规报告生成

```java
package com.complyguard.audit.v3;

import org.springframework.stereotype.Component;
import java.time.*;

/**
 * V3：自动合规报告
 *
 * 每月自动生成，涵盖 GDPR / SOC2 / HIPAA 所需指标。
 */
@Component
public class ComplianceReportService {

    /**
     * 生成月度合规报告
     */
    public ComplianceReport generateMonthly(String tenantId, YearMonth month) {
        return new ComplianceReport(
            tenantId,
            month,

            // === 数据处理统计 ===
            countAiRequests(tenantId, month),
            countBlockedByPolicy(tenantId, month),
            countSensitiveDataDetected(tenantId, month),

            // === 用户权利统计 ===
            countDeletionRequests(tenantId, month),
            countDataExportRequests(tenantId, month),
            averageDeletionTime(tenantId, month),  // 被遗忘权处理时长

            // === 安全统计 ===
            countSecurityIncidents(tenantId, month),
            countCrossTenantAttempts(tenantId, month),

            // === 审计完整性 ===
            auditLog.verifyChain(),
            countAuditRecords(tenantId, month),

            // === 数据驻留 ===
            calculateResidencyCompliance(tenantId, month),

            // === 导出 ===
            "Compliance-" + tenantId + "-" + month + ".json"
        );
    }

    /**
     * 检查合规违规
     */
    public List<ComplianceViolation> detectViolations(String tenantId) {
        List<ComplianceViolation> violations = new ArrayList<>();

        // 1. 数据驻留违规
        if (hasCrossBorderDataFlow(tenantId)) {
            violations.add(new ComplianceViolation(
                "DATA_RESIDENCY",
                "检测到跨境数据流动，未配置 SCC",
                ComplianceSeverity.HIGH
            ));
        }

        // 2. 被遗忘权超时（GDPR 要求 30 天内响应）
        long pendingDeletions = countPendingDeletionRequests(tenantId);
        if (pendingDeletions > 0) {
            violations.add(new ComplianceViolation(
                "DELETION_TIMEOUT",
                pendingDeletions + " 个删除请求待处理",
                ComplianceSeverity.MEDIUM
            ));
        }

        // 3. 审计链断裂
        if (!auditLog.verifyChain()) {
            violations.add(new ComplianceViolation(
                "AUDIT_CHAIN_BROKEN",
                "审计日志链验证失败——可能存在篡改",
                ComplianceSeverity.CRITICAL
            ));
        }

        // 4. 缺少用户同意
        long noConsent = countRequestsWithoutConsent(tenantId);
        if (noConsent > 0) {
            violations.add(new ComplianceViolation(
                "MISSING_CONSENT",
                noConsent + " 次 AI 处理缺少用户同意",
                ComplianceSeverity.HIGH
            ));
        }

        return violations;
    }

    // === 数据结构 ===

    public record ComplianceReport(
        String tenantId, YearMonth month,
        int totalAiRequests, int blockedRequests, int sensitiveDataDetected,
        int deletionRequests, int exportRequests, double avgDeletionHours,
        int securityIncidents, int crossTenantAttempts,
        boolean auditChainValid, int auditRecords,
        double residencyComplianceRate,
        String exportFile
    ) {}

    public record ComplianceViolation(
        String type, String description, ComplianceSeverity severity
    ) {}

    public enum ComplianceSeverity { LOW, MEDIUM, HIGH, CRITICAL }
}
```

### Step 3.2：定时报告 + 通知

```java
/**
 * 每月 1 号自动生成报告并通知
 */
@Scheduled(cron = "0 0 8 1 * *")
public void monthlyReport() {
    List<String> tenants = tenantService.getAllTenants();

    for (String tenantId : tenants) {
        var report = complianceReportService
            .generateMonthly(tenantId, YearMonth.now().minusMonths(1));

        // 检查违规
        var violations = complianceReportService.detectViolations(tenantId);

        // 发送报告给租户管理员
        notificationService.sendReport(tenantId, report, violations);

        // 有严重违规 → 告警合规官
        if (violations.stream().anyMatch(
                v -> v.severity() == ComplianceSeverity.CRITICAL)) {
            alertService.pageComplianceOfficer(tenantId, violations);
        }
    }
}
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 普通日志 | V2 链式审计 | V3 合规报告 |
|------|-----------|-----------|-----------|
| **不可篡改** | ❌ | ✅ 链式 Hash | ✅ |
| **完整性验证** | ❌ | ✅ verifyChain() | ✅ |
| **合规报告** | ❌ | ❌ | ✅ 月度自动 |
| **违规检测** | ❌ | ❌ | ✅ 主动发现 |
| **标准覆盖** | 无 | SOC2 | GDPR + SOC2 + HIPAA |

---

## 验收检查

- [ ] V1：基本审计日志可记录
- [ ] V2：链式 Hash + verifyChain 通过
- [ ] V3：月度报告 + 违规检测 + 定时通知

---

## 下一步

→ [Sprint 4：策略引擎与部署](Sprint4-策略部署.md)
