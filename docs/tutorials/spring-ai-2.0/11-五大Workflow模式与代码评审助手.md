# 10 五大 Workflow 模式与代码评审助手

> 本文合并自原 05「五大 Workflow 模式 Advisor 实现」+ 原 06「组合实战代码评审助手」。
>
> Anthropic《Building Effective Agents》(2024-12-19) 的五大模式全部用 **Spring `@Service`** 实现，最后用代码评审助手把五大模式串起来。
>
> **核心心法**：**Workflow > Agent**。能用确定性代码路径解决的，不要用自主 Agent。80%+ 企业场景能用 Workflow 解决，更稳定、更便宜、更可观测。
>
> 前置：[`./01`](./01-2.0基础重塑.md) - [`./05`](./05-MCP协议全解.md)
> 预计：2-3 天

---

## 0. 五大模式总览

| 模式 | 适用场景 | 复杂度 |
|------|---------|--------|
| **Prompt Chaining** | 任务可分解为线性步骤 | 低 |
| **Parallelization**（Sectioning / Voting） | 多个独立子任务 / 提升结果稳定性 | 中 |
| **Routing** | 不同类型输入走不同处理 | 低 |
| **Orchestrator-Workers** | 子任务动态确定 | 中高 |
| **Evaluator-Optimizer** | 结果可被评估 + 迭代改进 | 中 |

代码组织：所有模式的抽象类放在 `org.demo0X.workflows.<pattern>` 包下，具体业务子类放 `org.demo0X.service`。

---

## 0.1 关键决策：用 Service，不用 Advisor

> 这是本文最重要的一节。如果你只想记住一句话：**Workflow 用 Service 编排，Advisor 只做横切关注点（记忆、日志、重试、输出校验）。**

### 0.1.1 工业级实现的实际形态

调研三份一手资料：

| 资料 | 结论 |
|------|------|
| Anthropic 官方《Building Effective Agents》原文 | Workflow 定义为 "**predefined code paths** orchestrating LLMs"，给的 Python 示例全是普通函数串联 |
| Spring AI 2.0 官方文档《Prompt Engineering Patterns》 | 所有 Step-Back / CoT / Self-Consistency / ToT 示例全部在方法里直接 `chatClient.prompt().call()`，没有一个用 Advisor |
| Spring AI 工业实战教程（JavaTechOnline / Baeldung） | 五大 Workflow 全部 `@Service` 里编排 ChatClient，Advisor 只用于记忆 |

### 0.1.2 Advisor 的真实定位

Spring AI 官方原话：

> "Advisors are **interceptors** in the ChatClient pipeline. They can add **memory, logging, retry logic, and output validation**, all without modifying your core agent code."

关键词：**interceptors / cross-cutting concerns**（拦截器 / 横切关注点）。Advisor 不是业务编排工具。

### 0.1.3 反面教材：把 Workflow 写成 Advisor 会发生什么

```java
// ❌ 反模式：在 Advisor.before 里跑完业务，再把结果塞回 request
public class PromptChainingAdvisor implements BaseAdvisor {
    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String payload = req.prompt().getUserMessage().getText();
        for (Function<String, String> step : steps) {
            payload = step.apply(payload);  // ← 已经跑完所有 LLM
        }
        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(payload))  // ← 塞回 request
                        .build())
                .build();
    }
}
```

**问题**：Advisor 链走完 `before` 之后，请求会被传给真正的 ChatClient 触发**最后一次 LLM 调用**。也就是说：
1. 链里已经调了 N 次 LLM（N = steps.size）
2. 链终点又调 1 次 LLM（ChatClient 默认调用，**无 system prompt、无业务约束**）
3. 总共 N+1 次调用，最后这次输出**覆盖**了链路精心设计的结果，业务不可控

实际症状（demo06 真实踩坑）：
- 最终返回的是模型自由发挥的内容，不是工作流结果
- 多花一次 API 成本
- 因为子调用 ChatClient 时没传 `CONVERSATION_ID`，触发 `MessageChatMemoryAdvisor` 抛 `IllegalArgumentException: conversationId cannot be null`

### 0.1.4 正确形态：Service 编排

```java
// ✅ 正模式：Service 直接编排 ChatClient
@Service
public class ArticleChainingService extends ChainingService {
    public ArticleChainingService(ChatClient client) { super(client); }

    @Override
    protected List<BiFunction<String, String, String>> steps() {
        return List.of(
            (topic, sid)  -> call("生成大纲，只输出大纲", topic, sid),
            (outline, sid) -> call("根据大纲生成草稿", outline, sid),
            (draft, sid)   -> call("润色草稿让它更流畅", draft, sid)
        );
    }
}
```

返回 `String` 而不是 `ChatClientRequest`，彻底脱离 Advisor 链，不会有"多调一次"的污染。

---

## 1. Pattern 1: Prompt Chaining（提示链）

### 1.1 定义

把一个复杂任务拆成**线性**子任务，每步 LLM 调用以上一步输出为输入。

```
Step 1 → Step 2 → Step 3 → ... → Output
```

### 1.2 适用场景

| 场景 | 怎么拆 |
|------|-------|
| 写文档 | 大纲 → 草稿 → 润色 |
| 翻译复杂文档 | 直译 → 校对 → 本地化 |
| 代码生成 | 接口定义 → 实现 → 测试 |

### 1.3 抽象类：ChainingService

> 模板方法模式：父类持有 ChatClient + 定义流程，子类只声明 steps。

```java
// org.demo06.workflows.chaining.ChainingService
package org.demo06.workflows.chaining;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.function.BiFunction;

public abstract class ChainingService {

    protected final ChatClient chatClient;

    protected ChainingService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明步骤链。每步入参 (上一步输出, sessionId)，出参是本步输出。 */
    protected abstract List<BiFunction<String, String, String>> steps();

    /** 模板方法：跑完整条链。 */
    public String run(String input, String sessionId) {
        String payload = input;
        for (BiFunction<String, String, String> step : steps()) {
            payload = step.apply(payload, sessionId);
            if (payload == null) {            // gate check：返回 null 提前终止
                return "[CHAIN TERMINATED]";
            }
        }
        return payload;
    }

    /** 辅助方法：统一 LLM 调用样板（system + user + sessionId）。 */
    protected String call(String systemPrompt, String userText, String sessionId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
```

**关键设计点**：
- 父类持有 `ChatClient`，子类不重复注入
- `BiFunction<String, String, String>`：第二参数是 `sessionId`，三步共享同一会话记忆
- `run()` 返回 `String` 而不是 `ChatClientRequest`——彻底脱离 Advisor 链，无多余 LLM 调用
- `payload` 命名贯穿流转（表示"链上负载"），不用模糊的 `current`

### 1.4 子类：文章生成链

```java
// org.demo06.service.ArticleChainingService
package org.demo06.service;

import org.demo06.workflows.chaining.ChainingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.BiFunction;

@Service
public class ArticleChainingService extends ChainingService {

    public ArticleChainingService(ChatClient chatClient) {
        super(chatClient);
    }

    @Override
    protected List<BiFunction<String, String, String>> steps() {
        return List.of(
                (topic, sid)    -> call("生成大纲，只输出大纲", topic, sid),
                (outline, sid)  -> call("根据大纲生成草稿，只输出草稿", outline, sid),
                (draft, sid)    -> call("润色草稿让它更流畅，只输出最终文本", draft, sid)
        );
    }
}
```

### 1.5 Controller 直接调 Service

```java
// org.demo06.controller.TestController
@RestController
@RequestMapping("/demo06/workflow")
public class TestController {

    @Autowired
    private ArticleChainingService articleChainingService;

    @GetMapping("/chat")
    public String article(@RequestParam String prompt, @RequestHeader String sessionId) {
        return articleChainingService.run(prompt, sessionId);
    }
}
```

### 1.6 何时加 gate check

中间步骤可以判断"上一步质量够不够继续"，不够就提前终止：

```java
(outline, sid) -> {
    String result = call("生成大纲", outline, sid);
    if (result == null || result.length() < 50) {
        return null;   // 触发终止，run() 会返回 "[CHAIN TERMINATED]"
    }
    return result;
}
```

### 1.7 Postman 测试用例

为方便测试，controller 已经在上文给出。

> 用例设计原则：只验证**逻辑路径**（响应非空、字段存在、分支命中），不验证内容准确性。

#### 用例 1：常规三步链（happy path）

**目的**：验证 Outline → Draft → Polish 三步线性执行。

- Method：`POST`（或按你的 controller 用 `GET`）
- URL：`http://localhost:8096/demo06/workflow/chat?prompt=Spring AI 2.0 的 Advisor 链机制`
- Headers：`sessionId: session-001`
- **逻辑校验点**：
  - HTTP 200；
  - 响应非空字符串，长度 > 100（说明三步链都被执行了，不是单个 LLM 调用）；
  - 后端日志能看到 3 次 chat completion 调用（每次时间戳不同）。

#### 用例 2：空输入 → 验证 gate check 不触发

**目的**：空字符串输入不应让链提前返回 `[CHAIN TERMINATED]`。

- URL：`http://localhost:8096/demo06/workflow/chat?prompt= `
- Headers：`sessionId: session-002`
- **逻辑校验点**：
  - HTTP 200（不应 500）；
  - 响应不为 `[CHAIN TERMINATED]`（除非你按 §1.6 加了 gate）；
  - 后端只调用 3 次 LLM（不会因空输入死循环）。

#### 用例 3：同一 sessionId 多次调用 → 验证记忆共享

**目的**：三次 LLM 调用都用同一 sessionId，应该共享 ChatMemory。

- 用 sessionId = `session-003` 调用 `/chat?prompt=...`；
- 然后用同一 sessionId 调用 `/chat?prompt=刚才那篇的标题是什么？`；
- **逻辑校验点**：
  - 第二次响应里能引用第一次生成的文章标题（说明 ChatMemory 在三步间持久化了）；
  - 后端日志：每次请求都是 3 次 LLM 调用，但 ChatMemory 中累计的消息条数逐次增加。

---

## 2. Pattern 2: Parallelization（并行）

### 2.1 两种子模式

**Sectioning**：把任务拆成独立子任务并行执行。

```
Input → ┌─ Subtask A ─┐
        ├─ Subtask B ─┤→ Aggregate → Output
        └─ Subtask C ─┘
```

**Voting**：同一任务跑 N 次，投票决出最终答案。

```
Input → ┌─ LLM Run 1 ─┐
        ├─ LLM Run 2 ─┤→ Vote → Output
        └─ LLM Run 3 ─┘
```

### 2.2 抽象类：ParallelizationService

```java
// org.demo06.workflows.parallel.ParallelizationService
package org.demo06.workflows.parallel;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public abstract class ParallelizationService {

    protected final ChatClient chatClient;

    protected ParallelizationService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明若干 worker（入参 input + sessionId，出参是单 worker 结果）。 */
    protected abstract List<BiFunction<String, String, String>> workers();

    /** 子类声明聚合策略（入参 worker 结果列表，出参是最终输出）。 */
    protected abstract Function<List<String>, String> aggregator();

    public String run(String input, String sessionId) {
        List<String> results = Flux.fromIterable(workers())
                .flatMap(worker -> Mono.fromCallable(() -> worker.apply(input, sessionId))
                                .subscribeOn(Schedulers.boundedElastic()),
                        workers().size())   // 全部并发
                .collectList()
                .block();

        return aggregator().apply(results);
    }

    protected String call(String systemPrompt, String userText, String sessionId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
```

**为什么不是 Advisor**：`Flux.flatMap` 在 `before()` 里 `block()` 等所有 worker 完成——再把聚合结果塞回 request 触发 N+1 次 LLM。改成 Service 后，聚合结果直接返回，零污染。

### 2.3 子类：Sectioning（多视角分析）

```java
// org.demo06.service.MultiAngleAnalysisService
@Service
public class MultiAngleAnalysisService extends ParallelizationService {

    public MultiAngleAnalysisService(ChatClient chatClient) { super(chatClient); }

    @Override
    protected List<BiFunction<String, String, String>> workers() {
        return List.of(
            (code, sid) -> call("从 bug 风险角度分析", code, sid),
            (code, sid) -> call("从代码风格角度分析", code, sid),
            (code, sid) -> call("从安全漏洞角度分析", code, sid)
        );
    }

    @Override
    protected Function<List<String>, String> aggregator() {
        return results -> String.join("\n\n---\n\n", results);
    }
}
```

### 2.4 子类：Voting（提升稳定性）

```java
// org.demo06.service.VotingReviewService
@Service
public class VotingReviewService extends ParallelizationService {

    public VotingReviewService(ChatClient chatClient) { super(chatClient); }

    @Override
    protected List<BiFunction<String, String, String>> workers() {
        BiFunction<String, String, String> worker = (code, sid) ->
                call("你是一个严格的代码评审，给出 1-10 分", code, sid);
        return List.of(worker, worker, worker);   // 同一 worker 跑 3 次
    }

    @Override
    protected Function<List<String>, String> aggregator() {
        return results -> {
            List<Integer> scores = results.stream()
                    .map(this::extractScore)
                    .sorted()
                    .toList();
            return "中位数评分: " + scores.get(scores.size() / 2);
        };
    }

    private int extractScore(String text) { /* 省略：正则抽 1-10 数字 */ return 5; }
}
```

### 2.5 Postman 测试用例

#### 用例 1：Sectioning 三视角并行

**目的**：验证 bug / 风格 / 安全三个 worker 真的并发执行（不是串行）。

- URL：`POST http://localhost:8096/demo06/workflow/parallel/sectioning`
- Headers：`Content-Type: text/plain`、`sessionId: parallel-001`
- Body（raw → Text，完整复制）：
  ```java
  public class UserService {
      private Map<String, User> cache = new HashMap<>();
      public User getUser(String id) {
          User u = cache.get(id);
          if (u == null) {
              u = loadFromDb(id);
              cache.put(id, u);
          }
          return u;
      }
  }
  ```
- **逻辑校验点**：
  - HTTP 200，响应非空；
  - 响应里能找到分隔符 `---`（`String.join("\n\n---\n\n", results)` 产生的）；
  - 后端 3 次 LLM 调用的时间戳**接近**（间隔 < 500ms），说明并发；如果是串行，相邻间隔通常 > 3s；
  - 总耗时 ≈ 单次最长 worker 的耗时，而不是 3 次之和。

#### 用例 2：Voting 中位数

**目的**：验证 3 次评分 → 中位数聚合的逻辑。

- URL：`POST http://localhost:8096/demo06/workflow/parallel/voting`
- Headers：`Content-Type: text/plain`、`sessionId: parallel-002`
- Body（raw → Text，完整复制，这段代码有 SQL 注入，预期评分较低）：
  ```java
  public class LoginService {
      public boolean login(String name, String pwd) {
          String sql = "select * from user where name='" + name + "' and pwd='" + pwd + "'";
          ResultSet rs = stmt.executeQuery(sql);
          return rs.next();
      }
  }
  ```
- **逻辑校验点**：
  
  - HTTP 200，响应形如 `中位数评分: N`（N 是 1-10 的整数）；
  - 后端 3 次 LLM 调用都是相同 system prompt（worker 复用 3 次）；
  - 中位数 N 必然在 3 个评分之间。

#### 用例 3：单个 worker 抛异常 → 验证聚合层是否容错

**目的**：`Flux.flatMap(...).collectList().block()` 在任一 worker 抛错时会让整个流失败。本用例**记录**该行为不是 bug。

操作步骤：
1. 临时改 `MultiAngleAnalysisService.workers()` 的第一个 worker：
   ```java
   (code, sid) -> { throw new RuntimeException("simulated"); }
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8096/demo06/workflow/parallel/sectioning`
- Headers：`Content-Type: text/plain`、`sessionId: parallel-003`
- Body：用例 1 的同一段 Java 代码。
- **逻辑校验点**：
  - 返回 500（不是 200 退化到部分结果）；
  - 日志能看到 `simulated` 异常栈；
  - 确认 ParallelizationService **没有内置容错**——生产用要补 `.onErrorResume(...)`。

---

## 3. Pattern 3: Routing

### 3.1 定义

根据输入类型路由到不同处理流程。

```
Input → [Router] → ┌─ Path A
                   ├─ Path B
                   └─ Path C
```

### 3.2 抽象类：RoutingService

```java
// org.demo06.workflows.routing.RoutingService
package org.demo06.workflows.routing;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public abstract class RoutingService {

    protected final ChatClient chatClient;

    protected RoutingService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明分类器（入参原始输入，出参路由 key）。 */
    protected abstract Function<String, String> classifier();

    /** 子类声明路由表：route key → handler（handler 入参 input + sessionId）。 */
    protected abstract Map<String, BiFunction<String, String, String>> handlers();

    /** 默认路由 key（必须存在于 handlers，否则 NPE）。 */
    protected abstract String defaultRoute();

    public String run(String input, String sessionId) {
        String route = classifier().apply(input);
        BiFunction<String, String, String> handler = handlers().getOrDefault(
                route, handlers().get(defaultRoute()));
        return handler.apply(input, sessionId);
    }

    protected String classify(String systemPrompt, String input) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(input)
                .call()
                .content();
    }

    protected String call(String systemPrompt, String userText, String sessionId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
```

**为什么不是 Advisor**：classifier + handler 都在 `before()` 里跑完，结果塞回 request 触发额外调用，且 classifier 子调用没传 sessionId 会 NPE。改成 Service 后路由路径上的两次 LLM 调用都可控。

### 3.3 子类：按代码类型路由

```java
// org.demo06.service.CodeRouterService
@Service
public class CodeRouterService extends RoutingService {

    public CodeRouterService(ChatClient chatClient) { super(chatClient); }

    @Override
    protected Function<String, String> classifier() {
        return code -> classify("""
                判断代码类型，只输出一个词：
                - controller / service / repository / model / other
                """, code).toLowerCase().trim();
    }

    @Override
    protected Map<String, BiFunction<String, String, String>> handlers() {
        return Map.of(
                "controller", (code, sid) -> call("你是 Controller 评审专家，重点检查路由、参数校验、异常处理", code, sid),
                "service",    (code, sid) -> call("你是 Service 评审专家，重点检查事务、业务逻辑、性能", code, sid),
                "repository", (code, sid) -> call("你是 Repository 评审专家，重点检查 SQL、索引、N+1", code, sid),
                "other",      (code, sid) -> call("你是通用代码评审专家", code, sid)
        );
    }

    @Override
    protected String defaultRoute() { return "other"; }
}
```

> **避坑**：`handlers` 里必须包含 `defaultRoute` 对应的 key，否则 `handlers.get(defaultRoute())` 返回 null 导致 NPE。

### 3.4 Postman 测试用例

#### 用例 1：明确命中的 Controller 路由

**目的**：验证 classifier 返回 `controller` → 走 Controller handler。

- URL：`POST http://localhost:8096/demo06/workflow/routing/code`
- Headers：`Content-Type: text/plain`、`sessionId: route-001`
- Body（raw → Text，完整复制）：
  ```java
  @RestController
  @RequestMapping("/api/users")
  public class UserController {
      @Autowired private UserService userService;
      @GetMapping("/{id}")
      public User get(@PathVariable Long id) { return userService.findById(id); }
      @PostMapping
      public User create(@RequestBody User user) { return userService.save(user); }
  }
  ```
- **逻辑校验点**：
  - HTTP 200，响应非空；
  - 响应里**应该**提到路由 / 参数校验 / 异常处理这些 Controller 评审关键词；
  - 日志能看到 classifier LLM 调用（1 次）+ handler LLM 调用（1 次），共 2 次。

#### 用例 2：未命中任何 handler → default route

**目的**：handlers 含 controller / service / repository / other 四类，输入一个 record 类应该走 `defaultRoute = "other"`。

- URL：`POST http://localhost:8096/demo06/workflow/routing/code`
- Headers：`Content-Type: text/plain`、`sessionId: route-002`
- Body（raw → Text，完整复制）：
  ```java
  public record UserDTO(String name, Integer age, String email) {}
  ```
- **逻辑校验点**：
  - HTTP 200（不报错）；
  - 日志 classifier 输出应是 `model` 或 `other`，最终都走 default handler；
  - RoutingService 不抛 NPE。

#### 用例 3：classifier 输出格式不规整 → 验证清洗

**目的**：classifier LLM 可能返回 `"Controller\n"` 或 `"controller。"` 带标点。验证 `.toLowerCase().trim()` 够不够。

操作步骤：
1. 临时改 `CodeRouterService.classifier()` 的 system prompt，加一句 `请在输出后加一个句号`：
   ```java
   return code -> classify("""
       判断代码类型，只输出一个词，并在末尾加一个句号：
       - controller / service / repository / model / other
       """, code).toLowerCase().trim();A
   ```
2. 重启应用，调用接口。

- URL：同上
- Headers：`sessionId: route-003`
- Body：用例 1 的同一段 Controller 代码。
- **逻辑校验点**：
  - 当前 `.toLowerCase().trim()` 不能去掉句号 → 路由会失败回到 default；
  - 这揭示了一个**潜在 bug**：classifier 输出需要 `.replaceAll("[^a-z]", "")` 之类的清洗；
  - 通过 Postman 看到 default route 命中即可（说明 fallback 有效）。

---

## 4. Pattern 4: Orchestrator-Workers

### 4.1 与 Routing 的区别

Routing 是**静态**路由（事先定义好分类）。
Orchestrator-Workers 是**动态**分配：LLM 看了输入后**自己决定**要拆成几个子任务。

```
Input → [Orchestrator LLM]
            ↓ 决定子任务列表
        ┌───┴───┐
        ↓       ↓
     Worker  Worker   ... 动态数量
        ↓       ↓
        └───┬───┘
            ↓
       [Aggregator]
            ↓
         Output
```

### 4.2 抽象类：OrchestratorService

```java
// org.demo06.workflows.orchestrator.OrchestratorService
package org.demo06.workflows.orchestrator;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.BiFunction;

public abstract class OrchestratorService {

    protected final ChatClient chatClient;

    protected OrchestratorService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明 worker 函数（入参子任务描述 + sessionId，出参是 worker 结果）。 */
    protected abstract BiFunction<String, String, String> worker();

    public String run(String input, String sessionId) {
        // 1. orchestrator 决定子任务
        String planJson = chatClient.prompt()
                .system("""
                        把任务拆成若干独立子任务，输出 JSON：
                        {"subtasks": ["任务1", "任务2", ...]}
                        只输出 JSON。
                        """)
                .user(input)
                .call()
                .content();

        List<String> subtasks = parseSubtasks(planJson);

        // 2. 并行执行 worker
        List<String> results = Flux.fromIterable(subtasks)
                .flatMap(st -> Mono.fromCallable(() -> worker().apply(st, sessionId))
                                .subscribeOn(Schedulers.boundedElastic()),
                        Math.min(subtasks.size(), 5))
                .collectList()
                .block();

        // 3. 聚合
        return chatClient.prompt()
                .system("把以下子任务结果整合为完整报告")
                .user("原任务：" + input + "\n子任务结果：" + String.join("\n---\n", results))
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    /** 兜底解析：抽第一个 [...] 子串再 parse，避免 LLM 加前缀文字。 */
    protected List<String> parseSubtasks(String json) {
        if (json == null || json.isBlank()) return List.of();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        String array = json.substring(start, end + 1);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(array, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

**为什么不是 Advisor**：orchestrator + N 个 worker + aggregator 三次（+）LLM 调用全在 `before()` 里跑完，再塞回 request 又多调一次。改成 Service 后聚合结果直接返回。

### 4.3 子类：长文档摘要

```java
// org.demo06.service.LongDocSummaryService
@Service
public class LongDocSummaryService extends OrchestratorService {

    public LongDocSummaryService(ChatClient chatClient) { super(chatClient); }

    @Override
    protected BiFunction<String, String, String> worker() {
        return (subtask, sid) -> chatClient.prompt()
                .system("你是文档子任务执行者，按子任务描述处理原文")
                .user(subtask)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .content();
    }
}
```

### 4.4 Postman 测试用例

#### 用例 1：长文档 → 验证 orchestrator 动态拆分

**目的**：验证 orchestrator 能根据内容**自己决定**几个子任务。

- URL：`POST http://localhost:8096/demo06/workflow/orchestrator/summary`
- Headers：`Content-Type: text/plain`、`sessionId: orch-001`
- Body（raw → Text，完整复制以下博客内容）：
  ```
  《Spring Boot 启动原理深度剖析》
  
  Spring Boot 是 Spring 团队推出的快速开发框架，其核心价值在于"约定优于配置"。
  本文从 SpringApplication.run() 入口开始，逐层剖析启动流程。
  
  一、SpringApplication 实例化阶段
  在 main 方法中调用 SpringApplication.run(MyApp.class, args) 时，
  首先会创建一个 SpringApplication 实例。构造函数中会推断应用类型（SERVLET / REACTIVE / NONE），
  从 META-INF/spring.factories 中加载 ApplicationContextInitializer 和 ApplicationListener。
  
  二、prepareEnvironment 阶段
  这一阶段会创建并配置 Environment 对象，加载 application.properties / application.yaml，
  触发 ApplicationEnvironmentPreparedEvent 事件。
  
  三、createApplicationContext 阶段
  根据应用类型创建对应的 ApplicationContext：Servlet 用 AnnotationConfigServletWebServerApplicationContext，
  Reactive 用 AnnotationConfigReactiveWebServerApplicationContext。
  
  四、refreshContext 阶段
  这是启动的核心环节，依次执行 BeanFactoryPostProcessor、注册 BeanPostProcessor、
  实例化单例 Bean、启动内嵌 Tomcat / Netty 服务器。
  
  五、afterRefresh 阶段
  触发 ApplicationStartedEvent 和 ApplicationReadyEvent，应用正式对外提供服务。
  
  总结：理解 Spring Boot 启动流程对排查 Bean 创建失败、配置不生效等问题至关重要。
  ```
- **逻辑校验点**：
  - HTTP 200，响应非空；
  - 后端 LLM 调用次数 = 1（orchestrator 计划）+ N（worker 并行）+ 1（最终聚合），N 通常是 2-5；
  - `parseSubtasks(...)` 正确解析（日志不报 JSON 解析错）；
  - 聚合响应里能找到原任务的关键词。

#### 用例 2：超短输入 → orchestrator 决定不拆

**目的**：短输入下 orchestrator 应返回单元素数组。

- URL：同上
- Headers：`sessionId: orch-002`
- Body（raw → Text）：
  ```
  今天天气不错
  ```
- **逻辑校验点**：
  - HTTP 200；
  - 日志：`parseSubtasks(...)` 返回 List size = 1；
  - 总 LLM 调用次数 = 1 + 1 + 1 = 3 次（不浪费并行配额）。

#### 用例 3：orchestrator JSON 格式错乱 → 验证兜底解析

**目的**：LLM 偶尔会在 JSON 前后加文字。验证 `parseSubtasks` 是否有 `[` 截取兜底。

操作步骤：
1. 临时改 `OrchestratorService.run()` 的 orchestrator system prompt，加一句：
   ```
   输出前先说一句"好的，我帮你拆分："，然后再输出 JSON。
   ```
2. 重启应用，调用接口。

- URL：同上
- Headers：`sessionId: orch-003`
- Body：用例 1 的同一段博客内容。
- **逻辑校验点**：
  - 因为 `parseSubtasks` 做了 `[` `]` 截取 → 应返回 200；
  - 如果你的版本是裸 Jackson 解析 → 会抛 500；
  - 不管哪种结果，都帮你确认兜底逻辑是否需要补强。

---

## 5. Pattern 5: Evaluator-Optimizer

### 5.1 定义

LLM 生成 → 评估 → 不合格则反馈给 LLM 重做，直到合格或达到最大次数。

```
Input → [Generator] → Output → [Evaluator] → pass?
                                            ├ yes → return
                                            └ no  → feedback → loop back
```

### 5.2 抽象类：EvaluatorOptimizerService

```java
// org.demo06.workflows.evaluator.EvaluatorOptimizerService
package org.demo06.workflows.evaluator;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

public abstract class EvaluatorOptimizerService {

    protected final ChatClient chatClient;
    private final int maxIterations;

    protected EvaluatorOptimizerService(ChatClient chatClient, int maxIterations) {
        this.chatClient = chatClient;
        this.maxIterations = maxIterations;
    }

    /** 子类声明 generator（入参需求 + sessionId，出参是生成结果）。 */
    protected abstract java.util.function.BiFunction<String, String, String> generator();

    /** 子类声明 evaluator（入参需求 + 生成结果 + sessionId，出参是评估结果）。 */
    protected abstract java.util.function.TriFunction<String, String, String, EvalResult> evaluator();

    public String run(String input, String sessionId) {
        String output = generator().apply(input, sessionId);

        for (int i = 0; i < maxIterations; i++) {
            EvalResult eval = evaluator().apply(input, output, sessionId);
            if (eval.pass()) {
                return output;
            }
            // 用 feedback 重新生成
            output = generator().apply(input + "\n\n之前的输出有这些问题，请改进：\n" + eval.feedback(), sessionId);
        }
        return output;   // maxIterations 用完强制退出
    }

    public record EvalResult(boolean pass, String feedback) {}

    protected String call(String systemPrompt, String userText, String sessionId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
```

> 注意：JDK 没有自带 `TriFunction`，需要自己声明一个 `@FunctionalInterface`：
> ```java
> @FunctionalInterface
> public interface TriFunction<A, B, C, R> {
>     R apply(A a, B b, C c);
> }
> ```

**为什么不是 Advisor**：generator + evaluator 在 `before()` 里循环跑完，每轮 2 次 LLM 调用，最多 6 次。如果塞回 request 再触发一次，最终输出会被最后这次"无 system prompt"的调用覆盖——这正是反模式最危险的地方。

### 5.3 子类：代码生成 + 自动改进

```java
// org.demo06.service.CodeRefinerService
@Service
public class CodeRefinerService extends EvaluatorOptimizerService {

    public CodeRefinerService(ChatClient chatClient) {
        super(chatClient, 3);   // 最多重做 3 次
    }

    @Override
    protected BiFunction<String, String, String> generator() {
        return (req, sid) -> call("生成高质量的 Java 代码", req, sid);
    }

    @Override
    protected TriFunction<String, String, String, EvalResult> evaluator() {
        return (req, code, sid) -> {
            String eval = chatClient.prompt()
                    .system("""
                            判断代码是否合格，输出 JSON：
                            {"pass": true/false, "feedback": "..."}
                            只输出 JSON。
                            """)
                    .user("需求：" + req + "\n代码：" + code)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                    .call()
                    .content();
            boolean pass = eval.contains("\"pass\": true");
            String feedback = pass ? "" : eval;
            return new EvalResult(pass, feedback);
        };
    }
}
```

### 5.4 Postman 测试用例

#### 用例 1：一次通过 → 验证循环只跑 1 轮

**目的**：evaluator 第一次就 pass=true，验证 `for` 循环 break 生效。

- URL：`POST http://localhost:8096/demo06/workflow/eval/refine`
- Headers：`Content-Type: text/plain`、`sessionId: eval-001`
- Body（raw → Text）：
  ```
  写一个函数，把 List<String> 反转后返回
  ```
- **逻辑校验点**：
  - HTTP 200，响应是一段 Java 代码（含 `public`、`return` 等）；
  - 日志：generator 1 次 + evaluator 1 次 = 共 2 次 LLM 调用（没进入第 2 轮）。

#### 用例 2：多次迭代 → 验证反馈回路

**目的**：evaluator 连续 fail，最多迭代 3 次。

操作步骤：
1. 临时改 `CodeRefinerService.evaluator()`，强制永远 fail：
   ```java
   return (req, code, sid) -> new EvalResult(false, "always fail");
   ```
2. 重启应用，调用接口。

- URL：同上
- Headers：`sessionId: eval-002`
- Body：用例 1 的同一段需求文本。
- **逻辑校验点**：
  - HTTP 200，不会死循环（`maxIterations = 3` 强制退出）；
  - 日志：generator 调用次数 = 1 + 3 = 4 次（初次 + 3 次重做）；
  - evaluator 调用次数 = 3 次；
  - 总 LLM 调用 = 7 次（注意成本！）。

#### 用例 3：evaluator JSON 解析失败 → 验证容错

**目的**：LLM 返回的 JSON 可能不合规（带前缀 / 字段缺失），验证 `evaluator()` 的解析是否健壮。

操作步骤：
1. 临时改 evaluator 让它返回非 JSON（去掉正常 LLM 调用）：
   ```java
   return (req, code, sid) -> {
       String eval = "评估完成，代码不错";   // 非 JSON
       boolean pass = eval.contains("\"pass\": true");  // false
       return new EvalResult(pass, eval);
   };
   ```
2. 重启应用，调用接口。

- URL：同上
- Headers：`sessionId: eval-003`
- Body：任意需求文本。
- **逻辑校验点**：
  - HTTP 200（因为有 `pass = false` 走重做路径，最终 maxIterations 退出）；
  - 如果你的 evaluator 用裸 Jackson 解析 → 抛异常 → 500；
  - 这帮你确认 evaluator 的解析鲁棒性是否达到生产级别。

---

## 6. 综合实战：单文件代码评审助手

把五大模式组合起来，做一个真实可用的项目。

### 6.1 项目目标

输入一个 Java 文件 → 输出专业评审报告（bug + 风格 + 安全 + 改进建议）。

### 6.2 五大模式如何配合

| 模式 | 怎么用 |
|------|-------|
| **Routing** | 按代码类型路由（Controller / Service / Repository） |
| **Parallelization** | 并行评审 bug / 风格 / 安全 三视角 |
| **Orchestrator-Workers** | 文件超过 N 行时按方法拆分评审 |
| **Evaluator-Optimizer** | 评审报告质量不达标则改进 |
| **Prompt Chaining** | 总流程：分类 → 评审 → 聚合 → 优化 |

### 6.3 为什么不用单一 Agent

| 维度 | 自主 Agent | 五大模式组合 |
|------|-----------|------------|
| 稳定性 | LLM 可能跑偏 | 每步确定 |
| 成本 | LLM 决定调几次工具 | 固定调几次 LLM |
| 可观测 | 黑盒 | 每步日志可见 |
| 延迟 | 不确定 | 可预估 |

### 6.4 核心代码

> 注意：综合实战不再是单个抽象类，而是 **`@Service` 编排多个 Service**。这正是 Workflow 的最终形态——确定性 DAG，由 Java 代码控制流转，不是 Advisor 链。

```java
// org.demo06.service.CodeReviewService
// 本代码仅作学习材料参考

@Service
public class CodeReviewService {

    private final ChatClient client;
    private final CodeRouterService routerService;
    private final MultiAngleAnalysisService analysisService;

    public CodeReviewService(ChatClient client,
                              CodeRouterService routerService,
                              MultiAngleAnalysisService analysisService) {
        this.client = client;
        this.routerService = routerService;
        this.analysisService = analysisService;
    }

    public String review(String code, String sessionId) {
        // Step 1: Routing（分类）
        String typedReview = routerService.run(code, sessionId);

        // Step 2: Orchestrator-Workers（大文件按方法拆分，由 LongDocSummaryService 等处理，省略）

        // Step 3: Parallelization（三视角并行）
        String multiAngle = analysisService.run(code, sessionId);

        // Step 4: Aggregation（聚合）
        String aggregated = client.prompt()
                .system("把以下两份评审合并为一份专业报告")
                .user("按类型评审：" + typedReview + "\n\n三视角评审：" + multiAngle)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();

        // Step 5: Evaluator-Optimizer（质量优化）
        return refine(aggregated, code, sessionId);
    }

    private String refine(String report, String code, String sessionId) {
        for (int i = 0; i < 3; i++) {
            String eval = client.prompt()
                    .system("判断评审报告是否完整、专业。输出 JSON：{\"pass\":true/false}")
                    .user("代码：" + code + "\n报告：" + report)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
            if (eval.contains("\"pass\": true")) {
                return report;
            }
            report = client.prompt()
                    .system("改进评审报告，让它更专业、更具体")
                    .user("原报告：" + report + "\n代码：" + code)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
        }
        return report;
    }
}
```

### 6.5 Controller

```java
@RestController
@RequestMapping("/demo06/review")
public class ReviewController {

    private final CodeReviewService service;

    public ReviewController(CodeReviewService service) {
        this.service = service;
    }

    @PostMapping
    public String review(@RequestBody String code, @RequestHeader String sessionId) {
        return service.review(code, sessionId);
    }
}
```

### 6.6 Postman 测试用例

综合实战串联五大模式，重点验证**模式间协作**。

#### 用例 1：常规 Controller 文件 → 验证完整流水线

**目的**：覆盖 Routing → Parallelization → Aggregation → Evaluator。

- URL：`POST http://localhost:8096/demo06/review`
- Headers：`Content-Type: text/plain`、`sessionId: review-001`
- Body（raw → Text，完整复制以下 Controller 代码）：
  ```java
  @RestController
  @RequestMapping("/api/orders")
  public class OrderController {
  
      @Autowired
      private OrderService orderService;
  
      @GetMapping("/{id}")
      public Order get(@PathVariable Long id) {
          return orderService.findById(id);
      }
  
      @PostMapping
      public Order create(@RequestBody Order order) {
          return orderService.save(order);
      }
  
      @DeleteMapping("/{id}")
      public void delete(@PathVariable Long id) {
          orderService.delete(id);
      }
  }
  ```
- **逻辑校验点**：
  - HTTP 200，响应是结构化报告（含 bug / style / security 三个视角的痕迹）；
  - 后端 LLM 调用次数 ≈ 2（router 分类 + handler）+ 3（三视角并行）+ 1（聚合）+ 1-3（refine 迭代）= 7-9 次；
  - 总耗时 < 60s；
  - 日志能看到 `[Routing]` `[Parallelization]` `[Aggregation]` `[Evaluator]` 各阶段标记（如果你在 service 里加了 log）。

#### 用例 2：超大文件 → 验证 Orchestrator 拆分路径

**目的**：触发按方法拆分多段并行评审。

- URL：同上
- Headers：`sessionId: review-002`
- Body（raw → Text，把以下 Service 代码连续粘贴 5 次，模拟 500+ 行长文件）：
  ```java
  @Service
  public class PaymentService {
  
      @Autowired private PaymentRepository paymentRepository;
      @Autowired private PaymentGateway paymentGateway;
  
      @Transactional
      public Payment pay(Long orderId, BigDecimal amount, String channel) {
          Order order = orderRepository.findById(orderId).orElseThrow();
          if (order.getPaid()) {
              throw new IllegalStateException("already paid");
          }
          Payment payment = new Payment();
          payment.setOrderId(orderId);
          payment.setAmount(amount);
          payment.setChannel(channel);
          PaymentResult result = paymentGateway.charge(payment);
          if (result.isSuccess()) {
              payment.setStatus("PAID");
              order.setPaid(true);
          } else {
              payment.setStatus("FAILED");
          }
          return paymentRepository.save(payment);
      }
  
      public Payment refund(Long paymentId) {
          Payment payment = paymentRepository.findById(paymentId).orElseThrow();
          if (!"PAID".equals(payment.getStatus())) {
              throw new IllegalStateException("cannot refund");
          }
          RefundResult result = paymentGateway.refund(payment);
          if (result.isSuccess()) {
              payment.setStatus("REFUNDED");
          }
          return paymentRepository.save(payment);
      }
  }
  ```
- **逻辑校验点**：
  - HTTP 200；
  - 日志：拆分后 chunk 数 ≥ 2；
  - LLM 调用次数 = 1（分类）+ chunk 数 × 3 视角（并行）+ 1（聚合）+ 1-3（refine）；
  - 总耗时 < 90s。

#### 用例 3：非 Java 内容 → 验证降级

**目的**：上传一段 Markdown / JSON，验证 classifier 走 default 路径不爆。

- URL：同上
- Headers：`sessionId: review-003`
- Body（raw → Text，完整复制）：
  ```
  # README
  
  This is not Java code.
  
  ## Install
  
  Run `npm install` to setup dependencies.
  ```
- **逻辑校验点**：
  - HTTP 200（不应该 500）；
  - 响应里说明 "无法识别为 Java 代码" 或走 default 评审视角；
  - 日志 classifier 输出 `other`。

#### 用例 4：空请求体 → 验证边界

**目的**：空 body 不应让流水线崩溃。

- URL：同上
- Headers：`sessionId: review-004`
- Body：（完全留空）
- **逻辑校验点**：
  - 应返回 400 Bad Request（`@RequestBody String code` 必填）；
  - 如果你的 `@RequestBody(required = false)` 允许 null，则应返回 200 但响应里说明"输入为空"；
  - 不应 500 NPE。

#### 用例 5：并发压测 → 验证 Parallelization 的线程池

**目的**：`Schedulers.boundedElastic()` 在并发请求下不应该线程饥饿。

Postman 配置步骤：
1. 保存用例 1 的请求到一个 Collection；
2. 点 Runner → 选 Collection → Iterations = 10 → 勾选 "Run in parallel"；
3. 设置请求超时 120s，开始运行。

- Body：使用用例 1 的相同 Controller 代码。
- **逻辑校验点**：
  - 全部 200，无超时；
  - 后端不会因 `boundedElastic` 队列满而拒绝（默认队列 100k）；
  - 平均响应时间不应是单次响应时间的 10 倍（说明并行确实在跑）。

---

## 7. 模式选择决策树

```
任务能拆成线性步骤吗？
├── 能 → Prompt Chaining
└── 否 →
    任务有多个独立子任务吗？
    ├── 是 → Parallelization（Sectioning）
    └── 否 →
        需要提升结果稳定性吗？
        ├── 是 → Parallelization（Voting）
        └── 否 →
            输入类型决定了不同处理？
            ├── 是 → Routing
            └── 否 →
                子任务数量事先不知道？
                ├── 是 → Orchestrator-Workers
                └── 否 →
                    结果可被评估并改进？
                    ├── 是 → Evaluator-Optimizer
                    └── 否 → 自主 Agent（最后手段）
```

---

## 8. 反模式

### 8.1 用 Advisor 实现 Workflow（最大反模式）

```
# ❌ 反模式
public class XxxAdvisor implements BaseAdvisor {
    public ChatClientRequest before(...) {
        // 在这里跑完所有业务
        // 再把结果塞回 request → 触发额外 LLM 调用 → 业务结果被污染
    }
}
```

Advisor 是**横切关注点**（记忆 / 日志 / 重试 / 输出校验），**不是业务编排**。详见 §0.1。

### 8.2 把 Workflow 写成自主 Agent

```
# ❌ 反模式
"你是一个代码评审员，自由发挥"
```

LLM 可能：调工具调到爆、跑题、漏步骤。

```
# ✅ 正模式
明确告诉 LLM：先做 A，再做 B，最后做 C
```

### 8.3 不必要的并行

简单任务硬上 Parallelization，结果 3 倍成本但收益不明显。

**判断**：只有当 LLM 调用是瓶颈（>5s）时并行才有意义。

### 8.4 Evaluator-Optimizer 死循环

LLM 评估器永远说"不合格"。设 maxIterations=3 强制退出。

### 8.5 Routing 分类错

分类器用便宜模型理解不够。**Routing 用强模型，Worker 用便宜模型**。

### 8.6 子调用忘传 sessionId

在 Service 里调 `chatClient.prompt()` 时如果忘传 `CONVERSATION_ID`，而 ChatClient 默认配置了 `MessageChatMemoryAdvisor`，会直接抛 `IllegalArgumentException: conversationId cannot be null`。

**修法**：把 sessionId 透传到每个子调用，参考 `ChainingService.call(...)` 的样板。

---

## 9. 与 09 篇（多 Agent 编排）的关系

| 维度 | 五大 Workflow 模式（本文） | 多 Agent / Graph（09 篇） |
|------|--------------------------|------------------|
| 抽象层次 | `@Service` 编排（编译时确定） | 状态机图（运行时确定） |
| 复杂度 | 低 | 中高 |
| 灵活性 | 固定流程 | 动态流转、循环、条件分支 |
| 适用 | 80% 企业场景 | 复杂决策、需要 checkpoint 的长程任务 |

**建议**：先用 Workflow 模式跑通业务，撑不住时再上 Graph（如 demo05 的 ReportPipelineGraph）。

---

## 10. 实战任务

1. 实现 `ChainingService` 抽象类 + `ArticleChainingService` 子类，跑通"大纲 → 草稿 → 润色"文章生成。
2. 实现 `ParallelizationService`，三视角并行评审代码。
3. 实现 `RoutingService`，按代码类型路由。
4. 实现 `OrchestratorService`，对长文档动态拆分。
5. 实现 `EvaluatorOptimizerService`，自动改进 LLM 输出。
6. 把五大 Service 组合成 §6 的代码评审助手，跑通一个真实文件。
7. （进阶）把五大抽象类抽到 `org.demo0X.workflows.*` 包，做成可复用工具库。
8. （选做）评估自主 Agent vs Workflow 模式在同一任务上的成本差异。

---

## 11. 理解检查

1. 五大模式各自适用什么场景？
2. 为什么 Workflow 用 `@Service` 实现而不是 Advisor？（最重要的题）
3. Routing 和 Orchestrator-Workers 的本质区别？
4. Sectioning 和 Voting 的区别？什么时候用 Voting？
5. Evaluator-Optimizer 怎么避免死循环？
6. 为什么"Workflow > Agent"？什么场景必须用 Agent？
7. 五大模式在代码评审助手里如何协作？

---

## 12. 相关文档

- [`./10-多Agent编排实战.md`](./10-多Agent编排实战.md) —— 撑不住 Workflow 时升级到 Graph
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 基础（横切关注点）
- [Anthropic Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Spring AI Reference — Prompt Engineering Patterns](https://docs.spring.io/spring-ai/reference/api/chat/prompt-engineering-patterns.html)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
