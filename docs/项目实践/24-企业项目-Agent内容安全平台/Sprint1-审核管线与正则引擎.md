# Sprint 1 · 审核管线框架 + 正则规则引擎

> P24 SafeGuard · 第 1 周

---

## 目标

搭建内容审核管线框架，实现基于正则规则的快速审核。

## 任务清单

- [ ] 审核管线框架（串行/短路）
- [ ] 正则规则引擎（PII/密钥/URL）
- [ ] 关键词过滤（敏感词列表）
- [ ] 审核结果模型（PASS/FLAG/BLOCK + 原因）
- [ ] 审计日志（异步写入）

## 管线框架

```java
@Component
public class ModerationPipeline {
    private final List<Moderator> moderators;

    public ModerationResult moderate(String text, Direction dir) {
        for (Moderator m : moderators) {
            ModerationResult r = m.check(text, dir);
            if (r.action() == Action.BLOCK) return r; // 短路
        }
        return ModerationResult.pass();
    }
}
```

## 正则规则

```java
public class RegexModerator implements Moderator {
    private static final List<Rule> RULES = List.of(
        new Rule("PHONE", "1[3-9]\\d{9}", HIGH, MASK),
        new Rule("ID_CARD", "\\d{17}[\\dXx]", HIGH, MASK),
        new Rule("OPENAI_KEY", "sk-[A-Za-z0-9]{48}", CRITICAL, BLOCK),
        new Rule("PRIVATE_KEY", "-----BEGIN.*PRIVATE KEY-----", CRITICAL, BLOCK)
    );
}
```

## 验收

- [ ] 手机号/身份证能被脱敏（MASK）
- [ ] API Key/私钥能被拦截（BLOCK）
- [ ] 审核管线短路：严重违规立即返回
- [ ] 审计日志正确记录
