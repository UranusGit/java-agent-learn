# 项目 13：事件溯源 Agent 运行时平台 — 01-最小 Demo 搭建

> **定位**：v0——从零搭一个能跑通的 Spring AI 2.0 单体：一个 `ChatClient`、一个工具、内存会话。本迭代**故意不做任何「正确架构」**——先让主链路通，让痛点暴露。本文给出**完整可手写代码**（一行不省略）。前置阅读：[00-需求分析与架构设计]。
> 「遇到阻塞？→ [教程 01-Spring-AI框架入门]、[教程 02-ChatClient与对话模型]、[教程 03-工具调用]；API 真实性以 [附录 12-SpringAI2-API基准] 为准」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的 Agent：能对话、能调一个工具（查订单）、会话结束不崩溃 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → ChatClient → @Tool；会话存内存 Map |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**） |

## 2. 目标与量化验收

| # | 目标 | 验收 |
|---|------|------|
| 1 | 对话 + 工具 | 一次「查订单」对话正确返回订单数据 |
| 2 | 会话连续 | 第二问「刚才那个订单金额多少」能答对 |
| 3 | 工具注册 | 模型在「查订单」意图下正确触发工具 |
| 4 | 流式 | 前端收到增量 token |

**本迭代明确不做**：不做会话持久化、安全审批、可观测、多模型、多租户。

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

### 3.2 `application.yml`

```yaml
spring:
  application:
    name: agent-runtime
  ai:
    openai:
      base-url: https://api.deepseek.com          # DeepSeek 兼容 OpenAI 协议
      api-key: ${DEEPSEEK_API_KEY}                # 环境变量，不落明文
      chat:
        options:
          model: deepseek-chat
server:
  port: 8080
```

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
import org.springframework.ai.model.tool.ToolCallbackProvider;
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
                .defaultAdvisors(new MessageChatMemoryAdvisor(memory))
                .defaultTools(toolCallbackProvider)
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

> ⚠️ 上面 `ChatController` 里用到了 `ChatMemory.CONVERSATION_ID`，需要在类顶部补 import：`import org.springframework.ai.chat.memory.ChatMemory;`（代码与上文 `AgentConfig` 同包名不同，按你的包结构调整 import）。

### 3.7 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run
# 测试：
# curl -X POST "http://localhost:8080/api/chat?sessionId=s1" -H "Content-Type: text/plain" -d "订单 ORD-001 金额多少"
# curl -X POST "http://localhost:8080/api/chat/stream?sessionId=s1" -H "Content-Type: text/plain" -d "刚才那个订单状态呢"
```

## 4. ADR 演进决策

### ADR 013-03：v0 允许「正确架构缺席」，但必须埋下三个换接口
- **决策**：v0 用最直白实现，但三个换接口在代码里留名——① 会话收敛到 `ChatMemory` 接口（v1 换事件溯源实现）② 工具收敛到 `@Tool` + `ToolCallbackProvider`（v3 加 `ToolCallingManager` 装饰器）③ LLM 收敛到 `ChatClient`（v6 拆 LLM 网关）
- **取舍理由**：接口名先立、实现后换——让后续迭代有抓手又不破坏最小 demo

## 5. 验收与已知痛点

**验收**：对话正确、会话连续、工具触发、流式可见。

**已知痛点（供 v1 决策）**：
1. 会话在内存，重启即失，审计无法回放
2. 工具直连内存 map，换后端要改工具代码
3. 工具执行无策略位——`ToolCallingManager` 是 v3 要包装的拦截点
4. Token 用量无从得知

> **定位回顾**：v0 是「故意不完美」的地基。下一站 [02-迭代一-事件溯源会话日志]——用事件溯源解决痛点 1。
