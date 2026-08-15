# 附录 12-02：Tool、结构化输出与 Observation 真实 API 基准

> **定位**：本文是对 [教程 03-工具调用]、[教程 12-结构化输出]、[教程 16-全链路可观测性] 的深入展开，也是全体系的**工具/输出/可观测 API 真实性基准**：注解体系（`@Tool` 而非 `@ToolMethod`）、`entity()` 真实重载、ToolContext、Observation 真实扩展点。前置阅读：[教程 03]、[教程 12]、[教程 16]。

---

## 1. 工具注解体系

### 1.1 正确注解（基准）

```java
// Spring AI 2.0.0
public class WeatherTools {

    @Tool(name = "getWeather",                       // 可省略（默认方法名）
          description = "查询指定城市的当前天气。")     // 必写——LLM 唯一依据
    public Weather getWeather(
            @ToolParam(description = "城市名，如 Beijing") String city,
            ToolContext context) {                    // 可选: 框架注入的调用上下文
        String tenantId = context.getContext().get("tenant_id");   // 传递租户/会话等
        return weatherApi.fetch(city);
    }
}
```

**审计发现的虚构注解**：`@ToolMethod`（教程 40 旧稿通篇）**不存在**——只有 `@Tool`（方法级）与 `@ToolParam`（参数级）。`returnDirect` 是 `@Tool` 的属性而非独立注解。

### 1.2 ToolCallback 手工构建（真实形态）

```java
// 真实 API —— ToolCallback.builder()（审计发现教程 03 旧稿的 toolFunction(...) 链不存在）
ToolCallback cb = ToolCallback.builder()
        .toolDefinition(ToolDefinition.builder()
                .name("queryOrder")
                .description("按订单号查询订单")
                .inputSchema(jsonSchema)                 // JSON Schema 字符串
                .build())
        .toolMethod(reflectiveMethod)                    // 或 .toolFunction(fn)——以版本为准，二选一
        .build();
```

### 1.3 工具执行的拦截/观测扩展点（层次分清）

| 扩展点 | 能拦什么 | 不能拦什么 |
|--------|---------|-----------|
| `ToolCallingManager`（装饰/替换 Bean） | 工具执行前后（**HITL 的正确落点**） | ChatModel 内部循环 |
| `ToolCallback` 包装 | 单工具执行前后 | 批量语义 |
| Spring AOP `@Around("@annotation(Tool)")` | **理论拦截，实际收不到**——框架反射调用绕过代理 | （审计：项目 02 旧稿的方案无效） |
| Observation（框架原生） | Span/指标 | 业务逻辑 |

> HITL 审批要拦"工具意图已定、执行未发生"——`ToolCallingManager` 是唯一稳定层（[教程 22 §正确落点]、[项目 06 v3] 完整落地）。

## 2. 结构化输出：entity() 真实重载

```java
// Spring AI 2.0.0 —— entity() 只有两种常用形态（+带提示的可选变体）
record Person(String name, int age) {}

// 形态 1: Class
Person p = chatClient.prompt().user("...").call().entity(Person.class);

// 形态 2: ParameterizedTypeReference（泛型容器）
List<Person> list = chatClient.prompt().user("...")
        .call().entity(new ParameterizedTypeReference<List<Person>>() {});
```

**审计发现的虚构形态**：`entity(Person.class, spec -> spec.useProviderStructuredOutput().validateSchema())`（教程 02/12 旧稿）、`maxAttempts()` 自动重试参数、`StructuredOutputConversionException` 异常类型——**均不存在**。Provider 原生结构化输出与自动重试的正确姿势：按所引版本的 ChatOptions 结构化输出开关配置 + 业务层自实现重试（`retryWhen`）。

## 3. Observation：真实 API

### 3.1 真实扩展点

```java
// Spring AI 2.0 / Micrometer —— 真实 API
// ① 配置开关（真实键，随版本核对——新旧键名曾变更，以引入版本文档为准）
spring.ai.chat.observations.include-prompt-content=false

// ② 自定义 ObservationContext 增强（真实接口）
public class ToolAuditObservationHandler implements ObservationHandler<ToolObservationContext> {
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolObservationContext;    // 框架真实类型
    }
    @Override
    public void onStop(ToolObservationContext ctx) {
        // 真实取值路径: ctx 上的字段（如 toolDefinition/toolCall），不是 context.getTraceId()
        audit.write(ctx.getToolDefinition().name(), ctx.getDuration());
    }
}

// ③ @Observed 注解（Micrometer 真实注解——属性是 key-value 对，无 lowCardinalityKeyValue 单值属性）
@Observed(name = "tool.execute", contextualName = "tool-exec")
public Result execute(...) { ... }
```

**审计发现的虚构项**：`ObservationContextHolder.get()`、`Observation.Context#getKeyValue(String)`、`ObservationHandlerGrouping`、`@Observed(lowCardinalityKeyValue = {...})` 单值属性、`context.getTraceId()`/`getSpanId()`——均非真实 API。TraceId 的真实获取：`tracer.currentSpan().context().traceId()`（Micrometer Tracing 的 `Tracer` Bean）。

### 3.2 与 javaagent 的配合纪律

> OTel javaagent（`-javaagent` 挂载）与手写 `SdkTracerProvider` **二选一**——同用会双重注册（审计：教程 16 旧稿的冲突）。规则：用 javaagent 就删手写 Provider；需要代码级 Span 控制才手写 SDK 且不挂 agent。

## 4. SearchRequest / Document（新旧 API 分水岭）

```java
// 2.0 式（基准——教程 29/30/34 已用，附录 03/04/07 旧稿需统一）
List<Document> hits = vectorStore.similaritySearch(
        SearchRequest.builder().query(q).topK(5).similarityThreshold(0.7).build());

// ❌ 旧式（1.0 前，已废弃）: SearchRequest.query(q).topK(5).similarityThreshold(...)
// ❌ 旧构造器: Document.builder().text(text).metadata(Map.of(...).build()) → Document.builder().text(...).metadata(...).build()
// ❌ 类型名: VectorSearch → VectorStore
```

## 5. 全局替换规则汇总

| 从（虚构/旧） | 到（基准） |
|--------------|-----------|
| `@ToolMethod` | `@Tool` |
| `entity(Class, spec -> ...)` 全家族 | `entity(Class)` / `entity(TypeReference)` |
| AOP 拦 `@Tool` | ToolCallingManager 装饰器 / Observation |
| `ObservationContextHolder` / `getKeyValue` / `getTraceId` | `Tracer.currentSpan()` 真实链路 |
| `SearchRequest.query().withTopK()` | `SearchRequest.builder()` |
| `new Document(text, map)` | `Document.builder()` |
| `VectorSearch` | `VectorStore` |
| 不确定 | 标注「概念代码，真实 API 见 [附录 12]」 |

## 6. 总结

| 概念 | 一句话 |
|------|--------|
| 注解 | `@Tool` + `@ToolParam`，无 `@ToolMethod` |
| 拦截 | ToolCallingManager 是工具执行层的唯一稳定拦截点 |
| entity | 两种真实形态，spec-lambda 全家族为虚构 |
| Observation | ObservationHandler<ToolObservationContext> + Tracer 真实链路 |
| 检索 | SearchRequest.builder() 新式统一 |
| 纪律 | 存疑必标注「概念代码」，javaagent 与手写 SDK 二选一 |
