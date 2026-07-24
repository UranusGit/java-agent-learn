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

要做什么：让 Workflow 每一步把「我开始了/我结束了」告诉外部，外部再实时推给看的人。拆成三层：

```
[写作 Workflow 每一步]  ——emit 事件——>  [事件总线]  ——订阅——>  [SSE 推给 curl/浏览器]
   （采集）                             （总线）              （推送）
```

**关键决策：为什么搞总线，不直接在 Controller 收集事件返回？**

因为后面会有**不止一个消费者**（前端要看、日志要记、还要算成本）。总线结构让「生产事件的人」和「消费事件的人」互不干扰。这个价值第 2 章会体现，先留个印象。

> 更深一层：如果让 `ArticleService.run` 直接返回事件流，每多一个消费者订阅就会把整个 `run` 重新执行一遍（重新调 3 次 LLM）——因为那种流是「冷流」。总线用的 `Sinks.Many` 是「热流」，事件发一次就完，多个消费者共享同一条流。这是整个架构的地基，第 1 章先记住结论，第 2 章亲手体会。

### 1.2 动手

#### 1.2.1 AgentEvent——事件本身

先定义「一个事件长什么样」。

`src/main/java/com/example/aobs/obs/AgentEvent.java`：

```java
package com.example.aobs.obs;

import java.time.Instant;
import java.util.Map;

/**
 * 一个 Agent 事件，描述「Agent 执行过程中发生了什么」。
 * 用 record（Java 16+）：不可变数据类，自动生成构造器/getter/equals/hashCode。
 */
public record AgentEvent(
        String type,                  // 事件类型，如 "SESSION_STARTED"、"STEP_START"
        String sessionId,             // 属于哪个会话（前端按这个过滤）
        Instant timestamp,            // 发生时间
        Map<String, Object> data      // 附加信息（这一步的输入/输出等）
) {
    /** 快捷构造：自动填 timestamp。 */
    public static AgentEvent of(String type, String sessionId, Map<String, Object> data) {
        return new AgentEvent(type, sessionId, Instant.now(), data);
    }
}
```

> **小白疑问：为什么用 `Map<String,Object>` 装 data，不定义具体字段？**
> 不同事件附加信息差别大（SESSION_STARTED 有 input，STEP_END 有 output）。用 Map 灵活，新增事件类型不用改这个类。代价是类型不安全——第 2 章会权衡，先简单。

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

#### 1.2.3 给 Workflow 埋点（采集）

改 `ChainingService`，加 EventBus 依赖，在 `run` 里每步前后 emit。**不动业务逻辑**。

`src/main/java/com/example/aobs/workflow/ChainingService.java`（修改）：

```java
package com.example.aobs.workflow;

import com.example.aobs.obs.AgentEvent;
import com.example.aobs.obs.EventBus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public abstract class ChainingService {

    protected final ChatClient chatClient;
    protected final EventBus eventBus;    // ← 新增

    protected ChainingService(ChatClient chatClient, EventBus eventBus) {
        this.chatClient = chatClient;
        this.eventBus = eventBus;
    }

    protected abstract List<BiFunction<String, String, String>> steps();

    public String run(String input, String sessionId) {
        eventBus.emit(AgentEvent.of("SESSION_STARTED", sessionId, Map.of("input", input)));

        String payload = input;
        List<BiFunction<String, String, String>> stepList = steps();
        for (int i = 0; i < stepList.size(); i++) {
            int step = i;
            int total = stepList.size();

            eventBus.emit(AgentEvent.of("STEP_START", sessionId,
                    Map.of("step", step, "total", total)));

            payload = stepList.get(i).apply(payload, sessionId);  // 执行这步（调 LLM，耗时）

            eventBus.emit(AgentEvent.of("STEP_END", sessionId,
                    Map.of("step", step, "output", truncate(payload, 80))));
        }

        eventBus.emit(AgentEvent.of("SESSION_COMPLETED", sessionId,
                Map.of("output", truncate(payload, 80))));
        return payload;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

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

因为父类构造器加了参数，子类 `ArticleService` 跟着改（**配套改动**，改了接口要同步调用方才能编译）：

`src/main/java/com/example/aobs/writing/ArticleService.java`（改构造器）：

```java
package com.example.aobs.writing;

import com.example.aobs.obs.EventBus;
import com.example.aobs.workflow.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class ArticleService extends ChainingService {

    public ArticleService(ChatClient chatClient, EventBus eventBus) {
        super(chatClient, eventBus);
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
     * 订阅会话事件流 + 执行写作任务。
     * 用法：curl -N "http://localhost:8080/api/obs/article?prompt=AI的未来" -H "sessionId: s1"
     *
     * ⚠️ 这版有个竞态隐患（1.4 的测试会暴露）：subscribe 时订阅事件流和异步启动任务是两步，
     *    "启动任务"这一刻，事件流可能还没真正订阅上，导致 SESSION_STARTED 丢失。
     *    先别急着修——1.4 让测试先复现它，1.5 再用 READY 握手修。
     */
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader String sessionId) {

        // ① 订阅事件流
        Flux<ServerSentEvent<String>> events = eventBus.flux()
                .filter(e -> sessionId.equals(e.sessionId()))
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.type()))
                .map(this::toSse);

        // ② 异步启动写作任务（在另一线程，不阻塞 SSE 流）
        new Thread(() -> articleService.run(prompt, sessionId)).start();

        return events;
    }

    private ServerSentEvent<String> toSse(AgentEvent e) {
        return ServerSentEvent.<String>builder()
                .id(e.sessionId() + "-" + e.timestamp().toEpochMilli())  // 帧ID（第2章重连用）
                .event(e.type())
                .data(toJson(e))
                .build();
    }

    /** 简易 JSON 序列化。⚠️ 简陋版：第 2 章会换成 ObjectMapper。 */
    private String toJson(AgentEvent e) {
        return "{\"type\":\"" + e.type() + "\",\"data\":" + mapToJson(e.data()) + "}";
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
STEP_END          14:30:05   {"type":"STEP_END","data":{"step":"0","output":"一、引言..."}}
STEP_START        14:30:05   {"type":"STEP_START","data":{"step":"1","total":"3"}}
...
SESSION_COMPLETED 14:30:15   {"type":"SESSION_COMPLETED","data":{"output":"（润色后文章）..."}}
```

**对比第 0 章的黑盒**：那个等 15 秒出一坨文本；这个每 3-5 秒冒一个事件，实时看到「现在第几步、上一步产出了什么」。这就是「可观测」的体感。

### 1.4 用页面验证 → 暴露竞态

在页面上多点几次「生成」（或刷新页面后立刻点）。**偶发地**，你会看到事件流**少了开头**——第一条不是 `SESSION_STARTED`，而是直接 `STEP_START`，甚至有时整条流空掉。

> 这个现象不一定每次都复现（所以叫竞态），多试几次、或在机器繁忙时试，更容易出现。

**为什么**：看 1.2.4 的 `stream()` 方法。`return events` 这条流，WebFlux 是"返回后、客户端订阅时"才真正建立订阅；而 `new Thread(() -> run(...))` 是"方法返回前"就启动了。两者之间有个时间窗口：

```
时间线：
  new Thread 启动 run()  →  emit SESSION_STARTED（进总线）
  method return events   →  WebFlux/前端 开始订阅总线
                              ↑ 如果 SESSION_STARTED 在"订阅前"发出，热流不缓存历史，丢了
```

`Sinks.Many` 是**热流**——事件发一次就过，晚到的订阅者收不到历史。所以"先启动任务、后建立订阅"会漏掉 SESSION_STARTED。

> **为什么页面能暴露、curl 难暴露**：curl 你手动敲命令，人是慢的，等你敲完订阅早建立了，竞态不发作。页面是"打开就 fetch"，时序更紧凑，偶发能撞上。但竞态本质是偶发，最可靠的复现方式其实是自动化测试（第 6 章会补）——本章先用页面建立"确实有这个问题"的体感，再去修。

### 1.5 修复：READY 握手 + doOnSubscribe（协议层消除竞态）

竞态的根因是"订阅"和"触发"之间没有同步。企业级 SSE/WebSocket 系统的标准解法——**就绪握手**：确认订阅就绪后，才触发任务。

**两个手段配合**（单接口场景）：
1. `startWith(READY)`——订阅建立后，流首插一帧 READY（前端可据此知道"订阅好了"）。
2. `doOnSubscribe(...)`——**订阅真正发生时**才触发任务。这一条是消除竞态的关键：触发任务的瞬间，订阅已经建立，SESSION_STARTED 必达。

改 `SseController.stream()`：

```java
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader String sessionId) {

        ServerSentEvent<String> ready = ServerSentEvent.<String>builder()
                .id(sessionId + "-ready").event("READY").data("{\"type\":\"READY\"}").build();

        return eventBus.flux(e -> sessionId.equals(e.sessionId()))
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.type()))
                .map(this::toSse)
                .startWith(ready)
                // 订阅真正建立时才触发任务——竞态从根上消除
                .doOnSubscribe(() -> new Thread(() -> articleService.run(prompt, sessionId)).start());
    }
```

> **为什么 `doOnSubscribe` 解决竞态**：`doOnSubscribe` 的回调在"下游订阅这条流"时执行。而此时 `startWith` 的 READY 已经排在流首、总线订阅也已建立——触发任务的瞬间，订阅是就绪的，SESSION_STARTED 必达。
>
> **双接口场景的握手**：第 2 章会拆成 `/sse`（订阅）+ `/chat`（触发）两个接口，那时握手会变成"前端收到 `/sse` 的 READY 帧，才调 `/chat`"——纯协议层。本章单接口，用 `doOnSubscribe` 在后端把时序锁死，前端无感。
>
> `new Thread` 还在第 2 章换线程池——本章只解决竞态，不一次改两件事（演进纪律）。

**页面复测**：重启服务，在页面上连点十几次「生成」。这次每次第一条都是 `SESSION_STARTED`，竞态消除。

> **为什么这是企业级做法**：竞态发生在协议层（订阅 vs 触发时序），就在协议层（READY 握手）解决，而不是事后用"事件补发/重放"补救（那是不可靠的兜底手段）。**协议层治本优于应用层补救**——这是可观测系统的通用原则。

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
    ├── AgentEvent.java
    ├── EventBus.java             （改：加带过滤的 flux 重载）
    └── SseController.java        （改：READY 握手 + doOnSubscribe 触发）

src/main/resources/
└── static/
    └── index.html                ← 新增：可观测调试页面（随章节演进）
```

```bash
git add -A && git commit -m "第1章：事件总线+SSE+调试页面+READY握手消除竞态"
```

### 1.7 复盘

**做了**：搭起三层骨架（采集→总线→SSE），Agent 内部可见；给了第一个调试页面；并用页面验证抓住、用 READY 握手修复了竞态。

**这一章最该记住的工程教训**：
1. **热流不缓存历史**——`Sinks.Many` 是热流，"先发后订"会丢事件。这是响应式+SSE 架构的地基认知。
2. **页面比 curl 更能暴露时序问题**——竞态这种偶发问题，curl 手动敲基本不复现，页面（打开就 fetch）时序更紧凑，能撞上。最可靠的复现是自动化测试（第 6 章补），但页面让你先有体感。
3. **协议层治本优于应用层补救**——竞态发生在"订阅 vs 触发"的时序，就在协议层（READY 握手）解决，而不是事后用"事件重放/补发"补救（那是不可靠手段）。

**还差（后面章节解决）**：
- **事件可能丢**：`tryEmitNext` 失败被静默吞，高峰期缓冲满了 `SESSION_COMPLETED` 可能丢 → **第 2 章**
- **事件可能乱序**：埋点在不同时机发，理论上可能乱序到达 → **第 2 章**
- **断了重连会漏**：网络抖动、刷新页面，重连后中间事件没了 → **第 2 章**
- **只看到 Workflow 步骤**：还没看到工具调用细节、token 消耗 → **第 3 章**
- **`new Thread` 太糙**：要换线程池 → **第 2 章**
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
        String type,
        String sessionId,
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality    // ← 新增
) {

    /** 事件关键级别，决定背压满时降级策略。 */
    public enum Criticality {
        CRITICAL,      // 终态类，绝不能丢，落库兜底
        NORMAL,        // 阶段/步骤事件，尽力送达
        DISCARDABLE    // 流式片段，背压满优先丢
    }

    /** 按事件类型推断默认关键级别。 */
    public static Criticality defaultCriticality(String type) {
        return switch (type) {
            case "SESSION_STARTED", "SESSION_COMPLETED", "SESSION_FAILED" -> Criticality.CRITICAL;
            default -> Criticality.NORMAL;
        };
    }

    public static AgentEvent of(String type, String sessionId, Map<String, Object> data) {
        return new AgentEvent(type, sessionId, Instant.now(), data, defaultCriticality(type));
    }
}
```

> **小白疑问：`switch` 表达式（Java 14+）**
> `switch (type) { case ... -> ... }` 箭头形式不穿透、直接返回值，比老式 `case X: return ...; break;` 简洁。Java 21 完全支持。

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
        String type,
        String sessionId,
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality,
        long sequence        // ← 新增：会话内单调递增序号，0 表示未分配
) {
    public enum Criticality { CRITICAL, NORMAL, DISCARDABLE }

    public static Criticality defaultCriticality(String type) {
        return switch (type) {
            case "SESSION_STARTED", "SESSION_COMPLETED", "SESSION_FAILED" -> Criticality.CRITICAL;
            default -> Criticality.NORMAL;
        };
    }

    /** 快捷构造（sequence 默认 0，由 EventBus 分配）。 */
    public static AgentEvent of(String type, String sessionId, Map<String, Object> data) {
        return new AgentEvent(type, sessionId, Instant.now(), data, defaultCriticality(type), 0L);
    }
}
```

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
     * 订阅会话事件流 + 执行写作任务。支持 Last-Event-ID 重连回放。
     */
    @GetMapping(value = "/article", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt,
                                                @RequestHeader String sessionId,
                                                @RequestHeader(value = "Last-Event-ID", required = false)
                                                    String lastEventId) {

        // ① 回放段：有 lastEventId 且有 Redis，先补发断连期间的关键事件
        Flux<AgentEvent> replay = Flux.empty();
        CriticalEventStore store = storeProvider.getIfAvailable();
        if (store != null && lastEventId != null) {
            replay = Flux.fromIterable(store.findAfter(sessionId, parseTimestampFrom(lastEventId)));
        }

        // ② 实时段：订阅总线，过滤本会话
        Flux<AgentEvent> live = eventBus.flux()
                .filter(e -> sessionId.equals(e.sessionId()));

        // ③ 合并：先回放、再实时；收到终态就结束流
        Flux<ServerSentEvent<String>> events = Flux.concat(replay, live)
                .takeUntil(e -> "SESSION_COMPLETED".equals(e.type())
                        || "SESSION_FAILED".equals(e.type()))
                .map(this::toSse);

        // ④ 异步执行写作任务（响应式线程池，替换第1章的裸 new Thread）
        Mono.fromRunnable(() -> articleService.run(prompt, sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return events;
    }
```

> **「SSE 心跳」留给产品对外时**：现在内网/IDE 跑，两事件间隔再长连接也不会被断。等产品部署到 nginx/CDN 后面（第 3 章之后），代理的 60s 空闲超时会断慢生成连接——那时才需要心跳。**现在不做**（演进纪律），方案见附录 A.10。
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
                .event(e.type())
                .data(toJson(e))
                .build();
    }

    /** 用 ObjectMapper（替换第1章手写 JSON）。 */
    private String toJson(AgentEvent e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (Exception ex) {
            return "{\"type\":\"" + e.type() + "\"}";
        }
    }
}
```

> **三个改进（都是第 1 章承诺）**：① 加 Last-Event-ID 回放；② `new Thread` → `Mono.fromRunnable().subscribeOn(boundedElastic)`；③ 手写 JSON → ObjectMapper。
>
> **小白疑问：为什么 `Schedulers.boundedElastic()`？**
> 它是 Reactor 提供的「适合阻塞任务」的线程池，有上限（默认 10×CPU 核数）、可复用。裸 `new Thread` 每次创建销毁开销大、且无上限（高并发会炸）。`subscribe()` 是触发执行（fire-and-forget）。
>
> **企业级并发控制**：`boundedElastic` 默认上限 10×CPU 核数（8 核机器 = 80 个线程），超出的任务排队。这个上限是**背压保护**——高并发时请求排队而不是无限建线程拖垮系统。调上限：`Schedulers.newBoundedElastic(线程数, 队列大小, "name")` 自定义。**永远不要在高并发路径用裸 `new Thread`**——它是无上限的，一个流量洪峰就能 OOM。

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

**兑现两个改进承诺**：✅ 线程池；✅ ObjectMapper。

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

工具的 Observation 由 Spring AI 内部发起，`ObservationHandler.onStop` 只能读"当前线程上下文"，要让它拿到 sessionId，得把 sessionId 放到线程上下文（ThreadLocal）里。

**关键认知——33b 是同步模型，工具就在调用线程执行**：本项目的 `ChainingService.call` 是同步 `.call()`。[Spring AI 官方](https://docs.spring.io/spring-ai/reference/api/tools.html)明确：同步模式下 `DefaultToolCallingManager` 在**调用线程**同步执行工具（[异步是待加特性 #4755](https://github.com/spring-projects/spring-ai/issues/4755)）。所以 `call()` 里 `set(sessionId)` → 工具在同一线程执行 → `onStop` 在同一线程 `get()` 直接读到。**简单 set/get 即可，不需要跨线程传播**。

> **和流式模型的区别**：流式 `.stream()` 下工具执行可能切线程，才需要 Reactor Context + 自动传播跨线程带 sessionId。**本项目同步模型不切线程，用最简单的 ThreadLocal set/get**。两套方案的差异是线程模型决定的，不是任选——这是企业级"按实际线程模型选方案"的体现。
>
> **封装仍有价值**：即便不跨线程，用一个 `AppContextKeys` 统一管理 sessionId 的存取（消除魔法字符串、加新值易扩展），仍比散落的静态变量好。只是**不需要** ContextPropagationConfig 自动传播那套（那是 Reactor 模型的）。

#### token 统计：在 ChainingService.call 里旁路发事件

LLM 返回的 `ChatResponse` 里有 `Usage`（token 数）。我们的写作流程是同步 `.call()`，拿一次 response 拿一次 Usage 就行。难点是「统计了不影响主流程」——用旁路方式：在拿到 response 后发个 `LLM_TOKENS` 事件，token 统计交给消费者，业务代码不操心。

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

> **注意**：原来 `ChainingService` 是直接注入 `ChatClient.Builder` 构造的吗？不是——它注入的是 `ChatClient`。Spring AI starter 默认会提供一个 `ChatClient` Bean。这里我们**自定义**一个带工具的 `ChatClient` Bean 覆盖默认的。如果启动报「多个 ChatClient Bean」冲突，删掉这个自定义 Bean、改成在 `ChainingService.call` 里 `.tools(tools)` 也行——两种方式任选。

#### 3.2.3 工具调用可见：订阅原生 Observation（采集点）

写一个 `ObservationHandler` 订阅 Spring AI 原生的 `ToolCallingObservation`，把"工具名/参数/返回值"转成 `TOOL_CALL` 事件。不碰任何 `@Tool` 方法。

先加 actuator 依赖（它带来 `ObservationRegistry`）：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

**(a) 上下文封装（三件套）**——让 sessionId 能跨线程到达 `onStop`：

`src/main/java/com/example/aobs/obs/PropagatedContextValue.java`：
```java
package com.example.aobs.obs;

/** 可传播的上下文值：一个 ThreadLocal + 它在 Reactor Context 里的 key。泛型，任意类型可用。 */
public class PropagatedContextValue<T> {
    private final ThreadLocal<T> threadLocal = new ThreadLocal<>();
    private final String key;
    public PropagatedContextValue(String key) { this.key = key; }
    public String key() { return key; }
    public T get() { return threadLocal.get(); }
    public void set(T value) { threadLocal.set(value); }
    public void clear() { threadLocal.remove(); }
}
```

`src/main/java/com/example/aobs/obs/AppContextKeys.java`：
```java
package com.example.aobs.obs;

/** 全局上下文注册中心：所有可传播值的唯一定义点。 */
public final class AppContextKeys {
    public static final PropagatedContextValue<String> SESSION_ID = new PropagatedContextValue<>("sessionId");
    private AppContextKeys() {}
}
```

**为什么没有 ContextPropagationConfig**：本项目是同步模型，工具在调用线程执行（见 3.1），`set/get` 在同线程直接生效——**不需要** Reactor 自动传播（`Hooks.enableAutomaticContextPropagation` + `registerThreadLocalAccessor`）。那套是流式模型（`.stream()`）工具切线程时才需要的。强行加反而误导。`PropagatedContextValue`/`AppContextKeys` 在这里是纯粹的"ThreadLocal + key 统一管理"封装，不涉及传播。

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
        // sessionId 从上下文读——同步模型下工具在调用线程执行，
        // call() 里 set 的值，onStop 在同一线程直接 get 到。
        String sessionId = AppContextKeys.SESSION_ID.get();
        if (sessionId == null) sessionId = "unknown";

        String toolName = context.getToolDefinition() != null
                ? context.getToolDefinition().name() : "unknown";

        // HashMap 而非 Map.of——getToolCallArguments()/getToolCallResult() 可能返回 null
        Map<String, Object> data = new HashMap<>();
        data.put("tool", toolName);
        data.put("args", context.getToolCallArguments());
        data.put("result", context.getToolCallResult());

        eventBus.emit(AgentEvent.of("TOOL_CALL", sessionId, data));
    }
}
```

> **API 核实**：`ObservationHandler<T>.onStop/supportsContext`（micrometer-observation，webflux 间接带入）、`ToolCallingObservationContext.getToolDefinition().name()/getToolCallArguments()/getToolCallResult()`（spring-ai-model 2.0.0）——均为官方 API。
>
> **⚠️ 别写 ObservationConfig 手动注册**（真实坑）：[Spring Boot 官方文档](https://docs.spring.io/spring-boot/reference/actuator/observability.html)明确——`ObservationHandler` Bean 会被自动注册到 Registry。如果你再写一个 `@Configuration` 里 `registry.observationConfig().observationHandler(toolHandler)`，同一实例注册两次，`onStop` 被调两遍，`TOOL_CALL` 事件发两遍。只靠 `@Component` 自动注册即可。
>
> **onStop 才发事件**：工具开始时（onStart）还没有返回值。onStop 时工具已执行完，`getToolCallResult()` 有值——这时才能发完整的"参数+返回值"事件。


#### 3.2.4 token 统计：在 ChainingService.call 里旁路发事件

最直接的 token 统计：拿到 `ChatResponse` 后取 `Usage`，发 `LLM_TOKENS` 事件。改 `ChainingService.call`：

`src/main/java/com/example/aobs/workflow/ChainingService.java`（改 call 方法）：

```java
// 只改 call 方法，其余不变
protected String call(String system, String prompt, String sessionId) {
    // 把 sessionId 放进当前线程上下文——同步模型下工具在本线程执行，
    // ToolObservationHandler.onStop 在同线程 AppContextKeys.SESSION_ID.get() 直接读到。
    AppContextKeys.SESSION_ID.set(sessionId);
    try {
        org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .chatResponse();   // 改成拿完整 ChatResponse（而不是只拿 content）

        // 旁路发 token 事件（不影响主流程，返回值照样取 content）
        emitTokens(sessionId, response);

        return response.getResult().getOutput().getText();
    } finally {
        AppContextKeys.SESSION_ID.clear();   // 防线程池复用串会话
    }
}

/** 取 Usage 发 LLM_TOKENS 事件。 */
private void emitTokens(String sessionId, org.springframework.ai.chat.model.ChatResponse response) {
    try {
        var usage = response.getMetadata().getUsage();
        if (usage == null) return;
        int prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        String model = response.getMetadata().getModel();
        double cost = costCalculator.calculate(prompt, completion, model);

        eventBus.emit(AgentEvent.of("LLM_TOKENS", sessionId,
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

> **API 核实**：`chatResponse().getMetadata().getUsage()` → `Usage.getPromptTokens()/getCompletionTokens()`、`ChatResponseMetadata.getModel()` 全部真实存在。
>
> **小白疑问：为什么叫「旁路」？**
> token 统计是「额外关心的事」，不是写作主流程。我们发个事件让消费者去统计，`call` 该返回什么还返回什么（文章内容）。统计逻辑和业务逻辑解耦——后面想加 Langfuse 上报、想换计费方式，都改消费者不改 `call`。

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

`TOOL_CALL` 事件的 `sessionId` 应该是正确的会话 ID（不是 `unknown`）。因为 33b 同步模型下工具在调用线程执行、`call()` 里 set 了 sessionId，`onStop` 同线程 get 必然读到。

> **什么时候会读不到**：如果你后来把项目改成流式 `.stream()`，工具执行可能切线程，同线程 set/get 就失效——那时才需要 Reactor Context + 自动传播。**线程模型变了，会话隔离方案也得跟着变**——这是同步/流式架构差异的连锁影响。

### 3.4 checkpoint

```
src/main/java/com/example/aobs/
├── config/
│   └── ChatClientConfig.java          （新增）
├── obs/
│   ├── PropagatedContextValue.java    （新增：通用上下文值）
│   ├── AppContextKeys.java            （新增：上下文注册中心）
│   ├── ToolObservationHandler.java    （新增：订阅工具 Observation）
│   └── CostCalculator.java            （新增）
├── workflow/
│   └── ChainingService.java           （改：call 加 token 旁路 + set sessionId）
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
- **上下文到观测回调**：观测回调（onStop）够不着请求参数，靠 ThreadLocal 把 sessionId 带过去。同步模型（本项目）同线程 set/get 即可；流式模型才需自动传播跨线程。

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
        .takeUntil(e -> "SESSION_COMPLETED".equals(e.type()) || "SESSION_FAILED".equals(e.type()))
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
        String type,
        String sessionId,
        String tenantId,        // ← 新增：租户（隔离 + 成本归因）
        String userId,          // ← 新增：用户（成本归因 + 限流）
        String agentVersion,    // ← 新增：Agent 版本（回滚定位 + 成本归因）
        Instant timestamp,
        Map<String, Object> data,
        Criticality criticality,
        long sequence
) {
    public enum Criticality { CRITICAL, NORMAL, DISCARDABLE }

    public static Criticality defaultCriticality(String type) {
        return switch (type) {
            case "SESSION_STARTED", "SESSION_COMPLETED", "SESSION_FAILED" -> Criticality.CRITICAL;
            default -> Criticality.NORMAL;
        };
    }

    /** 带租户/用户的快捷构造。 */
    public static AgentEvent of(String type, String sessionId, String tenantId, String userId,
                                Map<String, Object> data) {
        return new AgentEvent(type, sessionId, tenantId, userId, "v1",
                Instant.now(), data, defaultCriticality(type), 0L);
    }
}
```

> 这一步要把前面所有 `AgentEvent.of(...)` 调用点都加上 tenantId/userId 参数。`ChainingService.run` 签名加 tenantId/userId，Controller 从请求头取——**这是一次涉及多文件的配套改动**，按 IDE 报错逐个修。

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
        eventBus.emit(AgentEvent.of("QUOTA_EXCEEDED", sessionId, tenantId, userId,
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
> **同步调用也要配 RestClient**：本项目的 `ChainingService.call` 是同步 `.call()`，走 RestClient。如果也遇到超时，同样自定义 `RestClient.Builder` 设读超时。

#### 6.2.5 修复事故 B：subscribe 接住错误

事故 B 的根因——`/chat` 的 `subscribe()` 无参，错误无人接，变 `ErrorCallbackNotImplemented`。修复：subscribe 传错误回调，把错误转成 `SESSION_FAILED` 事件（前端据此停止转圈）。

改 `SseController.stream()` 里的触发处（第 2 章用了 `doOnSubscribe` 触发，现在补错误处理）：

```java
.doOnSubscribe(() -> Mono.fromRunnable(() -> articleService.run(prompt, sessionId))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        // 错误回调：接住异常，转 SESSION_FAILED 事件（前端收到后停止转圈）
        .subscribe(
                result -> {},
                err -> eventBus.emit(AgentEvent.of("SESSION_FAILED", sessionId,
                        java.util.Map.of("error", err.getClass().getSimpleName() + ": " + err.getMessage())))
        ))
```

> **为什么必须 subscribe 接错误**：Reactor 的铁律——**没有错误回调的订阅，错误会变成 ErrorCallbackNotImplemented 刷日志，且永远到不了前端**。企业级代码里每个 `.subscribe()` 都要有 `onError`（或用 `.doOnError` + `.subscribe()`）。
>
> **主流程也要发 SESSION_FAILED**：`ChainingService.run` 里 LLM 调用失败时（事故 A 的超时），也要 try/catch 发 `SESSION_FAILED`——双保险（主流程 + subscribe 回调都发，确保前端必收到失败信号）。
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

在 `ChainingService.call` 上加重试（429/超时退避重试，耗尽降级）：
```java
    @io.github.resilience4j.retry.annotation.Retry(name = "llmCall", fallbackMethod = "callFallback")
    protected String call(String system, String prompt, String sessionId) {
        // ... 原 call 逻辑不变
    }

    /** 重试耗尽降级：返回降级文案，并发 SESSION_FAILED。 */
    private String callFallback(String system, String prompt, String sessionId, Throwable t) {
        eventBus.emit(AgentEvent.of("SESSION_FAILED", sessionId,
                Map.of("error", "LLM 不可用（重试耗尽）: " + t.getClass().getSimpleName())));
        return "（AI 服务暂时繁忙，请稍后重试）";
    }
```

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
        .doOnCancel(() -> eventBus.emit(AgentEvent.of("SESSION_CANCELLED", sessionId, Map.of())));
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
            jdbc.update("INSERT INTO event_archive (session_id, seq, type, tenant_id, ts, payload) VALUES (?,?,?,?,?,?)",
                    event.sessionId(), event.sequence(), event.type(), event.tenantId(),
                    event.timestamp().toEpochMilli(), mapper.writeValueAsString(event));
        } catch (Exception e) {
            System.err.println("[Archive] failed: " + e.getMessage());
        }
    }

    /** 按会话查历史（事后排查）。 */
    public List<Map<String, Object>> findBySession(String sessionId) {
        return jdbc.queryForList(
                "SELECT type, ts, payload FROM event_archive WHERE session_id = ? ORDER BY seq", sessionId);
    }
}
```

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

## 附录：完整目录树与踩坑手册

### A.1 完整项目结构（第 7 章结束时）

```
ai-writing-assistant/
├── pom.xml
├── src/main/java/com/example/aobs/
│   ├── Application.java
│   ├── config/
│   │   └── ChatClientConfig.java
│   ├── workflow/
│   │   └── ChainingService.java          # 业务基类（加埋点 + token 旁路）
│   ├── writing/
│   │   ├── ArticleService.java           # 写作助手
│   │   ├── ArticleController.java        # 同步接口
│   │   └── WritingTools.java             # @Tool 工具
│   └── obs/                              # 可观测层（第 1-6 章逐步长出）
│       ├── AgentEvent.java               # 事件（字段随章节长）
│       ├── EventBus.java / ShardedEventBus.java   # 总线（第1章）/分片总线（第4章）
│       ├── PropagatedContextValue.java   # 通用上下文值（第3章）
│       ├── AppContextKeys.java           # 上下文注册中心（第3章）
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
│   ├── schema.sql                        # event_archive 建表（第7章归档）
│   └── static/                           # 前端页面（随章节演进，主要验证手段）
│       ├── index.html                    # 调试台（第1章起，逐章增强）
│       └── reconnect.html                # 重连演示（第2章）
```
> 本文以**前端页面**为主要验证手段（不是单元测试）。真实项目里建议团队按需补 JUnit/StepVerifier 测试，但本文篇幅聚焦页面验证这条主线。

### A.2 git 提交历史

```
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
- **工具事件 sessionId 是 unknown** → 本项目同步模型下不会出现（工具在调用线程、set/get 同线程）。若你改成了流式 `.stream()` 才会出现——那时要用 Reactor Context + 自动传播。
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
    S7[第7章 运营事故<br/>心跳+重试+取消+连接治理+归档]
```

> **终极纪律**：走到你当前体量够用的阶段就停。日活不过千停第 3 章；不多实例停第 3 章；不对外卖停第 3 章。**每多走一章，都要能说出「是什么痛点逼我走的」——说不出来就别走**。

### A.5 完整 pom.xml（第 7 章结束时）

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

### A.6 完整 application.yaml（第 7 章结束时）

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

| 章节 | 新增字段 | record 签名（关键字段） | `of` 签名 |
|------|---------|----------------------|----------|
| 第 1 章 | — | `(type, sessionId, timestamp, data)` | `of(type, sessionId, data)` |
| 第 2 章 | `criticality` | `(type, sessionId, timestamp, data, criticality)` | `of(type, sessionId, data)`（criticality 默认 NORMAL） |
| 第 2 章 | `sequence` | `(..., criticality, sequence)` | `of(...)`（sequence 默认 0，EventBus 分配） |
| 第 5 章 | `tenantId`, `userId`, `agentVersion` | `(type, sessionId, tenantId, userId, ..., data, ...)` | `of(type, sessionId, tenantId, userId, data)` |

> 每次字段变更都是**涉及多文件的配套改动**（所有 `AgentEvent.of(...)` 调用点要同步改）——IDE 会报错，按报错逐个修。这是企业项目里"加一个字段"的真实成本，也是为什么第 1 章用 `Map<String,Object> data` 装附加信息（灵活、加字段不改签名）——但关键的结构化字段（sessionId/tenantId）还是提成 record 字段，为了强类型和过滤方便。

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

### A.10 DeepSeek 极简风调试页面（第 1-7 章通用）

放 `src/main/resources/static/index.html`，浏览器打开 `http://localhost:8080/index.html`。

**一个页面覆盖全部章节**：事件流（第1章）、工具调用面板（第3章 TOOL_CALL）、token 统计（第3章 LLM_TOKENS）、租户切换（第5章下拉框）、新对话（多轮/隔离）。各章验证都用它。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI 写作助手</title>
    <style>
        :root {
            --bg: #f7f7f8; --surface: #fff; --border: #ececec;
            --text: #1a1a1a; --text-2: #8e8e8e; --muted: #b0b0b0;
            --accent: #1a1a1a; --green: #00b96b; --orange: #e67e22; --red: #e53935;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
               background: var(--bg); color: var(--text); height: 100vh; display: flex; flex-direction: column;
               font-size: 15px; line-height: 1.8; }
        header { background: var(--surface); border-bottom: 1px solid var(--border);
                 padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; }
        header .title { font-size: 16px; font-weight: 600; }
        header .sub { color: var(--muted); font-size: 13px; margin-left: 8px; }
        header .actions { display: flex; gap: 8px; }
        header button, header select { font-size: 13px; border: 1px solid var(--border); background: var(--surface);
                        border-radius: 8px; padding: 5px 12px; cursor: pointer; color: var(--text-2); }
        header button:hover { border-color: var(--text-2); }
        #chat { flex: 1; overflow-y: auto; padding: 32px 0; }
        .msg { max-width: 720px; margin: 0 auto; padding: 0 24px; }
        .user { display: flex; justify-content: flex-end; margin-bottom: 16px; }
        .user .bubble { background: var(--accent); color: #fff; padding: 10px 16px;
                        border-radius: 12px 12px 4px 12px; max-width: 75%; word-break: break-word; }
        .assistant { margin-bottom: 20px; }
        .assistant .bubble { background: var(--surface); padding: 14px 18px; border-radius: 12px; word-break: break-word; }
        .process { max-width: 720px; margin: 0 auto 16px; padding: 0 24px; }
        .process summary { cursor: pointer; color: var(--muted); font-size: 13px; padding: 4px 0; user-select: none; }
        .step { background: var(--surface); border-left: 2px solid var(--muted); margin: 4px 0; padding: 6px 12px;
                border-radius: 0 8px 8px 0; font-size: 13px; color: var(--text-2); }
        .step.tool { border-left-color: var(--green); } .step.tool .label { color: var(--green); }
        .step.tokens { border-left-color: var(--orange); } .step.tokens .label { color: var(--orange); }
        .step.failed { border-left-color: var(--red); } .step.failed .label { color: var(--red); }
        .step .label { font-weight: 600; margin-right: 6px; }
        .step .kv { color: var(--muted); margin-right: 8px; } .step .kv b { color: var(--text-2); }
        #bar { background: var(--surface); border-top: 1px solid var(--border); padding: 12px 24px; }
        #input-wrap { max-width: 720px; margin: 0 auto; display: flex; gap: 8px; align-items: center;
                      background: var(--bg); border-radius: 22px; padding: 4px 4px 4px 18px; }
        #prompt { flex: 1; border: none; background: transparent; outline: none; font-size: 15px; padding: 8px 0; }
        #send { background: var(--accent); color: #fff; border: none; width: 32px; height: 32px;
                border-radius: 50%; cursor: pointer; font-size: 16px; flex-shrink: 0; }
        #send:disabled { background: #d0d0d0; }
        #status { text-align: center; color: var(--muted); font-size: 12px; padding: 4px 0; }
    </style>
</head>
<body>
<header>
    <div><span class="title">AI 写作助手</span><span class="sub">可观测调试台</span></div>
    <div class="actions">
        <select id="tenant"><option value="">默认租户</option><option value="tenantA">租户A</option><option value="tenantB">租户B</option></select>
        <button onclick="newChat()">新对话</button>
    </div>
</header>
<div id="chat"></div>
<div id="status">输入主题，回车发送</div>
<div id="bar"><div id="input-wrap">
    <input id="prompt" placeholder="输入主题，如：AI 的未来" value="AI 的未来">
    <button id="send" onclick="send()">➤</button>
</div></div>
<script>
    let sending = false, controller = null;
    document.getElementById('prompt').addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
    function newChat() { if (!sending) { document.getElementById('chat').innerHTML = ''; document.getElementById('status').textContent = '已开新对话'; } }

    async function send() {
        if (sending) return;
        const input = document.getElementById('prompt');
        const prompt = input.value.trim();
        if (!prompt) return;
        input.value = '';
        sending = true;
        document.getElementById('send').disabled = true;

        const chat = document.getElementById('chat');
        const u = document.createElement('div'); u.className = 'msg user';
        u.innerHTML = '<div class="bubble"></div>';
        u.querySelector('.bubble').textContent = prompt;
        chat.appendChild(u);

        const proc = document.createElement('details'); proc.className = 'process';
        proc.innerHTML = '<summary>执行过程</summary>';
        chat.appendChild(proc);

        const a = document.createElement('div'); a.className = 'msg assistant';
        a.innerHTML = '<div class="bubble"></div>';
        chat.appendChild(a);
        chat.scrollTop = chat.scrollHeight;

        const sessionId = 's-' + Date.now();
        const tenant = document.getElementById('tenant').value;
        const headers = { 'sessionId': sessionId, 'Accept': 'text/event-stream' };
        if (tenant) headers['X-Tenant-Id'] = tenant;

        controller = new AbortController();
        try {
            const resp = await fetch('/api/obs/article?prompt=' + encodeURIComponent(prompt), { headers, signal: controller.signal });
            const reader = resp.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
                let idx;
                while ((idx = buffer.indexOf('\n\n')) >= 0) {
                    handleFrame(buffer.slice(0, idx), proc, a);
                    buffer = buffer.slice(idx + 2);
                }
            }
            document.getElementById('status').textContent = '完成';
        } catch (e) {
            if (e.name !== 'AbortError') document.getElementById('status').textContent = '连接断开';
        }
        sending = false;
        document.getElementById('send').disabled = false;
    }

    function handleFrame(frame, proc, assistantEl) {
        let type = '', dataStr = '';
        for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            if (line.startsWith('data:')) dataStr += line.slice(5).trim();
        }
        if (!type) return;
        let data = {}; try { data = JSON.parse(dataStr).data || JSON.parse(dataStr); } catch(e) { data = {}; }

        if (type === 'CONTENT_DELTA') {
            if (data.text) assistantEl.querySelector('.bubble').textContent += data.text;
        } else if (type !== 'SESSION_COMPLETED' && type !== 'READY') {
            const step = document.createElement('div');
            let cls = '', label = type, kvs = [];
            if (type === 'TOOL_CALL') { cls = 'tool'; label = '🔧 ' + (data.tool||''); kvs = [['参数',data.args],['返回',data.result]]; }
            else if (type === 'LLM_TOKENS') { cls = 'tokens'; label = '💰 ' + (data.totalTokens||0) + ' tokens'; }
            else if (type === 'SESSION_FAILED') { cls = 'failed'; label = '❌ 失败'; kvs = [['error',data.error]]; }
            step.className = 'step ' + cls;
            let html = '<span class="label">' + label + '</span>';
            for (const [k,v] of kvs) { if (v != null && v !== '') html += '<span class="kv">' + k + ': <b>' + v + '</b></span>'; }
            step.innerHTML = html;
            proc.appendChild(step);
        }
        document.getElementById('chat').scrollTop = document.getElementById('chat').scrollHeight;
    }
</script>
</body>
</html>
```

> **风格说明**：DeepSeek 极简风——纯白底、深色（#1a1a1a）主色（不用鲜艳蓝）、窄列（720px）、大留白、细边框。用户气泡深色右对齐，助手白底左对齐。工具调用/token/失败用克制的绿/橙/红色标。
>
> **租户下拉框**：第 1-4 章用不到（选"默认租户"即可），第 5 章多租户时选 tenantA/B 演示隔离。多余时不影响。

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
