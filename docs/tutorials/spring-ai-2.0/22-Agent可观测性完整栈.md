# L3 Agent 可观测性完整栈（Spring AI 2.0）

> 本文回答：**Agent 应用该监控什么？出了问题怎么定位？传统 APM（SkyWalking / Pinpoint）够用吗？**
>
> Agent 应用比传统应用难调试 10 倍：一次请求触发 N 次 LLM 调用 + M 次工具调用 + RAG 检索 + Memory 读写……必须专门的 LLM Observability 栈。
>
> 前置：[`./14-评估闭环与Prompt版本管理.md`](./14-评估闭环与Prompt版本管理.md) + [`./19-AI原生系统设计.md`](./19-AI原生系统设计.md)
> 预计：2 天

---

## 0. 认知地图

```
传统 APM：Trace + Metrics + Logs（够用）
    +
Agent 新增：
    ├── Prompt 血缘（哪个 prompt 版本，渲染后是什么）
    ├── 模型血缘（用了哪个模型，参数是什么）
    ├── 工具调用血缘（调了什么工具，参数 + 结果）
    ├── RAG 检索血缘（查到了什么 chunk，相似度多少）
    ├── 质量指标（faithfulness、用户反馈率）
    └── Drift 检测（指标随时间漂移）
```

---

## 1. 传统 APM 不够用的地方

### 1.1 一次请求的实际工作量

```
用户问："我上个月订单为什么没发货"
    ↓
1. Router Agent 判断 → 调用 LLM 1（GPT-4o-mini）
2. 命中 Order Agent → 调用 LLM 2（Claude Sonnet）
3. RAG 检索：向量查询 → 命中 5 个 chunk
4. LLM 2 决定调工具：getOrderHistory
5. 工具执行：查 DB → 返回订单列表
6. LLM 2 决定调工具：getShippingStatus(orderId=...)
7. 工具执行：调物流 API → 返回状态
8. LLM 2 综合生成最终回复
9. 用户看到答案
```

**9 步、3 次 LLM 调用、2 次工具调用、1 次向量检索**。出问题（如答案不准）时，每一步都可能是凶手。

### 1.2 传统 Trace 的局限

- 看不到 prompt 内容（"调了 LLM，参数是 Prompt{...}"不解决问题）。
- 看不到工具参数和返回。
- 没有 token / cost 统计。
- 没有 faithfulness 等质量指标。
- 没有 LLM 输入和输出的 diff 对比。

### 1.3 LLM Observability 的新维度

| 维度 | 含义 |
|------|------|
| **Prompt 血缘** | 哪个 prompt 模板版本？渲染后实际是什么？ |
| **Model 血缘** | 哪个 provider / model / temperature？ |
| **Token 统计** | input / output / cached tokens / cost |
| **Tool trace** | 工具名、参数、返回、耗时、错误 |
| **Retrieval trace** | query、命中的 chunk、相似度分数 |
| **质量指标** | faithfulness、relevance、用户反馈 |
| **Drift** | 指标随时间的漂移 |

---

## 2. 三柱 + 一柱：Trace / Metrics / Logs + Eval

### 2.1 三柱（传统）

| 柱 | 工具 | Agent 场景的扩展 |
|----|------|----------------|
| Trace | OpenTelemetry | 加 LLM 语义约定（gen_ai.*） |
| Metrics | Prometheus | 加 token / cost / faithfulness 指标 |
| Logs | Loki / ELK | 加结构化 prompt / response 日志 |

### 2.2 第四柱：Eval

评估数据本身是可观测的一部分：

- 实时 LLM-as-Judge 打分 → 时间序列。
- 用户反馈率 → 时间序列。
- 错误模式聚类 → 趋势。

---

## 3. Spring AI 的内置可观测性

### 3.1 Spring AI 自带 OTel 集成

加依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-actuator-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

配置：

```yaml
spring:
  ai:
    chat:
      client:
        observations:
          include-prompt: true      # 默认 false，开启后能看到完整 prompt
          include-completion: true  # 同上，看到完整响应
management:
  tracing:
    sampling:
      probability: 1.0   # 100% 采样（生产环境 5-10%）
  otlp:
    tracing:
      endpoint: http://otel-collector:4317
```

### 3.2 自动的 Span

Spring AI 自动给这些操作打 span：

- `ChatClient.prompt` - 客户端调用
- `ChatModel.call` - 模型调用
- `ChatModel.stream` - 流式调用
- `VectorStore.similaritySearch` - 向量查询
- `ChatMemory.add` / `ChatMemory.get` - Memory 操作

每个 span 带 attributes：

```
gen_ai.operation.name = "chat"
gen_ai.system = "openai"
gen_ai.request.model = "gpt-4o"
gen_ai.response.model = "gpt-4o-2024-08-06"
gen_ai.usage.prompt_tokens = 234
gen_ai.usage.completion_tokens = 56
gen_ai.usage.total_tokens = 290
gen_ai.prompt = "..."      # 开启 include-prompt 才有
gen_ai.completion = "..."  # 开启 include-completion 才有
```

### 3.3 自定义 Span

```java
@WithSpan("agent.tool.execute")
public ToolResult executeTool(
        @SpanAttribute("tool.name") String name,
        @SpanAttribute("tool.arguments") String args) {
    // ...
}
```

---

## 4. Prompt 血缘

### 4.1 问题

业务方报告："昨天 14:00 之后的回答质量明显下降"。

- 是 Prompt 改了吗？（git blame）
- 是模型切换了吗？（部署历史）
- 是 RAG 检索质量下降了吗？（向量库重建）
- 是 Memory 出问题了吗？（数据迁移）

### 4.2 解决：每个 LLM 调用记录血缘

```java
// org.demo02.observability.PromptLineageAdvisor
// 本代码仅作学习材料参考

public class PromptLineageAdvisor implements BaseAdvisor {

    private final LineageRepository lineageRepo;

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        LineageRecord record = new LineageRecord(
                UUID.randomUUID().toString(),
                (String) req.context().get("sessionId"),
                Instant.now(),
                promptVersionProvider.current(),   // prompt 模板版本
                req.prompt().getSystemMessage().getText(),
                req.prompt().getUserMessage().getText(),
                req.toolNames(),
                null,   // 后续填
                null,
                null
        );
        req.context().put("__lineage_id__", record.id());
        lineageRepo.save(record);
        return req;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        String id = (String) resp.context().get("__lineage_id__");
        ChatResponse chatResp = resp.chatResponse();
        
        lineageRepo.update(id, 
                chatResp.getMetadata().getModel(),
                chatResp.getMetadata().getUsage().getTotalTokens(),
                chatResp.getText());
        return resp;
    }

    @Override public String getName() { return "PromptLineageAdvisor"; }
    @Override public int getOrder() { return Integer.MIN_VALUE + 60; }
}
```

### 4.3 血缘记录示例

```json
{
  "id": "lin-abc123",
  "session_id": "sess-xyz",
  "timestamp": "2026-07-17T10:30:15Z",
  "prompt_version": "v2.1.0",
  "prompt_template_hash": "sha256:...",
  "system_prompt": "你是客服...",
  "user_prompt": "我的订单为什么没发货",
  "tools_available": ["getOrderHistory", "getShippingStatus"],
  "model_used": "claude-sonnet-4-5",
  "temperature": 0.7,
  "total_tokens": 890,
  "response": "您的订单 #12345 当前状态是...",
  "duration_ms": 2300
}
```

---

## 5. 模型血缘

### 5.1 模型切换的隐性影响

同一份 prompt，不同模型行为差异大：

- temperature 0 在 GPT-4 和 Claude 上不一样"确定"。
- tool calling 格式不同（OpenAI function calling vs Anthropic tool use）。
- context window 不同（GPT-4o 128k vs Claude 200k）。

### 5.2 记录每次调用的模型元数据

```java
// org.demo02.observability.ModelLineage
// 本代码仅作学习材料参考

public record ModelLineage(
    String callId,
    String sessionId,
    String provider,         // openai / anthropic / deepseek
    String model,            // claude-sonnet-4-5
    String apiVersion,
    Map<String, Object> params,   // temperature / topP / maxTokens
    String routingReason,   // 为什么选这个模型（complex / fallback / cache）
    int inputTokens,
    int outputTokens,
    BigDecimal costUsd,
    long latencyMs,
    String errorMessage
) {}
```

### 5.3 模型变更的灰度

模型切换不要全量。先灰度：

```java
public ChatModel route(String query) {
    int bucket = Math.floorMod(query.hashCode(), 100);
    if (bucket < 5) {   // 5% 流量走新模型
        metrics.counter("model.exposure", "model", "claude-sonnet-4-6").increment();
        return newModel;
    }
    return oldModel;
}
```

---

## 6. 工具调用血缘

### 6.1 记录

```java
@Aspect
@Component
public class ToolCallTracer {

    private final ToolCallTraceRepository repo;
    private final Tracer tracer;

    @Around("@annotation(tool)")
    public Object trace(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
        Span span = tracer.nextSpan().name("tool." + pjp.getSignature().getName()).start();
        String toolName = pjp.getSignature().getName();
        String args = serialize(pjp.getArgs());
        long start = System.currentTimeMillis();

        try {
            Object result = pjp.proceed();
            long duration = System.currentTimeMillis() - start;
            
            ToolCallRecord record = new ToolCallRecord(
                    tracer.currentSpan().context().traceId(),
                    toolName,
                    tool.description(),
                    args,
                    serialize(result),
                    duration,
                    "success",
                    null
            );
            repo.save(record);
            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - start;
            repo.save(new ToolCallRecord(
                    tracer.currentSpan().context().traceId(),
                    toolName, tool.description(),
                    args, null, duration, "error", e.getMessage()));
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 6.2 用途

- 调试：用户报告"退款失败"，查 tool call trace，看到 `submitRefund` 失败原因。
- 性能：哪个工具最慢？P95 多少？
- 滥用：哪个用户调 `deleteUser` 太频繁？
- 评估：哪个工具经常被调但结果被 LLM 忽略？

---

## 7. RAG 检索血缘

### 7.1 记录命中的 chunk

```java
public class RetrievalTracingRetriever implements DocumentRetriever {

    private final DocumentRetriever delegate;
    private final RetrievalTraceRepository traceRepo;

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> docs = delegate.retrieve(query);
        
        // 记录每次检索
        RetrievalTrace trace = new RetrievalTrace(
                UUID.randomUUID().toString(),
                sessionId(),
                query.text(),
                docs.stream().map(d -> Map.entry(
                        d.getId(),
                        Map.of("content_preview", truncate(d.getText(), 100),
                                "score", d.getMetadata().get("score"),
                                "source", d.getMetadata().get("source"))
                )).collect(Collectors.toList()),
                Instant.now()
        );
        traceRepo.save(trace);
        
        return docs;
    }
}
```

### 7.2 检索质量监控

每天跑：

- **空检索率**：query 没命中任何 chunk 的比例（> 5% 说明知识库有缺口）。
- **低分检索率**：top1 相似度 < 0.5 的比例。
- **chunk 点击率**：哪些 chunk 经常被 LLM 用（说明有价值），哪些从不被引用（说明是噪声）。

---

## 8. Drift Detection（漂移检测）

### 8.1 什么是 Drift

**指标随时间慢慢恶化但没人发现**。比如：

- faithfulness 从 0.85 慢慢降到 0.78。
- 用户 👎 率从 5% 涨到 8%。
- 平均 token 数从 800 涨到 1200。

这种慢漂移单日看不出，长期累积就是质量崩塌。

### 8.2 检测方法

```java
// org.demo02.observability.DriftDetector
// 本代码仅作学习材料参考

@Component
public class DriftDetector {

    public DriftReport detect() {
        DriftReport report = new DriftReport();
        
        // 比较：过去 7 天 vs 之前 30 天
        checkMetricDrift(report, "faithfulness", 7, 30, -0.03);
        checkMetricDrift(report, "thumb_up_rate", 7, 30, -0.02);
        checkMetricDrift(report, "p95_latency_ms", 7, 30, 500.0);
        checkMetricDrift(report, "avg_tokens", 7, 30, 100.0);
        
        return report;
    }

    private void checkMetricDrift(DriftReport report, String metric,
                                    int recentDays, int baselineDays, double threshold) {
        double recent = avg(metric, recentDays);
        double baseline = avg(metric, baselineDays);
        double delta = recent - baseline;
        
        if (Math.abs(delta) > Math.abs(threshold)) {
            report.addAlert(metric, baseline, recent, delta, threshold);
        }
    }
}
```

### 8.3 Drift 的常见原因

- **Prompt 被改坏**（无回归测试）。
- **新文档入库污染了知识库**。
- **模型供应商悄悄改了模型**（确实会发生）。
- **用户 query 分布变化**（业务进入新场景）。

---

## 9. Toxicity 与安全监控

### 9.1 实时检测 LLM 输出的毒性

```java
public class ToxicityCheckAdvisor implements BaseAdvisor {

    private final ChatClient judge;

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        String output = resp.chatResponse().getText();
        
        ToxicityResult result = judge.prompt()
                .system("""
                    判断文本是否包含：
                    - 仇恨言论
                    - 歧视性内容
                    - 暴力威胁
                    - 性暗示
                    输出 JSON：{"safe": true/false, "categories": [...], "score": 0.0~1.0}
                    """)
                .user(output)
                .call()
                .entity(ToxicityResult.class);
        
        if (!result.safe() && result.score() > 0.7) {
            alertService.notify("Toxic output detected: " + output);
            // 替换为安全回复
            return overrideWithSafe(resp);
        }
        return resp;
    }
}
```

### 9.2 Prompt Injection 实时检测

见 [`./17-安全工程与红队.md`](./17-安全工程与红队.md)，所有疑似注入的请求都要告警 + 计数。

---

## 10. 自建 vs 用现成平台

### 10.1 现成的 LLM Observability 平台

| 平台 | 类型 | 特点 |
|------|------|------|
| **Langfuse**（开源自托管） | 开源 | 业界标杆，Java SDK 支持 |
| **Phoenix**（Arize） | 开源 | trace + eval 一体 |
| **LangSmith**（LangChain） | 商业 SaaS | LangChain 生态深度 |
| **Helicone** | 商业 | Proxy 模式，零代码 |
| **Datadog LLM** | 商业 | 已用 Datadog 直接加 |

### 10.2 自建的代价

| 自建 | 用 Langfuse |
|------|-------------|
| 完全可控 | 几天就能上线 |
| 定制度高 | 标准化，社区活跃 |
| 维护成本高 | 零维护 |
| 要写 SDK | 提供多语言 SDK |

**推荐**：起步阶段直接用 Langfuse（自托管版），后期特殊需求再自建。

### 10.3 集成 Langfuse

加依赖：

```xml
<dependency>
    <groupId>co.langfuse</groupId>
    <artifactId>langfuse-java</artifactId>
    <version>0.0.8</version>
</dependency>
```

```yaml
langfuse:
  host: http://langfuse:3000
  public-key: pk-lf-...
  secret-key: sk-lf-...
```

```java
@Component
public class LangfuseAdvisor implements BaseAdvisor {

    private final LangfuseClient client;

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        Trace trace = client.trace(req.context().get("sessionId").toString())
                .name("chat")
                .userId(req.context().get("userId").toString())
                .metadata(req.context())
                .create();
        req.context().put("__langfuse_trace_id__", trace.getId());
        return req;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        // 上报 generation
        client.generation(traceId(resp))
                .name("llm")
                .model(model(resp))
                .input(promptText(resp))
                .output(responseText(resp))
                .usage(usage(resp))
                .create();
        return resp;
    }
}
```

Langfuse Web UI 直接看 trace 树、prompt diff、token 用量、用户反馈。

---

## 11. Grafana 看板的关键面板

### 11.1 系统层

- **QPS / 错误率 / P50/P95 延迟**：基本健康度。
- **各模型调用量堆叠图**：路由分布。
- **Token 消耗趋势**：日累计 + 同比上周。
- **Cost 趋势**：日累计 + 单租户分摊。

### 11.2 质量层

- **Faithfulness 时间序列**：均值 + P5。
- **Relevance 时间序列**。
- **用户 👍/👎 率**：每日比例。
- **Drift 检测面板**：关键指标的 7 天 vs 30 天对比。

### 11.3 业务层

- **每日活跃会话数**。
- **会话长度分布**。
- **热门 query Top 20**。
- **失败 query 聚类**：相似失败 case 自动归类。

---

## 12. 告警

### 12.1 告警分级

| 级别 | 触发条件 | 通知方式 |
|------|---------|---------|
| P0 紧急 | 错误率 > 10%、主 LLM 全挂 | 电话 + 短信 + IM |
| P1 严重 | P95 > 10s、faithfulness < 0.7 | 短信 + IM |
| P2 警告 | Cost 超日预算 80%、Drift 触发 | IM |
| P3 提示 | 异常流量、单租户异常 | IM 群 |

### 12.2 告警去噪

- **聚合**：同一错误 5 分钟内只告警一次。
- **抑制**：P0 告警存在时，P2/P3 静默。
- **认领**：告警发出去 5 分钟没人认领升级。

---

## 13. 实战避坑

### 13.1 "采样率太低漏掉了关键 trace"

**症状**：用户报问题，trace 找不到。

**解决**：

- 关键路径（支付、退款、敏感操作）100% 采样。
- 普通路径 5-10% 采样。
- 错误请求强制保留 trace（错误率分母）。

### 13.2 "Prompt 内容泄漏到日志"

**风险**：日志含敏感信息（用户 PII、密钥）。

**解决**：

- 写日志前 PII 脱敏。
- 敏感字段（如 system prompt）单独存受限访问的表。
- 日志保留期合规。

### 13.3 "Trace 太深看不懂"

**症状**：一次请求 50+ span，找不到关键路径。

**解决**：

- 用 OTel 的 span kind（CLIENT / SERVER / INTERNAL）。
- 自定义业务标签（如 `agent.name`、`tool.name`）。
- Langfuse 这类工具自动做 trace 树渲染。

### 13.4 "Eval 数据和 Metrics 分裂"

**症状**：评估系统和监控系统各算各的，对不上。

**解决**：

- 统一指标定义（如 faithfulness 计算逻辑只写一份）。
- 评估系统的输出反哺监控（每天离线评估的指标推到 Prometheus）。

### 13.5 "Drift 检测误报"

**症状**：业务方说"今天就是有个大客户开会问的多，不是真的 drift"。

**解决**：

- Drift 检测做归因（按租户 / 用户群分）。
- 排除已知业务周期（如月底订单查询高峰）。

---

## 14. 实战任务

1. 集成 Spring AI Actuator + OTel，把 trace 上报给 Jaeger / Zipkin。
2. 配置 `include-prompt: true`，在 trace 中看到完整 prompt。
3. 实现 `PromptLineageAdvisor`，每个 LLM 调用记录 prompt 版本 + 内容。
4. 实现 `ToolCallTracer`（AOP），所有工具调用落 trace。
5. 实现 `RetrievalTracingRetriever`，记录每次 RAG 命中的 chunk。
6. 实现 Drift 检测脚本，每天比较近 7 天 vs 之前 30 天的关键指标。
7. 部署 Langfuse（自托管 Docker），把所有 trace 上报，看 Web UI。
8. （进阶）实现 Toxicity 实时检测 + 告警。
9. （选做）搭一套 Grafana 看板，覆盖 §11 的所有面板。

---

## 15. 理解检查

1. 传统 APM 在 LLM 应用上有什么不足？
2. Prompt 血缘要记录哪些字段？
3. Spring AI 自带的 OTel 集成需要哪些 attributes？
4. Drift Detection 怎么实现？常见原因有哪些？
5. 自建 Observability vs 用 Langfuse，怎么选？
6. 关键告警的分级标准是什么？

---

## 16. 进 L3 下一篇之前的能力确认

完成本篇你应该能：

- [ ] 集成 Spring AI Actuator + OTel 看到 LLM trace
- [ ] 记录 Prompt / Model / Tool / Retrieval 完整血缘
- [ ] 实现 Drift Detection
- [ ] 区分系统层、质量层、业务层的监控指标
- [ ] 配置分级告警
- [ ] 评估自建 vs 用 Langfuse 的取舍

---

## 17. 相关文档

- [`./14-评估闭环与Prompt版本管理.md`](./14-评估闭环与Prompt版本管理.md) —— 评估与监控的结合
- [`./19-AI原生系统设计.md`](./19-AI原生系统设计.md) —— Event Sourcing 与血缘
- [`./17-安全工程与红队.md`](./17-安全工程与红队.md) —— Toxicity 监控
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- [Langfuse 文档](https://langfuse.com/docs)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
