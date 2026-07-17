# 25 AI 工程的 SRE 实践

> LLM 系统的 SRE 比传统 Web 难一倍：延迟分布厚尾、输出不确定、依赖第三方 API、prompt 改动会破坏行为。本文给出一套可落地的 SRE 框架。
>
> 前置：[`./15-可观测性与成本治理.md`](./15-可观测性与成本治理.md)
> 预计：2 天

---

## 0. 认知地图

```
传统 SRE 四本柱（容量 / 变更 / 预算 / Runbook）
    + LLM 特有的三本柱（行为 / 成本 / 模型漂移）
    ↓
七本柱：容量 / 变更 / 行为 / 成本 / 模型漂移 / 安全 / Runbook
```

---

## 1. SLO 与 SLI

### 1.1 传统 SLO 不够用

| 传统 SLI | LLM 场景问题 |
|---------|------------|
| 请求成功率 200 = OK | LLM 200 但答错也算失败 |
| p99 延迟 | LLM 延迟分布厚尾，p99 比 p50 大 10 倍 |
| 错误率 < 0.1% | LLM 拒答 / 模型限流不算"错误"但用户体验差 |

### 1.2 LLM 专属 SLI

| SLI | 计算 | 目标 |
|-----|------|------|
| **answer_correctness** | LLM-as-judge 给答案打分 | > 0.85 |
| **faithfulness** | 答案是否忠于 retrieval 源 | > 0.9 |
| **hallucination_rate** | 关键事实错误的比例 | < 5% |
| **tool_call_success_rate** | 工具调用成功率 | > 99% |
| **token_cost_per_session** | 单会话 token 成本 | < $0.05 |
| **time_to_first_token (TTFT)** | 首 token 延迟 | p95 < 1.5s |
| **time_per_token (TPOT)** | 流式单 token 间隔 | p95 < 50ms |
| **upstream_rate_limit** | 上游 LLM 限流比例 | < 1% |

### 1.3 SLI → SLO → 错误预算

```
SLO: answer_correctness >= 0.85（30 天滚动窗口）
错误预算: (1 - 0.85) × 30 天 = 4.5 天可"不达标"
用完错误预算 → 冻结所有 prompt / 模型变更，只允许修复
```

---

## 2. 容量规划

### 2.1 LLM 容量的特殊性

- **依赖上游**：你的容量 = 上游 LLM 给你的配额 × 缓存命中率
- **延迟是排队**：单请求耗时长（5-30s），并发瓶颈不在 CPU 而在"在途请求数"
- **token 即金钱**：高负载 = 高成本，容量规划 = 成本规划

### 2.2 容量公式

```
峰值 QPS = (活跃用户数 × 单用户 QPS) / 业务时间窗系数
↑                                    ↑
                                    工作时间集中度（B2B 0.3，B2C 0.1）

需要并发数 = 峰值 QPS × 平均请求时长（秒）
上游配额 = 峰值 QPS × 平均 token 数 × 缓存未命中率
```

例：

```
峰值 1000 QPS × 平均 8 秒 = 8000 并发
峰值 1000 QPS × 2000 token × 0.7（缓存未命中）= 1.4M token/s
```

### 2.3 三道闸门

```java
// 本代码仅作学习材料参考
@Component
@RequiredArgsConstructor
public class LlmGateway {
    private final Bulkhead bulkhead;     // 并发上限
    private final RateLimiter limiter;   // QPS 上限
    private final BudgetGuard budget;    // 日预算上限

    public String call(Prompt p) {
        if (!budget.allow()) throw new BudgetExhausted();
        return limiter.execute(() ->
                bulkhead.execute(() ->
                        client.prompt(p).call().content()));
    }
}
```

### 2.4 弹性扩缩容

- **应用层**：K8s HPA 按 QPS / 并发数扩。
- **上游配额**：提前 1 周向 OpenAI / Anthropic 申请提额，否则扩了也白发。
- **本地推理**：用 vLLM 自建推理集群做"溢出"，参考 [`./16-多模型路由与国产化.md`](./16-多模型路由与国产化.md)。

---

## 3. 变更管理

### 3.1 三种变更的不同风险

| 变更类型 | 风险 | 频率 |
|---------|------|------|
| 应用代码 | 传统 bug，可单测覆盖 | 每周 |
| Prompt | 行为漂移，测试覆盖难 | 每周-每月 |
| 模型版本 | 行为大变，回归测试必须 | 每季度 |

### 3.2 Prompt 变更的三道门

```
PR 提交
  ↓
[门 1：离线 eval]   跑 eval 集，对比 baseline，要求不退步
  ↓
[门 2：影子流量]   线上 1% 流量双跑（老 prompt + 新 prompt），人工 spot check
  ↓
[门 3：渐进发布]   10% → 50% → 100%，每阶段观察 SLO
```

### 3.3 模型升级的回归测试

OpenAI / Anthropic 升级 model snapshot 会改行为。每次升级前：

1. 跑完整 eval 集（[`./12-评估闭环与Prompt版本管理.md`](./12-评估闭环与Prompt版本管理.md)）。
2. 对比 top-N 失败 case，看是新模型 bug 还是新行为。
3. 灰度 1 周，监控 SLO。
4. 全量后保留旧版本 fallback 1 个月。

### 3.4 Feature Flag

```java
// 本代码仅作学习材料参考
String promptVersion = featureFlag.evaluate("prompt.customer_service.v2")
        ? "v2" : "v1";
```

flag 不只是开关，更是回滚旋钮。

---

## 4. 行为可观测性

### 4.1 OTel GenAI Semantic Conventions

OpenTelemetry 2024 推出 GenAI 标准 attribute：

- `gen_ai.system`：openai / anthropic / ...
- `gen_ai.request.model`
- `gen_ai.usage.prompt_tokens` / `completion_tokens`
- `gen_ai.response.finish_reason`
- `gen_ai.tool.name` / `gen_ai.tool.call.id`

Spring AI 2.0 自动埋点（Micrometer + OTel），见 [`./15-可观测性与成本治理.md`](./15-可观测性与成本治理.md)。

### 4.2 三层 trace

```
[Span: handle_user_request]
  ├── [Span: chat_client.call]
  │     ├── [Span: advisor.memory.before]
  │     ├── [Span: advisor.rag.retrieve]
  │     │     ├── [Span: vector_store.search]
  │     │     └── [Span: rerank]
  │     ├── [Span: chat_model.invoke (openai)]
  │     ├── [Span: advisor.tool.loop]
  │     │     └── [Span: tool.weather_api]
  │     └── [Span: advisor.memory.after]
```

每个 span 都带 GenAI attributes，可以在 Jaeger / Tempo 里直接筛选。

### 4.3 Langfuse / Phoenix

OTel 解决"调用链"，但 LLM 还需要"prompt 血缘 / eval 关联"。Langfuse 自托管是主流：

- 每次 prompt 改动关联 eval run
- 用户反馈（thumbs up/down）回流
- A/B 实验对比

---

## 5. 成本治理

### 5.1 成本组成

```
总成本 = (input_tokens × price_in) + (output_tokens × price_out) + (embedding_tokens × price_emb)
        + 自托管 GPU 折旧 + 网络出口 + 存储向量
```

### 5.2 七个优化手段

| 手段 | 收益 | 风险 |
|------|------|------|
| Prompt Cache（OpenAI / Anthropic） | input -90% | cache miss 反而 +25% |
| Context 压缩（摘要历史） | -30~50% input | 信息丢失 |
| Streaming + early stop | -20% output | UX 受影响 |
| 模型降级（复杂→简单路由） | -50~70% | 简单模型办不了复杂事 |
| Batch API（异步） | -50% | 延迟变分钟级 |
| Embedding 量化（INT8） | -75% 存储 | recall 掉点 |
| Tool 结果缓存 | 工具调用 -80% | 数据陈旧 |

详见 [`./15-可观测性与成本治理.md`](./15-可观测性与成本治理.md) §Prompt Cache。

### 5.3 预算告警

```java
// 本代码仅作学习材料参考
@Component
public class BudgetAlert {
    @Scheduled(every = "1m")
    public void check() {
        double today = costStore.todayTotal();
        double budget = config.getDailyBudget();
        if (today > budget * 0.8) {
            pagerDuty.warn("LLM cost at 80% of daily budget: $" + today);
        }
        if (today > budget) {
            featureFlag.disable("non_essential_features");
            pagerDuty.critical("Daily budget exceeded");
        }
    }
}
```

---

## 6. 模型漂移检测

### 6.1 漂移的来源

- **上游模型升级**（OpenAI snapshot）
- **输入分布漂移**（用户问题风格变化）
- **依赖工具变化**（被调用的 API 改了返回）
- **prompt 缓存失效**

### 6.2 检测方法

**A. 输出分布监控**

```python
# 每日统计 answer_length 分布、token 分布、refusal 率
yesterday = stats("2026-07-16")
today = stats("2026-07-17")
kl = kl_divergence(yesterday, today)
if kl > 0.1:
    alert("output distribution drift")
```

**B. 固定 eval 集回归**

每周对固定 100 条 eval 跑一次，看分数变化。

**C. 用户反馈率**

用户 thumbs down 率突增 = 漂移信号。

### 6.3 漂移响应

```
检测到漂移 →
  ├── 自动回滚 prompt 版本（如果当天有变更）
  ├── 上游模型 fallback 到旧版本
  └── 触发复盘：是新行为还是 bug？
```

---

## 7. Runbook 模板

每个高发故障都要有 runbook。

### 7.1 上游 LLM 限流（429）

```
症状：上游 429 频率突增
影响：用户请求超时
排查：
  1. grafana 看 upstream_rate_limit 指标
  2. 检查是否到达 quota（OpenAI / Anthropic 后台）
  3. 是否某 client 异常高频
处置：
  1. 临时切到 fallback provider
  2. 申请临时提额
  3. 必要时启动自托管推理
```

### 7.2 答案质量下降

```
症状：用户 thumbs down 率突增
影响：体验差
排查：
  1. Langfuse 看最近 prompt 变更
  2. 比对最近 eval 分数
  3. 抽样 50 条 spot check
处置：
  1. 回滚最近 prompt 变更
  2. 通知产品方
  3. 写复盘 ADR
```

### 7.3 工具调用失败

```
症状：tool_call_success_rate 下降
影响：Agent 卡住
排查：
  1. trace 里看具体失败的 tool
  2. 检查该工具的上游 API
  3. 检查 tool 的 input schema 是否被改
处置：
  1. 临时禁用故障工具（FeatureFlag）
  2. LLM 自动 fallback 到"我暂时无法做 X"
```

### 7.4 成本超预算

见 §5.3。

### 7.5 Prompt Injection 攻击

见 [`./14-安全工程与红队.md`](./14-安全工程与红队.md)。

---

## 8. On-call 实践

### 8.1 PagerDuty 分级

| 严重度 | 触发 | 响应 |
|-------|------|------|
| P0 | 服务全挂 / 数据泄漏 | 立即唤醒所有人 |
| P1 | SLO 严重不达标 / 成本失控 | 5 分钟响应 |
| P2 | 单一功能降级 | 工作时间响应 |
| P3 | 监控告警但无影响 | 工作时间 batch 处理 |

### 8.2 事后复盘（Postmortem）

模板（Google SRE 经典）：

```markdown
# Postmortem: 2026-07-15 客服 Agent 拒答率激增

## Impact
- 持续 47 分钟
- 影响用户约 1.2 万
- 用户报错率 30%（正常 2%）

## Root Cause
- Anthropic API snapshot 升级（claude-sonnet-4-5-20250929 → 20251101）
- 新 snapshot 对 "I cannot help with..." 触发条件变严
- 我们的 prompt 里 "refund" 被 classifier 误判为 "sensitive"

## Timeline (UTC)
14:00 - Anthropic 部署
14:13 - 第一个告警
14:20 - on-call 确认
14:35 - 定位
14:50 - 回滚到旧 snapshot
15:00 - 恢复

## What went well
- Langfuse trace 让定位 < 5 分钟
- 自动 fallback 切到 GPT 缓解了一半流量

## What went wrong
- 没有 snapshot 变更的自动通知
- prompt 依赖了未文档化的 classifier 行为

## Action Items
- [ ] 订阅 Anthropic changelog 自动告警（owner: 张三, due: 2026-07-24）
- [ ] 把 classifier 依赖显式化（owner: 李四, due: 2026-08-01）
- [ ] 增加 "refusal_rate" SLI 告警（owner: 王五, due: 2026-07-22）
```

**核心原则**：blameless（不指责个人），focus on system。

---

## 9. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| 把 LLM 错误率混进应用错误率 | 告警风暴 | 单独 LLM dashboard |
| 用 200 = 成功 | 答错也算成功 | 加 answer_correctness SLI |
| 不限制单用户预算 | 一个用户拖垮全公司 | per-user budget cap |
| Prompt 改完直接全量 | 不可逆错误 | 灰度 + shadow traffic |
| 模型升级当天无监控 | 静默漂移 | 升级窗口 24h 加强监控 |
| Runbook 写完不演练 | 真出事抓瞎 | 季度 chaos day |
| 把错误归到个人 | 隐瞒文化 | blameless postmortem |

---

## 10. 实战任务

1. 为你的 Agent 定义 5 个 SLI，每个有计算公式和告警阈值。
2. 写一个成本看板，按用户 / 模型 / prompt 拆分。
3. 模拟 Anthropic snapshot 升级，演练一次 prompt 回滚。
4. 写一个 Runbook：上游 LLM 限流，包含 fallback 决策树。
5. 搭 Langfuse 自托管，把项目所有 LLM 调用 trace 接入。
6. （进阶）实现一个 Chaos Day：人为注入"上游 429 / 数据库慢 / prompt drift"，看团队反应。
7. （选做）写一个 drift detector，每日对固定 eval 集跑回归，分降自动告警。

---

## 11. 理解检查

1. LLM 的 SLI 和传统 Web 的 SLI 有什么本质差别？
2. 七个成本优化手段的优先级？
3. 模型漂移有哪四种来源？分别怎么检测？
4. Prompt 变更的三道门是什么？为什么不能跳过 shadow traffic？
5. Postmortem 的核心原则是什么？为什么？
6. 容量规划时为什么并发瓶颈不在 CPU 而在"在途请求数"？

---

## 12. 相关文档

- [`./12-评估闭环与Prompt版本管理.md`](./12-评估闭环与Prompt版本管理.md) —— eval 闭环
- [`./14-安全工程与红队.md`](./14-安全工程与红队.md) —— 安全 + 防失控
- [`./15-可观测性与成本治理.md`](./15-可观测性与成本治理.md) —— 观测栈
- [`./16-多模型路由与国产化.md`](./16-多模型路由与国产化.md) —— fallback 策略
- [`./27-CICD-for-AI.md`](./27-CICD-for-AI.md) —— CI/CD 配套
- [Google SRE Book](https://sre.google/sre-book/table-of-contents/)
- [OTel GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- [Langfuse](https://langfuse.com/)
- [Arize Phoenix](https://phoenix.arize.com/)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
