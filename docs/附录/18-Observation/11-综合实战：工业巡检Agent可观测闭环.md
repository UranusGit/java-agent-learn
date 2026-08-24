# 11 综合实战：工业巡检 Agent 的可观测闭环

> **定位**：收尾总装配。把 00-10 的全部零件组装成一个贴近你工业目标的完整作品：**多工具巡检 Agent + 流式输出 + RAG 知识库 + 四条观测出口 + 审计归档 + 前端时间线**，并给出向生产演进的架构路线（管控分离、多实例、合规留存）。本篇代码是"简约实现 + 工业级架构"的最终呈现。
>
> **前置阅读**：[附录 18-Observation/00]~[10] 全部。

---

## 11.1 业务设定：一条巡检指令的一生

> "巡检 CNC-001 和 AGV-07，判断是否异常，异常则建维修工单，最后给班组长一段总结。"

Agent 需要：两个查询工具 + 一个工单工具 + 两轮以上 LLM 推理。每一步都要"看得见、查得到、算得清"。

## 11.2 完整架构（本系列成果全景）

```mermaid
graph TB
    subgraph 接入层["接入层（Data Plane）"]
        HTTP["WebFlux<br/>/inspect /observe/stream /events"]
    end
    subgraph Agent层["Agent 运行时"]
        CC["ChatClient<br/>多工具 + RAG Advisor"]
        T0["getCurrentTime"]
        T1["queryDeviceStatus"]
        T2["createWorkOrder"]
        CC --> T0 & T1 & T2
    end
    subgraph 观测管线["Observation 管线（本系列）"]
        R["ObservationRegistry"]
        R --> H1["TextPublisher"]
        R --> H2["AgentEventCollector<br/>→ SSE 前端时间线"]
        R --> H3["TokenCostHandler<br/>→ MeterRegistry/actuator指标"]
        R --> H4["TracingHandler<br/>→ traceId/Zipkin"]
        R --> F["SensitiveToolFilter<br/>脱敏"]
        R --> CV["IndustrialToolConvention<br/>line.id 标签"]
    end
    subgraph 治理层["治理层（Control Plane 方向）"]
        G["指标治理<br/>/actuator/metrics<br/>(生产再接Prometheus)"]
        Z["Zipkin<br/>链路检索"]
        A["审计归档<br/>(traceId+事件落存储)"]
    end
    HTTP --> CC
    H2 --> FE["React 大屏<br/>EventSource"]
    H3 --> G
    H4 --> Z
    H2 --> A
```

管控分离视角：左侧接入/Agent 属 Data Plane（高频执行），底部治理属 Control Plane（低频决策：采样率、脱敏规则、SLO 阈值、成本预算）——观测管线正是两者之间天然的"数据面"，这也是为什么它值得从最小 demo 就按对的架构搭（呼应 [教程 20-管控分离架构]）。

## 11.3 代码总装（本篇两处增量，其余复用 00-10 累积工程）

相对 09 关的 v5，本篇只做两处增强：**① `/inspect` 加巡检系统提示**（Agent 的行为契约写明阈值与建单规则，多轮工具由 ChatClient 自动驱动）；**② 新增审计归档 Handler**。流式接口、RAG Advisor、观测管线（Collector/Convention/Filter/TokenCost/ToolLatency/RAG Handler）全部原样复用。

**① `InspectionController` 终版（完整文件 v6）**：

```java
// src/main/java/demo/demo01/controller/InspectionController.java（终版 v6）
package demo.demo01.controller;

import demo.demo01.obs.AgentEvent;
import demo.demo01.obs.AgentEventCollector;
import demo.demo01.tools.DeviceTools;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/demo01")
public class InspectionController {

    private static final String SYSTEM_PROMPT = """
            你是工厂设备巡检助手。规则：
            1. 查询工具返回 JSON 指标；温度>75 或振动>4 判定异常。
            2. 结论需要时间戳时必须先调用 getCurrentTime 工具，禁止自己编造时间。
            3. 设备维修建议优先参考知识库检索到的手册内容。
            """;

    private final ChatClient chatClient;
    private final AgentEventCollector eventCollector;
    private final ObservationRegistry registry;

    public InspectionController(ChatModel chatModel, DeviceTools deviceTools,
                                AgentEventCollector eventCollector,
                                ObservationRegistry registry,
                                VectorStore vectorStore) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultTools(deviceTools)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .topK(3)
                                .similarityThreshold(0.5)
                                .build())
                        .build())
                .build();
        this.eventCollector = eventCollector;
        this.registry = registry;
    }

    @GetMapping("/inspect")
    public String inspect(@RequestParam String prompt) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .call()
                .content();
    }

    @GetMapping("/events")
    public List<AgentEvent> events() {
        return eventCollector.drain();
    }

    @GetMapping(value = "/observe/stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<AgentEvent>> observeStream() {
        return eventCollector.stream()
                .map(e -> ServerSentEvent.<AgentEvent>builder(e).event("agent-event").build());
    }

    @GetMapping(value = "/inspect/stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<String>> inspectStream(@RequestParam String prompt) {
        String reqId = String.valueOf(System.nanoTime());
        return doStream(prompt)
                .doOnCancel(() -> Observation
                        .createNotStarted("agent.stream.cancelled", Observation.Context::new, registry)
                        .highCardinalityKeyValue("stream.req", reqId)
                        .observe(() -> { }))
                .doOnError(e -> Observation
                        .createNotStarted("agent.stream.error", Observation.Context::new, registry)
                        .lowCardinalityKeyValue("phase", "stream")
                        .error(e)
                        .observe(() -> { }));
    }

    private Flux<ServerSentEvent<String>> doStream(String prompt) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(prompt)
                .stream()
                .content()
                .map(token -> ServerSentEvent.<String>builder(token).event("delta").build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder("[完成]").event("done").build()));
    }
}
```

**② 审计归档 Handler（完整文件）**：

```java
// src/main/java/demo/demo01/obs/AuditArchiveHandler.java（完整文件）
package demo.demo01.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

@Component
public class AuditArchiveHandler implements ObservationHandler<ToolCallingObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveHandler.class);

    private final Tracer tracer;

    public AuditArchiveHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean supportsContext(Observation.Context ctx) {
        return ctx instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStop(ToolCallingObservationContext ctx) {
        // demo：结构化日志即审计。生产：换 MQ/JDBC，带 traceId 落库（06 关 §6.6 决策 3）
        Span span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : "no-trace";
        log.info("AUDIT traceId={} tool={} args={} ok={}",
                traceId,
                ctx.getToolDefinition().name(),
                ctx.getToolCallArguments(),
                ctx.getError() == null);
    }
}
```

审计与事件流的区别：事件流给**实时看**（会过期、可截断），审计给**事后查**（不丢、带责任字段）——同一观测源、两种 SLA，所以分成两个 Handler 而不是一个带开关的。

**③ HITL 触发点（预留）**：工单工具里对"高成本操作"插入确认。完整 HITL 落点是 `ToolCallingManager`/`ToolCallback` 包装层（非 Advisor，CLAUDE.md 铁律），本篇不展开——观测已把"哪次调用该审批"的信号（工具名+参数+traceId）备齐，[教程 28-Human-in-the-Loop与审批流] 是正篇。

## 11.4 一次巡检的完整可观测旅程（结果预期）

```mermaid
sequenceDiagram
    participant U as 班组长(浏览器/Postman)
    participant S as 巡检Agent服务
    participant L as LLM
    participant FE as 观测大屏(SSE)
    U->>S: /inspect 巡检CNC-001和AGV-07
    S->>FE: CHAT_CLIENT/LLM事件(traceId=T)
    S->>L: 第1次推理(决策查两台设备)
    S->>FE: TOOL事件×2(参数+脱敏结果)
    S->>L: 第2次推理(判定CNC异常)
    S->>FE: TOOL事件(建工单WO-x)
    S->>L: 第3次推理(总结)
    S-->>U: 巡检结论
    Note over S,FE: 同 traceId 贯穿:前端时间线/日志/Zipkin/审计/指标
```

## 11.5 Postman 综合测试用例（验收清单）

| # | 用例 | 操作 | 验收现象（全部命中才算闭环） |
|---|---|---|---|
| 1 | 多工具巡检 | `GET /demo01/inspect?prompt=巡检CNC-001和AGV-07，异常的建维修工单` | 结论含两设备评估 + 1 张工单号 |
| 2 | 前端时间线 | 先订阅 `GET /demo01/observe/stream` 再发用例 1 | SSE 按序收到 ≥7 条事件（CHAT_CLIENT+3×LLM+2×查询TOOL+1×工单TOOL），同一 traceId |
| 3 | 链路完整性 | 用例 1 的 traceId 去查 | console 日志行、审计 AUDIT 行、Zipkin span 树三处同一 traceId |
| 4 | 脱敏合规 | 检查一切出服务的内容（SSE/日志） | `temp` 原值只在内存，出口处均为 `***` |
| 5 | 成本计量 | 调用前后各查一次 `GET /actuator/metrics/agent.token.cost` | `measurements` 的 TOTAL 增量 ≈ 3 次 LLM 调用 token 之和；`availableTags` 含 `type` 维度 |
| 6 | 错误韧性 | 临时让工单工具抛异常再调 | 结论降级为"工单创建失败"；事件流出现 ERROR；指标照常；服务不崩 |
| 7 | 断线重连 | 中途断开 SSE 再重连 | replay 补发近期事件后继续实时 |

## 11.6 从 demo 到生产：演进路线（ADR 风格）

| 演进 | 驱动需求 | 方案 | 取舍理由 |
|---|---|---|---|
| V1（本篇）单实例全内存 | 学习/POC | buffer + Sinks + 结构化日志审计 | 零外部依赖，架构契约已对齐生产 |
| V2 多实例 | 高可用 | 事件走 Redis Pub/Sub 聚合；审计走 MQ；traceId 落工单表 | Handler 不改，只换"广播通道"实现 |
| V3 合规留存 | 审计法规 | 事件+trace 全量入对象存储/时序库，保留期按合规（6 个月~2 年） | 观测数据本身成为合规证据链 |
| V4 治理闭环 | 成本/质量 | 接入 Prometheus+Grafana（只加 registry 依赖，业务代码零改动）；采样率与脱敏规则收敛到 Control Plane 配置中心；告警回驱动（如 token 超预算自动降级模型） | 呼应 [教程 27-成本治理]、[教程 41-数据飞轮] |

## 11.7 系列总结：你带走了什么

| 能力 | 关卡 |
|---|---|
| 框架观测点全景 + 最小 console 闭环 | 00 |
| span 树/生命周期/基数纪律 | 01 |
| 五组件协作与扩展点选型 | 02 |
| 自定义 Handler 收集事件流 | 03 |
| Convention 注工业标签、Filter 脱敏 | 04 |
| SSE 推前端时间线（断线重连） | 05 |
| traceId 全链路贯穿 + 日志定位 | 06 |
| Token 计量、SLO、基数熔断（零安装） | 07 |
| 流式响应观测（span 闭合/中断/部分结果） | 08 |
| Advisor 与 RAG 检索质量观测 | 09 |
| 观测代码测试 + 跨服务 trace 传播 | 10 |
| 总装 + 审计 + 生产演进 ADR（本篇） | 11 |

**给架构师的一句话**：Observation 的本质是"一源多消费者的观测总线"。你在 demo 里用十来个类搭出的管线，与生产十万 QPS 系统的差异只在消费者实现与存储介质——契约（Context/事件 DTO/低高基数纪律）从第一天就是同一套。这就是"代码简约、架构工业级"的完整含义。

> 交叉引用：[教程 22-全链路可观测性]、[教程 23-工具执行可观测与审计]、[教程 27-成本治理与Token计量]、[教程 41-数据飞轮与持续改进]
