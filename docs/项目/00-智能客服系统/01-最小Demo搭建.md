# 项目 00：智能客服系统 — 01-最小 Demo 搭建

> **定位**：从零创建 Spring Boot 4.1 + Spring AI 2.0 项目骨架，实现最简 ChatClient + WebFlux + SSE 流式对话接口。读完这篇，你能跑通「用户提问 → AI 流式回复」完整链路。本文给出**完整可手写代码**（一行不省略）。
> **读者画像**：刚读完需求分析，准备动手写代码的开发者。
> **前置阅读**：[00-需求分析与架构设计]。
> **关联教程**：[教程 00-基础与核心/01-Spring-AI框架入门 AI 框架入门]、[教程 00-基础与核心/02-ChatClient与对话模型 与对话模型]、[教程 01-WebFlux与响应式编程/00-WebFlux从零入门 流式通信]；API 真实性以 [附录 05-SpringAI2-API基准] 为准。

---

## 1. 本篇目标

### 1.1 四问（本迭代）

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的客服 Agent 骨架：Spring Boot 4.1 + Spring AI 2.0 + WebFlux + SSE 流式对话，跑通「用户提问 → AI 流式回复」 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱）：`pom.xml` / 两段式配置 / `ChatController` / `ChatService` |
| **架构如何演进** | 从零起步：WebFlux 响应式骨架 + `ChatClient` 单链，不查库、不调工具、不做多轮记忆 |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**：不查库答不了业务、不调工具做不了事、无记忆接不住追问） |

让用户能跟 AI 客服说上话。不查库、不调工具、不管多轮记忆——纯 LLM 对话 + SSE 流式输出。

```mermaid
flowchart LR
    U["用户 POST /api/chat/stream"] --> C["ChatController"]
    C --> S["ChatService"]
    S --> CC["ChatClient"]
    CC --> DS["DeepSeek API"]
    DS --> CC
    CC --> S
    S --> C
    C --> U
```

四个文件：`pom.xml`、`application.yaml` + `application-customerservice.yaml`、`ChatController.java`、`ChatService.java`。

## 2. 完整代码（照抄即可）

### 2.1 `pom.xml`

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
    <groupId>com.shop</groupId>
    <artifactId>customer-service</artifactId>
    <version>0.1.0</version>

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

### 2.2 配置（两段式：入口 `application.yaml` + 业务 `application-customerservice.yaml`）

**`application.yaml`（入口：仅 .env import + profiles.active）**

```yaml
spring:
  application:
    name: customer-service
  config:
    import: optional:file:.env[.properties]   # 环境变量从 .env 导入，不落明文
  profiles:
    active: customerservice                    # 业务配置在 application-customerservice.yaml
```

**`application-customerservice.yaml`（业务配置：DeepSeek / 端口）**

```yaml
spring:
  ai:
    openai:
      base-url: https://api.deepseek.com          # DeepSeek 兼容 OpenAI 协议
      api-key: ${DEEPSEEK_API_KEY}                # 环境变量，不落明文
      chat:
        model: deepseek-chat          # Spring AI 2.0.0：无 options 中缀，参数直挂
server:
  port: 8081
```

### 2.3 `CustomerServiceApplication.java`

```java
package com.shop.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CustomerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
```

### 2.4 `ChatController.java`（WebFlux + SSE 流式）

```java
package com.shop.customer.controller;

import com.shop.customer.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private ChatService chatService;

    // 一次性对话（阻塞 LLM 调用收敛到 boundedElastic，不占 EventLoop）
    @PostMapping("/chat")
    public Mono<String> chat(@RequestParam String sessionId, @RequestBody String message) {
        return chatService.chat(sessionId, message);
    }

    // 流式对话（SSE）
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public Flux<String> chatStream(@RequestParam String sessionId, @RequestBody String message) {
        return chatService.chatStream(sessionId, message);
    }
}
```

### 2.5 `ChatService.java`

```java
package com.shop.customer.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 同步一次性对话：call() 是阻塞调用，收敛到 boundedElastic（WebFlux 铁律）
    public Mono<String> chat(String sessionId, String message) {
        return Mono.fromCallable(() -> chatClient.prompt()
                .user(message)
                .call()
                .content())
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<String> chatStream(String sessionId, String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
```

> 本迭代会话 ID 尚未用（无记忆）；`sessionId` 参数先占位，迭代三接入 ChatMemory 时用它。

### 2.6 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run -Dspring-boot.run.profiles=customerservice
# 测试：
# curl -X POST "http://localhost:8081/api/chat" -H "Content-Type: text/plain" -d "你好"
# curl -X POST "http://localhost:8081/api/chat/stream" -H "Content-Type: text/plain" -d "你好"  # 流式
```

### 2.7 本节测试与验证（工程骨架与依赖）

**前置条件**：JDK 21、Maven 已安装；终端可访问 Maven 中央仓库。

**材料——依赖树与配置核对命令**：

```bash
mvn dependency:tree -Dincludes=org.springframework.ai
mvn help:evaluate -Dexpression=spring-boot.version -q -DforceStdout
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `mvn clean compile` | BUILD SUCCESS，无编译错误（材料 2.1-2.5 全部类可编译） |
| 2 | 材料第一条 | 输出含 `org.springframework.ai:spring-ai-bom:jar:pom:2.0.0 (import)` 与 `spring-ai-starter-model-openai:jar:2.0.0` |
| 3 | 材料第二条 | 输出 `4.1.0` |
| 4 | 检查 `application-customerservice.yaml` | `api-key` 为 `${DEEPSEEK_API_KEY}` 占位符，非明文（铁律 9） |

**失败排查**：①BOM 未生效→`<dependencyManagement>` 位置/版本号笔误；②找不到 starter→本地仓库未同步，`mvn -U` 强制刷新；③`invalid target release`→JDK 非 21，`java -version` 核对。

### 2.8 本节测试与验证（对话与 SSE 流式）

**前置条件**：`DEEPSEEK_API_KEY` 已 export；`mvn spring-boot:run -Dspring-boot.run.profiles=customerservice` 启动成功（8081）。

**材料 A——curl 命令**：

```bash
curl -X POST "http://localhost:8081/api/chat" -H "Content-Type: text/plain" -d "你好"
curl -N -X POST "http://localhost:8081/api/chat/stream" -H "Content-Type: text/plain" -d "你好"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 材料A 第一条 | 200 返回完整中文回答（内容非空非报错） |
| 2 | 材料A 第二条（-N 关缓冲） | SSE 流式收到多个增量 data: 块（≥3 块），非一次性整段 |
| 3 | 发送空字符串 | 明确 4xx/提示，不 500 |

**失败排查**：①401→Key 未注入环境；②一次性整段→Flux 被某层聚合（检查是否误用 block/call().content()）；③500→WebClient 超时（模型服务不可达）；④`/chat` 报阻塞告警→确认已用 `Mono.fromCallable(...).subscribeOn(boundedElastic)`。

## 3. 全篇回归验证

**回归断言**（本篇全部小节验证通过后，最终整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重启应用，先 `/api/chat` 再 `/api/chat/stream` 各一次 | 两种模式均正常，且流式仍为增量输出（data: 块 ≥ 3 块） |
| 2 | 连续 3 次 `/api/chat/stream` | 每次均成功（HTTP 200、无 5xx），无端口占用/连接泄漏报错 |
| 3 | 记录首字延迟 | 同步 `/api/chat` 全量返回 < 3s；流式 `/api/chat/stream` 首个 data: 块 < 1s（TTFT 健康线） |

**失败排查**：重启失败→8081 被占用（`lsof -i:8081` 找残留进程）；流式退化→回查 §2.8 排查项。

## 4. 验收对照

| 验收项 | 目标 | 实测口径 |
|--------|------|---------|
| 工程骨架可编译 | `mvn clean compile` BUILD SUCCESS | §2.7 材料 1-3 全过 |
| 密钥不落明文 | `api-key` 为 `${DEEPSEEK_API_KEY}` 占位 | `grep -n "api-key" application-customerservice.yaml` |
| 同步对话 | 200 返回完整中文回答，内容非空 | §2.8 材料A 第 1 条 |
| SSE 流式 | 增量输出 data: 块 ≥ 3，非一次性整段 | §2.8 材料A 第 2 条 |
| 空串/异常输入 | 明确 4xx/提示，不 500 | §2.8 断言 3 |
| 首字延迟（TTFT） | < 1s（SSE 首帧） | 回归断言 3 |
| 重启稳定性 | 重启后两种模式仍正常 | 回归断言 1 |

> **定位回顾**：本篇搭建基座。下一站 [02-工具集成]——给客服加查 FAQ、查订单、查物流三个工具。
