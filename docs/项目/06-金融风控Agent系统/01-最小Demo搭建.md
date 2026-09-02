# 项目 06：金融风控 Agent 系统 — 01-最小 Demo 搭建

> **定位**：最小预审 Agent——信贷材料文本输入，产出结构化预审意见（风险等级/理由/置信度）。本篇刻意不碰审批流与审计，先让预审能力本身立住。**本文给出完整可手写代码（一行不省略）**。
>
> 「遇到阻塞？→ [教程 02-SpringAI核心机制/04-结构化输出 §entity]、[教程 00-基础与核心/02-ChatClient与对话模型]、[教程 00-基础与核心/01-Spring-AI框架入门]」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的预审 Agent：POST 材料文本 → 返回结构化预审意见 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → ChatClient → 结构化输出；无审批、无审计、无记忆 |
| **上一版痛点是什么** | 无（v0 是起点） |

**本迭代明确不做**：不做上传/解析（材料以纯文本传入）、不做审批（意见直接返回）、不做审计（v4 再来）、不做多模型（v5 再来）。

### 1.1 本节核对（四问）

- [ ] 能不看正文说出本迭代"明确不做"的四件事（上传解析/审批/审计/多模型）分别推迟到哪一篇
- [ ] 四问中"上一版痛点=无"与 v0 起点一致，无引用不存在的版本

## 2. 结构化预审意见设计

**为什么先设计输出再写代码**：结构化输出是整个系统的契约核心——审批工作台（v3）、审计回放（v4）、交叉验证比对（v5）全部消费这个结构。**输出 schema 是风控系统的 API**。

### 2.1 `PreTrialOpinion.java`（输出契约 record）

```java
package com.bank.risk.domain;

import java.util.List;

/**
 * 结构化预审意见 —— 与 entity(Class) 配合的结构化输出契约（教程 01-WebFlux与响应式编程/03-Sinks详解 §2）。
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

### 2.2 本节测试与验证（输出契约 record）

**前置条件**：00 篇工程骨架已编译通过。

**材料——契约字段核对**：对照 §2.1 代码逐项检查。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 手写 `PreTrialOpinion.java` 后 `mvn clean compile` | `BUILD SUCCESS`；record 为合法 Java 21 语法（嵌套 enum 与 record 均编译通过） |
| 2 | 字段核对 | 6 个组成部分齐全：applicationId / riskLevel / confidence / riskFactors / missingMaterials / summaryReason，无缺漏 |
| 3 | 枚举核对 | `RiskLevel` 恰为 LOW / MEDIUM / HIGH / REJECT_SUGGEST 四值，与 v2 分级路由的档位一一对应 |

**失败排查**：①嵌套 record 编译失败→漏了内部 `RiskFactor` 的定义或分号；②后续 v3/v4/v5 消费方字段取不到→擅自改动了字段名，破坏契约。

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class PreTrialService {

    @Autowired
    private ChatClient chatClient;
    // 注：v1 时点容器内只有一个 ChatClient Bean，此注入无歧义。
    // v5 引入双模型（modelAClient/modelBClient）后，本字段须改为
    // @Autowired @Qualifier("modelAClient") ChatClient chatClient 精确注入（见 05 篇 §3.3 改名提示），
    // 否则启动报 ChatClient 多 Bean 歧义。

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
                            .entity(PreTrialOpinion.class);   // 结构化输出（教程 01-WebFlux与响应式编程/03-Sinks详解 §2）
                    return validate(opinion);
                })
                .subscribeOn(Schedulers.boundedElastic());  // 阻塞调用桥接（教程 01-WebFlux与响应式编程/00-WebFlux从零入门 §5.5）
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

> **API 真实性标注**：`entity(Class)` 是 Spring AI 2.0.0 真实重载（`CallResponseSpec.entity(Class<T>)`）；`entity(Class, spec -> ...)` 同样是**真实重载**——`EntityParamSpec` 真实方法仅 `useProviderStructuredOutput()` 与 `validateSchema()` 两个（javap 实证，见 [附录 05-SpringAI2-API基准/02-Tool与Observation真实API §2]），不存在 `maxAttempts()`。本篇用最简 `entity(Class)`：对 DeepSeek（仅支持 `json_object` 一档）与 spec 变体效果差别不大（[教程 01-WebFlux与响应式编程/03-Sinks详解 §4.3]）。阻塞调用用 `Mono.fromCallable(...).subscribeOn(boundedElastic)` 桥接——`chatClient.call()` 直接跑在 Netty 线程会阻塞 EventLoop（[教程 01-WebFlux与响应式编程/00-WebFlux从零入门 §5.5]）。

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
package com.bank.risk.controller;

import com.bank.risk.domain.PreTrialOpinion;
import com.bank.risk.service.PreTrialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/pretrial")
public class PreTrialController {

    @Autowired
    private PreTrialService preTrialService;

    @PostMapping
    public Mono<PreTrialOpinion> pretrial(
            @RequestParam String applicationId,
            @RequestBody String materialText) {
        return preTrialService.preTrial(applicationId, materialText);
    }
}
```

### 3.5 校验层（不能信任 LLM 输出的格式）

**分层信任**：`entity(Class)` 只保证 JSON 能被反序列化，不保证数值语义正确。置信度越界、`HIGH` 等级却没有 `high` 风险因子支撑，都是业务校验层必须拦住的（[教程 01-WebFlux与响应式编程/03-Sinks详解 §5]）。上述 `PreTrialService#validate` 中的两层校验——格式由框架保证（entity），语义由业务保证（validate）——缺一不可。

### 3.6 最容易写错的三个姿势（对照）

| # | 错误姿势 | 后果 | 正确姿势 |
|---|---------|------|---------|
| 1 | `chatClient.call()` 直接在 Netty 线程跑 | EventLoop 阻塞，全线卡死 | `Mono.fromCallable(...).subscribeOn(boundedElastic)` |
| 2 | `entity(PreTrialOpinion.class, spec -> spec.maxAttempts(3))` | `maxAttempts()` 不是 `EntityParamSpec` 的真实方法，编译不过——自动重试框架不内置（[教程 01-WebFlux与响应式编程/03-Sinks详解 §5]） | `entity(PreTrialOpinion.class)`，或真实重载 `entity(Class, spec -> spec.useProviderStructuredOutput().validateSchema())`（[附录 05-SpringAI2-API基准/02-Tool与Observation真实API §2]） |
| 3 | 拿到 `entity()` 结果不校验直接用 | confidence 越界、HIGH 无 high 因子支撑仍被消费 | 业务层 `validate(...)` 兜底 |

### 3.7 本节测试与验证（ChatClient / 预审服务 / WebFlux 入口）

**前置条件**：§2.2 已通过；`DEEPSEEK_API_KEY` 已 export。

**材料——单元断言（validate 语义校验）**：

```java
// OpinionValidationException 触发样本（类名 PreTrialServiceValidationTest，写入 src/test/java，手写）：
// ① opinion.confidence() = 1.5  → 期望抛 "confidence 越界"
// ② riskLevel=HIGH 且 riskFactors 全为 medium → 期望抛 "HIGH 等级必须至少有一个 high 风险因子支撑"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 手写 3.1–3.4 四个类后 `mvn clean compile` | `BUILD SUCCESS` |
| 2 | 材料① ② 的校验单测 `mvn test -Dtest=PreTrialServiceValidationTest` | 两个样本均按预期抛 `OpinionValidationException`，消息与 validate 中文案一致 |
| 3 | 核对 §3.6 三个错误姿势 | 本项目代码均采用"正确姿势"列：boundedElastic 桥接 / 无 `maxAttempts()` / validate 兜底 |

**失败排查**：①`maxAttempts()` 编译不过→用的是臆造 API，回查 §3.6 第 2 行；②单测不抛异常→validate 未被调用（entity 之后漏了 return validate(opinion)）；③启动报 ChatClient bean 冲突→00 篇未定义其他 ChatClient bean，检查是否重复定义。

## 4. 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run -Dspring-boot.run.profiles=bankrisk
# 测试（端口 8081，见 00 篇两段式配置）：
# curl -X POST "http://localhost:8081/api/pretrial?applicationId=SO-0001" \
#   -H "Content-Type: text/plain" -d "营业执照: 广州XX贸易有限公司，注册资本500万元，经营范围为纺织服装批发，成立满3年。流水: 公账月均回款 50 万元，年末 3 个月骤降至不足10万元。征信: 无逾期记录，负债率约45%，近期有1笔经营贷申请。"
```

### 4.1 本节测试与验证（端到端预审请求）

**前置条件**：应用已启动（`mvn spring-boot:run -Dspring-boot.run.profiles=bankrisk`，8081 端口）。

**材料——curl 探针**：

```bash
curl -X POST "http://localhost:8081/api/pretrial?applicationId=SO-0001" \
  -H "Content-Type: text/plain" \
  -d "营业执照: 广州XX贸易有限公司，注册资本500万元，经营范围为纺织服装批发，成立满3年。流水: 公账月均回款 50 万元，年末 3 个月骤降至不足10万元。征信: 无逾期记录，负债率约45%，近期有1笔经营贷申请。"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 材料 curl | HTTP 200；响应体为 JSON，含 §2.1 契约的 6 个字段 |
| 2 | 字段语义 | `confidence` ∈ [0,1]；年末流水骤降被识别为风险因子且 `evidence` 引用材料原文 |
| 3 | 无越权表述 | `summaryReason` / `riskLevel` 中无"批准/拒绝"的终审决定字样（只有建议等级） |
| 4 | 再发一份只有营业执照、无流水无征信的材料 | 对应维度进 `missingMaterials`，不臆造 riskFactor |

**失败排查**：①404→`@RequestMapping("/api/pretrial")` 或 `@PostMapping` 路径写错；②entity 解析异常→DeepSeek 返回非 JSON（System Prompt 约束被材料文本淹没，可重试）；③EventLoop 告警/线程卡死→漏了 `subscribeOn(Schedulers.boundedElastic())`。

## 5. 验收对照

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 结构化成功率 | 100 份样本材料，JSON 解析 + 语义校验通过率 ≥ 95% |
| 2 | 证据可回溯 | 抽检风险因子 evidence 字段能在材料原文中定位 |
| 3 | 缺失归位 | 材料缺失的维度进 missingMaterials，不臆造风险因子 |
| 4 | 无终审越权 | 输出中无"批准/拒绝"最终决定表述（只有建议等级） |

> 本节即 §4.1 验证在 100 份样本上的规模化执行：每条验收项都有 §4.1 对应断言可复现（结构化成功率=断言 1/2、证据回溯=断言 2、缺失归位=断言 4、无越权=断言 3），本表不再单列操作步骤。

### 5.1 本迭代的 ADR（v1 不新增编号，指向 12 总账）

v1 的关键取舍已在 00 篇预录 ADR 中定型，本迭代是它们的**首次代码落地**，不新开编号（保持 12 篇总账 101-136 连续闭合）：

| 关联 ADR | 在 v1 的落点 |
|----------|-------------|
| ADR-104（结构化结论比对） | 输出契约 `PreTrialOpinion` schema 先行（§2）——v5 字段级比对与 v4 回放全部消费此契约 |
| ADR-101（终审人工） | System Prompt 规则 3「只做预审建议」（§3.1）+ §4.1 断言 3 的无越权核验 |

完整总账见 [12-ADR架构决策记录.md](12-ADR架构决策记录.md)。

## 6. v1 的痛点

> 本节核对（一句话）：两个痛点分别指向 02（置信度分级）与 03（人工审批），与 00 篇演进路线 v2/v3 一致即 PASS。

跑通一周后，两类问题浮现：

1. **置信度是"自由心证"**——LLM 报 0.9 的建议照样出错，报 0.5 的有时很准。预审意见缺少**可操作的分级机制**：什么级别的建议可以进快速通道、什么级别必须转人工？——v2 解决
2. **没有人工环节**——信贷员直接拿 HIGH 建议当结论用，监管红线（终审必须人工）形同虚设——v3 的核心痛点

## 7. 总结

| 概念 | 一句话 |
|------|--------|
| 输出契约 | 先定义 PreTrialOpinion schema 再写代码——schema 是风控系统的 API |
| 结构化输出 | `entity(Class)` 与 `entity(Class, spec -> ...)` 均真实重载（spec 仅 `useProviderStructuredOutput()`/`validateSchema()`，javap 实证） |
| 阻塞桥接 | `Mono.fromCallable(...).subscribeOn(boundedElastic)`，绝不在 EventLoop 上 block |
| 分层校验 | 框架管格式，业务管语义——两层都要 |

> 本节核对（一句话）：四行总结与 §2/§3/§3.5/§3.6 的口径一一对应即 PASS。

## 8. 全篇回归验证

**前置条件**：§2.2 / §3.7 / §4.1 各节验证均通过。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `mvn clean test` | 全部单测（含 §3.7 校验样本）通过，`BUILD SUCCESS` |
| 2 | §4.1 材料 curl 连发 3 次 | 三次均 200 且契约字段完整——最小闭环稳定（跨请求一致性） |
| 3 | 缺失材料样本 + 完整材料样本混合发 | missingMaterials 归位与风险因子识别互不串扰（跨样本回归） |

**失败排查**：①间歇性 JSON 解析失败→DeepSeek 偶发非 JSON 输出，属 §5 验收项 1 的成功率统计范围，必要时收紧 System Prompt；②连发后线程数异常增长→确认 boundedElastic 桥接未丢。

→ [02-置信度与风险分级.md](02-置信度与风险分级.md)
