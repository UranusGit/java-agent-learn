# Sprint 2 · LLM 安全审核 + 越狱检测

> P24 SafeGuard · 第 2 周

---

## 目标

实现基于 LLM 的语义安全审核和越狱提示词检测。

## 任务清单

- [ ] LLM 安全审核器（涉黄/涉暴/仇恨/违法分类）
- [ ] 越狱提示词检测（5 种模式）
- [ ] 中英文双语规则
- [ ] 审核结果缓存（相似内容不重复审核）
- [ ] 异步审核模式（不阻塞主流程）

## 越狱检测模式

```java
public class JailbreakDetector implements Moderator {
    static final List<Pattern> PATTERNS = List.of(
        new Pattern("role_override", List.of("忽略.*指令", "忘记.*规则", "ignore.*previous"), 0.9),
        new Pattern("encoding_bypass", List.of("base64.*解码", "rot13", "翻译后执行"), 0.8),
        new Pattern("authority_claim", List.of("我是.*开发者", "管理员模式", "admin.*mode"), 0.6),
        new Pattern("hypothetical", List.of("假设.*没有.*限制", "理论上.*无规则"), 0.7),
        new Pattern("step_extract", List.of("第一步.*告诉我", "逐字.*输出.*系统.*提示"), 0.85)
    );
}
```

## 验收

- [ ] 能检测到 "忽略之前的指令" 类越狱
- [ ] LLM 审核能分类违规类型
- [ ] 置信度 > 0.8 时拦截
- [ ] 审核延迟 < 3 秒
