# ComplyGuard Sprint 1 · 数据分类与脱敏（从最简版开始）

> **目标**：从"关键词黑名单"开始，一步步长成五级敏感分类 + 自动脱敏
> **前置**：了解 Spring AI ChatClient 基础

---

## V1：30 分钟——关键词过滤

> **思路**：先不搞分类、不搞脱敏。最简单的合规就是拦截几个敏感词。

### Step 1：关键词黑名单

```java
package com.complyguard.v1;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V1 极简版：关键词黑名单
 *
 * 拦截包含敏感关键词的用户输入。
 *
 * 问题：太粗暴——"我的密码是 123456" 直接被拦了
 * 但它验证了"输入可以在进 Agent 之前被检查"。
 */
@Component
public class KeywordFilter {

    private static final Set<String> BLOCKED = Set.of(
        "密码", "password", "身份证", "idcard",
        "银行卡", "信用卡", "bankcard",
        "社保号", "ssn", "病历", "处方"
    );

    public FilterResult check(String input) {
        String lower = input.toLowerCase();
        for (String keyword : BLOCKED) {
            if (lower.contains(keyword)) {
                return FilterResult.blocked(
                    "输入包含敏感关键词：" + keyword);
            }
        }
        return FilterResult.allowed();
    }

    public record FilterResult(boolean allowed, String reason) {
        public static FilterResult allowed() { return new FilterResult(true, null); }
        public static FilterResult blocked(String reason) { return new FilterResult(false, reason); }
    }
}
```

> ✅ V1 的价值：敏感输入在进 Agent 前被拦截。
>
> ❌ V1 的问题：关键词太粗暴——用户可能合法地讨论这些话题（如咨询密码安全），全拦了影响体验。

---

## V2：1 天——正则分类 + 自动脱敏

> **V1 的问题**：不是拦截，而是**识别→脱敏**——把敏感数据替换成掩码后再发给 Agent。

### Step 2.1：敏感数据分类器

```java
package com.complyguard.v2;

import org.springframework.stereotype.Component;
import java.util.regex.*;

@Component
public class DataClassifier {

    /**
     * 分类敏感数据
     *
     * V1 只是关键词拦截，V2 能精确识别敏感数据的类型和位置。
     */
    public List<SensitiveDataMatch> scan(String text) {
        List<SensitiveDataMatch> matches = new ArrayList<>();

        // 中国身份证号（18 位）
        findMatches(text, Patterns.ID_CARD, SensitivityLevel.PII, "身份证号")
            .forEach(matches::add);

        // 手机号
        findMatches(text, Patterns.PHONE, SensitivityLevel.PII, "手机号")
            .forEach(matches::add);

        // 邮箱
        findMatches(text, Patterns.EMAIL, SensitivityLevel.PII, "邮箱")
            .forEach(matches::add);

        // 银行卡号（16-19 位）
        findMatches(text, Patterns.BANK_CARD, SensitivityLevel.PCI, "银行卡号")
            .forEach(matches::add);

        // 医疗关键词
        findMatches(text, Patterns.PHI, SensitivityLevel.PHI, "医疗信息")
            .forEach(matches::add);

        // 机密标记
        findMatches(text, Patterns.CONFIDENTIAL, SensitivityLevel.CONFIDENTIAL, "机密标记")
            .forEach(matches::add);

        return matches;
    }

    private List<SensitiveDataMatch> findMatches(
            String text, Pattern pattern,
            SensitivityLevel level, String typeName) {

        List<SensitiveDataMatch> results = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            results.add(new SensitiveDataMatch(
                m.start(), m.end(), m.group(),
                level, typeName
            ));
        }
        return results;
    }

    public enum SensitivityLevel {
        PUBLIC,         // 公开
        INTERNAL,       // 内部
        CONFIDENTIAL,   // 机密
        PII,            // 个人身份信息
        PHI,            // 医疗健康信息
        PCI             // 支付卡信息
    }

    public record SensitiveDataMatch(
        int start, int end, String matched,
        SensitivityLevel level, String typeName
    ) {}

    // 正则模式
    static class Patterns {
        static final Pattern ID_CARD =
            Pattern.compile("[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]");
        static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
        static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}");
        static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
        static final Pattern PHI =
            Pattern.compile("(?i)(诊断|处方|病历|检查结果|HIV阳性|癌症晚期|精神分裂)");
        static final Pattern CONFIDENTIAL =
            Pattern.compile("(?i)(绝密|机密|内部文件|confidential|top secret|classified)");
    }
}
```

### Step 2.2：自动脱敏

```java
package com.complyguard.v2;

import org.springframework.stereotype.Component;

/**
 * 自动脱敏——把敏感数据替换为掩码后再发给 Agent
 *
 * "我的手机号是 13812345678"
 * → "我的手机号是 138****5678"
 *
 * "身份证号是 110101199001011234"
 * → "身份证号是 110101********1234"
 */
@Component
public class DataMasker {

    private final DataClassifier classifier;

    /**
     * 脱敏——替换所有敏感数据
     */
    public String mask(String input) {
        var matches = classifier.scan(input);

        // 从后往前替换（避免位置偏移）
        StringBuilder sb = new StringBuilder(input);
        matches.stream()
            .sorted((a, b) -> Integer.compare(b.start(), a.start()))
            .forEach(m -> {
                String mask = createMask(m);
                sb.replace(m.start(), m.end(), mask);
            });

        return sb.toString();
    }

    private String createMask(DataClassifier.SensitiveDataMatch match) {
        return switch (match.typeName()) {
            case "身份证号" -> maskKeepHeadTail(match.matched(), 6, 4);
            case "手机号" -> maskKeepHeadTail(match.matched(), 3, 4);
            case "邮箱" -> maskEmail(match.matched());
            case "银行卡号" -> maskKeepHeadTail(match.matched(), 4, 4);
            default -> "****";
        };
    }

    private String maskKeepHeadTail(String text, int headKeep, int tailKeep) {
        if (text.length() <= headKeep + tailKeep) return "****";
        String head = text.substring(0, headKeep);
        String tail = text.substring(text.length() - tailKeep);
        String middle = "*".repeat(text.length() - headKeep - tailKeep);
        return head + middle + tail;
    }

    private String maskEmail(String email) {
        int at = email.indexOf("@");
        if (at <= 1) return "****" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * 生成脱敏报告（审计用）
     */
    public MaskReport report(String original, String masked) {
        var matches = classifier.scan(original);
        return new MaskReport(
            matches.size(),
            matches.stream().map(DataClassifier.SensitiveDataMatch::typeName).distinct().toList(),
            matches.stream().map(DataClassifier.SensitiveDataMatch::level).distinct().toList()
        );
    }

    public record MaskReport(int count, List<String> types, List<DataClassifier.SensitivityLevel> levels) {}
}
```

### Step 2.3：脱敏 Advisor

```java
package com.complyguard.v2;

import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.stereotype.Component;

/**
 * 脱敏 Advisor——自动拦截输入并脱敏
 */
@Component
public class DataMaskingAdvisor implements CallAdvisor {

    private final DataMasker masker;

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        // 1. 脱敏用户输入
        String original = request.userText();
        String masked = masker.mask(original);

        if (!original.equals(masked)) {
            // 2. 用脱敏后的内容替换
            AdvisedRequest maskedRequest = AdvisedRequest.from(request)
                .withUserText(masked)
                .build();

            // 3. 记录脱敏审计
            var report = masker.report(original, masked);
            auditLog.record("DATA_MASKED", report);

            return chain.nextCall(maskedRequest);
        }

        return chain.nextCall(request);
    }

    @Override
    public int getOrder() { return -150; }  // 高优先级
}
```

> ✅ V2 的价值：精确识别 + 自动脱敏 + 审计记录。
>
> ❌ V2 的问题：脱敏后 Agent 可能需要原始数据（如查询订单需要手机号），一刀切脱敏影响功能。

---

## V3：1 天——策略化脱敏 + 上下文感知

> **V2 的问题**：所有敏感数据一律脱敏，但有些场景需要原始数据。
> **V3 的目标**：根据上下文决定脱敏策略——查询订单不脱敏手机号，闲聊时脱敏。

### Step 3.1：上下文感知脱敏策略

```java
package com.complyguard.v3;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * V3 新增：上下文感知脱敏策略
 *
 * V2 一刀切脱敏，V3 根据场景决定：
 * - 查询订单场景：手机号部分可见（后 4 位匹配）
 * - 闲聊场景：手机号完全脱敏
 * - 安全咨询场景：密码相关关键词允许讨论
 */
@Component
public class ContextAwareMasker {

    /**
     * 根据意图决定脱敏策略
     */
    public MaskStrategy determineStrategy(String userInput, String intent) {
        return switch (intent) {
            case "order_query" -> MaskStrategy.partial();  // 部分脱敏
            case "general_chat" -> MaskStrategy.strict();   // 严格脱敏
            case "security_consult" -> MaskStrategy.relaxed();  // 放宽
            default -> MaskStrategy.strict();  // 默认严格
        };
    }

    /**
     * 按策略脱敏
     */
    public String mask(String input, MaskStrategy strategy) {
        var matches = classifier.scan(input);

        StringBuilder sb = new StringBuilder(input);
        matches.stream()
            .sorted((a, b) -> Integer.compare(b.start(), a.start()))
            .forEach(m -> {
                if (shouldMask(m, strategy)) {
                    String mask = createMaskByStrategy(m, strategy);
                    sb.replace(m.start(), m.end(), mask);
                }
            });

        return sb.toString();
    }

    private boolean shouldMask(DataClassifier.SensitiveDataMatch match,
                                MaskStrategy strategy) {
        if (strategy == MaskStrategy.relaxed()) {
            // 放宽模式：只脱敏 PCI 和 机密
            return match.level() == DataClassifier.SensitivityLevel.PCI
                || match.level() == DataClassifier.SensitivityLevel.CONFIDENTIAL;
        }
        if (strategy == MaskStrategy.partial()) {
            // 部分模式：不脱敏邮箱和手机号（查询需要）
            return match.level() != DataClassifier.SensitivityLevel.PII
                || match.typeName().equals("身份证号");
        }
        // 严格模式：全部脱敏
        return true;
    }

    public record MaskStrategy(String level) {
        public static MaskStrategy strict() { return new MaskStrategy("STRICT"); }
        public static MaskStrategy partial() { return new MaskStrategy("PARTIAL"); }
        public static MaskStrategy relaxed() { return new MaskStrategy("RELAXED"); }
    }
}
```

### Step 3.2：脱敏还原（输出侧）

```java
/**
 * 输出脱敏还原
 *
 * Agent 的回答中可能包含掩码（如"您的手机号 138****5678"）。
 * 如果用户有权查看原始数据，可以将掩码还原。
 */
@Component
public class DataUnmasker {

    private final Map<String, String> maskMap = new ConcurrentHashMap<>();

    /**
     * 记录脱敏映射（在脱敏时调用）
     */
    public void recordMask(String masked, String original) {
        maskMap.put(masked, original);
    }

    /**
     * 还原脱敏数据
     */
    public String unmask(String output, String userId) {
        // 检查用户是否有权查看原始数据
        if (!hasPermission(userId)) {
            return output;  // 无权——保持脱敏
        }

        for (var entry : maskMap.entrySet()) {
            output = output.replace(entry.getKey(), entry.getValue());
        }
        return output;
    }

    private boolean hasPermission(String userId) {
        // 查权限表
        return true; // 简化
    }
}
```

---

## V1 → V2 → V3 演进总结

| 维度 | V1 关键词 | V2 正则脱敏 | V3 策略感知 |
|------|----------|-----------|-----------|
| **识别方式** | 关键词匹配 | 正则精确匹配 | + 意图上下文 |
| **处理方式** | 直接拦截 | 自动脱敏 | 策略化脱敏 |
| **粒度** | 全/无 | 五级敏感 | 五级 + 场景策略 |
| **可逆性** | 不可逆 | 不可逆 | + 可还原（有权限） |

---

## 验收检查

- [ ] V1：关键词过滤能拦截 5+ 个敏感词
- [ ] V2：正则识别 6 类敏感数据 + 自动脱敏 + Advisor 集成
- [ ] V3：上下文感知策略 + 脱敏还原

---

## 下一步

→ [Sprint 2：多租户隔离](Sprint2-多租户隔离.md)
