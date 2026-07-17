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
// 本代码仅作学习材料参考

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
                                .userMessage(new UserMessage("[CHAIN TERMINATED]"))
                                .build())
                        .build();
            }
        }
        return req.mutate()
                .prompt(req.prompt().mutate()
                        .userMessage(new UserMessage(current))
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        return resp;
    }

    @Override public String getName() { return "PromptChainingAdvisor"; }
    @Override public int getOrder() { return -2147483400; }
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
// 本代码仅作学习材料参考

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
                        .userMessage(new UserMessage(aggregated))
                        .build())
                .build();
    }

    @Override public String getName() { return "ParallelizationAdvisor"; }
    @Override public int getOrder() { return -2147483400; }
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
// 本代码仅作学习材料参考

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
                        .userMessage(new UserMessage(result))
                        .build())
                .build();
    }

    @Override public String getName() { return "RoutingAdvisor"; }
    @Override public int getOrder() { return -2147483400; }
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
// 本代码仅作学习材料参考

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
                        .userMessage(new UserMessage(aggregated))
                        .build())
                .build();
    }

    @Override public String getName() { return "OrchestratorWorkersAdvisor"; }
    @Override public int getOrder() { return -2147483400; }
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
// 本代码仅作学习材料参考

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
                        .userMessage(new UserMessage(finalOutput))
                        .build())
                .build();
    }

    public record EvalResult(boolean pass, String feedback) {}

    @Override public String getName() { return "EvaluatorOptimizerAdvisor"; }
    @Override public int getOrder() { return -2147483400; }
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

- [`./09-多Agent编排实战.md`](./09-多Agent编排实战.md) —— 撑不住 Workflow 时升级
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 基础
- [Anthropic Building Effective Agents](https://www.anthropic.com/research/building-effective-agents)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
