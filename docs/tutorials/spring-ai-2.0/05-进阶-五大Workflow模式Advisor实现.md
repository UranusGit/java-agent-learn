# L5 进阶 - 五大 Workflow 模式 Advisor 实现（核心篇）⭐

> Anthropic《Building Effective Agents》(2024-12-19) 的五大模式全部封装为可复用 Advisor。
> 这是整个 2.0 学习路线**最核心的一篇**，必须吃透。
>
> 前置：[`./01`](./01-初级-2.0基础重塑.md) - [`./04`](./04-中级-MCP与会话持久化.md)
> 预计：2-3 天

---

## 0. 为什么要学这一篇

### 0.1 Anthropic 的核心论断

> **Workflow > Agent**
> 能用确定性 DAG 解决的，不要用自主 Agent。80%+ 的企业场景都能用 Workflow 解决，比自主 Agent 更稳定、更便宜、更可观测。

### 0.2 五大模式总览

| 模式 | 适用场景 | 复杂度 |
|------|---------|--------|
| **Prompt Chaining** | 任务可分解为线性步骤 | 低 |
| **Parallelization**（Sectioning / Voting） | 多个独立子任务 / 提升结果稳定性 | 中 |
| **Routing** | 不同类型输入走不同处理 | 低 |
| **Orchestrator-Workers** | 子任务动态确定 | 中高 |
| **Evaluator-Optimizer** | 结果可被评估 + 迭代改进 | 中 |

### 0.3 代码组织约定

所有五大模式的 Advisor 放在 `org.demo02.toolkit.workflow` 包下，每个文件独立、可复用。

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
| 邮件撰写 | 收件人分析 → 主题 → 正文 |

### 1.3 反模式

- ❌ 子任务之间**强依赖循环**（→ 用 Evaluator-Optimizer）
- ❌ 子任务之间**可并行**（→ 用 Parallelization）
- ❌ 步数 > 6（→ 拆得太细，用 Orchestrator-Workers）

### 1.4 Advisor 实现

```java
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

public class PromptChainingAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatClient[] stepClients;
    private final int order;

    private PromptChainingAdvisor(int order, ChatClient... stepClients) {
        this.order = order;
        this.stepClients = stepClients;
    }

    public static PromptChainingAdvisor of(ChatClient... steps) {
        return new PromptChainingAdvisor(Ordered.HIGHEST_PRECEDENCE + 600, steps);
    }

    public PromptChainingAdvisor withOrder(int order) {
        return new PromptChainingAdvisor(order, stepClients);
    }

    @Override
    public String getName() { return "PromptChainingAdvisor"; }

    @Override
    public int getOrder() { return order; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String input = request.prompt().getInstructions().toString();
        String current = input;

        for (int i = 0; i < stepClients.length; i++) {
            current = stepClients[i].prompt()
                    .user(current)
                    .call()
                    .content();
        }

        // 最后一步走原始链（兼容 Memory / Validation 等）
        return chain.nextCall(request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(current)))
                .build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 流式版本：前 N-1 步同步执行，最后一步流式输出
        String input = request.prompt().getInstructions().toString();
        String current = input;

        for (int i = 0; i < stepClients.length - 1; i++) {
            current = stepClients[i].prompt().user(current).call().content();
        }

        ChatClientRequest finalReq = request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(current)))
                .build();

        return stepClients[stepClients.length - 1].prompt()
                .messages(finalReq.prompt().getInstructions())
                .stream()
                .chatClientResponse();
    }
}
```

### 1.5 使用示例：写一篇技术博客

```java
@Bean("outlineClient")
ChatClient outlineClient(ChatClient.Builder b) {
    return b.defaultSystem("你是技术博客大纲设计师，输出 markdown 大纲").build();
}

@Bean("draftClient")
ChatClient draftClient(ChatClient.Builder b) {
    return b.defaultSystem("你是技术写作高手，根据大纲写完整文章，markdown 格式").build();
}

@Bean("polishClient")
ChatClient polishClient(ChatClient.Builder b) {
    return b.defaultSystem("你是资深编辑，润色文章：修语病、补过渡、改标题").build();
}

@Bean
PromptChainingAdvisor blogChain(
        @Qualifier("outlineClient") ChatClient outline,
        @Qualifier("draftClient") ChatClient draft,
        @Qualifier("polishClient") ChatClient polish
) {
    return PromptChainingAdvisor.of(outline, draft, polish);
}

@GetMapping("/blog")
public String blog(@RequestParam String topic) {
    return chatClient.prompt()
            .user(topic)
            .advisors(blogChain)
            .call()
            .content();
}
```

**调用**：
```bash
curl "http://localhost:8080/blog?topic=Spring AI 2.0 的 ToolCallingAdvisor 原理"
```

**内部流程**：
```
topic
  → outlineClient 生成大纲
  → draftClient 根据大纲写正文
  → polishClient 润色
  → 输出最终文章
```

---

## 2. Pattern 2: Parallelization（并行化）

### 2.1 定义

多个 LLM 调用**并行执行**，最后聚合结果。两种子模式：
- **Sectioning**：拆成不同视角（同时分析代码、文档、测试）
- **Voting**：同一任务跑 N 次，投票取共识（提升稳定性）

### 2.2 适用场景

| 场景 | 子模式 |
|------|-------|
| 代码评审 | Sectioning：分别看 bug、风格、安全 |
| 用户意图识别 | Voting：跑 3 次取多数 |
| 多维情感分析 | Sectioning：分别分析情绪、紧急度、意图 |
| 风险评估 | Voting：3 个独立评估取平均 |

### 2.3 反模式

- ❌ 子任务之间**有依赖**（→ 用 Prompt Chaining）
- ❌ 子任务结果**有顺序要求**（→ 不能完全并行）
- ❌ 单个子任务就够慢了（→ 并行没意义）

### 2.4 Advisor 实现

```java
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ParallelizationAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatClient[] workers;
    private final AggregationStrategy strategy;
    private final int order;

    private ParallelizationAdvisor(int order, AggregationStrategy strategy, ChatClient... workers) {
        this.order = order;
        this.strategy = strategy;
        this.workers = workers;
    }

    public static ParallelizationAdvisor sectioning(ChatClient... workers) {
        return new ParallelizationAdvisor(Ordered.HIGHEST_PRECEDENCE + 600,
                AggregationStrategy.MERGE, workers);
    }

    public static ParallelizationAdvisor voting(ChatClient... workers) {
        return new ParallelizationAdvisor(Ordered.HIGHEST_PRECEDENCE + 600,
                AggregationStrategy.VOTE, workers);
    }

    @Override
    public String getName() { return "ParallelizationAdvisor"; }

    @Override
    public int getOrder() { return order; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String input = request.prompt().getInstructions().toString();

        var results = Arrays.stream(workers)
                .map(c -> Mono.fromCallable(() -> c.prompt().user(input).call().content())
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .toArray(Mono[]::new);

        var aggregated = Mono.zip(results)
                .map(arr -> strategy.aggregate(
                        Arrays.stream(arr).map(Object::toString).collect(Collectors.toList())
                ))
                .block();

        return chain.nextCall(request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(aggregated)))
                .build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 流式版本：等所有 worker 完成后一次性输出聚合结果
        ChatClientResponse resp = adviseCall(request, new NoopCallChain());
        return Flux.just(resp);
    }

    @FunctionalInterface
    public interface AggregationStrategy {
        String aggregate(java.util.List<String> results);

        AggregationStrategy MERGE = results -> String.join("\n\n---\n\n", results);

        AggregationStrategy VOTE = results -> {
            // 简化投票：取最长（信息量最大）
            return results.stream()
                    .reduce((a, b) -> a.length() > b.length() ? a : b)
                    .orElse("");
        };
    }
}
```

> 真实生产投票策略应该更智能（如基于 BertScore 相似度聚类）。

### 2.5 使用示例：代码评审

```java
@Bean("bugClient") ChatClient bugClient(ChatClient.Builder b) {
    return b.defaultSystem("你是 bug 猎手，专注找逻辑错误和边界问题").build();
}
@Bean("styleClient") ChatClient styleClient(ChatClient.Builder b) {
    return b.defaultSystem("你是代码风格专家，按 Google Java Style 检查").build();
}
@Bean("securityClient") ChatClient securityClient(ChatClient.Builder b) {
    return b.defaultSystem("你是安全专家，按 OWASP Top 10 检查").build();
}

@Bean
ParallelizationAdvisor codeReviewAdvisor(
        @Qualifier("bugClient") ChatClient bug,
        @Qualifier("styleClient") ChatClient style,
        @Qualifier("securityClient") ChatClient sec
) {
    return ParallelizationAdvisor.sectioning(bug, style, sec);
}

@GetMapping("/review")
public String review(@RequestParam String code) {
    return chatClient.prompt().user(code).advisors(codeReviewAdvisor).call().content();
}
```

3 个 LLM 并行跑，每个看不同维度，最后合并成完整评审报告。

### 2.6 Voting 示例：意图识别

```java
@Bean
ChatClient intentClassifier_v1(ChatClient.Builder b) {
    return b.defaultSystem("判断用户意图：refund / consult / complaint。只输出分类词").build();
}

@Bean
ParallelizationAdvisor votingAdvisor(
        @Qualifier("intentClassifier_v1") ChatClient c1,
        @Qualifier("intentClassifier_v1") ChatClient c2,
        @Qualifier("intentClassifier_v1") ChatClient c3
) {
    return ParallelizationAdvisor.voting(c1, c2, c3);
}
```

同一 prompt 跑 3 次（不同 temperature 触发不同路径），投票取多数，准确率显著高于单次。

---

## 3. Pattern 3: Routing（路由）

### 3.1 定义

根据输入特征路由到不同处理路径。

```
input → classifier → branch A / branch B / branch C
```

### 3.2 适用场景

| 场景 | 怎么路由 |
|------|---------|
| 客服系统 | 简单问题走 FAQ，复杂走人工 |
| 多语言 | 中文走中文 prompt，英文走英文 prompt |
| 代码生成 | 前端代码 / 后端代码 / 脚本走不同 prompt |
| 紧急度 | 紧急走强模型，非紧急走便宜模型 |

### 3.3 反模式

- ❌ 分支数量 > 5（→ 用 Orchestrator-Workers）
- ❌ 分类不确定（→ 加个 fallback 分支）

### 3.4 Advisor 实现

```java
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

public class RoutingAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatClient classifier;
    private final Function<String, String> routeFunction;
    private final Map<String, ChatClient> routes;
    private final ChatClient fallback;
    private final int order;

    private RoutingAdvisor(int order, ChatClient classifier,
                          Function<String, String> routeFunction,
                          Map<String, ChatClient> routes,
                          ChatClient fallback) {
        this.order = order;
        this.classifier = classifier;
        this.routeFunction = routeFunction;
        this.routes = routes;
        this.fallback = fallback;
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public String getName() { return "RoutingAdvisor"; }

    @Override
    public int getOrder() { return order; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String input = request.prompt().getInstructions().toString();

        String routeKey;
        if (classifier != null) {
            String classification = classifier.prompt()
                    .user(input)
                    .call()
                    .content()
                    .trim()
                    .toLowerCase();
            routeKey = classification;
        } else {
            routeKey = routeFunction.apply(input);
        }

        ChatClient chosen = routes.getOrDefault(routeKey, fallback);
        String result = chosen.prompt().user(input).call().content();

        return chain.nextCall(request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(result)))
                .build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientResponse resp = adviseCall(request, new NoopCallChain());
        return Flux.just(resp);
    }

    public static class Builder {
        private ChatClient classifier;
        private Map<String, ChatClient> routes;
        private ChatClient fallback;
        private int order = Ordered.HIGHEST_PRECEDENCE + 600;

        public Builder classifier(ChatClient c) { this.classifier = c; return this; }
        public Builder routes(Map<String, ChatClient> r) { this.routes = r; return this; }
        public Builder fallback(ChatClient c) { this.fallback = c; return this; }
        public Builder order(int o) { this.order = o; return this; }

        public RoutingAdvisor build() {
            return new RoutingAdvisor(order, classifier, null, routes, fallback);
        }
    }
}
```

### 3.5 使用示例：智能客服路由

```java
@Bean("classifier") ChatClient classifier(ChatClient.Builder b) {
    return b.defaultSystem("判断问题类型：billing / technical / general。只输出分类词").build();
}

@Bean("billingExpert") ChatClient billingExpert(ChatClient.Builder b) {
    return b.defaultSystem("你是账单专家，处理付款、退款、发票问题").build();
}

@Bean("techExpert") ChatClient techExpert(ChatClient.Builder b) {
    return b.defaultSystem("你是技术支持，处理 bug、配置、使用问题").build();
}

@Bean("generalExpert") ChatClient generalExpert(ChatClient.Builder b) {
    return b.defaultSystem("你是通用客服").build();
}

@Bean
RoutingAdvisor customerServiceAdvisor(
        @Qualifier("classifier") ChatClient classifier,
        @Qualifier("billingExpert") ChatClient billing,
        @Qualifier("techExpert") ChatClient tech,
        @Qualifier("generalExpert") ChatClient general
) {
    return RoutingAdvisor.builder()
            .classifier(classifier)
            .routes(Map.of(
                    "billing", billing,
                    "technical", tech,
                    "general", general
            ))
            .fallback(general)
            .build();
}
```

**效果**：
- "怎么退款？" → classifier 返回 "billing" → 走 billingExpert
- "App 闪退" → "technical" → techExpert
- "营业时间是？" → "general" → generalExpert

每个分支用**专门 prompt**，比一个超大 prompt 啥都问准确率高得多。

---

## 4. Pattern 4: Orchestrator-Workers（编排者-执行者）

### 4.1 定义

一个**编排者 LLM**根据任务动态拆分子任务，分配给多个**执行者 LLM**，最后汇总。

```
        Orchestrator
       ↓     ↓     ↓
    Worker Worker Worker
       ↓     ↓     ↓
       Aggregator
```

### 4.2 跟其他模式的区别

| 维度 | Prompt Chaining | Parallelization | Routing | **Orchestrator-Workers** |
|------|-----------------|----------------|---------|--------------------------|
| 步骤数 | 固定 | 固定 | 固定 | **动态** |
| 子任务内容 | 固定 | 固定 | 固定 | **LLM 决定** |
| 适合 | 流程已知 | 多视角 / 投票 | 分类 | 复杂任务 |

### 4.3 适用场景

- 代码库复杂变更（按文件拆子任务）
- 多源信息整合（编排者决定查哪些源）
- 大型文档撰写（拆章节）

### 4.4 Advisor 实现

```java
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

public class OrchestratorWorkersAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatClient orchestrator;
    private final ChatClient workerTemplate;
    private final int order;

    public OrchestratorWorkersAdvisor(ChatClient orchestrator, ChatClient workerTemplate) {
        this(orchestrator, workerTemplate, Ordered.HIGHEST_PRECEDENCE + 600);
    }

    public OrchestratorWorkersAdvisor(ChatClient orchestrator, ChatClient workerTemplate, int order) {
        this.orchestrator = orchestrator;
        this.workerTemplate = workerTemplate;
        this.order = order;
    }

    @Override
    public String getName() { return "OrchestratorWorkersAdvisor"; }

    @Override
    public int getOrder() { return order; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String task = request.prompt().getInstructions().toString();

        // Step 1: Orchestrator 决定要哪些子任务
        String planJson = orchestrator.prompt()
                .system("""
                    你是任务编排者。分析任务后，输出 JSON 数组，每个元素是一个子任务：
                    [{"id": "1", "description": "...", "focus": "..."}]
                    只输出 JSON，不要其他文字。
                    """)
                .user(task)
                .call()
                .content();

        List<SubTask> subTasks = parseSubTasks(planJson);

        // Step 2: 并行执行子任务
        List<Mono<String>> monos = subTasks.stream()
                .map(st -> Mono.fromCallable(() ->
                        workerTemplate.prompt()
                                .system("你的子任务焦点：" + st.focus())
                                .user(st.description())
                                .call()
                                .content()
                ).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .toList();

        List<String> results = Mono.zip(monos, arr ->
                Arrays.stream(arr).map(Object::toString).collect(Collectors.toList())
        ).block();

        // Step 3: 聚合
        StringBuilder aggregated = new StringBuilder();
        for (int i = 0; i < subTasks.size(); i++) {
            aggregated.append("## ").append(subTasks.get(i).focus()).append("\n\n");
            aggregated.append(results.get(i)).append("\n\n");
        }

        return chain.nextCall(request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(aggregated.toString())))
                .build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientResponse resp = adviseCall(request, new NoopCallChain());
        return Flux.just(resp);
    }

    private List<SubTask> parseSubTasks(String json) {
        // 简化版解析，生产用 Jackson
        return List.of(new SubTask("1", json, "general"));
    }

    public record SubTask(String id, String description, String focus) {}
}
```

### 4.5 使用示例：研究报告生成

```java
@Bean("orchestrator") ChatClient orchestrator(ChatClient.Builder b) {
    return b.defaultSystem("你是研究项目经理，擅长把大任务拆成可独立完成的子任务").build();
}

@Bean("researcher") ChatClient researcher(ChatClient.Builder b) {
    return b.defaultSystem("你是研究员，专注完成分配给你的子任务").build();
}

@Bean
OrchestratorWorkersAdvisor researchAdvisor(
        @Qualifier("orchestrator") ChatClient orch,
        @Qualifier("researcher") ChatClient worker
) {
    return new OrchestratorWorkersAdvisor(orch, worker);
}
```

**调用**：用户问 "对比 Spring AI 和 LangChain4j 在 Tool 调用上的差异"
- Orchestrator 拆出子任务：① Spring AI Tool 实现 ② LangChain4j Tool 实现 ③ 差异对比 ④ 选型建议
- 4 个 Worker 并行跑
- 聚合成完整报告

---

## 5. Pattern 5: Evaluator-Optimizer（评估-优化）

### 5.1 定义

LLM 生成 → LLM 评估 → 不达标则带着反馈重新生成，循环直到通过。

```
┌─ Generator ─→ Evaluator ─→ Pass? ─┐
│                  ↓ Yes              │
│                  No                 │
└────── feedback ────────────────────┘
```

### 5.2 适用场景

- 翻译（评估流畅度、准确度）
- 代码生成（评估是否能跑通）
- 文案撰写（评估是否达 KPI）
- 任何有明确质量标准的任务

### 5.3 反模式

- ❌ 没有明确评估标准（评估器没法工作）
- ❌ 任务本身是 open-ended（没有"达标"概念）

### 5.4 Advisor 实现

```java
package org.demo02.toolkit.workflow;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

public class EvaluatorOptimizerAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatClient generator;
    private final ChatClient evaluator;
    private final int maxIterations;
    private final int order;

    public EvaluatorOptimizerAdvisor(ChatClient generator, ChatClient evaluator) {
        this(generator, evaluator, 3, Ordered.HIGHEST_PRECEDENCE + 600);
    }

    public EvaluatorOptimizerAdvisor(ChatClient generator, ChatClient evaluator,
                                      int maxIterations, int order) {
        this.generator = generator;
        this.evaluator = evaluator;
        this.maxIterations = maxIterations;
        this.order = order;
    }

    @Override
    public String getName() { return "EvaluatorOptimizerAdvisor"; }

    @Override
    public int getOrder() { return order; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String task = request.prompt().getInstructions().toString();

        String currentOutput = generator.prompt().user(task).call().content();
        String feedback = null;

        for (int i = 0; i < maxIterations; i++) {
            EvalResult eval = evaluate(task, currentOutput);
            if (eval.pass()) {
                break;
            }
            feedback = eval.feedback();
            currentOutput = regenerate(task, currentOutput, feedback);
        }

        return chain.nextCall(request.mutate()
                .prompt(p -> p.addInstructions(new org.springframework.ai.chat.messages.UserMessage(currentOutput)))
                .build());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientResponse resp = adviseCall(request, new NoopCallChain());
        return Flux.just(resp);
    }

    private EvalResult evaluate(String task, String output) {
        String evalResponse = evaluator.prompt()
                .system("""
                    你是质量评估者。判断输出是否达标。
                    返回 JSON：{"pass": true/false, "feedback": "..."}
                    """)
                .user("任务：" + task + "\n\n输出：" + output)
                .call()
                .content();
        // 简化解析
        boolean pass = evalResponse.contains("\"pass\": true") || evalResponse.contains("\"pass\":true");
        String feedback = extractFeedback(evalResponse);
        return new EvalResult(pass, feedback);
    }

    private String regenerate(String task, String previous, String feedback) {
        return generator.prompt()
                .system("上次输出有问题：" + feedback + "，请改进。")
                .user("原任务：" + task + "\n\n上次输出：" + previous)
                .call()
                .content();
    }

    private String extractFeedback(String json) {
        int start = json.indexOf("\"feedback\":");
        if (start < 0) return "改进质量";
        return json.substring(start + 11).replaceAll("[\"\\n}]", "").trim();
    }

    public record EvalResult(boolean pass, String feedback) {}
}
```

### 5.5 使用示例：高质量翻译

```java
@Bean("translator") ChatClient translator(ChatClient.Builder b) {
    return b.defaultSystem("你是专业翻译，中英互译").build();
}

@Bean("translationEvaluator") ChatClient translationEvaluator(ChatClient.Builder b) {
    return b.defaultSystem("""
        你是翻译质量审核员，按以下标准评估：
        1. 准确度：是否忠实原文
        2. 流畅度：是否自然
        3. 术语：专业术语是否统一
        """).build();
}

@Bean
EvaluatorOptimizerAdvisor translationAdvisor(
        @Qualifier("translator") ChatClient t,
        @Qualifier("translationEvaluator") ChatClient e
) {
    return new EvaluatorOptimizerAdvisor(t, e, 3);
}
```

每次翻译最多迭代 3 次，直到评估通过。

---

## 6. 五大模式对比总结

| 模式 | 步骤 | 并行？ | 谁决定路径 | 适合 |
|------|------|--------|-----------|------|
| Prompt Chaining | 固定 | 否 | 开发者 | 线性流程 |
| Parallelization | 固定 | **是** | 开发者 | 多视角 / 投票 |
| Routing | 固定 | 否 | Classifier LLM | 分类处理 |
| Orchestrator-Workers | **动态** | **是** | Orchestrator LLM | 复杂任务分解 |
| Evaluator-Optimizer | 循环 | 否 | Evaluator LLM | 质量迭代 |

---

## 7. 组合使用

五大模式可以**组合**，比如：

```
Routing（先分类）
  ├─ 简单问题 → 单次调用
  └─ 复杂问题 → Orchestrator-Workers
                    ↓ 每个 Worker 内部用 Evaluator-Optimizer
```

```java
@Bean
ChatClient smartAgent(
        ChatClient.Builder builder,
        RoutingAdvisor router,
        OrchestratorWorkersAdvisor complex,
        EvaluatorOptimizerAdvisor quality
) {
    return builder
            .defaultAdvisors(router)        // 最外层路由
            .build();
}
```

L6 会用一个真实项目（代码评审助手）展示组合实战。

---

## 8. 三个反模式警告

### 8.1 不要无脑用 Orchestrator-Workers

> Anthropic 原文：When the subtask count and direction are non-deterministic, only then use Orchestrator-Workers.

子任务能预先列清的，老老实实用 Parallelization。

### 8.2 Evaluator 必须有客观标准

> "看起来不错" / "可以更好" = 垃圾反馈，生成器无法改进。

评估器 prompt 必须列出**明确标准**（如"准确度 > 0.9" / "字数 < 500" / "包含 X 关键词"）。

### 8.3 循环必须有上限

Evaluator-Optimizer、Orchestrator-Workers 的迭代必须有 `maxIterations` / `maxTurns`，防止失控（L8 详谈三重保护）。

---

## 9. 工程化建议

### 9.1 每个 Advisor 必须可观测

加 Micrometer：
```java
@Override
public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
    return timer.record(() -> doWork(req, chain));
}
```

L7 详谈。

### 9.2 失败要被 LLM 看到

子任务失败不要抛异常，返回 `ToolResult.error(stderr)` 给 LLM，让它自我修复（Claude Code 启示录）。

### 9.3 缓存中间结果

Prompt Chaining 的中间步骤结果可以 cache，下次相同输入直接复用（L7 详谈 Prompt Cache）。

---

## 10. 理解检查

1. Workflow > Agent 的本质原因是什么？
2. 五大模式分别适合什么场景？反模式是什么？
3. Routing 和 Orchestrator-Workers 的本质区别是什么？
4. Evaluator-Optimizer 的评估器为什么必须有客观标准？
5. 怎么判断你的场景该用 Parallelization 还是 Orchestrator-Workers？

---

## 11. 练习任务

1. 实现 PromptChainingAdvisor，做一个"大纲 → 草稿 → 润色"的博客生成器
2. 实现 ParallelizationAdvisor，做一个三视角代码评审
3. 实现 RoutingAdvisor，做一个智能客服路由
4. 实现 OrchestratorWorkersAdvisor，做一个研究报告生成器
5. 实现 EvaluatorOptimizerAdvisor，做一个高质量翻译器
6. 把 Routing + Orchestrator-Workers + Evaluator-Optimizer 组合起来
7. 给每个 Advisor 加 maxTurns 上限保护
8. （进阶）给 PromptChainingAdvisor 加中间结果缓存

---

## 12. 完成自检

完成本篇你应该能：
- [ ] 闭着眼睛说出五大模式的差异
- [ ] 不查资料实现任意一个 Pattern 的 Advisor
- [ ] 判断真实场景该用哪个模式
- [ ] 组合 2+ 模式解决复杂问题
- [ ] 给每个模式加上限保护和

完成后进入 [`./06-进阶-组合实战代码评审助手.md`](./06-进阶-组合实战代码评审助手.md)。
