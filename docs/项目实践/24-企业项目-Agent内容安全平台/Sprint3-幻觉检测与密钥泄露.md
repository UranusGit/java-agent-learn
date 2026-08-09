# Sprint 3 · 幻觉检测与密钥泄露检测

> P24 SafeGuard · 第 3 周

---

## 目标

实现输出侧的幻觉检测和敏感信息泄露防护。

## 任务清单

- [ ] 幻觉检测（输出事实 vs RAG 引用一致性）
- [ ] 数字一致性校验（LLM 计算结果验证）
- [ ] 内部文档泄露检测（输出包含不应泄露的内容）
- [ ] 品牌安全检查（不当言论/歧视性内容）
- [ ] 输出审核结果可视化

## 幻觉检测

```java
public class HallucinationDetector implements Moderator {
    /**
     * 提取输出中的事实性陈述，与 RAG 文档交叉验证
     */
    public ModerationResult check(String output, String ragContext) {
        List<String> claims = extractNumericClaims(output);
        for (String claim : claims) {
            if (!supportedByContext(claim, ragContext)) {
                return ModerationResult.flag("可能的幻觉：" + claim);
            }
        }
        return ModerationResult.pass();
    }
}
```

## 密钥泄露

```java
public class SecretLeakDetector implements Moderator {
    static final List<Pattern> SECRETS = List.of(
        new Pattern("AWS_KEY", "AKIA[0-9A-Z]{16}", CRITICAL),
        new Pattern("JWT", "eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", HIGH),
        new Pattern("GITHUB_TOKEN", "gh[ps]_[A-Za-z0-9]{36}", CRITICAL),
        new Pattern("SLACK_TOKEN", "xox[bp]-[A-Za-z0-9-]+", HIGH)
    );
}
```

## 验收

- [ ] 输出中的虚构数字能被标记
- [ ] API Key / JWT / Token 泄露能被拦截
- [ ] 内部文档内容泄露能被检测
- [ ] 审核结果记录了完整的检测链
