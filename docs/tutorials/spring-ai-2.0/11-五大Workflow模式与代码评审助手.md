# 10 五大 Workflow 模式与代码评审助手

> 本文合并自原 05「五大 Workflow 模式 Advisor 实现」+ 原 06「组合实战代码评审助手」。
>
> Anthropic《Building Effective Agents》(2024-12-19) 的五大模式全部封装为可复用 Advisor，最后用代码评审助手把五大模式串起来。
>
> **核心心法**：**Workflow > Agent**。能用确定性 DAG 解决的，不要用自主 Agent。80%+ 企业场景能用 Workflow 解决，更稳定、更便宜、更可观测。
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

代码组织：所有模式的 Advisor 放在 `org.demo02.toolkit.workflow` 包下，每个文件独立、可复用。

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

### 1.3 实现：PromptChainingAdvisor

```java
// org.demo02.toolkit.workflow.PromptChainingAdvisor
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.function.Function;

public class PromptChainingAdvisor implements BaseAdvisor {

    private final List<Function<String, String>> steps;

    public PromptChainingAdvisor(List<Function<String, String>> steps) {
        this.steps = steps;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String input = req.prompt().getUserMessage().getText();
        String current = input;
        for (Function<String, String> step : steps) {
            current = step.apply(current);
            if (current == null) {
                // 任何一步返回 null 即终止链（gate check）
                return req.mutate()
                        .prompt(req.prompt().mutate()
                                .messages(new UserMessage("[CHAIN TERMINATED]"))
                                .build())
                        .build();
            }
        }
        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(current))
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        return resp;
    }

    @Override public String getName() { return "PromptChainingAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }
}
```

### 1.4 使用

```java
@Bean
public PromptChainingAdvisor writeArticleAdvisor(ChatClient client) {
    return new PromptChainingAdvisor(List.of(
            // Step 1：大纲
            input -> client.prompt()
                    .system("生成文章大纲，只输出大纲")
                    .user(input).call().content(),
            // Step 2：草稿
            outline -> client.prompt()
                    .system("根据大纲生成草稿")
                    .user(outline).call().content(),
            // Step 3：润色
            draft -> client.prompt()
                    .system("润色文章，让它更流畅")
                    .user(draft).call().content()
    ));
}
```

### 1.5 何时加 gate check

中间步骤可以判断"上一步质量够不够继续"，不够就提前终止：

```java
input -> {
    String result = client.prompt()...call().content();
    if (result.length() < 50) {
        return null;   // 触发终止
    }
    return result;
}
```

### 1.6 Postman 测试用例

为方便测试，先补一个最小 controller，把链路暴露成 HTTP 接口：

```java
// org.demo02.project.workflow.WorkflowController
// 本代码仅作学习材料参考
@RestController
@RequestMapping("/workflow/chaining")
public class WorkflowController {

    private final ChatClient client;
    private final PromptChainingAdvisor writeArticleAdvisor;

    public WorkflowController(ChatClient client, PromptChainingAdvisor writeArticleAdvisor) {
        this.client = client;
        this.writeArticleAdvisor = writeArticleAdvisor;
    }

    @PostMapping("/article")
    public String article(@RequestBody String topic) {
        return client.prompt()
                .advisors(writeArticleAdvisor)
                .user(topic)
                .call()
                .content();
    }
}
```

> 用例设计原则：只验证**逻辑路径**（响应非空、字段存在、分支命中），不验证内容准确性。

#### 用例 1：常规三步链（happy path）

**目的**：验证 Outline → Draft → Polish 三步线性执行。

- Method：`POST`
- URL：`http://localhost:8080/workflow/chaining/article`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```
  Spring AI 2.0 的 Advisor 链机制：从 ChatClientRequest 到 ChatClientResponse 的流转原理
  ```

**逻辑校验点**（不查具体内容）：
- HTTP 200；
- 响应非空字符串，长度 > 100（说明三步链都被执行了，不是单个 LLM 调用）；
- 后端日志能看到 3 次 chat completion 调用（每次时间戳不同）。

#### 用例 2：空输入 → 验证 gate check / 链不中断

**目的**：确认空字符串输入不会让链提前返回 `[CHAIN TERMINATED]`，而是被 LLM 自行处理（取决于系统提示）。

- Method：`POST`
- URL：`http://localhost:8080/workflow/chaining/article`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，只放一个空格）：
  ```
   
  ```

**逻辑校验点**：
- HTTP 200（不应 500）；
- 响应不为 `[CHAIN TERMINATED]`（除非你的 step 1 显式 gate）；
- 后端只调用 1-3 次 LLM（不会因空输入死循环）。

#### 用例 3：超长输入 → 验证 token budget 不爆

**目的**：链上每一步输出都会进入下一步，长度会膨胀，确认不会触发 context overflow。

- Method：`POST`
- URL：`http://localhost:8080/workflow/chaining/article`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制以下段落，重复 5 次粘到 Postman 即可达 ~3000 字）：
  ```
  Spring AI 是 Spring 官方推出的 AI 应用开发框架，2.0 版本对 Advisor 链机制做了彻底重构。
  在 1.0 时代，Advisor 通过 @Around 拦截 ChatClient 的 call/stream 方法实现；2.0 将整个调用流程拆成
  ChatClientRequest -> AdvisorChain -> ChatClientResponse 三个阶段，每个 Advisor 既可以在 before 阶段
  修改请求（注入系统提示、拼接上下文、添加工具），也可以在 after 阶段处理响应（脱敏、打日志、缓存）。
  这种两阶段模型让 Advisor 的职责更清晰，也让 BaseAdvisor 接口的实现者可以聚焦于单一关注点。
  Advisor 的执行顺序由 getOrder() 决定，Spring 沿用了经典的 Ordered 语义：值越小越先执行 before、
  越后执行 after。在 AdvisorChain 内部，所有 Advisor 按 order 排序后串成一条责任链，请求从最高优先级
  流向最低优先级，响应则反向回流。掌握这条规则后，你可以精准控制日志、记忆、安全检查、缓存等横切
  关注点的执行时机。本文剩余部分会通过五个真实业务场景展示 Advisor 链的组合方式。
  ```

**逻辑校验点**：
- HTTP 200；
- 三步执行完，响应长度不应比输入短太多（说明没在第 2 步被截断）；
- 日志无 `ContextLengthExceededException`。

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

### 2.2 实现：ParallelizationAdvisor

```java
// org.demo02.toolkit.workflow.ParallelizationAdvisor
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Function;

public class ParallelizationAdvisor implements BaseAdvisor {

    private final List<Function<String, String>> workers;
    private final Function<List<String>, String> aggregator;

    public ParallelizationAdvisor(List<Function<String, String>> workers,
                                   Function<List<String>, String> aggregator) {
        this.workers = workers;
        this.aggregator = aggregator;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String input = req.prompt().getUserMessage().getText();

        List<String> results = Flux.fromIterable(workers)
                .flatMap(worker -> Mono.fromCallable(() -> worker.apply(input))
                        .subscribeOn(Schedulers.boundedElastic()),
                        workers.size())   // 全部并发
                .collectList()
                .block();

        String aggregated = aggregator.apply(results);

        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(aggregated))
                        .build())
                .build();
    }

    @Override public String getName() { return "ParallelizationAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }
}
```

### 2.3 使用：Sectioning（多视角分析）

```java
@Bean
public ParallelizationAdvisor multiAngleAnalysis(ChatClient client) {
    return new ParallelizationAdvisor(
            List.of(
                    input -> client.prompt()
                            .system("从 bug 风险角度分析")
                            .user(input).call().content(),
                    input -> client.prompt()
                            .system("从代码风格角度分析")
                            .user(input).call().content(),
                    input -> client.prompt()
                            .system("从安全漏洞角度分析")
                            .user(input).call().content()
            ),
            results -> String.join("\n\n---\n\n", results)
    );
}
```

### 2.4 使用：Voting（提升稳定性）

```java
@Bean
public ParallelizationAdvisor votingReviewer(ChatClient client) {
    Function<String, String> worker = input -> client.prompt()
            .system("你是一个严格的代码评审，给出 1-10 分")
            .user(input).call().content();

    return new ParallelizationAdvisor(
            List.of(worker, worker, worker),
            results -> {
                // 投票：取中位数
                List<Integer> scores = results.stream()
                        .map(s -> extractScore(s))
                        .sorted()
                        .toList();
                return "中位数评分: " + scores.get(scores.size() / 2);
            }
    );
}
```

### 2.5 Postman 测试用例

Sectioning 和 Voting 是两种并行子模式，**关键验证点是"并发是否真的发生"**（看 LLM 调用时间戳）。先补 controller：

```java
// org.demo02.project.workflow.WorkflowController（续）
// 本代码仅作学习材料参考

@Autowired private ParallelizationAdvisor multiAngleAnalysis;
@Autowired private ParallelizationAdvisor votingReviewer;

@PostMapping("/parallel/sectioning")
public String sectioning(@RequestBody String code) {
    return client.prompt().advisors(multiAngleAnalysis).user(code).call().content();
}

@PostMapping("/parallel/voting")
public String voting(@RequestBody String code) {
    return client.prompt().advisors(votingReviewer).user(code).call().content();
}
```

#### 用例 1：Sectioning 三视角并行

**目的**：验证 bug / 风格 / 安全三个 worker 真的并发执行（不是串行）。

- URL：`POST http://localhost:8080/workflow/parallel/sectioning`
- Headers：`Content-Type: text/plain`
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
      public void deleteUser(String id) {
          cache.remove(id);
          db.delete(id);
      }
  }
  ```

**逻辑校验点**：
- HTTP 200，响应非空；
- 响应里能找到分隔符 `---`（`String.join("\n\n---\n\n", results)` 产生的）；
- 后端 3 次 LLM 调用的时间戳**接近**（间隔 < 500ms），说明并发；如果是串行，相邻间隔通常 > 3s。
- 总耗时 ≈ 单次最长 worker 的耗时，而不是 3 次之和。

#### 用例 2：Voting 中位数

**目的**：验证 3 次评分 → 中位数聚合的逻辑。

- URL：`POST http://localhost:8080/workflow/parallel/voting`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制，这段代码有多处明显问题，预期评分较低）：
  ```java
  public class LoginService {
      public boolean login(String name, String pwd) {
          String sql = "select * from user where name='" + name + "' and pwd='" + pwd + "'";
          ResultSet rs = stmt.executeQuery(sql);
          if (rs.next()) {
              return true;
          }
          return false;
      }
  }
  ```

**逻辑校验点**：
- HTTP 200，响应形如 `中位数评分: N`（N 是 1-10 的整数）；
- 后端 3 次 LLM 调用都是相同 system prompt（worker 复用 3 次）；
- 中位数 N 必然在 3 个评分之间（如返回 `中位数评分: 6`，日志中应能看到 3 个评分中包含 6 或 6 是中位）。

#### 用例 3：单个 worker 抛异常 → 验证聚合层是否容错

**目的**：ParallelizationAdvisor 当前实现里 `Flux.flatMap(...).collectList().block()` 在任一 worker 抛错时会让整个流失败。这是已知行为，本用例用来**记录**该行为不是 bug。

操作步骤：
1. 临时改 `multiAngleAnalysis` Bean，把第 1 个 worker 改成：
   ```java
   input -> { throw new RuntimeException("simulated"); }
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8080/workflow/parallel/sectioning`
- Headers：`Content-Type: text/plain`
- Body：用例 1 的同一段 Java 代码即可。

**逻辑校验点**：
- 返回 500（不是 200 退化到部分结果）；
- 日志能看到 `simulated` 异常栈；
- 确认 ParallelizationAdvisor **没有内置容错**——生产用要补 `.onErrorResume(...)`。

---

## 3. Pattern 3: Routing

### 3.1 定义

根据输入类型路由到不同处理流程。

```
Input → [Router] → ┌─ Path A
                   ├─ Path B
                   └─ Path C
```

### 3.2 实现：RoutingAdvisor

```java
// org.demo02.toolkit.workflow.RoutingAdvisor
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Map;
import java.util.function.Function;

public class RoutingAdvisor implements BaseAdvisor {

    private final Function<String, String> classifier;
    private final Map<String, Function<String, String>> handlers;
    private final String defaultRoute;

    public RoutingAdvisor(Function<String, String> classifier,
                          Map<String, Function<String, String>> handlers,
                          String defaultRoute) {
        this.classifier = classifier;
        this.handlers = handlers;
        this.defaultRoute = defaultRoute;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String input = req.prompt().getUserMessage().getText();
        String route = classifier.apply(input);
        Function<String, String> handler = handlers.getOrDefault(route, handlers.get(defaultRoute));
        String result = handler.apply(input);

        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(result))
                        .build())
                .build();
    }

    @Override public String getName() { return "RoutingAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }
}
```

### 3.3 使用：按代码类型路由

```java
@Bean
public RoutingAdvisor codeRouter(ChatClient client) {
    Function<String, String> classifier = code -> client.prompt()
            .system("""
                判断代码类型，只输出一个词：
                - controller / service / repository / model / other
                """)
            .user(code).call().content().toLowerCase();

    Map<String, Function<String, String>> handlers = Map.of(
            "controller", code -> client.prompt()
                    .system("你是 Controller 评审专家，重点检查路由、参数校验、异常处理")
                    .user(code).call().content(),
            "service", code -> client.prompt()
                    .system("你是 Service 评审专家，重点检查事务、业务逻辑、性能")
                    .user(code).call().content(),
            "repository", code -> client.prompt()
                    .system("你是 Repository 评审专家，重点检查 SQL、索引、N+1")
                    .user(code).call().content()
    );

    return new RoutingAdvisor(classifier, handlers, "other");
}
```

### 3.4 Postman 测试用例

补 controller：

```java
// org.demo02.project.workflow.WorkflowController（续）
// 本代码仅作学习材料参考

@Autowired private RoutingAdvisor codeRouter;

@PostMapping("/routing/code")
public String routing(@RequestBody String code) {
    return client.prompt().advisors(codeRouter).user(code).call().content();
}
```

#### 用例 1：明确命中的 Controller 路由

**目的**：验证 classifier 返回 `controller` → 走 Controller handler。

- URL：`POST http://localhost:8080/workflow/routing/code`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```java
  @RestController
  @RequestMapping("/api/users")
  public class UserController {

      @Autowired
      private UserService userService;

      @GetMapping("/{id}")
      public User get(@PathVariable Long id) {
          return userService.findById(id);
      }

      @PostMapping
      public User create(@RequestBody User user) {
          return userService.save(user);
      }
  }
  ```

**逻辑校验点**：
- HTTP 200，响应非空；
- 响应里**应该**提到路由 / 参数校验 / 异常处理这些 Controller 评审关键词；
- 日志能看到 classifier LLM 调用（1 次）+ handler LLM 调用（1 次），共 2 次。

#### 用例 2：未命中任何 handler → default route

**目的**：handlers 只有 controller / service / repository 三类，输入一个 DTO/model 类应该走 `defaultRoute = "other"`。

- URL：`POST http://localhost:8080/workflow/routing/code`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```java
  public record UserDTO(String name, Integer age, String email) {}
  ```

**逻辑校验点**：
- HTTP 200（不报错，因为 classifier 返回的 "model" 走默认 fallback）；
- 日志 classifier 输出应是 `model` 或 `other`；
- RoutingAdvisor 不会 NPE（`handlers.getOrDefault(route, handlers.get(defaultRoute))` —— 注意 `defaultRoute = "other"` 但 handlers 里没有 `"other"`，会 NPE）；
- **如果 NPE，说明默认路由键配错了**——修：要么把 `defaultRoute` 改成 `"controller"`，要么在 handlers 加 `"other"` 项。

#### 用例 3：classifier 输出格式不规整

**目的**：classifier LLM 可能返回 `"Controller\n"` 或 `"controller。"` 带标点。验证 `toLowerCase()` 够不够。

操作步骤：
1. 临时改 `codeRouter` Bean 的 classifier system prompt，加一句 `请在输出后加一个句号`：
   ```java
   .system("""
       判断代码类型，只输出一个词，并在末尾加一个句号：
       - controller / service / repository / model / other
       """)
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8080/workflow/routing/code`
- Headers：`Content-Type: text/plain`
- Body：用例 1 的同一段 Controller 代码即可。

**逻辑校验点**：
- 当前 `.toLowerCase()` 不能去掉空白和标点 → 路由会失败回到 default；
- 这揭示了一个**潜在 bug**：classifier 输出需要 `.trim().replaceAll("[^a-z]", "")` 之类的清洗；
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

### 4.2 实现

```java
// org.demo02.toolkit.workflow.OrchestratorWorkersAdvisor
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Function;

public class OrchestratorWorkersAdvisor implements BaseAdvisor {

    private final ChatClient orchestrator;
    private final Function<String, String> worker;

    public OrchestratorWorkersAdvisor(ChatClient orchestrator,
                                       Function<String, String> worker) {
        this.orchestrator = orchestrator;
        this.worker = worker;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String input = req.prompt().getUserMessage().getText();

        // 1. Orchestrator 决定子任务
        String planJson = orchestrator.prompt()
                .system("""
                    把任务拆成若干独立子任务，输出 JSON：
                    {"subtasks": ["任务1", "任务2", ...]}
                    """)
                .user(input).call().content();

        List<String> subtasks = parseSubtasks(planJson);

        // 2. 并行执行
        List<String> results = Flux.fromIterable(subtasks)
                .flatMap(st -> Mono.fromCallable(() -> worker.apply(st))
                        .subscribeOn(Schedulers.boundedElastic()),
                        Math.min(subtasks.size(), 5))
                .collectList()
                .block();

        // 3. 聚合
        String aggregated = orchestrator.prompt()
                .system("把以下子任务结果整合为完整报告")
                .user("原任务：" + input + "\n子任务结果：" + String.join("\n---\n", results))
                .call().content();

        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(aggregated))
                        .build())
                .build();
    }

    @Override public String getName() { return "OrchestratorWorkersAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }
}
```

### 4.3 使用：长文档摘要

```java
@Bean
public OrchestratorWorkersAdvisor longDocSummarizer(ChatClient client) {
    return new OrchestratorWorkersAdvisor(
            client,
            subtask -> client.prompt()
                    .system("你是文档子任务执行者")
                    .user(subtask).call().content()
    );
}
```

输入 50000 字长文档，Orchestrator 决定"摘要 / 抽取要点 / 列出引用"3 个子任务，并行执行。

### 4.4 Postman 测试用例

补 controller：

```java
// org.demo02.project.workflow.WorkflowController（续）
// 本代码仅作学习材料参考

@Autowired private OrchestratorWorkersAdvisor longDocSummarizer;

@PostMapping("/orchestrator/summary")
public String summary(@RequestBody String doc) {
    return client.prompt().advisors(longDocSummarizer).user(doc).call().content();
}
```

#### 用例 1：长文档 → 验证 orchestrator 动态拆分

**目的**：验证 orchestrator 能根据内容**自己决定**几个子任务（不是固定数）。

- URL：`POST http://localhost:8080/workflow/orchestrator/summary`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制以下博客内容）：
  ```
  《Spring Boot 启动原理深度剖析》

  Spring Boot 是 Spring 团队推出的快速开发框架，其核心价值在于"约定优于配置"。
  本文从 SpringApplication.run() 入口开始，逐层剖析启动流程。

  一、SpringApplication 实例化阶段
  在 main 方法中调用 SpringApplication.run(MyApp.class, args) 时，
  首先会创建一个 SpringApplication 实例。构造函数中会推断应用类型（SERVLET / REACTIVE / NONE），
  从 META-INF/spring.factories 中加载 ApplicationContextInitializer 和 ApplicationListener，
  并推断 main 方法所在的类。

  二、prepareEnvironment 阶段
  这一阶段会创建并配置 Environment 对象，加载 application.properties / application.yaml，
  触发 ApplicationEnvironmentPreparedEvent 事件。此时 BootstrapRegistryInitializer
  和 EnvironmentPostProcessor 都会被调用，用于定制环境。

  三、createApplicationContext 阶段
  根据应用类型创建对应的 ApplicationContext：Servlet 用 AnnotationConfigServletWebServerApplicationContext，
  Reactive 用 AnnotationConfigReactiveWebServerApplicationContext。

  四、refreshContext 阶段
  这是启动的核心环节，依次执行 BeanFactoryPostProcessor、注册 BeanPostProcessor、
  实例化单例 Bean、启动内嵌 Tomcat / Netty 服务器。onRefresh() 阶段会启动 web 服务器。

  五、afterRefresh 阶段
  触发 ApplicationStartedEvent 和 ApplicationReadyEvent，应用正式对外提供服务。

  总结：理解 Spring Boot 启动流程对排查 Bean 创建失败、配置不生效等问题至关重要。
  ```

**逻辑校验点**：
- HTTP 200，响应非空；
- 后端 LLM 调用次数 = 1（orchestrator 计划）+ N（worker 并行）+ 1（最终聚合），N 通常是 2-5；
- orchestrator 的 JSON 输出能被 `parseSubtasks(...)` 正确解析（日志不报 JSON 解析错）；
- 聚合响应里能找到原任务的关键词（说明 worker 真基于原任务执行，不是空跑）。

#### 用例 2：超短输入 → orchestrator 决定不拆

**目的**：短输入下 orchestrator 应返回 `{"subtasks": ["..."]}` 单元素数组，避免无效并行。

- URL：`POST http://localhost:8080/workflow/orchestrator/summary`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```
  今天天气不错
  ```

**逻辑校验点**：
- HTTP 200；
- 日志：`parseSubtasks(...)` 返回 List size = 1；
- 总 LLM 调用次数 = 1 + 1 + 1 = 3 次（不浪费并行配额）。

#### 用例 3：orchestrator JSON 格式错乱 → 验证降级

**目的**：LLM 偶尔会在 JSON 前后加文字（"好的：\n{...}"）。验证 `parseSubtasks` 有兜底。

操作步骤：
1. 临时改 `OrchestratorWorkersAdvisor` 中 orchestrator 的 system prompt，加一句：
   ```
   输出前先说一句"好的，我帮你拆分："，然后再输出 JSON。
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8080/workflow/orchestrator/summary`
- Headers：`Content-Type: text/plain`
- Body：用例 1 的同一段博客内容即可。

**逻辑校验点**：
- 如果 `parseSubtasks` 直接 `new ObjectMapper().readValue(json, ...)`，会抛错 → 500；
- 期望：要么 Postman 看到 500（暴露 parser 没 robust），要么 200（说明 parser 做了 `[` 截取）；
- 不管哪种结果，都帮你定位到"需要给 `parseSubtasks` 加截取逻辑"这个真实生产场景。

---

## 5. Pattern 5: Evaluator-Optimizer

### 5.1 定义

LLM 生成 → 评估 → 不合格则反馈给 LLM 重做，直到合格或达到最大次数。

```
Input → [Generator] → Output → [Evaluator] → pass?
                                            ├ yes → return
                                            └ no  → feedback → loop back
```

### 5.2 实现

```java
// org.demo02.toolkit.workflow.EvaluatorOptimizerAdvisor
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.function.BiFunction;
import java.util.function.Function;

public class EvaluatorOptimizerAdvisor implements BaseAdvisor {

    private final Function<String, String> generator;
    private final BiFunction<String, String, EvalResult> evaluator;
    private final int maxIterations;

    public EvaluatorOptimizerAdvisor(Function<String, String> generator,
                                      BiFunction<String, String, EvalResult> evaluator,
                                      int maxIterations) {
        this.generator = generator;
        this.evaluator = evaluator;
        this.maxIterations = maxIterations;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String input = req.prompt().getUserMessage().getText();
        String currentOutput = generator.apply(input);

        for (int i = 0; i < maxIterations; i++) {
            EvalResult eval = evaluator.apply(input, currentOutput);
            if (eval.pass()) {
                break;
            }
            // 用 feedback 重新生成
            currentOutput = generator.apply(
                    input + "\n\n之前的输出有这些问题，请改进：\n" + eval.feedback());
        }

        String finalOutput = currentOutput;
        return req.mutate()
                .prompt(req.prompt().mutate()
                        .messages(new UserMessage(finalOutput))
                        .build())
                .build();
    }

    public record EvalResult(boolean pass, String feedback) {}

    @Override public String getName() { return "EvaluatorOptimizerAdvisor"; }
    @Override public int getOrder() { return HIGHEST_PRECEDENCE + 248; }
}
```

### 5.3 使用

```java
@Bean
public EvaluatorOptimizerAdvisor codeRefiner(ChatClient client) {
    return new EvaluatorOptimizerAdvisor(
            // Generator
            input -> client.prompt()
                    .system("生成高质量的 Java 代码")
                    .user(input).call().content(),
            // Evaluator
            (req, code) -> {
                String eval = client.prompt()
                        .system("""
                            判断代码是否合格，输出 JSON：
                            {"pass": true/false, "feedback": "..."}
                            """)
                        .user("需求：" + req + "\n代码：" + code)
                        .call().content();
                return parseEval(eval);
            },
            3   // 最多重做 3 次
    );
}
```

### 5.4 Postman 测试用例

补 controller：

```java
// org.demo02.project.workflow.WorkflowController（续）
// 本代码仅作学习材料参考

@Autowired private EvaluatorOptimizerAdvisor codeRefiner;

@PostMapping("/eval/refine")
public String refine(@RequestBody String requirement) {
    return client.prompt().advisors(codeRefiner).user(requirement).call().content();
}
```

#### 用例 1：一次通过 → 验证循环只跑 1 轮

**目的**：evaluator 第一次就 pass=true，验证 `for` 循环 break 生效。

- URL：`POST http://localhost:8080/workflow/eval/refine`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制，简单需求预期一次通过）：
  ```
  写一个函数，把 List<String> 反转后返回
  ```

**逻辑校验点**：
- HTTP 200，响应是一段 Java 代码（含 `public`、`return` 等）；
- 日志：generator 1 次 + evaluator 1 次 = 共 2 次 LLM 调用（没进入第 2 轮）。

#### 用例 2：多次迭代 → 验证反馈回路

**目的**：evaluator 连续 fail，最多迭代 3 次。

操作步骤：
1. 临时改 `codeRefiner` Bean 的 evaluator，强制永远 fail：
   ```java
   (req, code) -> new EvaluatorOptimizerAdvisor.EvalResult(false, "always fail")
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8080/workflow/eval/refine`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```
  写一个函数，把 List<String> 反转后返回
  ```

**逻辑校验点**：
- HTTP 200，不会死循环（`maxIterations = 3` 强制退出）；
- 日志：generator 调用次数 = 1 + 3 = 4 次（初次 + 3 次重做）；
- evaluator 调用次数 = 3 次；
- 总 LLM 调用 = 7 次（注意成本！）。

#### 用例 3：evaluator JSON 解析失败 → 验证容错

**目的**：LLM 返回的 JSON 可能不合规（带前缀 / 字段缺失），验证 `parseEval` 是否健壮。

操作步骤：
1. 临时改 `codeRefiner` Bean 的 evaluator，让它返回非 JSON 文本：
   ```java
   (req, code) -> {
       // 模拟 LLM 返回非 JSON 文本
       String eval = "评估完成，代码不错";
       return parseEval(eval);
   }
   ```
2. 重启应用，调用接口。

- URL：`POST http://localhost:8080/workflow/eval/refine`
- Headers：`Content-Type: text/plain`
- Body：用例 1 的同一段需求文本即可。

**逻辑校验点**：
- 如果 `parseEval` 直接 Jackson 解析 → 抛异常 → 500；
- 如果 `parseEval` 有 fallback（如 `text.contains("\"pass\": true")`）→ 200，但日志有 warn；
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

```java
// org.demo02.project.review.CodeReviewService
// 本代码仅作学习材料参考

@Service
public class CodeReviewService {

    private final ChatClient client;

    public ReviewReport review(String code) {
        // Step 1: Routing（分类）
        String type = classify(code);

        // Step 2: Orchestrator-Workers（大文件按方法拆分）
        List<String> chunks = shouldSplit(code)
                ? splitByMethod(code)
                : List.of(code);

        // Step 3: Parallelization（每段并行三视角）
        List<ChunkReview> chunkReviews = Flux.fromIterable(chunks)
                .flatMap(chunk -> reviewOneChunk(chunk, type))
                .collectList()
                .block();

        // Step 4: Aggregation（聚合）
        String aggregated = aggregate(chunkReviews);

        // Step 5: Evaluator-Optimizer（质量优化）
        String finalReport = refine(aggregated, code);

        return ReviewReport.from(finalReport);
    }

    private Mono<ChunkReview> reviewOneChunk(String chunk, String type) {
        return Mono.fromCallable(() -> {
            // 三视角并行
            List<String> angles = List.of("bug", "style", "security");
            List<String> results = Flux.fromIterable(angles)
                    .flatMap(angle -> Mono.fromCallable(() ->
                            client.prompt()
                                    .system(reviewPromptFor(angle, type))
                                    .user(chunk).call().content())
                            .subscribeOn(Schedulers.boundedElastic()),
                            3)
                    .collectList()
                    .block();
            return new ChunkReview(chunk, results);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String refine(String report, String code) {
        for (int i = 0; i < 3; i++) {
            String eval = client.prompt()
                    .system("判断评审报告是否完整、专业。输出 JSON：{\"pass\":true/false}")
                    .user("代码：" + code + "\n报告：" + report).call().content();
            if (parsePass(eval)) break;
            report = client.prompt()
                    .system("改进评审报告，让它更专业、更具体")
                    .user("原报告：" + report + "\n代码：" + code).call().content();
        }
        return report;
    }

    // 省略 classify / shouldSplit / splitByMethod / aggregate / parsePass 等辅助方法
}
```

### 6.5 调用

```java
@RestController
@RequestMapping("/review")
public class ReviewController {

    private final CodeReviewService service;

    @PostMapping
    public ReviewReport review(@RequestBody String code) {
        return service.review(code);
    }
}
```

```bash
curl -X POST http://localhost:8080/review -d @MyService.java
```

### 6.6 Postman 测试用例

综合实战串联五大模式，重点验证**模式间协作**而不是单点逻辑。

#### 用例 1：常规 Controller 文件 → 验证完整流水线

**目的**：覆盖 Routing → Orchestrator (不拆) → Parallelization (三视角) → Aggregation → Evaluator。

- URL：`POST http://localhost:8080/review`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制以下 ~50 行 Controller 代码）：
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

      @PutMapping("/{id}")
      public Order update(@PathVariable Long id, @RequestBody Order order) {
          order.setId(id);
          return orderService.save(order);
      }

      @DeleteMapping("/{id}")
      public void delete(@PathVariable Long id) {
          orderService.delete(id);
      }

      @GetMapping("/user/{userId}")
      public List<Order> listByUser(@PathVariable Long userId) {
          return orderService.findByUserId(userId);
      }
  }
  ```

**逻辑校验点**：
- HTTP 200，响应是结构化报告（含 bug / style / security 三个视角的痕迹）；
- 后端 LLM 调用次数 ≈ 1（分类）+ 3（三视角并行）+ 1（聚合）+ 1-3（refine 迭代）= 6-8 次；
- 总耗时 < 60s；
- 日志能看到 `[Routing]` `[Parallelization]` `[Aggregation]` `[Evaluator]` 各阶段标记（如果你在 service 里加了 log）。

#### 用例 2：超大文件 → 验证 Orchestrator 拆分路径

**目的**：触发 `shouldSplit(code) = true`，按方法拆分多段并行评审。

- URL：`POST http://localhost:8080/review`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，把以下 Service 代码连续粘贴 5 次，模拟 500+ 行长文件）：
  ```java
  @Service
  public class PaymentService {

      @Autowired
      private PaymentRepository paymentRepository;

      @Autowired
      private PaymentGateway paymentGateway;

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
          payment.setCreatedAt(LocalDateTime.now());
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
- 操作提示：在 Postman body 编辑区把上面整段复制粘贴 5 次（不需要分隔符），即可达到 ~250 行；如需更长可粘贴更多次。

**逻辑校验点**：
- HTTP 200；
- 日志：`splitByMethod(...)` 返回的 chunk 数 ≥ 2；
- LLM 调用次数 = 1（分类）+ chunk 数 × 3 视角（并行）+ 1（聚合）+ 1-3（refine）；
- 总耗时 < 90s（拆分后并行更快，而不是慢）。

#### 用例 3：非 Java 内容 → 验证降级

**目的**：上传一段 Markdown / JSON，验证 classifier 走 default 路径不爆。

- URL：`POST http://localhost:8080/review`
- Headers：`Content-Type: text/plain`
- Body（raw → Text，完整复制）：
  ```
  # README

  This is not Java code.

  ## Install

  Run `npm install` to setup dependencies.
  ```

**逻辑校验点**：
- HTTP 200（不应该 500）；
- 响应里说明 "无法识别为 Java 代码" 或走 default 评审视角；
- 日志 classifier 输出 `other`。

#### 用例 4：空请求体 → 验证边界

**目的**：空 body 不应让流水线崩溃。

- URL：`POST http://localhost:8080/review`
- Headers：`Content-Type: text/plain`
- Body：（完全留空，连空格都不要输入）

**逻辑校验点**：
- 应返回 400 Bad Request（Spring 会因 `@RequestBody String code` 必填而拒绝）；
- 如果你的 `@RequestBody(required = false)` 允许 null，则应返回 200 但响应里说明"输入为空"；
- 不应 500 NPE。

#### 用例 5：并发压测 → 验证 Parallelization 的线程池

**目的**：ParallelizationAdvisor 内部用 `Schedulers.boundedElastic()`，并发请求下不应该线程饥饿。

Postman 配置步骤：
1. 保存用例 1 的请求到一个 Collection；
2. 点 Runner → 选 Collection → Iterations = 10 → 勾选 "Run in parallel"（或调整 Postman Desktop 的并发设置）；
3. 设置请求超时 120s，开始运行。

- Body：使用用例 1 的相同 Controller 代码。

**逻辑校验点**：
- 全部 200，无超时（设置单请求超时 120s）；
- 后端不会因 `boundedElastic` 队列满而拒绝（默认队列 100k，10 并发轻松扛住）；
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

### 8.1 把 Workflow 写成自主 Agent

```
# ❌ 反模式
"你是一个代码评审员，自由发挥"
```

LLM 可能：调工具调到爆、跑题、漏步骤。

```
# ✅ 正模式
明确告诉 LLM：先做 A，再做 B，最后做 C
```

### 8.2 不必要的并行

简单任务硬上 Parallelization，结果 3 倍成本但收益不明显。

**判断**：只有当 LLM 调用是瓶颈（>5s）时并行才有意义。

### 8.3 Evaluator-Optimizer 死循环

LLM 评估器永远说"不合格"。设 maxIterations=3 强制退出。

### 8.4 Routing 分类错

分类器用便宜模型理解不够。**Routing 用强模型，Worker 用便宜模型**。

---

## 9. 与 09 篇（多 Agent 编排）的关系

| 维度 | 五大 Workflow 模式（本文） | 多 Agent（09 篇） |
|------|--------------------------|------------------|
| 抽象层次 | Advisor 模式（编译时确定） | 状态机图（运行时确定） |
| 复杂度 | 低 | 中高 |
| 灵活性 | 固定流程 | 动态流转 |
| 适用 | 80% 企业场景 | 复杂决策、循环 |

**建议**：先用 Workflow 模式跑通业务，撑不住时再上多 Agent 编排。

---

## 10. 实战任务

1. 实现 `PromptChainingAdvisor`，跑通"大纲 → 草稿 → 润色"文章生成。
2. 实现 `ParallelizationAdvisor`，三视角并行评审代码。
3. 实现 `RoutingAdvisor`，按代码类型路由。
4. 实现 `OrchestratorWorkersAdvisor`，对长文档动态拆分。
5. 实现 `EvaluatorOptimizerAdvisor`，自动改进 LLM 输出。
6. 把五大模式组合成 §6 的代码评审助手，跑通一个真实文件。
7. （进阶）把五大 Advisor 抽到 `org.demo02.toolkit.workflow` 包，做成可复用工具库。
8. （选做）评估自主 Agent vs Workflow 模式在同一任务上的成本差异。

---

## 11. 理解检查

1. 五大模式各自适用什么场景？
2. Routing 和 Orchestrator-Workers 的本质区别？
3. Sectioning 和 Voting 的区别？什么时候用 Voting？
4. Evaluator-Optimizer 怎么避免死循环？
5. 为什么"Workflow > Agent"？什么场景必须用 Agent？
6. 五大模式在代码评审助手里如何协作？

---

## 12. 相关文档

- [`./10-多Agent编排实战.md`](./10-多Agent编排实战.md) —— 撑不住 Workflow 时升级
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 基础
- [Anthropic Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
