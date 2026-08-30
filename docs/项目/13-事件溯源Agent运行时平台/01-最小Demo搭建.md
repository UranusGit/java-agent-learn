# 项目 13：事件溯源 Agent 运行时平台 — 01-最小 Demo 搭建

> **定位**：v0——从零搭一个能跑通的 Spring AI 2.0 单体：一个 `ChatClient`、一个工具、内存会话。本迭代**故意不做任何「正确架构」**——先让主链路通，让痛点暴露。本文给出**完整可手写代码**（一行不省略）。前置阅读：[00-需求分析与架构设计]。
> 「遇到阻塞？→ [教程 00-基础与核心/01-Spring-AI框架入门]、[教程 00-基础与核心/02-ChatClient与对话模型]、[教程 00-基础与核心/03-工具调用]；API 真实性以 [附录 05-SpringAI2-API基准] 为准」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的 Agent：能对话、能调一个工具（查订单）、会话结束不崩溃 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → ChatClient → @Tool；会话存内存 Map |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**） |

### 1.1 本节核对（四问）

- [ ] 四问（新增需求/影响模块/架构演进/上一版痛点）能在 30 秒内对不上号即 FAIL；v0 架构演进是"单体单模块"没有更高级形态
- [ ] 能说出 v0 的三大换接口（ChatMemory / @Tool+ToolCallbackProvider / ChatClient）各自被后续哪版替换（v1/v3/v6）

## 2. 目标与量化验收

| # | 目标 | 验收 |
|---|------|------|
| 1 | 对话 + 工具 | 一次「查订单」对话正确返回订单数据 |
| 2 | 会话连续 | 第二问「刚才那个订单金额多少」能答对 |
| 3 | 工具注册 | 模型在「查订单」意图下正确触发工具 |
| 4 | 流式 | 前端收到增量 token |

**本迭代明确不做**：不做会话持久化、安全审批、可观测、多模型、多租户。

### 2.1 本节核对（目标与量化验收）

- [ ] 四条验收（对话+工具/会话连续/工具注册/流式）各能对应 §3 的某个代码组件（OrderTools/MessageChatMemoryAdvisor/@Tool+Prompt/SSE）
- [ ] "本迭代明确不做"的清单与后续迭代篇（02-08 分别做持久化/审批/观测/多租户）对得上

## 3. 完整代码（照抄即可，一行不省略）

### 3.1 `pom.xml`（依赖）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>
    <groupId>com.group</groupId>
    <artifactId>agent-runtime</artifactId>
    <version>0.1.0</version>
    <name>agent-runtime</name>

    <properties>
        <java.version>21</java.version>
    </properties>

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

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 3.2 配置文件（两段式）

`application.yaml`（主配置，只做环境装载与 profile 激活）：

```yaml
spring:
  config:
    import: optional:file:.env[.properties]   # 环境变量从 .env 装载（可选文件，缺失不报错）
  profiles:
    active: eventsrc
```

`application-eventsrc.yaml`（业务配置，eventsrc profile 专属）：

```yaml
spring:
  application:
    name: agent-runtime
  ai:
    openai:
      base-url: https://api.deepseek.com          # DeepSeek 兼容 OpenAI 协议
      api-key: ${DEEPSEEK_API_KEY}                # 环境变量，不落明文
      chat:
        model: deepseek-chat          # Spring AI 2.0.0：无 options 中缀，参数直挂
server:
  port: 8081
```

> 启动命令统一 `mvn spring-boot:run -Dspring-boot.run.profiles=eventsrc -Dspring-boot.run.profiles=eventsrc`；后续各迭代的 yaml 增量一律追加到 `application-eventsrc.yaml`（v5 拆分后为各服务的 `application-eventsrc.yaml`，见 [06 §3.2] 端口矩阵），不动主配置。

### 3.3 `AgentRuntimeApplication.java`

```java
package com.group.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
```

### 3.4 配置类 `AgentConfig.java`（ChatClient + 会话记忆）

```java
package com.group.agent.config;

import com.group.agent.tool.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.tool.ToolCallbackProvider;   // Spring AI 2.0.0 真实包
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public ChatMemory chatMemory() {
        // 官方仅 InMemory / Jdbc 两类仓库；v0 用内存，v1 换成事件溯源
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory memory,
                                 ToolCallbackProvider toolCallbackProvider) {
        return builder
                .defaultSystem("你是订单助手，只能回答订单相关问题。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())   // Spring AI 2.0.0：无 public 构造器
                .defaultToolCallbacks(toolCallbackProvider)   // ToolCallbackProvider 走 defaultToolCallbacks（javap 实证 Builder 方法）
                .build();
    }
}
```

### 3.5 工具 `OrderTools.java` + `OrderInfo.java` + `OrderRepository.java`

```java
package com.group.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {

    private final OrderRepository orderRepository;

    public OrderTools(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Tool(name = "query_order", description = "按订单号查询订单金额与状态")
    public OrderInfo queryOrder(@ToolParam(description = "订单号") String orderId) {
        return orderRepository.findById(orderId);
    }
}
```

```java
package com.group.agent.tool;

// 订单信息（不可变 record）
public record OrderInfo(String orderId, double amount, String status) {}
```

```java
package com.group.agent.tool;

import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {

    // v0 用内存 map 模拟数据源，v2 换成能力缝（可切远程）
    private final Map<String, OrderInfo> store = new ConcurrentHashMap<>();

    public OrderRepository() {
        store.put("ORD-001", new OrderInfo("ORD-001", 199.0, "已发货"));
        store.put("ORD-002", new OrderInfo("ORD-002", 599.0, "待支付"));
    }

    public OrderInfo findById(String orderId) {
        return store.get(orderId);
    }
}
```

### 3.6 `ChatController.java`（对话 + 流式）

```java
package com.group.agent.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 一次性对话
    @PostMapping("/chat")
    public String chat(@RequestParam String sessionId, @RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    // 流式对话（SSE）
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public Flux<String> chatStream(@RequestParam String sessionId, @RequestBody String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
```

> ⚠️ `ChatMemory.CONVERSATION_ID` 由 `import org.springframework.ai.chat.memory.ChatMemory;` 提供（已并入上文 import；若你的包结构拆分文件，按需保留该 import）。

### 3.7 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run -Dspring-boot.run.profiles=eventsrc
# 测试：
# curl -X POST "http://localhost:8081/api/chat?sessionId=s1" -H "Content-Type: text/plain" -d "订单 ORD-001 金额多少"
# curl -X POST "http://localhost:8081/api/chat/stream?sessionId=s1" -H "Content-Type: text/plain" -d "刚才那个订单状态呢"
```

### 3.8 本节测试与验证（完整代码：对话 / 工具 / 流式）

**前置条件**：按 §3.1~§3.7 手写 pom / yml / 启动类 / AgentConfig / OrderTools+OrderInfo+OrderRepository / ChatController；`DEEPSEEK_API_KEY` 已 export；应用 `mvn spring-boot:run -Dspring-boot.run.profiles=eventsrc` 启动成功（无 `BeanDefinitionOverrideException`）。

**材料——两个叠加测试的 curl**：

```bash
# A：一次性对话（会话 s1）
curl -X POST "http://localhost:8081/api/chat?sessionId=s1" -H "Content-Type: text/plain" -d "订单 ORD-001 金额多少"
# B：流式 + 会话连续（同 sessionId=s1，第二问不带订单号）
curl -X POST "http://localhost:8081/api/chat/stream?sessionId=s1" -H "Content-Type: text/plain" -d "刚才那个订单状态呢"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 启动运行（§3.7 材料命令） | `spring-boot:run` 无启动异常；日志无 `BeanDefinitionOverrideException`（无手写同名 bean） |
| 2 | 材料 A：一次性对话「订单 ORD-001 金额多少」 | 返回订单金额 199 元与状态「已发货」（`OrderRepository` 已预置 ORD-001） |
| 3 | 材料 B：`/chat/stream` 第二问「刚才那个订单状态呢」 | 模型能结合上下文答出 ORD-001 状态（会话连续，靠 `MessageChatMemoryAdvisor` + 同 sessionId） |
| 4 | 材料 B 响应 | 流式返回增量 token（SSE 多段 output，非一次性整体返回） |
| 5 | 观测意图 | 说「查订单」时模型触发 `query_order` 工具（日志/流式过程可见工具调用） |

**失败排查**：①启动 `BeanDefinitionOverrideException`→§3.4 手写了 `vectorStore`/`chatMemory` 等与自动配置同名 bean，删掉手写 bean 或核对命名；②ORD-001 查不到→`OrderRepository` 构造里未 put ORD-001，或 `queryOrder` 的 `@ToolParam` 描述让模型没传对单号；③第二问答不对→sessionId 没统一（`ChatMemory.CONVERSATION_ID` 未随请求绑定），或 §3.6 `ChatController` 两端点 sessionId 传递不一致；④不流式→`/chat/stream` 未用 `produces = "text/event-stream"` 或 `@RequestBody` 取不到 message。

## 4. ADR 演进决策

### ADR 013-03：v0 允许「正确架构缺席」，但必须埋下三个换接口
- **决策**：v0 用最直白实现，但三个换接口在代码里留名——① 会话收敛到 `ChatMemory` 接口（v1 换事件溯源实现）② 工具收敛到 `@Tool` + `ToolCallbackProvider`（v3 加 `ToolCallingManager` 装饰器）③ LLM 收敛到 `ChatClient`（v6 拆 LLM 网关）
- **取舍理由**：接口名先立、实现后换——让后续迭代有抓手又不破坏最小 demo

### 4.1 本节核对（ADR 013-03）

- [ ] 三条换接口（ChatMemory / @Tool+ToolCallbackProvider / ChatClient）能在代码里确凿指认（import 与字段类型），找不到即 FAIL
- [ ] 三个换接口分别说得出后续替换者（事件溯源实现 / ToolCallingManager 装饰器 / LLM 网关）与其所在迭代（v1/v3/v6）

### 4.2 全篇回归（轻量）——再对照 §2 四条验收一键抽查

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重启应用，重跑 §3.8 材料 A | 对话 + 工具仍一次答对（入库幂等不报错；v0 无持久化，重启后会话清空属预期） |
| 2 | 重跑 §3.8 材料 B（流式 + 会话连续） | 流式增量 token 可见；同 sessionId 第二问仍能续上下文 |

> 至此 v0 验收闭环：对话/工具/会话连续/流式四条（§2）已被 §3.8 本节验证覆盖，此处仅做重启后再验的回归抽检。

## 5. 验收与已知痛点

**验收**：对话正确、会话连续、工具触发、流式可见。

**已知痛点（供 v1 决策）**：
1. 会话在内存，重启即失，审计无法回放
2. 工具直连内存 map，换后端要改工具代码
3. 工具执行无策略位——`ToolCallingManager` 是 v3 要包装的拦截点
4. Token 用量无从得知

### 5.1 本节核对（验收与已知痛点）

- [ ] 验收四条（对话正确/会话连续/工具触发/流式可见）与 §2 验收表一致；已知痛点四条与 §2 的"明确不做"和 ADR 013-03 的三个换接口呼应（痛点 1→v1、痛点 2→v3、痛点 3→ToolCallingManager、痛点 4→v6）
- [ ] 能说出每个痛点分别被哪篇迭代解决（02/03/05/07）

## 6. 验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 对话 + 工具 | 一次「查订单」对话正确返回订单数据（§3.8 材料 A） | ☐ |
| 会话连续 | 第二问「刚才那个订单金额多少」能答对（§3.8 材料 B） | ☐ |
| 工具注册 | 「查订单」意图下正确触发 `@Tool`（§3.8 材料 A 含工具调用） | ☐ |
| 流式 | 前端收到增量 token（§3.8 材料 B SSE） | ☐ |

> **定位回顾**：v0 是「故意不完美」的地基。下一站 [02-事件溯源会话日志]——用事件溯源解决痛点 1。
