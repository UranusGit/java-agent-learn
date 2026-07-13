# Spring AI 08 - 升级到 Spring AI 2.0

> 目标：把仓库从 Spring AI 1.0.0 + Spring Boot 3.5.10 升级到 Spring AI 2.0.0 + Spring Boot 4.x。
> 前置：已完成 [01-07](./01-快速起步.md)，对 Spring AI 1.0 有完整理解。
>
> 调研日期：2026-07-13。Spring AI 2.0.0 GA 于 2026-06-12 发布。

---

## 0. 为什么要升级

### 0.1 升级的 5 个核心理由

| 能力 | 1.0 | 2.0 | 影响 |
|------|-----|-----|------|
| **Tool Calling 循环** | 手动管理 | `ToolCallingAdvisor` 自动注册 + 递归迭代 | **不再需要手写 AgentLoop** |
| **结构化输出校验** | `BeanOutputConverter` | + `StructuredOutputValidationAdvisor`（校验+自动重试） | LLM 输出格式错误自动重试 |
| **MCP 集成** | client only | MCP Java SDK 2.0，client + server，`@McpTool` | 暴露 MCP Server 是 Spring AI 独占 |
| **会话持久化** | `MessageWindowChatMemory` | `spring-ai-session`（event-sourced，可重放） | 会话可重放、可恢复 |
| **可观测性** | Micrometer | + OTel GenAI Semantic Conventions | 跨框架统一观测 |

### 0.2 不升级的代价

- 阶段 4 自研的 `AgentLoop` 永远是技术债
- 无法消费 MCP Server 生态（招聘市场上"懂 MCP"加分）
- 错过 spring-ai-session 的 event-sourced 会话设计

---

## 1. 升级前的准备

### 1.1 创建升级分支

```bash
git checkout -b upgrade/spring-ai-2.0
```

### 1.2 全量回归测试基线

升级前先跑通所有功能，建立基线：
```bash
mvn clean test
# 记录所有通过的测试，作为升级后回归对比
```

### 1.3 备份关键文件

```bash
git tag v1.0-baseline
```

---

## 2. 升级步骤

### 2.1 Step 1：pom.xml 依赖升级

```xml
<!-- 原：Spring Boot 3.5.10 -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.10</version>
</parent>

<!-- 新：Spring Boot 4.0.x -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.0</version>
</parent>

<!-- Spring AI BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 2.2 Step 2：Jackson 2 → 3

Spring Boot 4 默认 Jackson 3。检查：

```java
// ❌ Jackson 2 写法（可能失效）
ObjectMapper mapper = new ObjectMapper();
mapper.writeValueAsString(obj);

// ✅ Jackson 3 写法
import tools.jackson.databind.ObjectMapper;
```

**重点检查**：
- 自定义 `@JsonComponent` 是否仍工作
- `ObjectMapper` Bean 配置是否兼容
- 第三方依赖（如 Jackson modules）是否支持 Jackson 3

### 2.3 Step 3：JSpecify null-safety 适配

Spring AI 2.0 全面采用 JSpecify。如果你用了 `@Nullable`/`@NonNull`（JSR-305）：

```java
// ❌ JSR-305（Spring 6 之前）
import javax.annotation.Nullable;

// ✅ JSpecify（Spring 6+）
import org.jspecify.annotations.Nullable;
```

### 2.4 Step 4：Tool Calling 迁移

**原 1.0 写法**（手写工具调用循环）：

```java
// 1.0：手写 AgentLoop
while (true) {
    ChatResponse resp = client.prompt(...).call().chatResponse();
    if (!resp.hasToolCalls()) break;
    for (ToolCall tc : resp.getToolCalls()) {
        // 手动执行 tool
        // 手动把结果塞回上下文
    }
}
```

**新 2.0 写法**：

```java
// 2.0：ToolCallingAdvisor 自动注册，递归迭代
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
        .defaultTools(myToolBean)  // Tool Bean 自动注入
        // ToolCallingAdvisor 自动生效，无需显式声明
        .build();
}

// 调用时透明，框架自动迭代到收敛
String result = client.prompt().user(question).call().content();
```

**关键变化**：你的阶段 4 自研 `AgentLoop` **可以删除**，或保留为"终止条件 + 预算控制"的薄壳。

### 2.5 Step 5：结构化输出迁移

```java
// 1.0：BeanOutputConverter
BeanOutputConverter<Foo> conv = new BeanOutputConverter<>(Foo.class);
Foo foo = conv.convert(client.prompt().user(...).call().content());

// 2.0：StructuredOutputValidationAdvisor（带校验+自动重试）
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
        .defaultAdvisors(new StructuredOutputValidationAdvisor())
        .build();
}

Foo foo = client.prompt()
    .user("...")
    .call()
    .entity(Foo.class);  // 自动校验 + 失败重试
```

### 2.6 Step 6：MCP 接入

详见 [09-MCP接入实战](./09-MCP接入实战.md)。

---

## 3. 回归测试

升级后必须跑的回归：

### 3.1 功能回归

- [ ] 阶段 2 的 `/hello` 接口
- [ ] 阶段 2 的多轮对话 + Tool
- [ ] 阶段 3 的 RAG 问答
- [ ] 阶段 4 的 5 大 Workflow 模式
- [ ] 阶段 4 的 AgentLoop（自研）—— **验证是否可移除**

### 3.2 性能回归

```bash
# 用 wrk 或 k6 压测
wrk -t4 -c100 -d30s http://localhost:8080/chat
# 对比 1.0 时代的 P50/P95 延迟
```

预期：ToolCallingAdvisor 自动迭代后性能应该持平或略优（少了手写循环开销）。

### 3.3 行为回归

- [ ] Tool 调用顺序是否正确（并行 vs 串行）
- [ ] 流式输出是否完整（无 token 丢失）
- [ ] 异常时是否正确降级

---

## 4. 常见升级问题

### 4.1 Q: `ChatClient.Builder` 上的方法签名变了

A: 2.0 把 `defaultAdvisors(Advisor...)` 改为可变参数 + `List<Advisor>`。如果编译失败，检查 advisor 链 API。

### 4.2 Q: Jackson 3 报错 "module not registered"

A: 第三方 Jackson module（如 `JavaTimeModule`）需要 Jackson 3 版本。检查依赖树：
```bash
mvn dependency:tree | grep jackson
```

### 4.3 Q: 自研 AgentLoop 删了之后，怎么控制 maxTurns 和预算？

A: 2.0 通过 Advisor 拦截：
```java
Advisor budgetAdvisor = new BudgetControlAdvisor(maxTokens, maxCost);
Advisor maxTurnsAdvisor = new MaxTurnsAdvisor(20);
```

详见 [reference/生产化与运营/15-Agent可靠性工程Java视角.md](../../reference/生产化与运营/15-Agent可靠性工程Java视角.md)。

---

## 5. 升级验收清单

- [ ] Spring Boot 4.0.x + Spring AI 2.0.0 启动正常
- [ ] Jackson 3 序列化/反序列化通过
- [ ] 阶段 1-4 所有功能回归通过
- [ ] 自研 `AgentLoop` 已移除或降级为薄壳
- [ ] 至少 1 个 Tool 通过 `ToolCallingAdvisor` 自动迭代
- [ ] 至少 1 处使用 `StructuredOutputValidationAdvisor`
- [ ] 性能回归（P95 延迟持平或更优）

---

## 6. 相关文档

- [09-MCP接入实战](./09-MCP接入实战.md)
- [10-Anthropic五大Workflow模式](./10-Anthropic五大Workflow模式.md)
- [`reference/选型与对比/10-SpringAI-vs-LangChain4j何时用何框架.md`](../../reference/选型与对比/10-SpringAI-vs-LangChain4j何时用何框架.md) —— 2.0 改变格局的详细分析
- [`reference/生产化与运营/14-MCP协议与生态.md`](../../reference/生产化与运营/14-MCP协议与生态.md)
