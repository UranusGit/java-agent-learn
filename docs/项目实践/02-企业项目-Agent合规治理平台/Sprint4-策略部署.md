# ComplyGuard Sprint 4 · 策略引擎与部署（从最简版开始）

> **目标**：从"几条 if-else"开始，一步步长成多维合规策略引擎 + Docker 全栈部署
> **前置**：Sprint 1-3 数据分类 + 租户隔离 + 审计

---

## V1：30 分钟——硬编码策略

> **思路**：先不搞策略引擎。最简单的合规策略就是几条 if-else。

### Step 1：规则检查

```java
package com.complyguard.policy.v1;

import org.springframework.stereotype.Component;

/**
 * V1 极简版：硬编码合规规则
 */
@Component
public class SimpleComplianceChecker {

    public ComplianceResult check(String userInput, String userRegion) {
        // 规则 1：EU 用户不能处理 PHI
        if (userRegion.equals("EU") && containsMedicalInfo(userInput)) {
            return ComplianceResult.blocked("EU 用户数据不能处理医疗信息");
        }

        // 规则 2：必须用户同意
        // ... 硬编码检查

        return ComplianceResult.allowed();
    }

    private boolean containsMedicalInfo(String text) {
        return text.matches("(?i).*(诊断|处方|病历).*");
    }

    public record ComplianceResult(boolean allowed, String reason) {
        public static ComplianceResult allowed() { return new ComplianceResult(true, null); }
        public static ComplianceResult blocked(String reason) { return new ComplianceResult(false, reason); }
    }
}
```

> ✅ V1 的价值：基本合规规则能拦截。
>
> ❌ V1 的问题：硬编码无法配置——每个租户合规要求不同。

---

## V2：1 天——可配置策略引擎

> **V1 的问题**：规则写死，无法按租户/地区配置。
> **V2 的目标**：数据库驱动的策略引擎——按租户 + 地区 + 数据类型配置。

### Step 2.1：合规策略引擎

```java
package com.complyguard.policy.v2;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V2：可配置合规策略引擎
 *
 * V1 硬编码，V2 从数据库加载策略。
 * 按租户 + 地区 + 数据敏感级别组合决策。
 */
@Component
public class CompliancePolicyEngine {

    /**
     * 检查请求是否合规
     */
    public ComplianceDecision check(ComplianceContext ctx) {
        List<String> violations = new ArrayList<>();
        List<String> requiredActions = new ArrayList<>();

        // 1. 数据驻留检查
        if (ctx.sensitivity().ordinal() >= SensitivityLevel.PII.ordinal()
            && !isDataResidencyCompliant(ctx)) {
            violations.add(String.format(
                "%s 数据必须存储在 %s 区域（当前处理区域：%s）",
                ctx.sensitivity(), ctx.userRegion(), ctx.processingRegion()));
        }

        // 2. 用户同意检查
        if (ctx.sensitivity().ordinal() >= SensitivityLevel.PII.ordinal()
            && !ctx.consent().aiProcessing()) {
            violations.add("用户未同意 AI 处理敏感数据");
        }

        // 3. 自动化决策权（GDPR Article 22）
        if (ctx.isAutomatedDecision() && !ctx.consent().automatedDecision()) {
            violations.add("涉及自动决策但用户未授权");
            requiredActions.add("转人工处理");
        }

        // 4. 跨境数据传输
        if (!ctx.userRegion().equals(ctx.processingRegion())) {
            if (!hasAdequacyDecision(ctx.userRegion(), ctx.processingRegion())) {
                requiredActions.add("需要 SCC（标准合同条款）或用户明确授权");
            }
        }

        // 5. 保留期限
        if (ctx.dataAge() != null && isExpired(ctx)) {
            requiredActions.add("数据已过保留期，需要删除");
        }

        if (!violations.isEmpty()) {
            return ComplianceDecision.block(violations, requiredActions);
        }

        return ComplianceDecision.allow(requiredActions);
    }

    private boolean isDataResidencyCompliant(ComplianceContext ctx) {
        // 加载租户的驻留策略
        ResidencyPolicy policy = loadResidencyPolicy(ctx.tenantId());
        return policy.allowedRegions().contains(ctx.processingRegion());
    }

    private boolean hasAdequacyDecision(String from, String to) {
        // GDPR adequacy decision 表
        Set<String> adequate = Set.of("EU", "UK", "JP", "KR", "CH");
        return adequate.contains(to) || from.equals(to);
    }

    // === 数据结构 ===

    public record ComplianceContext(
        String tenantId, String userId,
        String userRegion, String processingRegion,
        SensitivityLevel sensitivity,
        UserConsent consent,
        boolean isAutomatedDecision,
        Duration dataAge
    ) {}

    public record UserConsent(
        boolean aiProcessing,
        boolean dataRetention,
        boolean automatedDecision,
        Instant consentAt
    ) {}

    public record ComplianceDecision(
        boolean allowed,
        List<String> violations,
        List<String> requiredActions
    ) {
        public static ComplianceDecision allow(List<String> actions) {
            return new ComplianceDecision(true, List.of(), actions);
        }
        public static ComplianceDecision block(List<String> v, List<String> a) {
            return new ComplianceDecision(false, v, a);
        }
    }

    public enum SensitivityLevel {
        PUBLIC, INTERNAL, CONFIDENTIAL, PII, PHI, PCI
    }

    public record ResidencyPolicy(
        String tenantId, Set<String> allowedRegions
    ) {}
}
```

### Step 2.2：合规 Advisor

```java
/**
 * 合规 Advisor——自动拦截不合规请求
 */
@Component
public class ComplianceAdvisor implements CallAdvisor {

    private final CompliancePolicyEngine policy;
    private final DataClassifier classifier;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        // 构建合规上下文
        var ctx = buildContext(request);

        // 策略检查
        var decision = policy.check(ctx);

        if (!decision.allowed()) {
            // 记录违规
            auditLog.log("COMPLIANCE_BLOCKED",
                String.join("; ", decision.violations()));

            // 返回合规拒绝消息
            return blockedResponse(
                "您的请求因合规原因无法处理："
                + String.join("；", decision.violations()));
        }

        return chain.nextCall(request);
    }

    @Override
    public int getOrder() { return -200; }  // 合规层最高优先级
}
```

> ✅ V2 的价值：可配置策略 + 按租户/地区差异化管理。
>
> ❌ V2 的问题：没有可视化界面、没有全栈部署。

---

## V3：1 天——合规看板 + Docker 部署

> **V2 的问题**：策略在 DB 里，没有 UI 管理。
> **V3 的目标**：合规看板 + 策略配置 UI + Docker 全栈。

### Step 3.1：合规看板 API

```java
package com.complyguard.policy.v3;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceDashboardController {

    /**
     * 合规总览
     */
    @GetMapping("/overview/{tenantId}")
    public ComplianceOverview overview(@PathVariable String tenantId) {
        return new ComplianceOverview(
            complianceReportService.generateMonthly(
                tenantId, YearMonth.now()),
            complianceReportService.detectViolations(tenantId),
            policyEngine.getActivePolicies(tenantId),
            auditLog.verifyChain()
        );
    }

    /**
     * 策略配置
     */
    @GetMapping("/policies/{tenantId}")
    public List<CompliancePolicy> getPolicies(@PathVariable String tenantId) {
        return policyEngine.getPolicies(tenantId);
    }

    @PutMapping("/policies/{tenantId}")
    public CompliancePolicy updatePolicy(
            @PathVariable String tenantId,
            @RequestBody CompliancePolicy policy) {
        return policyEngine.updatePolicy(tenantId, policy);
    }

    /**
     * 审计日志查询
     */
    @GetMapping("/audit/{tenantId}")
    public AuditPage audit(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return auditLog.query(tenantId, page, size);
    }

    public record ComplianceOverview(
        ComplianceReportService.ComplianceReport report,
        List<ComplianceReportService.ComplianceViolation> violations,
        List<CompliancePolicy> policies,
        boolean auditChainValid
    ) {}
}
```

### Step 3.2：Docker 全栈

```yaml
version: '3.8'
services:
  complyguard:
    build: .
    ports: ["8091:8080"]
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/complyguard
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
    depends_on: [postgres]

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: complyguard
      POSTGRES_USER: complyguard
      POSTGRES_PASSWORD: complyguard
    ports: ["5439:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  redis:
    image: redis:7-alpine
    ports: ["6381:6379"]

volumes:
  pgdata:
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 硬编码 | V2 策略引擎 | V3 看板+部署 |
|------|----------|-----------|------------|
| **策略管理** | if-else | 数据库驱动 | + UI 配置 |
| **合规维度** | 2 条规则 | 5 维（驻留/同意/决策/跨境/保留） | 同 V2 |
| **可视化** | 无 | 无 | 合规看板 |
| **部署** | 无 | 无 | Docker Compose |

---

## 项目总结 & 简历描述

```
Agent 合规治理平台（ComplyGuard）

采用 V1→V2→V3 演进式开发，构建企业级 Agent 合规体系：
- 敏感数据五级分类 + 上下文感知自动脱敏
- 三层多租户隔离（应用层 + 数据层 + 向量层）
- 不可篡改链式审计日志（SHA-256 链 + DB 只追加约束）
- GDPR 被遗忘权 + 数据导出 + 保留策略
- 多维合规策略引擎（驻留 + 同意 + 跨境 + 自动决策）
- 月度自动合规报告 + 违规主动检测
```

---

## 验收检查

- [ ] V1：硬编码规则能拦截基本违规
- [ ] V2：策略引擎按租户/地区差异化配置
- [ ] V3：看板可查、策略可配、Docker 一键部署

→ 返回 [项目实践总览](../00-项目实践总览.md)
