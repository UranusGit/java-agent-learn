# 33a Agent 可观测性最小实战：演进式教程

> **这份文档怎么用**：照着从 Step 1 敲到 Step 8，每一步都是一个**能独立跑起来的程序**——加一点东西、跑一次、看到效果、再往下。最后得到一个**记得上文、能自主调工具、全过程实时可见**的多轮 Agent（独立模块 **demo07**）。
>
> **为什么不一次性给完整代码**：一次性把 WebFlux + Reactor Context + Observation + ContextPropagation + Memory 五件事摆出来，新人会认知过载。演进式让每步只引入 1 个新概念，始终在「跑得起来的代码」上前进。
>
> **和 33 的关系**：[33-方案](./33-Agent子过程实时可见性方案.md) 是理论全本，本 demo 是它的最小可跑版；[33b](./33b-Agent可观测性企业级演进实践.md) 是终极项目。
>
> **技术栈**：Spring Boot 4.1 · Spring AI 2.0.0 · Java 21 · WebFlux · Micrometer Observation · Micrometer ContextPropagation · 流式（`.stream()`）· DeepSeek。

---

## 演进路线（8 步）

| Step | 引入的概念 | 这步要解决的痛点 |
|------|-----------|----------------|
| 1 | 项目骨架 + WebFlux | 跑起来，确认依赖/启动没问题 |
| 2 | Spring AI 最小闭环 | 真正问 LLM 拿到回复 |
| 3 | 流式 `.stream()` | 不等 30 秒，逐字吐出 |
| 4 | 工具调用 `@Tool` | LLM 自主调工具拿实时数据 |
| 5 | **Observation（demo 的灵魂）** | 工具返回值藏在黑盒里，看不清 |
| 6 | 事件总线 + SSE | 把工具调用实时推给前端 |
| 7 | 会话隔离 + ContextPropagation（企业级封装） | 多用户并发会串流 |
| 8 | 多轮记忆 ChatMemory | LLM 不记得上文 |

> 每步的固定结构：**① 这步解决什么问题 → ② 改/加什么代码 → ③ 跑起来你会看到 → （需要时）④ 内部怎么流转**。

---

## Step 1：项目骨架——先跑起来（不接 LLM）

**① 要解决什么**：从零建一个 Spring Boot WebFlux 项目，确认依赖、启动、接口都通。这一步不碰 LLM，零门槛，先跑起来建立信心。

**② 代码**

`pom.xml`：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example.demo07</groupId>
    <artifactId>demo07</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>demo07</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencies>
        <!-- WebFlux：HTTP 接口 + SSE（注意不是 spring-boot-starter-web） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
    </dependencies>
</project>
```

> 现在只引 WebFlux。Spring AI、actuator 后面按需加。

`src/main/resources/application.yaml`（暂时空配置，后面填）：
```yaml
server:
  port: 8080
```

`src/main/java/com/example/demo07/Application.java`：
```java
package com.example.demo07;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`src/main/java/com/example/demo07/obs/ObsController.java`：
```java
package com.example.demo07.obs;

import org.springframework.web.bind.annotation.*;

/**
 * Step 1：先返回硬编码字符串，确认骨架跑得通。
 */
@RestController
@RequestMapping("/demo07/obs")
public class ObsController {

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt) {
        return "你说的是：" + prompt + "（Step 1 骨架，还没接 LLM）";
    }
}
```

**③ 跑起来你会看到**

```bash
cd demo07
mvn spring-boot:run

# 另一个终端
curl "http://localhost:8080/demo07/obs/chat?prompt=你好"
# 你说的是：你好（Step 1 骨架，还没接 LLM）
```

跑通了，骨架没问题。下一步接 LLM。

---

## Step 2：接上 ChatClient——真正问 LLM

**① 要解决什么**：让 `/chat` 真正把问题发给 LLM（DeepSeek），拿到回复。这一步是 Spring AI 的最小闭环——理解 `ChatClient.Builder` 自动装配。

**② 代码**

`pom.xml` 加两个依赖：
```xml
        <!-- Spring AI 调 OpenAI 兼容协议（DeepSeek 兼容） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
```

并在 `<dependencies>` 后加 Spring AI BOM 统一管版本：
```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

`application.yaml` 加 DeepSeek 配置：
```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:你的key}      # platform.deepseek.com 申请，用环境变量避免进 git
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-chat
        temperature: 0.7
logging:
  level:
    org.springframework.ai: info
```

> ⚠️ API key 别硬编码进 git。`${DEEPSEEK_API_KEY:你的key}` 是「优先读环境变量，没有用默认值」。

新增 `config/ChatClientConfig.java`——装配 ChatClient（Step 2 还不挂任何 Advisor）：
```java
package com.example.demo07.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Step 2：装配最简 ChatClient（空 Builder，不挂任何东西）。
 * ChatClient.Builder 由 Spring AI starter 自动注入。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

改 `ObsController`——用 ChatClient 调 LLM（同步 `.call()`，先不用流式）：
```java
package com.example.demo07.obs;

import com.example.demo07.config.ChatClientConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo07/obs")
public class ObsController {

    private final ChatClient chatClient;

    public ObsController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt) {
        // Step 2：同步调用，等 LLM 全部生成完一次性返回
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
```

**③ 跑起来你会看到**

```bash
curl "http://localhost:8080/demo07/obs/chat?prompt=用一句话介绍你自己"
# 我是一个 AI 助手……（等了几秒后一次性返回）
```

**④ 内部怎么流转**（参数传递线，第一次出现）：
```
HTTP ?prompt=xxx
   ↓
ObsController.chat(prompt)
   ↓ chatClient.prompt().user(prompt).call().content()
   ↓ （ChatClient 是单例 Bean，所有请求共享）
DeepSeek API（用 application.yaml 里的 key/base-url/model）
   ↓
返回完整字符串给前端
```

痛点来了：**要等好几秒，期间前端干等**。下一步改成流式。

---

## Step 3：流式 `.stream()`——逐字吐出

**① 要解决什么**：Step 2 同步 `.call()` 要等 LLM 全生成完才返回，体验差。改成流式，LLM 边产边推，前端逐字看到。

**② 代码**

改 `ObsController`——`.call()` 换 `.stream()`，返回类型从 `String` 换 `Flux<String>`：
```java
package com.example.demo07.obs;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo07/obs")
public class ObsController {

    private final ChatClient chatClient;

    public ObsController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Step 3：流式。返回 Flux<String> + produces SSE → 每个文本片段是一帧 data: 行。
     * LLM 边产边推，前端逐字收到，不等整段。
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();   // Flux<String>：每个 chunk 是一段文本
    }
}
```

**③ 跑起来你会看到**

```bash
curl -N "http://localhost:8080/demo07/obs/chat?prompt=用一句话介绍你自己"
# 我
# 是一个
# AI 助手
# …（逐段、近实时地到达）
```

> `produces = TEXT_EVENT_STREAM_VALUE` 让 Spring 把 `Flux<String>` 自动序列化成 SSE（Server-Sent Events）。`curl -N` 是禁用缓冲，实时看到流。这就是「流式」的全部——`.call()` → `.stream()`，返回类型 `String` → `Flux<String>`。

---

## Step 4：加工具——LLM 自主调 `getCurrentTime`

**① 要解决什么**：LLM 不知道「现在几点」（训练数据是旧的）。给它一个工具 `getCurrentTime`，LLM 自己决定要不要调，拿到真实时间再回答。

**② 代码**

新增 `tool/TimeTools.java`：
```java
package com.example.demo07.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具。@Tool 注解的方法会被 Spring AI 注册成工具，LLM 可自主调用。
 * description 写清楚——LLM 据此决定要不要调。
 */
@Component
public class TimeTools {

    @Tool(description = "获取服务器当前时间，格式 yyyy-MM-dd HH:mm:ss")
    public String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
```

改 `ChatClientConfig`——注册工具：
```java
package com.example.demo07.config;

import com.example.demo07.tool.TimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, TimeTools timeTools) {
        return builder
                .defaultTools(timeTools)    // 注册工具，LLM 自主决定调不调
                .build();
    }
}
```

**③ 跑起来你会看到**

```bash
curl -N "http://localhost:8080/demo07/obs/chat?prompt=现在几点了，用一句话规划今天下午"
# 现在是 14:30，下午建议……
```

**痛点暴露了**：LLM 回答里有 `14:30`，但 `14:30` 哪来的？`getCurrentTime` 真的被调了吗？返回值到底是什么？**你完全看不见**——工具调用发生在 Spring AI 内部循环里，是个黑盒。这正是下一步要解决的。

> 第一反应可能是「自己写个拦截器拦工具」——但工具调用在 Spring AI 内部 `ToolCallingAdvisor` 循环里，手动包 `ToolCallback` 或写 Advisor 都拦不全。Spring AI 早就挖好了观测点：**Observation**。下一步用它。

---

## Step 5：Observation——把工具黑盒打开（demo 的灵魂）

**① 要解决什么**：Step 4 那个「工具返回值看不见」的痛点。解决方案：写一个 `ObservationHandler`，**订阅** Spring AI 已经在发的工具调用 Observation，把「工具名/参数/返回值」打印出来。

**关键认知**：工具可见性不是「自己拦」，而是「订阅框架原生发的观测事件」。这是企业级做法——用框架能力，零侵入。

**② 代码**

`pom.xml` 加 actuator（它带来 `ObservationRegistry`，没它 Handler 注册不了）：
```xml
        <!-- Actuator：带来 ObservationRegistry Bean，ToolObservationHandler 注册必备；
             也是企业级标配（健康检查、metrics），间接带入 context-propagation（Step 7 用） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

新增 `obs/ToolObservationHandler.java`——订阅工具调用 Observation，打印结果：
```java
package com.example.demo07.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

/**
 * Step 5：订阅 Spring AI 原生的工具调用 Observation，打印工具名/参数/返回值。
 *
 * 为什么用 Observation 而不是自己拦：
 *   工具调用发生在 Spring AI 内部循环里，手动包 ToolCallback 拦不全。
 *   Spring AI 经 DefaultToolCallingManager（带 ObservationRegistry）执行工具，
 *   设计上就在发 ToolCallingObservation——我们只管订阅，零侵入。
 *
 * 关键时机：onStop 时工具已执行完，getToolCallResult() 才有值。
 */
@Component
public class ToolObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    /** 只处理工具调用的 Observation（LLM 调用是别的类型，这里不管）。 */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    /** 工具调用结束：此时 getToolCallResult() 有值。打印它。 */
    @Override
    public void onStop(ToolCallingObservationContext context) {
        String toolName = context.getToolDefinition() != null
                ? context.getToolDefinition().name() : "unknown";

        System.out.println("===== 工具调用 =====");
        System.out.println("  工具: " + toolName);
        System.out.println("  参数: " + context.getToolCallArguments());
        System.out.println("  返回: " + context.getToolCallResult());
        System.out.println("====================");
    }
}
```

**不需要手动注册 Handler。** `ToolObservationHandler` 标了 `@Component`，Spring Boot 4 + actuator 会**自动把所有 `ObservationHandler` 类型的 Bean 注册到 `ObservationRegistry`**（通过 `ObservationRegistryPostProcessor`）。所以 `@Component` 这一个注解就够了，Handler 自动收到事件。

> ⚠️ **别再写一个 `ObservationConfig` 手动 `registry.observationConfig().observationHandler(handler)`** ——那是新手最常踩的坑：
> Spring 已经自动注册了一次，你再手动注册一次，**同一个 Handler 实例会在 Registry 里出现两次**，结果是每次工具调用的 `onStop` 被调用两遍，控制台**打印两遍**「工具调用」。
>
> 记住：**`observationHandler(...)` 是「追加」不是「替换」，且 Spring 已自动追加过**。手动再追加 = 重复。靠 `@Component` 自动注册即可，这正是「能用框架原生能力就不自己包底层」的体现。

**③ 跑起来你会看到**

```bash
curl -N "http://localhost:8080/demo07/obs/chat?prompt=现在几点了"
```

后端控制台会打印：
```
===== 工具调用 =====
  工具: getCurrentTime
  参数: {}
  返回: 2026-07-23 14:30:25
====================
```

**痛点解决了！** 工具的参数和返回值清清楚楚。但只是打印在后端控制台——前端用户看不到。下一步把它实时推给前端。

> **API 核实**（官方/反编译确认）：
> - `ObservationHandler<T>` 的 `onStop(T)` / `supportsContext(Context)` —— micrometer-observation（webflux 间接带入）
> - `ToolCallingObservationContext` 的 `getToolDefinition().name()`、`getToolCallArguments()`、`getToolCallResult()` —— spring-ai-model 2.0.0
> - `DefaultToolCallingManager` 构造器强制接收 `ObservationRegistry` —— 工具执行设计上带 Observation

---

## Step 6：事件总线 + SSE——把工具调用实时推给前端

**① 要解决什么**：Step 5 的工具调用只打印在后端。要让它实时推给前端（浏览器/curl），需要一个「事件总线」+ 一个 SSE 订阅接口。

这里要做一个**架构决策**：为什么要拆成「`/chat` 触发 + `/sse` 订阅」两个接口，而不是一个？

> 因为一次请求里有**两种事件**要观测：工具调用（ToolObservationHandler 发）和 LLM 流式正文（Controller 里的流）。两者来自不同地方，需要一个**公共总线**汇合，再用一个 `/sse` 接口订阅。如果只用 Step 3 那种「`/chat` 直接返回 Flux」，工具事件就没地方塞进去了。

**② 代码**

定义事件模型 `obs/AgentEvent.java`：
```java
package com.example.demo07.obs;

import lombok.Builder;

/**
 * 一个 Agent 事件。record + Lombok @Builder：不可变 + 链式构造。
 * timestamp 用 long（epoch 毫秒），发事件处显式传 System.currentTimeMillis()。
 * data 用 Object：不同 type 装不同结构，交给 Jackson 序列化。
 */
@Builder
public record AgentEvent(
        String type,                  // 事件类型：READY / SESSION_* / TOOL_CALL / CONTENT_DELTA
        String sessionId,             // 会话 ID（SSE 按它过滤；Step 7 才真正用上）
        long timestamp,               // epoch 毫秒
        Object data                   // 附加信息（按 type 定）
) {
}
```

> pom 加 Lombok：
> ```xml
>         <dependency>
>             <groupId>org.projectlombok</groupId>
>             <artifactId>lombok</artifactId>
>             <optional>true</optional>
>         </dependency>
> ```

事件总线 `obs/AgentEventBus.java`：
```java
package com.example.demo07.obs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 事件总线。整个进程一个实例。当成「公共公告板」：emit 塞事件、flux 订阅。
 * 生产者消费者解耦——ToolObservationHandler 和 ObservableAgent 都往这塞，/sse 从这取。
 */
@Component
public class AgentEventBus {

    private final Sinks.Many<AgentEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void emit(AgentEvent event) {
        sink.tryEmitNext(event);
    }

    /** 订阅某会话的事件（SSE 接口用，自带 sessionId 过滤）。 */
    public Flux<AgentEvent> flux(String sessionId) {
        return sink.asFlux().filter(e -> sessionId.equals(e.sessionId()));
    }
}
```

改 `ToolObservationHandler`——不再打印，改成发事件（sessionId 先硬编码 "unknown"，Step 7 解决）：
```java
    @Override
    public void onStop(ToolCallingObservationContext context) {
        String toolName = context.getToolDefinition() != null
                ? context.getToolDefinition().name() : "unknown";

        Map<String, Object> data = new HashMap<>();
        data.put("tool", toolName);
        data.put("args", context.getToolCallArguments());
        data.put("result", context.getToolCallResult());

        bus.emit(AgentEvent.builder()
                .type("TOOL_CALL").sessionId("unknown")     // Step 6 先硬编码，Step 7 改对
                .timestamp(System.currentTimeMillis())
                .data(data).build());
    }
```
（构造器注入 `AgentEventBus bus`，完整文件见文末附录）

新增编排层 `obs/ObservableAgent.java`——把流式正文也转成事件，发会话生命周期事件：
```java
package com.example.demo07.obs;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

/**
 * Step 6：流式调 ChatClient，把每个阶段实时转成事件，推到总线。
 * 工具调用事件由 ToolObservationHandler 发，业务事件（正文/会话生命周期）归这里——职责分离。
 *
 * 设计要点（企业级响应式编排）：
 *   - 方法真正产出的是「事件」，所以返回 Flux<AgentEvent>（签名诚实，非"返回值只是触发器"）。
 *   - 事件双出口：作为流元素返回（给直接订阅方）+ 构造时同步 emit 进总线（给 /sse）。
 *   - 三段式 concat：opening → deltas → closing，顺序由 Flux.concat 保证。
 *   - 开始/完成用 Flux.defer：到点才构造，timestamp 才准；不提前 emit，subscribe 前无副作用。
 *
 * 命名约定（消除内外层重名）：
 *   - 外层 Flux 用「阶段名」：opening / deltas / closing（流的几段）
 *   - 内层 AgentEvent 用「事件名」：started / delta / completed / failed（具体一个事件）
 *   两层词类不同，不会撞名。
 */
@Component
public class ObservableAgent {

    private final ChatClient chatClient;
    private final AgentEventBus bus;

    public ObservableAgent(ChatClient chatClient, AgentEventBus bus) {
        this.chatClient = chatClient;
        this.bus = bus;
    }

    public Flux<AgentEvent> run(String userInput, String sessionId) {

        // 阶段一：会话开始。defer → 订阅时才构造 started，timestamp 才准。
        Flux<AgentEvent> opening = Flux.defer(() -> {
            AgentEvent started = AgentEvent.builder()
                    .type("SESSION_STARTED").sessionId(sessionId)
                    .timestamp(System.currentTimeMillis())
                    .data(Map.of("input", userInput)).build();
            bus.emit(started);
            return Flux.just(started);
        });

        // 阶段二：正文流。每个 chunk 映射成一个 delta（数量不定，只能由流产出，无法提前构造）。
        // .content() 直接拿 Flux<String>（纯文本 chunk），比 chatClientResponse() 更简洁。
        Flux<AgentEvent> deltas = chatClient.prompt()
                .user(userInput)
                .stream()
                .content()
                .map(chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return null;
                    }
                    AgentEvent delta = AgentEvent.builder()
                            .type("CONTENT_DELTA").sessionId(sessionId)
                            .timestamp(System.currentTimeMillis())
                            .data(Map.of("text", chunk)).build();
                    bus.emit(delta);
                    return delta;
                })
                .filter(Objects::nonNull)
                .doOnError(err -> {
                    AgentEvent failed = AgentEvent.builder()
                            .type("SESSION_FAILED").sessionId(sessionId)
                            .timestamp(System.currentTimeMillis())
                            .data(Map.of("error", err.getClass().getSimpleName() + ": " + err.getMessage()))
                            .build();
                    bus.emit(failed);
                });

        // 阶段三：会话完成。defer → 流执行到末尾时才构造 completed，timestamp 才准。
        Flux<AgentEvent> closing = Flux.defer(() -> {
            AgentEvent completed = AgentEvent.builder()
                    .type("SESSION_COMPLETED").sessionId(sessionId)
                    .timestamp(System.currentTimeMillis())
                    .data(Map.of()).build();
            bus.emit(completed);
            return Flux.just(completed);
        });

        // 三段编排：opening → deltas → closing。
        // 顺序靠 Flux.concat（前一段完成才订阅下一段）；时机靠各阶段的 Flux.defer / map lambda。
        return Flux.concat(opening, deltas, closing);
    }
}
```

> **为什么这样编排（企业级响应式要点）**：
> 1. **签名诚实**——返回 `Flux<AgentEvent>` 就是事件流，不再「返回值是触发器、真正产出在副作用里」。调用方可直接订阅拿完整事件序列。
> 2. **事件双出口**——每个事件既作为流元素返回（给直接订阅方），又 `bus.emit` 进总线（给 `/sse`）。一条逻辑、两个出口，不重复构造。
> 3. **三段独立具名**——`opening/deltas/closing` 各自成变量，末尾 `Flux.concat(opening, deltas, closing)` 一行编排。读起来是「定义三段 → 拼接」，层次清晰，不用在 concat 参数堆里分辨。
> 4. **顺序靠 `Flux.concat`**——`concat` 严格按序：前一段完成才订阅下一段。这是"开始→正文→完成"顺序的保证。
> 5. **时机靠 `Flux.defer`**——opening/closing 的 `currentTimeMillis()` 写在 defer 的 lambda 体内，到点才执行（`currentTimeMillis()` 这个**调用**在 lambda 里，不是把结果传进去——这是延迟生效的关键）；completed 的时间戳是流结束时刻，不是方法调用时刻。deltas 的 timestamp 则在每个 chunk 到达时由 map 的 lambda 取。
> 6. **错误路径安全**——`doOnError` 发 `SESSION_FAILED`；异常时 concat 后续（closing 的 defer）不会执行，不会多发 `SESSION_COMPLETED`。比 `doFinally`（错误和完成都触发）更严谨。

> 💡 **响应式核心认知（这段代码的底层原理）**：
> - **lambda 传递的是"位置"不是"执行"**——`.map(fn)` / `Flux.defer(fn)` 里的 `fn` 是一段"将来执行的代码"，组装 Flux 时只把 fn 存起来，fn 体内的代码（包括 `currentTimeMillis()`）一行都没跑。
> - **副作用写进 lambda 体内才能延迟**——`currentTimeMillis()` 这个调用必须写在 lambda 的 `{}` 里（不是把它的结果传进 lambda），它的执行才跟着 lambda 走。
> - **选哪个操作符 = 选"什么时候触发 lambda"**——map 靠"元素到达"触发、defer 靠"订阅"触发、doOnComplete 靠"完成"触发。延迟都靠 lambda，触发器因场景而异。

改 `ObsController`——双接口：`/chat` 触发（异步），`/sse` 订阅：
```java
@RestController
@RequestMapping("/demo07/obs")
public class ObsController {

    private final AgentEventBus bus;
    private final ObservableAgent agent;

    public ObsController(AgentEventBus bus, ObservableAgent agent) {
        this.bus = bus;
        this.agent = agent;
    }

    /** 接口一：触发 Agent。异步订阅 run()，立即返回 sessionId。 */
    @GetMapping("/chat")
    public String chat(@RequestParam String prompt,
                       @RequestHeader String sessionId) {
        agent.run(prompt, sessionId).subscribe();   // 异步触发，事件进总线，不阻塞
        return sessionId;
    }

    /**
     * 接口二：订阅事件流。
     *
     * 就绪握手（消除竞态，企业级标准做法）：
     * 用 startWith 在流的**最前面**插一帧 READY。前端收到 READY 才调 /chat 触发 Agent。
     * 这样"先订阅后触发"由协议保证，而非靠时序运气——避免 /chat 早于订阅生效、SESSION_STARTED 丢失。
     * 关键用 startWith 而非 concatWith：startWith 在上游（总线）订阅建立后才发 READY，
     * 握手才真实有效（READY 不会早于总线订阅）。
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> sse(@RequestHeader String sessionId) {
        AgentEvent ready = AgentEvent.builder()
                .type("READY").sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .data(Map.of()).build();

        return bus.flux(sessionId)
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.type()))
                .startWith(ready);   // 订阅总线就绪后，最前面插 READY
    }
}
```

**③ 跑起来你会看到**

```bash
# 终端 1：先订阅（sessionId 走 header）
curl -N -H "sessionId: s1" "http://localhost:8080/demo07/obs/sse"

# 终端 2：再触发
curl -H "sessionId: s1" "http://localhost:8080/demo07/obs/chat?prompt=现在几点了"
```

终端 1 实时收到：
```
data:{"type":"READY","sessionId":"s1",...,"data":{}}                 ← 订阅就绪帧（收到它再触发 /chat）
data:{"type":"SESSION_STARTED","sessionId":"s1",...,"data":{"input":"现在几点了"}}
data:{"type":"TOOL_CALL","sessionId":"unknown",...,"data":{"tool":"getCurrentTime","result":"2026-07-23 14:30:25"}}
                                                                ↑ 工具返回值！实时到达！
data:{"type":"CONTENT_DELTA","sessionId":"s1",...,"data":{"text":"现在"}}
data:{"type":"CONTENT_DELTA","sessionId":"s1",...,"data":{"text":"是14:30"}}
data:{"type":"SESSION_COMPLETED","sessionId":"s1",...,"data":{}}
```

> curl 是手动两终端，时序靠你自己控制（看到 READY 再开终端 2）。真实前端（附录 C HTML）会自动在收到 READY 后才 triggerChat，协议层消除竞态。

**前端实时看到工具返回值了！** 但注意一个 bug：`TOOL_CALL` 的 sessionId 是 `"unknown"`——因为 `ToolObservationHandler` 在工具执行线程里，拿不到 `/chat` 那个 sessionId。两个用户同时用就会串流。下一步解决。

**④ 内部怎么流转**（双接口架构 + 就绪握手，消除竞态）：
```
①前端 fetch /sse(s1) → 后端订阅总线（就绪）→ 发 READY 帧 → 前端收到 READY
②前端收到 READY 才 triggerChat → /chat(s1) → run(prompt, s1) 发 SESSION_STARTED/CONTENT_DELTA
                                                                                  ↓
                                                            AgentEventBus（公共公告板，此时早已被订阅）
                                                                                  ↑
/sse(s1) ──── startWith(READY) ─── filter(sessionId=s1) ─────────────────────────┘
                                    ↑
              ToolObservationHandler.onStop ──发 TOOL_CALL──┐（sessionId 还是 unknown！）
```

> **为什么需要 READY 握手**：双通道下，/sse 订阅和 /chat 触发是两个独立请求，若 /chat 早于 /sse 订阅生效，SESSION_STARTED 已进总线而订阅还没接上，事件就丢了（总线不缓存历史给晚到的订阅者）。READY 帧把"先订阅后触发"从**时序运气**变成**协议保证**——这是企业级 SSE/WebSocket 流式系统的标准做法。

---

## Step 7：会话隔离——sessionId 怎么传到工具线程（企业级封装）

**① 要解决什么**：Step 6 的 `TOOL_CALL` 的 sessionId 是 `unknown`，因为 `ToolObservationHandler` 在**另一个线程**执行工具，拿不到 `/chat` 的 sessionId。要解决「工具事件怎么标到正确的会话」。

**坑**：WebFlux 下 `ChatClient.stream()` 的 Flux 链会在 `boundedElastic` 线程池切换线程，普通 ThreadLocal 一切线程就丢。

**为什么不直接往 `ToolCallingObservationContext` 塞 sessionId**：那个 context 是 Spring AI 内部创建的，我们够不着它的"创建时机"；等 `onStop` 能看到它时它是只读的，也没有给我们用的写入口。而且 Observation 是通用观测层，刻意与 Reactor 解耦、不接收业务参数——它和外部世界的唯一通用接口是 ThreadLocal。所以 sessionId 只能走"线程上下文"，不能"塞进 context"。

**② 企业级做法（封装 + 自动传播）**

为什么不写个裸 `SessionIdHolder` 就完事？因为裸版有四个可维护性痛点：
- **职责泄漏**：业务代码（`run()`）直接操作 ThreadLocal 的 set/clear，混入基础设施细节。
- **key 散落**：`"sessionId"` 字符串在写、读、注册三处出现，靠人工保持一致。
- **难扩展**：将来加 `tenantId`/`traceId`，每个都要复制一套 Holder + Config。
- **易泄漏**：忘了 `clear` 就串会话（线程池复用）。

企业级用**封装 + Reactor 自动传播**解决：
- **通用上下文值类**（`PropagatedContextValue`）+ **集中注册中心**（`AppContextKeys`）+ **通用装配**（`ContextPropagationConfig`）——key 单一定义、加新值零改业务。
- 上下文跨线程传递交给 **Reactor 自动传播**（`Hooks.enableAutomaticContextPropagation` + 注册 accessor），框架在切线程时自动 set/clear ThreadLocal——**业务代码完全不碰 ThreadLocal**。

**(a) 通用上下文值 `obs/PropagatedContextValue.java`**

把"一个可传播的 ThreadLocal"抽象成通用类（泛型，任意类型都能用）：
```java
package com.example.demo07.obs;

/**
 * 可传播的上下文值：一个 ThreadLocal + 它在 Reactor Context 里的 key。
 * 配合 ContextPropagation 自动跨线程。泛型 T 支持任意类型（String/Long/...）。
 * 企业里 sessionId、tenantId、traceId 都用它，不必每个值写一个 Holder。
 */
public class PropagatedContextValue<T> {

    private final ThreadLocal<T> threadLocal = new ThreadLocal<>();
    private final String key;

    public PropagatedContextValue(String key) {
        this.key = key;
    }

    public String key() { return key; }
    public T get() { return threadLocal.get(); }
    public void set(T value) { threadLocal.set(value); }
    public void clear() { threadLocal.remove(); }
}
```

**(b) 集中注册中心 `obs/AppContextKeys.java`**

所有上下文值**只在这一处定义**，key 只出现一次（单一事实来源）：
```java
package com.example.demo07.obs;

/**
 * 全局上下文注册中心：所有可传播值的唯一定义点。
 * 加新值只在这里 new 一个 + 在 Config 里 register，业务代码零改动。
 */
public final class AppContextKeys {

    public static final PropagatedContextValue<String> SESSION_ID =
            new PropagatedContextValue<>("sessionId");

    // 将来扩展时只加这里 + Config 一行：
    // public static final PropagatedContextValue<String> TENANT_ID = new PropagatedContextValue<>("tenantId");
    // public static final PropagatedContextValue<String> TRACE_ID  = new PropagatedContextValue<>("traceId");

    private AppContextKeys() {}
}
```

**(c) 通用装配器 `config/ContextPropagationConfig.java`**

注册所有上下文值 + 开启自动传播。加新值只改这一处（加一行 `register`）：
```java
package com.example.demo07.config;

import com.example.demo07.obs.AppContextKeys;
import com.example.demo07.obs.PropagatedContextValue;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 企业级装配：
 * 1. 把所有 PropagatedContextValue 注册为可传播 ThreadLocal（Reactor Context ↔ ThreadLocal 桥接）
 * 2. 开启 Reactor 自动上下文传播（切线程时 ThreadLocal 自动恢复）
 * 加新上下文值只需在 init() 里多调一行 register。
 */
@Configuration
public class ContextPropagationConfig {

    @PostConstruct
    public void init() {
        ContextRegistry registry = ContextRegistry.getInstance();
        register(registry, AppContextKeys.SESSION_ID);
        // register(registry, AppContextKeys.TENANT_ID);   // 扩展时加这一行
        Hooks.enableAutomaticContextPropagation();
    }

    /** 通用注册：任何 PropagatedContextValue 都按同一套方式桥接。 */
    private <T> void register(ContextRegistry registry, PropagatedContextValue<T> value) {
        registry.registerThreadLocalAccessor(value.key(), value::get, value::set, value::clear);
    }
}
```

**(d) 业务代码：零 ThreadLocal 操作**

为什么业务代码不需要 set/clear ThreadLocal：**Reactor 自动传播**负责了。
`Hooks.enableAutomaticContextPropagation()` + (c) 注册的 accessor，会在 Flux 切线程（执行工具）时**自动**把 Reactor Context 里的 sessionId 灌进目标线程的 ThreadLocal、离开时自动清掉。业务只管读 Context 写事件，ThreadLocal 的 set/clear 全由框架代办。

> **企业级方向判断（为什么选自动传播而非显式 set/clear）**：三个维度都指向它——
> ① **生态主流**：Spring Boot 4 默认就开自动传播；Micrometer Tracing（Observation 的亲兄弟）的 traceId 正是这么跨线程到达 handler 的，你的 onStop 和它同构。
> ② **栈内一致**：将来的 traceId/tenantId 都走自动传播，sessionId 不该另搞一套。
> ③ **简洁**：业务零 ThreadLocal 代码，run 保持三段式干净。
> 同步 try/finally（如自造 `withValue`）在响应式里是**错的**——它套不住 Flux 的异步执行期（组装时 set、subscribe 前就 clear 了），所以不用。

`ObsController`——写上下文（强类型 key，不再裸字符串）：
```java
    @GetMapping("/chat")
    public String chat(@RequestParam String prompt,
                       @RequestHeader String sessionId) {
        agent.run(prompt)
                .contextWrite(reactor.util.context.Context.of(
                        AppContextKeys.SESSION_ID.key(), sessionId))   // ← 强类型 key，写进 Reactor Context
                .subscribe();
        return sessionId;
    }
```

`ObservableAgent.run()`——读 Context，三段式编排，**不碰 ThreadLocal**：
```java
    public Flux<AgentEvent> run(String userInput) {
        return Flux.deferContextual(ctx -> {
            String sessionId = ctx.getOrDefault(AppContextKeys.SESSION_ID.key(), "unknown");

            Flux<AgentEvent> opening = Flux.defer(() -> {
                AgentEvent started = AgentEvent.builder()
                        .type("SESSION_STARTED").sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .data(Map.of("input", userInput)).build();
                bus.emit(started);
                return Flux.just(started);
            });

            Flux<AgentEvent> deltas = chatClient.prompt()
                    .user(userInput)
                    .stream()
                    .content()
                    .map(chunk -> {
                        if (chunk == null || chunk.isEmpty()) return null;
                        AgentEvent delta = AgentEvent.builder()
                                .type("CONTENT_DELTA").sessionId(sessionId)
                                .timestamp(System.currentTimeMillis())
                                .data(Map.of("text", chunk)).build();
                        bus.emit(delta);
                        return delta;
                    })
                    .filter(Objects::nonNull)
                    .doOnError(err -> {
                        AgentEvent failed = AgentEvent.builder()
                                .type("SESSION_FAILED").sessionId(sessionId)
                                .timestamp(System.currentTimeMillis())
                                .data(Map.of("error", err.getClass().getSimpleName() + ": " + err.getMessage()))
                                .build();
                        bus.emit(failed);
                    });

            Flux<AgentEvent> closing = Flux.defer(() -> {
                AgentEvent completed = AgentEvent.builder()
                        .type("SESSION_COMPLETED").sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .data(Map.of()).build();
                bus.emit(completed);
                return Flux.just(completed);
            });

            return Flux.concat(opening, deltas, closing);
        });
    }
```

`ToolObservationHandler.onStop`——从强类型 key 读（自动传播已把值灌进当前线程）：
```java
        String sessionId = Optional.ofNullable(AppContextKeys.SESSION_ID.get())
                .orElse("unknown");   // 不再硬编码、不再依赖裸 Holder
        // ... 发 TOOL_CALL 带上 sessionId
```

**③ 跑起来你会看到**

```bash
# 终端 1 订阅 s1，终端 3 订阅 s2
curl -N -H "sessionId: s1" "http://localhost:8080/demo07/obs/sse" &
curl -N -H "sessionId: s2" "http://localhost:8080/demo07/obs/sse" &

# 终端 2 用 s1 触发
curl -H "sessionId: s1" "http://localhost:8080/demo07/obs/chat?prompt=现在几点了"
```

这次 `TOOL_CALL` 的 sessionId 是 `"s1"` 了！终端 1（s1）收到全部事件，终端 3（s2）什么都收不到——**会话隔离做实，工具事件也只标到对应会话**。

**④ sessionId 怎么到工具线程的**（这一步的核心，参数传递线讲透）：
```
/chat header: sessionId=s1
   ↓
ObsController: contextWrite(Context.of(AppContextKeys.SESSION_ID.key(), s1))
   ↓ 写进 Reactor Context（随 Flux 链传播）
ObservableAgent.run: deferContextual 读出 s1（业务代码到这就够了，不 set ThreadLocal）
   ↓ ChatClient.stream() 流式调用，LLM 决定调工具
   ↓ 工具执行切到 boundedElastic 线程（换了线程！）
Hooks.enableAutomaticContextPropagation() + 已注册的 accessor
   ↓ 框架自动：把 Reactor Context 的 sessionId 灌进工具线程的 ThreadLocal（业务无感）
   ↓ 框架自动：离开时 clear（防线程池复用串会话）
ToolObservationHandler.onStop: AppContextKeys.SESSION_ID.get() → 读到 s1
   ↓ TOOL_CALL 带上 s1
```

> **这套封装解决了什么**（企业级 vs 裸 SessionIdHolder）：
> | 维度 | 裸 SessionIdHolder | 企业级封装（方向 A：自动传播） |
> |------|-------------------|----------------------------|
> | 业务代码 | 直接 set/clear ThreadLocal | 零 ThreadLocal 代码，只读 Context |
> | key 管理 | `"sessionId"` 散落 3 处 | `AppContextKeys.SESSION_ID` 单一定义 |
> | 加新上下文值 | 复制 Holder + 改 Config + 改业务 | 加一个 `PropagatedContextValue` + Config 一行 |
> | 泄漏防护 | 靠业务记得 clear | 自动传播离开线程时自动 clear |
> | 栈内一致性 | 和 traceId/tenantId 机制不同 | 和 Micrometer Tracing 等同源一致 |
> | 职责边界 | 业务混入基础设施 | 业务/基础设施彻底分离 |
>
> **判断标准**：只要项目会出现第二个需要跨线程传递的上下文值（tenantId/traceId/userId），就值得上这套封装——加第一个值时嫌麻烦，加第二个时会庆幸封装了。

⚠️ **诚实说明**：`ToolObservationHandler.onStop` 能否读到，取决于 Spring AI 2.0 的 `ToolCallingAdvisor` 内部执行工具时是否走标准 Reactor 操作符链。本 demo 设计是标准做法，但**这一环需实跑验证**。若读不到，退路是把 sessionId 通过 `ToolContext` 显式传给工具（`@Tool` 方法加 `ToolContext` 参数），在工具里手动 emit 事件。


---

## Step 8：多轮记忆——LLM 记得上文

**① 要解决什么**：现在每次 `/chat` 都是独立调用，LLM 不记得之前聊过什么。问「现在几点」→回答 14:30；再问「那明天同一时间呢」→LLM 不知道「同一时间」指什么。要让它记得上文。

**② 代码（用框架原生 ChatMemory，零自研存取代码）**

新增 `config/ChatMemoryConfig.java`——内存版记忆：
```java
package com.example.demo07.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Step 8：内存版 ChatMemory（重启即丢，最小实战够用）。
 * MessageWindowChatMemory：滑动窗口，保留最近 20 条，超出按整轮淘汰。
 * 底层用 Spring AI 自动装配的 InMemoryChatMemoryRepository，无需额外依赖。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}
```

改 `ChatClientConfig`——挂记忆 Advisor：
```java
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 TimeTools timeTools,
                                 ChatMemory chatMemory) {
        return builder
                .defaultTools(timeTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
```

改 `ObservableAgent.run()`——把 sessionId 当作记忆会话 ID 传给 Advisor（sessionId 一身二职：既是观测 ID，又兼任 `ChatMemory.CONVERSATION_ID`）：
```java
            Flux<ChatClientResponse> stream = chatClient.prompt()
                    .user(userInput)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .chatClientResponse();
```

**③ 跑起来你会看到**

```bash
# 第一轮
curl -H "sessionId: s1" "http://localhost:8080/demo07/obs/chat?prompt=现在几点了，帮我规划今天下午"
# → 现在是 14:30，下午建议……

# 第二轮（同一 sessionId=s1，记得上文）
curl -H "sessionId: s1" "http://localhost:8080/demo07/obs/chat?prompt=那明天同一时间呢"
# → 明天 14:30 的话，建议……（接住了「规划/同一时间」的上下文！）

# 换 sessionId=s2 就是全新记忆
curl -H "sessionId: s2" "http://localhost:8080/demo07/obs/chat?prompt=那明天同一时间呢"
# → 什么同一时间？我不太明白……（没有上文）
```

**多轮记忆 + 会话隔离都做实了。** 这是完整 demo07 的最终形态。

⚠️ **已知限制**（官方明确）：Spring AI 2.0 下，工具调用的中间消息**不会自动存入 memory**。影响：用户消息和 LLM 最终回复会正常累积，多轮连续；但工具的 `ToolResponseMessage` 不进记忆——下一轮 LLM 记得自己说过什么，不一定记得调过什么工具。多数场景够用。

---

## 完整结构（Step 8 结束后的项目）

```
demo07/
├── pom.xml
├── src/main/resources/
│   ├── application.yaml
│   └── static/demo07.html                ← 调试页面（见附录）
└── src/main/java/com/example/demo07/
    ├── Application.java
    ├── config/
    │   ├── ChatClientConfig.java          ← Step 2/4/8 演进：空 → 工具 → 工具+记忆
    │   ├── ChatMemoryConfig.java          ← Step 8
    │   └── ContextPropagationConfig.java  ← Step 7
    ├── tool/
    │   └── TimeTools.java                 ← Step 4
    └── obs/
        ├── AgentEvent.java                ← Step 6
        ├── PropagatedContextValue.java    ← Step 7（通用上下文值）
        ├── AppContextKeys.java            ← Step 7（上下文注册中心）
        ├── AgentEventBus.java             ← Step 6
        ├── ToolObservationHandler.java    ← Step 5/6/7 演进
        ├── ObservableAgent.java           ← Step 6/7/8 演进
        └── ObsController.java             ← Step 1/2/3/6/7 演进
```

---

## 这个方案为什么「企业级、合理」

| 做法 | 为什么合理 |
|------|-----------|
| 工具用原生 `@Tool` + `.defaultTools()` | 用框架原生能力，不手动包底层 `ToolCallback` |
| 工具可见性用 `ObservationHandler` | 订阅框架已发的 Observation，不侵入工具代码 |
| 多轮记忆用原生 `MessageChatMemoryAdvisor` | 调前自动取历史、调后自动存回，业务零存取代码 |
| 流式 `.stream()` + `CONTENT_DELTA` | 每个阶段实时可见，不等整段返回 |
| 会话隔离走 Reactor Context + ContextPropagation | 不裸用 ThreadLocal（切线程会丢），随流传播到工具执行线程 |
| sessionId 兼任记忆会话 ID | 最小实战不分两个 ID，接口最简 |
| config/tool/obs 分层 | 装配、工具、可观测各管各的 |
| 两接口（/chat + /sse）分离 | 触发和订阅解耦 |
| `AgentEventBus` 总线 | 多消费者共享，加日志/审计消费者零改 Agent |

简单说：**能用框架原生能力的，就不要自己包底层**（工具可见用 Observation、记忆用 ChatMemory）。

---

## 这个 demo 没做什么（对照 33 方案）

| 没做 | 后果 | 方案章节 |
|------|------|---------|
| 事件序号 + 重排 | 可能乱序 | §17.1 |
| 背压分级降级 | 高频时事件可能丢 | §17.2 |
| SSE 断线重连 | 刷新页面漏中间事件 | §17.3 |
| LLM token 统计 | 不知道烧了多少钱 | §12 |
| 多实例 | 单机扛不住高并发 | §4 |
| 多租户（tenantId） | 只有 sessionId 维度 | §12 |
| 记忆持久化 | 内存 ChatMemory，重启即丢；持久化换 JDBC/Cassandra/Redis starter | §12 |
| 观测/记忆 ID 解耦 | sessionId 兼任记忆 ID，无法「一次观测跨多段记忆」 | — |
| 工具消息进记忆 | Spring AI 2.0 限制：工具调用中间消息不进 memory | — |

---

## 附录 A：完整 ToolObservationHandler（Step 7 后）

```java
package com.example.demo07.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    private final AgentEventBus bus;

    public ToolObservationHandler(AgentEventBus bus) {
        this.bus = bus;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        // Step 7：从强类型上下文 key 读（不再依赖裸 Holder）
        String sessionId = Optional.ofNullable(AppContextKeys.SESSION_ID.get()).orElse("unknown");

        String toolName = context.getToolDefinition() != null
                ? context.getToolDefinition().name() : "unknown";

        // HashMap 而非 Map.of——getToolCallArguments()/getToolCallResult() 可能返回 null
        Map<String, Object> data = new HashMap<>();
        data.put("tool", toolName);
        data.put("args", context.getToolCallArguments());
        data.put("result", context.getToolCallResult());
        data.put("toolType", context.getToolType());

        bus.emit(AgentEvent.builder()
                .type("TOOL_CALL").sessionId(sessionId)
                .timestamp(System.currentTimeMillis())
                .data(data).build());
    }
}
```

## 附录 B：完整 ObservableAgent（Step 8 后）

```java
package com.example.demo07.obs;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

@Component
public class ObservableAgent {

    private final ChatClient chatClient;
    private final AgentEventBus bus;

    public ObservableAgent(ChatClient chatClient, AgentEventBus bus) {
        this.chatClient = chatClient;
        this.bus = bus;
    }

    public Flux<AgentEvent> run(String userInput) {
        return Flux.deferContextual(ctx -> {
            String sessionId = ctx.getOrDefault(AppContextKeys.SESSION_ID.key(), "unknown");
            // 不 set ThreadLocal——自动传播在工具切线程时自动灌入/清理，业务零 ThreadLocal 代码

            // 阶段一：会话开始
            Flux<AgentEvent> opening = Flux.defer(() -> {
                AgentEvent started = AgentEvent.builder()
                        .type("SESSION_STARTED").sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .data(Map.of("input", userInput)).build();
                bus.emit(started);
                return Flux.just(started);
            });

            // 阶段二：正文流（带记忆 Advisor）；.content() 直接拿 Flux<String>
            Flux<AgentEvent> deltas = chatClient.prompt()
                    .user(userInput)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .map(chunk -> {
                        if (chunk == null || chunk.isEmpty()) return null;
                        AgentEvent delta = AgentEvent.builder()
                                .type("CONTENT_DELTA").sessionId(sessionId)
                                .timestamp(System.currentTimeMillis())
                                .data(Map.of("text", chunk)).build();
                        bus.emit(delta);
                        return delta;
                    })
                    .filter(Objects::nonNull)
                    .doOnError(err -> {
                        AgentEvent failed = AgentEvent.builder()
                                .type("SESSION_FAILED").sessionId(sessionId)
                                .timestamp(System.currentTimeMillis())
                                .data(Map.of("error", err.getClass().getSimpleName() + ": " + err.getMessage()))
                                .build();
                        bus.emit(failed);
                    });

            // 阶段三：会话完成
            Flux<AgentEvent> closing = Flux.defer(() -> {
                AgentEvent completed = AgentEvent.builder()
                        .type("SESSION_COMPLETED").sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .data(Map.of()).build();
                bus.emit(completed);
                return Flux.just(completed);
            });

            // 三段编排：opening → deltas → closing
            return Flux.concat(opening, deltas, closing);
        });
    }
}
```

## 附录 C：调试用 HTML 页面（Step 8 后）

放 `src/main/resources/static/demo07.html`，浏览器打开 `http://localhost:8080/demo07.html`。

> 为什么用 `fetch + ReadableStream` 而不是 `EventSource`：SSE 的 sessionId 走 header，而 `EventSource` 不能设自定义 header，`fetch` 可以。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>Agent 可观测性 Demo</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
               background: #f7f7f8; height: 100vh; display: flex; flex-direction: column; }
        header { background: #fff; padding: 14px 24px; border-bottom: 1px solid #ececec;
                 font-size: 16px; font-weight: 600; color: #333; }
        #chat { flex: 1; overflow-y: auto; padding: 24px 0; }
        .msg { max-width: 760px; margin: 0 auto; padding: 0 24px; }
        .user { display: flex; justify-content: flex-end; margin-bottom: 20px; }
        .user .bubble { background: #4d6bfe; color: #fff; padding: 10px 16px;
                        border-radius: 14px 14px 4px 14px; max-width: 70%; line-height: 1.6; word-break: break-all; }
        .assistant { margin-bottom: 24px; }
        .assistant .avatar { font-size: 13px; color: #999; margin-bottom: 8px; }
        .assistant .bubble { background: #fff; padding: 14px 18px; border-radius: 12px;
                             line-height: 1.8; color: #333; word-break: break-word;
                             box-shadow: 0 1px 3px rgba(0,0,0,0.04); overflow-x: auto; }
        /* Markdown 渲染样式：代码块、行内代码、列表、标题、段落间距 */
        .assistant .bubble pre { background: #f6f8fa; padding: 12px; border-radius: 6px;
                                 overflow-x: auto; margin: 8px 0; font-size: 13px; line-height: 1.5; }
        .assistant .bubble code { font-family: "SFMono-Regular", Consolas, monospace; font-size: 13px; }
        .assistant .bubble p { margin: 6px 0; }
        .assistant .bubble p:first-child { margin-top: 0; }
        .assistant .bubble p:last-child { margin-bottom: 0; }
        .assistant .bubble ul, .assistant .bubble ol { margin: 6px 0; padding-left: 22px; }
        .assistant .bubble h1, .assistant .bubble h2, .assistant .bubble h3 { margin: 10px 0 6px; }
        .assistant .bubble h1 { font-size: 18px; } .assistant .bubble h2 { font-size: 16px; } .assistant .bubble h3 { font-size: 15px; }
        .assistant .bubble a { color: #4d6bfe; }
        .process { max-width: 760px; margin: 0 auto 20px; padding: 0 24px; }
        .process summary { cursor: pointer; color: #999; font-size: 13px; padding: 6px 0; user-select: none; }
        .step { background: #fff; border-left: 3px solid #4d6bfe; margin: 6px 0; padding: 8px 12px;
                border-radius: 0 6px 6px 0; font-size: 13px; box-shadow: 0 1px 2px rgba(0,0,0,0.03); }
        .step .label { color: #4d6bfe; font-weight: 600; margin-right: 6px; }
        .step .kv { color: #888; margin-right: 10px; }
        .step .kv b { color: #333; font-weight: 500; }
        .step.tool { border-left-color: #00b96b; }
        .step.tool .label { color: #00b96b; }
        .step.done { border-left-color: #999; }
        #input-bar { background: #fff; border-top: 1px solid #ececec; padding: 14px 24px; }
        #input-wrap { max-width: 760px; margin: 0 auto; display: flex; gap: 10px; align-items: center;
                      background: #f7f7f8; border-radius: 22px; padding: 6px 6px 6px 18px; }
        #prompt { flex: 1; border: none; background: transparent; outline: none; font-size: 15px; padding: 8px 0; }
        #send { background: #4d6bfe; color: #fff; border: none; width: 36px; height: 36px;
                border-radius: 50%; cursor: pointer; font-size: 18px; flex-shrink: 0; }
        #send:disabled { background: #c5cdfa; cursor: not allowed; }
        #status { text-align: center; color: #bbb; font-size: 12px; padding: 6px 0; }
    </style>
</head>
<body>
<header>Agent 可观测性 Demo <span style="font-weight:400;color:#bbb;font-size:13px">· 流式 + 工具调用全程可见 + 多轮记忆</span>
    <button id="new-chat" style="float:right;font-size:13px;border:1px solid #ddd;background:#fff;border-radius:6px;padding:4px 10px;cursor:pointer;color:#666">新对话（清记忆）</button>
</header>
<div id="chat"></div>
<div id="status">输入问题，回车或点发送</div>
<div id="input-bar"><div id="input-wrap">
    <input id="prompt" placeholder="问点什么，如：现在几点了？规划今天下午" value="现在几点了，用一句话规划今天下午">
    <button id="send" onclick="start()">➤</button>
</div></div>
<script>
    let sending = false, sseController = null, pendingChat = null, assistantText = '';
    // sessionId 一身二职：事件流过滤 ID + 记忆会话 ID。同一对话多轮复用，点「新对话」才换。
    let currentSessionId = 's-' + Date.now();

    document.getElementById('prompt').addEventListener('keydown', e => { if (e.key === 'Enter') start(); });
    document.getElementById('new-chat').addEventListener('click', () => {
        if (sending) return;
        currentSessionId = 's-' + Date.now();
        document.getElementById('chat').innerHTML = '';
        assistantText = '';   // 清空累积的 Markdown 文本
        document.getElementById('status').textContent = '已开新对话（会话 ' + currentSessionId + '）';
    });

    function start() {
        if (sending) return;
        const promptInput = document.getElementById('prompt');
        const prompt = promptInput.value.trim();
        if (!prompt) return;
        const sessionId = currentSessionId;
        const chat = document.getElementById('chat');

        const u = document.createElement('div'); u.className = 'msg user';
        u.innerHTML = '<div class="bubble"></div>';
        u.querySelector('.bubble').textContent = prompt;
        chat.appendChild(u);

        const proc = document.createElement('details'); proc.className = 'process'; proc.open = true;
        proc.innerHTML = '<summary><b>Agent 执行过程</b>（会话 ' + sessionId + '，点击折叠）</summary>';
        chat.appendChild(proc);

        const a = document.createElement('div'); a.className = 'msg assistant';
        a.innerHTML = '<div class="avatar">Assistant</div><div class="bubble"></div>';
        chat.appendChild(a);
        chat.scrollTop = chat.scrollHeight;

        promptInput.value = '';
        assistantText = '';   // 每轮新气泡从空开始（否则会累积以往所有回答）
        setSending(true);
        document.getElementById('status').textContent = '连接中...';
        subscribeSse(sessionId, prompt, proc, a);
    }

    function subscribeSse(sessionId, prompt, proc, assistantEl) {
        sseController = new AbortController();
        // 待触发的 chat：收到 READY 帧（订阅就绪）后才触发，消除竞态
        pendingChat = { sessionId, prompt };
        fetch('/demo07/obs/sse', { method: 'GET',
            headers: { 'sessionId': sessionId, 'Accept': 'text/event-stream' },
            signal: sseController.signal
        }).then(resp => {
            if (!resp.ok) throw new Error('SSE 连接失败: ' + resp.status);
            document.getElementById('status').textContent = '等待就绪...';
            // 不在这里 triggerChat——等收到 READY 帧（见 onEvent）再触发，协议层保证时序
            const reader = resp.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            (function pump() {
                reader.read().then(({ done, value }) => {
                    if (done) { onStreamEnd(); return; }
                    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
                    let idx;
                    while ((idx = buffer.indexOf('\n\n')) >= 0) {
                        handleFrame(buffer.slice(0, idx), proc, assistantEl);
                        buffer = buffer.slice(idx + 2);
                    }
                    pump();
                }).catch(err => { if (err.name !== 'AbortError') onStreamEnd(); });
            })();
        }).catch(err => {
            document.getElementById('status').textContent = '连接失败: ' + err.message;
            setSending(false);
        });
    }

    function triggerChat(sessionId, prompt) {
        fetch('/demo07/obs/chat?prompt=' + encodeURIComponent(prompt), {
            method: 'GET', headers: { 'sessionId': sessionId }
        });
    }

    function handleFrame(frame, proc, assistantEl) {
        let dataLine = '';
        for (const line of frame.split('\n')) if (line.startsWith('data:')) dataLine += line.slice(5).trim();
        if (!dataLine) return;
        let evt; try { evt = JSON.parse(dataLine); } catch (e) { return; }
        onEvent(evt.type, evt.data || {}, proc, assistantEl);
    }

    function onEvent(type, data, proc, assistantEl) {
        if (type === 'READY') {
            // 就绪握手：订阅已建立，现在触发 /chat，保证不漏 SESSION_STARTED
            document.getElementById('status').textContent = 'Agent 思考中...';
            triggerChat(pendingChat.sessionId, pendingChat.prompt);
            return;
        }
        if (type === 'TOOL_CALL') {
            addStep(proc, 'tool', '🔧 ' + (data.tool || 'tool'), [['参数', data.args], ['返回值', data.result]]);
        } else if (type === 'CONTENT_DELTA') {
            if (data.text) {
                // 累积原始 Markdown 文本，每次增量到达后整体重新渲染（流式标准做法）
                assistantText += data.text;
                assistantEl.querySelector('.bubble').innerHTML = renderMarkdown(assistantText);
            }
        } else if (type === 'SESSION_COMPLETED') {
            addStep(proc, 'done', '✓ 完成', []);
            document.getElementById('status').textContent = '完成';
            setSending(false);
            if (sseController) sseController.abort();
        } else if (type === 'SESSION_FAILED') {
            const errStep = document.createElement('div'); errStep.className = 'step done';
            errStep.style.borderLeftColor = '#e53935';
            errStep.innerHTML = '<span class="label" style="color:#e53935">❌ 后端错误</span>'
                + '<span class="kv">error: <b>' + escapeHtml(String(data.error || '')) + '</b></span>';
            proc.appendChild(errStep);
            document.getElementById('status').textContent = '失败';
            setSending(false);
            if (sseController) sseController.abort();
        }
        document.getElementById('chat').scrollTop = document.getElementById('chat').scrollHeight;
    }

    function onStreamEnd() {
        if (sending) { document.getElementById('status').textContent = '连接已结束'; setSending(false); }
    }
    function addStep(proc, cls, label, kvs) {
        const step = document.createElement('div'); step.className = 'step ' + cls;
        let html = '<span class="label">' + label + '</span>';
        for (const [k, v] of kvs) if (v !== null && v !== undefined && v !== '')
            html += '<span class="kv">' + k + ': <b>' + escapeHtml(String(v)) + '</b></span>';
        step.innerHTML = html; proc.appendChild(step);
    }
    function escapeHtml(s) { return s.replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

    /**
     * 极简 Markdown 渲染（无外部依赖，流式安全）。
     * 支持：代码块 ```、行内代码 `、粗体 **、标题 #、无序列表 -、有序列表 1.、链接 []()、换行/段落。
     * 安全：所有非代码文本经 escapeHtml 转义，防 XSS（LLM 输出不可信）。
     * 流式：代码块未闭合 ``` 时，已闭合部分正常渲染，未闭合的按普通文本显示。
     */
    function renderMarkdown(md) {
        const blocks = [];   // 暂存代码块占位
        // 1. 提取代码块 ```...```（含未闭合的最后一个，按普通文本处理）
        let text = md.replace(/```(\w*)\n?([\s\S]*?)(?:```|$)/g, (m, lang, code) => {
            if (!m.endsWith('```')) return m;   // 未闭合，留给后续当普通文本
            const i = blocks.length;
            blocks.push('<pre><code>' + escapeHtml(code.replace(/\n$/, '')) + '</code></pre>');
            return ' BLOCK' + i + ' ';
        });
        // 2. 转义剩余文本（防 XSS）
        text = escapeHtml(text);
        // 3. 按行处理块级元素
        const lines = text.split('\n');
        let html = '', inUl = false, inOl = false;
        const closeLists = () => { if (inUl) { html += '</ul>'; inUl = false; } if (inOl) { html += '</ol>'; inOl = false; } };
        for (const line of lines) {
            if (/^###\s+/.test(line)) { closeLists(); html += '<h3>' + inline(line.replace(/^###\s+/, '')) + '</h3>'; }
            else if (/^##\s+/.test(line)) { closeLists(); html += '<h2>' + inline(line.replace(/^##\s+/, '')) + '</h2>'; }
            else if (/^#\s+/.test(line)) { closeLists(); html += '<h1>' + inline(line.replace(/^#\s+/, '')) + '</h1>'; }
            else if (/^[-*]\s+/.test(line)) { if (!inUl) { closeLists(); html += '<ul>'; inUl = true; } html += '<li>' + inline(line.replace(/^[-*]\s+/, '')) + '</li>'; }
            else if (/^\d+\.\s+/.test(line)) { if (!inOl) { closeLists(); html += '<ol>'; inOl = true; } html += '<li>' + inline(line.replace(/^\d+\.\s+/, '')) + '</li>'; }
            else if (line.trim() === '') { closeLists(); }
            else { closeLists(); html += '<p>' + inline(line) + '</p>'; }
        }
        closeLists();
        // 4. 行内规则：行内代码、粗体、链接
        function inline(s) {
            return s.replace(/`([^`]+)`/g, (_, c) => '<code>' + c + '</code>')
                    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
                    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');
        }
        // 5. 还原代码块占位
        return html.replace(/ BLOCK(\d+) /g, (_, i) => blocks[+i]);
    }
    function setSending(v) {
        sending = v;
        document.getElementById('send').disabled = v;
        document.getElementById('prompt').disabled = v;
    }
</script>
</body>
</html>
```

点发送后：你的问题以右侧蓝色气泡出现，下面是一个可折叠的「Agent 执行过程」区（绿色步骤显示工具调用/参数/返回值），助手回答以左侧气泡逐字流式填充。同一对话多轮复用 sessionId（这样才有连续上下文）；点「新对话」才换 sessionId、清空页面、开始一段全新记忆。

---

## 相关文档

- [33-Agent子过程实时可见性方案](./33-Agent子过程实时可见性方案.md) —— 完整方案（本 demo 的理论全本）
- [33b-Agent可观测性企业级演进实践](./33b-Agent可观测性企业级演进实践.md) —— 终极项目

---

> **回到**：[`./00-目录索引.md`](./00-目录索引.md) · [`./33-Agent子过程实时可见性方案.md`](./33-Agent子过程实时可见性方案.md)
