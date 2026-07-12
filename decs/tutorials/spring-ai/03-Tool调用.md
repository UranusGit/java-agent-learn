# Spring AI 03 - Tool 调用

> Function Calling 的 Spring AI 风格。重点对比 LangChain4j 的差异。
> 前置：已完成 [01-快速起步](./01-快速起步.md) 和 [02-Advisor链](./02-Advisor链.md)。

---

## 1. 与 LangChain4j 的对比速览

| 维度 | LangChain4j | Spring AI |
|------|-------------|-----------|
| 注解 | `@Tool`（dev.langchain4j.agent.tool） | `@Tool`（org.springframework.ai.tool） |
| 参数描述 | `@P("描述")` | `@P("描述")` |
| 装配方式 | `.tools(new XxxTools())` | `.defaultTools(xxxBean)` 或 `.tools(...)` |
| 依赖注入 | 手动 new | **支持 Spring Bean** |
| 返回类型 | 任意（自动序列化） | 任意（自动序列化） |

**核心差异**：Spring AI 的 Tool 可以是 Spring Bean，**能直接 `@Autowired` 业务 Service**。

---

## 2. 第一个 Tool

### 2.1 定义

```java
package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTools {

    @Tool(description = "获取当前系统时间，格式 ISO 标准")
    public String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
```

### 2.2 关键差异点

| 注意 | LangChain4j | Spring AI |
|------|-------------|-----------|
| `@Tool` 包 | `dev.langchain4j.agent.tool.Tool` | `org.springframework.ai.tool.annotation.Tool` |
| `@P` 名字 | `@P`（`dev.langchain4j.agent.tool.P`） | **`@ToolParam`** |
| 类是否要 `@Component` | 不需要（手动 new） | **需要**（如果是 Bean 装配方式） |

### 2.3 装配：两种方式

#### 方式 1：作为默认 Tool（推荐）

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, TimeTools timeTools) {
    return builder
            .defaultSystem("...")
            .defaultTools(timeTools)   // 全局生效
            .build();
}
```

#### 方式 2：单次调用时指定

```java
@GetMapping("/chat")
public String chat(@RequestParam String q, CalculatorTools calc) {
    return client.prompt()
            .user(q)
            .tools(calc)              // 只本次调用生效
            .call()
            .content();
}
```

---

## 3. 带参数的 Tool

```java
@Component
public class CalculatorTools {

    @Tool(description = "两个数相加")
    public double add(
            @ToolParam(description = "第一个数") double a,
            @ToolParam(description = "第二个数") double b
    ) {
        return a + b;
    }

    @Tool(description = "两个数相乘")
    public double multiply(
            @ToolParam(description = "第一个数") double a,
            @ToolParam(description = "第二个数") double b
    ) {
        return a * b;
    }
}
```

注意：Spring AI 用 **`@ToolParam`**（不是 `@P`）。

---

## 4. 注入业务 Service（Spring AI 的最大优势）

### 4.1 完整示例

```java
@Service
public class EmployeeService {
    public EmployeeInfo findByName(String name) { ... }
    public String findWorkstation(String employeeId) { ... }
}

@Component
public class EmployeeTools {

    private final EmployeeService employeeService;

    public EmployeeTools(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @Tool(description = "根据员工姓名查询工号、部门、入职日期")
    public EmployeeInfo queryEmployeeByName(
            @ToolParam(description = "员工中文全名") String fullName
    ) {
        return employeeService.findByName(fullName);
    }

    @Tool(description = "根据工号查询工位位置")
    public String queryWorkstation(
            @ToolParam(description = "工号，6 位数字") String employeeId
    ) {
        return employeeService.findWorkstation(employeeId);
    }
}
```

### 4.2 装配

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, EmployeeTools employeeTools) {
    return builder
            .defaultSystem("公司人事助手")
            .defaultTools(employeeTools)
            .build();
}
```

**对比 LangChain4j**：
```java
// LangChain4j：手动 new，依赖也要手动注入
EmployeeTools tools = new EmployeeTools(new EmployeeService(...));

Assistant agent = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .tools(tools)
        .build();
```

**Spring AI 的优势**：Tool 就是普通 Spring Bean，能用 `@Autowired`、`@Transactional`、Spring Data Repository 等所有 Spring 能力。

---

## 5. ToolContext：传递额外参数

某些场景下，Tool 需要拿到**调用上下文**（如当前用户 ID、租户 ID）。

```java
@Tool(description = "查询当前用户的订单")
public List<Order> queryMyOrders(ToolContext context) {
    String userId = (String) context.getContext().get("userId");
    return orderService.findByUser(userId);
}
```

### 5.1 调用时传入

```java
@GetMapping("/orders")
public String orders(@RequestParam String q, @RequestParam String userId) {
    return client.prompt()
            .user(q)
            .toolContext(Map.of("userId", userId))
            .call()
            .content();
}
```

**注意**：`ToolContext` 不算 Tool 参数，LLM 看不到它。它是给 Tool 方法运行时用的。

---

## 6. Tool 方法返回值处理

### 6.1 简单类型

```java
@Tool(description = "查询天气")
public String getWeather(@ToolParam("城市") String city) {
    return "晴，25 度";
}
```

### 6.2 自定义对象

```java
public record Weather(String city, double temp, String condition) {}

@Tool(description = "查询天气")
public Weather getWeather(@ToolParam("城市") String city) {
    return new Weather(city, 25.0, "晴");
}
```

Spring AI 自动用 Jackson 序列化成 JSON 给 LLM。

### 6.3 集合

```java
@Tool(description = "列出所有部门")
public List<Department> listDepartments() { ... }
```

### 6.4 注意事项

- 返回值类型必须**可序列化**（避免循环引用）
- 自定义对象字段名要语义清晰
- 大对象要警惕 token 浪费（如 List 有 1000 个元素）

---

## 7. 静态 Tool vs 实例 Tool

### 7.1 静态方法 Tool

```java
@Component
public class UtilTools {

    @Tool(description = "生成 UUID")
    public static String generateUuid() {
        return UUID.randomUUID().toString();
    }
}
```

Spring AI 支持静态方法，但装配方式略不同（需要 `ToolCallbacks.from(...)`）。

### 7.2 推荐：实例方法

简单、和 Spring Bean 配合好。除非工具完全是纯函数（无状态），否则用实例方法。

---

## 8. 动态注册 Tool（高级）

### 8.1 用 `ToolCallback` 接口

```java
public class DynamicTool implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("dynamicQuery")
                .description("动态查询工具")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}")
                .build();
    }

    @Override
    public String call(String toolInput) {
        // 自己解析参数、执行
        return "result";
    }
}
```

**用途**：
- Tool 配置从数据库读
- 多租户不同工具集
- Plugin 系统

---

## 9. 实战：智能运维 Agent

### 9.1 工具集

```java
@Component
@RequiredArgsConstructor
public class K8sTools {

    private final KubernetesClient k8s;  // fabric8 客户端

    @Tool(description = "查询指定命名空间下 Deployment 的副本数和状态")
    public DeploymentStatus getDeploymentStatus(
            @ToolParam(description = "命名空间，如 default") String namespace,
            @ToolParam(description = "Deployment 名称") String name
    ) {
        Deployment dep = k8s.apps().deployments()
                .inNamespace(namespace)
                .withName(name)
                .get();
        return new DeploymentStatus(
                name, namespace,
                dep.getStatus().getReplicas(),
                dep.getStatus().getReadyReplicas()
        );
    }
}

@Component
@RequiredArgsConstructor
public class PromTools {

    private final WebClient promClient;

    @Tool(description = "查询 Prometheus 指标")
    public double queryMetric(
            @ToolParam(description = "PromQL 查询表达式") String query
    ) {
        return promClient.get()
                .uri(uri -> uri.path("/api/v1/query")
                        .queryParam("query", query).build())
                .retrieve()
                .bodyToMono(PromResponse.class)
                .map(PromResponse::value)
                .block();
    }
}
```

### 9.2 装配

```java
@Bean
ChatClient opsAgent(ChatClient.Builder builder, K8sTools k8s, PromTools prom) {
    return builder
            .defaultSystem("你是运维助手，能查 K8s 和 Prometheus")
            .defaultTools(k8s, prom)
            .build();
}
```

### 9.3 使用

```
用户：用户服务有几个副本，CPU 高不高？

LLM 推理：
  Action: getDeploymentStatus(namespace="default", name="user-service")
  Observation: {"ready":3,"desired":3}

  Action: queryMetric(query="rate(container_cpu_usage_seconds_total{pod=~\"user-service.*\"}[5m])")
  Observation: 0.65

  Answer: 用户服务有 3 个副本，CPU 使用率约 65%
```

---

## 10. 调试技巧

### 10.1 看实际发出的 Tool 描述

启动时加日志：
```yaml
logging:
  level:
    org.springframework.ai: DEBUG
```

### 10.2 看请求体

```yaml
spring:
  ai:
    openai:
      chat:
        observations:
          log-prompt: true
          log-response: true
```

---

## 11. 常见错误

### 11.1 `@Tool` 包引错

**症状**：报错或 Tool 不被识别。

**原因**：引成了 LangChain4j 的 `@Tool`。
**解决**：用 `org.springframework.ai.tool.annotation.Tool`。

### 11.2 Tool 没被调用

**诊断**：
1. 看日志，请求体里有没有 `tools` 数组
2. 看是不是忘了 `.defaultTools(...)` 或 `.tools(...)`
3. 检查 Tool 描述是否清晰

### 11.3 Bean 注入失败

**症状**：`UnsatisfiedDependencyException`

**原因**：Tool Bean 内部依赖没注入。
**解决**：检查 Tool 的所有依赖都是 Spring Bean。

### 11.4 返回值序列化失败

**症状**：`JsonProcessingException`

**解决**：
- 返回值类型必须是 POJO 或 record
- 不能有循环引用
- 大对象考虑只返回关键字段

### 11.5 静态方法无法调用

**原因**：装配方式不对。
**解决**：用 `ToolCallbacks.from(UtilTools.class)` 而非 `defaultTools(utilToolsBean)`。

---

## 12. Spring AI vs LangChain4j：Tool 选型决策

| 场景 | 推荐 |
|------|------|
| 简单 Tool，无依赖 | 两者均可 |
| 需要注入 Service / Repository | **Spring AI** |
| 需要事务（`@Transactional`） | **Spring AI** |
| 多租户工具集 | **Spring AI**（ToolContext 优雅） |
| 非 Spring 项目 | LangChain4j |
| 需要复杂 Tool 决策 | 两者均可，看团队栈 |

---

## 13. 理解检查

1. Spring AI 的 `@Tool` 和 LangChain4j 的 `@Tool` 包名分别是什么？
2. 为什么说"Spring AI 的 Tool 更适合企业应用"？
3. `ToolContext` 解决了什么问题？什么场景下用？
4. Tool 返回 `List<Order>` 时，LLM 实际收到的是什么格式？
5. 静态方法 Tool 装配时和实例方法有什么不同？

---

## 14. 练习任务

1. 实现 `TimeTools`、`CalculatorTools`，让 LLM 调用
2. 写一个 `EmployeeTools`，注入业务 Service（伪造数据即可）
3. 测试 `ToolContext`：传入 userId，Tool 用它过滤数据
4. 用 Web 客户端调用，观察 LLM 串联调用多个 Tool 的过程
5. 故意写一个 Tool 描述模糊的版本，对比 LLM 调用准确率
6. （进阶）实现 `K8sTools` 或 `PromTools`（用伪造数据），搭一个智能运维 Agent

完成后进入 [04-RAG 实战](./04-RAG实战.md)。
