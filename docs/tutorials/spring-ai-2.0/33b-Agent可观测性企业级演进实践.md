# 33b Agent 可观测性工程：企业级演进实践手册

> **这份文档是什么**：一份**终极学习项目**手册。你照着它一步步敲代码、复现，最后得到一个真正的企业级可观测 Agent 项目。它同时兼顾两件事——
> - **项目演进**：按企业真实节奏走，每个阶段都是「上一阶段出问题了才进下一阶段」，不是一上来全铺架构；
> - **项目实践**：每行代码都给全、能编译、能跑，你手动复现就能形成完整项目。
>
> **和 33a 的关系**：[33a](./33a-Agent可观测性最小实战.md) 是你的练手小项目（30 分钟跑通骨架）；本文 33b 是终极项目（完整企业级，2-3 周复现）。两者不冲突，33a 是 33b 第 1 章的缩略版。
>
> **配套查参**：[33-Agent子过程实时可见性方案](./33-Agent子过程实时可见性方案.md) 是理论全本，某项设计想看完整细节时翻它。
>
> **技术栈**：Spring Boot 4.1 · Spring AI 2.0.0 · Java 21 · WebFlux · Reactor · Maven · DeepSeek（OpenAI 兼容协议）。
> **难度假设**：你会 Java、会用 IDE、会跑 Maven，但不熟 Reactor/SSE/可观测性。每个新概念第一次出现都先用大白话讲。
>
> **本文边界（重要）**：讲「**可观测性**」——让 Agent 的执行过程可见、可靠、可治理，以及支撑这套的工程化（事件总线、SSE、归档检索、重试降级等）。**不讲**两件事：
> - **AI 产品功能开发**（RAG、Agent 编排、多模态、prompt 工程的"怎么写好 prompt"）——那是产品能力，不是可观测性；
> - **部署运维**（Docker/k8s/CI-CD）——本文目标是你**在 IDE 里能启动跑通**，外部依赖只用一个 Redis（brew/docker 起一个即可），不引入容器编排。
>
> 简单说：**本文教你把"看不见的 Agent"变成"看得见、查得到、出事能救的生产级可观测系统"，在 IDE 里一步步复现**。AI 功能怎么写、怎么部署上云，是另外的文档。

---

## 目录

- [前言：怎么用这份文档](#前言怎么用这份文档)
- [第 0 章：开张——建项目，跑通黑盒 Agent](#第-0-章开张建项目跑通黑盒-agent)
- [第 1 章：第一次事故——给它装上监控屏](#第-1-章第一次事故给它装上监控屏)
- [第 2 章：用户投诉——让事件可靠](#第-2-章用户投诉让事件可靠)
- [第 3 章：看到工具调用与 token](#第-3-章看到工具调用与-token)
- [第 4 章：上量了——状态外置与分片](#第-4-章上量了状态外置与分片)
- [第 5 章：对多客户收费——多租户与成本治理](#第-5-章对多客户收费多租户与成本治理)
- [第 6 章：生产关键系统——灾备与 SRE](#第-6-章生产关键系统灾备与-sre)
- [第 7 章：运营中的事故——只有真实流量才踩得到](#第-7-章运营中的事故只有真实流量才踩得到)
- [第 8 章：会话持久化与历史回放——让数据活得久、查得到、接得上](#第-8-章会话持久化与历史回放让数据活得久查得到接得上)
- [附录：完整目录树与踩坑手册](#附录完整目录树与踩坑手册)

---

## 前言：怎么用这份文档

### 这份文档的写作哲学

企业里的系统从来不是「架构师画完图、工程师一把实现」的。真实节奏是：

```
上线最小版 → 用着用着出问题 → 加一层解决 → 量大了又出问题 → 再加一层 …
```

所以这份文档不按「先讲事件模型、再讲总线、再讲采集点」这种**知识分类**组织（那是教材），而是按**一个团队真做这个项目会经历的故事**组织。每一章的模板统一是：

| 小节 | 作用 |
|------|------|
| **X.0 场景** | 什么痛点把我们推到了这一步（演进叙事） |
| **X.1 思路** | 怎么解决、为什么是这个方案（决策对比 + 调研背书） |
| **X.2 动手** | 完整代码，照抄能编译（项目实践） |
| **X.3 前端页面** | 给一个调试页面，用页面调接口验证（能页面就页面，单接口阶段 curl 也行） |
| **X.4 用页面验证 + 暴露问题** | 页面操作看效果，遇到真实问题（竞态/丢事件/超时）就讲根因 |
| **X.5 修复** | 针对暴露的问题加机制，页面复测确认 |
| **X.6 checkpoint** | 这一步的目录结构 + git 提交点 |
| **X.7 复盘** | 解决了什么、又暴露了什么（承上启下） |

**先讲故事、再敲代码、再用页面验证、遇到问题就修**——这样你学到的每个机制都有「它解决了什么真实问题、页面怎么复现」的体感，而不是死记硬背，也不是靠手动 curl 拍脑袋判断"看起来对了"。
>
> **为什么用前端页面验证（而不是单元测试）**：这份文档定位是**企业级可观测产品的实操手册**——而可观测产品的核心交付物就是"看得见的页面"（运维大屏、调试页面、事件流可视化）。用页面验证，既是在测系统、也是在逐步搭出产品本身。某些场景（重连、并发会话、多实例）curl 根本演示不了，页面是唯一直观手段。少数纯后端的边界用 curl 辅助。
>
> **为什么不用单元测试**：单元测试（JUnit/StepVerifier）在企业项目里当然重要，但这份文档的篇幅和"实操手册 + 学习"定位下，页面验证更直观、更贴合"看得见的可观测产品"。真实项目里两边都要有——本文聚焦页面验证这条主线，单元测试留给团队按需补。

### 全书里程碑

| 阶段 | 一句话 | 适用体量 |
|------|-------|---------|
| 第 0 章 | 黑盒 Agent | 起点 |
| 第 1 章 | 最小可见性：采集→总线→SSE | 单实例、内部工具 |
| 第 2 章 | 可靠性：不丢、不乱序、能重连 | 单实例、有真实用户 |
| 第 3 章 | 工具调用与 token 可见 | 单实例、Agent 调工具 |
| 第 4 章 | 规模化：状态外置、分片、多实例 | 多实例、日活 1w+ |
| 第 5 章 | 治理：多租户、成本、配额 | SaaS、多客户 |
| 第 6 章 | 灾备：降级、SRE、测试 | 生产关键链路 |
| 第 7 章 | 运营事故：心跳、重试、取消、连接治理、归档 | 对外运营后 |
| 第 8 章 | 持久化与历史：状态机、回放、列表、续传、幂等、脱敏、TTL | 产品成熟、要查历史/恢复 |

> 你不需要一次走到第 6 章——**走到你当前体量够用的阶段就停**，等痛点出现再往前。这是这份文档的核心纪律：不为想象中的需求写代码。

### 复现约定

- **包名**：本文用 `com.example.aobs` 演示（aobs = Agent Observability）。你自己敲时换成想要的包名，IDE 全局替换即可。**所有 import 的前缀要跟着换**。
- **代码完整性**：每个代码块都是完整的、带 import 的、照抄能编译的。不会留半截、不会埋错。
- **简陋处会标注**：有些代码第一版先写简单版（比如手写 JSON），后面章节会改进。改进点一定明确标注「这一版简陋，第 X 章会改」，并说明为什么现在不一次到位。
- **每章结尾有 checkpoint**：目录结构 + git 提交命令。养成小步提交的习惯。
- **前端页面**：验证主要靠调试页面（放 `src/main/resources/static/`，浏览器打开）。页面随章节演进——第 1 章是最简的事件流显示，后面章节逐步增强（工具调用面板、重连演示、租户切换）。每个版本的页面都是完整 HTML，照抄能跑。
- **企业级方案优先**：每个技术决策讲清"为什么选它、调研背书、否了什么"，不只是"能跑就行"。真实坑（竞态、重复注册、超时）作为"问题→根因→修复"的演进素材，不回避。

---

## 第 0 章：开张——建项目，跑通黑盒 Agent

### 0.0 场景

你所在的团队要做一个「AI 写作助手」：用户输入主题，AI 生成文章。技术上用 Spring AI 调 DeepSeek，分三步（生成大纲 → 写草稿 → 润色）。

这一章结束时，你有一个**能跑但完全黑盒**的 Agent——调一次，等十几秒，出结果，中间一无所知。

> 为什么从黑盒开始？因为真实项目就是这样：**先让它能干活，再让它能被观察**。一上来就铺可观测性架构是过度设计。我们要等它「出事」，才知道该观测什么。这个「出事」会在第 1 章发生。

### 0.1 思路：先建项目跑通业务，不碰可观测性

这一步零可观测性，只把业务链路跑通。关键决策两个：

| 决策 | 选择 | 理由 |
|------|------|------|
| Web 框架 | WebFlux（不是 Web） | SSE 和 Spring AI 流式都是响应式，WebFlux 天然契合；后面不用迁移 |
| 业务结构 | 抽象基类 + 具体子类 | 第 1 章要给基类加可观测性，抽象出来好统一改 |

### 0.2 动手

#### 0.2.1 建项目 + pom.xml

用 IDE 或 Spring Initializr 建 Maven 单模块项目。目录：

```
ai-writing-assistant/
└── pom.xml
```

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

    <groupId>com.example.aobs</groupId>
    <artifactId>ai-writing-assistant</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>ai-writing-assistant</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencies>
        <!-- WebFlux：HTTP 接口和 SSE 都靠它（注意不是 spring-boot-starter-web） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Spring AI 调 OpenAI 兼容协议（DeepSeek 兼容） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>

        <!-- 配置元数据，让 application.yaml 有提示（可选但推荐） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <!-- Spring AI 的 BOM：统一管 spring-ai 各组件版本 -->
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

> **小白疑问：为什么用 WebFlux 不是 Web？**
> Web（spring-boot-starter-web）基于 Servlet，阻塞模型，一个请求占一个线程。WebFlux 基于 Reactor，响应式非阻塞。我们要做的 SSE 推送（后面会大量用）在 WebFlux 下天然契合（返回 `Flux<ServerSentEvent>` 就行）。所以从一开始就用 WebFlux，后面不用迁移。
>
> **重要**：不要同时引 `spring-boot-starter-web` 和 `spring-boot-starter-webflux`，会冲突。只用 WebFlux。

#### 0.2.2 配置文件

`src/main/resources/application.yaml`：

```yaml
spring:
  ai:
    openai:
      # DeepSeek 兼容 OpenAI 协议，所以用 openai starter 配 deepseek 地址
      # 换成你自己的 DeepSeek API key（去 platform.deepseek.com 申请）
      api-key: ${DEEPSEEK_API_KEY:你的key}
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-chat
        temperature: 0.7
logging:
  level:
    org.springframework.ai: info
```

> ⚠️ **安全提示**：API key 不要硬编码进 yaml 再提交 git。上面 `${DEEPSEEK_API_KEY:你的key}` 是「优先读环境变量，没有就用默认值」。生产里删掉默认值、只读环境变量。`.gitignore` 别把带真实 key 的配置提交。

#### 0.2.3 启动类

`src/main/java/com/example/aobs/Application.java`：

```java
package com.example.aobs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`@SpringBootApplication` 是组合注解（开启自动装配 + 组件扫描）。扫描范围是 `com.example.aobs` 及子包，所以后面所有类放这个包下。

#### 0.2.4 业务核心：三步链式 Workflow

用「抽象基类 + 具体子类」结构（模板方法模式），因为第 1 章要给基类加可观测性。

`src/main/java/com/example/aobs/workflow/ChainingService.java`：

```java
package com.example.aobs.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 三步链式 Workflow 的抽象基类。
 * 子类只要声明「这三步分别做什么」（实现 steps()），run() 负责按顺序串起来执行。
 */
public abstract class ChainingService {

    protected final ChatClient chatClient;

    protected ChainingService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明步骤链。每步入参是（上一步输出, sessionId），出参是本步输出。 */
    protected abstract List<BiFunction<String, String, String>> steps();

    /** 执行整条链：把上一步输出喂给下一步当输入。 */
    public String run(String input, String sessionId) {
        String payload = input;
        for (BiFunction<String, String, String> step : steps()) {
            payload = step.apply(payload, sessionId);
        }
        return payload;
    }

    /** 调一次 LLM。子类的步骤里用这个。 */
    protected String call(String system, String prompt, String sessionId) {
        return chatClient.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
```

`src/main/java/com/example/aobs/writing/ArticleService.java`：

```java
package com.example.aobs.writing;

import com.example.aobs.workflow.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

/** 写作助手：大纲 → 草稿 → 润色。 */
@Service
public class ArticleService extends ChainingService {

    public ArticleService(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    protected List<BiFunction<String, String, String>> steps() {
        return List.of(
                (topic, sid) -> call("你是写作助手。根据主题生成大纲，只输出大纲本身。", topic, sid),
                (outline, sid) -> call("你是写作助手。根据大纲写一篇草稿，只输出草稿正文。", outline, sid),
                (draft, sid) -> call("你是写作助手。润色这篇草稿让它更流畅自然，只输出最终文本。", draft, sid)
        );
    }
}
```

> **小白疑问：`@Service` 和构造器注入**
> `@Service` 告诉 Spring「这是个 Bean，帮我管理」。`ChatClient` 通过构造器传进来，Spring 看到该类型 Bean 自动注入。这是 Spring 推荐写法（比 `@Autowired` 字段注入更利于测试）。

#### 0.2.5 HTTP 接口

`src/main/java/com/example/aobs/writing/ArticleController.java`：

```java
package com.example.aobs.writing;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/article")
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    /** 生成文章。curl "http://localhost:8080/api/article?prompt=AI的未来" -H "sessionId: s1" */
    @GetMapping
    public String generate(@RequestParam String prompt,
                           @RequestHeader String sessionId) {
        return articleService.run(prompt, sessionId);
    }
}
```

### 0.3 验证

```bash
mvn spring-boot:run
```

看到 `Started Application in x.xxx seconds` 就是起来了。另开终端：

```bash
curl "http://localhost:8080/api/article?prompt=AI的未来" -H "sessionId: s1"
```

等约 10-15 秒（三次 LLM 调用），返回一篇润色过的文章。

### 0.4 checkpoint

```
src/main/java/com/example/aobs/
├── Application.java
├── workflow/
│   └── ChainingService.java
└── writing/
    ├── ArticleService.java
    └── ArticleController.java
```

```bash
git init && git add -A
git commit -m "第0章：黑盒写作助手跑通"
```

### 0.5 复盘

产品能跑了。但试着回答这些：
- 用户说「这次特别慢」——你知道是三步里哪步慢吗？**不知道。**
- 想看中间的大纲——能看到吗？**不能。**
- 想加日志记录每步耗时——要不要改业务代码？**要。**

**黑盒的代价：内部不可见，每次想知道多一点都要改业务代码。** 第 1 章解决这个问题。

---

## 第 1 章：第一次事故——给它装上监控屏

### 1.0 场景

产品上线给团队内部试用。一周后同事反馈「有时候特别慢，偶尔还超时」。你打开日志，只有 `ArticleService.run` 的开始结束，中间一片空白。本地复现三次都正常（问题是偶发的）。

**你意识到：看不到 Agent 内部，根本没法定位偶发问题。** 决定给它装「监控屏」——让三步执行的每一步都能被实时看到。

### 1.1 思路：三层最小架构

要做什么：把第 0 章那个"等十几秒出一坨文本"的同步黑盒，改造成**边生成边把每一步、每个 token 片段实时推出去**的流式 Agent。拆成三层：

```
[写作 Workflow 流式每一步]  ——emit 事件——>  [事件总线]  ——订阅——>  [SSE 推给 curl/浏览器]
   （采集：run 自身是 Flux<AgentEvent>）        （总线）              （推送）
```

这一步同时做两件事：
1. **流式化**：`ChainingService.run` 从同步 `String` 改成 `Flux<AgentEvent>`——三步链式，每步用 Spring AI 的 `.stream()` 逐字推送 `CONTENT_DELTA`，用户看到打字机效果而不是干等。
2. **可观测**：每步前后发 `STEP_START/STEP_END`、整条链发 `SESSION_STARTED/SESSION_COMPLETED`，过程全可见。

**关键决策一：为什么让 `run` 自己返回 `Flux<AgentEvent>`？**

第 0 章同步 `run` 返回一个 `String`，调用方拿到结果就完事——但这样**过程对外不可见**。改成 `Flux<AgentEvent>` 后，`run` 本身就是"事件流"：它一边执行一边把事件推给下游。Controller 直接把这个 Flux 当 SSE 流返回，**不用额外组装**。这是响应式编程里最自然的"过程即数据流"写法。

**关键决策二：有了 `Flux<AgentEvent>`，为什么还要 EventBus（总线）？**

这是最容易被问倒的点——`run` 已经返回事件流了，直接订阅不就行了？不行，因为 `run` 返回的是**冷流**：每多一个订阅者，就会把整个 `run` **重新执行一遍**（重新调 3 次 LLM、重新烧钱）。但实际有**不止一个消费者**：前端 SSE 要看、日志要记、第 3 章要算成本、第 5 章要按租户归因——每个消费者各订阅一次 = 重跑 N 次 LLM。

解法：`run` 内部把每个事件 **`bus.emit` 广播到 EventBus**（热流），`Flux<AgentEvent>` 只是给"主消费者"（SSE）用的入口。总线用的 `Sinks.Many` 是热流——事件发一次就完，多个消费者共享同一条流。所以代码里你会看到 run 既 `return Flux` 又 `bus.emit`，两者不矛盾：

```
run() 返回 Flux<AgentEvent>        ← SSE 订阅它（主消费者，驱动整条链执行）
   每产出一个事件 → bus.emit(事件)  ← 其他消费者（日志/成本/归因）订阅总线，不触发重跑
```

> **冷流 vs 热流**（整个架构的地基，第 2 章会亲手体会）：
> - `Flux<AgentEvent>`（run 返回的）是**冷流**——"被订阅时才执行"，N 个订阅者 = 执行 N 次。它适合"只有一个主消费者驱动执行"。
> - `Sinks.Many`（EventBus 内部）是**热流**——"与订阅无关，发一次就过"，N 个订阅者共享同一份已发出的事件。它适合"广播给多个消费者"。
>
> 这个"run 冷流驱动 + 总线热流广播"的组合是后面所有演进的地基。第 1 章先记住结论。

### 1.2 动手

#### 1.2.1 EventContent——事件类型枚举 + AgentEvent——事件本身

先定义「有哪些事件类型」和「一个事件长什么样」。

`src/main/java/com/example/aobs/obs/EventContent.java`（新增）：

```java
package com.example.aobs.obs;

/**
 * 所有事件类型的唯一定义点。用枚举而不是散落的字符串常量：
 *   - 编译期校验：拼错事件名 IDE 立刻报红，不会等到运行时才发现。
 *   - 一处定义、处处引用：后面所有 emit 点、前端对照表都从这里取。
 *
 * 字段随章节长：第 1 章这几个；第 3 章加 TOOL_CALL/LLM_TOKENS；
 * 第 5 章加 QUOTA_EXCEEDED；第 7 章加 SESSION_CANCELLED。只加不删（schema 演进纪律，见第 5 章）。
 */
public enum EventContent {
    // —— 会话生命周期（第 1 章）——
    SESSION_STARTED,    // 会话开始
    STEP_START,         // 某步开始
    CONTENT_DELTA,      // 流式正文片段
    STEP_END,           // 某步结束
    SESSION_COMPLETED,  // 会话正常完成
    SESSION_FAILED,     // 会话失败
    // —— 工具与成本（第 3 章）——
    TOOL_CALL,          // 工具调用（参数/返回）
    LLM_TOKENS,         // LLM token 消耗 + 成本
    // —— 治理（第 5/7 章）——
    QUOTA_EXCEEDED,     // 配额超限（第 5 章）
    SESSION_CANCELLED,  // 用户取消（第 7 章）
    STEP_RESUMED;       // 续传起点（第 8 章）

    /** 序列化名。和枚举名一致，单独抽方法是为了日后改序列化格式时只改一处。 */
    public String wireName() { return name(); }
}
```

`src/main/java/com/example/aobs/obs/AgentEvent.java`：

```java
package com.example.aobs.obs;

import java.time.Instant;
import java.util.Map;

/**
 * 一个 Agent 事件，描述「Agent 执行过程中发生了什么」。
 * 用 record（Java 16+）：不可变数据类，自动生成构造器/getter/equals/hashCode。
 *
 * 字段随章节演进（见附录 A.7 时间线）：
 *   第 1 章：(type, sessionId, timestamp, data)
 *   第 2 章：加 criticality（背压降级策略）、sequence（会话内序号）
 *   第 5 章：加 tenantId/userId/agentVersion（成本归因 + 隔离）
 *
 * 构造方式：用 builder（见下方），不直接 new。builder 默认填 timestamp 和 criticality。
 */
public record AgentEvent(
        EventContent type,            // 事件类型（枚举，不拼字符串）
        String sessionId,             // 属于哪个会话（前端按这个过滤）
        Instant timestamp,            // 发生时间
        Map<String, Object> data      // 附加信息（这一步的输入/输出/正文片段等）
) {

    /** builder：默认填 timestamp，criticality 第 2 章加（这里先不出现，第 2 章扩展 builder）。 */
    public static Builder builder() { return new Builder(); }

    /** 第 1 章的快捷构造（后续章节扩展参数时新增重载，不删旧的）。 */
    public static AgentEvent of(EventContent type, String sessionId, Map<String, Object> data) {
        return builder().type(type).sessionId(sessionId).data(data).build();
    }

    /** 事件类型名（序列化用，对前端就是字符串 "SESSION_STARTED" 等）。 */
    public String typeName() { return type.wireName(); }

    public static final class Builder {
        private EventContent type;
        private String sessionId;
        private Instant timestamp;
        private Map<String, Object> data = Map.of();

        public Builder type(EventContent t) { this.type = t; return this; }
        public Builder sessionId(String s) { this.sessionId = s; return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder data(Map<String, Object> d) { this.data = d; return this; }

        public AgentEvent build() {
            if (type == null) throw new IllegalStateException("type 必填");
            return new AgentEvent(type, sessionId,
                    timestamp != null ? timestamp : Instant.now(), data);
        }
    }
}
```

> **小白疑问一：为什么用枚举 `EventContent` 不用字符串？**
> 字符串散落各处，拼错 `"SESSION_STATRED"`（拼错字母）IDE 不报错、运行时才暴露。枚举把"所有合法事件类型"集中在一处，拼错编译期就拦住，后面所有 emit 点、前端对照表都从这里取。
>
> **小白疑问二：为什么用 `Map<String,Object>` 装 data，不定义具体字段？**
> 不同事件附加信息差别大（SESSION_STARTED 有 input，CONTENT_DELTA 有 text 片段，STEP_END 有 output）。用 Map 灵活，新增事件类型不用改这个类。代价是类型不安全——第 2 章会权衡，先简单。
>
> **小白疑问三：为什么用 builder 不用 record 的构造器？**
> record 字段会随章节长（第 2、5 章继续加）。每次加字段，所有 `new AgentEvent(...)` 调用点都要改参数列表、还容易传错位置（都是 String）。builder 是"按名字赋值"，加新字段时老代码不用改、新字段有默认值——企业项目里 schema 会演进的 DTO 都该用 builder。第 1 章先把 builder 架子立起来。

#### 1.2.2 EventBus——事件总线（整个方案的心脏）

`src/main/java/com/example/aobs/obs/EventBus.java`：

```java
package com.example.aobs.obs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 事件总线。整个进程就一个实例（@Component 默认单例）。
 * 两个核心能力：emit(事件) 往总线塞事件；flux() 拿事件流。
 *
 * 底层 Sinks.Many 当成一个「公共公告板」：
 *   multicast：广播，所有订阅者都收得到
 *   onBackpressureBuffer(256)：消费者来不及处理时缓冲 256 条
 *
 * 背压满后会发生什么（企业级必须搞清）：
 *   缓冲满时 emit 调 tryEmitNext 返回 FAIL_TERMINATED/FAIL_OVERFLOW，事件被丢弃（不阻塞生产者）。
 *   这就是第 2 章事故 1「SESSION_COMPLETED 丢失」的根因——CRITICAL 事件被丢，前端永远转圈。
 *   解法不是"加大 buffer"（治标），而是「关键事件落 Redis 兜底」——丢了大不了重连回放，不能没有。
 *   buffer 大小按"消费者处理速度 × 瞬时积压窗口"定，256 对调试场景够，生产按 metrics 调。
 */
@Component
public class EventBus {

    // autoCancel=false：即使暂时没人订阅，总线也别自动关闭
    private final Sinks.Many<AgentEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    /** 生产者调这个：发射一个事件。 */
    public void emit(AgentEvent event) {
        sink.tryEmitNext(event);   // 非阻塞塞事件，失败也不抛
    }

    /** 消费者调这个：拿事件流。 */
    public Flux<AgentEvent> flux() {
        return sink.asFlux();
    }
}
```

#### 1.2.3 给 Workflow 埋点 + 流式化（采集）

改 `ChainingService`：① 加 EventBus 依赖；② `run` 从同步 `String` 改成 `Flux<AgentEvent>` 流式三步链；③ 每步用 `.stream()` 逐字推送 `CONTENT_DELTA`；④ 每个事件同时 `bus.emit` 广播。**业务步骤（steps()）完全不动**。

> **关于 sessionId 的传递**：第 1 章的 `run` 用同步 for 循环，sessionId 直接当方法参数/局部变量传，简单够用。等到第 3 章讲工具调用的会话隔离时，才会遇到"工具执行线程读不到局部变量"的问题，那时再引入 Reactor Context 传播（`PropagatedContextValue`/`AppContextKeys`）。**第 1 章不需要它**——提前引入只会让人困惑"这玩意儿有什么用"。

现在改 `ChainingService`（**这一版是整个改造的核心**）：

`src/main/java/com/example/aobs/workflow/ChainingService.java`（重写）：

```java
package com.example.aobs.workflow;

import com.example.aobs.obs.AgentEvent;
import com.example.aobs.obs.EventBus;
import com.example.aobs.obs.EventContent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;

/**
 * 三步链式 Workflow 的抽象基类（流式版）。
 * 子类只要声明「这三步分别做什么」（实现 steps()），run() 负责按顺序串起来、边生成边推事件。
 *
 * 流式三步链的事件序列（一次写作）：
 *   SESSION_STARTED
 *     STEP_START(0) → CONTENT_DELTA×（大纲逐字）→ STEP_END(0)
 *     STEP_START(1) → CONTENT_DELTA×（草稿逐字）→ STEP_END(1)
 *     STEP_START(2) → CONTENT_DELTA×（润色逐字）→ STEP_END(2)
 *   SESSION_COMPLETED  （出错则 SESSION_FAILED）
 */
public abstract class ChainingService {

    protected final ChatClient chatClient;
    protected final EventBus eventBus;    // ← 新增：广播事件给多消费者

    protected ChainingService(ChatClient chatClient, EventBus eventBus) {
        this.chatClient = chatClient;
        this.eventBus = eventBus;
    }

    /** 一步链：system prompt + 「把上一步输出转成本步 user prompt」的函数。 */
    public record Step(String system, java.util.function.BiFunction<String, String, String> toUserPrompt) {}

    /** 子类声明步骤链。每步给 system 和「上一步输出 → 本步 user prompt」。 */
    protected abstract List<Step> steps();

    /**
     * 执行整条链，返回事件流。
     *
     * 设计取舍（关键认知）：流式 + 链式，纯 Reactor 写法（concatMap/expand）能做但可读性差、
     * 调试难。企业项目里流式链式的标准写法是——**控制流用普通 for 循环（人人读懂），
     * 把它整体放进 Flux.create + subscribeOn(boundedElastic)**。阻塞循环跑在弹性线程池，
     * 不占 Reactor 线程；"上一步完整输出喂下一步"就是 `payload = full` 一句话，所有跨步骤传值难题消失。
     *
     * sessionId 写进 Reactor Context（第 3 章工具线程要读它），同时循环里直接用局部变量也行。
     */
    public Flux<AgentEvent> run(String input, String sessionId) {
        return Flux.<AgentEvent>create(sink -> {
                    FluxSinkAdapter out = new FluxSinkAdapter(sink, eventBus, sessionId);
                    List<Step> stepList = steps();
                    int total = stepList.size();

                    out.emit(EventContent.SESSION_STARTED, Map.of("input", input));

                    String payload = input;   // 上一步完整输出，首步是用户 input
                    for (int step = 0; step < total; step++) {
                        Step s = stepList.get(step);
                        out.emit(EventContent.STEP_START,
                                Map.of("step", step, "total", total));
                        String userPrompt = s.toUserPrompt().apply(payload, sessionId);
                        String full = streamStep(s.system(), userPrompt, sessionId, step, out);  // 流式 + 累积
                        out.emit(EventContent.STEP_END,
                                Map.of("step", step, "output", truncate(full, 80)));
                        payload = full;   // ← 本步完整输出喂下一步，一句话解决
                    }

                    out.emit(EventContent.SESSION_COMPLETED, Map.of());
                    sink.complete();
                })
                .onErrorResume(err -> {
                    // 出错：发 SESSION_FAILED（前端收到后停止转圈），不把异常抛给下游
                    AgentEvent failed = AgentEvent.builder()
                            .type(EventContent.SESSION_FAILED)
                            .sessionId(sessionId)
                            .data(Map.of("error",
                                    err.getClass().getSimpleName() + ": " + err.getMessage()))
                            .build();
                    eventBus.emit(failed);
                    return Flux.just(failed);
                })
                // 阻塞循环跑在弹性线程池（不占 Reactor 线程，可复用、有上限）
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 单步流式调 LLM：每个 chunk 既 emit CONTENT_DELTA（用户看打字机）、又累积进 sb。
     * 用 reduce 拼完整文本（只订阅上游一次），最后 block 取回完整字符串。
     *
     * 为什么 block 安全：run 整体已经 subscribeOn(boundedElastic)，这里 block 阻塞的是
     * 弹性线程，不是 Reactor 调度线程——符合 boundedElastic "专为阻塞任务设计"的用途。
     */
    private String streamStep(String system, String userPrompt, String sessionId, int step, FluxSinkAdapter out) {
        return chatClient.prompt()
                .system(system)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user(userPrompt)
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .reduce(new StringBuilder(), (sb, chunk) -> {
                    sb.append(chunk);
                    out.emit(EventContent.CONTENT_DELTA,
                            Map.of("text", chunk, "step", step));   // 逐字推给前端 + 广播总线
                    return sb;
                })
                .map(StringBuilder::toString)
                .block();   // 阻塞等本步完整文本（跑在 boundedElastic，安全）
    }

    /**
     * 把 FluxSink + EventBus 的"发事件"收口在一个 helper，避免每个 emit 点重复样板。
     * 发事件 = 同时推给 SSE 下游（sink.next）和总线（eventBus.emit，供其他消费者）。
     */
    private static final class FluxSinkAdapter {
        private final FluxSink<AgentEvent> sink;
        private final EventBus bus;
        private final String sessionId;
        FluxSinkAdapter(FluxSink<AgentEvent> sink, EventBus bus, String sid) {
            this.sink = sink; this.bus = bus; this.sessionId = sid;
        }
        void emit(EventContent type, Map<String, Object> data) {
            AgentEvent e = AgentEvent.builder().type(type).sessionId(sessionId).data(data).build();
            bus.emit(e);        // 广播给其他消费者（热流）
            sink.next(e);       // 推给 SSE 主消费者（冷流下游）
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

> **读这段代码的关键认知**：
> 1. **`run` 返回的是"冷流"**：不订阅不执行。Controller 把它当 SSE 流返回、浏览器一订阅，整条链才开始跑（这就是 1.1 讲的"冷流驱动执行"）。
> 2. **控制流是普通 for 循环**：流式链式不一定要堆 Reactor 高阶算子。`Flux.create` 把一个 for 循环包成流，循环里 `payload = full` 一句话把上一步完整输出喂下一步——比 `concatMap`/`expand` 链清晰得多。**企业项目里流式链式的标准写法**就是这种"控制流留同步、推送用响应式"。
> 3. **`subscribeOn(boundedElastic)`**：for 循环里有 `streamStep` 的 `.block()`（阻塞等本步完整文本）。阻塞必须跑在专为阻塞任务设计的 `boundedElastic` 线程池，**绝不能跑在 Reactor 调度线程**（会拖垮整个事件循环）。这是"在响应式代码里安全使用阻塞"的关键纪律。
> 4. **每个事件都同时 `bus.emit` + `sink.next`**：`FluxSinkAdapter` 把这俩收口在一处——`sink.next` 推给 SSE 主消费者（冷流下游），`bus.emit` 广播给 EventBus（热流）供日志/成本/归因等其他消费者订阅。
> 5. **sessionId 双通道**：循环里直接用局部变量 `sessionId`（同步代码，直接读）；同时 `.contextWrite` 写进 Reactor Context——这是为第 3 章铺路（工具执行线程经自动传播读 Reactor Context，读不到局部变量）。
> 6. **`reduce` 只订阅上游一次**：chunk 既逐字 emit 又拼成完整文本，靠 `reduce` 的累积器在一次订阅里完成（不用 `collectInto` 再订阅一次，避免重跑 LLM）。
>
> **⚠️ 这版的简化点（后续章节改进）**：
> - token 统计（第 3 章）、错误重试（第 7 章）都还没接。
> - SESSION_COMPLETED 的 output 摘要这里没填（要拿到最后一步完整文本，循环里其实有 `payload`，可顺手填——留给第 2 章细化）。
> - `ChatClient.Builder` 在第 3 章会自定义（注册工具），届时 `chatClient` 来源跟着调整。

因为父类构造器加了参数，子类 `ArticleService` 跟着改（**配套改动**，改了构造器要同步子类才能编译）：

`src/main/java/com/example/aobs/writing/ArticleService.java`（改构造器）：

```java
package com.example.aobs.writing;

import com.example.aobs.obs.EventBus;
import com.example.aobs.workflow.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 写作助手（流式版）：大纲 → 草稿 → 润色。
 * 每步声明 system prompt + 「上一步输出 → 本步 user prompt」。
 * 注意：steps() 只声明「这步干什么」，不直接调 LLM——执行（流式、埋点）全在基类 run()。
 */
@Service
public class ArticleService extends ChainingService {

    public ArticleService(ChatClient chatClient, EventBus eventBus) {
        super(chatClient, eventBus);
    }

    @Override
    protected List<Step> steps() {
        return List.of(
                // 第 0 步：主题 → 大纲（首步 payload 就是用户输入的主题）
                new Step("你是写作助手。根据主题生成大纲，只输出大纲本身。",
                        (topic, sid) -> topic),
                // 第 1 步：大纲 → 草稿（上一步输出的大纲直接当 user prompt）
                new Step("你是写作助手。根据大纲写一篇草稿，只输出草稿正文。",
                        (outline, sid) -> outline),
                // 第 2 步：草稿 → 润色（上一步输出的草稿直接当 user prompt）
                new Step("你是写作助手。润色这篇草稿让它更流畅自然，只输出最终文本。",
                        (draft, sid) -> draft)
        );
    }
}
```

#### 1.2.4 SSE Controller——把事件推给客户端

`src/main/java/com/example/aobs/obs/SseController.java`：

```java
package com.example.aobs.obs;

import com.example.aobs.writing.ArticleService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * SSE 推送接口。SSE（Server-Sent Events）：HTTP 长连接，服务端持续推数据，浏览器原生支持。
 */
@RestController
@RequestMapping("/api/obs")
public class SseController {

    private final EventBus eventBus;
    private final ArticleService articleService;

    public SseController(EventBus eventBus, ArticleService articleService) {
        this.eventBus = eventBus;
        this.articleService = articleService;
    }

    /**
     * 订阅写作事件流并触发执行。run 自身返回 Flux<AgentEvent>——订阅它就触发整条链，
     * 每个事件转成 SSE 帧推给客户端。用法：
     * curl -N "http://localhost:8080/api/obs/article?prompt=AI的未来" -H "sessionId: s1"
     *
     * ⚠️ 这版有个竞态隐患（1.4 会暴露并修）：run 的 Flux 是冷流，订阅时才执行。
     *    Controller 返回后、浏览器真正订阅前，中间有窗口——1.4 用页面复现，1.5 用 READY 握手修。
     */
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader String sessionId) {
        // run 返回事件 Flux（订阅即执行），转成 SSE 帧
        return articleService.run(prompt, sessionId)
                .map(this::toSse)
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.event())   // 收到终态结束流
                        || "SESSION_FAILED".equals(e.event()));
    }

    private ServerSentEvent<String> toSse(AgentEvent e) {
        return ServerSentEvent.<String>builder()
                .id(e.sessionId() + "-" + e.timestamp().toEpochMilli())  // 帧ID（第2章重连用）
                .event(e.typeName())                                      // 枚举 → 序列化名
                .data(toJson(e))
                .build();
    }

    /** 简易 JSON 序列化。⚠️ 简陋版：第 2 章会换成 ObjectMapper。 */
    private String toJson(AgentEvent e) {
        return "{\"type\":\"" + e.typeName() + "\",\"data\":" + mapToJson(e.data()) + "}";
    }

    private String mapToJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append("\"").append(k).append("\":\"")
                .append(String.valueOf(v).replace("\"", "'")).append("\","));
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        return sb.append("}").toString();
    }
}
```

> **关于 `new Thread(...)`**：这是第一版的偷懒做法，第 2 章会换成响应式线程池。真实项目不该用裸 `new Thread`（无上限、开销大），但第 1 章先聚焦「让事件流通起来」，每章只引入一个新难点。
>
> **关于手写 JSON**：同样简陋，第 2 章换 `ObjectMapper`。**为什么现在不一次到位？** 因为第一版要让你看清「SSE 帧长什么样」（手动拼 JSON 最直观），等你看懂了再换健壮实现。
>
> **关于竞态**：上面 ⚠️ 标的那段，是这一章最值钱的教训——我们故意先不修，让 1.4 的测试把它"逼"出来。企业项目里很多问题不是 review 看出来的，是测试/压测跑出来的。


### 1.3 前端页面——看得见的可观测产品

这份文档的验证主要靠**前端页面**，不是 curl，更不是单元测试。原因有二：
1. 可观测产品的核心交付物就是"看得见的页面"——我们边验证边把这个产品搭出来。
2. 很多场景（实时事件流、重连、并发会话）curl 根本演示不了，页面是唯一直观手段。

第 1 章先给一个最简页面：输入 prompt → 调 `/api/obs/article` 这个 SSE 接口 → 实时把收到的事件一条条显示出来。

`src/main/resources/static/index.html`（第 1 章版）：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>AI 写作助手 · 可观测调试台</title>
    <style>
        body { font-family: -apple-system, "PingFang SC", sans-serif; background: #f7f7f8; margin: 0; padding: 24px; }
        h1 { font-size: 18px; }
        #bar { display: flex; gap: 8px; margin: 16px 0; }
        #prompt { flex: 1; padding: 8px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
        #send { padding: 8px 16px; background: #4d6bfe; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
        #send:disabled { background: #c5cdfa; }
        #events { background: #fff; border-radius: 8px; padding: 12px; max-height: 70vh; overflow-y: auto; }
        .ev { padding: 6px 8px; margin: 4px 0; border-left: 3px solid #4d6bfe; font-size: 13px; background: #fafafa; }
        .ev b { color: #4d6bfe; }
        .ev .t { color: #999; font-size: 11px; margin-left: 8px; }
    </style>
</head>
<body>
<h1>AI 写作助手 · 可观测调试台（第 1 章）</h1>
<div id="bar">
    <input id="prompt" placeholder="输入主题，如：AI 的未来" value="AI 的未来">
    <button id="send" onclick="start()">生成</button>
</div>
<div id="events"></div>
<script>
    let sending = false;
    async function start() {
        if (sending) return;
        const prompt = document.getElementById('prompt').value.trim();
        if (!prompt) return;
        const sessionId = 's-' + Date.now();
        document.getElementById('events').innerHTML = '';
        sending = true;
        document.getElementById('send').disabled = true;

        // fetch SSE 接口，用 ReadableStream 逐帧读取（不用 EventSource，因为要带 sessionId header）
        const resp = await fetch('/api/obs/article?prompt=' + encodeURIComponent(prompt), {
            headers: { 'sessionId': sessionId, 'Accept': 'text/event-stream' }
        });
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {       // 按空行切 SSE 帧
                handleFrame(buffer.slice(0, idx));
                buffer = buffer.slice(idx + 2);
            }
        }
        sending = false;
        document.getElementById('send').disabled = false;
    }

    function handleFrame(frame) {
        let type = '', data = '';
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (!type) return;
        const div = document.createElement('div');
        div.className = 'ev';
        div.innerHTML = '<b>' + type + '</b><span class="t">' + new Date().toLocaleTimeString() + '</span><br>' + data;
        document.getElementById('events').appendChild(div);
        document.getElementById('events').scrollTop = 99999;
    }
</script>
</body>
</html>
```

> **页面怎么读事件**：后端返回的是标准 SSE 流（每帧有 `event:` 行和 `data:` 行，帧间空行分隔）。前端用 `fetch + ReadableStream` 逐块读、按空行切帧。没用浏览器原生 `EventSource`，因为 `EventSource` 不能设自定义 header（`sessionId` 走 header）。
>
> **这个页面随章节演进**：第 1 章只显示事件流；第 2 章加重连演示；第 3 章加工具调用面板；第 5 章加租户切换。每章给完整的页面新版本。

#### 1.3.1 给 EventBus 加定向订阅（页面要用的）

SSE 接口要"只推某个会话的事件"，所以 `EventBus` 补一个带过滤的 `flux` 重载（1.2.4 的 `SseController` 已经用到了 `flux(e -> sessionId.equals(...))`，这里把实现补上）：

```java
/** 消费者调这个：拿过滤后的事件流（SSE 按会话订阅用）。 */
public Flux<AgentEvent> flux(java.util.function.Predicate<AgentEvent> filter) {
    return sink.asFlux().filter(filter);
}
```


#### 1.3.2 启动 + 打开页面

```bash
mvn spring-boot:run
```

浏览器打开 `http://localhost:8080/index.html`（Spring Boot 自动服务 `static/` 下的静态资源）。点「生成」，你会看到事件**逐条实时到达**：

```
SESSION_STARTED   14:30:01   {"type":"SESSION_STARTED","data":{"input":"AI 的未来"}}
STEP_START        14:30:01   {"type":"STEP_START","data":{"step":"0","total":"3"}}
CONTENT_DELTA     14:30:02   {"type":"CONTENT_DELTA","data":{"text":"一、","step":0}}
CONTENT_DELTA     14:30:02   {"type":"CONTENT_DELTA","data":{"text":"引言","step":0}}
CONTENT_DELTA     14:30:03   {"type":"CONTENT_DELTA","data":{"text":"...","step":0}}
STEP_END          14:30:05   {"type":"STEP_END","data":{"step":"0","output":"一、引言..."}}
STEP_START        14:30:05   {"type":"STEP_START","data":{"step":"1","total":"3"}}
...
SESSION_COMPLETED 14:30:15   {"type":"SESSION_COMPLETED","data":{}}
```

**对比第 0 章的黑盒**：那个等 15 秒出一坨文本；这个**生成过程中正文逐字冒出来**（CONTENT_DELTA 像打字机），还能看到「现在第几步、上一步产出了什么」。这就是「流式 + 可观测」的体感。

### 1.4 用页面验证 → 顺带搞懂"冷流为什么天然无竞态"

在页面上点「生成」，你会看到事件**逐条实时到达**——开头必定是 `SESSION_STARTED`，最后是 `SESSION_COMPLETED`，中间 CONTENT_DELTA 像打字机一样逐字冒。连点十几次，**每次开头都是 SESSION_STARTED，从不丢**。

这其实是个值得停下来想的事——为什么这么稳？

**关键认知：冷流天然消除了竞态**。看 1.2.4 的 `stream()`：Controller 直接 `return articleService.run(...).map(toSse)`，`run` 返回的是**冷流**（订阅时才执行）。WebFlux 把这条流返回后，**浏览器一订阅，run 才开始跑**——SESSION_STARTED 是在订阅之后才 emit 的，根本不存在"先发后订"的时间差。

> **对比"热流 + 异步触发"的竞态**：如果像老式写法那样——先 `new Thread(() -> run())` 启动任务（往热流 EventBus 里 emit SESSION_STARTED），再 `return eventBus.flux()` 让前端订阅——那就是"先发后订"，热流不缓存历史，SESSION_STARTED 会丢。这是经典的 SSE 竞态。
>
> **本方案为什么避开了它**：`run` 返回冷流、Controller 直接返回它、触发就是订阅本身——三件事合一，从架构上消除了竞态。**这是冷流的核心价值之一**：把"触发执行"和"建立订阅"变成同一件事，没有时序缝隙。
>
> **那 EventBus（热流）不就白引入了？** 没。EventBus 服务的是**其他消费者**（日志/成本/归因）——它们订阅 EventBus 时确实可能错过已发出的事件，但那不致命（成本统计漏一帧无所谓，关键事件第 2 章落 Redis 兜底）。SSE 这个**主消费者**走冷流，关键路径稳。这就是 1.1 讲的"冷流驱动 + 热流广播"分工的价值。

> **页面仍能暴露别的时序问题**：竞态没了，但"页面打开后 fetch 还没返回的那一两秒，用户不知道提交了没"——这个体验问题还在。1.5 用 READY 帧解决它。

### 1.5 改进：READY 帧——给前端一个"流已就绪"的信号

虽然冷流让 SESSION_STARTED 必达，但前端从"点提交"到"收到第一帧"之间有个黑盒窗口——用户盯着空白页面，不知道是在跑还是卡了。企业级 SSE 系统的标准做法：订阅建立后，流首立刻插一帧 `READY`，前端收到就知道"连接已建立，后面的事件马上来"。

这个 READY 帧也是企业级 SSE 的通用做法——在订阅建立后立刻发一个控制帧，让前端明确"连接通了"。后面章节（第 7 章连接治理）会复用同样的"控制帧"思路（心跳帧也是 SSE 注释帧）。本章先把它立起来。

改 `SseController.stream()`，流首 `startWith(READY)`：

```java
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader String sessionId) {
        ServerSentEvent<String> ready = ServerSentEvent.<String>builder()
                .id(sessionId + "-ready").event("READY").data("{\"type\":\"READY\"}").build();

        // run 返回冷流（订阅即执行），流首插 READY 帧，再 takeUntil 到终态结束
        return articleService.run(prompt, sessionId)
                .map(this::toSse)
                .startWith(ready)
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.event())
                        || "SESSION_FAILED".equals(e.event()));
    }
```

> **`startWith` 的语义**：在原流**前面**接一个固定元素。下游订阅时，先收到 READY，再收到 run 产出的真实事件。READY 在订阅瞬间就发——填补了"提交到第一帧"的窗口。
>
> **为什么 READY 不是事件类型**：READY 是 SSE 协议层的握手帧（event=`READY`），不进 `EventContent` 枚举——它不属于"Agent 执行事件"，是传输层的控制帧。把它和业务事件分开，是分层干净的体现。
>
> **冷流方案 vs doOnSubscribe**：老式热流方案要靠 `doOnSubscribe`（订阅时才触发任务）来消除竞态。本方案冷流触发即订阅，不需要 `doOnSubscribe`——少一个心智负担。这就是"冷流驱动执行"省掉的东西。

**页面复测**：重启服务，页面点「生成」。这次第一条是 `READY`（前端据此显示"生成中…"），紧接着 `SESSION_STARTED` → CONTENT_DELTA 逐字 → `SESSION_COMPLETED`。体验和稳定性都有了。

### 1.6 checkpoint

```
src/main/java/com/example/aobs/
├── Application.java
├── workflow/
│   └── ChainingService.java     （改：加 EventBus + 埋点）
├── writing/
│   ├── ArticleService.java      （改：构造器）
│   └── ArticleController.java
└── obs/                          ← 新增
    ├── AgentEvent.java           （新增：record + builder + 枚举）
    ├── EventContent.java         （新增：事件类型枚举）
    ├── EventBus.java             （新增：事件总线 Sinks.Many）
    └── SseController.java        （新增：订阅 run 的 Flux + READY 帧）

src/main/resources/
└── static/
    └── index.html                ← 新增：可观测调试页面（随章节演进）
```

```bash
git add -A && git commit -m "第1章：流式Agent+事件总线+SSE+调试页面"
```

### 1.7 复盘

**做了**：把第 0 章的同步黑盒改造成流式 Agent——`run` 返回 `Flux<AgentEvent>`，三步链式边生成边推 `CONTENT_DELTA`（打字机效果）；搭起三层骨架（采集→总线→SSE）；给了第一个调试页面；并加了 READY 帧给前端就绪信号。

**这一章最该记住的工程教训**：
1. **冷流驱动执行，天然消除竞态**——`run` 返回冷流，订阅即执行，"触发"和"订阅"合一，没有"先发后订"的时序缝隙。对比老式"热流 + 异步触发"会丢 SESSION_STARTED。**这是冷流的核心价值**。
2. **冷流 + 热流分工**——run 返回冷流给 SSE 主消费者（驱动执行、关键路径稳）；EventBus（Sinks.Many 热流）广播给其他消费者（日志/成本，漏一帧无所谓）。这是响应式架构的地基设计。
3. **流式链式的标准写法**——`Flux.create` + 普通 for 循环 + `subscribeOn(boundedElastic)`，比堆 Reactor 高阶算子（concatMap/expand）清晰得多。阻塞跑在弹性线程池，安全。

**还差（后面章节解决）**：
- **事件可能丢**：`tryEmitNext` 失败被静默吞，高峰期缓冲满了 `SESSION_COMPLETED` 可能丢 → **第 2 章**
- **事件可能乱序**：埋点在不同时机发，理论上可能乱序到达 → **第 2 章**
- **断了重连会漏**：网络抖动、刷新页面，重连后中间事件没了 → **第 2 章**
- **只看到 Workflow 步骤**：还没看到工具调用细节、token 消耗 → **第 3 章**
- **手写 JSON 简陋**：要换 ObjectMapper → **第 2 章**

**最该理解的**：整个架构的「心脏」是 `EventBus`（那个 `Sinks.Many`）。生产者和消费者通过它解耦。这个结构是后面所有演进的地基——后面加什么都不改这个骨架，只往里加东西。

---

> **第 1 章结束。**
>
> 第 2 章会让它「可靠」（不丢、不乱、能重连），并兑现上面两个改进承诺（线程池、ObjectMapper）。可靠性机制用页面演示（尤其重连——curl 做不了，页面是唯一直观手段），遇到问题就修——和这一章一样的"页面验证 → 暴露问题 → 修复"节奏。

---

## 第 2 章：用户投诉——让事件可靠

### 2.0 场景：三连投诉

第 1 章的版本上线用了几天，收到三个投诉，正好对应第 1 章末尾列的三个问题：

1. **「生成完了但页面一直转圈」**——查代码：`SESSION_COMPLETED` 没推到前端。高峰期事件多，`Sinks.Many` 的 256 缓冲满了，`tryEmitNext` 失败被静默吞掉。**事件丢了。**
2. **「进度条乱跳，第 2 步比第 1 步先显示」**——埋点在不同时机 emit，理论上可能乱序到达。**事件乱序。**
3. **「刷新页面，中间步骤没了」**——`Sinks.Many` 是热流，重连后只看得到重连之后的事件。**重连漏事件。**

这一章逐个解决。先解决最痛的——**事件丢失**。

> 这一章还顺带兑现第 1 章两个承诺：`new Thread` 换线程池、手写 JSON 换 ObjectMapper。

### 2.1 思路

#### 解决「丢失」：事件分级 + 关键事件落库兜底

把事件分三类，区别对待：

| 级别 | 例子 | 丢了后果 | 策略 |
|------|------|---------|------|
| CRITICAL | `SESSION_STARTED/COMPLETED/FAILED` | 前端永远转圈 | 先落 Redis，背压满也能回放 |
| NORMAL | `STEP_START/STEP_END` | 进度条少一帧 | 尽力送达 |
| DISCARDABLE | 流式正文片段（第 3 章） | 无所谓 | 背压满优先丢 |

> 这里第一次引入 Redis。第 0-1 章项目零外部依赖，可靠性需求出现才引入——这是演进的真实节奏。

#### 解决「重连漏」：Last-Event-ID 回放 + 消费端幂等

浏览器 `EventSource` 断线重连会**自动**带 `Last-Event-ID` 头（W3C 标准）。后端读这个头，从 Redis 把断连期间的事件补发。前提是后端每帧设了 `id:`——第 1 章已经设了，这就是「为演进留口子」。

> **⚠️ 回放是 at-least-once，不是 exactly-once——前端必须幂等去重**。
> 网络抖动可能导致 Last-Event-ID 没对齐（前端实际收到 5 条，但重连时报的 id 是 3），后端会从 3 之后补发——前端会**再次收到 4、5**（重复）。可靠消息的铁律是：**投递端 at-least-once（不丢）+ 消费端幂等去重（不重）**。前端按 `sequence` 去重（已收过的 sequence 丢弃）。具体代码见 2.3 的 reconnect.html。

#### 解决「乱序」：会话内序号

给每个事件一个会话内递增序号，SSE 端按序号排序后再发。但**单实例、单线程编排下序号天然有序，重排在第 2 章用不上**——这里只加序号字段（成本低、为后面铺路），重排逻辑放第 4 章（那里有多线程、多实例，乱序才真会发生）。

> **这是「不预先实现」的纪律**：序号字段现在加，重排逻辑等真需要时再写。否则第 2 章就要讲一堆现在用不上的排序窗口逻辑。

### 2.2 动手

#### 2.2.1 加 Redis 依赖

`pom.xml` 的 `<dependencies>` 加：

```xml
<!-- Redis：第 2 章开始用，存关键事件兜底 + 重连回放 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

`application.yaml` 加：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  ai:
    openai:
      # ... 原有配置不变
```

> 本地装 Redis：macOS `brew install redis && brew services start redis`；Docker `docker run -d -p 6379:6379 redis`。

#### 2.2.2 AgentEvent 加 criticality 字段

`src/main/java/com/example/aobs/obs/AgentEvent.java`（修改）：

```java
package com.example.aobs.obs;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        EventContent type,
        String sessionId,
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality    // ← 第 2 章新增
) {

    /** 事件关键级别，决定背压满时降级策略。 */
    public enum Criticality {
        CRITICAL,      // 终态类，绝不能丢，落库兜底
        NORMAL,        // 阶段/步骤事件，尽力送达
        DISCARDABLE    // 流式片段（CONTENT_DELTA），背压满优先丢
    }

    /** 按事件类型枚举推断默认关键级别。 */
    public static Criticality defaultCriticality(EventContent type) {
        return switch (type) {
            case SESSION_STARTED, SESSION_COMPLETED, SESSION_FAILED -> Criticality.CRITICAL;
            case CONTENT_DELTA -> Criticality.DISCARDABLE;
            default -> Criticality.NORMAL;
        };
    }

    public static Builder builder() { return new Builder(); }

    /** 第 2 章快捷构造：自动填 timestamp + 默认 criticality。 */
    public static AgentEvent of(EventContent type, String sessionId, Map<String, Object> data) {
        return builder().type(type).sessionId(sessionId).data(data).build();
    }

    public String typeName() { return type.wireName(); }

    public static final class Builder {
        private EventContent type;
        private String sessionId;
        private Instant timestamp;
        private Map<String, Object> data = Map.of();
        private Criticality criticality;   // null = 按 type 推断默认

        public Builder type(EventContent t) { this.type = t; return this; }
        public Builder sessionId(String s) { this.sessionId = s; return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder data(Map<String, Object> d) { this.data = d; return this; }
        public Builder criticality(Criticality c) { this.criticality = c; return this; }

        public AgentEvent build() {
            if (type == null) throw new IllegalStateException("type 必填");
            Criticality c = criticality != null ? criticality : defaultCriticality(type);
            return new AgentEvent(type, sessionId,
                    timestamp != null ? timestamp : Instant.now(), data, c);
        }
    }
}
```

> **第 2 章相对第 1 章的变化**：① record 加 `criticality` 字段；② builder 加 `criticality()` 方法（不填则按 type 推断默认——CRITICAL 事件落库、CONTENT_DELTA 可丢）；③ `defaultCriticality` 改成接受 `EventContent` 枚举（第 1 章是 String）。因为用了 builder，老的 `AgentEvent.of(...)` 调用点**不用改**（of 内部走 builder）。
>
> **小白疑问：`switch` 表达式（Java 14+）**
> `switch (type) { case ... -> ... }` 箭头形式不穿透、直接返回值。枚举的 switch 还能穷举所有 case——漏一个编译器会提示（除非用 default）。Java 21 完全支持。

#### 2.2.3 关键事件落库：CriticalEventStore

用 Redis Sorted Set（有序集合）存，score 用时间戳，方便按顺序回放。

`src/main/java/com/example/aobs/obs/CriticalEventStore.java`（新增）：

```java
package com.example.aobs.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 关键事件存储：Redis Sorted Set，score 用时间戳。
 * 两个用途：① 背压满兜底；② SSE 重连回放。
 */
@Component
public class CriticalEventStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public CriticalEventStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String sessionId) {
        return "aobs:events:" + sessionId;
    }

    /** 存一个事件，score 用时间戳，天然按发生顺序。 */
    public void save(AgentEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            String k = key(event.sessionId());
            redis.opsForZSet().add(k, json, event.timestamp().toEpochMilli());
            redis.expire(k, TTL);   // 每次存都续期：活跃会话保持，会话停止后 30 分钟自动清理整个 key
        } catch (Exception e) {
            System.err.println("[CriticalEventStore] save failed: " + e.getMessage());
        }
    }
```

> **Redis 清理策略（防内存撑爆）**：
> - **会话级 key + TTL**：每个会话一个 key（`aobs:events:{sessionId}`），`expire` 30 分钟。会话活跃时每次 save 续期；会话结束后 30 分钟 Redis 自动删整个 key——无需手动清理。
> - **为什么不在 ZSet 内删旧成员**：ZSet 没有按 score 自动过期成员的能力。靠 key 级 TTL 已够（单会话关键事件就几条，30 分钟内不会撑爆）。
> - **超长会话的兜底**：如果业务有写几小时的超长会话，ZSet 会一直长——那时加 `opsForZSet().removeRangeByScore(k, 0, now - 1h)` 定期剪掉 1 小时前的成员（保留最近窗口用于回放）。本项目 30 分钟 TTL 够用，不预先实现（演进纪律）。
    /**
     * 读某会话中，指定时间戳之后的所有事件（重连回放用）。
     * @param afterEpochMs 只读这个时间戳之后的事件
     */
    public List<AgentEvent> findAfter(String sessionId, long afterEpochMs) {
        Set<String> jsonSet = redis.opsForZSet().rangeByScore(
                key(sessionId), afterEpochMs + 1, Double.MAX_VALUE);
        if (jsonSet == null) return List.of();
        List<AgentEvent> result = new ArrayList<>();
        for (String json : jsonSet) {
            try {
                result.add(mapper.readValue(json, AgentEvent.class));
            } catch (Exception ignore) {
                // 单条解析失败跳过
            }
        }
        return result;
    }
}
```

> **API 核实**：`opsForZSet().add(key, member, score)`、`rangeByScore(key, min, max)`、`expire(key, Duration)` 都是 `spring-data-redis` 真实方法。`ObjectMapper` 来自 Jackson（Spring Boot 自带）。这是第 1 章承诺的「手写 JSON 换 ObjectMapper」。

#### 2.2.4 EventBus 落库关键事件 + 分配序号

这一步同时做两件事：关键事件落库（解决丢失）、分配会话内序号（为乱序重排铺路）。

先给 `AgentEvent` 再加 `sequence` 字段：

`src/main/java/com/example/aobs/obs/AgentEvent.java`（再加字段）：

```java
public record AgentEvent(
        EventContent type,
        String sessionId,
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality,
        long sequence        // ← 第 2 章新增：会话内单调递增序号，0 表示未分配
) {
    public enum Criticality { CRITICAL, NORMAL, DISCARDABLE }

    public static Criticality defaultCriticality(EventContent type) {
        return switch (type) {
            case SESSION_STARTED, SESSION_COMPLETED, SESSION_FAILED -> Criticality.CRITICAL;
            case CONTENT_DELTA -> Criticality.DISCARDABLE;
            default -> Criticality.NORMAL;
        };
    }

    public static Builder builder() { return new Builder(); }

    /** 快捷构造（sequence 默认 0，由 EventBus 分配；criticality 按 type 推断）。 */
    public static AgentEvent of(EventContent type, String sessionId, Map<String, Object> data) {
        return builder().type(type).sessionId(sessionId).data(data).build();
    }

    public String typeName() { return type.wireName(); }

    public static final class Builder {
        private EventContent type;
        private String sessionId;
        private Instant timestamp;
        private Map<String, Object> data = Map.of();
        private Criticality criticality;
        // sequence 不进 builder：它由 EventBus 统一分配，不该由构造方填

        public Builder type(EventContent t) { this.type = t; return this; }
        public Builder sessionId(String s) { this.sessionId = s; return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder data(Map<String, Object> d) { this.data = d; return this; }
        public Builder criticality(Criticality c) { this.criticality = c; return this; }

        public AgentEvent build() {
            if (type == null) throw new IllegalStateException("type 必填");
            Criticality c = criticality != null ? criticality : defaultCriticality(type);
            return new AgentEvent(type, sessionId,
                    timestamp != null ? timestamp : Instant.now(), data, c, 0L);
        }
    }
}
```

> **第 2 章这步相对上一步（2.2.2）的变化**：record 再加 `sequence` 字段（6 字段）。`sequence` 不进 builder——它由 EventBus 统一分配（见下方 withSequence），构造方不该自己填。所以 builder 没有序列号方法，`build()` 固定填 0，EventBus emit 时复制一份改 sequence。

`src/main/java/com/example/aobs/obs/EventBus.java`（修改：落库 + 序号）：

```java
package com.example.aobs.obs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EventBus {

    private final Sinks.Many<AgentEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    // ObjectProvider：有 Redis 就用 CriticalEventStore，没有也能启动（开发环境容错）
    private final ObjectProvider<CriticalEventStore> storeProvider;

    // 每个会话一个递增计数器（为序号用）
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public EventBus(ObjectProvider<CriticalEventStore> storeProvider) {
        this.storeProvider = storeProvider;
    }

    public void emit(AgentEvent event) {
        // 1. 分配会话内序号
        long seq = sequences.computeIfAbsent(event.sessionId(), k -> new AtomicLong())
                .incrementAndGet();
        AgentEvent sequenced = withSequence(event, seq);

        // 2. 关键事件先落库（兜底）
        CriticalEventStore store = storeProvider.getIfAvailable();
        if (store != null && sequenced.criticality() == AgentEvent.Criticality.CRITICAL) {
            store.save(sequenced);
        }

        // 3. 推总线
        sink.tryEmitNext(sequenced);
    }

    /** record 不可变，复制一份改 sequence。 */
    private static AgentEvent withSequence(AgentEvent e, long seq) {
        return new AgentEvent(e.type(), e.sessionId(), e.timestamp(), e.data(),
                e.criticality(), seq);
    }

    public Flux<AgentEvent> flux() {
        return sink.asFlux();
    }
}
```

> **为什么用 `AtomicLong`？**
> 序号要「自增」且线程安全。`AtomicLong.incrementAndGet()` 原子操作，多线程同时 emit 不会拿到重复序号。`ConcurrentHashMap` 保证「每会话一个计数器」的可见性。
>
> **为什么用 `ObjectProvider` 不直接 `@Autowired`？**
> 直接注入的话，没装 Redis 时 `StringRedisTemplate` 创建失败、整个应用起不来。`ObjectProvider.getIfAvailable()` 是「有就用、没有跳过」，让应用无 Redis 也能跑（只是没兜底）。开发体验上的小体贴。

#### 2.2.5 SseController 加回放 + 换线程池 + 换 ObjectMapper

`src/main/java/com/example/aobs/obs/SseController.java`（大改）：

```java
package com.example.aobs.obs;

import com.example.aobs.writing.ArticleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/obs")
public class SseController {

    private final EventBus eventBus;
    private final ArticleService articleService;
    private final ObjectProvider<CriticalEventStore> storeProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public SseController(EventBus eventBus,
                         ArticleService articleService,
                         ObjectProvider<CriticalEventStore> storeProvider) {
        this.eventBus = eventBus;
        this.articleService = articleService;
        this.storeProvider = storeProvider;
    }

    /**
     * 两种模式：
     *   - 正常请求（无 Last-Event-ID）：订阅 run 的冷流，订阅即触发写作（第 1 章方案）。
     *   - 重连请求（带 Last-Event-ID）：写作已在执行/已结束，不能重跑——走「Redis 回放 + 总线订阅」，
     *     补发断连期间的关键事件，再接实时事件。
     */
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader(name = "sessionId", required = false) String fromHeader,
                                                @RequestParam(name = "sessionId", required = false) String fromQuery,
                                                @RequestHeader(value = "Last-Event-ID", required = false)
                                                    String lastEventId) {
        String sessionId = fromHeader != null ? fromHeader : fromQuery;   // 兼容 query（EventSource 用）

        // 重连模式：回放 + 总线订阅
        if (lastEventId != null) {
            CriticalEventStore store = storeProvider.getIfAvailable();
            Flux<AgentEvent> replay = (store != null)
                    ? Flux.fromIterable(store.findAfter(sessionId, parseTimestampFrom(lastEventId)))
                    : Flux.empty();
            Flux<AgentEvent> live = eventBus.flux().filter(e -> sessionId.equals(e.sessionId()));
            return Flux.concat(replay, live)
                    .takeUntil(e -> e.type() == EventContent.SESSION_COMPLETED
                            || e.type() == EventContent.SESSION_FAILED)
                    .map(this::toSse);
        }

        // 正常模式：直接订阅 run 冷流（订阅即触发），流首插 READY（第 1 章）
        ServerSentEvent<String> ready = ServerSentEvent.<String>builder()
                .id(sessionId + "-ready").event("READY").data("{\"type\":\"READY\"}").build();
        return articleService.run(prompt, sessionId)
                .map(this::toSse)
                .startWith(ready);
    }
```

> **为什么重连不能再订阅 run**：run 是冷流、且代表"一次写作的执行"。重连时那次写作已经在第一次请求里触发了（或已结束），再订阅 run 会**重跑一遍 LLM**——既浪费钱、也不是用户想要的（用户要的是"接着看之前那次"）。所以重连走「Redis 回放关键事件 + EventBus 订阅后续」——这是热流广播的价值兑现：写作只执行一次，事件进 EventBus，重连者从总线接续。
>
> **「SSE 心跳」留给产品对外时**：现在内网/IDE 跑，两事件间隔再长连接也不会被断。等产品部署到 nginx/CDN 后面，代理的 60s 空闲超时会断慢生成连接——那时才需要心跳。**现在不做**（演进纪律），方案见附录 A.10。
    /** 帧ID 格式 "sessionId-时间戳"，取时间戳部分。 */
    private long parseTimestampFrom(String lastEventId) {
        int idx = lastEventId.lastIndexOf('-');
        if (idx < 0) return 0;
        try { return Long.parseLong(lastEventId.substring(idx + 1)); }
        catch (NumberFormatException e) { return 0; }
    }

    private ServerSentEvent<String> toSse(AgentEvent e) {
        return ServerSentEvent.<String>builder()
                .id(e.sessionId() + "-" + e.timestamp().toEpochMilli())
                .event(e.typeName())          // 枚举 → 序列化名（"SESSION_STARTED" 等）
                .data(toJson(e))
                .build();
    }

    /** 用 ObjectMapper（替换第1章手写 JSON）。 */
    private String toJson(AgentEvent e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (Exception ex) {
            return "{\"type\":\"" + e.typeName() + "\"}";
        }
    }
}
```

> **三个改进（都是第 1 章承诺）**：① 加 Last-Event-ID 回放；② 触发方式（run 冷流，订阅即触发，不再需要 `Mono.fromRunnable`）；③ 手写 JSON → ObjectMapper。
>
> **Jackson 序列化枚举 record**：`AgentEvent` 的 `type` 字段现在是 `EventContent` 枚举。Jackson 默认把枚举序列化成 `name()`（即 `"SESSION_STARTED"`），前端拿到的 JSON 里 `type` 还是字符串——和手写 JSON 时代兼容。`toSse` 里 `.event(e.typeName())` 同步取序列化名，保证 SSE 的 event 字段也是字符串。

> **「事后查历史」留给以后**：到这里 Redis 兜底 + 重连回放已经解决了"不丢"。但"事后查历史"（用户说"我昨天那次有问题"，Redis 早清了）是另一个能力——它要长期归档存储。**这不是第 2 章的痛点**（第 2 章只解决"实时/短期不丢"），所以现在不做。等到真有"查历史"的需求（产品成熟到要排查历史会话）再做，方案见附录 A.10。

### 2.3 验证（用页面 + Redis CLI）

确保 Redis 已启动。启动应用，打开 `http://localhost:8080/index.html`，测三个场景：

**场景 1：正常流程**（确认没退化）——页面点「生成」，和第 1 章一样看到完整事件流。

**场景 2：关键事件落库了**——页面跑完后查 Redis：
```bash
redis-cli ZRANGE aobs:events:s1 0 -1
```
能看到 `SESSION_STARTED`、`SESSION_COMPLETED` 两条（CRITICAL 落了库），`STEP_*` 不在（NORMAL 没落）。这就是"关键事件兜底"——即使高峰期缓冲满了丢事件，CRITICAL 的也在 Redis 里。

**场景 3：重连回放**（这一章的核心，页面演示）——重连是 curl 做不了的，必须用页面。但第 1 章的页面用 `fetch`（不自动重连），要演示自动重连得用浏览器原生 `EventSource`（它断线自动重连 + 自动带 `Last-Event-ID` 头）。

矛盾点：`EventSource` 不能设自定义 header（sessionId 没法走 header）。企业级解法——**sessionId 改走 query 参数**，后端兼容 query 和 header 两种来源。

后端 `SseController` 改 sessionId 取法（兼容 query）：
```java
    // 优先 header，没有用 query（让 EventSource 这种不能设 header 的客户端也能用）
    String resolveSessionId(@RequestHeader(name = "sessionId", required = false) String fromHeader,
                            @RequestParam(name = "sessionId", required = false) String fromQuery) {
        return fromHeader != null ? fromHeader : fromQuery;
    }
```

第 2 章页面增强版（用 EventSource 演示重连）——新建 `src/main/resources/static/reconnect.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>重连回放演示</title>
<style>body{font-family:sans-serif;padding:24px} .ev{padding:4px 8px;margin:2px 0;border-left:3px solid #4d6bfe;font-size:13px} .reconn{color:#e67e22;font-weight:bold} .dup{color:#999;font-style:italic}</style>
</head><body>
<h2>重连回放演示（第 2 章）</h2>
<p>sessionId 走 query。点开始后，中途 <b>停掉后端 3 秒再重启</b>，看 EventSource 自动重连 + Last-Event-ID 回放。</p>
<button onclick="start()">开始（sessionId=rc-demo）</button>
<div id="events"></div>
<script>
// 幂等去重：记录已收到的帧 id（对应后端 sequence）。回放可能重复投递，这里去重。
const seenIds = new Set();
let reconnectAttempts = 0;          // 重连次数（指数退避用）
const MAX_RECONNECT = 5;            // 最大重连次数，超了放弃（避免无限打爆后端）
let es = null;

function connect() {
    // EventSource：sessionId 走 query；断线自动重连，自动带 Last-Event-ID 头
    es = new EventSource('/api/obs/article?prompt=重连演示&sessionId=rc-demo');
    es.onopen = () => { reconnectAttempts = 0; add('[连接已建立]', 'reconn'); };
    es.onmessage = e => {
        if (e.lastEventId && seenIds.has(e.lastEventId)) {
            add('[重复帧已丢弃] ' + e.lastEventId, 'dup');   // 幂等：已收过的丢弃
            return;
        }
        if (e.lastEventId) seenIds.add(e.lastEventId);
        add(e.data);
    };
    es.addEventListener('SESSION_COMPLETED', e => { add('[完成]'); es.close(); });
    // 重连退避：EventSource 默认 3s 固定重连，会打爆挂掉的后端。这里接管——
    // onerror 时主动 close，按指数退避（2s/4s/8s...）自己重连，超阈值放弃。
    es.onerror = () => {
        es.close();
        if (reconnectAttempts >= MAX_RECONNECT) {
            add('[重连失败，请稍后手动重试]', 'reconn');
            return;
        }
        const delay = Math.min(2000 * Math.pow(2, reconnectAttempts), 30000); // 指数退避，上限 30s
        reconnectAttempts++;
        add('[' + delay/1000 + 's 后第 ' + reconnectAttempts + ' 次重连]', 'reconn');
        setTimeout(connect, delay);
    };
}
function start() {
    document.getElementById('events').innerHTML = '';
    seenIds.clear();
    reconnectAttempts = 0;
    connect();
}
function add(text, cls) {
    const d = document.createElement('div');
    d.className = 'ev ' + (cls || '');
    d.textContent = new Date().toLocaleTimeString() + '  ' + text;
    document.getElementById('events').appendChild(d);
}
</script></body></html>
```

**操作**：打开 `reconnect.html` 点开始 → 事件流开始到达 → **停掉后端**（Ctrl+C）→ 页面连接断开，EventSource 自动尝试重连 → **重启后端** → EventSource 重连成功，后端读 `Last-Event-ID` 从 Redis 补发断连期间错过的关键事件。页面会看到 `[连接已建立]` 再次出现 + 补发的事件。

> **为什么重连必须用 EventSource 而不是 fetch**：`fetch + ReadableStream` 是一次性读取，断了就断了，不会自动重连，也不会带 `Last-Event-ID`。`EventSource` 是 W3C 标准，断线自动重连 + 自动带 `Last-Event-ID` 是它的核心能力。企业级 SSE 系统（需要可靠重连的）都用 EventSource 或带重连逻辑的客户端库。
>
> **Last-Event-ID 怎么生效**：第 1 章给每帧设了 `id:`（`toSse` 里的 `.id(...)`），EventSource 重连时自动把最后收到的 id 放进 `Last-Event-ID` 请求头。后端读这个头，从 Redis 的 ZSet 里查"比这个 id 更晚的"关键事件补发。这就是第 1 章"为演进留口子"的兑现。

### 2.4 checkpoint

```
src/main/java/com/example/aobs/obs/
├── AgentEvent.java          （改：加 criticality + sequence）
├── EventBus.java            （改：落库 + 分配序号）
├── CriticalEventStore.java  （新增：Redis 短期兜底）
└── SseController.java       （改：回放 + 线程池 + ObjectMapper + sessionId 兼容 query）

src/main/resources/static/
├── index.html               （第1章版，不变）
└── reconnect.html           （新增：EventSource 重连演示 + 退避）
```
pom 加了 `spring-boot-starter-data-redis`。

```bash
git add -A && git commit -m "第2章：事件可靠性——分级落库、序号、重连回放（页面演示）"
```

### 2.5 复盘

**解决了第 1 章的三个问题**：
- ✅ 事件丢失 → 关键事件落库兜底
- ✅ 重连漏事件 → Last-Event-ID 回放
- 🟡 事件乱序 → 序号字段加好了，重排逻辑第 4 章再写

**兑现改进承诺**：✅ ObjectMapper（手写 JSON 退役）。线程池第 1 章已用 `subscribeOn(boundedElastic)` 解决（run 冷流自带）。

**引入新依赖**：Redis（项目从零依赖变成有一个）。

**还差**：
- 看不到工具调用细节、token 消耗 → **第 3 章**
- 多实例下序号/状态会裂 → **第 4 章**

---

## 第 3 章：看到工具调用与 token

### 3.0 场景

产品迭代：用户反馈「写出来的文章字数经常超」，你决定加一个「查字数」工具，让润色步骤先查字数再决定怎么润色。同时财务找你：「这个月 LLM 花了多少钱？哪些会话烧得多？」——你现在完全不知道 token 消耗。

两个新需求：
1. 让 Agent 能调工具，且**工具调用过程要可见**（调了什么、参数、返回、耗时）
2. **统计每次 LLM 的 token 消耗**，算成本

### 3.1 思路

#### 工具调用可见：订阅 Spring AI 原生 Observation（不自己拦）

工具调用发生在 Spring AI 内部（`ToolCallingAdvisor` 循环里）。第一反应可能是"自己包一层装饰器拦 `ToolCallback.call`"——但这条路有两个问题：① 手动包底层 `ToolCallback` 侵入性强、不像用框架；② 工具调用循环内部拦不全。

**企业级方案**：Spring AI 2.0 原生就在发工具调用的 Observation——`ToolCallingObservation`。我们只写一个 `ObservationHandler` **订阅**它，把"工具名/参数/返回值"转成事件。零侵入工具代码。

> **调研背书**：[Spring AI 官方 Tool Calling 文档](https://docs.spring.io/spring-ai/reference/api/tools.html)明确，工具执行经 `DefaultToolCallingManager`（带 `ObservationRegistry`）执行，设计上就发 `ToolCallingObservation`。`ToolCallingObservationContext` 提供 `getToolDefinition().name()`、`getToolCallArguments()`、`getToolCallResult()`。这是官方机制，不是 hack。
>
> **对比装饰器（被否）**：装饰器要"把 @Tool 对象转成 ToolCallback[] 再包一层拦截 call()"——能达成可见，但要写底层代码、且只拦得到"自己包的那条路径"。Observation 是框架在所有工具执行路径上都发的，覆盖更全。**能用框架原生能力，就不要自己包底层**——这是企业级的基本判断。

#### 会话隔离：工具事件怎么标对 sessionId

工具的 Observation 由 Spring AI 内部发起，`ObservationHandler.onStop` 只能读"当前线程上下文"。问题是：sessionId 在 ChainingService 的 run 循环里，而工具执行在 Spring AI 内部的线程上——**两者不在同一个线程**。要让它拿到 sessionId，得把 sessionId 跨线程传过去。

**关键认知——33b 是流式模型，工具执行会切线程**：本项目第 1 章已把 `ChainingService.call` 改成流式 `.stream()`（跑在 `boundedElastic` 线程池）。Spring AI 在流式下处理工具调用时，工具可能在不同于"发起调用的线程"上执行。所以"在第 1 章的 for 循环里 `set(sessionId)` 到 ThreadLocal、`onStop` 同线程 `get()`"这条路**走不通**——工具执行线程读不到调用线程的 ThreadLocal。

**企业级方案：Reactor Context + 自动传播（ContextPropagation）**。这是 Micrometer Context Propagation 库的核心能力——把 sessionId 放进 Reactor Context，注册一个 `ThreadLocalAccessor`，开启 `Hooks.enableAutomaticContextPropagation()` 后，Reactor 会**自动**把 Reactor Context 里的值同步到任务执行线程的 ThreadLocal 上。于是：

```
run 循环里 contextWrite(sessionId)          → 写进 Reactor Context
  ↓（自动传播，跨越 boundedElastic 线程切换）
工具执行线程：ThreadLocal 里有 sessionId     → onStop 里 get() 读到
```

> **为什么不能只用 ThreadLocal**：ThreadLocal 是"线程局部"的，线程一切就丢。第 1 章同步 for 循环里直接用局部变量 `sessionId` 够用（同线程），但工具执行切到别的线程，局部变量和 ThreadLocal 都到不了。Reactor Context 是"流的数据"，随流传播；配上自动传播，它能在线程切换时自动灌进目标线程的 ThreadLocal——这是响应式代码里传"请求级上下文"的标准解法。
>
> **和同步模型的区别**：如果项目是纯同步 `.call()`（工具在调用线程执行），用最简单的 ThreadLocal set/get 即可（[Spring AI 官方](https://docs.spring.io/spring-ai/reference/api/tools.html)说明同步模式工具在调用线程执行）。**本项目流式模型会切线程，所以必须用 Reactor Context + 自动传播**。两套方案的差异是线程模型决定的，不是任选——这是企业级"按实际线程模型选方案"的体现。
>
> **关于 Context Propagation 库**：它是 Micrometer 的独立库（`io.micrometer:context-propagation`），Spring Boot 3+ 的 webflux/actuator 已传递带入。Reactor 3.5+ 的 `Hooks.enableAutomaticContextPropagation()` 依赖它，把"Reactor Context ↔ ThreadLocal"的桥接自动化。

#### token 统计：流式末尾取 Usage 旁路发事件

LLM 返回的 `ChatResponse` 里有 `Usage`（token 数）。流式下，`Usage` 在**最后一个 chunk** 的 metadata 里（前面 chunk 没有）。难点是「统计了不影响主流程」——用旁路方式：在每步流结束时取 Usage 发个 `LLM_TOKENS` 事件，token 统计交给消费者，业务代码不操心。

> **关于 token 单价**：不同模型单价不同（DeepSeek 比 GPT-4 便宜几十倍）。我们维护一个单价表，按 `promptTokens × 输入价 + completionTokens × 输出价` 算成本。单价是配置，随官方调价改。

### 3.2 动手

#### 3.2.1 加一个工具：查字数

先给写作助手加个简单工具，让 Agent 真有工具可调。

`src/main/java/com/example/aobs/writing/WritingTools.java`（新增）：

```java
package com.example.aobs.writing;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 写作辅助工具。@Tool 注解的方法会被 Spring AI 注册成工具，LLM 可以自主调用。
 */
@Component
public class WritingTools {

    /** 查文本字数。LLM 调用时传 text 参数。 */
    @Tool(description = "统计给定文本的字数（中文按字算，英文按词算的近似）")
    public int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        // 简单实现：去空格后长度。真实场景按语言细分。
        return text.replaceAll("\\s+", "").length();
    }
}
```

> **`@Tool` 注解**：Spring AI 2.0 的工具声明方式。`description` 告诉 LLM 这个工具干什么，LLM 据此决定要不要调。方法参数 LLM 会自动从对话里提取。

#### 3.2.2 让 ChatClient 注册这个工具

改启动配置，让 `ChatClient` 默认带上 `WritingTools`。新建一个配置类：

`src/main/java/com/example/aobs/config/ChatClientConfig.java`（新增）：

```java
package com.example.aobs.config;

import com.example.aobs.writing.WritingTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * 自定义 ChatClient：默认带上写作工具。
     * Spring AI 的 ChatClient.Builder 是自动注入的（starter 提供）。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, WritingTools tools) {
        return builder
                .defaultTools(tools)           // 注册工具，LLM 可自主调用
                .build();
    }
}
```

> **注意**：`ChainingService` 注入的是 `ChatClient`。Spring AI starter 默认会提供一个 `ChatClient` Bean。这里我们**自定义**一个带工具的 `ChatClient` Bean 覆盖默认的。如果启动报「多个 ChatClient Bean」冲突，删掉这个自定义 Bean、改成在 `ChainingService.streamStep` 的 `chatClient.prompt().tools(tools)` 调用时传也行——两种方式任选。

#### 3.2.3 工具调用可见：订阅原生 Observation（采集点）

写一个 `ObservationHandler` 订阅 Spring AI 原生的 `ToolCallingObservation`，把"工具名/参数/返回值"转成 `TOOL_CALL` 事件。不碰任何 `@Tool` 方法。

先加 actuator 依赖（它带来 `ObservationRegistry`）：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

**(a) 上下文封装（三件套）**——让 sessionId 从 Reactor Context 自动传播到工具执行线程的 ThreadLocal，`onStop` 才能读到：

`src/main/java/com/example/aobs/obs/PropagatedContextValue.java`：
```java
package com.example.aobs.obs;

import io.micrometer.context.ThreadLocalAccessor;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 可传播的上下文值：同时是「Reactor Context 的 key」+「ThreadLocal 容器」+「ThreadLocalAccessor」。
 * 流式下工具执行切线程，sessionId 要靠它三重身份跨线程到达 onStop：
 *   - Reactor Context key：run 里 .contextWrite(SESSION_ID.write(ctx, sid)) 写进去；
 *   - ThreadLocalAccessor：注册到 ContextRegistry 后，Reactor 自动把 Context 的值
 *     灌进任务线程的 ThreadLocal，工具线程就能 SESSION_ID.getValue() 读到。
 * 泛型，任意类型可用。
 */
public class PropagatedContextValue<T> implements ThreadLocalAccessor<T> {
    private final String key;
    private final ThreadLocal<T> threadLocal = new ThreadLocal<>();

    public PropagatedContextValue(String key) { this.key = key; }
    public String key() { return key; }

    // —— Reactor Context 读写（run 里用）——
    public Context write(Context ctx, T value) { return ctx.put(key, value); }
    public T read(ContextView ctx) {
        @SuppressWarnings("unchecked") T v = (T) ctx.getOrDefault(key, null);
        return v;
    }

    // —— ThreadLocalAccessor 接口（自动传播用）——
    @Override public Object getKey() { return key; }
    @Override public T getValue() { return threadLocal.get(); }       // onStop 里调这个读
    @Override public void setValue(T value) { threadLocal.set(value); }  // Reactor 自动传播时调
    @Override public void setValue() { threadLocal.remove(); }
    public T get() { return threadLocal.get(); }   // getValue 的短别名，代码里顺手用
}
```

`src/main/java/com/example/aobs/obs/AppContextKeys.java`：
```java
package com.example.aobs.obs;

/** 全局上下文注册中心：所有可传播值的唯一定义点。 */
public final class AppContextKeys {
    public static final PropagatedContextValue<String> SESSION_ID =
            new PropagatedContextValue<>("sessionId");
    private AppContextKeys() {}
}
```

`src/main/java/com/example/aobs/obs/ContextPropagationConfig.java`（新增——开启自动传播）：
```java
package com.example.aobs.obs;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;

/**
 * 开启 Reactor 自动上下文传播。
 *
 * 做两件事：
 *   1. 注册 ThreadLocalAccessor：告诉 ContextRegistry「sessionId 这个 key ↔ 这个 ThreadLocal」。
 *   2. Hooks.enableAutomaticContextPropagation()：让 Reactor 在线程切换（如 subscribeOn 到
 *      boundedElastic、或 Spring AI 内部的工具执行调度）时，自动把 Reactor Context 的值
 *      同步到目标线程的 ThreadLocal。
 *
 * 开启后：run 里 contextWrite(sessionId) → 工具执行线程的 ThreadLocal 自动有 sessionId →
 *        onStop 里 AppContextKeys.SESSION_ID.getValue() 读到。流式下工具切线程不再丢 sessionId。
 *
 * ⚠️ enableAutomaticContextPropagation 是全局开关，整个 JVM 生效一次。放 @PostConstruct 里、
 *    且只在应用启动时调一次（多次调无副作用，但没意义）。
 */
@Component
public class ContextPropagationConfig {

    @PostConstruct
    public void init() {
        ContextRegistry registry = io.micrometer.context.ContextRegistry.getInstance();
        registry.registerThreadLocalAccessor(AppContextKeys.SESSION_ID);
        Hooks.enableAutomaticContextPropagation();
    }
}
```

> **API 核实**：`io.micrometer.context.ThreadLocalAccessor`（getKey/getValue/setValue）来自 `context-propagation` 库（Spring Boot webflux/actuator 传递带入）；`ContextRegistry.getInstance().registerThreadLocalAccessor(accessor)` 接受 `ThreadLocalAccessor` 实例；`reactor.core.publisher.Hooks.enableAutomaticContextPropagation()` 是 Reactor 3.5+ 的全局开关。
>
> **这是第 3 章最核心的技术点**：流式 Agent 下"请求级上下文（sessionId/tenantId）怎么跨线程到达框架内部回调"。答案就是 Reactor Context + Micrometer Context Propagation 自动传播。同步模型用不到它（同线程 ThreadLocal 够用），流式模型必须用它。

**(b) Observation Handler——订阅工具调用，发 TOOL_CALL 事件**：

`src/main/java/com/example/aobs/obs/ToolObservationHandler.java`（新增）：
```java
package com.example.aobs.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订阅 Spring AI 原生的工具调用 Observation，转成 TOOL_CALL 事件。
 * 关键时机：onStop 时工具已执行完，getToolCallResult() 才有值。
 *
 * ⚠️ 只标 @Component，不写 ObservationConfig 手动注册——
 *    Spring Boot 会自动注册所有 ObservationHandler Bean。
 *    手动再注册一次 = 同一实例注册两次 = onStop 被调两遍 = 打印/发事件两遍（真实踩过的坑）。
 */
@Component
public class ToolObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

    private final EventBus eventBus;

    public ToolObservationHandler(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStop(ToolCallingObservationContext context) {
        // sessionId 从 ThreadLocal 读——自动传播已把 Reactor Context 的值灌进来
        // （见 ContextPropagationConfig）。流式下工具切线程也读得到。
        String sessionId = AppContextKeys.SESSION_ID.getValue();
        if (sessionId == null) sessionId = "unknown";

        String toolName = context.getToolDefinition() != null
                ? context.getToolDefinition().name() : "unknown";

        // HashMap 而非 Map.of——getToolCallArguments()/getToolCallResult() 可能返回 null
        Map<String, Object> data = new HashMap<>();
        data.put("tool", toolName);
        data.put("args", context.getToolCallArguments());
        data.put("result", context.getToolCallResult());

        eventBus.emit(AgentEvent.of(EventContent.TOOL_CALL, sessionId, data));
    }
}
```

> **API 核实**：`ObservationHandler<T>.onStop/supportsContext`（micrometer-observation，webflux 间接带入）、`ToolCallingObservationContext.getToolDefinition().name()/getToolCallArguments()/getToolCallResult()`（spring-ai-model 2.0.0）——均为官方 API。
>
> **⚠️ 别写 ObservationConfig 手动注册**（真实坑）：[Spring Boot 官方文档](https://docs.spring.io/spring-boot/reference/actuator/observability.html)明确——`ObservationHandler` Bean 会被自动注册到 Registry。如果你再写一个 `@Configuration` 里 `registry.observationConfig().observationHandler(toolHandler)`，同一实例注册两次，`onStop` 被调两遍，`TOOL_CALL` 事件发两遍。只靠 `@Component` 自动注册即可。
>
> **onStop 才发事件**：工具开始时（onStart）还没有返回值。onStop 时工具已执行完，`getToolCallResult()` 有值——这时才能发完整的"参数+返回值"事件。

> **关于 traceId（重要认知，不用加代码）**：你订阅的 Observation 体系（`ToolCallingObservation`、Spring AI 的 LLM Observation）**自带 traceId/spanId**——每次 LLM 调用、每次工具调用各是一个 span，串成一条调用树。这是**分布式追踪（tracing）**的维度，和我们自建的 `sessionId`（业务会话维度）是两套并行的可观测：
> - `sessionId`：用户这次写作（"帮写一篇AI未来"）——业务语义，贯穿三步链。本文的事件流用它。
> - `traceId`：一次技术调用链——LLM→工具→Redis，框架自动生成。用来回答"这次写作里，到底是第 2 步那次 LLM 慢了，还是它调 countWords 慢了"。
>
> **本文为什么不自己实现 traceId**：① 文档定位是"业务可观测（事件流）"，tracing 是另一条线（附录 A.8 列为上线前补）；② 单服务项目里 sessionId + step 已能定位，traceId 在跨服务链路才不可替代；③ 框架已自动生成 traceId，你不用造。你该知道的是"这两套要靠 traceId↔sessionId 关联打通"——归档时存 trace_id 列（第 7 章/附录 A.8），任意一个维度能跳查另一个。**别把 traceId 和 sessionId 混为一谈，也别重复造框架已有的东西**。


#### 3.2.4 token 统计：在 streamStep 里旁路发事件

流式下 token 信息在**最后一个流式响应**的 metadata 里。第 1 章的 `streamStep` 用 `.stream().content()` 只拿文本——这里改成 `.stream().chatResponse()` 拿完整响应（每个 chunk 是一个 `ChatResponse`，最后一个含 `Usage`）。reduce 累积文本的同时，把最后一个 chunk 的 Usage 取出发 `LLM_TOKENS`。

`src/main/java/com/example/aobs/workflow/ChainingService.java`（改 streamStep + 加 emitTokens）：

```java
// streamStep 改：用 chatResponse() 流，reduce 时同时累积文本和记录最后一个 response
private String streamStep(String system, String userPrompt, String sessionId, int step, FluxSinkAdapter out) {
    org.springframework.ai.chat.model.ChatResponse[] last = {null};   // 装最后一个 chunk（含 Usage）

    String full = chatClient.prompt()
            .system(system)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
            .user(userPrompt)
            .stream()
            .chatResponse()                                   // ← 改：拿完整响应（不只是 content）
            .filter(resp -> resp.getResult() != null
                    && resp.getResult().getOutput().getText() != null
                    && !resp.getResult().getOutput().getText().isEmpty())
            .map(resp -> {
                String chunk = resp.getResult().getOutput().getText();
                last[0] = resp;                               // 每次更新，结束时就是最后一个
                return chunk;
            })
            .reduce(new StringBuilder(), (sb, chunk) -> {
                sb.append(chunk);
                out.emit(EventContent.CONTENT_DELTA,
                        Map.of("text", chunk, "step", step));
                return sb;
            })
            .map(StringBuilder::toString)
            .block();

    emitTokens(sessionId, last[0]);   // 旁路发 token 事件（不影响主流程，full 照常返回）
    return full;
}

/** 取 Usage 发 LLM_TOKENS 事件。流式下 Usage 在最后一个 response 的 metadata 里。 */
private void emitTokens(String sessionId, org.springframework.ai.chat.model.ChatResponse response) {
    try {
        if (response == null) return;
        var usage = response.getMetadata().getUsage();
        if (usage == null) return;
        int prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        String model = response.getMetadata().getModel();
        double cost = costCalculator.calculate(prompt, completion, model);

        eventBus.emit(AgentEvent.of(EventContent.LLM_TOKENS, sessionId,
                Map.of("promptTokens", prompt,
                        "completionTokens", completion,
                        "totalTokens", prompt + completion,
                        "model", String.valueOf(model),
                        "costUsd", cost)));
    } catch (Exception ignore) {
        // 统计失败不影响主流程
    }
}
```

> **API 核实**：`stream().chatResponse()` 返回 `Flux<ChatResponse>`（流式版，每个 chunk 一个）；`getMetadata().getUsage()` → `Usage.getPromptTokens()/getCompletionTokens()`、`ChatResponseMetadata.getModel()` 全部真实存在。流式下 Usage 只在最后一个 chunk 有值（前面 chunk 的 Usage 是 null），所以用 `last[0]` 记最后一个。
>
> **为什么不需要 `AppContextKeys.SESSION_ID.set()` 了**：第 1 章同步版要在 call 里 `set(sessionId)` 给工具线程用——那是同步模型。现在流式版靠 `ContextPropagationConfig` 的自动传播（3.2.3），run 里 `.contextWrite` 写进 Reactor Context，自动灌进工具线程，不用手动 set/clear。
>
> **小白疑问：为什么叫「旁路」？**
> token 统计是「额外关心的事」，不是写作主流程。我们发个事件让消费者去统计，`streamStep` 该返回什么还返回什么（本步完整文本）。统计逻辑和业务逻辑解耦——后面想加 Langfuse 上报、想换计费方式，都改消费者不改 `streamStep`。

> **配套改动：run 要补 `.contextWrite(sessionId)`**。第 1 章精简版删掉了它（那时没有自动传播）。第 3 章加了 `ContextPropagationConfig`，run 必须把 sessionId 写进 Reactor Context，自动传播才有东西可传。在 `run()` 返回的 Flux 链尾加一行：
> ```java
> .contextWrite(AppContextKeys.SESSION_ID.write(reactor.util.context.Context.empty(), sessionId))
> ```
> （import `com.example.aobs.obs.AppContextKeys` 和 `reactor.util.context.Context`）。这是第 3 章相对第 1 章的唯一"非 streamStep"改动——为了工具会话隔离。

#### 3.2.5 成本计算器

`src/main/java/com/example/aobs/obs/CostCalculator.java`（新增）：

```java
package com.example.aobs.obs;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 按 model 单价算成本。单价来自 DeepSeek/OpenAI 官方定价（美元/1K tokens）。
 * 这里用近似值，真实场景从配置读、随官方调价改。
 */
@Component
public class CostCalculator {

    // 每模型「(输入价, 输出价)」美元/1K tokens
    private final Map<String, double[]> pricing = Map.of(
            "deepseek-chat", new double[]{0.00027, 0.00110},    // DeepSeek 近似
            "deepseek-v4-flash", new double[]{0.00014, 0.00028},
            "gpt-4o", new double[]{0.00250, 0.01000}
    );
    private static final double[] DEFAULT = {0.0003, 0.001};

    public double calculate(int promptTokens, int completionTokens, String model) {
        double[] p = pricing.getOrDefault(model == null ? "" : model, DEFAULT);
        return promptTokens / 1000.0 * p[0] + completionTokens / 1000.0 * p[1];
    }
}
```

> `ChainingService` 要注入 `CostCalculator`（构造器加参数），别忘了同步改 `ArticleService` 构造器——这是第 0 章讲过的「配套改动」纪律。

### 3.3 验证（页面演示 + 一个真实坑）

第 3 章把页面升级为"工具 + token 可见"版——替换 `src/main/resources/static/index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>AI 写作助手 · 可观测调试台（第 3 章）</title>
    <style>
        body { font-family: -apple-system, "PingFang SC", sans-serif; background: #f7f7f8; margin: 0; padding: 24px; }
        h1 { font-size: 18px; }
        #bar { display: flex; gap: 8px; margin: 16px 0; align-items: center; }
        #prompt { flex: 1; padding: 8px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
        #send { padding: 8px 16px; background: #4d6bfe; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
        #send:disabled { background: #c5cdfa; }
        #cost { font-size: 13px; color: #666; margin-left: 12px; }
        #cost b { color: #e67e22; }
        #events { background: #fff; border-radius: 8px; padding: 12px; max-height: 70vh; overflow-y: auto; }
        .ev { padding: 6px 8px; margin: 4px 0; border-left: 3px solid #4d6bfe; font-size: 13px; background: #fafafa; }
        .ev.tool { border-left-color: #00b96b; } .ev.tool b { color: #00b96b; }
        .ev.tokens { border-left-color: #e67e22; } .ev.tokens b { color: #e67e22; }
        .ev .t { color: #999; font-size: 11px; margin-left: 8px; }
    </style>
</head>
<body>
<h1>AI 写作助手 · 可观测调试台（第 3 章：工具 + token 可见）</h1>
<div id="bar">
    <input id="prompt" placeholder="输入主题，提到字数会触发工具" value="写一篇关于 AI 的 300 字短文">
    <button id="send" onclick="start()">生成</button>
    <span id="cost">本次成本：<b id="costVal">$0.0000</b></span>
</div>
<div id="events"></div>
<script>
    let sending = false, totalCost = 0;
    async function start() {
        if (sending) return;
        const prompt = document.getElementById('prompt').value.trim();
        if (!prompt) return;
        const sessionId = 's-' + Date.now();
        document.getElementById('events').innerHTML = '';
        totalCost = 0; document.getElementById('costVal').textContent = '$0.0000';
        sending = true; document.getElementById('send').disabled = true;

        const resp = await fetch('/api/obs/article?prompt=' + encodeURIComponent(prompt), {
            headers: { 'sessionId': sessionId, 'Accept': 'text/event-stream' }
        });
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
                handleFrame(buffer.slice(0, idx));
                buffer = buffer.slice(idx + 2);
            }
        }
        sending = false; document.getElementById('send').disabled = false;
    }

    function handleFrame(frame) {
        let type = '', data = '';
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (!type) return;
        let cls = '';
        let detail = data;
        try {
            const obj = JSON.parse(data).data || {};
            if (type === 'TOOL_CALL') { cls = 'tool'; detail = '🔧 ' + (obj.tool||'') + ' | 参数: ' + (obj.args||'') + ' | 返回: ' + (obj.result||''); }
            else if (type === 'LLM_TOKENS') {
                cls = 'tokens';
                detail = '💰 ' + (obj.totalTokens||0) + ' tokens (' + (obj.model||'') + ')';
                totalCost += Number(obj.costUsd||0);
                document.getElementById('costVal').textContent = '$' + totalCost.toFixed(4);
            }
        } catch (e) {}
        const div = document.createElement('div');
        div.className = 'ev ' + cls;
        div.innerHTML = '<b>' + type + '</b><span class="t">' + new Date().toLocaleTimeString() + '</span><br>' + detail;
        document.getElementById('events').appendChild(div);
        document.getElementById('events').scrollTop = 99999;
    }
</script>
</body>
</html>
```

启动应用，打开 `http://localhost:8080/index.html`，输入一个会触发工具的 prompt（提到字数，LLM 更可能调 `countWords`）：

```
写一篇关于 AI 的 300 字短文
```

事件流里会多出：
- `TOOL_CALL`（绿色，带 tool=countWords、args、result——工具的参数和返回值都看得见！）
- `LLM_TOKENS`（橙色，每次 LLM 调用后，带 promptTokens/completionTokens/costUsd）

**对照第 1 章的"只看到步骤"**：现在工具的黑盒打开了——你能看到 LLM 调了什么工具、传了什么参数、工具返回了什么。这就是 Observation 订阅的价值。

**观察成本**：页面右上角实时累加本次写作的总成本（把所有 `LLM_TOKENS` 的 `costUsd` 加起来，通常零点几美分）。

#### 一个真实坑：TOOL_CALL 事件发了两遍

如果你照着某些老教程，**额外写了一个 `ObservationConfig` 手动 `registry.observationConfig().observationHandler(toolHandler)`**，页面会看到 `TOOL_CALL` 事件**每个都出现两次**（参数、返回值完全一样）。

**根因**：[Spring Boot 官方文档](https://docs.spring.io/spring-boot/reference/actuator/observability.html)明确——`ObservationHandler` Bean 会被自动注册到 Registry。你再手动注册一次，同一实例在 Registry 里出现两次，每次工具调用的 `onStop` 被调两遍。

**修复**：删掉 `ObservationConfig`，只保留 `ToolObservationHandler` 上的 `@Component`。重启，事件只发一遍。

> **企业级教训**：`observationHandler(...)` 是"追加"不是"替换"，且 Spring 已自动追加过。手动再追加 = 重复。这是 Observation 体系最常踩的坑——本节代码从一开始就只标 `@Component`，但你看到老教程写手动注册时要警觉。

#### 关于会话隔离的实跑验证

`TOOL_CALL` 事件的 `sessionId` 应该是正确的会话 ID（不是 `unknown`）。因为 `ContextPropagationConfig` 开启了自动传播——run 里 `contextWrite(sessionId)` 写进 Reactor Context，工具执行线程的 ThreadLocal 被自动灌入，`onStop` 里 `AppContextKeys.SESSION_ID.getValue()` 读到。

> **怎么验证自动传播真的生效**：临时把 `ContextPropagationConfig` 的 `@PostConstruct` 注释掉（不开启传播），重启后 `TOOL_CALL` 的 sessionId 会变成 `unknown`——因为流式下工具切了线程，读不到。放开注释、重启，又恢复正常。这个对比能让你直观看到"自动传播"在干什么。
>
> **如果怎么都是 unknown**：检查 `context-propagation` 库是否在依赖里（webflux/actuator 应已传递带入，没有就显式加 `io.micrometer:context-propagation`）；确认 `Hooks.enableAutomaticContextPropagation()` 真的被调用了（启动日志或断点）。

### 3.4 checkpoint

```
src/main/java/com/example/aobs/
├── config/
│   └── ChatClientConfig.java          （新增）
├── obs/
│   ├── PropagatedContextValue.java    （新增：Reactor Context key + ThreadLocalAccessor）
│   ├── AppContextKeys.java            （新增：上下文注册中心）
│   ├── ContextPropagationConfig.java  （新增：开启自动传播）
│   ├── ToolObservationHandler.java    （新增：订阅工具 Observation）
│   └── CostCalculator.java            （新增）
├── workflow/
│   └── ChainingService.java           （改：streamStep 取 Usage 发 LLM_TOKENS）
└── writing/
    ├── WritingTools.java              （新增）
    └── ArticleService.java            （改：构造器加 CostCalculator）
```
pom 加了 `spring-boot-starter-actuator`。

```bash
git add -A && git commit -m "第3章：Observation订阅工具调用 + 会话隔离 + token成本"
```

### 3.5 复盘

**做了**：工具调用过程可见（订阅原生 Observation）、token/成本统计（旁路 Usage）、工具事件正确标会话（上下文传播）。

**学到的三个模式**：
- **订阅式采集**：不自己拦工具，订阅框架原生发的 Observation。能用框架能力就不自己包底层。
- **旁路统计**：拿 response 的元数据发事件，业务主流程不变。token、延迟、调用量都这么采集。
- **上下文到观测回调**：观测回调（onStop）够不着请求参数。流式模型（本项目）下工具执行切线程，靠 **Reactor Context + Micrometer Context Propagation 自动传播**把 sessionId 跨线程带过去；同步模型才用最简单的同线程 ThreadLocal set/get。

**踩的坑（企业级必经）**：
- Observation 重复注册 → 只用 `@Component` 自动注册。

**还差**：
- 单实例扛不住高并发 → **第 4 章**
- 成本只能看单次，没有「这个租户花了多少」→ **第 5 章**

> **注意**：第 3 章后项目还是**单实例**。如果你的产品日活不过千、不要多租户，**停在第 3 章就够了**——已经是一个可靠、可见、能算钱的单实例 Agent。第 4 章开始是「量大了/要对外卖」才需要的。

---

## 第 4 章：上量了——状态外置与分片

### 4.0 场景：上多实例，全裂了

日活涨到上万，单实例扛不住，团队决定起 3 个实例做负载均衡。上线第一天就出三个问题：

1. **事件跨实例不通**——用户请求落到实例 A，但 SSE 连接在实例 B。B 订阅的是自己的 `EventBus`，收不到 A 发的事件。前端永远转圈。
2. **序号乱了**——第 2 章的序号是进程内 `ConcurrentHashMap`。同一个会话两步可能落在不同实例，序号从 1、2 变成 1（A）、1（B），重排逻辑废了。
3. **单总线吞吐到顶**——单实例并发会话涨了，一个 `Sinks.Many` 灌 5w/s，缓冲爆；某个慢 SSE 消费者拖累所有会话。

根因：**前面所有状态（事件流、序号、成本）都在进程内**。单实例无感，多实例就裂。

### 4.1 思路

| 问题 | 方案 |
|------|------|
| 事件跨实例不通 | Redis Stream 广播：每个实例 emit 时 `XADD` 到 Stream，所有实例 `XREADGROUP` 拉取 |
| 序号进程内 | 会话状态外置 Redis（`SessionStateStore`），序号用 Redis 原子自增 |
| 单总线吞吐 | 分片：按 sessionId hash 路由到 N 个 sink，慢消费者只影响 1/N |

> **演进逻辑**：第 1-3 章用进程内状态是对的（简单快），因为那时单实例。第 4 章上多实例，进程内状态从「优点」变「致命缺陷」。**这不是设计错误，是约束变了**——同一套代码在不同体量下评价完全不同。

### 4.2 动手

#### 4.2.1 会话状态外置：SessionStateStore

所有 per-session 可变状态进 Redis。这是第 4 章改动最大的一项。

`src/main/java/com/example/aobs/obs/SessionStateStore.java`（新增）：

```java
package com.example.aobs.obs;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话状态外置存储。Redis Hash 存 per-session 状态，TTL 30 分钟。
 * 进程内无状态 → 多实例任意节点都能正确读写。
 */
@Component
public class SessionStateStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private final StringRedisTemplate redis;

    public SessionStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String sid) {
        return "aobs:session:" + sid + ":state";
    }

    /** 会话内序号：Redis 原子自增，多实例也唯一。 */
    public long nextSequence(String sid) {
        Long n = redis.opsForHash().increment(key(sid), "seq", 1);
        redis.expire(key(sid), TTL);
        return n == null ? 0 : n;
    }
}
```

> **API 核实**：`opsForHash().increment(key, hashKey, delta)` 是 spring-data-redis 真实方法，原子操作，多实例并发也唯一。

改 `EventBus` 用外置序号：

```java
// EventBus 构造器注入 SessionStateStore，emit 里改成：
public class EventBus {
    // ... 删掉原来的 sequences ConcurrentHashMap
    private final SessionStateStore stateStore;

    public EventBus(ObjectProvider<CriticalEventStore> storeProvider,
                    SessionStateStore stateStore) {
        this.storeProvider = storeProvider;
        this.stateStore = stateStore;
    }

    public void emit(AgentEvent event) {
        // 序号从 Redis 拿（多实例唯一）
        long seq = stateStore.nextSequence(event.sessionId());
        AgentEvent sequenced = withSequence(event, seq);
        // ... 落库 + 推总线（不变）
    }
}
```

#### 4.2.2 事件跨实例广播：RedisStreamBridge

每个实例 emit 时除了推本地 sink，还 publish 到 Redis Stream；同时订阅 Stream 把别的实例的事件回灌本地。

`src/main/java/com/example/aobs/obs/RedisStreamBridge.java`（新增）：

```java
package com.example.aobs.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Redis Stream 跨实例广播。
 * publish：本实例发的事件写 Stream；subscribe：拉别实例的事件回灌本地总线。
 */
@Component
public class RedisStreamBridge {

    private static final String STREAM_KEY = "aobs:events:stream";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private volatile boolean running = true;

    public RedisStreamBridge(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 本实例发的事件 → 写 Stream。 */
    public void publish(AgentEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            redis.opsForStream().add(STREAM_KEY,
                    Map.of("instance", instanceId, "payload", json));
        } catch (Exception ignore) {
            // 广播失败不能影响主流程
        }
    }

    /** 订阅别实例的事件，回灌本地。 */
    public void subscribeRemote(java.util.function.Consumer<AgentEvent> consumer) {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    var records = redis.opsForStream().read(
                            StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                    if (records == null) continue;
                    for (var rec : records) {
                        Object src = rec.getValue().get("instance");
                        if (instanceId.equals(src)) continue;   // 自己发的跳过，防回环
                        Object payload = rec.getValue().get("payload");
                        if (payload instanceof String s) {
                            consumer.accept(mapper.readValue(s, AgentEvent.class));
                        }
                    }
                } catch (Exception ignore) {
                    sleepQuiet(500);
                }
            }
        }, "redis-stream-bridge");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    public void stop() { running = false; }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> **API 核实**：`opsForStream().add(key, Map)`、`read(StreamReadOptions, StreamOffset)` 是 spring-data-redis 真实方法。
>
> **防回环**：publish 时带 `instance`（本实例 ID），subscribe 时跳过自己发的——否则实例 A 发的事件经 Stream 回到 A 又发一次，无限循环。

改 `EventBus` 接入广播：

```java
// EventBus 加 RedisStreamBridge，emit 里 publish：
public class EventBus {
    private final RedisStreamBridge streamBridge;
    // ...
    public void emit(AgentEvent event) {
        AgentEvent sequenced = withSequence(event, stateStore.nextSequence(event.sessionId()));
        // ... 落库 + 推本地 sink（不变）
        streamBridge.publish(sequenced);   // ← 跨实例广播
    }

    @PostConstruct
    public void init() {
        streamBridge.subscribeRemote(this::acceptLocal);   // 订阅别实例事件
    }

    private void acceptLocal(AgentEvent event) {
        sink.tryEmitNext(event);   // 别实例的事件回灌本地 sink
    }
}
```

> **现在 SSE 怎么工作**：用户 SSE 连实例 B，请求落实例 A。A 发事件 → publish 到 Stream → B 的 `subscribeRemote` 拉到 → 回灌 B 的 sink → B 的 SSE 推给用户。跨实例事件通了。

#### 4.2.3 分片总线：解决吞吐与爆炸半径

单 sink → N 个 sink，按 sessionId hash 路由。

`src/main/java/com/example/aobs/obs/ShardedEventBus.java`（替换 EventBus）：

```java
package com.example.aobs.obs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片总线：按 sessionId hash 路由到 N 个 sink。
 * - 同一会话同分片 → 会话内有序
 * - 慢消费者只影响 1/N 会话 → 爆炸半径小
 * 替换第 1-3 章的单 sink EventBus。
 */
@Component
public class ShardedEventBus implements EventBusSPI {

    private static final int SHARDS = 16;
    private final Sinks.Many<AgentEvent>[] sinks;
    private final SessionStateStore stateStore;
    private final RedisStreamBridge streamBridge;
    private final ObjectProvider<CriticalEventStore> storeProvider;

    @SuppressWarnings("unchecked")
    public ShardedEventBus(SessionStateStore stateStore,
                           RedisStreamBridge streamBridge,
                           ObjectProvider<CriticalEventStore> storeProvider) {
        this.stateStore = stateStore;
        this.streamBridge = streamBridge;
        this.storeProvider = storeProvider;
        this.sinks = new Sinks.Many[SHARDS];
        for (int i = 0; i < SHARDS; i++) {
            sinks[i] = Sinks.many().multicast().onBackpressureBuffer(256, false);
        }
    }

    /** 按 sessionId 选分片。 */
    private Sinks.Many<AgentEvent> route(String sessionId) {
        return sinks[Math.floorMod(sessionId.hashCode(), SHARDS)];
    }

    @Override
    public void emit(AgentEvent event) {
        long seq = stateStore.nextSequence(event.sessionId());
        AgentEvent sequenced = withSequence(event, seq);

        CriticalEventStore store = storeProvider.getIfAvailable();
        if (store != null && sequenced.criticality() == AgentEvent.Criticality.CRITICAL) {
            store.save(sequenced);
        }
        route(sequenced.sessionId()).tryEmitNext(sequenced);   // 路由到分片
        streamBridge.publish(sequenced);
    }

    /** 全量订阅（审计消费者用）：merge 所有分片。 */
    @Override
    public Flux<AgentEvent> flux() {
        List<Flux<AgentEvent>> all = new ArrayList<>(SHARDS);
        for (Sinks.Many<AgentEvent> s : sinks) all.add(s.asFlux());
        return Flux.merge(all);
    }

    /** 定向订阅（SSE 用）：只订阅该会话所在分片，省 15/16 过滤。 */
    @Override
    public Flux<AgentEvent> fluxFor(String sessionId) {
        return route(sessionId).asFlux().filter(e -> sessionId.equals(e.sessionId()));
    }

    private static AgentEvent withSequence(AgentEvent e, long seq) {
        return new AgentEvent(e.type(), e.sessionId(), e.timestamp(), e.data(),
                e.criticality(), seq);
    }
}
```

抽个接口让 Controller 依赖抽象不依赖具体：

```java
// src/main/java/com/example/aobs/obs/EventBusSPI.java
package com.example.aobs.obs;

import reactor.core.publisher.Flux;

/** 总线接口，Controller 依赖它，不依赖具体实现（单 sink 或分片）。 */
public interface EventBusSPI {
    void emit(AgentEvent event);
    Flux<AgentEvent> flux();
    Flux<AgentEvent> fluxFor(String sessionId);
}
```

> **关键不变量**：同一 sessionId 永远落同一分片（hash 路由），会话内有序不受分片影响。

改 `SseController` 用定向订阅（`ChainingService`、`ToolObservationHandler` 里的 `EventBus` 也改成 `EventBusSPI`）：

```java
// SseController 里原来 eventBus.flux().filter(sessionId) 改成：
Flux<AgentEvent> live = eventBus.fluxFor(sessionId);   // 定向，省 N-1 倍过滤
```

#### 4.2.4 序号重排（终于用上了）

第 2 章加了序号但没用。现在多实例 + 多线程，事件真会乱序了。加个重排器：

`src/main/java/com/example/aobs/obs/EventSequencer.java`（新增）：

```java
package com.example.aobs.obs;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * 按会话内 sequence 重排。用「乱序容忍窗口」：
 * 攒一小批，按 seq 排序输出；超窗口强制输出当前最小（防无限等待）。
 */
public class EventSequencer {

    private final Duration window;
    private final Map<String, PriorityQueue<AgentEvent>> buffers = new HashMap<>();
    private final Map<String, Long> lastEmitted = new HashMap<>();

    public EventSequencer(Duration window) {
        this.window = window;
    }

    public Flux<AgentEvent> reorder(Flux<AgentEvent> in) {
        return in.bufferTimeout(64, window).flatMap(batch ->
                Flux.fromIterable(reorderBatch(batch)));
    }

    private List<AgentEvent> reorderBatch(List<AgentEvent> batch) {
        if (batch.isEmpty()) return batch;
        String sid = batch.get(0).sessionId();
        PriorityQueue<AgentEvent> pq = buffers.computeIfAbsent(sid,
                k -> new PriorityQueue<>(Comparator.comparingLong(AgentEvent::sequence)));
        pq.addAll(batch);
        List<AgentEvent> out = new ArrayList<>();
        long expected = lastEmitted.getOrDefault(sid, 0L) + 1;
        while (!pq.isEmpty() && pq.peek().sequence() <= expected) {
            AgentEvent e = pq.poll();
            out.add(e);
            lastEmitted.put(sid, e.sequence());
            expected = e.sequence() + 1;
        }
        return out;
    }
}
```

`SseController` 流里加 `.transform(sequencer::reorder)`：

```java
Flux<ServerSentEvent<String>> events = Flux.concat(replay, live)
        .transform(new EventSequencer(Duration.ofMillis(100))::reorder)   // ← 重排
        .takeUntil(e -> e.type() == EventContent.SESSION_COMPLETED
                || e.type() == EventContent.SESSION_FAILED)
        .map(this::toSse);
```

### 4.3 验证（双实例 + 页面演示跨实例）

**场景 1：跨实例事件通**——起两个实例（不同端口）：
```bash
SERVER_PORT=8081 mvn spring-boot:run &
SERVER_PORT=8082 mvn spring-boot:run &
```

**页面演示**（这是 curl 难演示的，必须页面）：浏览器开两个 tab，分别打开 `http://localhost:8081/index.html` 和 `http://localhost:8082/index.html`。在 8081 的页面点「生成」（触发任务），在 **8082 的页面**也能看到同一个会话的事件流——因为事件经 Redis Stream 跨实例广播了。这就是多实例可观测的关键：**不管请求落在哪个实例，任何实例的页面都能订阅到事件**。

**场景 2：序号全局唯一**——查 Redis：
```bash
redis-cli HGET aobs:session:s4:state seq
```
跨实例多次请求后，seq 持续递增、不重复（序号生成走 Redis，不靠单机内存）。

### 4.4 checkpoint

```
src/main/java/com/example/aobs/obs/
├── SessionStateStore.java     （新增）
├── RedisStreamBridge.java     （新增）
├── ShardedEventBus.java       （新增，替换单 sink）
├── EventBusSPI.java           （新增接口）
└── EventSequencer.java        （新增）
```

```bash
git add -A && git commit -m "第4章：规模化——状态外置、分片总线、跨实例广播、序号重排"
```

### 4.5 复盘

**解决了上量的三个问题**：✅ 跨实例事件通（Redis Stream）；✅ 序号全局唯一（状态外置）；✅ 吞吐与爆炸半径（分片）。

**架构变化**：单实例 → 多实例；进程内状态 → Redis 外置；单 sink → 分片。

**还差**：现在所有用户共用一套系统、成本没法按客户分摊 → **第 5 章多租户**。

> **演进纪律提醒**：如果你的产品不上多实例、日活不过万，**停在第 3 章就行**。第 4 章这些复杂度是「上量」才需要的，不要为了「看起来高级」而过早引入。

---

## 第 5 章：对多客户收费——多租户与成本治理

### 5.0 场景：产品变 SaaS

产品要从「内部工具」变成「对外 SaaS」：多个企业客户（租户）来用，按用量收费。新需求：

1. **租户隔离**：租户 A 的会话/事件/成本，租户 B 绝不能看到。
2. **成本归因**：每次 token 消耗要算到「哪个租户、哪个用户、哪个 Agent 版本」头上，用于分摊计费。
3. **多级配额**：第 3 章只有单会话预算。某租户发起一万个会话、每个都不超单会话预算，但租户总成本爆了——要 per-tenant 日预算 + per-user 限流。

### 5.1 思路

| 需求 | 方案 |
|------|------|
| 租户隔离 | 事件加 `tenantId` 字段，SSE 双向校验（请求 tenantId 必须匹配事件 tenantId） |
| 成本归因 | 事件加 `userId`/`agentVersion`/`promptVersion`，消费者按维度聚合 |
| 多级配额 | 三层（session/tenant/user），都基于 Redis（复用第 4 章状态外置） |

> **演进逻辑**：第 1-4 章 `AgentEvent` 字段是逐步加的——第 1 章 4 字段、第 2 章加 criticality/sequence、第 5 章加归因维度。字段随业务复杂度长，不是一开始全设计好。

### 5.2 动手

#### 5.2.1 AgentEvent 加归因字段

`src/main/java/com/example/aobs/obs/AgentEvent.java`（再加字段）：

```java
public record AgentEvent(
        EventContent type,
        String sessionId,
        String tenantId,        // ← 第 5 章新增：租户（隔离 + 成本归因）
        String userId,          // ← 第 5 章新增：用户（成本归因 + 限流）
        String agentVersion,    // ← 第 5 章新增：Agent 版本（回滚定位 + 成本归因）
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality,
        long sequence
) {
    public enum Criticality { CRITICAL, NORMAL, DISCARDABLE }

    public static Criticality defaultCriticality(EventContent type) {
        return switch (type) {
            case SESSION_STARTED, SESSION_COMPLETED, SESSION_FAILED -> Criticality.CRITICAL;
            case CONTENT_DELTA -> Criticality.DISCARDABLE;
            default -> Criticality.NORMAL;
        };
    }

    public static Builder builder() { return new Builder(); }

    /** 第 5 章快捷构造：带租户/用户。 */
    public static AgentEvent of(EventContent type, String sessionId, String tenantId, String userId,
                                Map<String, Object> data) {
        return builder().type(type).sessionId(sessionId)
                .tenantId(tenantId).userId(userId).data(data).build();
    }

    /** 第 1-4 章的旧 of（无租户）保留兼容。 */
    public static AgentEvent of(EventContent type, String sessionId, Map<String, Object> data) {
        return builder().type(type).sessionId(sessionId).data(data).build();
    }

    public String typeName() { return type.wireName(); }

    public static final class Builder {
        private EventContent type;
        private String sessionId;
        private String tenantId;
        private String userId;
        private String agentVersion = "v1";
        private Instant timestamp;
        private Map<String, Object> data = Map.of();
        private Criticality criticality;

        public Builder type(EventContent t) { this.type = t; return this; }
        public Builder sessionId(String s) { this.sessionId = s; return this; }
        public Builder tenantId(String t) { this.tenantId = t; return this; }
        public Builder userId(String u) { this.userId = u; return this; }
        public Builder agentVersion(String v) { this.agentVersion = v; return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder data(Map<String, Object> d) { this.data = d; return this; }
        public Builder criticality(Criticality c) { this.criticality = c; return this; }

        public AgentEvent build() {
            if (type == null) throw new IllegalStateException("type 必填");
            Criticality c = criticality != null ? criticality : defaultCriticality(type);
            return new AgentEvent(type, sessionId, tenantId, userId, agentVersion,
                    timestamp != null ? timestamp : Instant.now(), data, c, 0L);
        }
    }
}
```

> **第 5 章相对第 2 章的变化**：record 加 `tenantId`/`userId`/`agentVersion` 三个归因字段；builder 加对应方法。**因为用 builder，老的 `of(type, sessionId, data)` 调用点不用改**（第 5 章新增了带租户的 of 重载，老 of 保留）。只有需要归因的 emit 点（SESSION_STARTED/LLM_TOKENS 等）改成传 tenantId/userId——`ChainingService.run` 签名加 tenantId/userId，Controller 从请求头取，按 IDE 报错逐个修。

#### 5.2.2 Controller 从请求头取租户/用户

`SseController`、`ArticleController` 加请求头：

```java
@GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
        @RequestParam String prompt,
        @RequestHeader String sessionId,
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    // ... 把 tenantId/userId 传给 articleService.run
}
```

#### 5.2.3 SSE 双向校验（租户隔离）

`SseController` 的实时流加租户过滤：

```java
Flux<AgentEvent> live = eventBus.fluxFor(sessionId)
        .filter(e -> {
            // 纵深防御：tenantId 必须双向匹配，null 一律拒绝
            if (tenantId == null || e.tenantId() == null) return false;
            return tenantId.equals(e.tenantId());
        });
```

> **为什么「双向匹配 + null 拒绝」？** 单靠「事件带 tenantId 过滤」不够——如果采集点漏传 tenantId（值为 null），过滤会放行，租户 B 可能看到 null 的事件。null 一律拒绝是兜底。

#### 5.2.4 多级配额：QuotaService

三层配额，都基于 Redis（复用第 4 章 `SessionStateStore` 的思路）。

`src/main/java/com/example/aobs/obs/QuotaService.java`（新增）：

```java
package com.example.aobs.obs;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 三层配额：session / tenant / user。都基于 Redis。
 * 配套第 3 章的 CostCalculator——每次烧 token 后调 check，超限拦截。
 */
@Component
public class QuotaService {

    private final StringRedisTemplate redis;

    public QuotaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** per-tenant 日成本累加 + 上限校验。返回 false = 该租户今日预算用尽。 */
    public boolean checkTenantDailyBudget(String tenantId, double inc, double budgetUsd) {
        String key = "aobs:quota:tenant:" + tenantId + ":cost:" + LocalDate.now();
        Double after = redis.opsForValue().increment(key, inc);
        if (after != null && after == inc) {
            redis.expire(key, Duration.ofDays(1));   // 首次写设 TTL
        }
        return after == null || after <= budgetUsd;
    }

    /** per-user 速率限制（每分钟 max 次）。返回 false = 触发限流。 */
    public boolean checkUserRpm(String userId, int maxPerMinute) {
        String key = "aobs:quota:user:" + userId + ":rpm";
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;
        redis.opsForZSet().removeRangeByScore(key, 0, windowStart);  // 清窗口外
        Long count = redis.opsForZSet().zCard(key);
        if (count != null && count >= maxPerMinute) return false;
        redis.opsForZSet().add(key, String.valueOf(now), now);
        redis.expire(key, Duration.ofMinutes(1));
        return true;
    }
}
```

> **API 核实**：`opsForValue().increment(key, double)`、`opsForZSet().removeRangeByScore/zCard/add` 全部真实存在。
>
> **三层配额分工**：per-session 防单次失控（第 3 章的 budgetPerSession）；per-tenant 防租户烧爆总账；per-user 防滥用刷接口。

#### 5.2.5 在 ChainingService 里接入配额

`call` 方法里，每次烧 token 后校验租户预算：

```java
private void emitTokens(String sessionId, String tenantId, String userId,
                        ChatResponse response) {
    // ... 原有 token 统计
    double cost = costCalculator.calculate(prompt, completion, model);

    // 租户日预算校验
    if (!quotaService.checkTenantDailyBudget(tenantId, cost, tenantDailyBudget)) {
        eventBus.emit(AgentEvent.of(EventContent.QUOTA_EXCEEDED, sessionId, tenantId, userId,
                Map.of("reason", "tenant-daily-budget", "cost", cost)));
        throw new RuntimeException("租户日预算超限");
    }
}
```

### 5.3 验证（页面租户切换 + curl 辅助）

**场景 1：租户隔离（页面演示）**——第 5 章页面加个租户下拉框（`tenantA` / `tenantB`），切换后请求带对应 `X-Tenant-Id`。开两个浏览器窗口分别选不同租户，A 的页面只看 A 的事件、B 的只看 B 的——多租户隔离可视化。

**场景 2：配额拦截**——给 tenantA 设日预算 $0.01，页面跑长任务，事件流里出现 `QUOTA_EXCEEDED`（前端据此提示"额度用尽"）。

**场景 3：成本归因**——查 Redis 看租户日成本累加：
```bash
redis-cli GET aobs:quota:tenant:tenantA:cost:2026-07-22
```

> **页面演进**：到第 5 章，调试页面已经有事件流（第1章）+ 重连（第2章）+ 工具/token 面板（第3章）+ 租户切换（第5章）。这就是一个真实的可观测产品前台了。

### 5.4 checkpoint

```
src/main/java/com/example/aobs/obs/
├── AgentEvent.java       （改：加 tenantId/userId/agentVersion）
├── QuotaService.java     （新增）
├── SseController.java    （改：租户过滤）
└── ...（ChainingService/Controller 配套加 tenantId/userId）
```

```bash
git add -A && git commit -m "第5章：多租户隔离 + 成本归因 + 多级配额"
```

### 5.5 复盘

**做了**：租户隔离（双向校验）、成本归因（userId/tenantId/agentVersion）、多级配额（session/tenant/user）。

**架构变化**：单租户 → 多租户；单级预算 → 三级配额。

**关于事件 schema 版本化**：`agentVersion` 字段除了成本归因，还服务"事件结构升级时的向后兼容"。事件 schema 会随业务演进（加字段、改结构），企业级要保证：
- **老前端收到新事件**：新字段加在 `data` Map 里（前端忽略不认识的字段）或 record 末尾（反序列化兼容）——**只加字段不删字段、不改字段类型**。
- **新前端收到老事件**：新代码对可能缺失的字段给默认值（`event.tenantId() == null ? "default" : ...`）。
- **不兼容变更另起 type**：如果要改某事件的语义（如 STEP_END 的 output 含义变了），不要改原事件，而是**新增事件类型**（如 `STEP_END_V2`），让新老消费者各取所需。

> **schema 演进纪律**（向后兼容三原则）：① 只加字段不删字段；② 字段类型不改；③ 不兼容变更另起 type。这是所有消息系统（Kafka/事件总线）的通用纪律，第 1 章用 `Map<String,Object> data` 装附加信息就是为了这条——Map 天然容忍加字段。

**Prompt 版本管理与 AB 测试（AI 产品核心迭代手段）**：

`promptVersion` 字段除了成本归因，更重要的用途是 **prompt 灰度对比**。AI 产品最主要的迭代是**改 prompt（不是改代码）**——一周可能改几次。每次改要能回答"新版比旧版好吗？"，靠的就是 promptVersion：

```
版本 v1: "你是写作助手。根据大纲写一篇草稿。"
版本 v2: "你是写作助手。根据大纲写一篇 800 字草稿，分段清晰。"
                                                    ↓ 灰度各跑 50%
              按 promptVersion 聚合质量指标对比：
              - 用户采纳率（v1: 62% → v2: 71% ✓）
              - 重试率（v1: 18% → v2: 9% ✓）
              - 平均成本（v1: $0.012 → v2: $0.011）
              → v2 全面更好，全量切 v2
```

实现：prompt 从代码里抽到配置（或 prompt 仓库），按版本号取；`AgentEvent` 带 `promptVersion`；消费者按版本聚合质量指标。**这是 AI 产品和普通后端最大的区别——prompt 是"数据"，要版本化管理 + 灰度，不是写死在代码里**。

> **为什么必须版本化**：不版本化，你永远不知道"用户采纳率下降"是因为改了 prompt、换了模型、还是流量变了。版本化让 prompt 变更**可归因、可回滚**——出问题切回上一版，几分钟恢复。

**还差**：系统成为生产关键链路，要求「出事能救」→ **第 6 章灾备**。

---

## 第 6 章：生产关键系统——灾备与 SRE

### 6.0 场景：成为关键链路

产品火了，写作助手成为客户业务的关键工具，宕机有损失。而且线上出了两个真实事故：

**事故 A：长文本生成超时**。用户写长文，生成到一半后端报 `com.openai.errors.OpenAIIoException: Stream failed`，根因链：`InterruptedIOException: timeout` ← `StreamResetException: stream was reset: CANCEL`。LLM 生成有自然停顿（尤其调工具前后、长段落），底层 OkHttp 的读超时把正常停顿误判成"卡死"，主动取消了 HTTP/2 流。

**事故 B：错误未捕获刷屏**。事故 A 的错误沿 Reactor 流往上传，到 `/chat` 的 `subscribe()` 没有错误回调，变成 `ErrorCallbackNotImplemented` 一堆栈刷日志，且前端没收到 `SESSION_FAILED`、页面一直转圈。

运维提要求：
1. **依赖挂了不能全瘫**——Redis、DeepSeek 挂了怎么办？
2. **长任务不能被超时误杀**——配置合理的底层超时。
3. **错误要有归宿**——subscribe 必须接住错误，转成 SESSION_FAILED 事件。
4. **出事要知道、能恢复**——健康检查、监控告警、降级预案。

### 6.1 思路

| 需求 | 方案 |
|------|------|
| 依赖降级 | 所有外部调用经「降级门面」，失败走 fallback + 打点 + 联动健康检查 |
| 健康检查 | Actuator `HealthIndicator`，Redis/总线状态进 `/actuator/health` |
| 长任务超时 | 配置底层 RestClient/WebClient 的读超时（够长，覆盖 LLM 生成停顿） |
| 错误归宿 | `subscribe(onNext, onError)` 接住错误，转 SESSION_FAILED 事件 |
| 可测性 | 页面演示 + 关键场景 curl；自动化测试团队按需补（本文聚焦页面验证） |

> **统一原则**：可观测链路故障**绝不拖垮主链路**。所有观测相关 IO（Redis publish、Langfuse 上报）短超时、异常吞且打点。Agent 推理本身照常。

### 6.2 动手

#### 6.2.1 加 Actuator 依赖

`pom.xml` 加：

```xml
<!-- Actuator：健康检查、监控指标 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

#### 6.2.2 降级门面：外部调用统一走这里

`src/main/java/com/example/aobs/obs/ResilientExternal.java`（新增）：

```java
package com.example.aobs.obs;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 降级门面：所有外部依赖（Redis、Langfuse、DeepSeek）调用经此。
 * 失败 → 打点 + 返回 fallback，绝不抛异常到主链路。
 */
@Component
public class ResilientExternal {

    public <T> T call(String dependency, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Exception ex) {
            // 生产里换成 metrics.degradation(dependency) + log
            System.err.println("[降级] " + dependency + " 不可用: " + ex.getMessage());
            return fallback;
        }
    }
}
```

把 `RedisStreamBridge.publish`、`CriticalEventStore.save` 里的调用用 `ResilientExternal.call` 包一层（失败不再静默吞，而是打点）。这样 Redis 挂了，写作照常，只是跨实例广播失效——降级而非熔断。

#### 6.2.3 健康检查：Redis 与总线状态

`src/main/java/com/example/aobs/obs/ObsHealthIndicator.java`（新增）：

```java
package com.example.aobs.obs;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 可观测系统自身的健康检查：Redis 连通性。
 * 访问 /actuator/health 能看到。
 *
 * 注意：不直接用底层 RedisConnection.ping()（其可用性随驱动版本变化），
 * 而是用 RedisTemplate 现成的 hasKey 跑一次真实命令——连通就 UP，异常就 DOWN。
 * （Spring Boot 的 redis starter 其实已自动注册了 RedisHealthIndicator，
 *  这里手写一份是为了演示「自定义健康检查」怎么写。）
 */
@Component
public class ObsHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public ObsHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try {
            redis.hasKey("aobs:healthcheck");   // hasKey 是 RedisTemplate 真实方法，连通验证
            return Health.up().withDetail("redis", "reachable").build();
        } catch (Exception e) {
            return Health.down().withDetail("redis", e.getMessage()).build();
        }
    }
}
```

> **API 核实**：`HealthIndicator`、`Health.up()/down()` 是 Spring Boot Actuator 标准 API；`StringRedisTemplate.hasKey(K)` 来自 `RedisTemplate`（已核实存在）。访问 `http://localhost:8080/actuator/health` 看到 `{"status":"UP"}` 或 `"DOWN"`。

#### 6.2.4 修复事故 A：配置底层客户端超时

Spring AI 的 OpenAI starter 底层用 RestClient/WebClient，默认读超时对长文本生成太短。参考 [Spring AI 超时配置 issue #354](https://github.com/spring-projects/spring-ai/issues/354) 和 [增加请求超时指南](https://javaaidev.com/docs/spring-ai/guide/increase-request-timeout)：超时要配在底层 HTTP 客户端上。

最稳的做法——自定义 `RestClient.Builder`/`WebClient.Builder` Bean，设足够长的读超时（覆盖 LLM 生成的自然停顿）：

```java
package com.example.aobs.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

/**
 * 给底层 HTTP 客户端设足够长的超时——LLM 生成有自然停顿（尤其长文本、调工具前后），
 * 默认超时会把正常停顿误判成卡死，抛 StreamResetException: CANCEL（事故 A）。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .responseTimeout(java.time.Duration.ofSeconds(180))   // 读超时 180s，覆盖长生成
                .doOnConnected(c -> c.addHandlerLast(new ReadTimeoutHandler(180, TimeUnit.SECONDS)));

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
```

> **为什么 180 秒**：DeepSeek 长文本生成 + 多步 workflow，单次 LLM 调用可能到 60-90 秒（含停顿）。180 秒留足余量。太短=误杀（事故 A）；太长=真卡死时用户等太久。企业级按 P99 生成耗时 × 2-3 倍设。
>
> **本项目走的就是 WebClient**：`ChainingService.streamStep` 用流式 `.stream()`，底层是 WebClient——上面这个 `WebClient.Builder` Bean 正好生效，无需额外配置。

#### 6.2.5 修复事故 B：错误要有归宿（转成 SESSION_FAILED）

事故 B 的根因——LLM 调用抛异常时，错误沿 Reactor 流往上传，如果没人接，变成 `ErrorCallbackNotImplemented` 刷日志，且前端没收到 `SESSION_FAILED`、页面一直转圈。

**本项目的冷流方案已经天然处理了它**：第 1 章的 `ChainingService.run` 在 Flux 链上挂了 `.onErrorResume(err -> 发 SESSION_FAILED)`——任何步骤（含 streamStep 的 LLM 调用）抛异常，都被这里接住、转成 `SESSION_FAILED` 事件推给前端，前端停止转圈。冷流驱动执行的好处之一：错误归宿和执行路径是同一条 Flux 链，不会"漏接"。

> **为什么冷流方案不容易漏接错误**：老式"热流 + `Mono.fromRunnable(run).subscribe()` 触发"方案里，执行和 SSE 推送是**两条独立的链**——subscribe 触发的执行如果抛错，要专门给 subscribe 加 `onError` 回调才不漏（漏了就是事故 B）。冷流方案里执行和推送是**同一条 Flux**（Controller 直接 return run 的 Flux），`.onErrorResume` 一处兜住所有步骤的错误，没有"两条链要对齐"的心智负担。
>
> **subscribe 仍要接错误的场景**：如果你在别处用 `run(...).subscribe()` 消费（不走 SSE，比如内部任务），那个 subscribe **必须**带 `onError`——Reactor 铁律：没有错误回调的订阅，错误变 `ErrorCallbackNotImplemented`。本文 SSE 路径走冷流返回（WebFlux 框架订阅，框架自己接错误），不踩这个坑。
>
> **「LLM 429 重试」留给规模化后**：超时和错误归宿解决了"硬故障"。但 LLM 的 429（限流）是另一种失败——它在"流量上来、频繁触发限流"时才高频出现。**第 6 章聚焦灾备（出事能救），429 重试放第 7 章**（运营事故，痛点驱动）。

#### 6.2.6 监控指标

在 `application.yaml` 暴露 Actuator 端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always   # 显示每个健康检查的细节
```

关键监控指标（生产接 Prometheus/Grafana）：
- `agent_event_emit_failures_total`：事件发射失败数（背压满）
- `sse_active_connections`：活跃 SSE 连接数
- `session_cost_usd`：会话成本
- `tenant_daily_cost_usd`：租户日成本
- `fallback_degradation_total`：降级次数（Redis/Langfuse 不可用）

> **「连接治理」「用户取消」留给运营阶段**：连接刷爆、用户中途取消——这两个痛点在"产品对外、有真实用户"之后才出现。**第 6 章聚焦关键链路的灾备（超时/错误/降级/健康），它们放第 7 章**（运营事故，痛点驱动）。

### 6.3 验证（页面 + curl 演示灾备与事故修复）

**启动 + 健康检查**：
```bash
mvn spring-boot:run
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

**事故 A 复测（长文本不再超时）**——页面输入一个超长 prompt（如"写一篇 3000 字的深度报告"）。修复前会 `Stream failed`；修复后（底层超时 180s）完整生成，事件流正常到 `SESSION_COMPLETED`。

**事故 B 复测（错误有归宿）**——临时把 DeepSeek key 改错（或断网），页面跑一次。前端收到 `SESSION_FAILED` 事件（带 error 信息），页面停止转圈、显示错误——而不是像修复前那样无限转圈 + 后端刷 ErrorCallbackNotImplemented。

**灾备：模拟 Redis 挂**：
```bash
redis-cli shutdown   # 或 docker stop redis
curl http://localhost:8080/actuator/health   # DOWN（但应用没挂）
curl "http://localhost:8080/api/article?prompt=test" -H "sessionId: s6"   # 写作仍工作（降级）
```
**关键验证**：Redis 挂了，写作主流程不挂，只是可观测降级——这就是「绝不拖垮主链路」。

### 6.4 checkpoint

```
src/main/java/com/example/aobs/
├── config/
│   └── HttpClientConfig.java    （新增：底层超时，修事故A）
└── obs/
    ├── ResilientExternal.java   （新增：降级门面）
    └── ObsHealthIndicator.java  （新增：健康检查）
```
SseController 改：subscribe 加错误回调（修事故B）。pom 加了 actuator。

```bash
git add -A && git commit -m "第6章：灾备降级 + 健康检查 + 超时/错误修复 + 监控"
```

### 6.5 复盘

**做了**：降级门面（依赖挂不瘫）、健康检查（Actuator）、修复两个真实事故（长文本超时、错误未捕获）、监控指标。

**两个事故的根因教训**：
- **事故 A（超时）**：流式 LLM 有自然停顿，底层默认超时按"读间隔"算，误杀正常停顿。企业级：超时配在底层客户端，按 P99 耗时 × 2-3 倍设。
- **事故 B（错误未捕获）**：Reactor 的 subscribe 不接错误 = ErrorCallbackNotImplemented + 前端无感知。企业级：每个 subscribe 都有 onError，错误转成用户可见的 FAILED 事件。

**架构变化**：从「能跑」到「出事能救」。可观测系统自身也有了可观测性（监控自己的健康）。

**到这里，一个完整的企业级可观测 Agent 项目就成型了**——从第 0 章的黑盒，演进到具备可见性、可靠性、规模化、多租户治理、灾备的生产系统。

> **但运营才刚开始**：产品上线对外后，会陆续冒出一批"只有真实运营才碰得到"的事故——它们不是架构问题，是流量和用户行为带来的。第 7 章逐个解。

---

## 第 7 章：运营中的事故——只有真实流量才踩得到

### 7.0 场景：产品对外后，事故接踵而来

第 6 章结束，产品部署到生产（过 nginx、对外服务、有真实用户）。接下来几周，运维群里冒出这些反馈——它们有个共同点：**在 IDE 内网开发时永远不会出现，只有真实流量/用户行为才触发**。这一章逐个解决，每个都是一个独立的运营事故。

| 顺序 | 事故 | 触发条件 | 本章小节 |
|------|------|---------|---------|
| ① | 慢生成时连接被断 | 过 nginx，生成慢 >60s | 7.1 |
| ② | LLM 频繁 429 | 流量上来，撞限流 | 7.2 |
| ③ | 用户想中途停止 | 真实用户用，发现方向不对 | 7.3 |
| ④ | 连接被刷爆 | 对外后被恶意刷 | 7.4 |
| ⑤ | 要查昨天的会话 | 运营久了，排查历史 | 7.5 |

> **为什么这些都在第 7 章、不提前**：它们都是"产品对外、有真实流量"后才出现的痛点。提前做就是过度设计（内网开发根本踩不到）。这一章按"运营中真实出现顺序"逐个解——每个都是"出事了→修"。

### 7.1 事故①：慢生成时连接被断 → SSE 心跳

**痛点**：产品过 nginx 对外服务后，用户反馈"写长文时，生成到一半事件流就不动了，但后端日志显示还在正常生成"。

**根因**：nginx 等代理有"空闲超时"——一条连接 60 秒没有任何数据传输就断开。长文生成时，两步之间可能停顿 >60s，代理把连接断了，但后端还在生成、事件还在往总线发（发到一个已经断掉的连接）。

**解法：SSE 心跳**。后端每 15 秒发一个注释帧（`: heartbeat`），让代理看到"连接还活着"，不判空闲。在 `SseController.stream` 的事件流上 merge 一个定时心跳：

```java
// 心跳：每 15s 发注释帧（: 开头的行，EventSource 不当事件派发，只保活）
Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
        .map(i -> ServerSentEvent.<String>builder().comment("heartbeat").build());

return events.mergeWith(heartbeat)
        .takeUntilOther(events.then());   // 事件流结束（takeUntil）后停掉心跳，否则连接不关
```

> **为什么用注释帧**：SSE 协议里 `:` 开头是注释，`EventSource` 收到不触发 `onmessage`，只保活——比发假事件干净。
>
> **`takeUntilOther(events.then())`**：`Flux.interval` 永不完成，merge 后整个流不会自然结束。事件流 `takeUntil` 终止→完成→`then()` 发完成信号→停掉心跳。否则写作完成后 SSE 连接不关。需要 `import java.time.Duration;`。
>
> **内网开发不踩这个**：IDE 直连后端，没代理，连接不会被空闲断——所以第 1-6 章不需要心跳。过了 nginx 才需要。

### 7.2 事故②：LLM 频繁 429 → 重试与降级

**痛点**：流量上来后，用户反馈"经常生成失败"。后端日志：DeepSeek 返回 **429（rate limit）**或 503（过载）。这些是**瞬时错误**——不是你的 bug，是上游 LLM 限流。

**根因**：第 6 章事故 B 把错误转成 `SESSION_FAILED`，但 429 这种瞬时错误**不该直接让用户失败**，而该自动重试。

**解法：Resilience4j 重试 + 降级**。加依赖：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>2.2.0</version>
        </dependency>
```

把 `ChainingService.streamStep` 改成 public 并加重试（429/超时退避重试，耗尽降级）：
```java
    @io.github.resilience4j.retry.annotation.Retry(name = "llmCall", fallbackMethod = "streamStepFallback")
    public String streamStep(String system, String userPrompt, String sessionId, int step, FluxSinkAdapter out) {
        // ... 原 streamStep 逻辑不变（流式调 LLM + 累积 + emit CONTENT_DELTA）
    }

    /** 重试耗尽降级：返回降级文案，并发 SESSION_FAILED。 */
    public String streamStepFallback(String system, String userPrompt, String sessionId, int step,
                                     FluxSinkAdapter out, Throwable t) {
        eventBus.emit(AgentEvent.of(EventContent.SESSION_FAILED, sessionId,
                Map.of("error", "LLM 不可用（重试耗尽）: " + t.getClass().getSimpleName())));
        return "（AI 服务暂时繁忙，请稍后重试）";
    }
```

> **为什么 streamStep 要改 public**：Resilience4j 的 `@Retry` 靠 Spring AOP 代理生效，AOP 只拦截 **public** 方法（且不能自调用——`run` 循环里调 `streamStep` 要确保走的是代理对象，生产里可把 streamStep 拆到独立 Bean 注入回来调，避免自调用失效）。
>
> **流式重试的注意点**：流式 `.stream()` 的失败分两种——① 建连就失败（429/超时），重试整步合理；② 流到一半断（事故 A），重试会从头再流一次（已 emit 的 CONTENT_DELTA 会重复，前端需按 step+offset 去重）。生产里对第 ② 种更精细的处理是"断点续传"，本文先用"整步重试 + 前端容忍重复"的简单方案。

`application.yaml` 配重试：
```yaml
resilience4j:
  retry:
    instances:
      llmCall:
        max-attempts: 3                          # 含首次共 3 次
        wait-duration: 2s
        enable-exponential-backoff: true         # 指数退避：2s/4s/8s
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException   # 5xx
          - java.net.SocketTimeoutException                            # 超时
```

> **调研结论**：[Spring AI 2.0 ChatClient 没有内置通用重试](https://github.com/spring-projects/spring-ai/issues/3858)，生产用 Resilience4j。
>
> **为什么指数退避**：429 是"过载"，立即重试加剧过载。指数退避给 LLM 喘息。**只重试瞬时错误**（429/503/超时），400/401 重试也没用。

### 7.3 事故③：用户想中途停止 → 取消生成

**痛点**：真实用户用着用着反馈"生成到一半发现方向不对，想停，但停不掉，只能干等"。

**根因**：现在生成一旦开始就跑到 `SESSION_COMPLETED`，没有"取消"通道。更关键——**不取消 = LLM 继续烧钱**（用户走了但后端还在生成付费）。

**解法：前端 abort + 后端 doOnCancel**。前端 fetch 时保存 AbortController，点停止调 abort：

```js
const controller = new AbortController();
fetch('/api/obs/article?prompt=...', { signal: controller.signal, headers: {...} });
// 用户点"停止"：
controller.abort();   // 断开 SSE 连接
```

后端 SSE 流被断开时 WebFlux 取消 Flux，用 `doOnCancel` 捕获，发 `SESSION_CANCELLED`、释放资源：
```java
return events.mergeWith(heartbeat)
        .takeUntilOther(events.then())
        .doOnCancel(() -> eventBus.emit(AgentEvent.of(EventContent.SESSION_CANCELLED, sessionId, Map.of())));
```

> ⚠️ **诚实声明（重要）**：取消下游 Flux **不一定**停止上游 LLM 生成。[Spring AI issue #6654](https://github.com/spring-projects/spring-ai/issues/6654) 报告——取消 Reactor 订阅**不会关闭**底层 OpenAI SDK 的 HTTP 流，LLM 可能**继续生成、继续计费**。这是 Spring AI 当前已知缺陷。务实做法：① `doOnCancel` 发事件 + 释放资源（前端体验对）；② 接受"LLM 可能多生成一会"的成本损失；③ 关注 #6654 升级。**不要假装 dispose 就能省钱**。

### 7.4 事故④：连接被刷爆 → SSE 连接数治理

**痛点**：产品对外后，监控发现 `sse_active_connections` 飙到几千——有恶意客户端（或一个用户开 N 个 tab）疯狂建 SSE 连接，每个连接占一个服务端资源（NIO channel + Flux 订阅），服务端资源耗尽。

**根因**：SSE 是长连接，第 1-6 章从没限制过连接数。对外后必须加上限。

**解法：连接计数 + 超限拒绝**：

```java
@Component
public class SseConnectionLimiter {
    private static final int MAX_GLOBAL = 1000;          // 全局上限
    private final AtomicInteger active = new AtomicInteger(0);

    public boolean tryAcquire() {
        return active.getAndUpdate(c -> c < MAX_GLOBAL ? c + 1 : c) < MAX_GLOBAL;
    }
    public void release() { active.decrementAndGet(); }
}
```

`SseController.stream` 里用，`doFinally` 保证无论怎么结束都释放：
```java
    public Flux<ServerSentEvent<String>> stream(...) {
        if (!limiter.tryAcquire()) {
            return Flux.error(new IllegalStateException("连接数已达上限，请稍后重试"));
        }
        return events.mergeWith(heartbeat)
                .takeUntilOther(events.then())
                .doFinally(sig -> limiter.release());
    }
```

> **多实例用 Redis 计数**：单机 `AtomicInteger` 只管本机。多实例（第 4 章）用 `redis.opsForValue().increment/decrement` 全局统计。per-user/per-IP 上限进阶按需加。

### 7.5 事故⑤：要查昨天的会话 → 事件归档

**痛点**：产品运营几个月后，客服收到用户反馈"我上周三那次生成结果不对，能帮我看看当时的输入输出吗"。去 Redis 查——早过了 30 分钟 TTL，什么都没了。

**根因**：第 2 章的 Redis 是**短期兜底**（30 分钟，防丢/回放），不是**长期归档**。"事后查历史"是另一个能力，需要持久存储。

**解法：事件归档到 JDBC（本文用 H2，生产换 ES/ClickHouse）**。Redis 短期 + 归档长期，热冷分层。

加 H2 依赖：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>
```

`application.yaml`（文件存储，IDE 重启不丢）：
```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/aobs-events
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
```

`src/main/resources/schema.sql`：
```sql
CREATE TABLE IF NOT EXISTS event_archive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT, type VARCHAR(32) NOT NULL, tenant_id VARCHAR(64),
    trace_id VARCHAR(64),                       -- 关联分布式追踪（见下方说明）
    ts BIGINT NOT NULL, payload TEXT
);
CREATE INDEX IF NOT EXISTS idx_archive_session ON event_archive(session_id, seq);
CREATE INDEX IF NOT EXISTS idx_archive_tenant_time ON event_archive(tenant_id, ts);
```

归档服务 `EventArchiveService`（归档 + 查询）：
```java
@Service
public class EventArchiveService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public EventArchiveService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 归档。失败只打日志，绝不拖累事件流主路径。 */
    public void archive(AgentEvent event) {
        try {
            // traceId 从 MDC 取：Spring Boot 的 Observation 已把 traceId 放进 SLF4J MDC。
            // 取不到（没在 Observation 作用域内）就存 null，不阻塞归档。
            String traceId = org.slf4j.MDC.get("traceId");
            jdbc.update("INSERT INTO event_archive (session_id, seq, type, tenant_id, trace_id, ts, payload) VALUES (?,?,?,?,?,?,?)",
                    event.sessionId(), event.sequence(), event.typeName(), event.tenantId(), traceId,
                    event.timestamp().toEpochMilli(), mapper.writeValueAsString(event));
        } catch (Exception e) {
            System.err.println("[Archive] failed: " + e.getMessage());
        }
    }

    /** 按会话查历史（事后排查）。 */
    public List<Map<String, Object>> findBySession(String sessionId) {
        return jdbc.queryForList(
                "SELECT type, trace_id, ts, payload FROM event_archive WHERE session_id = ? ORDER BY seq", sessionId);
    }
}
```

> **trace_id 列的价值**：归档存了 traceId，事后排查时——用户说"上周那次慢"，你按 sessionId 查到归档、拿到 traceId，再到追踪系统（Tempo/Zipkin）按 traceId 看完整调用链（哪次 LLM 慢、哪个工具慢）。**sessionId（业务）↔ traceId（技术）双向跳查**，是可观测性真正"可查"的闭环。没接追踪系统时这列可空，接了就有大用。
>
> ⚠️ `MDC.get("traceId")` 能拿到的前提是 Spring Boot 的 tracing 已配置好（actuator + 一个 tracer 导出端点，如 Zipkin/Tempo）。没配 tracer 时 MDC 里没有 traceId，这列存 null——不影响归档主流程。配了 tracer 后自动有值，无需改这行代码。

EventBus.emit 里归档所有事件（和落 Redis 一起）；SseController 加查询接口 `GET /api/obs/history/{sessionId}`。

> **为什么 Redis + 归档两层**：Redis 快但短期（内存贵，30min 兜底）；归档慢但长期（磁盘便宜，事后查）。热冷分层，各取所长。
>
> **归档必须容错**：归档走 JDBC（磁盘 IO），不能阻塞事件流。`archive` 内部 try/catch 吞异常——归档挂了不影响实时事件和写作主流程（和第 6 章"绝不拖垮主链路"一致）。
>
> **生产换 ES/ClickHouse**：H2 是 IDE 可跑的演示。生产要全文检索换 Elasticsearch，要时序统计换 ClickHouse——接口不变（`archive`/`findBySession`），只换实现。
>
> ⚠️ `AgentEvent` 的 `sequence()`/`tenantId()` 是第 2/5 章加的字段。归档代码假设它们已存在。

### 7.6 复盘

**这一章的特点**：每个机制都不是"架构规划"，而是"运营某天出了事，回头加的"。它们的共同点是——**IDE 内网开发永远踩不到，只有真实流量/用户行为才触发**。

**工程教训**：
- **不要提前防御这些**：心跳、连接治理、归档，在第 1-6 章做就是过度设计。等痛点出现再做，才知道真正需要什么。
- **AI 项目的几个固有难点**：429 重试（外部不稳定）、取消生成（issue #6654 的已知缺陷）——这些是 AI 区别于普通后端的地方，要专门处理。
- **热冷分层**：Redis（热/短期）+ 归档（冷/长期）是可观测系统的通用数据分层。

> **到这里，运营阶段常见事故也覆盖了**。再往后的优化（具体 buffer 调参、告警阈值、SLO）是持续运营的事，不是一次性"搭出来"。

---

## 第 8 章：会话持久化与历史回放——让数据活得久、查得到、接得上

> **本章节奏**：和第 1-7 章一样，按"痛点驱动"分**六个阶段**，每个阶段都是一个**能独立编译、能跑的项目 checkpoint**。不要一口气读完——照着每个阶段敲代码、跑通、看到它暴露的问题，再进下一阶段。**每个机制都是被上一阶段的痛点逼出来的**，这样你才学得到"为什么这么设计"，而不是死记一坨最终代码。

### 8.0 场景：客服工单倒逼的"历史能力"

第 7 章结束，产品稳定运营了几个月。客服连开工单，每一个都暴露第 1-7 章没覆盖的能力：

1. **「用户说昨天那次生成到一半断了，能接着看完吗？」**——缺：历史回放。
2. **「运营要在后台看所有会话，按状态筛选」**——缺：会话列表。
3. **「部署重启，进行中的会话全废了」**——缺：断点续传。
4. **「合规审计说归档里有用户身份证号」**——缺：PII 脱敏。

共同点：都是"**产品成熟到要管历史数据**"才出现的。第 1-7 章聚焦"实时跑起来、看得见、出事能救"；第 8 章回答"**数据跑完之后，怎么活得久、查得到、接得上、管得住**"。

> **为什么放第 8 章、分六阶段**：这些能力在第 1-6 章做就是过度设计。它们是"运营几个月、有真实历史要管"才出现的痛点。而且它们之间有**依赖链**——不先持久化就没法回放、不先回放就暴露不出越权、不先续传就暴露不出脱敏问题。所以按依赖顺序分阶段，每阶段踩到下阶段的痛点。

**六个阶段一览**（每阶段一个 checkpoint）：

| 阶段 | 做什么 | 能跑出什么 | 暴露什么 → 下阶段 |
|------|--------|-----------|-----------------|
| **8.1 最小持久化** | 会话落库（状态 + 输入输出） | 查库能看到会话记录 | 想看历史事件流 → 8.2 回放 |
| **8.2 历史回放** | 归档事件重放成 SSE | 点会话能回放事件流 | 回放没鉴权，越权 → 8.3 |
| **8.3 列表 + 鉴权** | 会话列表 + 租户校验 | 左侧栏列表、防越权 | 中断会话没法恢复 → 8.4 |
| **8.4 断点续传** | last_step + runFrom | 中断能续跑 | 续传喂了脱敏输入 → 8.5 |
| **8.5 治理配套** | 幂等/脱敏/加密/TTL/僵尸回收 | 防重/合规/成本可控 | 要管租户本身 → 8.6 |
| **8.6 多租户管理** | 租户 CRUD + 配额 + 鉴权 | 租户可建可停 | — |

---

### 8.1 阶段一：最小持久化——会话能落库

#### 8.1.0 场景

客服说"用户问昨天那次的结果"，你查库——**什么都没有**。第 7 章归档存的是"事件流"（过程），没有"会话概要"（结果）。连"有哪些会话、各自什么状态、输入输出是什么"都不知道。

这一阶段只做**最小的一件事**：会话开始/完成/失败时，往一张概要表写一条记录。不做回放、不做列表、不做续传——先让数据落下来。

#### 8.1.1 思路

加一张 `session_record` 表（会话概要，一个会话一行），和第 7 章的 `event_archive`（事件流，一个会话 N 行）**分开**。原因：列表查询要扫概要（小表），回放要扫事件（大表）——读多写少的概要、写多读少的事件，分开存是企业级数据建模的常规。

`SessionStatus` 先给最小三个状态：`ACTIVE`（进行中）、`COMPLETED`（完成）、`FAILED`（失败）。后面阶段再加 `INTERRUPTED`/`EXPIRED`。

#### 8.1.2 动手

`src/main/java/com/example/aobs/session/SessionStatus.java`（新增）：

```java
package com.example.aobs.session;

/** 会话生命周期状态。本阶段只用到前三个；INTERRUPTED/EXPIRED 后续阶段加。 */
public enum SessionStatus {
    ACTIVE,        // 进行中
    COMPLETED,     // 正常完成
    FAILED,        // 失败
    INTERRUPTED,   // 中断（第 8.4 章加，停机/崩溃）
    EXPIRED        // 过期（第 8.5 章加，TTL 清理）
}
```

`src/main/resources/schema.sql`（在 `event_archive` 之后追加）：

```sql
-- 第 8.1 章：会话概要（和事件归档分开）
CREATE TABLE IF NOT EXISTS session_record (
    session_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,          -- SessionStatus 枚举名
    input_text TEXT,                      -- 输入（本阶段先存原文，8.5 改脱敏）
    output_text TEXT,                     -- 最终输出（COMPLETED 时回填）
    total_steps INT,
    started_at BIGINT NOT NULL,
    ended_at BIGINT,                      -- COMPLETED/FAILED 时回填
    updated_at BIGINT NOT NULL
);
```

`src/main/java/com/example/aobs/session/SessionService.java`（新增，最小 CRUD）：

```java
package com.example.aobs.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 会话概要的落库。本阶段只做 start/complete/fail 三件事。 */
@Service
public class SessionService {

    private final JdbcTemplate jdbc;
    public SessionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 开始会话（SESSION_STARTED 时调）。MERGE 做 upsert，重复调不报错。 */
    public void start(String sessionId, String input, int totalSteps) {
        long now = System.currentTimeMillis();
        jdbc.update("MERGE INTO session_record KEY(session_id) VALUES (?,?,?,?,?,?,NULL,?)",
                sessionId, SessionStatus.ACTIVE.name(), input, null, totalSteps, now, now);
    }

    /** 正常完成（SESSION_COMPLETED 时调），回填输出。 */
    public void complete(String sessionId, String output) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE session_record SET status=?, output_text=?, ended_at=?, updated_at=? WHERE session_id=?",
                SessionStatus.COMPLETED.name(), output, now, now, sessionId);
    }

    /** 失败（SESSION_FAILED 时调）。 */
    public void fail(String sessionId) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE session_record SET status=?, ended_at=?, updated_at=? WHERE session_id=?",
                SessionStatus.FAILED.name(), now, now, sessionId);
    }
}
```

> **API 核实**：H2 的 `MERGE INTO ... KEY(col)` 是 upsert（MySQL 用 `ON DUPLICATE KEY UPDATE`、PostgreSQL 用 `ON CONFLICT`——换库时改这一句）。`JdbcTemplate.update(sql, args...)` 是 spring-jdbc 标准 API。

接入 `ChainingService.run`——在 SESSION_STARTED/COMPLETED/FAILED 时调 SessionService。**只加三个调用，不动 run 的执行逻辑**：

```java
// ChainingService 构造器加 SessionService（老的无 SessionService 构造器保留兼容，内部传 null）
protected final SessionService sessionService;

// run 的 Flux.create 里：
out.emit(EventContent.SESSION_STARTED, Map.of("input", input));
if (sessionService != null) sessionService.start(sessionId, input, steps().size());   // ← 新增

// ... 循环执行步骤不变 ...

// 循环结束后：
if (sessionService != null) sessionService.complete(sessionId, truncate(payload, 2000));   // ← 新增
out.emit(EventContent.SESSION_COMPLETED, Map.of());

// onErrorResume 里：
if (sessionService != null) sessionService.fail(sessionId);   // ← 新增
```

子类 `ArticleService` 构造器加 SessionService（配套改动）：

```java
public ArticleService(ChatClient chatClient, EventBus eventBus, SessionService sessionService) {
    super(chatClient, eventBus, sessionService);
}
```

#### 8.1.3 验证

启动应用，页面点「生成」跑完一次。查库：

```bash
# H2 文件库用任意工具或加 /h2-console；这里示意 SQL
SELECT session_id, status, input_text, substr(output_text,1,40), started_at FROM session_record;
```

能看到一条 `COMPLETED` 的记录，带输入和输出。再制造一次失败（断网/改错 key），查到一条 `FAILED`。**会话终于落库了**。

#### 8.1.4 暴露问题

客服回来说"我看到那条记录了，但用户问的是**当时生成到一半的大纲长什么样**"——概要表只有最终输出，**过程事件没有可回放的入口**。第 7 章归档存了事件，但没接口把它重放出来。

→ 进 **8.2 历史回放**。

#### 8.1.5 checkpoint

```
src/main/java/com/example/aobs/session/   ← 新增包
├── SessionStatus.java
└── SessionService.java
src/main/resources/schema.sql             （改：加 session_record 表）
src/main/java/.../workflow/ChainingService.java （改：run 接 SessionService）
src/main/java/.../writing/ArticleService.java   （改：构造器）
```

```bash
git add -A && git commit -m "第8.1章：会话最小持久化（状态+输入输出落库）"
```

---

### 8.2 阶段二：历史回放——把归档事件重放成 SSE

#### 8.2.0 场景

8.1 暴露的问题：概要表没有过程，用户想看"当时大纲"。第 7 章归档表 `event_archive` 其实**已经存了全部事件**（按 sequence），只是没接口把它重放出来。

这一阶段做**回放接口**：输入 sessionId，从归档按 sequence 查出全部事件，逐条映射成 SSE 帧推给前端。前端用**同一个调试页面**看历史——和实时看的体验一致（同样的 CONTENT_DELTA 逐字、同样的 STEP 面板）。

#### 8.2.1 思路

复用第 7 章 `event_archive` 表（不新建表）。回放 = `SELECT payload FROM event_archive WHERE session_id=? ORDER BY seq`，每条 payload 本身就是完整的事件 JSON，包成 SSE 帧推出去。

**关键认知：回放是只读重放，不重跑 LLM**。回放读的是历史快照（归档的事件），不触发 `run`、不烧钱。这点和第 2 章"重连走 EventBus 看后续实时事件"完全不同——回放看"过去的全部"，重连看"断连后的增量"。

#### 8.2.2 动手

`src/main/java/com/example/aobs/session/SessionReplayController.java`（新增）：

```java
package com.example.aobs.session;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 历史回放：把归档事件按 sequence 重放成 SSE 流。
 * 前端订阅它，事件逐条到达——和实时接口的体验一致。
 *
 * ⚠️ 本阶段先不做鉴权（8.3 会暴露并修）。
 */
@RestController
@RequestMapping("/api/session")
public class SessionReplayController {

    private final JdbcTemplate jdbc;

    public SessionReplayController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping(value = "/replay/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> replay(@PathVariable String sessionId) {
        // 按序号查出该会话全部事件的 payload（已是完整 JSON）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT payload FROM event_archive WHERE session_id=? ORDER BY seq", sessionId);

        return Flux.fromIterable(rows)
                .map(row -> {
                    String payload = String.valueOf(row.get("payload"));
                    return ServerSentEvent.<String>builder()
                            .event(extractType(payload))   // 从 payload 取 type 作为 SSE event 字段
                            .data(payload)
                            .build();
                })
                .startWith(ServerSentEvent.<String>builder()
                        .event("READY").data("{\"type\":\"READY\"}").build());
    }

    /** 从 payload JSON 粗取 type（SSE event 字段用）。生产换 ObjectMapper 解析。 */
    private String extractType(String payload) {
        int i = payload.indexOf("\"type\":\"");
        if (i < 0) return "EVENT";
        int j = payload.indexOf("\"", i + 8);
        return j < 0 ? "EVENT" : payload.substring(i + 8, j);
    }
}
```

> **API 核实**：`JdbcTemplate.queryForList(sql, args)` 返回 `List<Map<String,Object>>`；`Flux.fromIterable(...).map(...)` 是标准 Reactor；`ServerSentEvent.builder()` 是 WebFlux 的 SSE 帧。归档表的 `payload` 列在第 7 章存的是 `ObjectMapper.writeValueAsString(event)` 完整 JSON，这里原样包成 SSE data。
>
> **为什么前端不用改**：实时流（`/api/obs/article`）和历史回放（`/api/session/replay/{id}`）都是 SSE，前端帧解析逻辑完全一样。A.10 的页面 `replay()` 函数直接订阅这个接口即可。

#### 8.2.3 验证

页面跑完一次写作（8.1 已落库 + 归档有事件）。然后用浏览器或 curl 回放：

```bash
curl -N "http://localhost:8080/api/session/replay/s-1700000000000"
```

事件逐条到达——SESSION_STARTED → STEP_START → CONTENT_DELTA× → STEP_END → ... → SESSION_COMPLETED。**和历史实时看的一模一样**。在 A.10 页面左侧栏点历史会话，右侧渲染出完整回放。

#### 8.2.4 暴露问题

现在回放接口**任何人知道 sessionId 就能看**——没有鉴权。sessionId 不是秘密（它是时间戳生成的、可能出现在日志里），猜到一个就能看别人的会话（含用户输入）。这是越权漏洞。

更糟：还没法"列出有哪些会话"——客服要在后台看所有会话，现在只能猜 sessionId 一个个回放试。

→ 进 **8.3 会话列表 + 鉴权**。

#### 8.2.5 checkpoint

```
src/main/java/com/example/aobs/session/
└── SessionReplayController.java   ← 新增：回放接口
```

```bash
git add -A && git commit -m "第8.2章：历史回放（归档事件按sequence重放成SSE）"
```

---

### 8.3 阶段三：会话列表 + 鉴权——能查所有会话、防越权

#### 8.3.0 场景

8.2 暴露两个问题：① 回放没鉴权，越权；② 没有"列表"，客服看不到所有会话。这一阶段一起解决——加列表接口（分页 + 状态筛选），并给所有历史接口加租户鉴权。

但这里先要补一个**前置条件**：第 5 章的 `tenantId` 是事件级字段，会话概要表（8.1）还没存 tenantId。要按租户筛会话、要校验"你只能看自己租户的会话"，先得让 `session_record` 带上 tenantId。

#### 8.3.1 思路

- `session_record` 加 `tenant_id` 列，`SessionService.start` 时写入。
- 列表接口 `GET /api/session/list?tenantId=&status=&page=&size=`：按租户/状态筛选、分页。
- 回放/列表都校验：请求的 tenantId 必须匹配会话的 tenantId（**双向校验**，复用第 5 章思想）。
- 加一个 `WebFilter` 统一校验请求的租户身份（`X-Tenant-Id` + `X-Tenant-Key`）——这样不用每个 Controller 自己校验，避免漏。

> **鉴权铁律：sessionId 不是秘密**。所有历史接口（回放/列表/续传/删除）都必须校验 tenantId。知道 sessionId 不等于有权看，鉴权靠"请求方 tenantId 必须匹配会话 tenantId"。

#### 8.3.2 动手

`session_record` 加 `tenant_id` 列（改 schema）：

```sql
ALTER TABLE session_record ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_session_tenant_status ON session_record(tenant_id, status);
```

`SessionRecord` DTO（新增，行映射 + 给列表返回用）：

```java
package com.example.aobs.session;

import java.sql.ResultSet;

/** 会话概要 DTO（session_record 一行的映射）。字段随阶段长。 */
public record SessionRecord(
        String sessionId, String tenantId, String status,
        String inputText, String outputText, int totalSteps,
        long startedAt, Long endedAt
) {
    public static SessionRecord fromRow(ResultSet rs) throws java.sql.SQLException {
        return new SessionRecord(
                rs.getString("session_id"), rs.getString("tenant_id"), rs.getString("status"),
                rs.getString("input_text"), rs.getString("output_text"), rs.getInt("total_steps"),
                rs.getLong("started_at"), (Long) rs.getObject("ended_at"));
    }
}
```

`SessionService` 扩展——start 带 tenantId、加 get/list（在 8.1 基础上追加方法）：

```java
// start 签名加 tenantId
public void start(String sessionId, String tenantId, String input, int totalSteps) {
    long now = System.currentTimeMillis();
    jdbc.update("MERGE INTO session_record KEY(session_id) VALUES (?,?,?,?,?,?,NULL,NULL,?)",
            sessionId, SessionStatus.ACTIVE.name(), tenantId, input, null, totalSteps, now, now);
}

public SessionRecord get(String sessionId) {
    var rs = jdbc.query("SELECT * FROM session_record WHERE session_id=?",
            (r, i) -> SessionRecord.fromRow(r), sessionId);
    return rs.isEmpty() ? null : rs.get(0);
}

/** 分页 + 按租户/状态筛选。 */
public List<SessionRecord> list(String tenantId, SessionStatus status, int page, int size) {
    StringBuilder sql = new StringBuilder("SELECT * FROM session_record WHERE 1=1");
    java.util.List<Object> args = new java.util.ArrayList<>();
    if (tenantId != null) { sql.append(" AND tenant_id=?"); args.add(tenantId); }
    if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
    sql.append(" ORDER BY started_at DESC LIMIT ? OFFSET ?");
    args.add(size); args.add(page * size);
    return jdbc.query(sql.toString(), (r, i) -> SessionRecord.fromRow(r), args.toArray());
}
```

> `ChainingService.run` 调 `start` 的地方同步加 tenantId（从请求上下文取，本阶段先用方法参数透传，8.5 会整理成 SessionContext）。

**鉴权过滤器**——所有 `/api/**` 统一校验租户身份：

```java
package com.example.aobs.session;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 租户鉴权过滤器：校验 X-Tenant-Id（本阶段先只校验存在性，8.6 加 X-Tenant-Key 真鉴权）。
 * 放过静态资源和调试端点（生产收紧）。
 */
@Component
public class TenantAuthFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/index.html") || path.startsWith("/actuator") || path.equals("/")) {
            return chain.filter(exchange);
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        if (tenantId == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        exchange.getAttributes().put("tenantId", tenantId);   // Controller 取用
        return chain.filter(exchange);
    }
}
```

回放接口加鉴权（改 SessionReplayController）：

```java
@GetMapping(value = "/replay/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> replay(@PathVariable String sessionId,
                                            @RequestHeader("X-Tenant-Id") String tenantId) {
    SessionRecord rec = sessionService.get(sessionId);
    // 双向校验：会话不存在或租户不匹配，都返回 not found（不暴露存在性）
    if (rec == null || !tenantId.equals(rec.tenantId())) {
        return Flux.just(ServerSentEvent.<String>builder()
                .event("ERROR").data("{\"error\":\"session not found\"}").build());
    }
    // ... 原回放逻辑 ...
}
```

列表接口（SessionReplayController 加）：

```java
@GetMapping("/list")
public Map<String, Object> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
    SessionStatus st = status != null ? SessionStatus.valueOf(status.toUpperCase()) : null;
    return Map.of("items", sessionService.list(tenantId, st, page, size), "page", page, "size", size);
}
```

> **API 核实**：`WebFilter` 是 WebFlux 过滤器（Servlet 版是 `jakarta.servlet.Filter`）；`exchange.getAttributes().put(...)` 在请求链路里传值，Controller 用 `@RequestHeader` 或 `exchange.getAttribute` 取。H2 的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 是标准 DDL。
>
> **鉴权不暴露存在性**：会话不存在和租户不匹配都返回"not found"——不告诉调用方"这个会话存在但没权"，否则可被用来探测 sessionId 是否存在。

#### 8.3.3 验证

**正常**：带 `X-Tenant-Id: tenantA` 调 `/api/session/list`，看到 tenantA 的会话列表。带状态筛选 `?status=COMPLETED` 只看完成的。

**越权被拦**：tenantA 的会话，用 `X-Tenant-Id: tenantB` 调回放 → 返回 not found（看不到 B 没权的会话）。

**无租户头被拦**：不带 `X-Tenant-Id` 调任何 `/api/**` → 401。

#### 8.3.4 暴露问题

客服在列表里看到一些 `ACTIVE` 状态的会话——但它们其实是**上周部署重启时被打断的僵尸会话**（停机时没标记，永远卡在 ACTIVE）。用户问"我那次断了能接着跑吗"——现在没法恢复，只能让用户重输。

→ 进 **8.4 断点续传**。

#### 8.3.5 checkpoint

```
src/main/java/com/example/aobs/session/
├── SessionRecord.java           ← 新增：DTO
├── SessionService.java          （改：start 带 tenantId + get/list）
├── SessionReplayController.java （改：回放鉴权 + 列表接口）
└── TenantAuthFilter.java        ← 新增：鉴权过滤器
src/main/resources/schema.sql    （改：session_record 加 tenant_id）
```

```bash
git add -A && git commit -m "第8.3章：会话列表+租户鉴权（防越权）"
```

---

### 8.4 阶段四：断点续传——中断后从断点接着跑

#### 8.4.0 场景

8.3 暴露：僵尸 ACTIVE 会话（停机时未标记）+ 用户想续中断。这一阶段做两件事：① 停机时把未完成的 ACTIVE 标成 INTERRUPTED；② 给 INTERRUPTED/FAILED 会话一个"继续生成"入口，从断点接着跑。

`SessionStatus` 启用 `INTERRUPTED`（8.1 已定义）。

#### 8.4.1 思路

**续传的关键认知：续传 ≠ 简单重跑**。会话崩在第 2 步，续传要从第 2 步接着跑——但第 2 步的输入（第 1 步的草稿）在内存里没了（重启了），必须从持久化取。所以续传要解决两个问题：
1. **记得跑到哪一步了** → `session_record` 加 `last_step` 列，每步完成时更新。
2. **拿到上一步的输出** → 第 7 章归档的 STEP_END 事件存了 output（摘要），续传时取它喂下一步。

> **续传只允许 INTERRUPTED/FAILED**：ACTIVE 正在跑（不该续）、COMPLETED 已完成（没意义）。状态机限定续传入口——**状态不只是标签，是行为开关**。

#### 8.4.2 动手

`session_record` 加 `last_step` 列：

```sql
ALTER TABLE session_record ADD COLUMN IF NOT EXISTS last_step INT DEFAULT -1;   -- -1=未开始
```

`SessionService` 加 advanceStep / markInterrupted（在 8.3 基础上追加）：

```java
/** 每步完成时更新 last_step（续传依据）。 */
public void advanceStep(String sessionId, int lastStep) {
    jdbc.update("UPDATE session_record SET last_step=?, updated_at=? WHERE session_id=?",
            lastStep, System.currentTimeMillis(), sessionId);
}

/** 优雅停机时把所有未完成的 ACTIVE 标 INTERRUPTED。 */
public void markInterrupted() {
    jdbc.update("UPDATE session_record SET status=?, updated_at=? WHERE status=?",
            SessionStatus.INTERRUPTED.name(), System.currentTimeMillis(), SessionStatus.ACTIVE.name());
}

/** 取某步的完整输出（续传喂下一步用）。从归档 STEP_END 取 output。 */
public String getStepOutput(String sessionId, int step) {
    try {
        var payloads = jdbc.queryForList(
                "SELECT payload FROM event_archive WHERE session_id=? AND type=? ORDER BY seq DESC LIMIT 1",
                String.class, sessionId, "STEP_END");
        if (payloads.isEmpty()) return null;
        String p = payloads.get(0);
        int i = p.indexOf("\"output\":\"");
        if (i < 0) return null;
        int j = p.indexOf("\"", i + 10);
        return j < 0 ? null : p.substring(i + 10, j);
    } catch (Exception e) { return null; }
}
```

`ChainingService` 加 `runFrom`（从 fromStep 接着跑），run 调 runFrom(..., 0)：

```java
/**
 * 从 fromStep 开始跑。fromStep=0 等价全新执行。续传时 fromStep = last_step + 1。
 * 续传的第一步，其输入（上一步输出）从归档取（resolveUserPrompt）。
 */
public Flux<AgentEvent> runFrom(String input, String sessionId, String tenantId, int fromStep) {
    return Flux.<AgentEvent>create(sink -> {
                FluxSinkAdapter out = new FluxSinkAdapter(sink, eventBus, sessionId);
                java.util.List<Step> stepList = steps();
                int total = stepList.size();

                if (fromStep == 0) {
                    sessionService.start(sessionId, tenantId, input, total);
                    out.emit(EventContent.SESSION_STARTED, Map.of("input", input));
                } else {
                    // 续传：发 STEP_RESUMED，让前端知道"从第 N 步接着跑"
                    out.emit(EventContent.STEP_RESUMED, Map.of("fromStep", fromStep, "total", total));
                }

                String payload = input;
                for (int step = fromStep; step < total; step++) {
                    Step s = stepList.get(step);
                    out.emit(EventContent.STEP_START, Map.of("step", step, "total", total));
                    // 续传的第一步：输入从归档取（上一步输出）；否则用循环累积的 payload
                    String userPrompt = (step == fromStep && fromStep > 0)
                            ? s.toUserPrompt().apply(sessionService.getStepOutput(sessionId, step - 1), sessionId)
                            : s.toUserPrompt().apply(payload, sessionId);
                    String full = streamStep(s.system(), userPrompt, sessionId, step, out);
                    out.emit(EventContent.STEP_END, Map.of("step", step, "output", truncate(full, 80)));
                    sessionService.advanceStep(sessionId, step);   // 记录进度
                    payload = full;
                }

                sessionService.complete(sessionId, truncate(payload, 2000));
                out.emit(EventContent.SESSION_COMPLETED, Map.of());
                sink.complete();
            })
            .onErrorResume(err -> { sessionService.fail(sessionId); /* ... 发 SESSION_FAILED ... */ return Flux.empty(); })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
}

/** 全新执行（第 1-7 章的老入口保留兼容）。 */
public Flux<AgentEvent> run(String input, String sessionId, String tenantId) {
    return runFrom(input, sessionId, tenantId, 0);
}
```

`EventContent` 加 `STEP_RESUMED`：

```java
    STEP_RESUMED;       // 续传起点（第 8.4 章）
```

续传接口（SessionReplayController 加）：

```java
@PostMapping(value = "/resume/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> resume(@PathVariable String sessionId,
                                            @RequestHeader("X-Tenant-Id") String tenantId) {
    SessionRecord rec = sessionService.get(sessionId);
    if (rec == null || !tenantId.equals(rec.tenantId())) {
        return Flux.just(errorSse("session not found"));
    }
    if (!"INTERRUPTED".equals(rec.status()) && !"FAILED".equals(rec.status())) {
        return Flux.just(errorSse("只能续传中断或失败的会话，当前:" + rec.status()));
    }
    int fromStep = rec.lastStep() + 1;
    return articleService.runFrom(rec.inputText(), sessionId, tenantId, fromStep).map(this::toSse);
}
```

优雅停机标记（`@PreDestroy`）+ 开启优雅停机：

```java
@Component
public class ShutdownHook {
    private final SessionService sessionService;
    public ShutdownHook(SessionService sessionService) { this.sessionService = sessionService; }
    @jakarta.annotation.PreDestroy
    public void onShutdown() { sessionService.markInterrupted(); }
}
```

```yaml
# application.yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

> **优雅停机序列**：① 收到信号 → ② 拒新请求 → ③ 等进行中请求结束（≤30s）→ ④ `@PreDestroy` 标 INTERRUPTED → ⑤ 退出。重启后这些会话出现在列表里，可续传。

#### 8.4.3 验证

页面跑写作，跑到第 1 步时 `kill` 后端（优雅 kill，不是 -9）。重启后端，调 `/api/session/list` 查 INTERRUPTED——能看到那个会话。调 `/api/session/resume/{id}` → 事件流从 STEP_RESUMED 开始，接着跑完。**断点续传可用**。

#### 8.4.4 暴露问题

续传后会发现：**用户原输入里的手机号变成了 `138****5678`**——因为 8.1 的 `start` 把 input 原样存了（还没脱敏，但 8.5 要脱敏），而续传 `runFrom` 拿 `rec.inputText()` 当 input 喂 LLM。一旦 8.5 给 input 脱敏，续传就喂了打码文本，LLM 生成质量下降。**脱敏（合规）和续传（要原文）是冲突需求**。

另一个：`kill -9`（非优雅）时 `@PreDestroy` 不触发，会话卡在 ACTIVE 僵尸态。

→ 进 **8.5 治理配套**（脱敏/加密/僵尸回收）。

#### 8.4.5 checkpoint

```
src/main/java/com/example/aobs/session/
├── SessionService.java           （改：advanceStep/markInterrupted/getStepOutput）
├── SessionReplayController.java  （改：续传接口）
└── ShutdownHook.java             ← 新增：停机标记
src/main/java/.../workflow/ChainingService.java （改：runFrom 支持续传）
src/main/java/.../obs/EventContent.java          （改：加 STEP_RESUMED）
src/main/resources/schema.sql     （改：session_record 加 last_step）
```

```bash
git add -A && git commit -m "第8.4章：断点续传（last_step+runFrom+停机标记INTERRUPTED）"
```

---

### 8.5 阶段五：治理配套——脱敏/加密/幂等/TTL/僵尸回收

#### 8.5.0 场景

8.4 暴露：① 脱敏和续传原文冲突；② `kill -9` 僵尸会话。加上线后就一直在的合规/成本问题——归档存了用户原文（身份证）、重复提交烧两次钱、归档无限增长。这一阶段把"上历史功能必须配套"的治理能力一次性补齐（**不是可选，是前提**）。

#### 8.5.1 思路

| 治理项 | 解决什么 | 做法 |
|--------|---------|------|
| **PII 脱敏** | 归档/列表存原文=合规事故 | 入库前正则打码（手机/身份证/邮箱） |
| **加密原文** | 脱敏后续传没原文=质量差 | 单独存加密原文，续传解密用 |
| **幂等键** | 连点两次烧两次钱 | 请求带 key，Redis SETNX 占坑 |
| **TTL 清理** | 归档无限增长 | 定时删过期会话（保留期 90 天） |
| **僵尸回收** | kill -9 卡 ACTIVE | 定时把超时 ACTIVE 标 INTERRUPTED |

> **核心：脱敏只针对"副本"，不碰"原文"**。LLM 看原文（质量）、存储看脱敏版（合规）、续传看加密原文（功能）——三份分层存储，互不牺牲。

#### 8.5.2 动手

**（a）PII 脱敏** `PiiMasker`：

```java
package com.example.aobs.session;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/** PII 脱敏：入库前打码。只脱敏"副本"（归档/列表），不脱敏喂 LLM 的原文。 */
@Component
public class PiiMasker {
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])[1-9]\\d{16}[0-9Xx](?![0-9Xx])");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    public String mask(String text) {
        if (text == null) return null;
        String t = PHONE.matcher(text).replaceAll(m -> mid(m.group(), 3, 4));
        t = ID_CARD.matcher(t).replaceAll(m -> mid(m.group(), 6, 4));
        t = EMAIL.matcher(t).replaceAll(m -> mid(m.group(), 1, m.group().indexOf('@')));
        return t;
    }
    private String mid(String s, int pre, int suf) {
        if (s.length() <= pre + suf) return "****";
        return s.substring(0, pre) + "*".repeat(s.length() - pre - suf) + s.substring(s.length() - suf);
    }
}
```

**（b）加密原文** `CryptoService`（AES-GCM）+ session_record 加列：

```sql
ALTER TABLE session_record ADD COLUMN IF NOT EXISTS input_text_raw TEXT;  -- 加密原文（续传用）
```

```java
package com.example.aobs.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM 加密。续传原文加密存储，密钥从配置读（生产走 KMS）。 */
@Service
public class CryptoService {
    private final byte[] key;
    public CryptoService(@Value("${aobs.crypto.key}") String hex) {
        this.key = hexToBytes(hex);   // 32 字节 = AES-256
    }
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public String decrypt(String enc) {
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static byte[] hexToBytes(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2*i, 2*i+2), 16);
        return b;
    }
}
```

```yaml
aobs:
  crypto:
    key: ${AOBS_CRYPTO_KEY:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f}
```

`SessionService.start` 改为：脱敏版存 `input_text`、加密原文存 `input_text_raw`：

```java
public void start(String sessionId, String tenantId, String rawInput, int totalSteps) {
    long now = System.currentTimeMillis();
    jdbc.update("MERGE INTO session_record KEY(session_id) VALUES (?,?,?,?,?,?,?,?,NULL,NULL,?)",
            sessionId, SessionStatus.ACTIVE.name(), tenantId,
            piiMasker.mask(rawInput),           // input_text = 脱敏版（展示/合规）
            cryptoService.encrypt(rawInput),    // input_text_raw = 加密原文（续传用）
            null, totalSteps, -1, now, now);
}
```

续传接口改用加密原文（修 8.4 暴露的问题）：

```java
// resume 接口里：input 取加密原文并解密
String rawInput = cryptoService.decrypt(rec.inputTextRaw());
return articleService.runFrom(rawInput, sessionId, tenantId, fromStep).map(this::toSse);
```

> **API 核实**：`javax.crypto.Cipher`（AES/GCM/NoPadding）、`GCMParameterSpec`（128 位 auth tag、12 字节 IV）是 JDK 标准。AES-GCM 是加密"带认证"数据的标准（比 CBC 安全，防填充攻击）。

**（c）幂等键** `IdempotencyService`（Redis SETNX）：

```java
package com.example.aobs.session;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/** 幂等键：防重复提交。SETNX 原子占坑，重复 key 返回已有会话。 */
@Service
public class IdempotencyService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;
    public IdempotencyService(StringRedisTemplate redis) { this.redis = redis; }

    /** 占坑。true=首次可执行，false=重复。SETNX 原子，无竞态。 */
    public boolean tryAcquire(String key, String sessionId) {
        if (key == null || key.isBlank()) return true;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("aobs:idem:" + key, sessionId, TTL));
    }
    public String getExistingSession(String key) {
        return key == null ? null : redis.opsForValue().get("aobs:idem:" + key);
    }
}
```

SSE 接口开头加幂等检查（重复 key 引导走回放）：

```java
if (idemKey != null && !idempotencyService.tryAcquire(idemKey, sessionId)) {
    String existing = idempotencyService.getExistingSession(idemKey);
    return Flux.just(sse("IDEMPOTENT_REPLAY", "{\"existingSession\":\"" + existing + "\"}"));
}
```

> **SETNX 是原子占坑**：`SET key val NX EX 600` 在"不存在时设置+过期"一步原子完成，并发只一个成功。不能用"先 GET 判空再 SET"——两步间有竞态。

**（d）TTL 清理 + 僵尸回收** `ArchiveRetentionJob`：

```java
package com.example.aobs.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** 归档保留期清理 + 僵尸 ACTIVE 回收。 */
@Component
public class ArchiveRetentionJob {
    private static final int RETENTION_DAYS = 90;
    private final JdbcTemplate jdbc;
    public ArchiveRetentionJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 每天 03:00 清理超过 90 天的会话（分批删，避免大表锁）。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpired() {
        long cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86400_000L;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_id FROM session_record WHERE updated_at < ? AND status <> ?",
                cutoff, SessionStatus.ACTIVE.name());
        for (Map<String, Object> row : rows) {
            String sid = (String) row.get("session_id");
            jdbc.update("DELETE FROM event_archive WHERE session_id=?", sid);
            jdbc.update("DELETE FROM session_record WHERE session_id=?", sid);
        }
    }

    /** 每 5 分钟回收僵尸 ACTIVE（kill -9 遗留）：超 10 分钟没更新 = 卡死，转 INTERRUPTED。 */
    @Scheduled(fixedDelay = 300_000)
    public void reapZombieActive() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        jdbc.update("UPDATE session_record SET status=?, updated_at=? WHERE status=? AND updated_at < ?",
                SessionStatus.INTERRUPTED.name(), System.currentTimeMillis(),
                SessionStatus.ACTIVE.name(), cutoff);
    }
}
```

启动类加 `@EnableScheduling`。

> **僵尸回收靠 updated_at 超时**：正常会话每步都更新 updated_at（advanceStep）。10 分钟没更新 = 卡死。转 INTERRUPTED 让用户能续传。**心跳超时检测是分布式系统识别僵死的通用手段**。
>
> **分批删而非一条大 DELETE**：大表 DELETE 锁大量行、产生巨量 undo log。先 SELECT 出 id 再逐条删（事务小）。生产里更大量数据用"分页删 + sleep"。

#### 8.5.3 验证

- **脱敏**：输入含手机号，查 session_record.input_text 是 `138****5678`，input_text_raw 是加密串；解密 input_text_raw 得到原文；续传喂的是原文，生成质量正常。
- **幂等**：连点两次生成（同 Idempotency-Key），第二次返回 IDEMPOTENT_REPLAY，不重跑。
- **TTL/僵尸**：手动改一条记录的 updated_at 到 11 分钟前，等定时任务跑（或手动调 reapZombieActive），状态变 INTERRUPTED。

#### 8.5.4 暴露问题（最后）

到目前为止，租户身份校验只验了 `X-Tenant-Id` 存在（8.3 的简化），没验"这个 tenantId 合法吗、有没有密钥"。任何人填个 tenantId 就能过。而且租户本身没法管理（不能建租户、改配额、停用）。

→ 进 **8.6 多租户管理**。

#### 8.5.5 checkpoint

```
src/main/java/com/example/aobs/session/
├── PiiMasker.java              ← 新增：脱敏
├── CryptoService.java          ← 新增：AES-GCM 加密
├── IdempotencyService.java     ← 新增：幂等键
├── ArchiveRetentionJob.java    ← 新增：TTL 清理 + 僵尸回收
└── SessionService.java         （改：start 存脱敏+加密原文）
src/main/resources/schema.sql   （改：session_record 加 input_text_raw）
src/main/java/.../Application.java （改：加 @EnableScheduling）
```

```bash
git add -A && git commit -m "第8.5章：治理配套（脱敏+加密+幂等+TTL+僵尸回收）"
```

---

### 8.6 阶段六：多租户管理——租户可建可停可改配额

#### 8.6.0 场景

8.5 暴露：租户身份没真鉴权（只验存在）、租户本身不可管理。这一阶段把第 5 章的"租户"从"事件字段"升级成"可管理实体"——建租户（发密钥）、改配额、停用、真鉴权。

#### 8.6.1 思路

- `tenant` 表：租户信息 + 配额 + apiKey（存 SHA-256 哈希）。
- `TenantAuthFilter`（8.3 已建）升级：不只验 X-Tenant-Id 存在，还要 `X-Tenant-Id` + `X-Tenant-Key` 匹配（checkApiKey），且租户 ACTIVE。
- 管理接口（运营用）：建/改配额/停用/列表。

> **入口鉴权（本节）+ 出口校验（第 5 章）= 纵深防御**：鉴权回答"你是哪个租户、合法吗"（入口拦），校验回答"这个事件确实属于这个租户吗"（出口防越权）。单靠任一不够。

#### 8.6.2 动手

`tenant` 表：

```sql
CREATE TABLE IF NOT EXISTS tenant (
    tenant_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128),
    status VARCHAR(16) NOT NULL,          -- ACTIVE/SUSPENDED
    daily_budget_usd DOUBLE NOT NULL,
    user_rpm_limit INT NOT NULL,
    api_key_hash VARCHAR(128),            -- SHA-256 哈希（不存明文）
    created_at BIGINT NOT NULL
);
```

`Tenant` + `TenantService`：

```java
package com.example.aobs.tenant;

public record Tenant(String tenantId, String name, TenantStatus status,
                     double dailyBudgetUsd, int userRpmLimit, String apiKeyHash, long createdAt) {
    public enum TenantStatus { ACTIVE, SUSPENDED }
}
```

```java
package com.example.aobs.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Service
public class TenantService {
    private final JdbcTemplate jdbc;
    public TenantService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(Tenant t, String plainApiKey) {
        jdbc.update("INSERT INTO tenant (tenant_id,name,status,daily_budget_usd,user_rpm_limit,api_key_hash,created_at) VALUES (?,?,?,?,?,?,?)",
                t.tenantId(), t.name(), t.status().name(), t.dailyBudgetUsd(),
                t.userRpmLimit(), sha256(plainApiKey), System.currentTimeMillis());
    }
    public void updateQuota(String id, double budget, int rpm) {
        jdbc.update("UPDATE tenant SET daily_budget_usd=?, user_rpm_limit=? WHERE tenant_id=?", budget, rpm, id);
    }
    public void setStatus(String id, Tenant.TenantStatus s) {
        jdbc.update("UPDATE tenant SET status=? WHERE tenant_id=?", s.name(), id);
    }
    public Tenant get(String id) {
        var rs = jdbc.query("SELECT * FROM tenant WHERE tenant_id=?", (r,i) -> new Tenant(
                r.getString("tenant_id"), r.getString("name"),
                Tenant.TenantStatus.valueOf(r.getString("status")),
                r.getDouble("daily_budget_usd"), r.getInt("user_rpm_limit"),
                r.getString("api_key_hash"), r.getLong("created_at")), id);
        return rs.isEmpty() ? null : rs.get(0);
    }
    public List<Tenant> list() {
        return jdbc.query("SELECT * FROM tenant ORDER BY created_at DESC", (r,i) -> new Tenant(
                r.getString("tenant_id"), r.getString("name"),
                Tenant.TenantStatus.valueOf(r.getString("status")),
                r.getDouble("daily_budget_usd"), r.getInt("user_rpm_limit"),
                r.getString("api_key_hash"), r.getLong("created_at")));
    }
    /** 鉴权：租户存在 + ACTIVE + key 哈希匹配。 */
    public boolean checkApiKey(String tenantId, String plainKey) {
        Tenant t = get(tenantId);
        return t != null && t.status() == Tenant.TenantStatus.ACTIVE
                && t.apiKeyHash() != null && t.apiKeyHash().equals(sha256(plainKey));
    }
    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toString(s.hashCode()); }
    }
}
```

`TenantAuthFilter` 升级（8.3 的基础上加 key 校验）：

```java
@Component
public class TenantAuthFilter implements WebFilter {
    private final TenantService tenantService;
    public TenantAuthFilter(TenantService tenantService) { this.tenantService = tenantService; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/index.html") || path.startsWith("/actuator") || path.equals("/")
                || path.startsWith("/api/admin")) {   // 运营管理接口走单独鉴权（生产加）
            return chain.filter(exchange);
        }
        String tid = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        String tkey = exchange.getRequest().getHeaders().getFirst("X-Tenant-Key");
        if (!tenantService.checkApiKey(tid, tkey)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        exchange.getAttributes().put("tenantId", tid);
        return chain.filter(exchange);
    }
}
```

管理接口（运营用）：

```java
@RestController
@RequestMapping("/api/admin/tenant")
public class TenantController {
    private final TenantService tenantService;
    public TenantController(TenantService t) { this.tenantService = t; }

    @PostMapping
    public Map<String,Object> create(@RequestBody Map<String,Object> body) {
        String apiKey = "sk-" + java.util.UUID.randomUUID();   // 明文 key 只返回这一次
        tenantService.create(new Tenant((String)body.get("tenantId"), (String)body.get("name"),
                Tenant.TenantStatus.ACTIVE, 10.0, 60, null, System.currentTimeMillis()), apiKey);
        return Map.of("tenantId", body.get("tenantId"), "apiKey", apiKey, "msg", "妥善保存 apiKey，之后不可见");
    }
    @PutMapping("/{id}/quota")
    public void quota(@PathVariable String id, @RequestParam double budget, @RequestParam int rpm) {
        tenantService.updateQuota(id, budget, rpm);
    }
    @PutMapping("/{id}/status")
    public void status(@PathVariable String id, @RequestParam String s) {
        tenantService.setStatus(id, Tenant.TenantStatus.valueOf(s.toUpperCase()));
    }
    @GetMapping
    public List<Tenant> list() { return tenantService.list(); }
}
```

> **apiKey 存哈希、明文只返回一次**：和用户密码同理，库泄漏也拿不到明文。租户丢了 key 只能重置（重新 create/生成）。停用租户（SUSPENDED）在 checkApiKey 直接拒——既不烧 LLM 也不进事件流，**停用是入口拦截**。

#### 8.6.3 验证

```bash
# 运营建租户（拿到 apiKey）
curl -X POST http://localhost:8080/api/admin/tenant -H "Content-Type: application/json" \
  -d '{"tenantId":"tenantA","name":"客户A"}'
# → {"tenantId":"tenantA","apiKey":"sk-xxx","msg":"妥善保存..."}

# 用 key 调业务接口（通过鉴权）
curl -N "http://localhost:8080/api/obs/article?prompt=test" \
  -H "X-Tenant-Id: tenantA" -H "X-Tenant-Key: sk-xxx" -H "sessionId: s1"

# 错 key 被拒
curl -N "http://localhost:8080/api/obs/article?prompt=test" \
  -H "X-Tenant-Id: tenantA" -H "X-Tenant-Key: wrong" -H "sessionId: s1"   # 401

# 停用
curl -X PUT "http://localhost:8080/api/admin/tenant/tenantA/status?s=SUSPENDED"
# 再用原 key 调 → 401（停用即拒）
```

#### 8.6.4 checkpoint

```
src/main/java/com/example/aobs/tenant/    ← 新增包
├── Tenant.java
├── TenantService.java
└── TenantController.java
src/main/java/.../session/TenantAuthFilter.java （改：加 X-Tenant-Key 校验）
src/main/resources/schema.sql             （改：加 tenant 表）
```

```bash
git add -A && git commit -m "第8.6章：多租户管理（CRUD+配额+停用+真鉴权）"
```

---

### 8.7 前端整合——DeepSeek 风对话页面

六个阶段的后端都齐了，前端用一个**参照 DeepSeek 官网布局**的页面整合：左侧会话历史栏（第 8 章列表/回放/续传）+ 右侧对话区（实时事件流）+ 底部输入。**只做对话，不做多模态/上传**。完整页面见附录 **A.10**（覆盖第 1-8 章所有验证）。

> **为什么前端放附录 A.10、不在这展开**：页面 HTML 较长（~200 行），放正文挤占"分阶段学后端"的注意力。A.10 给完整页面，每个阶段验证时用它对应的功能（8.1 跑完看左侧栏刷新、8.2 点会话回放、8.4 点续传、8.6 切租户）。

---

### 8.8 第 8 章复盘

**做了**（六阶段，每阶段一个能跑的 checkpoint）：
1. 最小持久化（会话落库）→ 2. 历史回放（归档重放成 SSE）→ 3. 列表+鉴权（防越权）→ 4. 断点续传（从 last_step 接着跑）→ 5. 治理配套（脱敏/加密/幂等/TTL/僵尸回收）→ 6. 多租户管理（CRUD+配额+停用+真鉴权）。

**这一章最该记住的工程教训**：
1. **每个机制都是被上一阶段痛点逼出来的**——不先落库就没法回放、不先回放就暴露不出越权、不先续传就暴露不出脱敏冲突。**演进驱动设计，不是一上来全画好**。
2. **会话状态机是历史功能的地基**——没有明确状态，列表筛选、续传决策、过期清理都无从下手。状态不只是标签，是行为开关（续传只允许 INTERRUPTED/FAILED）。
3. **断点续传 ≠ 简单重跑**——续传要把中间产物也持久化（上一步输出），否则下一步拿不到输入。
4. **合规和功能要分层存储**——脱敏版（展示/合规）、加密原文（续传/功能）、LLM 看原文。三份分层，互不牺牲。
5. **历史功能必须配套治理**——幂等（防烧钱）、脱敏（防合规事故）、TTL（防成本爆炸）、鉴权（防越权）。缺一个历史功能就是黑洞。**不是可选，是前提**。
6. **入口鉴权 + 出口校验 = 纵深防御**——鉴权拦身份（入口）、校验防越权（出口），单靠任一不够。

**到这里，一个从"实时可见"到"历史可管"的完整可观测产品成型了**——Agent 跑得起来、看得见、出事能救、数据活得久、查得到、接得上、管得住。

> **第 8 章是"可观测性"向"数据治理"的自然延伸**。再往后是产品功能（多轮记忆、历史编辑、A/B、质量评估）——它们是 AI 产品能力，不是可观测本身，进附录 A.11-A.13 讲。

---

## 附录：完整目录树与踩坑手册

### A.1 完整项目结构（第 8 章结束时）

```
ai-writing-assistant/
├── pom.xml
├── src/main/java/com/example/aobs/
│   ├── Application.java
│   ├── config/
│   │   └── ChatClientConfig.java
│   ├── workflow/
│   │   └── ChainingService.java          # 业务基类（流式 run + 续传 runFrom + 埋点）
│   ├── writing/
│   │   ├── ArticleService.java           # 写作助手
│   │   ├── ArticleController.java        # 同步接口
│   │   └── WritingTools.java             # @Tool 工具
│   ├── session/                          # 会话持久化与历史（第8章）
│   │   ├── SessionStatus.java            # 状态枚举
│   │   ├── SessionRecord.java            # 会话概要 DTO
│   │   ├── SessionService.java           # CRUD + 状态流转
│   │   ├── SessionContext.java           # 请求上下文
│   │   ├── SessionReplayController.java  # 回放/列表/续传/删除
│   │   ├── IdempotencyService.java       # 幂等键
│   │   ├── PiiMasker.java                # PII 脱敏
│   │   ├── ArchiveRetentionJob.java      # TTL 清理 + 僵尸回收
│   │   └── ShutdownHook.java             # 停机标记 INTERRUPTED
│   └── obs/                              # 可观测层（第 1-6 章逐步长出）
│       ├── AgentEvent.java               # 事件 record + builder（字段随章节长）
│       ├── EventContent.java             # 事件类型枚举（第1章起逐章加）
│       ├── EventBus.java / ShardedEventBus.java   # 总线（第1章）/分片总线（第4章）
│       ├── PropagatedContextValue.java   # Reactor Context key + ThreadLocalAccessor（第3章）
│       ├── AppContextKeys.java           # 上下文注册中心（第3章）
│       ├── ContextPropagationConfig.java # 开启自动传播（第3章）
│       ├── EventSequencer.java           # 序号重排（第4章）
│       ├── CriticalEventStore.java       # 关键事件落库 Redis（第2章）
│       ├── EventArchiveService.java      # 事件归档 JDBC + 历史查询（第7章）
│       ├── SseConnectionLimiter.java     # SSE 连接数治理（第7章）
│       ├── SessionStateStore.java        # 会话状态外置（第4章）
│       ├── RedisStreamBridge.java        # 跨实例广播（第4章）
│       ├── ToolObservationHandler.java   # 订阅工具 Observation（第3章）
│       ├── CostCalculator.java           # 成本计算（第3章）
│       ├── QuotaService.java             # 多级配额（第5章）
│       ├── SseController.java            # SSE 推送
│       ├── ResilientExternal.java        # 降级门面（第6章）
│       └── ObsHealthIndicator.java       # 健康检查（第6章）
├── src/main/resources/
│   ├── application.yaml
│   ├── schema.sql                        # event_archive + session_record 建表
│   └── static/                           # 前端页面（随章节演进，主要验证手段）
│       ├── index.html                    # 调试台（第1章起，逐章增强）
│       ├── reconnect.html                # 重连演示（第2章）
│       └── history.html                  # 历史会话管理台（第8章）
```
> 本文以**前端页面**为主要验证手段（不是单元测试）。真实项目里建议团队按需补 JUnit/StepVerifier 测试，但本文篇幅聚焦页面验证这条主线。

### A.2 git 提交历史

```
第8章：会话持久化+历史回放+断点续传+幂等+脱敏+TTL
第7章：运营事故——心跳、重试降级、取消、连接治理、事件归档
第6章：灾备降级 + 健康检查 + 超时/错误修复 + 监控
第5章：多租户隔离 + 成本归因 + 多级配额
第4章：规模化——状态外置、分片总线、跨实例广播、序号重排
第3章：Observation订阅工具调用 + 会话隔离 + token成本
第2章：事件可靠性——分级落库、序号、重连回放（页面演示）
第1章：事件总线+SSE+调试页面+READY握手消除竞态
第0章：黑盒写作助手跑通
```

### A.3 踩坑手册（每章容易卡的地方）

**第 0 章**：
- 同时引了 `spring-boot-starter-web` 和 `webflux` → 冲突。只引 webflux。
- API key 硬编码提交了 git → 用环境变量。

**第 1 章**：
- `curl` 没加 `-N` → 看不到实时，以为坏了。
- **竞态**（先启动任务再订阅）→ 漏 `SESSION_STARTED`。页面偶发能复现（curl 基本不行）。解法：`startWith(READY)` + `doOnSubscribe` 触发，协议层消除竞态。
- 改了 `ChainingService` 构造器，忘了改 `ArticleService` → 编译报错，这是好事，按报错改。

**第 2 章**：
- 没装 Redis 就跑 → 起不来或落库失败。用 `ObjectProvider` 让没 Redis 也能启动是容错。
- `takeUntil` 和 `takeWhile` 搞混 → `takeUntil` 是「匹配后还发这一个再停」，`takeWhile` 是「匹配时停、这一个不发」。
- EventSource 演示重连时 sessionId 必须走 query（EventSource 不能设 header）→ 后端要兼容 query 取 sessionId。

**第 3 章**：
- **Observation 重复注册**（打印/发事件两遍）→ 写了 `ObservationConfig` 手动 `observationHandler()` 又标了 `@Component`。Spring Boot 已自动注册 Bean，手动再注册 = 两次。解法：删掉 ObservationConfig，只留 `@Component`。
- **工具事件 sessionId 是 unknown** → 流式下工具执行切线程，`ContextPropagationConfig` 没开（或 `context-propagation` 库没在依赖里）就会读不到。解法：确认 `Hooks.enableAutomaticContextPropagation()` 已调、`PropagatedContextValue` 注册到了 `ContextRegistry`、run 里 `.contextWrite(sessionId)` 补上了。
- 自定义 `ChatClient` Bean 和默认的冲突 → 二选一，或用 `.tools()` 在调用时传。
- token 取不到（`Usage` 是 null）→ 有些模型/provider 不返回 Usage，检查 model 配置。

**第 4 章**：
- Redis Stream 防回环忘了做 → 事件无限循环刷屏。必须带 instanceId 跳过自己的。
- 分片后用 `flux()` 全量订阅（merge 所有分片）→ SSE 应该用 `fluxFor(sessionId)` 定向订阅。
- 起多实例忘了不同端口 → `SERVER_PORT` 环境变量。

**第 5 章**：
- 加了 tenantId 字段，所有 `AgentEvent.of(...)` 调用点没同步改 → 编译报错，按 IDE 逐个修。
- 租户过滤只查事件 tenantId、不查请求 tenantId → 越权风险。必须双向匹配 + null 拒绝。

**第 6 章**：
- **长文本超时 `Stream failed: CANCEL`** → 底层 OkHttp 读超时太短，把 LLM 生成的正常停顿误判成卡死。解法：自定义 `WebClient.Builder`/`RestClient.Builder` 设足够长读超时（180s）。
- **`ErrorCallbackNotImplemented` 刷屏** → `subscribe()` 无参没接错误。解法：`subscribe(onNext, onError)`，错误转 `SESSION_FAILED` 事件。
- 健康检查把 Redis 探测设成「必须 UP 否则应用拒绝启动」→ 错。健康检查只反映状态，不该影响启动。

**第 7 章**：
- 心跳 `Flux.interval` 忘了 `takeUntilOther` 停 → 写作完成后 SSE 连接不关。必须事件流结束就停心跳。
- Resilience4j `@Retry` 加在 private 方法上 → AOP 不生效，没重试。必须 public，且避免自调用（自调用绕过代理）。
- 归档 `archive` 没 try/catch → JDBC 慢/挂会拖垮事件流主路径。归档必须容错（吞异常 + 打点）。

**第 8 章**：
- **续传喂了脱敏输入给 LLM** → 生成质量下降。脱敏版只用于展示/合规，续传要用加密原文（分两列存）。
- **`kill -9` 后会话卡在 ACTIVE** → `@PreDestroy` 不触发，要靠 updated_at 超时定时任务兜底回收僵尸。
- **幂等键用"先 GET 判空再 SET"** → 并发竞态，两个请求都判空都 SET。必须用 Redis `SETNX`（setIfAbsent）原子占坑。
- **回放/列表接口漏了 tenantId 校验** → 知道 sessionId 就能看别人会话。所有历史接口必须校验 tenantId 双向匹配。
- **续传段事件和历史事件混在一起** → 回放时分不清哪段是续传。续传事件 data 里带 `resume: true` 标记。

### A.4 演进全景图

```mermaid
flowchart TD
    S0[第0章 黑盒 Agent] -->|事故: 看不到内部| S1
    S1[第1章 三层骨架<br/>采集-总线-SSE] -->|投诉: 事件丢/乱/漏| S2
    S2[第2章 可靠性<br/>分级落库+序号+回放] -->|需求: 调工具算钱| S3
    S3[第3章 工具与token<br/>Observation订阅+旁路统计] -->|体量: 上多实例| S4
    S4[第4章 规模化<br/>状态外置+分片+广播] -->|业务: 对外SaaS| S5
    S5[第5章 治理<br/>多租户+归因+配额] -->|体量: 关键链路| S6
    S6[第6章 灾备<br/>降级+健康+超时/错误+SRE] -->|对外运营: 真实流量| S7
    S7[第7章 运营事故<br/>心跳+重试+取消+连接治理+归档] -->|工单: 查历史/续中断| S8
    S8[第8章 持久化与历史<br/>状态机+回放+列表+续传+治理配套]
```

> **终极纪律**：走到你当前体量够用的阶段就停。日活不过千停第 3 章；不多实例停第 3 章；不对外卖停第 3 章。**每多走一章，都要能说出「是什么痛点逼我走的」——说不出来就别走**。

### A.5 完整 pom.xml（第 8 章结束时）

照着这个最终版核对依赖，缺啥补啥：

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

    <groupId>com.example.aobs</groupId>
    <artifactId>ai-writing-assistant</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencies>
        <!-- WebFlux：HTTP 接口 + SSE（第0章） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <!-- Spring AI 调 DeepSeek（第0章） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <!-- Actuator：ObservationRegistry + 健康检查 + 监控端点（第3章引入，第6章用满） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!-- Redis：关键事件落库 + 跨实例广播 + 配额（第2章引入） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- 配置元数据（第0章，让 yaml 有提示） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- Resilience4j：LLM 重试/降级（第6章 6.2.6） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>2.2.0</version>
        </dependency>
        <!-- 事件归档：JDBC + H2（第2章 2.2.6；生产换 ES/ClickHouse） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>
    </dependencies>

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

> 单元测试依赖（`spring-boot-starter-test`）本文未用（页面验证为主），团队按需加。

### A.6 完整 application.yaml（第 8 章结束时）

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}          # 只读环境变量，别进 git
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-chat
        temperature: 0.7
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  datasource:                                  # 事件归档 H2（第7章 7.5；生产换 ES/ClickHouse）
    url: jdbc:h2:file:./data/aobs-events
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always                             # 启动建表（IF NOT EXISTS）

server:
  port: ${SERVER_PORT:8080}                 # 多实例用环境变量改端口

management:                                  # Actuator 端点（第6章）
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

resilience4j:                                # LLM 重试/降级（第7章 7.2）
  retry:
    instances:
      llmCall:
        max-attempts: 3
        wait-duration: 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - java.net.SocketTimeoutException

server:
  shutdown: graceful                         # 优雅停机（A.8）
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

logging:
  level:
    org.springframework.ai: info
```

> **超时不在这里配**：长文本超时配在底层 `WebClient.Builder`/`RestClient.Builder` Bean 上（见 6.2.4），不是 yaml 属性——Spring AI 的 openai starter 没有直接的 read-timeout 属性键。

### A.7 AgentEvent 字段演进时间线

`AgentEvent` 随章节演进，字段逐步增加。下表是全局视角，方便你到后面章节时核对"现在 record 长什么样"：

| 章节 | 新增字段 | record 签名（关键字段） | 构造方式 |
|------|---------|----------------------|---------|
| 第 1 章 | — | `(EventContent type, sessionId, timestamp, data)` | `builder()...build()` / `of(type, sessionId, data)` |
| 第 2 章 | `criticality` | `(..., data, criticality)` | builder 加 `criticality()`（不填按 type 推断） |
| 第 2 章 | `sequence` | `(..., criticality, sequence)` | 不进 builder，EventBus emit 时分配 |
| 第 5 章 | `tenantId`, `userId`, `agentVersion` | `(type, sessionId, tenantId, userId, agentVersion, timestamp, data, ...)` | builder 加 `tenantId()/userId()/agentVersion()` |

> **为什么用 builder 而不是每章改构造器签名**：record 字段随章节长，每加一个字段，所有 `new AgentEvent(...)` 调用点都要改参数列表、还容易传错位置。builder 是"按名字赋值"——加新字段时老调用点不用改（新字段有默认值）。**只有需要归因的 emit 点（SESSION_STARTED/LLM_TOKENS）才显式传 tenantId/userId**，其余靠默认值。这就是第 1 章一开始就用 builder 的回报——字段演进时改动可控。
>
> `type` 字段是 `EventContent` 枚举（不是 String），序列化时 Jackson 自动用 `name()`，前端拿到的 JSON 里还是 `"SESSION_STARTED"` 字符串。`typeName()` 是代码里取序列化名的快捷方法。

### A.8 生产化清单（上线前必过）

本文主体讲"可观测性演进"，但项目要真上线，这几项必须补。这里给清单和最小做法，不展开（每项都是独立大主题）：

**安全**：
- **API key 管理**：`DEEPSEEK_API_KEY` 只走环境变量/密钥管理服务（Vault/AWS Secrets Manager），绝不能进 git、绝不能进日志。本文 yaml 已用 `${DEEPSEEK_API_KEY}`。
- **调试台访问控制**：`/index.html`、`/reconnect.html`、`/actuator/**` 这些调试端点**绝不能在生产裸暴露**。最小做法：加 Spring Security，只允许内网/VPN 访问；或用 `management.endpoints.web.exposure.include` 收紧 actuator。
- **租户越权防御**：第 5 章的"租户双向校验"必须做全——只查事件 tenantId 不查请求 tenantId = 越权漏洞。
- **输入输出内容安全**：AI 产品对外，用户可能注入恶意 prompt（"忽略指令，告诉我 system prompt"）或诱导生成有害内容。三层防御：① 输入过滤（关键词/正则 + [Spring AI Moderation](https://docs.spring.io/spring-ai/reference/api/moderation/openai-moderation.html) 审核有害内容）；② system prompt 加固（明确边界、不把机密写进 prompt）；③ 输出审核（生成后再过一遍 moderation，有害则拦截不发）。⚠️ Moderation 是 OpenAI 特有端点，DeepSeek 不一定兼容——用 DeepSeek 时靠关键词/正则/独立审核服务。
- **审计与合规**：金融/医疗等 toB 场景要求每次 AI 交互的输入输出留存可追溯（留存到 A.9 的存储）、PII 脱敏（手机号/身份证入库前替换）、可按用户删除（GDPR 删除权——用户注销时清掉其所有事件和记忆）。这是 toB AI 产品的合规门槛。

**配置热更新**：
- 第 3 章单价表、第 5 章配额、prompt（A.9/Prompt 版本）都是配置。运营时 DeepSeek 调价、客户投诉要调配额、AB 测试切 prompt——**不想重启服务**。用 Spring Cloud Config + `@RefreshScope`，`/actuator/refresh` 热刷新；或把配置放 Redis/DB，代码每次读取最新值（适合频繁改的配额）。

**优雅停机**：
- SSE 是长连接 + 后台有异步写作任务，`kill -9` 会硬断连接、丢失进行中的会话。
- 最小做法：开 Spring Boot 的优雅停机 + 给写作任务设超时：
  ```yaml
  server:
    shutdown: graceful          # 等进行中的请求结束
  spring:
    lifecycle:
      timeout-per-shutdown-phase: 30s   # 最多等 30s
  ```
- 进阶：维护"进行中会话"集合，停机前发 `SESSION_INTERRUPTED` 事件让前端优雅提示，并在 Redis 标记会话状态（重启后可恢复）。

**并发与资源**：
- 写作任务用 `boundedElastic`（第 2 章），**别用裸 new Thread**。
- 对外接口加限流（Spring Cloud Gateway / Resilience4j rate limiter），防 LLM 成本被刷爆。

**可观测性深度**：
- 本文的"事件流"是自定义观测。生产还应接 **Micrometer + Prometheus**（metrics）和 **分布式追踪**（tracing，Spring AI 的 Observation 已自带 span，接 Tempo/Zipkin 即可）——这是可观测性三件套（metrics/tracing/logs）的后两件。
- **traceId 和 sessionId 要关联**：这是可观测三件套联动的关键。sessionId 是业务会话维度（本文的事件流用它），traceId 是分布式追踪维度（跨服务的请求链路用它）。两者不能各管各的——否则在追踪系统按 traceId 查到一条链路，却对不上是哪个用户的哪次会话。企业级做法：把 sessionId 作为 **baggage**（分布式上下文字段）放进追踪，或事件归档时同时存 traceId（`event_archive` 加 trace_id 列）。这样任意一个维度都能跳查到另一个——这是"可观测性"真正"可查"的闭环。

**成本告警闭环**：
第 5 章算了成本指标（`tenant_daily_cost_usd`），但要"烧钱时主动知道"，得接告警。Prometheus alertmanager 规则示例（`alerts.yml`）：
```yaml
groups:
  - name: llm-cost
    rules:
      - alert: TenantCostSpike              # 单租户日成本超 $10 告警
        expr: tenant_daily_cost_usd > 10
        for: 5m
        annotations:
          summary: "租户 {{ $labels.tenant }} 日成本 {{ $value }} 超阈值"
      - alert: LlmRetryRateHigh             # LLM 重试率 >20%，说明上游不稳定
        expr: rate(llm_retry_total[5m]) / rate(llm_call_total[5m]) > 0.2
        for: 10m
        annotations:
          summary: "LLM 重试率过高，检查 429/超时"
```
告警经 alertmanager 路由到 Slack/钉钉/邮件。**成本治理不只是算账，更是异常感知**——异常成本飙升（bug 导致死循环调 LLM、租户被刷）要能在几分钟内发现，而不是月底账单出来才知道。

> 这份清单是"演进到能上线"的边界。本文教你把可观测骨架搭起来，这份清单教你把它守好。

### A.9 上下文窗口与记忆管理（多轮对话必踩）

本文是单步 workflow（大纲→草稿→润色，每步独立），没真正用记忆。但企业级 AI 产品多是**多轮对话**（累积历史），这时必踩"上下文窗口超限"——这是 LLM 固有限制，不是 bug。

**问题**：多轮对话历史越积越长，超过模型的上下文窗口（DeepSeek 64K/128K tokens），API 报错或输出退化。

**Spring AI 默认方案的局限**：`MessageWindowChatMemory.maxMessages(20)` 按**消息条数**裁剪，不按 token。一条长消息可能几千 token，20 条可能远超窗口；反之 20 条短消息又浪费窗口。[Issue #2923](https://github.com/spring-projects/spring-ai/issues/2923) 在讨论 token 级裁剪，目前要自定义。

**企业级做法（三选一，按场景）**：

| 方案 | 适用 | 做法 |
|------|------|------|
| **按 token 裁剪** | 精确控制成本/窗口 | 自定义 `ChatMemory`，每次用 `JTokkitTokenCountEstimator` 算 token，超阈值从最旧开始删，直到塞得下 |
| **摘要压缩** | 长对话要保留全局上下文 | 旧消息定期让 LLM 总结成一段摘要（SystemMessage），替换原始历史 |
| **混合** | 生产主流 | 保留最近 N 条原文 + 之前的摘要，兼顾近期细节和长期记忆 |

**按 token 裁剪的最小实现**（伪代码）：
```java
public class TokenAwareChatMemory implements ChatMemory {
    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();
    private final long maxTokens;
    // ... add 时：如果 总token > maxTokens，从最旧的非系统消息开始删，直到塞得下
}
```

> **为什么必须 token 级**：按条数裁剪在"消息长度差异大"的真实对话里不可靠（一条代码粘贴就几千 token）。AI 产品付费按 token，记忆管理也必须按 token——否则要么超限报错、要么浪费窗口多花钱。
>
> **和成本的关系**：每次请求的历史 token 都计费。记忆管理不只是"不报错"，更是**成本控制**——裁掉无关历史 = 少付输入 token 费。这是 AI 后端和普通后端的又一区别。

### A.10 DeepSeek 风对话页面（覆盖第 1-8 章）

放 `src/main/resources/static/index.html`，浏览器打开 `http://localhost:8080/index.html`。

**布局参照 DeepSeek 官网**（`chat.deepseek.com` 的对话骨架）：**左侧会话历史栏 + 右侧对话区 + 底部输入框**。本文只做对话功能，不做多模态/文件上传。一个页面覆盖全部章节——左侧栏整合会话历史（第 8 章回放/续传）+ 租户切换（第 5 章）；右侧对话区实时展示事件流（CONTENT_DELTA 打字机 + 工具/token 面板）。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI 写作助手</title>
    <style>
        :root {
            --bg: #f7f7f8; --surface: #fff; --sidebar: #fafafa; --border: #ececec;
            --text: #1a1a1a; --text-2: #6b6b6b; --muted: #b0b0b0;
            --accent: #1a1a1a; --hover: #f0f0f0;
            --green: #00b96b; --orange: #e67e22; --red: #e53935;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        html, body { height: 100%; }
        body { font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
               background: var(--bg); color: var(--text); font-size: 15px; line-height: 1.8;
               display: flex; height: 100vh; overflow: hidden; }

        /* 左侧会话栏（DeepSeek 式） */
        #sidebar { width: 260px; background: var(--sidebar); border-right: 1px solid var(--border);
                   display: flex; flex-direction: column; flex-shrink: 0; }
        #sidebar .top { padding: 12px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 8px; }
        #new-chat { background: var(--accent); color: #fff; border: none; border-radius: 8px;
                    padding: 10px; font-size: 14px; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; }
        #new-chat:hover { opacity: 0.9; }
        #tenant-row { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-2); }
        #tenant { flex: 1; border: 1px solid var(--border); background: var(--surface); border-radius: 6px;
                  padding: 4px 8px; font-size: 13px; color: var(--text); }
        #sessions { flex: 1; overflow-y: auto; padding: 8px; }
        .session-item { padding: 8px 10px; border-radius: 6px; cursor: pointer; font-size: 13px;
                        color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-bottom: 2px; }
        .session-item:hover { background: var(--hover); }
        .session-item.active { background: var(--hover); color: var(--text); font-weight: 500; }
        .session-item .badge { font-size: 11px; padding: 1px 5px; border-radius: 4px; margin-left: 4px; }
        .session-item .badge.interrupted { background: #fff3e0; color: var(--orange); }
        .session-item .badge.failed { background: #fdecea; color: var(--red); }
        .session-item .resume-btn { float: right; font-size: 11px; color: var(--accent); display: none; }
        .session-item:hover .resume-btn { display: inline; }

        /* 右侧主区 */
        #main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
        #header { padding: 12px 24px; border-bottom: 1px solid var(--border); background: var(--surface);
                  display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }
        #header .title { font-size: 16px; font-weight: 600; }
        #header .sub { color: var(--muted); font-size: 13px; margin-left: 8px; }
        #header .cost { font-size: 13px; color: var(--text-2); }
        #header .cost b { color: var(--orange); }

        #chat { flex: 1; overflow-y: auto; padding: 32px 0; }
        .msg { max-width: 720px; margin: 0 auto; padding: 0 24px; }
        .user { display: flex; justify-content: flex-end; margin-bottom: 16px; }
        .user .bubble { background: var(--accent); color: #fff; padding: 10px 16px;
                        border-radius: 12px 12px 4px 12px; max-width: 75%; word-break: break-word; }
        .assistant { margin-bottom: 20px; }
        .assistant .bubble { background: var(--surface); padding: 14px 18px; border: 1px solid var(--border);
                            border-radius: 12px; word-break: break-word; min-height: 20px; }
        .assistant .bubble.empty { color: var(--muted); font-style: italic; }
        .process { max-width: 720px; margin: 0 auto 16px; padding: 0 24px; }
        .process summary { cursor: pointer; color: var(--muted); font-size: 13px; padding: 4px 0; user-select: none; }
        .step { background: var(--surface); border-left: 2px solid var(--muted); margin: 4px 0; padding: 6px 12px;
                border-radius: 0 8px 8px 0; font-size: 13px; color: var(--text-2); }
        .step.tool { border-left-color: var(--green); } .step.tool .label { color: var(--green); }
        .step.tokens { border-left-color: var(--orange); } .step.tokens .label { color: var(--orange); }
        .step.failed { border-left-color: var(--red); } .step.failed .label { color: var(--red); }
        .step.resume-marker { border-left-color: var(--orange); color: var(--orange); font-style: italic; }
        .step .label { font-weight: 600; margin-right: 6px; }
        .step .kv { color: var(--muted); margin-right: 8px; } .step .kv b { color: var(--text-2); }

        #bar { background: var(--surface); border-top: 1px solid var(--border); padding: 12px 24px; flex-shrink: 0; }
        #input-wrap { max-width: 720px; margin: 0 auto; display: flex; gap: 8px; align-items: center;
                      background: var(--bg); border-radius: 22px; padding: 4px 4px 4px 18px; border: 1px solid var(--border); }
        #prompt { flex: 1; border: none; background: transparent; outline: none; font-size: 15px; padding: 10px 0; }
        #send { background: var(--accent); color: #fff; border: none; width: 34px; height: 34px;
                border-radius: 50%; cursor: pointer; font-size: 16px; flex-shrink: 0; }
        #send:disabled { background: #d0d0d0; }
        #status { text-align: center; color: var(--muted); font-size: 12px; padding: 4px 0; }
    </style>
</head>
<body>
<aside id="sidebar">
    <div class="top">
        <button id="new-chat" onclick="newChat()">+ 新建对话</button>
        <div id="tenant-row">租户
            <select id="tenant" onchange="loadSessions()">
                <option value="">（无）</option>
                <option value="tenantA">租户A</option>
                <option value="tenantB">租户B</option>
            </select>
        </div>
    </div>
    <div id="sessions"></div>
</aside>

<main id="main">
    <header id="header">
        <div><span class="title">AI 写作助手</span><span class="sub">可观测调试台</span></div>
        <div class="cost">本次成本：<b id="costVal">$0.0000</b></div>
    </header>
    <div id="chat"></div>
    <div id="status">输入主题，回车发送</div>
    <div id="bar"><div id="input-wrap">
        <input id="prompt" placeholder="输入主题，如：AI 的未来" value="AI 的未来">
        <button id="send" onclick="send()">➤</button>
    </div></div>
</main>

<script>
    let sending = false, controller = null, currentSession = null, totalCost = 0;
    document.getElementById('prompt').addEventListener('keydown', e => { if (e.key === 'Enter') send(); });

    // 新建对话：清空右侧，生成新 sessionId（不入历史列表，直到发送后服务端落库）
    function newChat() {
        if (sending) return;
        currentSession = 's-' + Date.now();
        document.getElementById('chat').innerHTML = '';
        totalCost = 0; document.getElementById('costVal').textContent = '$0.0000';
        document.getElementById('status').textContent = '已开新对话';
        document.querySelectorAll('.session-item').forEach(el => el.classList.remove('active'));
    }

    // 加载左侧历史会话列表（第 8 章 /api/session/list）
    async function loadSessions() {
        const tenant = document.getElementById('tenant').value;
        const resp = await fetch('/api/session/list?tenantId=' + encodeURIComponent(tenant || ''));
        let items = []; try { items = (await resp.json()).items || []; } catch(e) {}
        const box = document.getElementById('sessions'); box.innerHTML = '';
        for (const s of items) {
            const div = document.createElement('div');
            div.className = 'session-item' + (s.sessionId === currentSession ? ' active' : '');
            const badge = s.status === 'INTERRUPTED' ? '<span class="badge interrupted">中断</span>'
                        : s.status === 'FAILED' ? '<span class="badge failed">失败</span>' : '';
            const canResume = s.status === 'INTERRUPTED' || s.status === 'FAILED';
            div.innerHTML = '<span>' + (s.inputText || '(空)').slice(0,20) + '</span>' + badge
                + (canResume ? '<span class="resume-btn" onclick="event.stopPropagation();resume(\'' + s.sessionId + '\')">续传</span>' : '');
            div.onclick = () => replay(s.sessionId);
            box.appendChild(div);
        }
    }

    // 回放历史会话（第 8 章 /api/session/replay）——把归档事件流渲染到右侧
    async function replay(sessionId) {
        if (sending) return;
        currentSession = sessionId;
        newChat(); currentSession = sessionId;
        const tenant = document.getElementById('tenant').value;
        document.getElementById('status').textContent = '回放历史...';
        const resp = await fetch('/api/session/replay/' + sessionId + '?tenantId=' + encodeURIComponent(tenant || ''));
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
                handleFrame(buffer.slice(0, idx), null, null, true);
                buffer = buffer.slice(idx + 2);
            }
        }
        document.getElementById('status').textContent = '回放完成';
        loadSessions();
    }

    // 续传（第 8 章 /api/session/resume）——从断点接着跑
    async function resume(sessionId) {
        if (sending) return;
        currentSession = sessionId;
        const tenant = document.getElementById('tenant').value;
        document.getElementById('status').textContent = '续传中...';
        const resp = await fetch('/api/session/resume/' + sessionId + '?tenantId=' + encodeURIComponent(tenant || ''),
            { method: 'POST', headers: { 'X-Tenant-Id': tenant || '' } });
        await pump(resp);
        loadSessions();
    }

    // 发送新消息（第 1 章 /api/obs/article，带租户 + 幂等键）
    async function send() {
        if (sending) return;
        const input = document.getElementById('prompt');
        const prompt = input.value.trim();
        if (!prompt) return;
        input.value = '';
        sending = true;
        document.getElementById('send').disabled = true;

        if (!currentSession) currentSession = 's-' + Date.now();
        const sessionId = currentSession;
        const tenant = document.getElementById('tenant').value;

        const chat = document.getElementById('chat');
        const u = document.createElement('div'); u.className = 'msg user';
        u.innerHTML = '<div class="bubble"></div>';
        u.querySelector('.bubble').textContent = prompt;
        chat.appendChild(u);

        const proc = document.createElement('details'); proc.className = 'process';
        proc.innerHTML = '<summary>执行过程</summary>'; proc.open = true;
        chat.appendChild(proc);

        const a = document.createElement('div'); a.className = 'msg assistant';
        a.innerHTML = '<div class="bubble empty">生成中...</div>';
        chat.appendChild(a);
        chat.scrollTop = chat.scrollHeight;

        const headers = { 'sessionId': sessionId, 'Accept': 'text/event-stream',
                          'Idempotency-Key': sessionId + '-' + Date.now() };
        if (tenant) { headers['X-Tenant-Id'] = tenant; headers['X-Tenant-Key'] = 'demo-key-' + tenant; }

        controller = new AbortController();
        try {
            const resp = await fetch('/api/obs/article?prompt=' + encodeURIComponent(prompt), { headers, signal: controller.signal });
            await pump(resp, proc, a);
            document.getElementById('status').textContent = '完成';
            loadSessions();   // 刷新左侧列表（新会话入库）
        } catch (e) {
            if (e.name !== 'AbortError') document.getElementById('status').textContent = '连接断开';
        }
        sending = false;
        document.getElementById('send').disabled = false;
    }

    // 通用：读 SSE 流，按帧分发（实时/回放/续传共用）
    async function pump(resp, proc, assistantEl) {
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
            let idx;
            while ((idx = buffer.indexOf('\n\n')) >= 0) {
                handleFrame(buffer.slice(0, idx), proc, assistantEl, false);
                buffer = buffer.slice(idx + 2);
            }
        }
    }

    // 帧处理：实时模式（写到传入的 proc/assistantEl）vs 回放模式（追加到聊天区）
    function handleFrame(frame, proc, assistantEl, isReplay) {
        let type = '', dataStr = '';
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            if (line.startsWith('data:')) dataStr += line.slice(5).trim();
        }
        if (!type) return;
        let data = {}; try { data = JSON.parse(dataStr).data || JSON.parse(dataStr); } catch(e) { data = {}; }

        // 回放模式：CONTENT_DELTA 也追加到聊天区（用临时气泡）；STEP_* 进折叠面板
        if (isReplay && !proc) {
            if (type === 'CONTENT_DELTA') {
                let a = document.querySelector('#chat .replay-assistant');
                if (!a) {
                    a = document.createElement('div'); a.className = 'msg assistant replay-assistant';
                    a.innerHTML = '<div class="bubble"></div>';
                    document.getElementById('chat').appendChild(a);
                }
                a.querySelector('.bubble').textContent += (data.text || '');
            } else if (type === 'STEP_START' || type === 'STEP_END' || type === 'TOOL_CALL' || type === 'LLM_TOKENS' || type === 'STEP_RESUMED') {
                let p = document.querySelector('#chat .replay-proc');
                if (!p) {
                    p = document.createElement('details'); p.className = 'process replay-proc'; p.open = true;
                    p.innerHTML = '<summary>执行过程（回放）</summary>';
                    document.getElementById('chat').appendChild(p);
                }
                renderStep(p, type, data);
            }
            document.getElementById('chat').scrollTop = document.getElementById('chat').scrollHeight;
            return;
        }

        // 实时模式
        if (type === 'CONTENT_DELTA') {
            const bubble = assistantEl.querySelector('.bubble');
            bubble.classList.remove('empty');
            if (data.text) bubble.textContent += data.text;
        } else if (type !== 'SESSION_COMPLETED' && type !== 'READY') {
            renderStep(proc, type, data);
            if (type === 'LLM_TOKENS') {
                totalCost += Number(data.costUsd || 0);
                document.getElementById('costVal').textContent = '$' + totalCost.toFixed(4);
            }
        }
        document.getElementById('chat').scrollTop = document.getElementById('chat').scrollHeight;
    }

    function renderStep(proc, type, data) {
        const step = document.createElement('div');
        let cls = '', label = type, kvs = [];
        if (type === 'TOOL_CALL') { cls = 'tool'; label = '🔧 ' + (data.tool||''); kvs = [['参数',data.args],['返回',data.result]]; }
        else if (type === 'LLM_TOKENS') { cls = 'tokens'; label = '💰 ' + (data.totalTokens||0) + ' tokens'; }
        else if (type === 'SESSION_FAILED') { cls = 'failed'; label = '❌ 失败'; kvs = [['error',data.error]]; }
        else if (type === 'STEP_RESUMED') { cls = 'resume-marker'; label = '↻ 从第 ' + (data.fromStep||0) + ' 步续传'; }
        else if (type === 'STEP_START') { label = '▸ 第 ' + (data.step||0) + ' 步开始'; }
        else if (type === 'STEP_END') { label = '◂ 第 ' + (data.step||0) + ' 步完成'; kvs = [['输出',(data.output||'').slice(0,40)]]; }
        step.className = 'step ' + cls;
        let html = '<span class="label">' + label + '</span>';
        for (const [k,v] of kvs) { if (v != null && v !== '') html += '<span class="kv">' + k + ': <b>' + v + '</b></span>'; }
        step.innerHTML = html;
        proc.appendChild(step);
    }

    // 启动：开新对话 + 加载历史
    newChat();
    loadSessions();
</script>
</body>
</html>
```

> **布局参照说明**：本页参照 DeepSeek 官网（`chat.deepseek.com`）的对话骨架——左侧会话历史栏（新建对话入口 + 历史会话列表）+ 右侧对话区（标题/成本 + 消息流 + 底部输入）。**只做对话功能**，不做官网的多模态/文件上传。DeepSeek 的视觉特征这里都对齐了：极简白底（#f7f7f8）、深色主色（#1a1a1a，不用鲜艳蓝）、窄列（720px 消息区）、大留白、细边框、用户气泡深色右对齐、助手白底左对齐。
>
> **⚠️ 诚实声明**：本文未直接抓取 chat.deepseek.com（环境限制无法 WebFetch），布局和配色基于 DeepSeek 公知的极简风格设计。若官方改版或有偏差，以你实际看到的官网为准微调 CSS 变量（`--bg`/`--accent` 等）。
>
> **一个页面覆盖全部章节**：
> - **第 1-3 章**：右侧对话区实时展示事件流（CONTENT_DELTA 打字机 + STEP_START/END + TOOL_CALL + LLM_TOKENS）。
> - **第 5 章**：左侧栏租户下拉，切换后请求带 `X-Tenant-Id` + `X-Tenant-Key`（第 8 章 8.2.11 鉴权）。
> - **第 7 章**：成本实时累加（右上角 `LLM_TOKENS.costUsd`）。
> - **第 8 章**：左侧会话列表（调 `/api/session/list`）、点会话回放（`/api/session/replay`）、中断/失败的点"续传"（`/api/session/resume`）、新对话带 `Idempotency-Key`（第 8 章 8.2.6 幂等）。
>
> **实时/回放/续传共用一套帧解析**：三种模式都是 SSE 流，前端 `handleFrame` 统一处理（回放模式 `isReplay=true` 把事件追加到聊天区，实时模式写传入的气泡）。**统一 SSE 协议让"实时看/回放看/续传看"用同一套前端代码**——这是事件流架构的回报。

> **`history.html` 不再单独需要**：A.10 这个 `index.html` 已经把第 8 章 8.3 的历史列表/回放/续传全整合进左侧栏了。第 8 章 8.3 提到的 `history.html` 可作为"纯运营后台列表页"的简化版保留，或直接用 `index.html` 代替——两者功能重叠时优先用 `index.html`（用户和运营都靠它）。

### A.11 历史消息编辑与重放（产品能力，不是可观测）

> **边界声明**：本节是**AI 产品功能**，不是可观测性。主体 1-8 章讲"把 Agent 跑起来、看得见、数据管得住"；这一节讲"用户能改历史消息、从改动点重新生成"——这是 ChatGPT 的 edit message 能力，属于对话产品。放附录是因为它常被和"历史回放/续传"混淆，但本质不同。

**和第 8 章三个能力的区别**（容易混）：

| 能力 | 干什么 | 改不改变历史 |
|------|--------|------------|
| 历史回放（8.2.4） | 把存档事件原样重放给前端看 | 不改，只读 |
| 断点续传（8.2.9） | 中断后从断点接着跑**未完成的步骤** | 不改历史，只续未来 |
| **历史编辑（本节）** | 改某条已存在的消息，从那里**重新生成后续** | **改了**——产生新分支 |

**核心难点：改历史 = 产生分支，不是覆盖**。用户改了第 2 条消息，从第 2 条重新生成——原来的第 3、4 条不能删（用户可能想对比），要保留为"旧分支"。所以历史编辑天然是**树状/图状的会话结构**，不是线性的。

**最小实现思路**（不展开代码）：

```
会话树：
  msg1(用户:写AI) → msg2(AI:大纲) → msg3(用户:草稿) → msg4(AI:文章v1)
                        │
                        └─编辑msg3为"写800字" → msg3'(用户:写800字) → msg4'(AI:文章v2)
  两个叶子：msg4(原) 和 msg4'(编辑后)，用户可切换查看
```

数据模型：消息表加 `parent_msg_id`（指向上一条），形成树。编辑 = 新建一条 `parent_msg_id = msg2` 的消息，从它重新生成。这样原分支（msg3→msg4）和新分支（msg3'→msg4'）都保留。

**和 ChatMemory 的关系**：编辑后重新生成，要把"从 msg1 到 msg3' 的历史"喂给 LLM（不是到 msg4）。所以 ChatMemory（A.9）要支持"从某个节点截取历史"，而不是"取全部"。这是编辑功能对记忆模块的要求。

**本文为什么不做完整实现**：① 它是产品功能，撑破可观测文档定位；② 树状会话 + 分支切换的前端复杂度高，和"学可观测"无关；③ 它依赖 ChatMemory 改造（A.9 已是附录）。真要做的话，建消息树表 + 改 ChatMemory 截取逻辑 + 前端分支切换，是一个独立小项目。

> **可观测视角的提示**：即使做编辑功能，每次编辑后的重新生成**仍走本文的事件流体系**（SESSION_STARTED → ... → SESSION_COMPLETED），事件照常归档。编辑产生的新会话在历史列表里是一个新 session（或同 session 的新分支），回放/续传/脱敏全都复用——**可观测基础设施对产品功能是透明的，产品功能长在上面，不用改地基**。这就是 1-8 章搭的骨架的价值。

### A.12 A/B 测试与 prompt 分流（产品迭代基础设施）

> **边界声明**：产品迭代能力，不是可观测本身。但 A.7 讲了 promptVersion 灰度对比的**概念**，这里补"分流怎么做"。

**目标**：两个 prompt 版本（v1/v2）各跑 50% 流量，按版本聚合质量指标对比，决定是否全量切 v2。

**分流**（按 userId 稳定 hash，保证同一用户总进同一桶）：

```java
@Component
public class AbRouter {
    /** 按 userId + 实验名 hash 分桶。返回桶名（如 "v1"/"v2"）。 */
    public String bucket(String userId, String experiment, List<String> buckets) {
        int h = (userId + ":" + experiment).hashCode();
        int idx = Math.floorMod(h, buckets.size());
        return buckets.get(idx);
    }
}
```

```java
// ChainingService.run 里，根据分桶选 prompt 版本
String bucket = abRouter.bucket(ctx.userId(), "outline-prompt", List.of("v1", "v2"));
String system = bucket.equals("v2") ? OUTLINE_V2 : OUTLINE_V1;
// 事件带 promptVersion（第 5 章的 agentVersion 字段，或单独加 promptVersion）
```

**指标聚合**：消费者订阅 EventBus，按 `promptVersion` 聚合——成功率、平均 token、平均耗时、（接 A.13 的）用户反馈分布。对比两个桶的指标，决定 v2 是否更好。

**为什么不进主体**：分流逻辑是产品迭代工具，和"可观测性"正交。可观测提供数据（事件带 promptVersion），A/B 是数据的**用法**之一。本文把"带版本字段"做进事件（第 5 章），分流和聚合留给产品团队按需搭。

> **可观测是 A/B 的地基**：没事件流带 promptVersion，A/B 无从聚合。本文的归档 + 版本字段让 A/B 分析可以**事后离线跑**（不用实时聚合）——查归档按 promptVersion 分组统计即可。这是"可观测数据复用"的又一例。

### A.13 内容质量评估与用户反馈（可观测向质量的延伸）

> **边界声明**：半产品半可观测——"生成质量好不好"是产品关心的事，但"质量可量化、可对比"是可观测的延伸（从"过程可见"到"结果可评"）。

**两个质量信号源**：

1. **用户显式反馈**（便宜、主观）：用户点 👍/👎。加一个 `SESSION_FEEDBACK` 事件：

```java
// EventContent 加 SESSION_FEEDBACK
// 前端在生成完成后显示 👍/👎，点击后 POST /api/session/{id}/feedback?score=1（或 -1）
// 后端发事件 + 落库（session_record 加 feedback_score 列）
eventBus.emit(AgentEvent.of(EventContent.SESSION_FEEDBACK, sessionId,
        Map.of("score", score, "promptVersion", agentVersion)));
```

2. **LLM-as-judge**（贵、客观）：用另一个 LLM 给生成结果打分。成本高（每次额外一次 LLM 调用），通常只对**采样**的会话做（比如 10%）：

```java
// 生成完成后，10% 概率触发评审
if (random.nextDouble() < 0.1) {
    String judgePrompt = "给以下文章打分(1-5)，从流畅度/结构/准确性评价：" + output;
    int score = Integer.parseInt(chatClient.prompt().user(judgePrompt).call().content());
    eventBus.emit(AgentEvent.of(EventContent.QUALITY_SCORE, sessionId,
            Map.of("score", score, "judge", "llm")));
}
```

**指标聚合**：按 promptVersion（A.12）聚合反馈分布、平均 judge 分。v1 的 👍 率 62% vs v2 的 71% → v2 更好，全量切 v2。

**本文为什么只给思路**：① LLM-as-judge 成本高、prompt 要调，是独立主题；② 质量评估的"金标准"因业务而异（写作看流畅、客服看解决率、代码看能跑），没有通用实现。本文把"反馈事件"这个采集点给出，聚合和分析留给业务。

> **质量可观测是可观测的"第三维度"**：第 1-3 章是"过程可观测"（发生了什么）、第 5 章是"成本可观测"（花了多少）、A.13 是"质量可观测"（好不好）。三者都建在同一套事件流上——**一套采集，多维分析**。这是事件驱动可观测架构的终极价值：数据采一次，过程/成本/质量/A-B 全能分析。

---

## 相关文档

- [33-Agent子过程实时可见性方案](./33-Agent子过程实时可见性方案.md) —— 完整架构蓝图（某项设计看细节时翻）
- [33a-Agent可观测性最小实战](./33a-Agent可观测性最小实战.md) —— 练手小项目（本文第 1 章的缩略版）
- [34-研究Agent与知识库实战](./34-研究Agent与知识库实战.md) —— 补充项目：学完本文，想做"自主研究 Agent + 知识库 + MCP"时看
- [04-流式响应与Reactor深度](./04-流式响应与Reactor深度.md) —— SSE、cold/hot Flux（第 1 章前置）
- [03-Tool调用](./03-Tool调用.md) / [03-Advisor链全解](./03-Advisor链全解.md) —— 工具与 Advisor（第 3 章前置）
- [15-可观测性与成本治理](./15-可观测性与成本治理.md) —— 成本计量、Langfuse（第 3、5 章）
- [26-AI工程的SRE实践](./26-AI工程的SRE实践.md) —— SLO、Runbook（第 6 章）

---

> **回到**：[`./00-目录索引.md`](./00-目录索引.md) · [`./33-Agent子过程实时可见性方案.md`](./33-Agent子过程实时可见性方案.md)

---

*全书完。按章节复现，你会得到一个从黑盒演进到生产级的企业可观测 Agent 项目。每一步的代码都基于已核实的 Spring AI 2.0 API，照抄能编译、能跑。*
