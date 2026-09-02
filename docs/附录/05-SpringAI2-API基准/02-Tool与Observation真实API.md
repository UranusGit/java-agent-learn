# 附录 05-02：Tool、结构化输出与 Observation 真实 API 基准

> **定位**：本文是对 [教程 00-基础与核心/03-工具调用]、[教程 02-SpringAI核心机制/04-结构化输出]、[教程 04-企业级架构主干/02-全链路可观测性] 的深入展开，也是全体系的**工具/输出/可观测 API 真实性基准**：注解体系（`@Tool` 而非 `@ToolMethod`）、`entity()` 真实重载、ToolContext、Observation 真实扩展点。前置阅读：[教程 00-基础与核心/03-工具调用]、[教程 02-SpringAI核心机制/02-Agent状态管理]、[教程 03-React前端与AgenticUI/01-React状态管理]。

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

**审计发现的虚构注解**：`@ToolMethod`（教程 05-Observation可观测/07-指标治理：Token计量、SLO与基数熔断 旧稿通篇）**不存在**——只有 `@Tool`（方法级）与 `@ToolParam`（参数级）。`returnDirect` 是 `@Tool` 的属性而非独立注解。

### 1.2 ToolCallback 手工构建（真实形态）

```java
// 真实 API（javap 实证：ToolCallback 是接口、无 builder()；手工构建走两个具体实现）
// ① 方法引用方式（推荐，与 @Tool 注解解析同构）
ToolCallback cb = MethodToolCallback.builder()
        .toolDefinition(ToolDefinition.builder()
                .name("queryOrder")
                .description("按订单号查询订单")
                .inputSchema(jsonSchema)                 // JSON Schema 字符串
                .build())
        .toolMethod(reflectiveMethod)                    // java.lang.reflect.Method
        .toolObject(toolInstance)                        // 方法所属实例
        .build();

// ② 函数式方式
ToolCallback cb2 = FunctionToolCallback.builder("queryOrder",
        (Map<String, Object> args) -> orderService.query(args))
        .description("按订单号查询订单")
        .build();

// ToolDefinition 接口真实方法：name()/description()/inputSchema()，静态 builder()
// ❌ 虚构：ToolCallback.builder()、.toolFunction(fn) 链（教程 00-基础与核心/03-工具调用 旧稿）
```

### 1.3 工具执行的拦截/观测扩展点（层次分清）

| 扩展点 | 能拦什么 | 不能拦什么 |
|--------|---------|-----------|
| `ToolCallingManager`（装饰/替换 Bean） | 工具执行前后（**HITL 的正确落点**） | ChatModel 内部循环 |
| `ToolCallback` 包装 | 单工具执行前后 | 批量语义 |
| Spring AOP `@Around("@annotation(Tool)")` | **理论拦截，实际收不到**——框架反射调用绕过代理 | （审计：项目 02 旧稿的方案无效） |
| Observation（框架原生） | Span/指标 | 业务逻辑 |

> HITL 审批要拦"工具意图已定、执行未发生"——`ToolCallingManager` 是唯一稳定层（[教程 02-SpringAI核心机制/04-结构化输出 §正确落点]、[项目 06 v3] 完整落地）。

## 2. 结构化输出：entity() 真实重载

```java
// Spring AI 2.0.0 —— entity() 全部重载（javap 实证 CallResponseSpec）
record Person(String name, int age) {}

// 形态 1: Class（+ 带 EntityParamSpec 的可选变体）
Person p = chatClient.prompt().user("...").call().entity(Person.class);
// 形态 1b: Class + spec —— 真实 API（EntityParamSpec 有两个方法）
Person p2 = chatClient.prompt().user("...")
        .call().entity(Person.class, spec -> spec.useProviderStructuredOutput().validateSchema());

// 形态 2: ParameterizedTypeReference（泛型容器）
List<Person> list = chatClient.prompt().user("...")
        .call().entity(new ParameterizedTypeReference<List<Person>>() {});

// 形态 3: StructuredOutputConverter<T>
Person p3 = chatClient.prompt().user("...")
        .call().entity(new BeanOutputConverter<>(Person.class));
```

**javap 实证**（`ChatClient$EntityParamSpec`）：真实方法只有 **`useProviderStructuredOutput()`** 与 **`validateSchema()`** 两个——`entity(Class, spec -> ...)` 是**真实 API**（此前审计误判为虚构）。`maxAttempts()` 自动重试参数**不在 EntityParamSpec 上**；`StructuredOutputConversionException` 异常类型**在所有本地 2.0.0 jar 中均不存在**。自动重试需业务层自实现（`retryWhen`）。

## 3. Observation：真实 API

### 3.1 真实扩展点

```java
// Spring AI 2.0 / Micrometer —— 真实 API
// ① 配置开关（javap 实证：ChatObservationProperties，前缀 spring.ai.chat.observations）
//    真实属性键只有三个：log-completion / log-prompt / include-error-logging
spring.ai.chat.observations.log-prompt=true
// ❌ 虚构键：include-prompt-content（本地 2.0.0 不存在）

// ② 自定义 ObservationContext 增强（真实接口，javap 实证）
//    真实类型名：org.springframework.ai.tool.observation.ToolCallingObservationContext
public class ToolAuditObservationHandler implements ObservationHandler<ToolCallingObservationContext> {
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;    // 框架真实类型
    }
    @Override
    public void onStop(ToolCallingObservationContext ctx) {
        // 真实取值路径: ctx.getToolDefinition()/getToolCallId()/getToolCallArguments()/getToolCallResult()
        // ❌ 无 getDuration()（Observation.Context 无此方法；时长由 Handler/Timer 记录）
        audit.write(ctx.getToolDefinition().name(), ctx.getToolCallArguments());
    }
}

// ③ @Observed 注解（Micrometer 真实注解——属性是 key-value 对，无 lowCardinalityKeyValue 单值属性）
@Observed(name = "tool.execute", contextualName = "tool-exec")
public Result execute(String toolName, Map<String, Object> args) {
    // 真实方法体:执行工具并返回结果(此处省略具体工具逻辑,保留可编译骨架)
    return new Result(toolName, args);
}
```

**审计发现的虚构项**：`ObservationContextHolder.get()`、`Observation.Context#getKeyValue(String)`、`ObservationHandlerGrouping`、`@Observed(lowCardinalityKeyValue = {...})` 单值属性、`context.getTraceId()`/`getSpanId()`——均非真实 API。TraceId 的真实获取：`tracer.currentSpan().context().traceId()`（Micrometer Tracing 的 `Tracer` Bean）。

### 3.2 与 javaagent 的配合纪律

> OTel javaagent（`-javaagent` 挂载）与手写 `SdkTracerProvider` **二选一**——同用会双重注册（审计：教程 01-WebFlux与响应式编程/06-线程模型与调度器 旧稿的冲突）。规则：用 javaagent 就删手写 Provider；需要代码级 Span 控制才手写 SDK 且不挂 agent。

## 4. SearchRequest / Document（新旧 API 分水岭）

```java
// 2.0 式（基准——教程 00-基础与核心/04-记忆与会话管理 §30/34 已用，附录 03/04/07 旧稿需统一）
List<Document> hits = vectorStore.similaritySearch(
        SearchRequest.builder().query(q).topK(5).similarityThreshold(0.7).build());

// ❌ 旧式（1.0 前，已废弃）: SearchRequest.query(q).topK(5).similarityThreshold(...)
// ❌ 旧构造器: Document.builder().text(text).metadata(Map.of(...).build()) → Document.builder().text(...).metadata(...).build()
// ❌ 类型名: VectorSearch → VectorStore
```

**TokenTextSplitter 同属新旧分水岭**：包路径 `org.springframework.ai.transformer.splitter.TokenTextSplitter`（1.x 老路径 `org.springframework.ai.transformer.TokenTextSplitter` 已不存在）；全部 5 个构造器自 2.0.0-M3 起 `@Deprecated(forRemoval=true)`，唯一基准是静态 `TokenTextSplitter.builder()`（来源：[spring-ai#6347](https://github.com/spring-projects/spring-ai/issues/6347)、[官方 Javadoc](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/transformer/splitter/TokenTextSplitter.html)）：

```java
// 2.0 式（基准——教程 00-基础与核心/05-RAG检索增强生成、项目 00-智能客服、附录 01-Embedding 已统一）
TokenTextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(800)            // 每个 chunk 的目标 token 数
        .withMinChunkSizeChars(100)    // chunk 内段落最小字符数
        .withMinChunkLengthToEmbed(5)  // 短于该长度的 chunk 跳过嵌入
        .withMaxNumChunks(10000)       // 单文档最大 chunk 数
        .withKeepSeparator(true)       // 切分时保留分隔符
        .build();

// ❌ 旧式（2.0.0-M3 起 forRemoval 弃用）: new TokenTextSplitter(800, 100, 5, 10000, true)
//    旧 5 参顺序: chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator——与 with* 方法一一对应
//    （2.0.0 终版全参构造器实为 6 参，多一个 punctuationMarks: List<Character>，Builder 对应 withPunctuationMarks）
```

**DocumentReader / MarkdownDocumentReader 同属分水岭**：读取接口 `org.springframework.ai.document.DocumentReader extends Supplier<List<Document>>`——基准读取方法是 `get()`（Supplier 语义，这正是 ETL Reader 段的设计）；接口另有一个 `default read()` 别名（内部委托 `get()`），但**不存在** `read(Resource)` 带参形态。`MarkdownDocumentReader` 无无参构造、无单参 `(Resource)` 构造，真实构造器只有 4 个（javap 实证 `spring-ai-markdown-document-reader-2.0.0.jar`）：`(String)` / `(String, MarkdownDocumentReaderConfig)` / `(Resource, MarkdownDocumentReaderConfig)` / `(List<Resource>, MarkdownDocumentReaderConfig)`：

```java
// 2.0 式（基准——教程 00-基础与核心/05-RAG检索增强生成、项目 00-智能客服已统一）
List<Document> mdDocs = new MarkdownDocumentReader("classpath:docs/guide.md").get();

// Resource 形态必须显式传 Config——没有单参 (Resource) 构造器
MarkdownDocumentReader reader = new MarkdownDocumentReader(
        new ClassPathResource("manual/产品手册.md"),
        MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)  // 「---」分隔线处拆成独立文档
                .withIncludeCodeBlock(false)             // 是否保留代码块
                .withIncludeBlockquote(true)             // 是否保留引用块
                .build());
List<Document> docs = reader.get();   // DocumentReader 是 Supplier<List<Document>>——读取用 get()

// ❌ 虚构: new MarkdownDocumentReader() 无参构造；reader.read(resource) 带参 read()——均不存在
```

## 5. 全局替换规则汇总

| 从（虚构/旧） | 到（基准） |
|--------------|-----------|
| `@ToolMethod` | `@Tool` |
| `entity(Class)` / `entity(TypeReference)` | 真实重载含 `entity(Class, Consumer<EntityParamSpec>)`（`useProviderStructuredOutput()`/`validateSchema()`，javap 实证） |
| AOP 拦 `@Tool` | ToolCallingManager 装饰器 / Observation |
| `ObservationContextHolder` / `getKeyValue` / `getTraceId` | `Tracer.currentSpan()` 真实链路 |
| `SearchRequest.query().withTopK()` | `SearchRequest.builder()` |
| `new Document(text, map)` | `Document.builder()` |
| `VectorSearch` | `VectorStore` |
| `new TokenTextSplitter(...)` 全部构造器 | `TokenTextSplitter.builder()`（with* 方法与旧参数逐位对应） |
| `new MarkdownDocumentReader()` 无参 + `reader.read(resource)` | `(String)` / `(Resource, Config)` 构造器 + `reader.get()` 读取 |
| 不确定 | 标注「概念代码，真实 API 见 [附录 05]」 |

## 6. 总结

| 概念 | 一句话 |
|------|--------|
| 注解 | `@Tool` + `@ToolParam`，无 `@ToolMethod` |
| 拦截 | ToolCallingManager 是工具执行层的唯一稳定拦截点 |
| entity | 真实重载：Class / TypeReference / StructuredOutputConverter ×（可选 Consumer<EntityParamSpec>），spec 方法仅 useProviderStructuredOutput()+validateSchema() |
| Observation | ObservationHandler<ToolObservationContext> + Tracer 真实链路 |
| 检索/分块 | SearchRequest.builder() / TokenTextSplitter.builder() 新式统一 |
| Reader | `DocumentReader` 是 `Supplier<List<Document>>`，读取用 `get()`；资源在构造器传入，无 `read(Resource)` |
| 纪律 | 存疑必标注「概念代码」，javaagent 与手写 SDK 二选一 |
