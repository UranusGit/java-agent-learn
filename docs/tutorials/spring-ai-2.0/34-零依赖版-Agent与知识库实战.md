# 34-零依赖版：Agent 与知识库实战（管数分离 + 多端同步，演进式落地）

> **这份文档是什么**：基于 [34-研究Agent与知识库实战.md](./34-研究Agent与知识库实战.md) 改编的一份**全新实践手册**。它保留了原项目的全部企业级演进脉络（固定 workflow → 自主 Agent → 知识库 → Plan-Execute 并发 → 审计 → 会话持久化 → 多端同步 → 管数分离 → 高可用 → Kafka → 微服务拆分），但把**"外部依赖"全部砍掉**：
>
> - ❌ **不依赖任何外部 LLM 服务**（DeepSeek/OpenAI/通义都不要）—— 用一个**内置模拟 LLM**（`MockLlmClient`），逐字流式吐字，照样驱动 Agent 循环、工具调用、Plan-Execute。换真模型时只换这一个实现。
> - ❌ **不依赖任何数据库**（PostgreSQL/pgvector/MySQL 都不要）—— 会话记忆、知识库、审计日志**全部落 Redis**（Redis 既当缓存又当持久层）。知识库检索用**内存 TF-IDF + Redis 存储**，不需要 embedding、不需要向量库。
> - ❌ **不依赖网页搜索**（Bing/DuckDuckGo 都不要）—— 网页搜索工具换成一个**内置静态知识源**（也落 Redis），接口不变。
> - ✅ **可以依赖 Redis 和 Kafka** —— 这是本文仅有的两个外部中间件。它们是"多端同步 + 管数分离 + 微服务解耦"的基石。
>
> **核心交付目标**（你照着敲完得到的东西）：
> 1. **管数分离**：`POST` 触发（管理面，写）与 `GET` 订阅（数据面，只读）彻底拆成两个接口、两个服务。
> 2. **多页面/多端数据同步展示**：手机、iPad、浏览器三端打开同一会话，看到的流式内容**逐字一致**（Redis Streams 回放 + Pub/Sub 实时广播；演进后期升级 Kafka 消费组）。
> 3. **企业级演进顺序**：每章一个痛点驱动，按"功能演进（0-8）→ 架构演进（9-17）"的节奏一步步推进，**不跳章**。
>
> **技术栈**：Spring Boot 3.3 · Java 21 · WebFlux · 内置 Mock LLM（接口同 Spring AI `ChatClient` 风格）· **Redis**（Streams + Pub/Sub + Hash/List：知识库/记忆/审计/锁的统一存储）· **Kafka**（后期 chunk 持久总线）· Spring Cloud Gateway + Eureka（微服务）。
>
> ⚠️ **版本与可移植**：本文用 Spring Boot 3.3.x（稳定 GA）。Mock LLM 是纯 Java 实现，不随版本变动。Redis/Kafka/Eureka/Gateway 都是成熟稳定组件，命名以你版本官方文档为准。

---

## 目录

- [前言：这份文档的边界与怎么用](#前言这份文档的边界与怎么用)
- [替代方案速查表：原版依赖 → 零依赖版](#替代方案速查表原版依赖--零依赖版)
- [第 0 章：固定 workflow 打底（内置 Mock LLM + 静态知识源）](#第-0-章固定-workflow-打底内置-mock-llm--静态知识源)
- [第 1 章：引入自主 Agent 循环](#第-1-章引入自主-agent-循环)
- [第 2 章：知识库搜索——内存 TF-IDF RAG（落 Redis）](#第-2-章知识库搜索内存-tf-idf-rag落-redis)
- [第 3 章：上线后的运营事故——超时/重试/错误归宿](#第-3-章上线后的运营事故超时重试错误归宿)
- [第 4 章：先规划再调研——Plan 阶段（串行起步）](#第-4-章先规划再调研plan-阶段串行起步)
- [第 5 章：多 Worker 并发调研——把串行变并行](#第-5-章多-worker-并发调研把串行变并行)
- [第 6 章：结构化审计日志——落 Redis，整体流程可追溯](#第-6-章结构化审计日志落-redis整体流程可追溯)
- [第 7 章：会话持久化——ChatMemory 落 Redis，刷新不丢历史](#第-7-章会话持久化chatmemory-落-redis刷新不丢历史)
- [第 8 章：会话管理 CRUD + 前端对话页](#第-8-章会话管理-crud--前端对话页)
- [第 9 章：多端同步流式——企业级多端同步的真实做法](#第-9-章多端同步流式企业级多端同步的真实做法)
- [第 10 章：管数分离——触发与订阅解耦](#第-10-章管数分离触发与订阅解耦)
- [补强 A：传输层真相——SSE / WebSocket / Flux 到底用哪个](#补强-a传输层真相sse--websocket--flux-到底用哪个)
- [补强 B：管数分离的企业级真相——OpenAI Assistants 式的 run 资源](#补强-b管数分离的企业级真相openai-assistants-式的-run-资源)
- [补强 C：多页面同步的端到端证明——A 输出 30% 时 B 打开要看到前 30% 且两页继续一致](#补强-c多页面同步的端到端证明a-输出-30-时-b-打开要看到前-30-且两页继续一致)
- [第 10.5 章：管理数据持久层——引入 H2（开发期零硬件，生产一行配置切 PG）](#第-105-章管理数据持久层引入-h2开发期零硬件生产一行配置切-pg)
- [第 11 章：Redis 高可用——消除单点](#第-11-章redis-高可用消除单点)
- [第 12 章：消息队列升级——Redis Streams → Kafka](#第-12-章消息队列升级redis-streams--kafka)
- [第 13 章：微服务拆分（一）——先拆订阅服务](#第-13-章微服务拆分一先拆订阅服务)
- [第 14 章：微服务拆分（二）——再拆触发服务](#第-14-章微服务拆分二再拆触发服务)
- [第 15 章：微服务拆分（三）——加 API 网关](#第-15-章微服务拆分三加-api-网关)
- [第 16 章：微服务拆分（四）——拆 LLM 网关](#第-16-章微服务拆分四拆-llm-网关)
- [第 17 章：分布式 ChatMemory——拆服务后恢复多轮记忆](#第-17-章分布式-chatmemory拆服务后恢复多轮记忆)
- [附录：项目结构与踩坑手册](#附录项目结构与踩坑手册)

---

## 前言：这份文档的边界与怎么用

### 它讲什么、不讲什么

**讲**：一个**会话化研究问答系统**，管数分离、多端同步展示。Agent 自主决策、查知识库、先规划再并行调研、给出结果；背后是工程化（Agent 循环、RAG、Plan-Execute、审计、会话持久化、Redis 三层广播、Kafka 总线、微服务）。全程**只依赖 Redis + Kafka**，**不连任何 LLM API、不连任何数据库**。

**不讲**（同原版）：
- 完整可观测体系（OpenTelemetry/链路追踪/SSE 推前端看每步）——本文只做最小可追溯（结构化审计日志落 Redis）。
- 多租户与用户体系（JWT/租户隔离）——本文聚焦"管数分离 + 多端同步"主线，单租户单实例起步，分布式阶段做水平扩展。
- 部署运维（k8s/CI-CD）——目标是 IDE/本地 `docker-compose` 能起、能跑通。

### 演进路线（每章一个痛点驱动）

| 阶段 | 痛点（驱动） | 章节 |
|------|------------|------|
| 起点 | 固定步骤能跑通最小研究 | 第 0 章 |
| 开放任务 | 固定步骤应对不了"研究XX" → Agent 自主 | 第 1 章 |
| 资料不够 | 模拟搜索不够 → 查内部知识库（RAG） | 第 2 章 |
| 上线 | 对外运营出事故 → 超时/重试/错误归宿 | 第 3 章 |
| 漏角度 | 隐式 ReAct 无全局规划，复杂主题查不全 → 先 Plan 再 Execute | 第 4 章 |
| 太慢 | 串行调研一个个排队 → 多 Worker 并发 | 第 5 章 |
| 可追溯 | "结论怎么来的"说不清 → 结构化审计日志 | 第 6 章 |
| 记忆 | 刷新就丢、无法多轮追问 → 会话历史落 Redis | 第 7 章 |
| 产品化 | 只有单次研究没法当产品 → 会话 CRUD + 前端 | 第 8 章 |
| 多端同步 | 手机发了，iPad 看不到 → Redis 三层广播 | 第 9 章 |
| **管数分离** | 切换设备会重复触发 → POST 触发 + GET 只读流 | **第 10 章** |
| 高可用 | Redis 单点故障全瘫 → Sentinel 主从 | 第 11 章 |
| 总线升级 | chunk 要跨服务消费/长期保留 → Kafka 消费组 | 第 12 章 |
| 微服务① | SSE 长连接挤爆单进程 → 拆订阅服务 | 第 13 章 |
| 微服务② | 触发(IO)与业务(CPU)资源画像冲突 → 拆触发服务 | 第 14 章 |
| 微服务③ | 前端记一堆端口 → API 网关 + 服务发现 | 第 15 章 |
| 微服务④ | 换 LLM 实现要改业务代码 → 拆 LLM 网关 | 第 16 章 |
| 分布式记忆 | 拆服务后触发服务失忆 → Redis 热缓存 + Redis 兜底 | 第 17 章 |

> **架构演进（第 9 章起）**：前 8 章是"功能演进"（原型→产品）；第 9 章起进入"架构演进"——把"能上线的单体"一步步推向"分布式企业级终极形态"，**管数分离（第 10 章）是架构演进的核心**。
>
> **演进纪律**：没有稳定的单次 Agent，会话化只会把不稳定放大 N 倍。**顺序不要跳**。

### 复现约定（照着敲的铁律）

- **演进铁律**：每一章只引入本章真正用到的依赖、配置、代码——后面才用到的，一律不提前搬。
- **代码文件：完整版覆盖**。每个 Java 文件都是**完整的、带 import 的、照抄能编译的**。
- **配置文件：增量片段**。`pom.xml`/`application.yaml` 第 0 章给初始完整版，之后每章只贴"本章追加的片段"。
- **改动锚点**：改已有文件用注释 `// ▼ 第X章新增` / `// ✦ 第X章替换` 标出。
- **简陋处会标注**：第一版先写简单版，后面章节改进时一定标注"这一版简陋，第 X 章会改"。
- **每章结尾有 checkpoint**：目录结构 + git 提交命令。

---

## 替代方案速查表：原版依赖 → 零依赖版

| 原版依赖 | 零依赖版替代 | 替代品定位 | 何时引入 |
|---------|------------|----------|---------|
| DeepSeek/OpenAI（外部 LLM） | **`MockLlmClient`**（内置，逐字流式吐字，支持工具调用循环） | LLM 抽象接口的实现，换真模型只换这一个 | 第 0 章 |
| Bing 网页搜索 | **`WebSearchTool`（内置静态知识源）** | 工具接口不变，数据源换成内置条目 | 第 0 章 |
| PostgreSQL + pgvector（知识库向量库） | **内存 TF-IDF + Redis 存储**（`KnowledgeBaseTool`） | 不需要 embedding，纯关键词加权检索 | 第 2 章 |
| PostgreSQL（ChatMemory 会话存储） | **Redis List/Hash** | Redis 既当热缓存也当持久层 | 第 7 章 |
| PostgreSQL（审计日志） | **Redis List**（按会话串联） | 结构化 JSON 落 Redis | 第 6 章 |
| Redis Streams（chunk 总线） | 保留（**就是 Redis**） | 多端同步的持久层 | 第 9 章 |
| —（原版没有） | **Kafka**（chunk 持久总线升级） | 跨服务消费/长期保留 | 第 12 章 |
| Sentinel/Cluster | 保留 | Redis 高可用 | 第 11 章 |
| Eureka/Gateway | 保留 | 服务发现 + 统一入口 | 第 15 章 |

> **为什么 Mock LLM 是核心**：Agent 系统（循环、工具调用、Plan-Execute）的"智能"体现在**编排逻辑**，不在 LLM 本身。Mock LLM 让你能**离线、零成本、可复现**地验证全部编排逻辑——这是本文能做到"不依赖外部服务"的关键。生产时把 `MockLlmClient` 换成真 `ChatClient`，**业务代码一行不动**（接口同构）。

---


## 第 0 章：固定 workflow 打底（内置 Mock LLM + 静态知识源）

### 0.0 场景

你要做一个"研究助手"：用户输入一个主题，系统去**查资料**，基于资料**给出研究结果**。本章先用**固定 workflow**（提炼关键词 → 搜资料 → 生成结果）跑通最小版——**先把业务跑通，再谈自主**。

零依赖版的关键：LLM 用内置 `MockLlmClient`（逐字吐字），搜索用内置静态知识源。**全程不连任何外部服务**。

### 0.1 思路

| 决策 | 选择 | 理由 |
|------|------|------|
| LLM | 内置 `MockLlmClient` | 不依赖外部 API；接口与 Spring AI `ChatClient` 同构，换真模型只换实现 |
| 搜索资料 | 内置 `WebSearchTool`（静态知识源） | 不依赖网页抓取；返回关键词命中的内置条目，第 2 章升级成知识库 RAG |
| 可见性 | 先用日志 | 第 0 章痛点小（等待时不知在干嘛），日志够透光 |

### 0.2 动手

本章是**建项目**，所有文件都是新建。

#### 0.2.1 建主项目 `research-agent` + pom

```
research-agent/
└── pom.xml
```

**【新建文件】** `research-agent/pom.xml`：

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
        <version>3.3.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>research-agent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>research-agent</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!--
          第 0 章只引两个依赖：
            webflux —— Web 栈基础（Controller、SSE 流式都靠它）
          零依赖版不引 spring-ai：用内置 MockLlmClient 代替，不连任何 LLM API。
          演进纪律：后续章节用到了再加——
            第 2 章知识库落 Redis（已够，无需新依赖）；第 9 章加 data-redis-reactive（多端同步总线）。
        -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> **为什么第 0 章连 Redis 都不引**：演进铁律——本章还不需要 Redis（多端同步是第 9 章的事）。知识库、会话、审计都还没出现。第 0 章只验证"固定 workflow + Mock LLM 流式"能跑。

#### 0.2.2 启动类

**【新建文件】** `research-agent/src/main/java/com/example/research/Application.java`：

```java
package com.example.research;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 研究问答系统启动类（零依赖版）。
 * 扫描 com.example.research 及其子包（llm/tool/kb/plan/audit/session/stream）。
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

#### 0.2.3 配置文件

**【新建文件】** `research-agent/src/main/resources/application.yaml`：

```yaml
server:
  port: 8080
# 零依赖版：没有 spring.ai、没有 datasource、没有 redis。
# Mock LLM 行为由 Java 代码控制（见 MockLlmClient）。
# 第 9 章才会在这里加 spring.data.redis（多端同步总线）。
```

#### 0.2.4 LLM 抽象接口（核心：换实现不换业务）

这是零依赖版的**灵魂**——一个与 Spring AI `ChatClient` 同构的接口，让业务代码不知道背后是真模型还是 Mock。

**【新建文件】** `research-agent/src/main/java/com/example/research/llm/LlmClient.java`：

```java
package com.example.research.llm;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 客户端抽象接口（零依赖版核心抽象）。
 *
 * 为什么要有这个接口：
 *   业务代码（ResearchService 等）只依赖这个接口，不依赖任何具体 LLM。
 *   - 第 0-17 章用 MockLlmClient（内置、离线、零成本）。
 *   - 生产时换成真实现（new OpenAiLlmClient(...) 或适配 Spring AI ChatClient），
 *     业务代码一行不动——这就是"管数分离 + 解耦"在 LLM 层的体现。
 *
 * 方法语义对齐 Spring AI ChatClient：
 *   - chat(system, user)           ≈ prompt().system().user().call().content()
 *   - chat(system, user, history)  ≈ 带 MessageChatMemoryAdvisor 的多轮调用
 *   - stream(system, user, tools)  ≈ .stream().content() + .tools()
 *
 * 工具调用（tools）：Mock 通过关键词匹配模拟"模型决定调哪个工具"，
 *   真实模型靠 function calling。接口对调用方透明。
 */
public interface LlmClient {

    /** 单轮（非流式）：用于"提炼关键词"这类一次性任务。 */
    String chat(String system, String user);

    /** 单轮带历史（多轮记忆）：history 是过往 [user, assistant, user, assistant ...] 序列。 */
    String chat(String system, String user, List<LlmMessage> history);

    /**
     * 流式：逐字吐出结果（Flux<String>，每个元素是一个 chunk）。
     * tools：可选，Mock 会按 system/user 内容决定是否"调用"工具，并把它拼进结果。
     * 这是对 Agent 循环（第 1 章）最关键的方法。
     */
    Flux<String> stream(String system, String user, List<LlmMessage> history, List<LlmTool> tools);

    /** 一条消息（user 或 assistant），对齐 ChatMemory 的 Message 概念。 */
    record LlmMessage(String role, String content) {}

    /** 工具描述：name + 什么时候用的说明（对齐 @Tool 的 description）。 */
    record LlmTool(String name, String description,
                   java.util.function.Function<String, String> invoke) {}
}
```

> **这个接口为什么是"换实现不换业务"的关键**：注意 `stream(...)` 的签名——`system + user + history + tools`。这正是 Spring AI `ChatClient.prompt().system().user().advisors(记忆).tools(工具).stream().content()` 的扁平化表达。Mock 用它驱动 Agent 循环，真模型用它驱动真实 function calling。**ResearchService 只认 `LlmClient`**——这就是第 16 章"拆 LLM 网关"能在零依赖版照样成立的基础。

#### 0.2.5 MockLlmClient：内置、逐字流式、模拟工具调用

**【新建文件】** `research-agent/src/main/java/com/example/research/llm/MockLlmClient.java`：

```java
package com.example.research.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内置 Mock LLM（零依赖版：不连任何 LLM API）。
 *
 * 行为：
 *   1. chat()（非流式）：返回一句基于 user + history 的"提炼/总结"。
 *   2. stream()（流式）：把一段"研究结果"拆成字符，逐字吐出（每字 30-80ms，模拟真实打字）。
 *   3. 工具调用模拟：如果 tools 非空，且 user 里出现工具 description 命中的关键词，
 *      就在结果里插入"[调用工具 X] 摘要内容"，模拟"模型决定调工具并拿到结果"。
 *
 * 简陋版（第 0 章刻意如此）：
 *   - 工具调用是"关键词命中即调"，不是真正的多步 ReAct 循环。第 1 章用 ToolCallingLoop 做真循环。
 *   - 研究结果文本是模板拼接，不是真模型生成。目的是让编排逻辑能离线跑通。
 *   这些是演进素材，后续章节按需增强。
 */
@Component
public class MockLlmClient implements LlmClient {

    /** 非流式：提炼关键词 / 简短回答。 */
    @Override
    public String chat(String system, String user) {
        // 简单"提炼"：取 user 里出现频率高的词当作关键词
        if (system != null && system.contains("关键词")) {
            return extractKeywords(user);
        }
        return "（Mock 回答）关于「" + user + "」：这是一个用于离线验证编排逻辑的模拟回答。";
    }

    /** 带历史的非流式：把历史最近一条拼进去，模拟"记得上文"。 */
    @Override
    public String chat(String system, String user, List<LlmMessage> history) {
        String prev = (history == null || history.isEmpty())
                ? "" : "（接上文：" + history.get(history.size() - 1).content() + "）";
        return prev + chat(system, user);
    }

    /**
     * 流式：逐字吐出研究结果。tools 非空时模拟"调用工具拿资料再总结"。
     * 第 0 章固定 workflow 不传 tools（.stream(system,user,null,List.of())），
     * 但本方法已支持工具，第 1 章直接复用。
     */
    @Override
    public Flux<String> stream(String system, String user, List<LlmMessage> history, List<LlmTool> tools) {
        // 1. 如果有工具且 user 命中工具描述，模拟调用工具
        StringBuilder materials = new StringBuilder();
        if (tools != null) {
            for (LlmTool tool : tools) {
                if (user != null && keywordHit(user, tool)) {
                    String result = tool.invoke().apply(user);
                    materials.append("[已调用工具 ").append(tool.name()).append("]\n")
                            .append(result).append("\n\n");
                }
            }
        }

        // 2. 拼"研究结果"文本（模板，非真生成）
        String answer = buildAnswer(user, history, materials.toString());

        // 3. 拆成字符逐字吐出（模拟流式打字）
        return charByChar(answer);
    }

    // —— 私有辅助 ——

    private String buildAnswer(String user, String historyContext, String materials) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 研究结果：").append(user == null ? "（未指定主题）" : user).append("\n\n");
        if (!materials.isEmpty()) {
            sb.append("### 参考资料\n").append(materials);
        }
        sb.append("### 结论\n");
        sb.append("基于上述信息（零依赖版 Mock 生成，仅供验证编排逻辑）：")
          .append(user == null ? "" : "「").append(user == null ? "" : user)
          .append(user == null ? "" : "」是一个值得深入研究的主题。");
        if (historyContext != null && !historyContext.isEmpty()) {
            sb.append("结合上文，可以进一步展开追问。");
        }
        sb.append("\n\n（流式结束）");
        return sb.toString();
    }

    /** 把字符串拆成字符，每字随机延迟 30-80ms 逐个发出——模拟真实流式打字体验。 */
    private Flux<String> charByChar(String text) {
        String[] chars = text.split("");
        return Flux.create(sink -> {
            new Thread(() -> {
                try {
                    for (String c : chars) {
                        sink.next(c);
                        Thread.sleep(ThreadLocalRandom.current().nextInt(30, 80));
                    }
                    sink.complete();
                } catch (InterruptedException e) {
                    sink.error(e);
                }
            }, "mock-llm-stream").start();
        });
    }

    /** 关键词提炼：把 user 按空格/标点拆词，取较长的几个当关键词。 */
    private String extractKeywords(String user) {
        if (user == null) return "关键词";
        String[] words = user.split("[\\s，。、,\\.]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.length() >= 2) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(w);
            }
        }
        return sb.isEmpty() ? user.trim() : sb.toString();
    }

    /** 工具命中判断：user 里出现工具 name 或 description 里的关键词。 */
    private boolean keywordHit(String user, LlmTool tool) {
        String u = user.toLowerCase();
        return u.contains(tool.name().toLowerCase())
                || (tool.description() != null
                    && containsAny(u, tool.description().toLowerCase().split("[，,。\\s]+")));
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String k : keywords) {
            if (k.length() >= 2 && text.contains(k)) return true;
        }
        return false;
    }
}
```

> **`charByChar` 为什么用裸 Thread + Flux.create**：模拟真实流式必须有真实的时间流逝（不能用 `Flux.interval` 平铺，否则字符是固定间隔，不自然）。第 0 章先这样；第 9 章会看到 SSE 心跳与这种流的协作。**简陋处**：裸线程没有取消传播——第 9 章加固③会处理。

#### 0.2.6 网页搜索工具（内置静态知识源）

接口与原版一致（`search(query) → Mono<String>`），但数据源是内置条目，不抓网页。

**【新建文件】** `research-agent/src/main/java/com/example/research/tool/WebSearchTool.java`：

```java
package com.example.research.tool;

import com.example.research.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网页搜索工具（零依赖版：内置静态知识源）。
 * 接口与原版一致：search(query) → Mono<String>。
 * 数据源换成内置条目（List），按关键词匹配返回——不抓任何网页。
 *
 * 第 0 章固定 workflow 里手动调；第 1 章注册给 LlmClient 自主调；
 * 第 2 章再加一个 KnowledgeBaseTool（知识库 RAG）。
 */
@Component
public class WebSearchTool {

    // 内置静态知识源：演示用。生产换成 Tavily API / 真网页抓取，接口不变。
    private static final List<String> STATIC_SNIPPETS = List.of(
        "2026年大模型推理框架：vLLM 凭借 PagedAttention 持续领先，吞吐量行业第一。",
        "TensorRT-LLM 是 NVIDIA 推出的推理框架，与 GPU 深度绑定，延迟最低。",
        "SGLang 主打结构化生成与 RadixAttention，适合复杂工具调用场景。",
        "TGI（Text Generation Inference）由 HuggingFace 出品，部署简单、社区活跃。",
        "推理框架的选型取决于吞吐、延迟、硬件生态、易用性的权衡。"
    );

    /** 按关键词匹配内置条目，返回前 5 条摘要。失败返回空，不让 Agent 崩。 */
    public Mono<String> search(String query) {
        return Mono.fromSupplier(() -> {
            if (query == null || query.isBlank()) return "（搜索无结果）";
            String q = query.toLowerCase();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String s : STATIC_SNIPPETS) {
                if (s.toLowerCase().contains(someKeywordOf(q)) || containsAnyWord(s.toLowerCase(), q)) {
                    sb.append("- ").append(s).append("\n");
                    if (++count >= 5) break;
                }
            }
            return sb.isEmpty() ? "（搜索无结果，内置知识源未命中。可尝试换个关键词。）" : sb.toString();
        }).onErrorResume(e -> Mono.just("（搜索失败）"));
    }

    /** 作为 LlmTool 注册时用：输入是 user 文本，直接当 query 搜。 */
    public String invokeAsTool(String userInput) {
        return search(userInput).block();   // 工具同步返回字符串（第 1 章在循环里用）
    }

    /** 构造一个可注册给 LlmClient 的工具描述。 */
    public LlmClient.LlmTool asTool() {
        return new LlmClient.LlmTool(
                "web_search",
                "在互联网上搜索给定关键词，返回网页摘要。用于查询最新、需要核实的信息。",
                this::invokeAsTool);
    }

    private String someKeywordOf(String q) {
        String[] parts = q.split("\\s+");
        return parts.length > 0 ? parts[0] : q;
    }

    private boolean containsAnyWord(String text, String q) {
        for (String w : q.split("\\s+")) {
            if (w.length() >= 2 && text.contains(w)) return true;
        }
        return false;
    }
}
```

#### 0.2.7 固定 workflow：提炼关键词 → 搜索 → 研究结果

固定三步，全程流式：

**【新建文件】** `research-agent/src/main/java/com/example/research/ResearchService.java`：

```java
package com.example.research;

import com.example.research.llm.LlmClient;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 第 0 章：固定 workflow（提炼关键词 → 搜资料 → 流式生成结果）。
 *
 * 第一步让 LLM 把主题提炼成关键词；
 * 第二步手动调 searchTool.search(关键词) 拿资料（Mono<String>）；
 * 第三步把资料喂给 LLM 流式生成结果（Flux<String>）。
 *
 * 第 1 章会让 LLM 自主决定几步（Agent 循环）。
 */
@Service
public class ResearchService {

    private final LlmClient llm;
    private final WebSearchTool searchTool;

    public ResearchService(LlmClient llm, WebSearchTool searchTool) {
        this.llm = llm;
        this.searchTool = searchTool;
    }

    /** 固定 workflow（流式）：① 提炼关键词 ② 搜资料 ③ 基于资料流式生成。 */
    public Flux<String> research(String topic) {
        // 第一步：提炼关键词（非流式 chat）
        System.out.println("[研究] 提炼关键词: " + topic);
        String searchQuery = llm.chat(
                "你是搜索关键词提炼助手。根据用户主题输出最适合搜索引擎的关键词，只输出关键词本身。",
                topic);
        System.out.println("[研究] 关键词: " + searchQuery);

        // 第二步 + 第三步：搜索（Mono<String>）→ 基于资料流式生成（Flux<String>）
        return searchTool.search(searchQuery)
                .doOnNext(m -> System.out.println("[研究] 搜索完成，开始生成..."))
                .flatMapMany(materials -> llm.stream(
                        "你是研究助理。基于资料给出结构清晰的研究结果。资料不足要明确说，绝不编造。",
                        "研究主题：" + topic,
                        List.of(),
                        List.of()))   // 第 0 章不传工具（固定 workflow 手动调搜索）
                .doOnComplete(() -> System.out.println("[研究] 生成完成"));
    }
}
```

#### 0.2.8 接口（流式 SSE）

**【新建文件】** `research-agent/src/main/java/com/example/research/ResearchController.java`：

```java
package com.example.research;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 研究接口 Controller。第 0 章就是流式：GET /api/research，Flux<String> + SSE。
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchService researchService;

    public ResearchController(ResearchService researchService) {
        this.researchService = researchService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> research(@RequestParam String topic) {
        return researchService.research(topic);
    }
}
```

### 0.3 验证

```bash
mvn spring-boot:run

# 流式请求（-N 关闭缓冲，能看到结果逐字出现）
curl -N "http://localhost:8080/api/research?topic=2026年大模型推理框架"
```

预期：研究结果**逐字流式输出**（Mock LLM 每 30-80ms 吐一个字）。控制台能看到 `[研究]` 日志。

### 0.4 checkpoint

```
research-agent/
├── pom.xml
└── src/main/
    ├── java/com/example/research/
    │   ├── Application.java
    │   ├── ResearchService.java        # 固定 workflow
    │   ├── ResearchController.java     # REST 接口
    │   ├── llm/
    │   │   ├── LlmClient.java          # LLM 抽象接口（核心）
    │   │   └── MockLlmClient.java      # 内置 Mock 实现
    │   └── tool/
    │       └── WebSearchTool.java      # 内置静态知识源搜索
    └── resources/
        └── application.yaml
```

```bash
git add -A && git commit -m "第0章：固定workflow + 内置Mock LLM + 静态知识源（零外部依赖）"
```

### 0.5 复盘

**做了**：固定 workflow 跑通；`LlmClient` 抽象 + `MockLlmClient` 实现（**零依赖 LLM 的基石**）；内置静态知识源搜索；最小日志可见性。

**核心**：`LlmClient` 接口让"业务编排"与"LLM 实现"解耦——这是零依赖版能做到"换真模型不动业务"的根本，也是第 16 章"LLM 网关"的雏形。

**还差**：
- 固定步骤应对不了开放任务（要搜多次）→ **第 1 章自主 Agent**
- 搜索信息不够 → 查内部知识库 → **第 2 章 RAG**
- 上线后超时/错误没归宿 → **第 3 章**

---

## 第 1 章：引入自主 Agent 循环

### 1.0 场景：固定步骤不够用了

第 0 章上线几天，用户反馈："对比 A 和 B 框架"——系统只搜了一次，结果对 B 一笔带过。**固定步骤应对不了开放任务**。

**Agent 和 workflow 的本质区别**：workflow 人写死步骤；Agent **LLM 自己决定下一步**（要不要再搜？搜什么？够了没？）。

### 1.1 思路：先"让 LLM 能调工具"，再"让它循环调"

零依赖版没有 Spring AI 的 `ToolCallingAdvisor` 自动循环。我们不直接写循环，而是**分两步演进**：
1. **1.2 最小版**：先把工具"注册给 LLM"——`WebSearchTool.asTool()` 告诉 LLM 有什么工具、Mock 在 `stream()` 内模拟"命中即调"。**先验证"工具被调了、结果进了输出"**，这一步还没有循环。
2. **1.4 引入循环骨架**：发现"开放任务可能要搜多次"的痛点，才把单次调用包进 `ToolCallingLoop`（带 `maxIterations` 防死循环）。**为真模型的 function-calling 多轮留好骨架**。

```
最小版：   LLM.stream(带工具) → Mock 内部"命中即调" → 单轮出结果
演进版：   ToolCallingLoop { 调 LLM → 解析 tool_call → 执行 → 喂回 → 再调 } 直到无 tool_call
```

### 1.2 动手（最小版）：把工具注册给 LLM

#### 1.2.1 WebSearchTool.asTool()：把工具描述给 LLM

第 0 章 `WebSearchTool` 已有 `search(query)`。现在加一个 `asTool()`——把"工具名 + description + 调用入口"打包成 `LlmClient.LlmTool`，注册给 LLM。**这一步只让 LLM"知道有这个工具、能调"，还没有循环。**

**【改已有文件，片段】** `WebSearchTool.java` 加方法（第 0 章已有 `search`/`invokeAsTool`，这里加 `asTool`）：

```java
/** 构造一个可注册给 LlmClient 的工具描述。 */
public LlmClient.LlmTool asTool() {
    return new LlmClient.LlmTool(
            "web_search",
            "在互联网上搜索给定关键词，返回网页摘要。用于查询最新、需要核实的信息。",
            this::invokeAsTool);
}
```

> Mock LLM 在 `stream(system, user, history, tools)` 里会**按 user 内容是否命中工具 description 的关键词**，决定调不调、把结果拼进输出（见 `MockLlmClient`）。**这一步已经实现了"工具被自主调用"的表象**——但本质是单轮，因为 Mock 一次性把"调工具+拼结果"做完了。

#### 1.2.2 ResearchService：注册工具（先不引循环）

**【改已有文件，完整版覆盖】** `ResearchService.java`（第 1 章最小版——把工具传给 LLM，还没有 ToolCallingLoop）：

```java
package com.example.research;

import com.example.research.llm.LlmClient;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 第 1 章最小版：把 web_search 注册给 LLM。
 * LLM 在 stream() 内自主决定调不调工具（Mock 命中即调）。这一步还没循环。
 */
@Service
public class ResearchService {

    private final LlmClient llm;
    private final WebSearchTool searchTool;

    public ResearchService(LlmClient llm, WebSearchTool searchTool) {
        this.llm = llm;
        this.searchTool = searchTool;
    }

    public Flux<String> research(String topic) {
        return llm.stream(
                "你是研究助理。你可以调用 web_search 工具查资料。" +
                "资料足够后给出结构清晰的研究结果。资料不足要明确说，绝不编造。",
                "研究主题：" + topic,
                List.of(),
                List.of(searchTool.asTool()))               // ▼ 注册工具给 LLM
                .timeout(Duration.ofSeconds(60));
    }
}
```

### 1.3 验证最小版

```bash
curl -N "http://localhost:8080/api/research?topic=对比vLLM和TensorRT-LLM的发展"
```

预期：Mock 命中"搜索/查询"关键词 → 调 `web_search` → 内置条目命中 → 摘要拼进结果逐字输出。

### 1.4 最小版的隐患 → 引入循环骨架

**隐患（驱动演进）**：最小版的"自主调工具"是**单轮假象**——Mock 一次性把"调工具+拼结果"做完。但真实 Agent 是**循环**：调一次工具看结果够不够，不够再调一次（换关键词、核实矛盾）。而且：
- **没有循环骨架**：换真模型（function calling）时，需要"解析 tool_call → 执行 → 喂回 → 再调"，最小版没地方放这套逻辑。
- **没有防死循环**：真模型可能无限调工具，需要 `maxIterations` 截断。

**解法**：把单次 `llm.stream(...)` 包进 `ToolCallingLoop`。Mock 下它仍是单轮（因为 Mock 内部已做完），但**循环骨架（round 计数、maxIterations、history 累积）已经在**——换真模型时只补 tool_call 解析。

#### 1.4.1 ToolCallingLoop：循环骨架（为真模型多轮留接口）

**【新建文件】** `research-agent/src/main/java/com/example/research/llm/ToolCallingLoop.java`：

```java
package com.example.research.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具调用循环骨架。
 *
 * 循环本质：调 LLM → 解析 tool_call → 执行工具 → 把结果作为 history 喂回 → 再调 LLM
 *          → 直到 LLM 不再请求工具（给出最终答案）。
 *
 * Mock 下：MockLlmClient.stream() 内部已把"调工具+拼结果"做完，所以本循环对 Mock 退化为单轮。
 * 真模型下：runRound 解析 tool_calls，执行后递归 runRound(round+1)——多轮。
 *
 * maxIterations 防死循环（真模型可能无限调工具）。
 */
@Component
public class ToolCallingLoop {

    private static final int MAX_ITERATIONS = 5;

    private final LlmClient llm;

    public ToolCallingLoop(LlmClient llm) {
        this.llm = llm;
    }

    public Flux<String> run(String system, String user,
                            List<LlmClient.LlmMessage> history,
                            List<LlmClient.LlmTool> tools) {
        List<LlmClient.LlmMessage> hist = history == null ? new ArrayList<>() : new ArrayList<>(history);
        return runRound(system, user, hist, tools, 0);
    }

    private Flux<String> runRound(String system, String user,
                                  List<LlmClient.LlmMessage> history,
                                  List<LlmClient.LlmTool> tools, int round) {
        if (round >= MAX_ITERATIONS) {                                   // ▼ 防死循环
            return Flux.just("\n[达到最大轮次 " + MAX_ITERATIONS + "，停止]");
        }
        return llm.stream(system, user, history, tools);
        // Mock：单轮结束。真模型：解析 tool_calls → 执行 → history.add(工具结果) → runRound(round+1)
        // 多轮扩展点见附录（真实 function-calling 解析）。
    }
}
```

#### 1.4.2 ResearchService：改用 ToolCallingLoop

**【改已有文件，完整版覆盖】** `ResearchService.java`（把 `llm.stream` 换成 `agentLoop.run`）：

```java
package com.example.research;

import com.example.research.llm.ToolCallingLoop;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 第 1 章演进版：用 ToolCallingLoop 托管工具调用。
 * 演进：第0章固定workflow → 第1章最小版(注册工具) → 第1章演进版(循环骨架)。
 * 后续：第2章加知识库工具；第7章传 history 接入记忆。
 */
@Service
public class ResearchService {

    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;

    public ResearchService(ToolCallingLoop agentLoop, WebSearchTool searchTool) {
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
    }

    public Flux<String> research(String topic) {
        return agentLoop.run(
                "你是研究助理。你可以调用 web_search 工具查资料。" +
                "自主决定搜索几次、搜什么关键词。资料矛盾时多搜一轮核实。" +
                "资料足够后给出结构清晰的研究结果。绝不编造。",
                "研究主题：" + topic,
                List.of(),
                List.of(searchTool.asTool()))
                .timeout(Duration.ofSeconds(60));
    }
}
```

### 1.5 checkpoint + 复盘

```
research-agent/src/main/java/com/example/research/llm/
└── ToolCallingLoop.java          （新增：循环骨架）
research-agent/.../ResearchService.java   （改：先注册工具 → 再用 ToolCallingLoop）
```

```bash
git add -A && git commit -m "第1章：自主Agent（先注册工具，再演进到循环骨架）"
```

**做了**：先 `asTool()` 把工具注册给 LLM（最小版，验证工具能被调）→ 发现"单轮假象/无防死循环"痛点 → 引入 `ToolCallingLoop` 循环骨架（为真模型多轮留接口）。

**核心跃迁**：从"人写死步骤"到"LLM 自主决定下一步"。循环骨架（决定→执行→喂回→再决定）裸露可见，换真模型只补 tool_call 解析。**这是演进式：先让工具能用，再为循环留位。**

---


## 第 2 章：知识库搜索——内存 TF-IDF RAG（落 Redis）

### 2.0 场景：搜索信息不够/不准

第 1 章的 `web_search` 只命中内置静态条目，覆盖面窄。研究企业内部的事（比如"本公司的部署规范"）根本搜不到——**需要查内部知识库**。这就是 RAG 的驱动点。

但零依赖版**不能用 pgvector、不能调外部 embedding API**。怎么在不依赖向量库的前提下做 RAG？

### 2.1 思路：内存 TF-IDF 检索 + Redis 存储

| 决策 | 选择 | 理由 |
|------|------|------|
| 向量化 | **不做**（不要 embedding） | 零依赖：不调外部 embedding API、不要 pgvector |
| 检索算法 | **TF-IDF + 余弦相似度**（纯 Java） | 纯内存计算，零外部依赖；关键词加权检索，对"研究"类任务够用 |
| 文档存储 | **Redis**（Hash 存文档，第 7 章起 Redis 进来） | 不依赖数据库；Redis 既存知识库也（后续）存会话/审计 |
| 分词 | 简单 n-gram + 空格分词 | 中文不做重型分词，用字/词 n-gram 兜底 |

> **为什么 TF-IDF 而不是向量检索**：向量检索需要 embedding 模型（外部服务）或本地模型（重依赖）。TF-IDF 是纯线性代数，几十行 Java 就能跑，对"基于关键词的研究问答"质量足够。**这是"不依赖外部服务"的必然选择**。换向量检索时，`KnowledgeBaseTool` 的接口不变，只换 `retrieve()` 实现——和第 16 章换 LLM 同构。

### 2.2 动手

本章引 Redis（`data-redis-reactive`）、建 3 文件（`KnowledgeBaseDoc`、`TfidfIndex`、`KnowledgeBaseTool`）、改 `ResearchService`（多注册一个工具）。从此 Redis 成为项目的统一存储。

#### 2.2.1 pom 加依赖

**【改已有文件】** `pom.xml`，追加：

```xml
        <!-- 第 2 章：响应式 Redis（知识库存储；后续会话/审计/多端同步都用它） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
        </dependency>
```

#### 2.2.2 application.yaml：Redis 连接

**【改已有文件】** `application.yaml`，追加：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 5s
```

#### 2.2.3 RedisConfig：ReactiveRedisTemplate + 监听容器 + 初始化知识库

**【新建文件】** `research-agent/src/main/java/com/example/research/config/RedisConfig.java`：

```java
package com.example.research.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 * - ReactiveRedisTemplate<String,String>：字符串键值（知识库/会话/审计/锁/流都用它）。
 * - ReactiveRedisMessageListenerContainer：第 9 章多端同步的 Pub/Sub 监听器（提前建好，免得第 9 章启动报错）。
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer s = StringRedisSerializer.UTF_8;
        RedisSerializationContext<String, String> ctx = RedisSerializationContext
                .<String, String>newSerializationContext(s).key(s).value(s).hashKey(s).hashValue(s).build();
        return new ReactiveRedisTemplate<>(factory, ctx);
    }

    // 第 9 章会用，这里先建好 bean，避免到时候忘了。
    @Bean
    public ReactiveRedisMessageListenerContainer listenerContainer(ReactiveRedisConnectionFactory factory) {
        return new ReactiveRedisMessageListenerContainer(factory);
    }
}
```

#### 2.2.4 知识库文档模型 + 最朴素检索（先跑通，不讲算法）

> **演进纪律**：知识库检索**不**一上来就 TF-IDF。先给**最朴素的"逐词包含匹配"**——能存文档、能按关键词命中返回，先让 RAG 链路跑通。然后 2.6-2.9 按"一个痛点 → 一处升级"逐步演进：先发现"高频词淹没关键词"→才加 IDF；先发现"长短文档不公平"→才加余弦归一化；先发现"中文搜不到"→才加 2-gram。**每节只引入一个概念。**

先建文档模型 `KbDoc`（极简 JSON，不引 Jackson，零依赖）+ 最朴素的 `TfidfIndex`（这版其实只做"包含匹配"，名字先占着，后面才长成 TF-IDF）。

**【新建文件】** `research-agent/src/main/java/com/example/research/kb/TfidfIndex.java`（最朴素版）：

```java
package com.example.research.kb;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库索引（最朴素版：逐词包含匹配）。
 *
 * 这版只做：把 query 按空格拆词，统计每个文档命中了几个词，按命中数排序。
 * 先让 RAG 链路跑通（存文档 + 按词命中检索），不讲算法。
 *
 * ⚠️ 刻意简陋（后面 2.6-2.9 逐个补）：
 *   - 没有 IDF："大模型/推理"这种每篇都有的高频词，命中数虚高，淹没真正关键词 → 2.6 补
 *   - 没有归一化：长文档天然命中更多词，排名虚高 → 2.7 补（余弦）
 *   - 中文按空格拆不开："vLLM是什么"拆不出有意义的词 → 2.8 补（2-gram）
 *   - 没有 topK 截断和 0 命中过滤 → 2.9 补
 * 文档存 Redis（Hash: kb:docs），ID 自增（kb:seq）。不依赖数据库。
 */
@Component
public class TfidfIndex {

    private static final String KEY_DOCS = "kb:docs";
    private static final String KEY_SEQ  = "kb:seq";

    private final ReactiveRedisTemplate<String, String> redis;

    public TfidfIndex(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /** 载入全部文档。 */
    public Mono<List<KbDoc>> loadAll() {
        return redis.opsForHash().values(KEY_DOCS)
                .map(o -> KbDoc.fromJson(o.toString()))
                .collectList();
    }

    /** 新增一篇文档，返回 id。 */
    public Mono<String> add(String title, String content) {
        return redis.opsForValue().increment(KEY_SEQ)
                .map(id -> new KbDoc("doc" + id, title, content))
                .flatMap(doc -> redis.opsForHash().put(KEY_DOCS, doc.id(), doc.toJson())
                        .thenReturn(doc.id()));
    }

    /**
     * 检索（最朴素版）：query 按空格拆词，统计每篇文档命中几个词，按命中数降序。
     */
    public Mono<List<KbDoc>> retrieve(String query, int topK) {
        return loadAll().map(docs -> {
            List<String> terms = List.of(query == null ? new String[0] : query.split("\\s+"));
            return docs.stream()
                    .map(d -> Map.entry(d, countHits(d, terms)))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        });
    }

    /** 命中数：文档里包含了 query 的几个词。 */
    private long countHits(KbDoc d, List<String> terms) {
        String text = (d.title() + " " + d.content()).toLowerCase();
        return terms.stream().filter(t -> !t.isBlank() && text.contains(t.toLowerCase())).count();
    }

    /** 知识库文档（极简 JSON 序列化，零外部依赖）。 */
    public record KbDoc(String id, String title, String content) {
        public String toJson() {
            return "{\"id\":\"" + id + "\",\"title\":\"" + esc(title)
                    + "\",\"content\":\"" + esc(content) + "\"}";
        }
        static KbDoc fromJson(String json) {
            return new KbDoc(extract(json, "id"), extract(json, "title"), extract(json, "content"));
        }
        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
        private static String extract(String json, String key) {
            String k = "\"" + key + "\":\"";
            int start = json.indexOf(k);
            if (start < 0) return "";
            start += k.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(++i);
                    sb.append(n == 'n' ? '\n' : n);
                } else if (c == '"') break;
                else sb.append(c);
            }
            return sb.toString();
        }
    }
}
```

> **为什么先这么朴素**：RAG 的价值是"Agent 能查到相关文档喂给 LLM"。第一步只要"存得下、查得到"——检索质量先不追求。**先把链路打通，再迭代质量**，符合企业项目"先让它 work，再让它 better"的真实节奏。

#### 2.2.5 KnowledgeBaseTool：注册给 LLM 的知识库工具

**【新建文件】** `research-agent/src/main/java/com/example/research/kb/KnowledgeBaseTool.java`：

```java
package com.example.research.kb;

import com.example.research.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库工具：注册给 LLM 自主调用（RAG 的工具化形态）。
 * retrieve(query) 检索 topK 文档，拼成上下文返回。
 * 不依赖向量库、不依赖数据库：检索算法在内存（TfidfIndex），文档存 Redis。
 */
@Component
public class KnowledgeBaseTool {

    private final TfidfIndex index;

    public KnowledgeBaseTool(TfidfIndex index) {
        this.index = index;
    }

    /** 检索并格式化为 LLM 可读的上下文。 */
    public Mono<String> retrieve(String query) {
        return index.retrieve(query, 3)
                .map(docs -> docs.isEmpty()
                        ? "（知识库无相关文档）"
                        : docs.stream().map(d -> "【" + d.title() + "】" + d.content())
                              .collect(Collectors.joining("\n\n")));
    }

    /** 同步版（工具调用用）。 */
    public String invokeAsTool(String userInput) {
        return retrieve(userInput).block();
    }

    /** 构造工具描述，注册给 LlmClient。 */
    public LlmClient.LlmTool asTool() {
        return new LlmClient.LlmTool(
                "knowledge_base",
                "查询内部知识库，返回最相关的文档片段。用于研究企业内部信息、规范、已有结论。",
                this::invokeAsTool);
    }
}
```

#### 2.2.6 初始化种子文档（启动时灌入 Redis）

**【新建文件】** `research-agent/src/main/java/com/example/research/kb/KbSeeder.java`：

```java
package com.example.research.kb;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 启动时往 Redis 灌入种子知识文档（演示用）。
 * 已存在则跳过（用 kb:seeded 标记）。
 */
@Component
public class KbSeeder {

    private final TfidfIndex index;
    private final ReactiveRedisTemplate<String, String> redis;

    public KbSeeder(TfidfIndex index, ReactiveRedisTemplate<String, String> redis) {
        this.index = index;
        this.redis = redis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        redis.opsForValue().get("kb:seeded")
                .switchIfEmpty(Mono.just("0"))
                .flatMap(seeded -> "1".equals(seeded) ? Mono.empty() :
                    index.add("vLLM 推理框架", "vLLM 是高效的大模型推理框架，核心是 PagedAttention，吞吐量行业领先。")
                        .then(index.add("TensorRT-LLM", "TensorRT-LLM 由 NVIDIA 出品，与 GPU 深度绑定，延迟最低。"))
                        .then(index.add("SGLang 框架", "SGLang 主打结构化生成与 RadixAttention，适合复杂工具调用。"))
                        .then(index.add("部署规范", "本公司大模型服务部署规范：推理服务必须配置超时与重试，禁止裸 block。"))
                        .then(redis.opsForValue().set("kb:seeded", "1")))
                .subscribe();
    }
}
```

#### 2.2.7 ResearchService：多注册一个知识库工具

**【改已有文件，完整版覆盖】** `ResearchService.java`（相对第 1 章，多注入 `KnowledgeBaseTool` 并注册）：

```java
package com.example.research;

import com.example.research.kb.KnowledgeBaseTool;
import com.example.research.llm.ToolCallingLoop;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 第 2 章：自主 Agent + 知识库工具。
 * LLM 现在有两个工具：web_search（公开信息）+ knowledge_base（内部知识）。
 */
@Service
public class ResearchService {

    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;

    public ResearchService(ToolCallingLoop agentLoop, WebSearchTool searchTool,
                           KnowledgeBaseTool knowledgeBaseTool) {
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
    }

    public Flux<String> research(String topic) {
        return agentLoop.run(
                "你是研究助理。你有两个工具：web_search（查公开信息）、knowledge_base（查内部知识库）。"
              + "自主决定调哪个、调几次。内部问题优先查 knowledge_base。资料足够后给出结构清晰的研究结果。绝不编造。",
                "研究主题：" + topic,
                List.of(),
                List.of(searchTool.asTool(), knowledgeBaseTool.asTool()))
                .timeout(Duration.ofSeconds(60));
    }
}
```

### 2.3 验证最朴素版

```bash
docker run -d --name research-redis -p 6379:6379 redis:7-alpine
mvn spring-boot:run

curl -N "http://localhost:8080/api/research?topic=部署规范"
curl -N "http://localhost:8080/api/research?topic=vLLM SGLang"
docker exec research-redis redis-cli HGETALL kb:docs
```

**这版能跑通什么**：输入"部署规范"→ 命中部署规范文档；输入"vLLM SGLang"→ 命中两篇相关文档。RAG 链路打通了。

**但仔细测会发现问题**（这就是要演进的痛点）：① 输入"vLLM 推理框架"，"推理"这个词每篇文档几乎都有，命中数虚高，可能把不相关的文档也顶上来；② 输入纯中文问句"vLLM是什么"，按空格拆不开，命中数为 0 查不到。**这正是 2.6（IDF）、2.8（2-gram）要解决的。**

### 2.4 最朴素检索的隐患清单（后面逐个补）

| 隐患 | 现象 | 何时补 |
|------|------|--------|
| 高频词淹没关键词 | "推理/大模型"每篇都有，命中数虚高 | 2.6（IDF）|
| 长短文档不公平 | 长文档天然命中更多词 | 2.7（余弦归一化）|
| 中文拆不开 | 纯中文问句按空格拆不出词，查不到 | 2.8（2-gram 分词）|
| 无 topK 截断/0 命中过滤 | 不相关的也返回 | 2.9（过滤）|

> **演进纪律**：先让检索 work（2.2-2.3），再逐个提升质量（2.6-2.9）。**不一开始就上完整 TF-IDF。**

### 2.5 checkpoint（最朴素版）

```
research-agent/src/main/java/com/example/research/
├── config/RedisConfig.java        （新增）
└── kb/
    ├── TfidfIndex.java            （新增：最朴素包含匹配）
    ├── KnowledgeBaseTool.java     （新增）
    └── KbSeeder.java              （新增）
```

```bash
git add -A && git commit -m "第2章(朴素版)：知识库RAG链路打通（包含匹配+落Redis）"
```

---

### 2.6 升级①：IDF——压低高频词的权重

**痛点**：2.4 清单的"高频词淹没关键词"。"推理/大模型"这类词每篇文档都有，朴素"命中数"把它们当成强信号，导致不相关文档排名虚高。真正有区分度的词（如"PagedAttention"只出现在 vLLM 那篇）反而被淹没。

**解法**：**IDF（逆文档频率）**——一个词在越多文档出现，权重越低。`idf = log(文档总数 / 包含该词的文档数)`。"推理"出现在所有 4 篇 → idf≈0；"PagedAttention"只出现在 1 篇 → idf 高。用 `词频 × idf` 替代纯命中数。

**【改已有文件，完整版覆盖】** `TfidfIndex.java`（在朴素版基础上，把 `countHits` 换成 TF-IDF 打分，标 `▼ 升级①`）：

```java
package com.example.research.kb;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class TfidfIndex {

    private static final String KEY_DOCS = "kb:docs";
    private static final String KEY_SEQ  = "kb:seq";
    private final ReactiveRedisTemplate<String, String> redis;

    public TfidfIndex(ReactiveRedisTemplate<String, String> redis) { this.redis = redis; }

    public Mono<List<KbDoc>> loadAll() {
        return redis.opsForHash().values(KEY_DOCS).map(o -> KbDoc.fromJson(o.toString())).collectList();
    }

    public Mono<String> add(String title, String content) {
        return redis.opsForValue().increment(KEY_SEQ)
                .map(id -> new KbDoc("doc" + id, title, content))
                .flatMap(doc -> redis.opsForHash().put(KEY_DOCS, doc.id(), doc.toJson()).thenReturn(doc.id()));
    }

    public Mono<List<KbDoc>> retrieve(String query, int topK) {
        return loadAll().map(docs -> {
            List<String> terms = tokenize(query);
            // ▼ 升级①：算每个 query 词的 idf
            Map<String, Double> idf = computeIdf(docs, terms);
            return docs.stream()
                    .map(d -> Map.entry(d, score(d, terms, idf)))
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        });
    }

    /** ▼ 升级①：文档得分 = Σ(词频 × idf)。高频词 idf 低，压不相关文档的分数。 */
    private double score(KbDoc d, List<String> terms, Map<String, Double> idf) {
        String text = (d.title() + " " + d.content()).toLowerCase();
        Map<String, Long> tf = terms.stream().filter(t -> text.contains(t))
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        double s = 0;
        for (Map.Entry<String, Long> e : tf.entrySet()) {
            s += e.getValue() * idf.getOrDefault(e.getKey(), 0.0);
        }
        return s;
    }

    /** ▼ 升级①：idf = log(N / (1 + df)) + 1。出现在越多文档的词，idf 越低。 */
    private Map<String, Double> computeIdf(List<KbDoc> docs, List<String> terms) {
        Map<String, Double> idf = new HashMap<>();
        for (String term : new HashSet<>(terms)) {
            long df = docs.stream().filter(d -> (d.title() + " " + d.content()).toLowerCase().contains(term)).count();
            idf.put(term, Math.log((docs.size() + 1.0) / (df + 1.0)) + 1.0);
        }
        return idf;
    }

    /** 分词：先只按空格/标点拆（英文够用；中文 2-gram 在 2.8 补）。 */
    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.toLowerCase().split("[\\s，。、,.]+"))
                .filter(s -> !s.isBlank()).collect(Collectors.toList());
    }

    public record KbDoc(String id, String title, String content) {
        public String toJson() {
            return "{\"id\":\"" + id + "\",\"title\":\"" + esc(title) + "\",\"content\":\"" + esc(content) + "\"}";
        }
        static KbDoc fromJson(String json) {
            return new KbDoc(extract(json, "id"), extract(json, "title"), extract(json, "content"));
        }
        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
        private static String extract(String json, String key) {
            String k = "\"" + key + "\":\"";
            int start = json.indexOf(k);
            if (start < 0) return "";
            start += k.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) { char n = json.charAt(++i); sb.append(n == 'n' ? '\n' : n); }
                else if (c == '"') break; else sb.append(c);
            }
            return sb.toString();
        }
    }
}
```

**验证**：再搜"vLLM 推理框架"，"推理"的 idf 低，真正区分性的词权重起来，vLLM 文档更稳地排第一。

> **这版简陋处**：得分是"词频×idf 的求和"，长短文档不公平（长文档词频天然高）。→ 2.7 用余弦归一化解决。

### 2.7 升级②：余弦归一化——长短文档公平比较

**痛点**：2.6 的得分是裸求和，长文档天然词频高、分数虚高。应该把文档和 query 都看成"向量"，用**夹角余弦**比较方向相似度——和向量长度（文档长短）无关。

**解法**：把"词→权重"的 Map 当稀疏向量，算 query 向量和文档向量的**余弦相似度** `dot / (|a|·|b|)`。这一步之后，检索质量基本够用。

**【改已有文件，片段】** `TfidfIndex.retrieve` 和打分改成余弦（标 `▼ 升级②`）：

```java
public Mono<List<KbDoc>> retrieve(String query, int topK) {
    return loadAll().map(docs -> {
        List<String> terms = tokenize(query);
        Map<String, Double> idf = computeIdf(docs, terms);
        Map<String, Double> qVec = tfidfVector(terms, idf);              // ▼ 升级②：query 向量
        return docs.stream()
                .map(d -> Map.entry(d, cosine(qVec, tfidfVector(tokenize(d.title() + " " + d.content()), idf))))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .filter(e -> e.getValue() > 0)                            // ▼ 顺手补：过滤 0 相似度
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    });
}

/** ▼ 升级②：稀疏 TF-IDF 向量（词→tf×idf）。 */
private Map<String, Double> tfidfVector(List<String> terms, Map<String, Double> idf) {
    Map<String, Long> tf = terms.stream().collect(Collectors.groupingBy(t -> t, Collectors.counting()));
    Map<String, Double> vec = new HashMap<>();
    for (Map.Entry<String, Long> e : tf.entrySet()) {
        vec.put(e.getKey(), e.getValue() * idf.getOrDefault(e.getKey(), 0.0));
    }
    return vec;
}

/** ▼ 升级②：两个稀疏向量的余弦相似度。和向量长度无关，长短文档公平。 */
private double cosine(Map<String, Double> a, Map<String, Double> b) {
    double dot = 0, na = 0, nb = 0;
    for (Map.Entry<String, Double> e : a.entrySet()) {
        na += e.getValue() * e.getValue();
        Double bv = b.get(e.getKey());
        if (bv != null) dot += e.getValue() * bv;
    }
    for (double v : b.values()) nb += v * v;
    return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
}
```

> 删掉 2.6 的 `score(...)` 方法（被余弦版取代）。这版已经接近完整 TF-IDF。**但中文还搜不到**——下一节补分词。

### 2.8 升级③：中文 2-gram 分词——让纯中文问句能搜到

**痛点**：2.4 清单的"中文拆不开"。纯中文问句"vLLM是什么"按空格拆不出有意义的词，命中数为 0。我们没有专业中文分词库（零依赖），怎么让中文能匹配？

**解法**：**2-gram 兜底**——把连续的中文字符每两个字当一个"词"。"大模型推理"→ `[大模, 模型, 型推, 推理]`。query 和文档都这么拆，2-gram 重合就命中。粗糙但管用，不需要任何分词依赖。

**【改已有文件，片段】** `tokenize` 加中文 2-gram（标 `▼ 升级③`）：

```java
private List<String> tokenize(String text) {
    if (text == null) return List.of();
    List<String> tokens = new ArrayList<>();
    // 英文/数字词
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-zA-Z0-9_\\-]+").matcher(text);
    while (m.find()) tokens.add(m.group().toLowerCase());
    // ▼ 升级③：中文 2-gram（每两个相邻字当一个词，兜底中文匹配）
    String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
    for (int i = 0; i + 2 <= cn.length(); i++) {
        tokens.add(cn.substring(i, i + 2));
    }
    return tokens;
}
```

**验证**：现在搜"vLLM是什么"、"对比推理框架"这类纯中文问句也能命中——2-gram 把它们拆成可匹配的片段。

> **2-gram 的代价**：会拆出很多无意义片段（如"的框"），但 IDF 会压低这些在多文档出现的片段。对学习用 RAG 完全够用。生产换专业分词（jieba/HanLP）或直接上向量检索，接口不变。

### 2.9 升级④小结：到这里就是完整 TF-IDF

四步走完，`TfidfIndex` 已经是：**TF-IDF 向量 + 余弦相似度 + 中英混合分词 + topK 过滤**——一个零依赖、纯 Java、对研究问答够用的 RAG 检索器。**它不是一上来就写好的，而是 2.2 朴素版 → 2.6 IDF → 2.7 余弦 → 2.8 2-gram 一步步长出来的**，每步都对应一个真实检索痛点。

> **为什么不在这再贴一遍终态完整代码**：把 2.6 + 2.7 + 2.8 的片段合并就是终态。重复贴整文件反而看不出"哪些是哪一步加的"。**演进式文档的价值，就是让你看清能力的生长路径**，而不是面对一个"为什么这么写"的成品。

### 2.10 checkpoint + 复盘（第 2 章全节）

```bash
git add -A && git commit -m "第2章：TF-IDF四步演进（朴素匹配→IDF→余弦→2-gram）"
```

**做了**：知识库 RAG 链路打通 + 检索质量四步演进。文档落 Redis（不依赖数据库），检索纯内存（不依赖向量库）。两个工具（web_search + knowledge_base）分工。

**核心**：RAG 不一定要向量库——零依赖版用 TF-IDF 证明"检索能力"可以纯内存一步步搭出来。**换向量检索时，只换 `TfidfIndex.retrieve()`，接口和工具注册都不动**——和第 16 章换 LLM 同构的解耦红利。

---

## 第 3 章：上线后的运营事故——超时/重试/错误归宿

### 3.0 场景

第 2 章上线对外运营。事故来了：
- 用户问了个超长复杂主题，研究跑了 5 分钟还没结束（Mock 吐字慢 + 工具多轮）→ **没有全局超时**，前端一直转圈。
- Redis 偶发抖动（容器重启），某次检索失败 → 错误冒泡到用户面前，直接 500。
- 用户问完不知道结果对不对、资料从哪来的 → **错误和信息都没归宿**。

### 3.1 思路：三层兜底

| 问题 | 解法 | 落点 |
|------|------|------|
| 跑太久无超时 | 全局 `timeout` + 工具级 timeout | ResearchService / 工具 |
| 中间件抖动报错 | `.retry()` 重试 + `.onErrorResume` 降级 | 工具内部 |
| 错误无归宿 | 统一异常 + 错误也走流式输出给前端 | GlobalErrorFilter |

### 3.2 动手

本章改 `WebSearchTool`/`KnowledgeBaseTool`（加重试降级）、加 `GlobalErrorFilter`。`ResearchService` 已有 `.timeout(60s)`（第 1 章），这里把工具也加固。

#### 3.2.1 工具加重试 + 降级

**【改已有文件，片段】** `KnowledgeBaseTool.retrieve()`（相对第 2 章，加重试降级）：

```java
public Mono<String> retrieve(String query) {
    return index.retrieve(query, 3)
            .timeout(Duration.ofSeconds(5))                    // ▼ 第3章：工具级超时
            .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(200)))  // ▼ 重试 2 次
            .map(docs -> docs.isEmpty()
                    ? "（知识库无相关文档）"
                    : docs.stream().map(d -> "【" + d.title() + "】" + d.content())
                          .collect(Collectors.joining("\n\n")))
            .onErrorResume(e -> Mono.just("（知识库暂时不可用，已降级：" + e.getMessage() + "）"));  // ▼ 降级
}
```

`WebSearchTool.search()` 同理加 `.timeout(5s).retryWhen(...).onErrorResume(...)`。

#### 3.2.2 全局异常处理：错误也走流式

**【新建文件】** `research-agent/src/main/java/com/example/research/web/GlobalErrorFilter.java`：

```java
package com.example.research.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * 全局异常兜底：把未捕获异常转成 200 + 错误消息（而非裸 500）。
 * 流式接口的异常会被 SSE 编码器吃掉，这里主要兜非流式接口。
 * 第 6 章审计日志会在这里落"错误事件"。
 */
@Configuration
public class GlobalErrorFilter {

    @Bean
    public WebFilter errorFilter() {
        return (ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) ->
                chain.filter(exchange).onErrorResume(err -> {
                    System.err.println("[全局异常] " + err.getClass().getSimpleName() + ": " + err.getMessage());
                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);
                    byte[] body = ("（处理出错：" + err.getMessage() + "）").getBytes();
                    return exchange.getResponse().writeWith(
                            Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                });
    }
}
```

### 3.3 验证

```bash
# 临时关掉 Redis 模拟故障
docker stop research-redis
curl -N "http://localhost:8080/api/research?topic=部署规范"
# 预期：不报 500，结果里出现"知识库暂时不可用，已降级"

docker start research-redis
```

### 3.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第3章：超时+重试+降级+全局异常兜底"
```

**做了**：工具级 timeout + retry + onErrorResume 降级；全局 WebFilter 把异常转成可读响应。**没有稳定兜底的 Agent 不能上线**——这是后续多端同步、管数分离的前置（地基不稳就上分布式只会放大故障）。

---

## 第 4 章：先规划再调研——Plan 阶段（串行起步）

### 4.0 场景：隐式 ReAct 查不全复杂主题

第 1-3 章的 Agent 是"隐式 ReAct"——边想边搜，没有全局规划。用户问"对比 A、B、C 三个框架"时，它可能只搜了 A 就收手，**复杂主题查不全**。

**解法**：先让 LLM 做一个**显式的 Plan**（"这个主题要拆成哪几个子任务"），再逐个 Execute。

### 4.1 思路：Plan-Execute-Aggregate

```
Plan（让 LLM 拆子任务）→ Execute（逐个调研，第 5 章并发）→ Aggregate（汇总成最终结果）
```

本章先**串行** Execute（一个个查），第 5 章再并行。

### 4.2 动手

#### 4.2.1 PlanExecuteService

**【新建文件】** `research-agent/src/main/java/com/example/research/plan/PlanExecuteService.java`：

```java
package com.example.research.plan;

import com.example.research.llm.LlmClient;
import com.example.research.llm.ToolCallingLoop;
import com.example.research.tool.WebSearchTool;
import com.example.research.kb.KnowledgeBaseTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 第 4 章：Plan-Execute-Aggregate（串行起步）。
 *
 * Plan：让 LLM 把主题拆成子任务（返回分号分隔的列表）。
 * Execute：逐个子任务调用 Agent 调研（串行，第 5 章并发）。
 * Aggregate：把各子任务结果汇总成最终研究结果，流式输出。
 *
 * 简陋处：Plan 的解析是字符串拆分（非结构化 JSON）。第 6 章审计会记录每个子任务。
 */
@Service
public class PlanExecuteService {

    private final LlmClient llm;
    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;
    private final KnowledgeBaseTool kbTool;

    public PlanExecuteService(LlmClient llm, ToolCallingLoop agentLoop,
                              WebSearchTool searchTool, KnowledgeBaseTool kbTool) {
        this.llm = llm;
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
        this.kbTool = kbTool;
    }

    /** 深度研究：Plan → 串行 Execute → Aggregate，全程流式。 */
    public Flux<String> researchDeep(String topic) {
        return Flux.defer(() -> {
            // ① Plan：拆子任务
            String plan = llm.chat(
                    "你是研究规划师。把用户的研究主题拆成 2-4 个子研究方向，用分号分隔，只输出子方向名称。",
                    topic);
            List<String> subtasks = parsePlan(plan, topic);
            System.out.println("[Plan] 子任务: " + subtasks);

            // ② Execute（串行）：每个子任务跑一遍 Agent，收集结果
            List<String> results = new ArrayList<>();
            for (String sub : subtasks) {
                System.out.println("[Plan] 调研: " + sub);
                // 串行：block 拿结果（简陋版，第 5 章改并发）
                String res = agentLoop.run(
                        "你是研究助理，专注调研给定的子方向。资料足够后简洁总结。",
                        "调研方向：" + sub,
                        List.of(),
                        List.of(searchTool.asTool(), kbTool.asTool()))
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .map(StringBuilder::toString)
                        .block();
                results.add("### " + sub + "\n" + res);
            }

            // ③ Aggregate：汇总流式输出
            String materials = String.join("\n\n", results);
            return llm.stream(
                    "你是研究主编。基于各子方向的调研结果，汇总成一份结构清晰、有对比的综合研究报告。",
                    "主题：" + topic + "\n\n各子方向结果：\n" + materials,
                    List.of(),
                    List.of());
        });
    }

    private List<String> parsePlan(String plan, String topic) {
        if (plan == null || plan.isBlank()) return List.of(topic);
        List<String> subs = Arrays.stream(plan.split("[；;]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return subs.isEmpty() ? List.of(topic) : subs;
    }
}
```

> **简陋处**：串行 Execute 用 `.block()`（在 `Flux.defer` 里）——第 5 章改并发。Plan 解析是字符串拆分，不是结构化。

#### 4.2.2 Controller 加 /deep

**【改已有文件，片段】** `ResearchController.java`，注入 `PlanExecuteService` 加一个端点：

```java
private final PlanExecuteService planExecuteService;   // ▼ 第4章

// 构造器加参数...

@GetMapping(value = "/deep", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> deep(@RequestParam String topic) {
    return planExecuteService.researchDeep(topic);   // ▼ 第4章：Plan-Execute 深度研究
}
```

### 4.3 验证

```bash
curl -N "http://localhost:8080/api/research/deep?topic=对比vLLM、TensorRT-LLM、SGLang"
```

预期：日志先打印 `[Plan] 子任务: [...]`，然后逐个 `[Plan] 调研`，最后流式输出综合报告。

### 4.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第4章：Plan-Execute-Aggregate（串行起步）"
```

**做了**：显式 Plan（拆子任务）→ 串行 Execute → Aggregate 汇总。复杂主题不再"查一半就收手"。

---

## 第 5 章：多 Worker 并发调研——把串行变并行

### 5.0 场景：串行太慢

第 4 章串行调研，3 个子任务要排队跑，耗时叠加。**React 的 `flatMap` 让我们天然能并发**。

### 5.1 思路：flatMap 并发 + 限流 + 错误隔离

- `Flux.fromIterable(subtasks).flatMap(sub -> 调研, concurrency)` 并发跑。
- `concurrency` 限流（不能无限并发，会把 LLM/Mock 打爆）。
- 单个子任务出错用 `onErrorResume` 隔离，不拖垮整体。

### 5.2 动手

**【改已有文件，片段】** `PlanExecuteService.researchDeep()` 的 Execute 部分（替换第 4 章的串行 for 循环）：

```java
public Flux<String> researchDeep(String topic) {
    return Flux.defer(() -> {
        String plan = llm.chat(
                "你是研究规划师。把用户主题拆成 2-4 个子方向，分号分隔，只输出名称。",
                topic);
        List<String> subtasks = parsePlan(plan, topic);
        System.out.println("[Plan] 子任务: " + subtasks);

        // ▼ 第5章替换：串行 for 循环 → flatMap 并发（限流 + 错误隔离）
        int concurrency = Math.min(subtasks.size(), 3);   // 最多 3 路并发
        return Flux.fromIterable(subtasks)
                .flatMap(sub -> executeSubtask(sub), concurrency)   // 并发调研
                .reduce(new StringBuilder(), StringBuilder::append)
                .flatMapMany(materials -> llm.stream(
                        "你是研究主编。基于各子方向结果汇总成综合研究报告。",
                        "主题：" + topic + "\n\n结果：\n" + materials,
                        List.of(), List.of()));
    });
}

/** ▼ 第5章新增：单个子任务调研（返回带标题的结果，出错隔离降级）。 */
private Flux<String> executeSubtask(String sub) {
    System.out.println("[Plan] 并发调研: " + sub);
    return agentLoop.run(
            "你是研究助理，专注调研给定子方向。资料足够后简洁总结。",
            "调研方向：" + sub,
            List.of(),
            List.of(searchTool.asTool(), kbTool.asTool()))
            .reduce(new StringBuilder(), StringBuilder::append)
            .map(sb -> "### " + sub + "\n" + sb + "\n\n")
            .onErrorResume(e -> reactor.core.publisher.Mono.just(
                    "### " + sub + "\n（该子方向调研失败，已隔离：" + e.getMessage() + "）\n\n"))
            .flux();   // Mono→Flux 以便 flatMap 合流
}
```

> **`flatMap` 限流的意义**：`flatMap(mapper, concurrency)` 的第二个参数是最大并发数。即使有 10 个子任务，也只同时跑 3 个——保护下游（真实场景下保护 LLM API 的 QPS 限制）。**错误隔离**：单个子任务 `onErrorResume` 降级，其他子任务不受影响——这是"部分失败不拖垮整体"的标准模式。

### 5.3 验证

```bash
curl -N "http://localhost:8080/api/research/deep?topic=对比三大推理框架"
```

预期：日志里 `[Plan] 并发调研` 几乎同时出现（而非排队），总耗时显著缩短。

### 5.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第5章：flatMap并发调研+限流+错误隔离"
```

**核心跃迁**：串行 → 并发。Reactor 的 `flatMap` 让"多 Worker 并发"在响应式代码里极其自然——一行 `.flatMap(sub -> ..., concurrency)` 就完成并发 + 限流。

---

## 第 6 章：结构化审计日志——落 Redis，整体流程可追溯

### 6.0 场景："结论怎么来的"说不清

第 5 章并发调研后，用户问"这个结论基于哪些资料？"——答不上来。**需要结构化审计日志**，按会话把"Plan 了什么、调了哪些工具、每步结果"串起来。

零依赖版**不用数据库**，审计日志落 Redis（List，按 sessionId 串联）。

### 6.1 思路：AuditEvent + Redis List

- 每个关键动作（Plan、工具调用、子任务完成、Aggregate）产生一个 `AuditEvent`（JSON）。
- 落 Redis List `audit:{sessionId}`，按时间顺序追加。
- 提供查询接口 `GET /api/audit/{sessionId}` 回看全流程。

### 6.2 动手

#### 6.2.1 AuditEvent + AuditLogger

**【新建文件】** `research-agent/src/main/java/com/example/research/audit/AuditLogger.java`：

```java
package com.example.research.audit;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 结构化审计日志（落 Redis List，按 sessionId 串联）。
 * 不依赖数据库：用 Redis List 持久化，TTL 7 天。
 *
 * 用法：在 Plan、工具调用、Aggregate 等关键点调用 log(sessionId, type, detail)。
 */
@Component
public class AuditLogger {

    private static final String KEY_PREFIX = "audit:";
    private final ReactiveRedisTemplate<String, String> redis;

    public AuditLogger(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /** 记录一条审计事件（异步落 Redis，不阻塞主流程）。 */
    public void log(String sessionId, String type, String detail) {
        String json = toJson(sessionId, type, detail);
        redis.opsForList().rightPush(KEY_PREFIX + sessionId, json)
                .then(redis.expire(KEY_PREFIX + sessionId, java.time.Duration.ofDays(7)))
                .subscribe();   // fire-and-forget
    }

    /** 查询某会话的全量审计记录。 */
    public reactor.core.publisher.Flux<String> query(String sessionId) {
        return redis.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
    }

    private String toJson(String sessionId, String type, String detail) {
        return "{\"ts\":\"" + Instant.now() + "\",\"session\":\"" + sessionId
                + "\",\"type\":\"" + type + "\",\"detail\":\""
                + (detail == null ? "" : detail.replace("\"", "\\\"").replace("\n", "\\n"))
                + "\"}";
    }
}
```

#### 6.2.2 在编排里埋点

**【改已有文件，片段】** `PlanExecuteService` 注入 `AuditLogger`，在 Plan/Execute/Aggregate 埋点（这里需要 `sessionId` 参数——这正好为第 7 章引入 sessionId 做铺垫）：

```java
// 方法签名加 sessionId：
public Flux<String> researchDeep(String topic, String sessionId) {
    audit.log(sessionId, "PLAN_START", "主题: " + topic);
    // ... Plan 之后：
    audit.log(sessionId, "PLAN_DONE", "子任务: " + subtasks);
    // executeSubtask 加 sessionId 参数，完成时：
    audit.log(sessionId, "SUBTASK_DONE", sub);
    // Aggregate 时：
    audit.log(sessionId, "AGGREGATE", "开始汇总");
}
```

#### 6.2.3 审计查询接口

**【改已有文件，片段】** `ResearchController` 或新建 `AuditController`：

```java
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditLogger audit;
    public AuditController(AuditLogger audit) { this.audit = audit; }

    @GetMapping("/{sessionId}")
    public Flux<String> audit(@PathVariable String sessionId) {
        return audit.query(sessionId);
    }
}
```

### 6.3 验证

```bash
curl -N "http://localhost:8080/api/research/deep?topic=对比三大框架&sessionId=audit-001"
curl "http://localhost:8080/api/audit/audit-001"
# 预期：返回 PLAN_START / PLAN_DONE / SUBTASK_DONE / AGGREGATE 等结构化事件
```

### 6.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第6章：结构化审计日志（落Redis，按session串联）"
```

**做了**：审计事件落 Redis List，按 sessionId 串联全流程。Redis 当持久层——"不依赖数据库"的又一落地。

---

## 第 7 章：会话持久化——ChatMemory 落 Redis，刷新不丢历史

### 7.0 场景：刷新就丢、无法多轮追问

前面研究的 sessionId 已经在审计里用了，但**真正的对话历史没存**。用户刷新页面，上一轮问的什么全没了；追问"接着上面说"时 Agent 失忆。

**解法**：把每轮的 user/assistant 消息落 Redis（List，按 sessionId），下次调用前读出来作为 `history` 传给 LLM。

### 7.1 思路：ChatMemory（Redis List）+ history 注入

- 存：每轮 `user` + `assistant` 落 Redis List `chat:{sessionId}`。
- 读：调用 LLM 前读 List，转成 `List<LlmMessage>` 传入 `stream(system, user, history, tools)`。
- 这正是 `LlmClient` 接口里 `history` 参数的用途。

### 7.2 动手

#### 7.2.1 ChatMemoryStore

**【新建文件】** `research-agent/src/main/java/com/example/research/session/ChatMemoryStore.java`：

```java
package com.example.research.session;

import com.example.research.llm.LlmClient;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆（落 Redis List，按 sessionId）。
 * 不依赖数据库：Redis 既当热缓存也当持久层。
 *
 * 读：把 Redis List 里的消息转成 List<LlmMessage> 传给 LLM（多轮记忆）。
 * 写：每轮的 user + assistant 追加进 List。
 */
@Component
public class ChatMemoryStore {

    private static final String KEY = "chat:";
    private static final Duration TTL = Duration.ofDays(7);
    private final ReactiveRedisTemplate<String, String> redis;

    public ChatMemoryStore(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    /** 读历史，转成 LlmMessage 列表。 */
    public Mono<List<LlmClient.LlmMessage>> load(String sessionId) {
        return redis.opsForList().range(KEY + sessionId, 0, -1)
                .map(this::decode)
                .collectList();
    }

    /** 追加本轮 user + assistant。 */
    public Mono<Void> append(String sessionId, String user, String assistant) {
        return redis.opsForList().rightPushAll(KEY + sessionId,
                        encode("user", user), encode("assistant", assistant))
                .then(redis.expire(KEY + sessionId, TTL))
                .then();
    }

    private String encode(String role, String content) {
        return role + "::" + (content == null ? "" : content.replace("\n", "\\n"));
    }

    private LlmClient.LlmMessage decode(String raw) {
        int idx = raw.indexOf("::");
        if (idx < 0) return new LlmClient.LlmMessage("user", raw);
        String role = raw.substring(0, idx);
        String content = raw.substring(idx + 2).replace("\\n", "\n");
        return new LlmClient.LlmMessage(role, content);
    }
}
```

#### 7.2.2 ResearchService 注入记忆

**【改已有文件，完整版覆盖】** `ResearchService.java`（加 sessionId + ChatMemoryStore，调用前后读写历史）：

```java
package com.example.research;

import com.example.research.kb.KnowledgeBaseTool;
import com.example.research.llm.LlmClient;
import com.example.research.llm.ToolCallingLoop;
import com.example.research.session.ChatMemoryStore;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 第 7 章：自主 Agent + 会话记忆。
 * research(topic, sessionId)：调用前读历史，调用后把 user+assistant 写回。
 * 这样刷新页面、跨设备，只要带同一 sessionId，Agent 就记得上文。
 */
@Service
public class ResearchService {

    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final ChatMemoryStore memory;   // ▼ 第7章新增

    public ResearchService(ToolCallingLoop agentLoop, WebSearchTool searchTool,
                           KnowledgeBaseTool knowledgeBaseTool, ChatMemoryStore memory) {
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
        this.memory = memory;
    }

    /** 带 sessionId 的研究接口（多轮记忆）。 */
    public Flux<String> research(String topic, String sessionId) {
        return memory.load(sessionId)                                    // ▼ 读历史
                .flatMapMany(history -> {
                    StringBuilder collected = new StringBuilder();
                    return agentLoop.run(
                            "你是研究助理。你有 web_search（公开信息）和 knowledge_base（内部知识库）。"
                          + "结合上文历史继续研究。绝不编造。",
                            "研究主题：" + topic,
                            history,                                     // ▼ 注入历史
                            List.of(searchTool.asTool(), knowledgeBaseTool.asTool()))
                            .timeout(Duration.ofSeconds(60))
                            .doOnNext(collected::append)
                            .doOnComplete(() -> memory.append(sessionId, topic, collected.toString()).subscribe());  // ▼ 写回历史
                });
    }
}
```

> **调用前后读写历史的时序**：`load(sessionId)` 拿历史 → 作为 `history` 传给 Agent → 流式收集 assistant 输出 → `doOnComplete` 把 `user + assistant` 写回。下次同 sessionId 调用，就能读到本轮，实现多轮。**注意**：`memory.append` 在 `doOnComplete` 里 fire-and-forget，不阻塞流。

#### 7.2.3 Controller 加 sessionId

**【改已有文件，片段】** `ResearchController.research()` 加 `@RequestParam String sessionId`：

```java
@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> research(@RequestParam String topic, @RequestParam String sessionId) {
    return researchService.research(topic, sessionId);
}
```

### 7.3 验证

```bash
# 第一轮
curl -N "http://localhost:8080/api/research?topic=vLLM是什么&sessionId=mem-001"
# 第二轮追问（同 sessionId）
curl -N "http://localhost:8080/api/research?topic=它和PagedAttention什么关系&sessionId=mem-001"
# 预期：第二轮 Agent 基于第一轮历史展开（Mock 会把上一轮 assistant 拼进上下文）

# 验证 Redis 里有历史
docker exec research-redis redis-cli LRANGE chat:mem-001 0 -1
```

### 7.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第7章：会话记忆落Redis，多轮+刷新不丢"
```

**做了**：`ChatMemoryStore` 用 Redis List 存对话历史；调用前读、调用后写。`LlmClient.stream(...,history,...)` 的 `history` 参数终于用上了。

**核心**：会话记忆不需要数据库——Redis List 就是天然的"按序追加 + 范围读"。第 17 章拆服务后，这层会演变成"Redis 热缓存 + 兜底"。

---

## 第 8 章：会话管理 CRUD + 前端对话页

### 8.0 场景：只有单次研究没法当产品用

第 7 章有了多轮记忆，但**没有会话管理**——用户不能看"我有哪些会话"、不能新建/重命名/删除会话、没有前端对话页。这是从"研究功能"到"产品"的临门一脚。

### 8.1 思路：一步步从"能聊天"到"产品"

> **演进纪律**：产品化也是一步步加的，不是一上来就铺全套 CRUD + 完整前端。本章的演进顺序：
>
> 1. **最小前端**（8.2.3a）：一个输入框 + 研究按钮 + EventSource 订阅 SSE——**先让浏览器能聊天**，连会话列表都没有。
> 2. **+ 会话列表/新建**（8.2.3b）：发现"开新会话、切换会话"的需求 → 加 SessionStore（最小：create + list）+ 前端会话列表。
> 3. **+ 历史回看**（8.2.3c）：发现"刷新页面想看上次结果"→ 加 `GET /sessions/{id}/messages`。
> 4. **+ 重命名/删除**：发现"会话太多要管理"→ SessionStore 补 rename/delete。
>
> 下面 8.2.1/8.2.2 给 SessionStore/Controller 的**终态**（含完整 CRUD），8.2.3 给前端的**逐版演进**。你可以照着 8.2.3 的 a→b→c 一步步加，每步都是一个能跑的中间态。

- 会话元信息（id、标题、创建时间）存 Redis Hash `sessions`。
- CRUD：新建会话、列出会话、重命名、删除（连带删 chat/audit）。
- 前端：静态 HTML（EventSource 订阅 SSE），放 `resources/static/`。

### 8.2 动手

#### 8.2.1 SessionStore（会话元信息）

**【新建文件】** `research-agent/src/main/java/com/example/research/session/SessionStore.java`：

```java
package com.example.research.session;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话元信息 CRUD（落 Redis Hash）。
 * Key: sessions（Hash: sessionId -> JSON{title,createdAt}）。
 * 不依赖数据库。
 */
@Component
public class SessionStore {

    private static final String KEY = "sessions";
    private final ReactiveRedisTemplate<String, String> redis;

    public SessionStore(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public Mono<String> create(String title) {
        String id = UUID.randomUUID().toString();
        String json = "{\"title\":\"" + title + "\",\"createdAt\":\"" + Instant.now() + "\"}";
        return redis.opsForHash().put(KEY, id, json).thenReturn(id);
    }

    public Flux<String> list() {
        return redis.opsForHash().entries(KEY)
                .map(e -> "{\"id\":\"" + e.getKey() + "\"," + e.getValue().toString().substring(1));
    }

    public Mono<Boolean> rename(String id, String title) {
        return redis.opsForHash().get(KEY, id)
                .flatMap(json -> redis.opsForHash().put(KEY, id,
                        "{\"title\":\"" + title + "\"," + json.toString().split(",", 2)[1]));
    }

    public Mono<Void> delete(String id) {
        return redis.opsForHash().remove(KEY, id)
                .then(redis.opsForList().trim("chat:" + id, 1, 0))    // 删历史（trim 到空）
                .then(redis.opsForList().trim("audit:" + id, 1, 0))
                .then();
    }
}
```

#### 8.2.2 SessionController

**【新建文件】** `research-agent/src/main/java/com/example/research/session/SessionController.java`：

```java
package com.example.research.session;

import com.example.research.ChatMemoryStore;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 会话管理 + 历史查询接口。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStore sessions;
    private final ChatMemoryStore memory;

    public SessionController(SessionStore sessions, ChatMemoryStore memory) {
        this.sessions = sessions;
        this.memory = memory;
    }

    @PostMapping
    public Mono<Map<String, String>> create(@RequestParam(defaultValue = "新会话") String title) {
        return sessions.create(title).map(id -> Map.of("sessionId", id));
    }

    @GetMapping
    public Flux<String> list() { return sessions.list(); }

    @PatchMapping("/{id}")
    public Mono<Boolean> rename(@PathVariable String id, @RequestParam String title) {
        return sessions.rename(id, title);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id) { return sessions.delete(id); }

    /** 某会话的完整历史消息（JSON 一次性返回，管数分离里的"已完成历史"）。 */
    @GetMapping("/{id}/messages")
    public Flux<String> messages(@PathVariable String id) {
        return memory.load(id).map(m -> "{\"role\":\"" + m.role() + "\",\"content\":\""
                + m.content().replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
    }
}
```

#### 8.2.3 前端对话页（逐版演进）

前端最能体现"先能用再变好"。我们分三版，每版都是能跑的中间态。

##### 8.2.3a 最小版：一个输入框 + 流式输出（连会话都没有）

**痛点驱动**：第 8 章第一目标——让用户能在浏览器里看到流式输出（之前只能 curl）。**先不管多会话**，固定一个 sessionId。

**【新建文件】** `research-agent/src/main/resources/static/index.html`（最小版）：

```html
<!DOCTYPE html>
<html lang="zh">
<head><meta charset="UTF-8"><title>研究助手</title></head>
<body>
  <input id="topic" placeholder="输入研究主题" style="width:300px"/>
  <button onclick="send()">研究</button>
  <pre id="out" style="white-space:pre-wrap;background:#f5f5f5;padding:8px;min-height:200px"></pre>

  <script>
    const SID = "demo-session";   // 最小版：写死一个 sessionId
    function send(){
      const t = document.getElementById('topic').value;
      document.getElementById('out').textContent = '';
      const es = new EventSource(`/api/research?topic=${encodeURIComponent(t)}&sessionId=${SID}`);
      es.onmessage = e => { document.getElementById('out').textContent += e.data; };
      es.onerror = () => es.close();
    }
  </script>
</body>
</html>
```

**这版能跑**：浏览器打开 `http://localhost:8080/`，输入主题，结果逐字出现。

**最小版的隐患**（驱动下一版）：
- 只有一个写死的会话——所有人共用，无法区分不同话题。
- 刷新页面看不到上次结果（没有历史回看）。
- 想开新话题，只能覆盖上一个。

##### 8.2.3b 加会话列表：新建 + 切换会话

**痛点驱动**：上面的"写死会话"撑不住真实使用——要能开新会话、在多个会话间切换。这就需要 SessionStore（8.2.1 的 create + list）。

**【改 index.html，增量】** 加会话列表 + 新建按钮，`SID` 改成可切换的 `curSession`：

```html
<!-- body 里加会话区 -->
<h3>会话列表</h3>
<div id="sessions"></div>
<button onclick="newSession()">新建会话</button>
<hr/>
<div>当前会话: <span id="cur"></span></div>
<input id="topic" placeholder="输入研究主题" style="width:300px"/>
<button onclick="send()">研究</button>
<pre id="out" style="white-space:pre-wrap;background:#f5f5f5;padding:8px;min-height:200px"></pre>

<script>
  let curSession = null;
  function newSession(){
    fetch('/api/sessions?title=新会话',{method:'POST'})
      .then(r=>r.json()).then(d=>{ curSession=d.sessionId; document.getElementById('cur').textContent=curSession; loadSessions(); });
  }
  function loadSessions(){
    fetch('/api/sessions').then(r=>r.json()).then(arr=>{
      document.getElementById('sessions').innerHTML = arr.map(s=>{
        const o=JSON.parse(s);
        return `<div onclick="openSession('${o.id}')">${o.title}</div>`;
      }).join('');
    });
  }
  function openSession(id){ curSession=id; document.getElementById('cur').textContent=id; }
  function send(){
    const t=document.getElementById('topic').value;
    document.getElementById('out').textContent='';
    const es=new EventSource(`/api/research?topic=${encodeURIComponent(t)}&sessionId=${curSession}`);
    es.onmessage=e=>{ document.getElementById('out').textContent+=e.data; };
    es.onerror=()=>es.close();
  }
  loadSessions();
</script>
```

**这版能跑**：新建会话、切换会话、各会话独立聊天（第 7 章的记忆按 sessionId 隔离，所以切换会话历史不串）。

**还差**：切换到一个旧会话，看不到它之前的输出（刷新就空了）。

##### 8.2.3c 加历史回看：打开旧会话看到之前的消息

**痛点驱动**：打开旧会话应该看到历史，而不是空白。加 `GET /api/sessions/{id}/messages`（8.2.2 已有），`openSession` 里调它回填。

**【改 index.html，增量】** `openSession` 里加载历史：

```javascript
function openSession(id){
  curSession=id;
  document.getElementById('cur').textContent=id;
  document.getElementById('out').textContent='';
  // ▼ 8.2.3c：打开旧会话时回填历史消息
  fetch(`/api/sessions/${id}/messages`).then(r=>r.json()).then(msgs=>{
    document.getElementById('out').textContent = msgs.map(m=>JSON.parse(m).content).join('\n');
  });
}
```

**这版就是产品级的会话页了**：新建/切换/历史回看/流式输出全齐。

> **前端的多端同步伏笔**：现在两个浏览器标签打开**同一 sessionId**，会各自触发一次研究（重复触发 LLM）——这正是第 9 章多端同步、第 10 章管数分离要解决的痛点。第 8 章先让它"能用"，第 9-10 章让它"多端一致且不重复"。**演进到这里，前端的"能用"做完了；"多端一致"是下一章的痛点。**

### 8.3 验证

```bash
mvn spring-boot:run
# 浏览器打开 http://localhost:8080/
# 新建会话 → 输入主题 → 研究，结果逐字出现在页面上
```

### 8.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第8章：会话CRUD+前端对话页（产品化）"
```

**做了**：`SessionStore` 会话元信息 CRUD（Redis Hash）；`SessionController`；静态前端。**功能演进到此定型**（第 0-8 章完成"能用的产品"）。

> **第 8 章结束 = 功能定型。** 接下来第 9 章起进入**架构演进**——把"能用的单体"推向"管数分离、多端同步、高可用、微服务的分布式企业级形态"。**这是从"玩具"走向"企业级可用"的关键转折。**

---


## 第 9 章：多端同步流式——企业级多端同步的真实做法

> **这一章和原版的根本不同**：原版把"Redis Streams + Pub/Sub + SETNX"当作既定方案直接上。本章先**调研企业级多端同步到底怎么做**（ChatGPT/Claude/Cursor/Linear/飞书等），讲清取舍，再据此落地。**不是模仿，是带着判断重做。**

### 9.0 场景：单机热流在多端/多实例前失效

第 8 章产品上线，部署了两台机器做水平扩展。真实场景来了：

> 用户手机发了一条长研究问题，浏览器开始流式输出。途中打开 iPad 看进度——iPad 上**没有任何内容**。更糟：手机和 iPad 如果**同时**到达，两台实例各自触发一次 LLM——两份 token、两份大概率不同的结果（单一写者被破坏）。

3 个根因：

| 问题 | 根因 | 层级 |
|------|------|------|
| 跨实例不可见 | SSE 热流在单机内存里 | 消息路由层 |
| 多端重复触发 LLM | 无全局"单一写者"保证 | 锁/选举层 |
| 晚加入/断线重连丢内容 | 无持久回放 + 无 resume 补推 | 持久/补推层 |

### 9.1 调研：企业级多端同步怎么做的

我把真实产品的多端同步拆成几个维度，给出业界主流方案和我的取舍：

#### ① 流式输出的传输：SSE vs WebSocket

| 方案 | 方向 | 适合 | 业界用法 |
|------|------|------|---------|
| **SSE**（Server-Sent Events） | 服务器→客户端单向 | 纯单向流式输出 | 足够简单场景；但客户端无法中途发"取消/换设备指令" |
| **WebSocket** | 双向 | 需要客户端心跳、中途取消、切设备发指令 | **ChatGPT/Claude 实际用 WebSocket**——因为用户要能中途打断、切设备 |

**我的判断**：研究问答是**纯单向流式输出**（服务器吐字，客户端只读），中途取消可以用"客户端断连 → 服务端心跳感知"（原版加固②）实现，**SSE 够用且更轻**。但我会把 WebSocket 作为"何时该升级"的明确边界写进去——**如果将来要做"用户中途追加分叉问题 / 客户端发控制指令"，就必须上 WebSocket**。这一章先用 SSE，但在设计上不依赖"SSE 独有"的特性（心跳用应用层实现，不依赖浏览器自动重连），保证将来换 WebSocket 时只换传输层。

#### ② 单一写者（single-writer）保证——这是多端同步的头号难题

多端同时打开同一会话时，**全集群只能有一个实例真正去调 LLM**。否则：重复触发、烧 token、结果分叉（每个 LLM 调用结果都不同）。

| 方案 | 实现 | 取舍 |
|------|------|------|
| SETNX + TTL | 最简单的分布式锁 | 原版用的；够用但有"锁过期后旧持有者继续写"的风险 |
| Redis Redlock | 跨多 Redis 实例的锁 | 更抗单点；Martin Kleppmann 有过著名批评（时钟漂移） |
| **lease + fencing token** | 锁带租约 + 单调递增 token，存储层拒绝旧 token 的写 | **业界更稳的做法**（Google Chubby 思路）|

**我的判断**：本章用 **SETNX + TTL（最简版，和原版一致）**，但**显式承认它的缺陷**（锁过期窗口、无 fencing），并在第 11 章 Redis 高可用、附录里把 **fencing token 作为加固扩展点**讲清楚——而不是假装 SETNX 完美。**学习文档的诚实**比"看起来很完整"重要。

#### ③ 持久回放 + resume token（断线重连补推）

晚加入的设备（iPad 晚 10 秒打开）和断线重连的设备，必须能看到"已经吐出来的内容"。

| 方案 | 实现 |
|------|------|
| Redis Streams XREAD（任意 offset 重放） | 持久追加日志，offset 从 0 读全量历史 |
| **客户端带 resume token 重连** | 客户端记录最后收到的 `eventId`，重连时带上，服务端从该 id 之后补推 |

业界做法（SSE 的 `Last-Event-ID` 头、Kafka 的 offset、ChatGPT 的 `conversation_id + offset`）本质都一样：**单调 id + 从 id 之后补推**。

**我的判断**：原版加固④发明了一个 `AtomicReference<String> cursor` 游标，方向对但实现绕。我用更直白的方式：**给每个 chunk 一个单调递增的 `seq`，客户端记录最后收到的 seq，重连时带上 `lastSeq`，服务端从该 seq 之后补推**。这套语义和 SSE `Last-Event-ID` / Kafka offset 同构，但更好理解。

#### ④ 幂等去重（at-least-once 的必然要求）

"至少一次交付"意味着同一条 chunk 可能被推两次（重连补推窗口重叠）。必须**客户端按 seq 去重**，否则文字会重复。

**原版漏了这点**。本章补上：**每个 chunk 带 seq，客户端维护"已处理最大 seq"，收到 ≤ 已处理 seq 的丢弃**。

#### ⑤ 会话状态多端一致：要不要 CRDT？

Notion/Linear/飞书文档用 **CRDT 或 OT** 做协同编辑——那是"多端同时编辑同一份可变状态"。

**我的判断（重要边界）**：LLM 研究问答**不是协同编辑**，它是"单一写者（LLM）写、多读者（设备）看"的**追加日志**模型。**不需要 CRDT/OT**——那是杀鸡用牛刀，而且引入巨大的复杂度。用"事件日志 + 多播 + seq 去重"就是正解。**这个边界判断本身就是企业级设计能力的体现**：知道什么问题该用什么工具，不被"显得高级"绑架。

#### 调研结论 → 本章架构（终态长这样，但我们会一步步搭到它）

```
                       ┌── 单一写者 ──┐
LLM 调用(唯一实例) ──写──→ Redis Stream 持久日志(每条带 seq) ──多播──→ 各实例各设备
                       │  (单一事件源，权威数据)  │
                       └──────────────────────────┘
        客户端记录最后收到的 SSE id(=seq)，断线重连浏览器自动带 Last-Event-ID → 服务端从该 seq 之后补推
        客户端按 seq 幂等去重
```

这是**终态**。但本章**不一次搭成**——先 9.2 给最小版（只做"持久+广播"，让多端看到同一条流），再 9.6-9.9 按"一个痛点 → 一处加固"逐步补上单一写者、seq、Last-Event-ID、MAXLEN/cancel。**每节只引入一个新概念。**

> **和原版的区别小结**：
> - 显式区分 SSE/WebSocket 适用边界（不假装 SSE 万能）。
> - 承认 SETNX 的缺陷，标注 fencing 扩展点（不假装它完美）。
> - 用 `seq` 单调号 + 协议级 Last-Event-ID，替代原版 `__END__` 字符串标记和手写 cursor（更直白、更通用）。
> - 明确"不需要 CRDT"的边界判断。
> - **回归演进式**：最小版先跑通，再逐个加固（原版 9.6-9.9 的好节奏，我之前一度丢了，现在找回来）。

### 9.2 动手：先跑通"多端能看到同一条流"（最小版）

> **演进纪律**：本章**不**一次把 seq/游标/Last-Event-ID/单一写者全堆上。先给**最小能跑版**——只解决"多端看到同一条流"这一件事。然后 9.6-9.9 按"一个痛点 → 一处加固"的节奏逐个补：先发现"多端重复触发"→才加单一写者；先发现"晚加入漏 chunk"→才加 seq 游标；先发现"断线重连丢内容"→才加 Last-Event-ID。**每节只新增一个概念。**

本章只引一个新文件 `SyncStreamBus`（Redis 第 2 章已连）。chunk 总线先用 **Redis Streams**（第 12 章再升级 Kafka）。

#### 9.2.1 最小版 SyncStreamBus：XADD 持久 + Pub/Sub 广播

最小版只做两件事：① LLM 的每个 chunk **XADD 进 Streams**（持久，晚加入能读历史）；② 同步 **PUBLISH 到频道**（实时通知所有订阅者）。**先不管 seq、不管去重、不管单一写者**——那是后面发现痛点才加的。

**【新建文件】** `research-agent/src/main/java/com/example/research/stream/SyncStreamBus.java`（最小版）：

```java
package com.example.research.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 多端同步流总线（最小版：XADD 持久 + Pub/Sub 广播）。
 *
 * 这一版只解决一件事：多端能看到同一条流。
 *   写：upstream 每个 chunk → XADD（持久，晚加入可读历史）+ PUBLISH（实时广播）
 *   读：先 range 读历史（回放）+ concatWith 接 Pub/Sub（实时）
 *
 * ⚠️ 刻意简陋（后面 9.6-9.9 逐个补）：
 *   - 没有单一写者：多端同时请求会各自触发一次 LLM（重复触发，烧 token）→ 9.6 补
 *   - 没有 seq：range 和订阅之间有漏 chunk 竞态 → 9.7 补
 *   - 没有 Last-Event-ID：断线重连丢内容 → 9.8 补
 *   - 没有 MAXLEN：Stream 无限增长撑爆 Redis → 9.9 补
 * 先把这版跑通、理解"持久 + 广播"怎么协作，再一节节看为什么必须加固。
 */
@Component
public class SyncStreamBus {

    private static final Logger log = LoggerFactory.getLogger(SyncStreamBus.class);
    private static final String KEY_STREAM = "sync:%s:chunks";
    private static final String CHANNEL    = "sync:%s";

    private final ReactiveRedisTemplate<String, String> redis;
    private final ReactiveRedisMessageListenerContainer listener;

    public SyncStreamBus(ReactiveRedisTemplate<String, String> redis,
                         ReactiveRedisMessageListenerContainer listener) {
        this.redis = redis;
        this.listener = listener;
    }

    /**
     * 订阅会话流：把 upstream 的每个 chunk 写进 Redis（持久+广播），
     * 同时返回"回放历史 + 接实时"的 Flux 给所有订阅者。
     */
    public Flux<String> subscribe(String sessionId, Flux<String> upstream) {
        String streamKey = KEY_STREAM.formatted(sessionId);
        String channel   = CHANNEL.formatted(sessionId);

        log.info("[SyncBus] 启动 upstream，写 Redis (session={})", sessionId);
        // 后台 fire-and-forget：写 Redis。HTTP 响应不阻塞，直接从 Redis 读。
        upstream.flatMap(chunk -> writeChunk(streamKey, channel, chunk))
                .doOnComplete(() -> {
                    redis.convertAndSend(channel, "__END__").subscribe();   // 通知订阅者流结束
                    log.info("[SyncBus] 流完成 (session={})", sessionId);
                })
                .subscribe();

        return replayThenListen(streamKey, channel);
    }

    /** 写一条 chunk：XADD（持久）+ PUBLISH（广播）。 */
    private Mono<Void> writeChunk(String streamKey, String channel, String chunk) {
        return redis.opsForStream().add(streamKey, Map.of("chunk", chunk))
                .then(redis.convertAndSend(channel, chunk));
    }

    /** 回放历史 + 接实时：先 range 全量，再 concatWith Pub/Sub。 */
    private Flux<String> replayThenListen(String streamKey, String channel) {
        Flux<String> history = redis.opsForStream().range(streamKey, Range.unbounded())
                .map(r -> (String) r.getValue().get("chunk"));
        Flux<String> live = listener.receive(ChannelTopic.of(channel))
                .map(msg -> msg.getMessage())
                .takeUntil("__END__"::equals)
                .filter(m -> !"__END__".equals(m));
        return history.concatWith(live);
    }
}
```

#### 9.2.2 ResearchService：upstream 包进 SyncStreamBus

**【改已有文件，完整版覆盖】** `ResearchService.java`：

```java
package com.example.research;

import com.example.research.kb.KnowledgeBaseTool;
import com.example.research.llm.ToolCallingLoop;
import com.example.research.session.ChatMemoryStore;
import com.example.research.stream.SyncStreamBus;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 第 9 章：研究服务经 SyncStreamBus 实现多端同步。
 * 同一 sessionId 在任何实例、任何设备访问，内容逐字一致。
 */
@Service
public class ResearchService {

    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final ChatMemoryStore memory;
    private final SyncStreamBus bus;

    public ResearchService(ToolCallingLoop agentLoop, WebSearchTool searchTool,
                           KnowledgeBaseTool knowledgeBaseTool, ChatMemoryStore memory,
                           SyncStreamBus bus) {
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
        this.memory = memory;
        this.bus = bus;
    }

    public Flux<String> research(String topic, String sessionId) {
        return memory.load(sessionId)
                .flatMapMany(history -> {
                    StringBuilder collected = new StringBuilder();
                    Flux<String> upstream = agentLoop.run(
                            "你是研究助理。结合历史继续研究。绝不编造。",
                            "研究主题：" + topic,
                            history,
                            List.of(searchTool.asTool(), knowledgeBaseTool.asTool()))
                            .timeout(Duration.ofSeconds(60))
                            .doOnNext(collected::append)
                            .doOnComplete(() -> memory.append(sessionId, topic, collected.toString()).subscribe());
                    return bus.subscribe(sessionId, upstream);
                });
    }
}
```

#### 9.2.3 Controller：朴素 SSE（先不堆 id/event/retry）

**【改已有文件，完整版覆盖】** `ResearchController.java`。最小版用最朴素的 SSE——`Flux<String>` + 心跳。**协议级的 `id`/`event`/`Last-Event-ID` 不在这版**，等 9.8（断线重连）痛点出现才加。

```java
package com.example.research;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 研究接口 Controller（第 9 章最小版：朴素 SSE + 心跳）。
 * 后续 9.8 才把协议级 id/event/Last-Event-ID 加上。
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchService researchService;

    public ResearchController(ResearchService researchService) {
        this.researchService = researchService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> research(@RequestParam String topic, @RequestParam String sessionId) {
        Flux<ServerSentEvent<String>> data = researchService.research(topic, sessionId)
                .map(c -> ServerSentEvent.<String>builder().data(c).build());
        // 心跳：每 1s 一条注释行。客户端断开后，下一个心跳写失败 → cancel → 传到下游。
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        return data.mergeWith(heartbeat).takeUntilOther(data.then());
    }
}
```

### 9.3 验证最小版

```bash
docker run -d --name research-redis -p 6379:6379 redis:7-alpine
mvn spring-boot:run

# 终端1
curl -N "http://localhost:8080/api/research?topic=2026推理框架&sessionId=sync-001"
# 终端2（晚 5 秒，同 sessionId）——能看到前文 + 继续实时
curl -N "http://localhost:8080/api/research?topic=2026推理框架&sessionId=sync-001"
```

**这版能跑通什么**：终端2 先回放终端1 已输出的内容（XADD 持久），再接实时（Pub/Sub 广播）——**多端看到同一条流**的核心已经成立。

**但别急**：仔细看会发现两个问题——① 终端2 也带了 `topic` 参数，它**又触发了一次 LLM**（看日志有两次 upstream 启动）；② 高并发下终端2 偶尔**少几个字**（漏 chunk 竞态）。这正是 9.6、9.7 要逐个解决的。

### 9.4 最小版的隐患清单（后面逐个补）

| 隐患 | 现象 | 何时暴露 | 在哪补 |
|------|------|---------|--------|
| 多端重复触发 | 终端2 也带 topic，又跑一次 LLM，烧 token、结果分叉 | 本地就能复现 | 9.6（单一写者）|
| 漏 chunk | range 读到 N、订阅生效前 N+1 被 PUBLISH，漏掉 N+1 | 高并发偶发 | 9.7（seq 游标）|
| 断线重连丢内容 | 没有协议级 id，浏览器刷新/断线后无法从断点续传 | 刷新页面就暴露 | 9.8（Last-Event-ID）|
| Stream 无限增长 | XADD 无上限，冷数据撑爆 Redis | 上线后长期运行 | 9.9（MAXLEN + cancel）|

> **演进纪律的体现**：最小版只覆盖"多端看到同一条流"。后四节每节只解决一个隐患，每节只引入一个新概念（锁 / seq / 协议级 id / MAXLEN）。**不提前搬后面才用到的代码。**

### 9.5 checkpoint（最小版）

```
research-agent/src/main/java/com/example/research/stream/
└── SyncStreamBus.java          （新增：XADD 持久 + Pub/Sub 广播，最小版）
```

```bash
git add -A && git commit -m "第9章(最小版)：Redis Streams+Pub/Sub多端同步骨架"
```

---

### 9.6 加固①：单一写者——多端不重复触发 LLM

**痛点**：9.3 验证里，终端2 带 `topic` 又触发了一次 LLM。多端同步的**头号难题**：全集群只能有一个实例真正去调 LLM，否则重复触发、烧 token、结果分叉。

**解法**：`SETNX` 锁。第一个请求抢到锁才启动 upstream；后来的请求抢不到锁，**只读流、不触发**。

**【改已有文件，完整版覆盖】** `SyncStreamBus.java`（在最小版基础上，subscribe 内加 SETNX，标 `▼ 加固①`）：

```java
package com.example.research.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class SyncStreamBus {

    private static final Logger log = LoggerFactory.getLogger(SyncStreamBus.class);
    private static final String KEY_STREAM = "sync:%s:chunks";
    private static final String CHANNEL    = "sync:%s";
    private static final String KEY_LOCK   = "sync:%s:lock";          // ▼ 加固①：SETNX 锁
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);    // ▼ 加固①：锁 TTL，防崩溃死锁

    private final ReactiveRedisTemplate<String, String> redis;
    private final ReactiveRedisMessageListenerContainer listener;

    public SyncStreamBus(ReactiveRedisTemplate<String, String> redis,
                         ReactiveRedisMessageListenerContainer listener) {
        this.redis = redis;
        this.listener = listener;
    }

    public Flux<String> subscribe(String sessionId, Flux<String> upstream) {
        String streamKey = KEY_STREAM.formatted(sessionId);
        String channel   = CHANNEL.formatted(sessionId);
        String lockKey   = KEY_LOCK.formatted(sessionId);

        // ▼ 加固①：SETNX 抢锁。只有抢到锁的请求才启动 upstream（单一写者）。
        return redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL)
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        log.info("[SyncBus] 获得锁(单一写者)，启动 LLM (session={})", sessionId);
                        upstream.flatMap(chunk -> writeChunk(streamKey, channel, chunk))
                                .doOnComplete(() -> {
                                    redis.delete(lockKey).subscribe();   // 完成 清锁
                                    redis.convertAndSend(channel, "__END__").subscribe();
                                    log.info("[SyncBus] 流完成 (session={})", sessionId);
                                })
                                .doOnError(err -> { redis.delete(lockKey).subscribe();   // 出错也清锁
                                    log.error("[SyncBus] 流错误: {}", err.getMessage()); })
                                .subscribe();
                    } else {
                        log.info("[SyncBus] 锁已被占用，只读流不触发 (session={})", sessionId);
                    }
                    // 无论是否抢到锁，都从 Redis 读——保证所有订阅者内容一致
                    return Mono.just(replayThenListen(streamKey, channel));
                })
                .flatMapMany(flux -> flux);
    }

    private Mono<Void> writeChunk(String streamKey, String channel, String chunk) {
        return redis.opsForStream().add(streamKey, Map.of("chunk", chunk))
                .then(redis.convertAndSend(channel, chunk));
    }

    private Flux<String> replayThenListen(String streamKey, String channel) {
        Flux<String> history = redis.opsForStream().range(streamKey, Range.unbounded())
                .map(r -> (String) r.getValue().get("chunk"));
        Flux<String> live = listener.receive(ChannelTopic.of(channel))
                .map(msg -> msg.getMessage())
                .takeUntil("__END__"::equals)
                .filter(m -> !"__END__".equals(m));
        return history.concatWith(live);
    }
}
```

**验证**：再跑 9.3 的两个终端，日志里**只有一次** `[SyncBus] 获得锁`——终端2 不再重复触发 LLM。

> **这版简陋处（诚实）**：SETNX 有"锁过期后旧持有者继续写"的风险（fencing 问题）。本章先用最简版，附录讲 fencing token 扩展点。**不假装它完美**——学习文档的诚实比"看起来完整"重要。

### 9.7 加固②：seq 游标——消除漏 chunk 竞态

**痛点**：9.4 隐患清单的"漏 chunk"。`replayThenListen` 的 `range` 读到 N、`concatWith` 接 Pub/Sub——这两步之间有个微秒级窗口：N+1 被 XADD+PUBLISH，但此刻订阅还没生效 → N+1 的 PUBLISH 收不到、历史已结束不会重读 → **晚加入的设备少一个字**。本地几乎测不出，线上高并发必现。

**解法**：给每个 chunk 一个单调 `seq`（`INCR`）。**Pub/Sub 只当通知铃，数据永远按游标从 Streams 增量读**。通知早到/晚到/丢了都不影响正确性——下一条通知来了，从游标读出所有积压 chunk。

**【改已有文件，完整版覆盖】** `SyncStreamBus.java`（在加固①基础上，加 seq + 游标读，标 `▼ 加固②`）：

```java
package com.example.research.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SyncStreamBus {

    private static final Logger log = LoggerFactory.getLogger(SyncStreamBus.class);
    private static final String KEY_STREAM = "sync:%s:chunks";
    private static final String CHANNEL    = "sync:%s";
    private static final String KEY_LOCK   = "sync:%s:lock";
    private static final String KEY_SEQ    = "sync:%s:seq";            // ▼ 加固②：单调 seq 来源（INCR）
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final ReactiveRedisTemplate<String, String> redis;
    private final ReactiveRedisMessageListenerContainer listener;

    public SyncStreamBus(ReactiveRedisTemplate<String, String> redis,
                         ReactiveRedisMessageListenerContainer listener) {
        this.redis = redis;
        this.listener = listener;
    }

    public Flux<String> subscribe(String sessionId, Flux<String> upstream) {
        String streamKey = KEY_STREAM.formatted(sessionId);
        String channel   = CHANNEL.formatted(sessionId);
        String lockKey   = KEY_LOCK.formatted(sessionId);
        String seqKey    = KEY_SEQ.formatted(sessionId);

        return redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL)
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        log.info("[SyncBus] 获得锁，启动 LLM (session={})", sessionId);
                        upstream.flatMap(chunk -> writeChunk(streamKey, channel, seqKey, chunk))
                                .doOnComplete(() -> {
                                    redis.delete(lockKey).subscribe();
                                    redis.convertAndSend(channel, "__END__").subscribe();
                                })
                                .doOnError(err -> { redis.delete(lockKey).subscribe();
                                    log.error("[SyncBus] 流错误: {}", err.getMessage()); })
                                .subscribe();
                    }
                    // 加固②：游标从 0 开始（首次订阅）；9.8 会把它和 Last-Event-ID 接上
                    return Mono.just(replayThenListen(streamKey, channel, 0));
                })
                .flatMapMany(flux -> flux);
    }

    /** ▼ 加固②：写 chunk 时带 seq。INCR → XADD(seq+chunk) → PUBLISH(通知铃，内容不直接用)。 */
    private Mono<Void> writeChunk(String streamKey, String channel, String seqKey, String chunk) {
        return redis.opsForValue().increment(seqKey)
                .flatMap(seq -> redis.opsForStream().add(streamKey, Map.of("seq", String.valueOf(seq), "chunk", chunk))
                        .then(redis.convertAndSend(channel, seq + "::" + chunk)));   // 通知带 seq，但读侧按游标读
    }

    /** ▼ 加固②：游标回放。先读历史推进游标，再 Pub/Sub 收到通知就从游标增量读——不漏不重。 */
    private Flux<String> replayThenListen(String streamKey, String channel, long fromSeq) {
        AtomicReference<Long> cursor = new AtomicReference<>(fromSeq);
        Flux<String> history = readAfter(streamKey, cursor);
        Flux<String> live = listener.receive(ChannelTopic.of(channel))
                .takeUntil(m -> m.startsWith("__END__"))
                .flatMap(notify -> readAfter(streamKey, cursor));   // 通知触发"去读一次"
        return history.concatWith(live)
                .takeUntil(s -> s.endsWith("::__END__"))
                .filter(s -> !s.endsWith("::__END__"));
    }

    private Flux<String> readAfter(String streamKey, AtomicReference<Long> cursor) {
        return redis.opsForStream().range(streamKey, Range.unbounded())
                .filter(r -> seqOf(r) > cursor.get())
                .map(r -> { cursor.set(seqOf(r)); return seqOf(r) + "::" + r.getValue().get("chunk"); });
    }

    private long seqOf(MapRecord<String, String, String> r) {
        try { return Long.parseLong(r.getValue().get("seq")); }
        catch (Exception e) { return 0; }
    }
}
```

> **为什么用 `INCR` 生成 seq 而不是 Streams 自带 recordId**：recordId 是 `时间戳-序号`，不连续、不好按数值比较。`INCR` 拿到 1,2,3... 连续单调，`seq > cursor` 一行判断即可。

> **这版输出格式变成了 `seq::chunk`**——Controller 也要跟着解析（9.8 会把它映射成 SSE 的 `id`）。9.8 之前，前端可以简单 `split("::")[1]` 取内容。**这就是"逐个加固"带来的小步迭代**：seq 一加，契约微调，下一节顺势接上 Last-Event-ID。

### 9.8 加固③：协议级 SSE——Last-Event-ID 断线续传

**痛点**：用户刷新页面 / 网络抖动断开，再连回来想接着看——但朴素 SSE 没有 resume 机制，重连只能从头来或丢内容。

**解法**：把 9.7 的 `seq` 映射成 SSE 协议原生的 `id` 字段。浏览器 `EventSource` 会自动记录最后收到的 `id`，**断线重连时自动放进 `Last-Event-ID` 请求头**——服务端读这个头，从该 seq 之后补推。顺带把 `event:` 分类（token/done）和 `retry:` 也加上，让前端能干净区分事件类型、控制重连节奏。

**【改已有文件，完整版覆盖】** `ResearchController.java`（解析 `seq::chunk` → 协议级 SSE，标 `▼ 加固③`）：

```java
@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> research(@RequestParam String topic, @RequestParam String sessionId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    // ▼ 加固③：service.research 现在接收 fromSeq（断线重连从该 seq 之后补推）
    Flux<ServerSentEvent<String>> data = researchService.research(topic, sessionId, lastEventId != null ? lastEventId : 0)
            .map(s -> {
                int idx = s.indexOf("::");
                long seq = Long.parseLong(s.substring(0, idx));
                return ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))     // ▼ 协议级 id：浏览器记录，断线自动带 Last-Event-ID
                        .event("token")              // ▼ 事件分类
                        .data(s.substring(idx + 2))
                        .build();
            })
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("").build());

    Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
            .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
    ServerSentEvent<String> retry = ServerSentEvent.<String>builder()
            .retry(Duration.ofSeconds(3).toMillis()).build();   // ▼ 断线重连节奏
    return Flux.concat(Flux.just(retry), data.mergeWith(heartbeat).takeUntilOther(data.then()));
}
```

`ResearchService.research` 签名加 `fromSeq`，传给 `SyncStreamBus.replayThenListen(streamKey, channel, fromSeq)`（9.7 已留好这个参数）。**这就是 9.7 预留 `fromSeq` 的用途兑现**——每步加固都为下一步留接口。

### 9.9 加固④：MAXLEN 封顶 + cancel 传回——防撑爆、防烧 token

**痛点①**：XADD 无上限，会话长了 Stream 无限增长，冷数据撑爆 Redis。**解法**：`XTRIM MAXLEN ~` 近似裁剪。

**痛点②**：前端断开后，upstream 还在后台跑、还在烧 token。**解法**：把 upstream 的 `Disposable` 存下来，绑到返回 Flux 的 `doFinally`，前端断开（cancel）时 `dispose()` 停掉 LLM。

这两个加固的完整 `SyncStreamBus` 终态代码，和第 10 章 runId 化后的版本合并给出（见 **第 10 章 10.2.1** 的 `SyncStreamBus` 终态版——它同时包含 MAXLEN、cancel、退避重试、fencing 标注）。**本章读到这，理解了"为什么需要 MAXLEN 和 cancel"即可，可照抄的终态代码在第 10 章。**

> **为什么不在这重复贴一遍**：第 10 章会把流 key 从 sessionId 改成 runId（一次会话可多次研究），届时 `SyncStreamBus` 会重写一遍并包含这两个加固。**演进纪律**：避免同一个文件在 9.9 和 10.2 贴两份完整版造成"到底抄哪个"的困惑——以最后一版（第 10 章 runId 版）为准。

### 9.10 checkpoint + 复盘（第 9 章全节）

```bash
git add -A && git commit -m "第9章：多端同步四步加固（单一写者+seq游标+Last-Event-ID+MAXLEN/cancel）"
```

**做了**：调研企业级多端同步（传输/单一写者/补推/去重/状态一致边界），据此**一步步**落地：最小版（持久+广播）→ 单一写者（SETNX）→ seq 游标（防漏）→ 协议级 SSE（Last-Event-ID 续传）→ MAXLEN/cancel（防撑爆/烧 token）。

**核心跃迁**：从"单实例内存热流"到"Redis Streams + Pub/Sub 集中分发"。**每一步只解决一个痛点、只引入一个概念**——这是学习文档应有的节奏，不是一上来堆工业级终态。

**我的判断**：原版的"三层广播 + __END__"方向对；我用 `seq + Last-Event-ID` 更直白，且语义和 Kafka offset 同构。补强 A 讲透 SSE/WebSocket/Flux 分层，补强 C 端到端证明"A 输出30%时B打开"场景。

---

## 第 10 章：管数分离——触发与订阅解耦

### 10.0 场景：一个接口干了三件事

第 9 章后，产品提需求：**用户手机发起研究，中途切 iPad 继续，不能因切换就重新触发 LLM**。

第 9 章的接口 `GET /api/research?topic=...&sessionId=...` 一个接口干了三件事：触发 LLM（抢锁、跑 upstream）、返回流（订阅 Redis）、副作用（XADD 写 Redis）藏在"看似查询"的 GET 里——**违反 REST 语义**。更深的耦合：订阅流出问题时，错误冒泡会连累正在跑的 LLM。**触发和订阅绑在一起，一损俱损**。

### 10.1 思路：管理面（触发）与数据面（订阅）分离

把"触发"建模成一个**有生命周期的 run 资源**（OpenAI Assistants 式），管理面写、数据面只读：

```
管理面（触发）  POST /api/runs             → 幂等创建 run（Idempotency-Key），返回 201 + run 资源
管理面（状态）  GET  /api/runs/{runId}     → 查 run 状态机（queued/RUNNING/DONE/FAILED/CANCELLED）
管理面（取消）  POST /api/runs/{runId}/cancel → 主动停止
数据面（订阅）  GET  /api/runs/{runId}/stream → 纯只读 SSE，带 Last-Event-ID 重连
```

> **管数分离 ≠ 微服务拆分**：本章只在单进程内把接口拆开（逻辑解耦），物理拆成独立服务是第 13 章的事。**先逻辑后物理，顺序不能反。**

> **我的判断（相对原版的根本改进）**：原版/我初稿用 `POST /api/research` + `{status:"started"}`，太弱。**真实 AI 平台的管数分离标准是把触发建模成 run 资源**——有独立 id、状态机、可查询/取消/订阅，配 `Idempotency-Key` 防多端重复提交、SSE 协议级 `Last-Event-ID` 重连。补强 B 是这套设计的理论详解，下面 10.2 是可照抄的代码。

### 10.2 动手

> **演进纪律**：管数分离也是一步步加的，不是一上来就铺全套 run 资源。本章的演进顺序：
>
> 1. **最小分离**（10.2.1）：把第 9 章的 `subscribe()` 拆成 `trigger()`（管理面，POST）+ `subscribeReadOnly()`（数据面，GET 只读）。**只解决"触发和订阅解耦"这一件事**——第二台设备只发 GET，不再重复触发。
> 2. **runId 隔离**（10.2.1 合并）：流 key 从 sessionId 改成 runId——一次会话能发起多次研究（多个 run），互不干扰。这是"触发=创建一次研究"语义的自然结果。
> 3. **run 资源 + 状态**（10.2.3 `RunStore`/`GET /runs/{id}`）：触发返回一个有 id、有状态机的资源，让前端能轮询"是否完成"。
> 4. **幂等键**（10.2.3 `Idempotency-Key`）：多端同时点提交，用幂等键保证只创建一个 run。
> 5. **取消**（10.2.3 `POST /runs/{id}/cancel`）：前端要能主动停止。
>
> 下面 10.2.0 先给**最小分离版**（只做"触发和订阅拆成两个接口"这一件事），让你先看到最朴素的管数分离长什么样、它的局限在哪。然后 10.2.1 起逐步演进到 run 资源终态。**每步解决一个痛点，不是一蹴而就。**

#### 10.2.0 最小分离版：POST 触发 + GET 只读流（sessionId 维度，先不引入 run 资源）

**痛点驱动**：第 9 章一个 `GET /api/research?topic=...&sessionId=...` 同时干三件事（触发+订阅+写副作用）。最小一步：把它**拆成两个接口**——POST 触发（管理面）、GET 只读流（数据面）。**只解决"解耦"这一件事**，先不管幂等/状态/取消。

**【改已有文件，片段】** `SyncStreamBus`（在 9.9 基础上，把 `subscribe()` 拆成两个方法，标 `▼ 最小分离`）：

```java
// ▼ 最小分离：管理面——触发 LLM（抢锁+写 Redis），不返回流。
public Mono<Boolean> trigger(String sessionId, Flux<String> upstream) {
    String streamKey = "sync:" + sessionId + ":chunks";   // 这版仍用 sessionId 当 key
    String channel   = "sync:" + sessionId;
    String lockKey   = "sync:" + sessionId + ":lock";
    return redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(5))
            .doOnNext(acquired -> {
                if (Boolean.TRUE.equals(acquired)) {
                    upstream.flatMap(chunk -> writeChunk(streamKey, channel, chunk))
                            .doOnComplete(() -> { redis.delete(lockKey).subscribe();
                                redis.convertAndSend(channel, "__END__").subscribe(); })
                            .subscribe();
                }
            });
}

// ▼ 最小分离：数据面——纯只读订阅流（不触发、不抢锁、不写）。
public Flux<String> subscribeReadOnly(String sessionId, long lastSeq) {
    return replayThenListen("sync:" + sessionId + ":chunks", "sync:" + sessionId, lastSeq);
}
```

**【改已有文件，片段】** `ResearchController`（POST + GET 分离，标 `▼ 最小分离`）：

```java
@PostMapping   // 管理面：触发，返回 202
public Mono<ResponseEntity<Map<String, String>>> trigger(@RequestParam String topic, @RequestParam String sessionId) {
    return researchService.trigger(topic, sessionId)
            .thenReturn(ResponseEntity.accepted().body(Map.of("sessionId", sessionId, "status", "started")));
}

@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)   // 数据面：只读 SSE
public Flux<ServerSentEvent<String>> stream(@RequestParam String sessionId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    return researchService.subscribeReadOnly(sessionId, lastEventId != null ? lastEventId : 0)
            .map(s -> ServerSentEvent.<String>builder().data(s).build());
}
```

**验证最小分离版**：

```bash
curl -i -X POST "http://localhost:8080/api/research?topic=框架对比&sessionId=split-001"   # 202
curl -N "http://localhost:8080/api/research/stream?sessionId=split-001"                   # 只读订阅
# 第二台设备也只发 GET，不重复触发（日志只有一次"获得锁"）
```

**最小分离版的局限（驱动后续演进）**：
- 返回 `202 + {status:"started"}`——前端拿不到一个能轮询的"任务状态"，不知道何时完成。
- 流按 sessionId——一个会话同时只能有一次研究，想并发研究两个子问题做不到。
- 没有幂等——多端同时 POST 会各自触发（虽然 SETNX 兜底了"只跑一次"，但前端语义混乱）。
- 不能主动取消。

**这些局限，正是 10.2.1-10.2.3 演进到 run 资源的动力。** 下面给终态代码（runId 隔离 + run 资源 + 幂等 + 取消），它由这些步叠加而来。

#### 10.2.1 SyncStreamBus：subscribe() 拆成 trigger(runId) + subscribeReadOnly(runId)

**【改已有文件，完整版覆盖】** `SyncStreamBus.java`。相对第 9 章，本章做三件事：
1. **流按 `runId` 隔离**（不再用 sessionId）——这样同一会话可以发起多次研究（多个 run），互不干扰。这是补强 B "run 资源"的配套。
2. `subscribe(sessionId, ...)` 拆成 `trigger(runId, ...)`（管理面）+ `subscribeReadOnly(runId, ...)`（数据面）。
3. 补两个工业级加固：**`XTRIM MAXLEN` 封顶**（防 Redis 撑爆）、**cancel 传回 upstream**（前端断开能停 LLM，不再烧 token）。

> `replayThenListen` 沿用第 9 章的 **seq 游标版**（防漏 chunk）——契约统一，不回头用 naive 版。

```java
package com.example.research.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SyncStreamBus {

    private static final Logger log = LoggerFactory.getLogger(SyncStreamBus.class);

    // ▼ 第10章：key 按 runId 隔离（一会话可多次 run，互不干扰）
    private static final String KEY_STREAM = "run:%s:chunks";
    private static final String CHANNEL    = "run:%s";
    private static final String KEY_LOCK   = "run:%s:lock";
    private static final String KEY_SEQ    = "run:%s:seq";
    private static final String KEY_STATUS = "run:%s:status";
    private static final Duration LOCK_TTL   = Duration.ofMinutes(5);
    private static final Duration STREAM_TTL = Duration.ofHours(24);
    private static final long STREAM_MAXLEN = 10_000L;   // ▼ 工业级加固：MAXLEN 封顶

    private final ReactiveRedisTemplate<String, String> redis;
    private final ReactiveRedisMessageListenerContainer listener;

    public SyncStreamBus(ReactiveRedisTemplate<String, String> redis,
                         ReactiveRedisMessageListenerContainer listener) {
        this.redis = redis;
        this.listener = listener;
    }

    /** ▼ 第10章新增：管理面——触发 LLM（单一写者）。不返回流，只负责"跑起来 + 写 Redis"。 */
    public Mono<Boolean> trigger(String runId, Flux<String> upstream) {
        String streamKey = KEY_STREAM.formatted(runId);
        String channel   = CHANNEL.formatted(runId);
        String lockKey   = KEY_LOCK.formatted(runId);
        String seqKey    = KEY_SEQ.formatted(runId);

        return redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL)
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        setStatus(runId, "RUNNING").subscribe();
                        log.info("[SyncBus] trigger 获得锁，启动 LLM (run={})", runId);
                        // ▼ 工业级加固：把 Disposable 存下来，绑到前端断开时停 LLM
                        Disposable handle = upstream
                                .flatMap(chunk -> writeChunk(streamKey, channel, seqKey, chunk))
                                .doOnComplete(() -> finishStream(streamKey, channel, lockKey, runId))
                                .doOnError(err -> {
                                    redis.delete(lockKey).subscribe();
                                    setStatus(runId, "FAILED").subscribe();
                                    log.error("[SyncBus] 流错误 (run={}): {}", runId, err.getMessage());
                                })
                                .subscribe();   // fire-and-forget：不阻塞 HTTP 响应
                        // cancel 传回：管理面不持有 SSE Flux，这里仅记录 handle，
                        // 供未来"主动取消 run"接口调用 handle.dispose() 停 LLM。
                        // （前端断开走数据面的 doFinally，见 subscribeReadOnly。）
                        storeHandle(runId, handle);
                    }
                    return Mono.just(acquired);
                });
    }

    /** ▼ 第10章新增：数据面——纯只读订阅流（不触发、不抢锁、不写）。 */
    public Flux<String> subscribeReadOnly(String runId, long lastSeq) {
        Flux<String> output = replayThenListen(KEY_STREAM.formatted(runId), CHANNEL.formatted(runId), lastSeq);
        // 前端断开 → cancel → doFinally → dispose 掉本订阅（防资源泄漏）。
        // 注意：这里 dispose 的是"本订阅者的读取"，不是 LLM（LLM 可能有多个订阅者，停一个不影响其他）。
        return output.doFinally(sig -> {
            if (sig == SignalType.CANCEL) log.info("[SyncBus] 订阅者断开 (run={}, sig={})", runId, sig);
        });
    }

    /** ▼ 第10章新增：查询任务状态（管理面轮询用）。 */
    public Mono<String> status(String runId) {
        return redis.opsForValue().get(KEY_STATUS.formatted(runId)).defaultIfEmpty("NONE");
    }

    /** 主动取消 run（管理面扩展点：前端点"停止"时调）。 */
    public Mono<Void> cancel(String runId) {
        Disposable handle = removeHandle(runId);
        if (handle != null && !handle.isDisposed()) handle.dispose();
        return redis.delete(KEY_LOCK.formatted(runId))
                .then(setStatus(runId, "CANCELLED"))
                .then(redis.convertAndSend(CHANNEL.formatted(runId), "__END__::-1").then());
    }

    // —— 私有：写 chunk（MAXLEN 封顶 + 退避重试） ——

    private Mono<Void> writeChunk(String streamKey, String channel, String seqKey, String chunk) {
        return redis.opsForValue().increment(seqKey)
                .flatMap(seq -> {
                    StringRecord record = StreamRecords.string(Map.of("seq", String.valueOf(seq), "chunk", chunk))
                            .withStreamKey(streamKey);
                    return redis.opsForStream().add(record)
                            .flatMap(ignored -> redis.opsForStream().trim(streamKey, STREAM_MAXLEN, true))  // ▼ MAXLEN ~ 封顶
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))  // ▼ 退避重试（覆盖 Redis HA 故障转移空窗）
                            .onErrorResume(e -> { log.error("[SyncBus] XADD 失败: {}", e.getMessage()); return Mono.empty(); })
                            .then(redis.convertAndSend(channel, seq + "::" + chunk));   // 通知铃
                });
    }

    private void finishStream(String streamKey, String channel, String lockKey, String runId) {
        redis.opsForValue().get(KEY_SEQ.formatted(runId))
                .doOnNext(maxSeq -> redis.convertAndSend(channel, "__END__::" + maxSeq).subscribe())
                .then(redis.expire(streamKey, STREAM_TTL))
                .then(redis.delete(lockKey))
                .then(setStatus(runId, "DONE"))
                .doOnSuccess(v -> { removeHandle(runId); log.info("[SyncBus] 流完成 (run={})", runId); })
                .subscribe();
    }

    private Mono<Void> setStatus(String runId, String status) {
        return redis.opsForValue().set(KEY_STATUS.formatted(runId), status, Duration.ofMinutes(30)).then();
    }

    // —— seq 游标回放（第 9 章工业级版，防漏 chunk） ——

    private Flux<String> replayThenListen(String streamKey, String channel, long lastSeq) {
        AtomicReference<Long> cursor = new AtomicReference<>(lastSeq);
        Flux<String> history = readAfter(streamKey, cursor);
        Flux<String> live = listener.receive(ChannelTopic.of(channel))
                .takeUntil(m -> m.startsWith("__END__"))
                .flatMap(notify -> readAfter(streamKey, cursor));
        return history.concatWith(live)
                .takeUntil(s -> s.endsWith("::__END__"))
                .filter(s -> !s.endsWith("::__END__"));
    }

    private Flux<String> readAfter(String streamKey, AtomicReference<Long> cursor) {
        return redis.opsForStream().range(streamKey, Range.unbounded())
                .filter(r -> seqOf(r) > cursor.get())
                .map(r -> { cursor.set(seqOf(r)); return seqOf(r) + "::" + r.getValue().get("chunk"); });
    }

    private long seqOf(MapRecord<String, String, String> r) {
        try { return Long.parseLong(r.getValue().get("seq")); }
        catch (Exception e) { return 0; }
    }

    // —— run → Disposable 句柄（用于主动取消） ——

    private final java.util.concurrent.ConcurrentHashMap<String, Disposable> handles = new java.util.concurrent.ConcurrentHashMap<>();
    private void storeHandle(String runId, Disposable d) { handles.put(runId, d); }
    private Disposable removeHandle(String runId) { return handles.remove(runId); }
}
```

#### 10.2.2 ResearchService：暴露 trigger(runId) + subscribeReadOnly(runId)

**【改已有文件，完整版覆盖】** `ResearchService.java`（相对第 9 章，方法改为 `runId` 隔离）：

```java
package com.example.research;

import com.example.research.kb.KnowledgeBaseTool;
import com.example.research.llm.ToolCallingLoop;
import com.example.research.session.ChatMemoryStore;
import com.example.research.stream.SyncStreamBus;
import com.example.research.tool.WebSearchTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Service
public class ResearchService {

    private final ToolCallingLoop agentLoop;
    private final WebSearchTool searchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final ChatMemoryStore memory;
    private final SyncStreamBus bus;

    public ResearchService(ToolCallingLoop agentLoop, WebSearchTool searchTool,
                           KnowledgeBaseTool knowledgeBaseTool, ChatMemoryStore memory,
                           SyncStreamBus bus) {
        this.agentLoop = agentLoop;
        this.searchTool = searchTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
        this.memory = memory;
        this.bus = bus;
    }

    /** ▼ 第10章新增：管理面——触发一次 LLM（写 Redis，不返回流）。按 runId 隔离流。 */
    public Mono<Boolean> trigger(String topic, String sessionId, String runId) {
        return memory.load(sessionId)
                .flatMap(history -> {
                    StringBuilder collected = new StringBuilder();
                    Flux<String> upstream = agentLoop.run(
                            "你是研究助理。结合历史继续研究。绝不编造。",
                            "研究主题：" + topic,
                            history,
                            List.of(searchTool.asTool(), knowledgeBaseTool.asTool()))
                            .timeout(Duration.ofSeconds(60))
                            .doOnNext(collected::append)
                            .doOnComplete(() -> memory.append(sessionId, topic, collected.toString()).subscribe());
                    return bus.trigger(runId, upstream);   // ▼ 按 runId 隔离
                });
    }

    /** ▼ 第10章新增：数据面——只读订阅流（不触发）。 */
    public Flux<String> subscribeReadOnly(String runId, long lastSeq) {
        return bus.subscribeReadOnly(runId, lastSeq);
    }

    /** ▼ 第10章新增：任务状态（管理面轮询）。 */
    public Mono<String> status(String runId) { return bus.status(runId); }

    /** ▼ 第10章新增：主动取消 run（前端点"停止"时调）。 */
    public Mono<Void> cancel(String runId) { return bus.cancel(runId); }
}
```

#### 10.2.3 RunController：管数分离的企业级 REST（run 资源 + 幂等 + 协议级 SSE）

> **这一节是管数分离的最终版**——把"触发"建模成一个有生命周期的 **run 资源**（OpenAI Assistants 式），配 **Idempotency-Key 幂等键**（防多端重复提交）+ **SSE 协议级 `id/Last-Event-ID`**（断线重连自动补推）+ **`event: token/done` 分类**。原版/我初稿的 `202+{status}` 太弱；这一版才是真实 AI 平台的标准。补强 B 是这版的详解，本节是可照抄的代码。

**【新建文件】** `research-agent/src/main/java/com/example/research/run/RunStore.java`（run 资源 + 幂等映射，落 Redis）：

```java
package com.example.research.run;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Run 资源存储：每次研究是一个有 id、有状态机的异步任务。
 * 状态机：queued → RUNNING → DONE / FAILED / CANCELLED
 * 幂等：Idempotency-Key 头 → 同 key 返回同一个 runId（防多端重复提交）。
 */
@Component
public class RunStore {

    private static final String KEY_RUN  = "run:%s:status";
    private static final String KEY_IDEM = "idem:%s";   // idempotencyKey → runId
    private static final Duration TTL = Duration.ofDays(1);
    private final ReactiveRedisTemplate<String, String> redis;

    public RunStore(ReactiveRedisTemplate<String, String> redis) { this.redis = redis; }

    /** 幂等创建 run：同 idempotencyKey 返回同一 runId；否则新建。 */
    public Mono<String> create(String sessionId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return redis.opsForValue().get(KEY_IDEM.formatted(idempotencyKey))
                    .switchIfEmpty(Mono.defer(() -> newRun(sessionId)
                            .flatMap(runId -> redis.opsForValue().set(KEY_IDEM.formatted(idempotencyKey), runId, TTL)
                                    .thenReturn(runId))));
        }
        return newRun(sessionId);
    }

    private Mono<String> newRun(String sessionId) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String json = "{\"id\":\"" + runId + "\",\"session\":\"" + sessionId
                + "\",\"status\":\"queued\",\"createdAt\":\"" + Instant.now() + "\"}";
        return redis.opsForValue().set(KEY_RUN.formatted(runId), json, TTL).thenReturn(runId);
    }

    public Mono<Void> setStatus(String runId, String status) {
        return redis.opsForValue().get(KEY_RUN.formatted(runId))
                .flatMap(json -> redis.opsForValue().set(KEY_RUN.formatted(runId),
                        json.replaceAll("\"status\":\"[^\"]*\"", "\"status\":\"" + status + "\""), TTL).then());
    }

    public Mono<String> get(String runId) { return redis.opsForValue().get(KEY_RUN.formatted(runId)); }
}
```

**【新建文件，替换原 ResearchController】** `research-agent/src/main/java/com/example/research/run/RunController.java`：

```java
package com.example.research.run;

import com.example.research.ResearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * 管数分离的企业级 REST（run 资源）：
 *
 * 管理面（写/触发）：
 *   POST /api/runs?sessionId=X&topic=Y   头 Idempotency-Key → 201 + run 资源
 *   GET  /api/runs/{runId}               → 查状态（轮询）
 *   POST /api/runs/{runId}/cancel        → 主动取消
 *
 * 数据面（只读订阅）：
 *   GET  /api/runs/{runId}/stream        → SSE，带 Last-Event-ID 重连
 *
 * 多端同步：第二台设备只发 GET stream（浏览器自动带 Last-Event-ID），不重复创建 run。
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunStore runs;
    private final ResearchService researchService;

    public RunController(RunStore runs, ResearchService researchService) {
        this.runs = runs;
        this.researchService = researchService;
    }

    /** 管理面：幂等创建 run + 触发。返回 201 Created + run 资源。 */
    @PostMapping
    public Mono<ResponseEntity<String>> create(@RequestParam String sessionId,
                                               @RequestParam String topic,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return runs.create(sessionId, idemKey)
                .flatMap(runId -> runs.setStatus(runId, "queued")
                        .then(researchService.trigger(topic, sessionId, runId))   // 触发（runId 隔离流）
                        .thenReturn(ResponseEntity
                                .status(HttpStatus.CREATED)        // ▼ 201 Created
                                .location(URI.create("/api/runs/" + runId))
                                .body("{\"runId\":\"" + runId + "\",\"sessionId\":\"" + sessionId
                                        + "\",\"streamUrl\":\"/api/runs/" + runId + "/stream\"}")));
    }

    /** 管理面：查 run 状态（前端轮询）。 */
    @GetMapping("/{runId}")
    public Mono<ResponseEntity<String>> status(@PathVariable String runId) {
        return runs.get(runId)
                .map(json -> ResponseEntity.ok().body(json))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /** 管理面：主动取消 run（前端点"停止"时调）。 */
    @PostMapping("/{runId}/cancel")
    public Mono<Void> cancel(@PathVariable String runId) {
        return runs.setStatus(runId, "CANCELLED").then(researchService.cancel(runId));
    }

    /** 数据面：纯只读 SSE 订阅（带 Last-Event-ID 重连 + 协议级 id/event）。 */
    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String runId,
                                                @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        // resume：优先用 SSE 协议头 Last-Event-ID（浏览器断线重连自动带），否则从 0 开始
        long fromSeq = lastEventId != null ? lastEventId : 0;

        Flux<ServerSentEvent<String>> data = researchService.subscribeReadOnly(runId, fromSeq)
                .map(s -> {
                    int idx = s.indexOf("::");
                    long seq = Long.parseLong(s.substring(0, idx));
                    return ServerSentEvent.<String>builder()
                            .id(String.valueOf(seq))     // ▼ 协议级 id：浏览器记录，断线自动带 Last-Event-ID
                            .event("token")
                            .data(s.substring(idx + 2))
                            .build();
                })
                .concatWithValues(ServerSentEvent.<String>builder().event("done").data("").build());

        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());

        ServerSentEvent<String> retry = ServerSentEvent.<String>builder()
                .retry(Duration.ofSeconds(3).toMillis()).build();   // ▼ 断线重连节奏
        return Flux.concat(Flux.just(retry), data.mergeWith(heartbeat).takeUntilOther(data.then()));
    }
}
```

> **前端怎么配合（管数分离 + 多端同步）**：
> 1. 设备 A：`POST /api/runs?sessionId=Y&topic=X`（带 `Idempotency-Key: deviceA-001`）→ `201` + `{runId, streamUrl}`。
> 2. 设备 A：`new EventSource(streamUrl)` 订阅 SSE，浏览器自动记录每个事件的 `id`。
> 3. 设备 B（同 sessionId、同幂等键）：`POST` 带相同 `Idempotency-Key` → **返回同一个 runId**，后端不重复触发 LLM。
> 4. 设备 B：`new EventSource(streamUrl)` 订阅——**先从 Stream 回放前文（前 30% 立刻可见），再接实时**。断线时浏览器**自动**带 `Last-Event-ID` 重连续传。
> 5. 点"停止"：`POST /api/runs/{runId}/cancel`。
>
> **这正是"多端同步不重复触发 + 切换设备看到前文"的最终解**：幂等键保证唯一 run、单一写者保证唯一 LLM 调用、Stream+游标保证前文可见且不漏、SSE id 保证断线续传。

### 10.3 验证

```bash
# 1. 设备A 触发（管理面）——201 + run 资源（带幂等键）
curl -i -X POST "http://localhost:8080/api/runs?topic=2026框架对比&sessionId=split-001" \
  -H "Idempotency-Key: deviceA-001"
# HTTP/1.1 201 Created
# Location: /api/runs/run_abc123
# {"runId":"run_abc123","sessionId":"split-001","streamUrl":"/api/runs/run_abc123/stream"}

# 2. 设备A 订阅（数据面）
curl -N "http://localhost:8080/api/runs/run_abc123/stream"

# 3. 设备B（同幂等键再 POST）——返回同一个 runId，后端不重复触发（日志只有一次"trigger 获得锁"）
curl -i -X POST "http://localhost:8080/api/runs?topic=2026框架对比&sessionId=split-001" \
  -H "Idempotency-Key: deviceA-001"

# 4. 设备B 订阅（带 Last-Event-ID 断线续传）——内容与 A 一致，且先回放前文
curl -N -H "Last-Event-ID: 5" "http://localhost:8080/api/runs/run_abc123/stream"

# 5. 查状态 / 取消
curl "http://localhost:8080/api/runs/run_abc123"
curl -X POST "http://localhost:8080/api/runs/run_abc123/cancel"
```

### 10.4 checkpoint + 复盘

```
research-agent/src/main/java/com/example/research/
├── run/
│   ├── RunStore.java          （新增：run 资源 + 幂等映射）
│   └── RunController.java     （新增：管数分离企业级 REST）
├── stream/SyncStreamBus.java  （改：trigger(runId)+subscribeReadOnly(runId)+MAXLEN+cancel）
└── ResearchService.java       （改：trigger/subscribeReadOnly 按 runId）
```

```bash
git add -A && git commit -m "第10章：管数分离企业级（run资源+幂等键+协议级SSE+MAXLEN+cancel）"
```

**做了**：把"触发"建模成 **run 资源**（有 id、状态机、streamUrl），配 `Idempotency-Key` 幂等、`POST /runs` → `201`、`GET /runs/{id}` 查状态、`POST /runs/{id}/cancel` 取消、`GET /runs/{id}/stream` 协议级 SSE（`id`/`event`/`Last-Event-ID`/`retry`）。`SyncStreamBus` 按 runId 隔离流，补 MAXLEN 封顶和 cancel。

**我的判断**：原版/我初稿的 `202+{status}` 太弱。**真实 AI 平台（OpenAI Assistants）的管数分离标准是"run 资源 + 幂等键 + 协议级重连 + 事件分类"**——这才是"企业级"和"玩具 demo"的分水岭。补强 B 是这版的理论详解。

> **第 10 章是架构演进的核心**。到这里，"管数分离 + 多端同步"两个核心目标已经达成。后续 11-17 章是把这个分离的、同步的系统推向高可用、跨服务、可治理的企业级形态。

---


## 补强 A：传输层真相——SSE / WebSocket / Flux 到底用哪个

> 你问的这个问题很关键，很多人在这层概念混乱。我不省事，把这层彻底讲透。**这一节是第 9 章的"硬核补充"，比第 9 章主体更重要。**

### A.1 三个东西根本不在同一层（先把概念理清）

把 SSE / WebSocket / Flux 放在一起选，是常见的概念错误——它们**分属不同抽象层**：

| 概念 | 是什么 | 所在层 | 作用范围 |
|------|--------|--------|---------|
| **Flux** | Reactor 的响应式流抽象（`Flux<T>`） | **应用内部** | 进程内，描述"数据逐个产生"；**不是网络协议** |
| **SSE** | HTTP 之上的单向流式协议（`text/event-stream`） | **网络传输** | 服务器→客户端，单向 |
| **WebSocket** | 全双工双向通道（HTTP Upgrade → ws://） | **网络传输** | 双向 |

**关键认知**：
- **Flux 和 SSE 不互斥，是不同层**。Flux 是"服务端怎么把数据从 LLM 传到 HTTP 写出器"的内部管道；SSE 是"这些数据在网络上长什么样"。**你用 Flux 在服务端组装数据，用 SSE 在网络上传输——两者配合，不是二选一。**
- "用 Flux 还是用 SSE" 是**伪命题**。正确的问题是："服务端用 Flux 组装数据（内部）+ 用哪种网络协议（SSE 还是 WebSocket）传给前端（外部）"。

### A.2 业界 LLM 流式到底用哪个（真实情况，不是理论）

我按真实产品/平台说：

#### OpenAI / Anthropic 的 API 层 → SSE（事实标准）

OpenAI `/v1/chat/completions`（`stream:true`）、Anthropic `/v1/messages` streaming、DeepSeek、通义、各大模型厂商的官方 API —— **对外流式接口清一色 SSE**。

为什么？
- LLM 流式是**纯单向输出**（服务器吐 token，客户端只读）→ SSE 的单向语义完美匹配。
- HTTP 兼容：能过 CDN、网关、代理；SSE 就是普通 HTTP 长连接。
- 浏览器原生 `EventSource`，断线**自动重连并带 `Last-Event-ID` 头**（关键，后面讲）。
- 比 WebSocket 简单：不用协议升级、不用双向状态机、调试容易。

**结论**：LLM 流式输出，**SSE 是业界事实标准**。本项目就该用 SSE。

#### ChatGPT / Claude 的网页端 → WebSocket（前端需要双向）

但打开 ChatGPT 网页，前端和服务器之间**实际是 WebSocket**。为什么前端要用 WS 而不是 SSE？

因为网页端要**客户端→服务器**的控制指令：
- 用户点"⏹ 停止生成" → 客户端发 `{type:"cancel"}`。
- 客户端定时发**心跳**（保活、探活）。
- 切设备时同步指令。
- 边生成边发"追加分叉问题"。

SSE 是单向的，发不了这些 → 必须用 WebSocket 双向通道。

**但注意一个业界关键细节**：很多产品的 WebSocket 里，**LLM token 那一段的"形状"还是 SSE 风格的**——服务端把上游 LLM 的 SSE 流，在网关里"桥接"成 WebSocket 帧推给前端。即：

```
LLM 厂商 --SSE--> 你的网关/服务 --WebSocket 帧--> 浏览器
```

**SSE 在"服务器↔LLM"或"网关↔简单客户端"那一段，WS 在"网关↔富交互前端"那一段**。两者共存。

### A.3 本项目的正确选择 + 为什么

**本项目用 SSE**。理由：
1. 场景是"服务器单向吐 LLM token 给前端"——SSE 的语义正好匹配。
2. 多端同步靠"Redis Streams 多播 + seq"，不需要客户端发指令（取消用"客户端断连 → 心跳写失败 → cancel"实现，第 9 章已讲）。
3. SSE 浏览器原生支持、能过网关、自动重连——工程成本最低。

**何时升级到 WebSocket**（明确的演进边界，写进文档不藏着）：
- 需要"用户中途点停止"且不想靠断连实现（断连会丢心跳窗口）→ 上 WS 发 `{cancel}`。
- 需要"边生成边让用户输入" → 上 WS。
- 需要前端高频心跳/状态同步 → 上 WS。

**到那一步时，服务端 Flux 管道不用改**——只把最外层的"HTTP 写出器"从 SSE 编码换成 WebSocket handler。**这就是"Flux 在内、协议在外"分层的价值**：换传输不换业务。

### A.4 真实企业级 SSE 的细节（这才是重点，别只会 `Flux<String>` 往外丢）

很多人写 SSE 就 `Flux<String>` + `produces=text/event-stream`，以为完事了。**真实企业级 SSE 必须做对这些**（这是"玩具"和"企业级"的分水岭）：

SSE 协议每个事件可以有三个字段：

```
id: 42          ← 事件 id（= resume token，断线重连靠它）
event: token    ← 事件类型（可分类：token/done/error）
data: 你好      ← 数据载荷
retry: 3000     ← 重连等待毫秒数（浏览器断线后等多久重连）

```

企业级必须做的：

1. **每个事件带 `id:`**（单调递增，对应第 9 章的 `seq`）。客户端记录最后收到的 id。
2. **`Last-Event-ID` 头重连**：浏览器 `EventSource` 断线自动重连时，**会自动把最后收到的 `id:` 放进 `Last-Event-ID` 请求头**。服务端读这个头，从该 id 之后补推——这就是 resume 补推的**协议级标准做法**，不用自己发明 token 传递方式。
3. **`retry:` 字段**：告诉浏览器断线后等多少毫秒重连（默认 3 秒，LLM 场景可设长点防雪崩）。
4. **`event:` 分类**：`token`（正常 chunk）/ `done`（流结束）/ `error`（出错）——比用 `__END__` 字符串塞进 data 干净得多（这是我相对第 9 章的改进）。
5. **心跳注释行 `: ping`**：保活 + 感知断连（第 9 章已做）。

**企业级 SSE Controller 范例（带 id/event/Last-Event-ID，替换第 9 章 Controller）**：

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@RequestParam String sessionId,
                                            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    // lastEventId：浏览器断线重连时自动带上最后收到的 event id（resume 补推）
    long lastSeq = lastEventId == null ? 0 : lastEventId;

    Flux<ServerSentEvent<String>> data = researchService.subscribeReadOnly(sessionId, lastSeq)
            .map(s -> {
                // s 格式 "seq::chunk"（第 9 章 SyncStreamBus 约定）
                int idx = s.indexOf("::");
                long seq = Long.parseLong(s.substring(0, idx));
                String chunk = s.substring(idx + 2);
                return ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))     // ▼ 协议级 id：浏览器记录，断线重连自动带 Last-Event-ID
                        .event("token")              // ▼ 事件类型分类
                        .data(chunk)
                        .build();
            })
            .concatWithValues(ServerSentEvent.<String>builder().event("done").data("").build());  // ▼ 协议级结束

    Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
            .map(i -> ServerSentEvent.<String>builder().comment("ping").build());

    // retry: 告诉浏览器断线后等 3s 重连
    ServerSentEvent<String> retry = ServerSentEvent.<String>builder().retry(Duration.ofSeconds(3).toMillis()).build();
    return Flux.concat(Flux.just(retry), data.mergeWith(heartbeat).takeUntilOther(data.then()));
}
```

> **这段代码相对原版/第 9 章的进步**：
> - 用 SSE 协议原生的 `id:` + `Last-Event-ID` 头做 resume，**而不是自己发明 seq 传递**——浏览器自动处理重连和 id 记录。
> - 用 `event: done/error` 替代 `__END__` 字符串——协议级语义，前端 `addEventListener('done', ...)` 干净分发。
> - 用 `retry:` 控制重连节奏。
>
> **这才是"真实企业级 SSE"**。`Flux<String>` 只是服务端内部组装数据的手段，不是 SSE 的全部。

### A.5 Flux 在这套里到底扮演什么角色（回应你的疑问）

把整条链路画清楚：

```
LLM(Mock)  ──Flux<String>──→  SyncStreamBus(写Redis) ──Flux──→ 订阅者读出 Flux<String>
                                                                    │
                                                         Controller 把 Flux<String>
                                                         映射成 Flux<ServerSentEvent>
                                                                    │
                                                         SSE 编码器 → 网络(text/event-stream) → 浏览器 EventSource
```

- **Flux 是全程的内部管道**：从 LLM 吐字、到写 Redis、到读出来，都是 `Flux<String>` 在串。
- **SSE 是最外层的网络编码**：Controller 把 `Flux<String>` 映射成 `Flux<ServerSentEvent<String>>`，Spring 的 SSE 编码器把它写成 `id:..\nevent:..\ndata:..\n\n` 的网络帧。
- **换 WebSocket 时**：把最外层换成 WebSocket handler，内部 Flux 管道不变。

**一句话总结**：**内部全用 Flux 组装数据，外部用 SSE 传输（这是 LLM 流式的事实标准）；要双向控制时，外部升级到 WebSocket，内部 Flux 不动。** 这个分层认知，比"选 Flux 还是 SSE"重要一百倍。

---

## 补强 B：管数分离的企业级真相——OpenAI Assistants 式的 run 资源

> 你说"不要偷懒省事"。这一节讲清**为什么管数分离要做成 run 资源**（理论 + 业界对照），**可照抄的代码已经在第 10 章 10.2.3 落地**（`RunStore` + `RunController`）。本节是第 10 章的"为什么这么设计"的详解，不重复贴代码。

### B.1 真实企业级管数分离的标准形态

OpenAI Assistants API 是管数分离的教科书级实现，它的 run 模型是这样的：

```
1. POST /threads/{thread_id}/runs      → 创建一个 run（异步任务）
   请求带 Idempotency-Key 头（防重复提交）
   返回 201 Created + run 资源对象：
     { id: "run_abc", status: "queued", thread_id, created_at, ... }

2. GET  /runs/{run_id}                 → 查 run 状态（轮询）
   返回 { status: "queued" → "in_progress" → "completed"/"failed", ... }

3. GET  /runs/{run_id}/stream (SSE)    → 流式订阅 run 的输出（纯只读）
   带 Last-Event-ID 重连
   多端订阅同一 run，只读不触发
```

**这套设计的精髓**：
- **触发 = 创建一个"run 资源"**（不是 `202` 含糊的"已接受"，而是 `201` 明确创建了一个有 id、有状态的任务对象）。
- **run 是一等公民**：它有独立 id、生命周期（queued→running→completed/failed）、可查询、可取消、可订阅。
- **单一执行者**：后端 worker 队列只让一个 worker 真正跑这个 run（分布式锁/lease）。
- **幂等创建**：`Idempotency-Key` 头保证"多端同时点提交"只创建一个 run。
- **读写分离**：创建/查状态是管理面（写/读元数据），订阅 stream 是数据面（只读输出）。

### B.2 为什么是 run 资源，而不是 `{status:"started"}`

`202 + {status:"started"}`（我之前第 10 章的写法）的问题：
- 没有独立的 run id（只有 sessionId），无法精确表达"这个会话的第 N 次研究"。
- 没有状态机（queued/running/completed/failed），前端只能猜。
- 没有幂等，多端同时提交会重复创建。

**run 资源解决了全部**：
- run id 精确标识"某次研究"（一个会话可以有多次 run）。
- 状态机让前端能正确轮询、知道何时去订阅、何时该看历史。
- 幂等键防多端重复触发（这正是管数分离要解决的"切换设备重复触发"痛点的协议级解法）。

### B.3 落地代码在哪里

**可照抄的完整代码已在第 10 章 10.2 落地**（`RunStore` + `RunController` + `SyncStreamBus.trigger(runId)`），这里不重复贴，只点出三个关键设计：

1. **流按 runId 隔离**（`run:{runId}:chunks`），而非 sessionId——一次会话可发起多次研究，互不干扰。
2. **trigger 带 runId + 状态推进**：开始 `RUNNING`、完成 `DONE`、出错 `FAILED`、取消 `CANCELLED`。
3. **subscribeReadOnly(runId, lastSeq)**：纯只读，按 runId 读流，seq 游标防漏。

对照第 10 章 10.2.3 的 `RunController`：`POST /api/runs`（201 + 幂等）、`GET /api/runs/{id}`（状态）、`POST /api/runs/{id}/cancel`（取消）、`GET /api/runs/{id}/stream`（协议级 SSE）。

### B.4 端到端走一遍（验证管数分离 + 多端同步真的成立）

```bash
# 1. 设备A：创建 run + 触发（带幂等键）
curl -i -X POST "http://localhost:8080/api/runs?sessionId=s1&topic=2026框架对比" \
  -H "Idempotency-Key: device-A-001"
# HTTP/1.1 201 Created
# Location: /api/runs/run_abc123
# {"runId":"run_abc123","sessionId":"s1","streamUrl":"/api/runs/run_abc123/stream"}

# 2. 设备A：订阅自己的 run（SSE）
curl -N "http://localhost:8080/api/runs/run_abc123/stream"

# 3. 设备B（iPad）：重复带同一幂等键创建 → 返回同一个 run（不重复触发）
curl -i -X POST "http://localhost:8080/api/runs?sessionId=s1&topic=2026框架对比" \
  -H "Idempotency-Key: device-A-001"
# → 同样返回 run_abc123，后端不会触发第二次 LLM

# 4. 设备B：直接订阅（只读，带 Last-Event-ID 断线续传）
curl -N -H "Last-Event-ID: 5" "http://localhost:8080/api/runs/run_abc123/stream"
# → 从 seq>5 之后补推，内容与设备A逐字一致

# 5. 轮询状态（前端判断该订阅还是看历史）
curl "http://localhost:8080/api/runs/run_abc123"
# → {"status":"in_progress"} 或 {"status":"completed"}
```

### B.5 这套相对"省事版"的进步（回应"不要偷懒"）

| 维度 | 省事版（原第10章） | 企业级版（补强B） |
|------|------------------|-----------------|
| 触发返回 | `202 + {status:started}` | `201 + run 资源`（有独立 id、状态机、streamUrl） |
| 幂等 | 无 | `Idempotency-Key` 头 → 多端重复提交只创建一个 run |
| 流隔离 | 按 sessionId（一会话只能一次研究） | 按 runId（一会话可多次研究，互不干扰） |
| 状态查询 | 无 | `GET /runs/{id}` 轮询状态机 |
| 重连续传 | 自定义 lastSeq 参数 | SSE 协议级 `Last-Event-ID` 头（浏览器自动带） |
| 事件语义 | `__END__` 字符串 | SSE `event: token/done` 协议级分类 |

**这套（run 资源 + 幂等键 + Last-Event-ID + 事件分类）就是 OpenAI Assistants 等真实 AI 平台的管数分离标准。** 不是我编的，是业界已验证的做法。

> **学习要点**：管数分离的"企业级"不在于"拆了两个接口"，而在于**把触发建模成一个有生命周期的资源（run）**，配上**幂等、状态机、协议级重连、事件分类**。这些细节才是"真实企业级"和"玩具 demo"的分水岭。

---


## 第 10.5 章：管理数据持久层——引入 H2（开发期零硬件，生产一行配置切 PG）

> **定位**：这一章解决全文最大的一个"妥协"——前面（第 7/8/10 章）把**用户数据**（会话历史、会话元信息、run 资源、审计）全压在 Redis 上。Redis 是内存型、TTL 会过期、故障转移会丢数据，而且**没法按条件查询**（用户搜历史、运营统计都做不到）。这些数据是**关系型数据库的活**。
>
> **本章引入 H2**（嵌入式文件库，零硬件、重启不丢）作为管理数据持久层。关键纪律：**用 Spring Data JPA 抽象 + 标准 DDL，代码不写任何 H2 方言**——这样生产换 PostgreSQL 时，只改 `application.yaml` 一行，代码零改动。
>
> **H2 的正确定位（诚实）**：H2 在企业里基本只用于开发/测试，**生产几乎没人用 H2 当主库**（多实例数据分裂、性能/功能受限）。所以 H2 是"零硬件的开发期存储 + 平滑切 PG 的垫脚石"，不是企业级生产存储。**这条边界我会写明，不藏。**

### 10.5.0 痛点：用户数据不该全压 Redis

盘点一下"该长期持久"的用户数据，现在的处境：

| 数据 | 现在存哪 | 痛点 |
|------|---------|------|
| 会话历史（user/assistant 消息） | Redis List `chat:{sid}` | TTL 7 天过期就丢；故障转移丢未同步的；没法搜索 |
| 会话元信息（标题/创建时间） | Redis Hash `sessions` | TTL 过就没了；没法按用户/时间查询 |
| run 资源（研究任务状态） | Redis `run:{id}:status` | TTL 30min 后查不到历史任务 |
| 审计日志 | Redis List `audit:{sid}` | TTL 7 天；法规要长期保留做不到 |

**核心问题**：内存型存储不该扛"持久 + 可查询 + 关系完整"的活。**该有一张关系表。**

> **实时流态（chunk）不动**：Redis Streams/Kafka 是高吞吐、24h 过期的流式数据，**不进数据库**——职责分离。本章只接管"管理面用户数据"。

### 10.5.1 思路：H2 文件库 + JPA，方言可切

| 决策 | 选择 | 理由 |
|------|------|------|
| 数据库 | **H2 文件库**（`jdbc:h2:file:./data/research`） | 嵌入式、零硬件、重启不丢；学习阶段无需装 PG/MySQL |
| 访问层 | **Spring Data JPA**（标准 ORM） | 方言自动适配 H2/PG；换库代码零改 |
| DDL | **标准 `schema.sql`**（ANSI SQL） | H2 和 PG 都能跑；比 `ddl-auto=update` 更显式可控 |
| 哪些数据进库 | 会话历史、会话元信息、run 资源、审计 | 该持久 + 可查询的管理数据 |
| 哪些不进 | chunk 流（Redis Streams/Kafka）、SETNX 锁（Redis） | 实时态/协调态，不是持久数据 |

> **为什么能"一行配置切 PG"**：JPA 屏蔽了方言差异。H2 和 PG 都支持标准 SQL + JPA 注解定义的表结构。换库时把 `spring.datasource.url/driver` 从 H2 改成 PG，JPA 自动用 PG 方言——**业务代码（Repository/Service）一行不动**。这就是"用对抽象"的红利。

### 10.5.2 动手

#### 10.5.2.1 pom 加依赖

**【改已有文件】** `pom.xml`，追加：

```xml
        <!-- 第 10.5 章：管理数据持久层 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <!-- H2：嵌入式文件库，开发期零硬件（生产换 postgresql 驱动） -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>
```

> **生产切 PG 时，加 `org.postgresql:postgresql` 驱动，H2 可保留也可移除**——驱动选择由 `spring.datasource.driver-class-name` 配置决定，代码无感。

#### 10.5.2.2 application.yaml：H2 数据源 + JPA

**【改已有文件】** `application.yaml`，追加：

```yaml
spring:
  datasource:
    # ▼ H2 文件库：重启不丢。生产换 PG 时改这一段即可：
    #   url: jdbc:postgresql://${PG_HOST:localhost}:5432/research
    #   driver-class-name: org.postgresql.Driver
    url: jdbc:h2:file:./data/research;AUTO_SERVER=TRUE   # AUTO_SERVER 允许多进程访问（开发期方便）
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: none                 # 用 schema.sql 显式建表，不靠 hibernate 自动生成（更可控、方言无关）
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
  sql:
    init:
      mode: always                   # 启动时执行 schema.sql（H2/PG 通用）
      schema-locations: classpath:schema.sql
```

> **`ddl-auto: none` + 标准 `schema.sql` 的纪律**：不靠 Hibernate 自动建表（它生成的 DDL 会带方言差异），而是手写**标准 ANSI SQL**——H2 和 PG 都能跑，是"换库零改"的关键。H2 的 `IF NOT EXISTS` 和 PG 都兼容。

#### 10.5.2.3 标准 DDL（H2/PG 通用）

**【新建文件】** `research-agent/src/main/resources/schema.sql`：

```sql
-- 管理/用户数据表（标准 ANSI SQL，H2 和 PostgreSQL 都能跑）
-- 切 PG 时这份 schema.sql 原样可用（H2 的 IF NOT EXISTS / BIGINT/ TIMESTAMP PG 都支持）

-- 会话元信息（替代 Redis Hash "sessions"）
CREATE TABLE IF NOT EXISTS app_session (
    id          VARCHAR(64)  PRIMARY KEY,
    title       VARCHAR(256),
    created_at  TIMESTAMP NOT NULL
);

-- 会话历史消息（替代 Redis List "chat:{sid}"）
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,     -- user / assistant
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id);

-- run 资源（替代 Redis "run:{id}:status"）
CREATE TABLE IF NOT EXISTS research_run (
    id          VARCHAR(64) PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,     -- queued/RUNNING/DONE/FAILED/CANCELLED
    created_at  TIMESTAMP NOT NULL,
    finished_at TIMESTAMP
);

-- 审计事件（替代 Redis List "audit:{sid}"）
CREATE TABLE IF NOT EXISTS audit_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    run_id      VARCHAR(64),
    type        VARCHAR(32) NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_event(session_id);
```

> **方言无关的几个要点**：①`BIGINT AUTO_INCREMENT`（H2）在 PG 是 `BIGSERIAL`——为完全通用，生产切 PG 时把这张表改成 `BIGSERIAL`，或用 JPA 的 `@GeneratedValue(strategy=IDENTITY)` 让框架处理（下面 entity 这么做）。②`TIMESTAMP` 两边都支持（不用 `TIMESTAMPTZ`，保持简单）。③`TEXT` 两边都支持。**entity 层用 JPA 注解，主键生成交给框架，DDL 层尽量用两边都有的类型**。

#### 10.5.2.4 JPA Entity（让框架屏蔽方言）

**【新建文件】** `research-agent/src/main/java/com/example/research/db/entity/ChatMessageEntity.java`（示例，其余 entity 同理）：

```java
package com.example.research.db.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 主键生成交框架，H2/PG 都适配
    private Long id;

    private String sessionId;
    private String role;
    @Column(length = 100000) private String content;
    private Instant createdAt;

    // getters/setters 省略（实际项目用 Lombok @Data）
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

> **`@GeneratedValue(IDENTITY)` 的作用**：主键自增交给 JPA 框架，H2 用 `AUTO_INCREMENT`、PG 用 `SERIAL`——**代码一样，方言不同由框架处理**。这就是 entity 层屏蔽方言的关键。

#### 10.5.2.5 Spring Data Repository（标准接口，零方言）

**【新建文件】** `research-agent/src/main/java/com/example/research/db/ChatMessageRepository.java`：

```java
package com.example.research.db;

import com.example.research.db.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 会话历史 Repository（标准 Spring Data，H2/PG 通用）。 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    // 方法名查询：框架自动生成 SQL，方言由 JPA 适配——换库零改
    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    // 需要自定义时用 JPQL（不是原生 SQL，跨库通用）
    @Query("select m from ChatMessageEntity m where m.content like %:kw%")
    List<ChatMessageEntity> searchByContent(String kw);
}
```

> **`run`/`session`/`audit` 的 Repository 同理建**（`ResearchRunRepository`/`SessionRepository`/`AuditEventRepository`），各自继承 `JpaRepository`。代码全是标准接口，不含任何 H2 字样。

#### 10.5.2.6 把数据访问从 Redis 切到 JPA（演进式：一类一类迁）

> **演进纪律**：不是一夜把所有数据都迁了。一类一类迁——先迁"用户最在意"的会话历史，验证通了再迁 run/审计。每迁一类，对应原来的 Redis 实现就退役。

**【改已有文件，片段】** `ChatMemoryStore` 的实现从 Redis List 改成调 Repository（接口不变，只换实现）：

```java
// ChatMemoryStore.java（原来读写 Redis List，现在读写 JPA）
@Component
public class ChatMemoryStore {

    private final ChatMessageRepository repo;
    public ChatMemoryStore(ChatMessageRepository repo) { this.repo = repo; }

    public Mono<List<LlmClient.LlmMessage>> load(String sessionId) {
        return Mono.fromCallable(() -> repo.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(e -> new LlmClient.LlmMessage(e.getRole(), e.getContent()))
                .toList())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());   // JDBC 阻塞→放弹性线程池
    }

    public Mono<Void> append(String sessionId, String user, String assistant) {
        return Mono.fromRunnable(() -> {
            Instant now = Instant.now();
            save(repo, sessionId, "user", user, now);
            save(repo, sessionId, "assistant", assistant, now);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void save(ChatMessageRepository repo, String sid, String role, String content, Instant now) {
        ChatMessageEntity e = new ChatMessageEntity();
        e.setSessionId(sid); e.setRole(role); e.setContent(content); e.setCreatedAt(now);
        repo.save(e);
    }
}
```

> **关键点：JDBC 是阻塞的，WebFlux 是响应式的**——直接在响应式链里调 `repo.save()` 会阻塞 Netty 事件循环线程。必须用 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 把阻塞调用丢到弹性线程池。**这是响应式 + 阻塞库混用的标准模式**，附录踩坑手册会强调。
>
> `ChatMemoryStore` 的**对外接口（load/append）没变**——所以 `ResearchService`、`ResearchController` 一行都不用改。**这就是分层抽象的红利**：存储从 Redis 换成 H2，上层无感。

`SessionStore`、`RunStore`、`AuditLogger` 同理：实现从 Redis 改成各自的 Repository，接口不变。迁完后，Redis 里 `chat:`/`sessions`/`run:`/`audit:` 这些 key 就可以清掉了——Redis 回归"实时流态 + 锁"的职责。

### 10.5.3 验证

```bash
mvn spring-boot:run
# 触发一次研究（会写 chat_message 表）
curl -i -X POST "http://localhost:8080/api/runs?topic=vLLM是什么&sessionId=db-001" -H "Idempotency-Key: db-1"
curl -N "http://localhost:8080/api/runs/<runId>/stream"

# H2 控制台查看数据（浏览器开 http://localhost:8080/h2-console，或直接看文件库）
# JDBC URL: jdbc:h2:file:./data/research  用户名 sa  无密码
# 能看到 app_session / chat_message / research_run / audit_event 表里有数据

# 重启应用，数据还在（文件库持久）
# 验证查询能力（Redis 做不到的）：
curl "http://localhost:8080/api/sessions/db-001/messages"   # 历史——现在从 H2 读，TTL 不会再丢
```

### 10.5.4 一行配置切 PostgreSQL（生产）

开发用 H2，上生产换 PG——**只改配置，代码零改**：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${PG_HOST:localhost}:5432/research
    driver-class-name: org.postgresql.Driver
    username: ${PG_USER:research}
    password: ${PG_PASSWORD:research}
  sql:
    init:
      mode: always            # schema.sql 同一份，PG 也能跑（AUTO_INCREMENT 改 SERIAL 见下）
```

pom 加 PG 驱动：

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

> **schema.sql 的小调整**：`BIGINT AUTO_INCREMENT` 是 H2 写法，PG 用 `BIGSERIAL`。要完全通用，两选一：
> - 简单：保留两份 schema（`schema-h2.sql` / `schema-pg.sql`），`spring.sql.init.schema-locations` 按 profile 选。
> - 优雅：entity 用 `@GeneratedValue(IDENTITY)` + `ddl-auto=update`，让 Hibernate 按方言建表（牺牲"显式 DDL"换"零方言差异"）。
> 生产推荐前者（显式可控），学习期用 H2 的 `AUTO_INCREMENT` 即可。

### 10.5.5 checkpoint + 复盘

```
research-agent/src/main/java/com/example/research/db/
├── entity/
│   ├── ChatMessageEntity.java
│   ├── AppSessionEntity.java
│   ├── ResearchRunEntity.java
│   └── AuditEventEntity.java
├── ChatMessageRepository.java
├── SessionRepository.java
├── ResearchRunRepository.java
└── AuditEventRepository.java
research-agent/src/main/resources/
└── schema.sql                     （标准 DDL，H2/PG 通用）
```

```bash
git add -A && git commit -m "第10.5章：管理数据持久层（H2+JPA，一行配置切PG）"
```

**做了**：引入 H2 文件库 + Spring Data JPA，把用户数据（会话历史/会话元信息/run/审计）从 Redis 迁到关系库。标准 DDL + JPA 抽象保证换库零改代码。Redis 回归实时流态/锁职责。

**核心跃迁**：从"管理数据全压 Redis（妥协）"到"该持久的落关系库"。**学习用 H2（零硬件），生产换 PG（改配置）**——这是企业级"数据库类型是配置项而非代码差异"的标准实践。

**工程教训**：
- **用对抽象屏蔽方言**：JPA + 标准 DDL 是"换库零改"的前提。如果代码里写死了 H2 方言，切 PG 就要改一大片。
- **响应式 + 阻塞库要隔离线程**：JDBC 阻塞调用必须 `subscribeOn(boundedElastic)`，不能阻塞 Netty 事件循环。
- **H2 是垫脚石不是终点**：诚实标注——开发/学习用 H2，生产换 PG。不假装 H2 能扛生产。
- **职责分离**：持久管理数据→关系库（H2/PG）；实时流态→Redis/Kafka；协调锁→Redis。各司其职，不互相越界。

---


## 第 11 章：Redis 高可用——消除单点

### 11.0 场景：Redis 挂了，全系统瘫痪

第 10 章管数分离后，一次复盘：凌晨 Redis 所在机器 OOM 重启。结果：触发接口（抢不到锁）失败、订阅接口（读不到流）空转、正在输出的研究全中断、锁状态丢失。**Redis 是单点故障（SPOF）**——它挂 = 全系统瘫痪（会话记忆、知识库、审计、多端同步全压在它上）。

### 11.1 思路：一步步消除单点（不是一上来就 Sentinel）

> **演进纪律**：高可用也是一步步加的。我们沿着真实演进路径看每一步解决了什么、还差什么：
>
> | 阶段 | 做法 | 解决了什么 | 还差什么（下一痛点） |
> |------|------|----------|-------------------|
> | ① 单节点 | 一个 Redis（第 2 章起就是） | 能跑 | Master 挂 = 全瘫；重启可能丢未持久化数据 |
> | ② + AOF 持久化 | `--appendonly yes` | 重启不丢已落盘数据 | Master 挂期间服务中断（不会自动切） |
> | ③ + Slave（主从复制） | 加一个从节点实时复制 | 有数据副本、可分担读 | Master 挂了 Slave 不会自动顶上（要人工切） |
> | ④ + Sentinel（哨兵） | 3 哨兵监控 + 自动故障转移 | **Master 挂自动切到 Slave，业务无感** | —（单点消除） |
>
> **本章直接给 ④ 终态（1主1从3哨兵）的代码**，但你要清楚它是由这四步叠加——每一步都对应一个"还差什么"的痛点。如果跟着演进，可以先给单 Redis 开 AOF，再加 Slave，最后加 Sentinel。

| 角色 | 职责 | 数量 |
|------|------|------|
| Master | 读写都走它 | 1 |
| Slave | 实时复制 Master，故障时被提升 | ≥1 |
| Sentinel | 监控 Master，挂时投票选新 Master | ≥3（奇数防脑裂）|

**为什么 Sentinel 而不是 Cluster**：Cluster 是"分片"，数据量大到单机放不下才用。本项目 Redis 数据量小（Streams 24h 过期、记忆 TTL、知识库种子），**痛点是"单点故障"不是"容量"**——Sentinel 刚好对症，Cluster 反而过度设计。

> **我的判断**：这里和原版一致——Sentinel 对症。但我要补一点原版没强调的：本项目 Redis 同时承担"热缓存（会话记忆）"和"事实存储（知识库、审计）"两种角色（因为零依赖、不用数据库）。**这放大了 Redis 单点的影响**——数据库型系统里，Redis 挂了还有 DB 兜底；这里没有 DB，所以 Sentinel + AOF 持久化对零依赖版**比原版更重要**。

### 11.2 动手

#### 11.2.1 docker-compose 起 1主1从3哨兵

**【新建文件】** `research-agent/redis-ha/docker-compose.yml`：

```yaml
version: "3.8"
services:
  redis-master:
    image: redis:7-alpine
    command: redis-server --appendonly yes        # AOF 持久化，重启不丢
    ports: ["6380:6379"]
  redis-slave:
    image: redis:7-alpine
    command: redis-server --appendonly yes --replicaof redis-master 6379
    depends_on: [redis-master]
    ports: ["6381:6379"]
  sentinel-1:
    image: redis:7-alpine
    command: >
      sh -c 'echo "sentinel monitor mymaster redis-master 6379 2" > /etc/sentinel.conf &&
             echo "sentinel down-after-milliseconds mymaster 3000" >> /etc/sentinel.conf &&
             echo "sentinel failover-timeout mymaster 10000" >> /etc/sentinel.conf &&
             redis-server /etc/sentinel.conf --sentinel'
    depends_on: [redis-master, redis-slave]
    ports: ["26379:26379"]
  sentinel-2: { image: redis:7-alpine, command: >
      sh -c 'echo "sentinel monitor mymaster redis-master 6379 2" > /etc/sentinel.conf &&
             echo "sentinel down-after-milliseconds mymaster 3000" >> /etc/sentinel.conf &&
             redis-server /etc/sentinel.conf --sentinel',
      depends_on: [redis-master, redis-slave], ports: ["26380:26379"] }
  sentinel-3: { image: redis:7-alpine, command: >
      sh -c 'echo "sentinel monitor mymaster redis-master 6379 2" > /etc/sentinel.conf &&
             echo "sentinel down-after-milliseconds mymaster 3000" >> /etc/sentinel.conf &&
             redis-server /etc/sentinel.conf --sentinel',
      depends_on: [redis-master, redis-slave], ports: ["26381:26379"] }
```

#### 11.2.2 application.yaml：连哨兵

**【改已有文件】** `application.yaml`，`spring.data.redis` 替换为：

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: localhost:26379,localhost:26380,localhost:26381
        password: ${REDIS_PASSWORD:}
      timeout: 5s
```

> **业务代码零改动**：Spring Data Redis 检测到 Sentinel 配置后自动从哨兵查 Master、故障转移时自动重连。`SyncStreamBus`/`ChatMemoryStore`/`TfidfIndex` 一行不改。**这是用 Redis（而非自研）的红利**。

#### 11.2.3 故障转移瞬间的退避重试

故障转移有 3-10 秒空窗。`SyncStreamBus.writeChunk` 的重试加指数退避：

```java
// writeChunk 内的 redis.opsForStream().add(...)
.retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))
```

### 11.3 验证

```bash
docker-compose -f redis-ha/docker-compose.yml up -d
mvn spring-boot:run
curl -X POST "http://localhost:8080/api/runs?topic=高可用测试&sessionId=ha-001" -H "Idempotency-Key: ha-1"
curl -N "http://localhost:8080/api/runs/<runId>/stream"
# 另一个终端：docker stop <master容器名>
# 预期：日志几秒内重连新 Master，SSE 短暂卡顿后继续，不中断
```

### 11.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第11章：Redis Sentinel高可用+退避重试"
```

**做了**：Redis 单节点 → Sentinel 主从。业务零改动。**对零依赖版（Redis 既当缓存又当事实存储），高可用是刚需，不是可选**。

---

## 第 12 章：消息队列升级——Redis Streams → Kafka

### 12.0 场景：chunk 总线要跨服务消费、长期保留

第 11 章后，新需求：① 审计/计费/分析三个服务都要消费同一批 chunk（跨服务消费）；② 法规要 chunk 保留 30 天（Redis 内存型太贵）；③ chunk 流成公司级资产，多团队各自消费进度。

**解法**：chunk 持久总线从 Redis Streams 升级到 Kafka——消费组、磁盘持久、标准化订阅。

> **Redis 不下岗，是分工**：Kafka 接管 chunk 持久总线（高吞吐、跨服务、长期保留）；Redis 继续做 SETNX 锁（低延迟）、会话记忆/知识库/审计（低延迟读写）。

### 12.1 思路：Streams 多播 → Kafka 消费组

| 维度 | Redis Streams | Kafka |
|------|--------------|-------|
| 持久 | 内存（AOF 成本高） | 磁盘原生（保留 30 天成本低） |
| 消费模式 | 多播 | 消费组（各组进度独立） |
| offset | 手写 seq | 内建（消费组自动提交） |
| 跨服务消费 | 能，但各自为战 | 标准化（groupId + topic） |

### 12.2 动手

#### 12.2.1 pom + yaml

```xml
<!-- pom.xml 追加 -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

```yaml
# application.yaml 追加
spring:
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
    consumer:
      group-id: research-sse
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

#### 12.2.2 KafkaChunkBus：chunk 持久总线

**【新建文件】** `research-agent/src/main/java/com/example/research/stream/KafkaChunkBus.java`：

```java
package com.example.research.stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 12 章：基于 Kafka 的 chunk 持久总线。
 * - 写：produce 到 topic=research-chunks，key=sessionId（同会话进同分区，保序）
 * - 读：每个 sessionId 一个内存 Sink，把 Kafka 推送扇出给本实例多个 SSE 客户端
 *
 * 和 SyncStreamBus 分工：Kafka 管 chunk 持久总线，Redis 管锁/记忆/知识库。
 *
 * 注意：Kafka 自带 offset 托管——回放靠 auto-offset-reset: earliest + 消费组续读，
 * 不用手写 seq 游标（第 9 章的 seq 逻辑在 Kafka 下被 offset 取代）。
 */
@Component
public class KafkaChunkBus {

    private static final String TOPIC = "research-chunks";
    private final KafkaTemplate<String, String> kafka;
    private final ConcurrentHashMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    public KafkaChunkBus(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /** 写一条 chunk。key=sessionId 保证同会话进同分区、保序。 */
    public void write(String sessionId, String chunk) {
        kafka.send(TOPIC, sessionId, chunk);
    }

    /** 订阅会话流（回放 + 实时，offset 由 Kafka 托管）。 */
    public Flux<String> subscribe(String sessionId) {
        Sinks.Many<String> sink = sinks.computeIfAbsent(sessionId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    /** Kafka MessageListener 回调：按 key(sessionId) 分发到对应 sink。 */
    public void dispatch(ConsumerRecord<String, String> record) {
        Sinks.Many<String> sink = sinks.get(record.key());
        if (sink != null) sink.tryEmitNext(record.value());
    }

    /** 订阅结束清理（Controller 的 doFinally 调）。 */
    public void unsubscribe(String sessionId) {
        Sinks.Many<String> sink = sinks.remove(sessionId);
        if (sink != null) sink.tryEmitComplete();
    }
}
```

**【新建文件】** `research-agent/src/main/java/com/example/research/stream/KafkaConfig.java`：

```java
package com.example.research.stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 全局 Kafka 消费容器：一个消费者订阅 topic，按 key 分发到 KafkaChunkBus 的 sink。
 * 关键：N 个 SSE 连接共享一个消费者（高效），而非每连接一个消费者（会爆）。
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentMessageListenerContainer<String, String> chunkContainer(
            ConsumerFactory<String, String> cf, KafkaChunkBus bus) {
        ContainerProperties props = new ContainerProperties("research-chunks");
        props.setMessageListener((org.springframework.kafka.listener.MessageListener<String, String>)
                record -> bus.dispatch(record));
        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(cf, props);
        container.getContainerProperties().setGroupId("research-sse");
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return container;
    }
}
```

#### 12.2.3 SyncStreamBus.trigger 改用 Kafka 写 chunk

```java
// SyncStreamBus.trigger 的 upstream 处理改为：
upstream.doOnNext(chunk -> chunkBus.write(sessionId, chunk))   // ▼ 第12章：写 Kafka
        .doOnComplete(() -> { redis.delete(lockKey).subscribe(); chunkBus.write(sessionId, "__END__"); })
        .subscribe();
// 锁仍归 Redis，chunk 写入委托 KafkaChunkBus（trigger 签名加 KafkaChunkBus 参数）
```

Controller 的 `stream()` 改调 `chunkBus.subscribe(sessionId)`。

### 12.3 验证

```bash
# 起 Kafka（KRaft 单节点）
docker run -d --name research-kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9092 -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  confluentinc/cp-kafka:latest

curl -X POST "http://localhost:8080/api/runs?topic=Kafka测试&sessionId=kafka-001" -H "Idempotency-Key: k-1"
curl -N "http://localhost:8080/api/runs/<runId>/stream"

# 跨服务消费：另起消费组（模拟审计服务）
docker exec research-kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic research-chunks --group audit-service --from-beginning
```

### 12.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第12章：chunk总线升级Kafka+消费组跨服务消费"
```

**做了**：chunk 总线 Redis Streams → Kafka。offset 由 Kafka 托管，省掉第 9 章手写 seq（在 Kafka 语境下）。**Redis 与 Kafka 分工**：Redis 管锁/记忆/知识库（低延迟），Kafka 管 chunk 持久总线（跨服务/长期）。

---

## 第 13-16 章：微服务拆分（订阅 → 触发 → 网关 → LLM 网关）

> **我的判断（重要）**：原版把"订阅/触发/网关/LLM网关 + Eureka"逐个拆成 6 个服务。**作为企业级目标态，这个拆分方向是对的**——触发(IO密集)、订阅(连接密集)、业务(CPU密集)、LLM治理确实资源画像不同，该拆。**但在"零依赖、单机学习"的语境下，一上来铺 6 个微服务 + Eureka 是过重的**——学习者会被运维复杂度淹没，反而不理解"为什么拆"。
>
> 所以我的处理方式：
> - **先讲清"该不该拆、什么时候拆、按什么拆"的判断标准**（这才是企业级能力的核心，比代码更重要）。
> - **给出"模块化单体 → 按真实瓶颈物理拆"的渐进路径**，而不是一步到位 6 服务。
> - **把 6 服务 + Eureka 作为"企业级目标态"描述清楚**（架构图 + 职责表 + 拆分顺序的理由），代码层面复用第 9-12 章已有的类，只在"物理边界"上拆。

### 13.0 什么时候该拆微服务（判断标准）

不是"看起来高级就拆"。拆微服务的触发条件（**任一成立才考虑**）：

| 信号 | 含义 | 本项目对应 |
|------|------|-----------|
| **资源画像冲突** | 两类逻辑抢不同资源（IO vs CPU，连接数 vs 内存） | 订阅(连接密集) vs 触发(LLM IO) vs 业务(CPU) |
| **独立扩容需求** | 某部分要单独扩容，其他不需要 | 订阅要按 SSE 连接数扩，触发要按 LLM 并发扩 |
| **独立发布节奏** | 某部分变更频繁，要独立上线 | LLM 网关换厂商频繁，不应牵连业务 |
| **团队边界** | 不同团队负责不同部分 | 多团队时，服务边界对齐团队边界 |

**没有这些信号就不拆**——过早拆分只会得到"分布式的大泥球"（更多故障点、更难调试、分布式事务难题）。**单体 + 良好的模块化，对小团队/早期产品往往是最优解**。

> **我的立场**：如果你是个人/小团队学习本项目，**拆到"管数分离（第10章）+ Redis HA（第11章）+ Kafka（第12章）"这一步，已经是一个相当可用的企业级单体**。下面 13-16 章的微服务拆分，是"当规模真正需要时"的目标态——读懂它的逻辑比照抄 6 个 pom 更重要。

### 13.1 模块化单体：拆分前的正确姿势

在物理拆服务前，先在**单进程内做好模块化**——每个"未来的服务"是一个独立包（`trigger/`/`subscribe/`/`kb/`/`session/`），包间只通过明确的接口通信，不互相直接 `new`。**这样物理拆分时，只需把某个包移到独立进程 + 加个启动类**，几乎不用改业务代码。

本项目第 0-12 章其实已经是模块化单体的雏形：`tool/`、`kb/`、`llm/`、`session/`、`stream/`、`audit/` 都是高内聚的包。**这是"先逻辑后物理"原则的体现**。

### 13.2 物理拆分的目标态（6 服务 + 注册中心）

当规模真正需要时，目标架构如下（**企业级终极形态**）：

```
前端 ──→ API网关(:8080) ──┬── POST /api/runs（+cancel/status） ──→ research-trigger   (lb)
                          ├── GET  /api/runs/{id}/stream        ──→ research-subscribe  (lb)
                          └── /api/**（知识库/会话 CRUD）         ──→ research-agent     (业务核心: kb/plan/session)
                                                                    │
                              research-llm-gateway ←── 触发服务调它(屏蔽LLM厂商)
                                                                    │
                              research-registry(Eureka :8761)  ←── 全部注册
共享：Kafka(chunk总线) + Redis(锁/记忆/知识库) + Redis Sentinel(HA)
```

| 服务 | 职责 | 扩容依据 | 依赖 |
|------|------|---------|------|
| `research-subscribe` | 订阅 Kafka、SSE 推前端 | SSE 长连接数 | webflux + kafka（最小） |
| `research-trigger` | POST 触发、抢锁、写 Kafka、调 LLM 网关 | LLM 并发数 | webflux + redis-reactive + kafka |
| `research-agent` | 业务核心：知识库、Plan-Execute、会话 CRUD | 业务 QPS | webflux + redis-reactive |
| `research-llm-gateway` | 封装 LLM 厂商细节、A/B、熔断、计费 | LLM 调用量 | webflux + (MockLlmClient 或真模型) |
| `research-gateway` | API 网关、路由、lb | — | spring-cloud-gateway |
| `research-registry` | Eureka 服务发现 | — | eureka-server |

**拆分顺序（每章拆一个，痛点驱动）**：

| 章 | 拆什么 | 驱动痛点 |
|----|--------|---------|
| 13 | 订阅服务 | SSE 长连接挤爆单进程文件描述符 |
| 14 | 触发服务 | 触发(IO)与业务(CPU)资源画像冲突 |
| 15 | API 网关 + Eureka | 前端要记一堆端口、扩容 IP 漂移 |
| 16 | LLM 网关 | 换 LLM 厂商要改业务代码 |

### 13.3 拆订阅服务（示例，其余同理）

**思路**：把第 12 章的订阅逻辑（`KafkaChunkBus` 读侧 + `SubscribeController`）搬到独立进程 `research-subscribe`，只引 webflux + kafka（不引 Redis/PG/Spring AI——职责单一、部署轻量）。

**`research-subscribe/pom.xml`**：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>research-subscribe</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
        <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
    </dependencies>
</project>
```

**启动类 `SubscribeApplication`**：

```java
package com.example.subscribe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class SubscribeApplication {
    public static void main(String[] args) { SpringApplication.run(SubscribeApplication.class, args); }
}
```

**只读 `KafkaChunkBus`**（去掉写侧 `write`，只保留订阅/分发/清理）。关键：按 `runId`（Kafka 消息 key）路由到 sink，订阅结束清理防泄漏：

```java
package com.example.subscribe;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.concurrent.ConcurrentHashMap;

/** 订阅服务的 chunk 总线（只读侧）。写侧在 research-trigger。 */
@Component
public class KafkaChunkBus {

    private static final String TOPIC = "research-chunks";
    // key=runId（Kafka 消息 key），每个 run 一个 sink，扇出给本实例多个 SSE 订阅者
    private final ConcurrentHashMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /** 订阅某个 run 的流。auto-offset-reset=earliest 让首次订阅从最早读（回放前文）。 */
    public Flux<String> subscribe(String runId) {
        Sinks.Many<String> sink = sinks.computeIfAbsent(runId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    /** Kafka MessageListener 回调：按 key(runId) 分发到对应 sink。 */
    public void dispatch(ConsumerRecord<String, String> record) {
        Sinks.Many<String> sink = sinks.get(record.key());
        if (sink != null) sink.tryEmitNext(record.value());
    }

    /** 订阅结束清理 sink（Controller 的 doFinally 调，防内存泄漏）。 */
    public void unsubscribe(String runId) {
        Sinks.Many<String> sink = sinks.remove(runId);
        if (sink != null) sink.tryEmitComplete();
    }
}
```

**`KafkaConfig`**（全局消费者，按 key 分发）：

```java
package com.example.subscribe;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConfig {
    @Bean
    public ConcurrentMessageListenerContainer<String, String> chunkContainer(
            ConsumerFactory<String, String> cf, KafkaChunkBus bus) {
        ContainerProperties props = new ContainerProperties("research-chunks");
        props.setMessageListener((org.springframework.kafka.listener.MessageListener<String, String>)
                record -> bus.dispatch(record));
        ConcurrentMessageListenerContainer<String, String> container = new ConcurrentMessageListenerContainer<>(cf, props);
        container.getContainerProperties().setGroupId("research-sse");
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return container;
    }
}
```

**`SubscribeController`**（数据面：只读 SSE，协议级 id/event，`doFinally` 清 sink）：

```java
package com.example.subscribe;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.time.Duration;

@RestController
@RequestMapping("/api/runs")
public class SubscribeController {

    private final KafkaChunkBus chunkBus;
    public SubscribeController(KafkaChunkBus chunkBus) { this.chunkBus = chunkBus; }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@PathVariable String runId) {
        // Kafka 版：offset 由消费组托管，earliest 自动回放前文；去重靠 Kafka 不重投。
        // chunk 内容已是 "seq::chunk"（触发服务写入时带 seq）。
        Flux<ServerSentEvent<String>> data = chunkBus.subscribe(runId)
                .takeUntil(s -> s.endsWith("::__END__"))
                .map(s -> {
                    int idx = s.indexOf("::");
                    long seq = idx > 0 ? Long.parseLong(s.substring(0, idx)) : 0;
                    String chunk = idx > 0 ? s.substring(idx + 2) : s;
                    return ServerSentEvent.<String>builder()
                            .id(String.valueOf(seq)).event("token").data(chunk).build();
                })
                .concatWithValues(ServerSentEvent.<String>builder().event("done").data("").build());

        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        // doFinally：客户端断开时清 sink，防内存泄漏（订阅服务扩多实例时尤其重要）
        return data.mergeWith(heartbeat)
                .takeUntilOther(data.then())
                .doFinally(sig -> chunkBus.unsubscribe(runId));
    }
}
```

```yaml
# research-subscribe application.yml
server:
  port: 8082
spring:
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: research-sse
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

> **原 `research-agent` 删掉订阅逻辑**：`GET /api/runs/{runId}/stream` 改由订阅服务(:8082)提供，原进程只留触发（`POST /api/runs`）等管理面。
>
> **关于 Kafka 版的多端同步（回应补强 C 的 30% 场景）**：Kafka 版下，"前文可见 + 不漏不重"靠的是 **消费组 offset 托管**——`auto-offset-reset: earliest` 让新订阅者从头读（前文可见），消费组自动提交 offset（不漏不重），断线重连续读。第 9 章手写的 seq 游标在 Kafka 语境下被 offset 取代——**但触发服务写入 chunk 时仍带 seq**（`seq::chunk`），这样订阅服务转 SSE 时还能给 `id` 字段供浏览器 Last-Event-ID 用。**两套机制互补**：Kafka offset 保证不漏不重，seq/Last-Event-ID 保证浏览器断线续传。

### 13.4 验证

```bash
cd research-trigger && mvn spring-boot:run       # 触发 :8081（或单体的 :8080）
cd research-subscribe && mvn spring-boot:run      # 订阅 :8082
# 触发（管理面）
curl -i -X POST "http://localhost:8081/api/runs?topic=拆分测试&sessionId=split-001&" -H "Idempotency-Key: k1"
# 订阅（数据面，落在另一个进程）
curl -N "http://localhost:8082/api/runs/<runId>/stream"
# 订阅服务独立扩容
cd research-subscribe && mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8083"
```

### 13.5 checkpoint + 复盘

```bash
git add -A && git commit -m "第13章：拆出独立订阅服务research-subscribe"
```

**做了 + 我的判断**：把订阅逻辑物理拆出。**拆分顺序依赖前置条件**：能拆订阅，是因为第 12 章已把 chunk 总线升级 Kafka——触发和订阅天然通过消息队列解耦，拆开不用引入分布式事务。**如果第 12 章没做 Kafka 升级，这步拆分会卡在"两进程怎么共享 chunk 流"**。这就是"先总线升级、再服务拆分"的具象理由。

---

## 第 14 章：拆触发服务

### 14.0 场景

第 13 章后，触发逻辑仍留在原进程，扩容触发要连带扩容业务逻辑（知识库、Plan、会话）。**触发(IO)与业务(CPU)资源画像不同**，该拆。

### 14.1 思路

把触发逻辑独立成 `research-trigger`：调 LLM 网关、抢 Redis 锁、写 Kafka。原 `research-agent` 退化成业务核心（知识库/Plan/会话）。

> **触发服务调 LLM 直连还是走网关？** 本章先直连（Mock LLM 打包进触发服务）。LLM 网关屏蔽厂商差异是第 16 章的事——一步步来。

### 14.2 动手

**`research-trigger`**：把 `RedisStreamBus`(锁部分) + `KafkaChunkBus`(写侧) + `MockLlmClient` + `TriggerController` 复制过去，依赖 webflux + redis-reactive + kafka。

> **触发服务调 LLM 不带 ChatMemory（本章简化）**——多轮记忆留给业务核心/Redis。这是第 17 章"分布式 ChatMemory"的演进起点（**代价先欠下，第 17 章还**）。

### 14.3 checkpoint + 复盘

```bash
git add -A && git commit -m "第14章：拆出独立触发服务research-trigger"
```

**核心跃迁**：从"单进程"到"业务核心 + 触发 + 订阅"三进程。触发和订阅各自独立扩容。

---

## 第 15 章：加 API 网关 + 服务发现

### 15.0 场景

第 14 章后，前端面对三个地址（触发:8081、订阅:8082、业务:8080），还要记端口、处理扩容 IP 漂移。**违背"对内微服务、对外单体 API"原则**。

### 15.1 思路

加 `research-gateway`（Spring Cloud Gateway，响应式，支持 SSE 透传）+ `research-registry`（Eureka）。前端只访问网关(:8080)，网关按 URL+方法路由。

```yaml
# research-gateway application.yml 路由
spring:
  cloud:
    gateway:
      routes:
        - id: trigger       # 管理面：创建 run（POST）、查状态、取消
          uri: lb://research-trigger
          predicates: [Path=/api/runs, Method=POST]
        - id: run-status     # 管理面：GET /api/runs/{id}、cancel
          uri: lb://research-trigger
          predicates: [Path=/api/runs/**, Method=POST]   # cancel 走这里
        - id: subscribe      # 数据面：只读 SSE 流（GET）
          uri: lb://research-subscribe
          predicates: [Path=/api/runs/*/stream, Method=GET]
        - id: business
          uri: lb://research-agent
          predicates: [Path=/api/**]
```

> **网关必须响应式才支持 SSE**：Spring Cloud Gateway（WebFlux）逐 chunk 透传 SSE；传统 Servlet 网关（Zuul 1）会缓冲破坏流式。**这是流式系统在网关选型上的关键差异**。

### 15.2 checkpoint + 复盘

```bash
git add -A && git commit -m "第15章：API网关+Eureka统一入口"
```

**做了**：网关屏蔽后端拓扑，服务发现解决 IP 漂移。前端无感知后端拆分/扩容。

---

## 第 16 章：拆 LLM 网关

### 16.0 场景

第 15 章后，想从 Mock 切到真模型（或换厂商），要改触发服务的依赖/配置/system prompt/流式格式适配……改完还要重新部署。**换厂商 = 改业务代码**。

> **在零依赖版里，这个痛点更纯粹**：当前所有服务都打包了 `MockLlmClient`。要换真模型，得改多个服务。**把 LLM 实现收敛到一个网关，业务只认统一接口**——这正是第 0 章引入 `LlmClient` 抽象时就埋下的伏笔。

### 16.1 思路

拆 `research-llm-gateway`，封装所有 LLM 厂商细节。对外暴露统一接口 `/llm/chat/stream`（第 0 章 `LlmClient` 的 HTTP 化）。触发服务调这个接口，不直连任何 LLM、不持有 key。

**`LlmController`（LLM 网关统一接口）**：

```java
@RestController
@RequestMapping("/llm")
public class LlmController {
    private final LlmClient llm;   // 注入 MockLlmClient（或真实现）
    public LlmController(LlmClient llm) { this.llm = llm; }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest req) {
        return llm.stream(req.system(), req.user(), List.of(), List.of());
    }
    public record ChatRequest(String system, String user) {}
}
```

**`LlmRouter`（厂商路由扩展点）**：本章单实现（Mock）；扩展点：A/B、按租户路由、熔断降级、token 计费——**所有 LLM 治理逻辑收敛在这一处**。

> **触发服务改为调网关**：去掉 `spring-ai`/`MockLlmClient` 依赖，用 `WebClient` 调 `http://research-llm-gateway/llm/chat/stream`。换厂商、加厂商、A/B、计费，全在网关内部改，触发服务一行不动。

### 16.2 checkpoint + 复盘

```bash
git add -A && git commit -m "第16章：拆LLM网关屏蔽厂商差异"
```

**核心跃迁**：从"业务直连 LLM"到"业务调统一接口、网关路由厂商"。**第 0 章的 `LlmClient` 抽象在这里兑现**——当初的解耦设计，让"换 Mock 为真模型"只需改一个网关服务。**这是"先抽象、后实现"复利的具象体现**。

> **第 16 章结束 = 微服务拆分完成**。13-16 章逐个拆出订阅、触发、网关、LLM 网关，每次只拆一个、跑通再拆下一个。加上 10-12 章的管数分离/Redis HA/Kafka，整套企业级分布式架构成形。

---

## 第 17 章：分布式 ChatMemory——拆服务后恢复多轮记忆

### 17.0 场景：触发服务"失忆"了

第 14 章拆触发服务时欠了个代价——触发服务调 LLM 不带历史记忆。用户第一轮问 vLLM，第二轮追问 PagedAttention，触发服务**完全不记得第一轮**。

**根因**：会话记忆在第 7 章落在 Redis（`ChatMemoryStore`）。触发服务是独立进程，理论上能读同一个 Redis——但第 14 章简化掉了。现在补回来。

### 17.1 思路：Redis 热缓存 + Redis 兜底（零依赖版特化）

原版是"Redis 热缓存 + PG 兜底"。**零依赖版没有 PG**——所以记忆就是"Redis 热缓存 + Redis 兜底"（同一个 Redis，TTL 区分热/冷，或两套 key）。触发服务直接读共享 Redis 拿历史，**不跨进程 RPC 业务核心**。

> **我的判断**：这其实是零依赖版的**优势**——因为记忆本来就在 Redis（第 7 章），拆服务后触发服务只需连同一个 Redis 就能读到历史，**几乎零成本恢复多轮记忆**。原版要处理"PG 兜底"是因为它有数据库；我们没有数据库，反而更简单。

### 17.2 动手

触发服务直接复用第 7 章的 `ChatMemoryStore`（连同一个 Redis）。触发前读历史、触发后写回：

```java
// research-trigger TriggerController
@PostMapping
public Mono<ResponseEntity<Map<String, String>>> trigger(@RequestParam String topic, @RequestParam String sessionId) {
    return memory.load(sessionId)   // ▼ 第17章：从共享 Redis 读历史
        .map(history -> {
            String fullUser = history.stream().map(LlmClient.LlmMessage::content)
                    .collect(Collectors.joining("\n")) + "\n研究主题：" + topic;
            return Map.of("system", "你是研究助理。结合历史研究。绝不编造。", "user", fullUser);
        })
        .flatMap(req -> {
            Flux<String> upstream = llmGateway.post().uri("/llm/chat/stream")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req).retrieve().bodyToFlux(String.class);
            StringBuilder collected = new StringBuilder();
            return bus.trigger(sessionId, upstream.doOnNext(collected::append)
                    .doOnComplete(() -> memory.append(sessionId, topic, collected.toString()).subscribe()))  // ▼ 写回历史
                .thenReturn(ResponseEntity.accepted().body(Map.of("sessionId", sessionId, "status", "started")));
        });
}
```

### 17.3 验证

```bash
curl -X POST "http://localhost:8080/api/runs?topic=vLLM是什么&sessionId=mem-001" -H "Idempotency-Key: mem-1"
curl -N "http://localhost:8080/api/runs/<runId>/stream"
curl -X POST "http://localhost:8080/api/runs?topic=它和PagedAttention什么关系&sessionId=mem-001" -H "Idempotency-Key: mem-2"
curl -N "http://localhost:8080/api/runs/<runId>/stream"
# 预期：第二轮基于第一轮历史展开——多轮记忆恢复
docker exec research-redis redis-cli LRANGE chat:mem-001 0 -1
```

### 17.4 checkpoint + 复盘

```bash
git add -A && git commit -m "第17章：分布式ChatMemory（共享Redis，零依赖特化）"
```

**做了**：触发服务连共享 Redis 读写历史，恢复多轮记忆。**共享缓存 > 同步 RPC**——多服务访问同一数据，放共享存储而非跨进程调用，是避免同步依赖的标准模式。

> **第 17 章结束。** 从第 0 章的固定 workflow，到这里的分布式企业级多端同步、管数分离系统——**17 章一个痛点驱动一步**。每一步都是"上一步出问题了才进下一步"，没有跳章。

---

## 补强 C：多页面同步的端到端证明——"A 输出到 30% 时 B 打开，要看到前 30% 且两页继续一致"

> 你提的这个场景，是多端同步**最容易被忽略、却最考验设计**的硬核用例。我专门走一遍，证明第 9-10 章的机制确实解决了它，并讲透其中的**漏 chunk 竞态**——这是"能跑 demo"和"企业级可靠"的分水岭。

### C.1 场景精确描述

```
t0:  页面A 打开，POST 创建 run_abc（触发，A 所在实例抢到锁，成为"单一写者"）
t1:  A 订阅 GET /api/runs/run_abc/stream，开始收 SSE：id:1..id:30（已显示 30%）
t2:  LLM 仍在跑，正在吐 id:31...

t3:  页面B 打开（同 sessionId / 同 runId）
     要求：
       (1) B 立刻看到前 30%（id:1..30）
       (2) B 继续实时收 id:31,32,...，且和 A 完全一致
       (3) A 不受影响，继续收 id:31,32,...
       (4) B 不重复触发 LLM（不烧第二份 token）
```

### C.2 四个机制，缺一个就崩

| 要求 | 机制 | 在哪实现 |
|------|------|---------|
| (1) B 看到前 30% | LLM chunk 全程 `XADD` 进 Redis Streams（持久），B 从 offset 0 `range` 读全量历史 | `SyncStreamBus.replayThenListen` 的 history 段 |
| (4) B 不重复触发 | 管数分离：B 只发 `GET stream`（数据面，纯只读），不抢锁不触发；单一写者由 trigger 的 SETNX 保证 | 第 10 章 `trigger()`/`subscribeReadOnly()` |
| (3) A 不受影响 | A 一直在订阅 Pub/Sub 频道，B 的加入是"新增一个订阅者"，对 A 透明 | Redis Pub/Sub 多播 |
| (2) 两页后续一致 | **A 和 B 都从同一个 Redis 数据源消费**（Stream + 频道），同源必然一致 | 核心设计：单一事件源 |

### C.3 核心认知：为什么两页"必然一致"

**关键不在"把 A 的数据复制给 B"，而在"A 和 B 各自从同一个源头按各自进度消费"：**

```
                  LLM（单一写者，只在一台实例上跑一次）
                         │ 每个 chunk
                         ▼
              XADD ──→ Redis Stream（run_abc:chunks）   ← 持久，唯一权威数据源
              PUBLISH ──→ 频道 run_abc                   ← 通知铃
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                 ▼
   页面A 订阅         页面B 订阅        （页面C 也能加）
   （一直在听）       （t3 加入：
                      先读历史 1..30，
                      再接实时）
```

因为后续 chunk 只写进这一条 Stream + 这一个频道，**所有订阅者最终看到的内容一定逐字相同**。这是 ChatGPT 多端同步的底层同构——"一个中心产生流，多路分发"。

### C.4 漏 chunk 竞态（重点：naive 实现会在这里翻车）

如果 `replayThenListen` 写成最朴素的"先 range 全量历史，再 subscribe 频道"，在 t3（B 加入）这个瞬间有竞态：

```
t3.0  B 执行 range(run_abc:chunks) 读到 id:1..30，history 流结束
t3.1  写者 XADD id:31 并 PUBLISH（此刻 B 的频道订阅还没生效！）
t3.2  B 的频道订阅生效，只能听到 id:32 起
→ B 漏掉了 id:31 ❌（历史已结束不会再读，订阅又错过了 PUBLISH 那一刻）
```

这个窗口本地几乎测不出（要正好卡在微秒级），但**线上高并发必现**。很多人写多端同步栽在这里。

### C.5 seq 游标解法（补强里已实现，这里讲清为什么）

**铁律：Pub/Sub 只当通知铃，数据永远按 seq 从 Streams 增量读。**

```java
// B 的订阅逻辑（修正版）：
AtomicReference<Long> cursor = new AtomicReference<>(0L);   // 已读到的最大 seq

// ① 历史：range 全量，推进游标
readAfter(streamKey, cursor)   // 读 seq>0，读完 cursor=30

// ② 实时：收到任意 PUBLISH（不管内容），就从游标之后增量读
listener.receive(频道)
    .flatMap(notify -> readAfter(streamKey, cursor))   // 通知只触发"去读一次"
    // 收到 id:31 的 PUBLISH 时，即便订阅晚生效，下一次任何通知来了，
    // readAfter 会从 cursor=30 读出 31,32... 全部积压——不漏！
```

- 通知早到？`readAfter` 从游标读出所有积压 chunk。
- 通知晚到？下一条通知来了照样补读。
- 通知丢了？相邻的下一通知来了，从游标读出中间所有 chunk。
- **游标持续推进 + 数据唯一在 Streams = 不漏不重**。

> 这和 Kafka offset、SSE `Last-Event-ID`、数据库 binlog position **完全同构**——所有"可靠消息回放"的本质都是"记住消费位置 + 从位置之后读"。

### C.6 幂等去重：B 前面 30% 不能重复显示

B 先读历史 id:1..30 显示出来，万一某条在"历史"和"实时增量"边界被读到两次（窗口重叠），会重复。解法：**页面端按 seq 幂等去重**。

```javascript
// 页面 B 前端逻辑
let lastShown = 0;
es.onmessage = (e) => {
    // SSE data 里的 chunk 带 id（补强 A 的 ServerSentEvent.id）
    const seq = parseInt(e.lastEventId);   // 浏览器自动记录的 event id
    if (seq <= lastShown) return;          // 去重：已显示过的丢弃
    lastShown = seq;
    appendToScreen(e.data);
};
// 断线重连：浏览器自动带 Last-Event-ID 头，服务端从该 seq 之后补推
```

### C.7 端到端时序总图（最终证明）

```
t0  A: POST → run_abc 创建，A 实例抢锁成为写者
t1  A: GET stream → 订阅频道 run_abc
t1+ 写者: LLM 吐字 → XADD(seq=1..30) + PUBLISH ×30
    A: 收到 id:1..30，屏幕显示 30% ✅

t3  B: GET stream（同 run_abc）→ 不触发（只读）
    B: readAfter(cursor=0) 读历史 → id:1..30 快速刷出 ✅(1) 前面 30% 可见
    B: cursor=30，接频道订阅
    
t4  写者: 吐 id:31 → XADD(31) + PUBLISH
    A: 频道收到通知 → 显示 id:31 ✅(3) A 继续
    B: 频道收到通知 → readAfter(cursor=30) 读出 31 → 显示 ✅(2) B 与 A 一致
t5+ ... 31,32,... 两页逐字相同，都继续流 ✅
    全程只有一次 LLM 调用 ✅(4) B 没重复触发
```

**结论**：你的场景**完全被覆盖**，且每个要求都有明确机制兜底：
- 前 30% 可见 ← Redis Streams 持久 + range 回放
- 两页继续一致 ← 单一事件源 + seq 游标增量读
- 不漏 chunk ← 游标（防 naive concatWith 竞态）
- 不重复 ← seq 幂等去重
- 不重复触发 ← 管数分离 + 单一写者

> **学习要点**：多端同步的可靠性，全在"漏"和"重"两个字上。naive 实现（朴素 concatWith）会漏；没有去重会重。**seq 游标（不漏）+ 客户端 seq 去重（不重）+ 持久 Streams（前文可见）+ 单一写者（不重复触发）**，四者齐备才是企业级多端同步。少任何一个，线上都会以"偶发少字 / 偶发多字 / 重复烧 token"暴露。

---

## 附录：项目结构与踩坑手册

### 终态项目结构（6 服务 + 注册中心）

```
research-platform/
├── research-agent/           # 业务核心：知识库/Plan/会话CRUD（模块化单体可先不拆）
├── research-trigger/         # 触发：调LLM网关 + 锁 + 写Kafka
├── research-subscribe/       # 订阅：读Kafka + SSE
├── research-llm-gateway/     # LLM 网关：封装厂商（Mock 或真模型）
├── research-gateway/         # API 网关（Spring Cloud Gateway）
├── research-registry/        # Eureka 服务发现
├── redis-ha/                 # Redis Sentinel（1主1从3哨兵）
└── 共享中间件：Kafka + Redis
```

### 我的几点独立思考（写给学习者）

1. **多端同步的本质是"单一事件日志 + 多播 + seq去重 + resume"**，不是某个具体中间件。Redis Streams 能做，Kafka 也能做——选哪个看"要不要跨服务/长期保留"。不要被工具绑死思路。

2. **LLM 流式同步用 SSE 够用，但一旦要"客户端发控制指令"就得 WebSocket**。别为显高级乱上 WebSocket，也别假装 SSE 万能。

3. **SETNX 锁有 fencing 缺陷**——生产级用 lease + fencing token（存储层拒旧 token 的写）。学习时知道这个坑，比假装 SETNX 完美重要。

4. **管数分离要配 `202 + Location + 任务状态`**，不只是 `{status:"started"}`。这是 REST 规范，让前端能正确轮询和订阅。

5. **不要为了显高级上 CRDT/OT**。LLM 问答是"单写者追加日志"，用事件多播足够。CRDT 是给"多人同时编辑同一可变状态"的——杀鸡用牛刀。

6. **微服务拆分有判断标准（资源画像/独立扩容/独立发布/团队边界）**，没有信号就别拆。模块化单体往往是小团队最优解。本项目拆到"管数分离 + Redis HA + Kafka"已是相当可用的企业级单体。

7. **零依赖版的真正价值**：`LlmClient` 抽象让"Mock 离线验证编排"和"生产真模型"无缝切换——第 16 章换 LLM 网关不改业务，是第 0 章抽象的复利。

### 加固扩展点（最小版待补，企业级必做）

| 加固项 | 怎么补 |
|--------|--------|
| Stream MAXLEN 封顶 | `XADD` 后 `XTRIM stream ~ 10000`（近似裁剪） |
| cancel 传回 upstream | 存 `Disposable`，`output.doFinally` 里 `dispose()` |
| fencing token | 锁值用单调 token，存储写时校验 token ≥ 当前 |
| 消费组 offset 手动提交 | `AckMode.MANUAL` + 处理完再 ack |
| 知识库检索性能 | IDF 预计算缓存；或换真向量检索（只改 `TfidfIndex.retrieve`） |

### 踩坑手册

- **`ReactiveRedisMessageListenerContainer` 没建 bean 会启动报错**——第 2 章 `RedisConfig` 已建。
- **`@Bean` 覆盖冲突**（如自定义 RedisTemplate）：`spring.main.allow-bean-definition-overriding: true`。
- **SSE 流不结束**：忘了发 `__END__` 标记，Pub/Sub Flux 持续打开——务必在 `finishStream` 发结束标记。
- **多端内容分叉**：单一写者被破坏（锁没生效或 TTL 过期又触发）——检查 SETNX 和 TTL，必要时上 fencing。
- **Mock 线程不响应取消**：`charByChar` 用裸 Thread，第 9 章 cancel 不会停它——附录加固点里改成响应 `Disposable`。

---

> **全文结束。** 这份零依赖版把原版 34 的企业级演进脉络（固定 workflow → Agent → RAG → Plan-Execute → 审计 → 会话 → 多端同步 → **管数分离** → 高可用 → Kafka → 微服务 → 分布式记忆）完整保留，但**砍掉所有外部 LLM/数据库依赖**（用 Mock LLM + Redis/TF-IDF 替代），并对**多端同步（第9章）和管数分离（第10章）做了独立的企业级调研与设计**。最终态是一个管数分离、多端同步、可演进到分布式的企业级可用系统——不是玩具。
