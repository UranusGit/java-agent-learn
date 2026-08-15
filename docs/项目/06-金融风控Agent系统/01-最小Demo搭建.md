# 项目 06：金融风控 Agent 系统 — 01-最小 Demo 搭建

> **定位**：最小预审 Agent——信贷材料文本输入，产出结构化预审意见（风险等级/理由/置信度）。本篇刻意不碰审批流与审计，先让预审能力本身立住。**本文给出完整可手写代码（一行不省略）**。
>
> 「遇到阻塞？→ [教程 12-结构化输出 §entity]、[教程 02-ChatClient与对话模型]、[教程 01-Spring-AI框架入门]」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的预审 Agent：POST 材料文本 → 返回结构化预审意见 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → ChatClient → 结构化输出；无审批、无审计、无记忆 |
| **上一版痛点是什么** | 无（v0 是起点） |

**本迭代明确不做**：不做上传/解析（材料以纯文本传入）、不做审批（意见直接返回）、不做审计（v4 再来）、不做多模型（v5 再来）。

## 2. 结构化预审意见设计

**为什么先设计输出再写代码**：结构化输出是整个系统的契约核心——审批工作台（v3）、审计回放（v4）、交叉验证比对（v5）全部消费这个结构。**输出 schema 是风控系统的 API**。

### 2.1 `PreTrialOpinion.java`（输出契约 record）

```java
package com.bank.risk.domain;

import java.util.List;

/**
 * 结构化预审意见 —— 与 entity(Class) 配合的结构化输出契约（教程 12）。
 * 全项目消费方：审批工作台(v3)、审计回放(v4)、交叉验证比对(v5)。
 */
public record PreTrialOpinion(
        String applicationId,           // 关联申请编号
        RiskLevel riskLevel,            // LOW / MEDIUM / HIGH / REJECT_SUGGEST
        double confidence,              // 0.0-1.0 置信度
        List<RiskFactor> riskFactors,   // 风险因子明细
        List<String> missingMaterials,  // 材料缺失清单
        String summaryReason            // 一段人话总结（给审批员看的）
) {
    public enum RiskLevel {
        LOW, MEDIUM, HIGH, REJECT_SUGGEST
    }

    public record RiskFactor(
            String dimension,           // 经营稳定性/流水真实性/征信记录/行业风险
            String severity,            // low/medium/high
            String evidence,            // 材料中的依据（可回溯）
            String description
    ) {}
}
```

## 3. 核心实现

### 3.1 `RiskAgentConfig.java`（ChatClient Bean）

```java
package com.bank.risk.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RiskAgentConfig {

    @Bean
    public ChatClient pretrialChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        你是小微企业信贷预审助手。根据信贷员提供的申请材料输出预审意见。
                        规则：
                        1. 每个风险因子必须引用材料中的具体证据，禁止臆造
                        2. 材料不足以判断的维度，归入 missingMaterials 而不是猜测
                        3. 你只做预审建议，终审由人工完成——输出中不得出现"批准"字样的最终决定
                        """)
                .build();
    }
}
```

### 3.2 `PreTrialService.java`（预审服务）

```java
package com.bank.risk.service;

import com.bank.risk.domain.PreTrialOpinion;
import com.bank.risk.domain.PreTrialOpinion.RiskLevel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class PreTrialService {

    private final ChatClient chatClient;

    public PreTrialService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Mono<PreTrialOpinion> preTrial(String applicationId, String materialText) {
        return Mono.fromCallable(() -> {
                    PreTrialOpinion opinion = chatClient.prompt()
                            .user(u -> u.text("""
                                    申请编号：{appId}
                                    申请材料：
                                    {material}
                                    """)
                                    .param("appId", applicationId)
                                    .param("material", materialText))
                            .call()
                            .entity(PreTrialOpinion.class);   // 结构化输出（教程 12 §entity(Class)）
                    return validate(opinion);
                })
                .subscribeOn(Schedulers.boundedElastic());  // 阻塞调用桥接（教程 24 §弹性调度）
    }

    /** 分层信任：entity 只保证 JSON 可解析；这里做语义校验（置信度范围、等级与因子逻辑一致）。 */
    private PreTrialOpinion validate(PreTrialOpinion opinion) {
        if (opinion.confidence() < 0 || opinion.confidence() > 1) {
            throw new OpinionValidationException("confidence 越界: " + opinion.confidence());
        }
        if (opinion.riskLevel() == RiskLevel.HIGH && opinion.riskFactors().stream()
                .noneMatch(f -> "high".equals(f.severity()))) {
            throw new OpinionValidationException("HIGH 等级必须至少有一个 high 风险因子支撑");
        }
        return opinion;
    }
}
```

> **API 真实性标注**：`entity(Class)` 是 Spring AI 2.0.0 真实重载（`CallResponseSpec.entity(Class<T>)`）。**不使用任何 spec-lambda 重载**（`entity(Class, spec -> ...)` 是虚构 API，见 [附录 12 §entity 真实重载]）。阻塞调用用 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 桥接——`chatClient.call()` 直接跑在 Netty 线程会阻塞 EventLoop（[教程 24 §弹性调度]）。

### 3.3 `OpinionValidationException.java`（业务校验异常）

```java
package com.bank.risk.service;

/** 结构化输出语义校验失败 —— 不能信任 LLM 输出的格式与数值范围。 */
public class OpinionValidationException extends RuntimeException {

    public OpinionValidationException(String message) {
        super(message);
    }
}
```

### 3.4 `PreTrialController.java`（WebFlux 入口）

```java
package com.bank.risk.web;

import com.bank.risk.domain.PreTrialOpinion;
import com.bank.risk.service.PreTrialService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/pretrial")
public class PreTrialController {

    private final PreTrialService preTrialService;

    public PreTrialController(PreTrialService preTrialService) {
        this.preTrialService = preTrialService;
    }

    @PostMapping
    public Mono<PreTrialOpinion> pretrial(
            @RequestParam String applicationId,
            @RequestBody String materialText) {
        return preTrialService.preTrial(applicationId, materialText);
    }
}
```

### 3.5 校验层（不能信任 LLM 输出的格式）

**分层信任**：`entity(Class)` 只保证 JSON 能被反序列化，不保证数值语义正确。置信度越界、`HIGH` 等级却没有 `high` 风险因子支撑，都是业务校验层必须拦住的（[教程 12 §输出校验]）。上述 `PreTrialService#validate` 中的两层校验——格式由框架保证（entity），语义由业务保证（validate）——缺一不可。

### 3.6 最容易写错的三个姿势（对照）

| # | 错误姿势 | 后果 | 正确姿势 |
|---|---------|------|---------|
| 1 | `chatClient.call()` 直接在 Netty 线程跑 | EventLoop 阻塞，全线卡死 | `Mono.fromCallable(...).subscribeOn(boundedElastic)` |
| 2 | `entity(PreTrialOpinion.class, spec -> spec.validateSchema())` | 该重载是虚构 API，编译不过 | `entity(PreTrialOpinion.class)`（[附录 12 §entity 真实重载]） |
| 3 | 拿到 `entity()` 结果不校验直接用 | confidence 越界、HIGH 无 high 因子支撑仍被消费 | 业务层 `validate(...)` 兜底 |

## 4. 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run
# 测试：
# curl -X POST "http://localhost:8080/api/pretrial?applicationId=SO-0001" \
#   -H "Content-Type: text/plain" -d "营业执照: 广州XX贸易有限公司... 流水: 月均 50 万，年末 3 个月骤降..."
```

## 5. 验收标准

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 结构化成功率 | 100 份样本材料，JSON 解析 + 语义校验通过率 ≥ 95% |
| 2 | 证据可回溯 | 抽检风险因子 evidence 字段能在材料原文中定位 |
| 3 | 缺失归位 | 材料缺失的维度进 missingMaterials，不臆造风险因子 |
| 4 | 无终审越权 | 输出中无"批准/拒绝"最终决定表述（只有建议等级） |

## 6. v1 的痛点

跑通一周后，两类问题浮现：

1. **置信度是"自由心证"**——LLM 报 0.9 的建议照样出错，报 0.5 的有时很准。预审意见缺少**可操作的分级机制**：什么级别的建议可以进快速通道、什么级别必须转人工？——v2 解决
2. **没有人工环节**——信贷员直接拿 HIGH 建议当结论用，监管红线（终审必须人工）形同虚设——v3 的核心痛点

## 7. 总结

| 概念 | 一句话 |
|------|--------|
| 输出契约 | 先定义 PreTrialOpinion schema 再写代码——schema 是风控系统的 API |
| 结构化输出 | `entity(Class)` 真实重载；spec-lambda 是虚构 API |
| 阻塞桥接 | `Mono.fromCallable(...).subscribeOn(boundedElastic)`，绝不在 EventLoop 上 block |
| 分层校验 | 框架管格式，业务管语义——两层都要 |

→ [02-迭代一-置信度与风险分级.md](02-迭代一-置信度与风险分级.md)
