# 方法论复盘：Spring AI 工具调用迭代限制的调研全过程

> **问题一句话**：Spring AI 2.0 怎么限制 Agent 的最大工具调用次数？
>
> **结论一句话**：用 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 禁用自动循环，手写 `Flux.expand()` 控制迭代。但找到这个答案花了 6 轮试错——这份文档复盘整个过程，留作以后遇到"框架能力缺失"类问题的参考。

---

## 1. 调研全过程（按时间线）

### 第 1 步：找配置项（失败）

**做法**：搜 `spring.ai.tool-calling.max-iterations`，翻 `application.yaml` 的自动补全列表。

**结论**：不存在。GitHub issues [#3333](https://github.com/spring-projects/spring-ai/issues/3333)、[#1004](https://github.com/spring-projects/spring-ai/discussions/1004) 确认——这是已知功能缺口，社区在等。

**教训**：先搜 GitHub issues 和官方文档的 known gaps，而不是盲目翻配置。

---

### 第 2 步：找 API 方法（失败）

**做法**：搜社区教程和博客，找到 `ToolCallingChatOptions.builder().internalToolExecutionMaxIterations(5)` 的写法，直接写代码。

**结果**：编译报错"方法不存在"。

**教训**：社区教程可能基于早期 milestone/RC 版本。**永远不要凭社区代码推断 API 存在性**——你用的可能是 GA 版，API 名被改了甚至整个方法被移除了。必须以 `javap` 反编译你实际 classpath 上的 jar 为准。

---

### 第 3 步：`javap` 反编译 jar，确认"真的没有"（关键转折点）

**做法**：不再搜社区教程，直接反编译自己 Maven 仓库里的 jar：

```bash
javap -public -classpath spring-ai-model-2.0.0.jar \
  org.springframework.ai.model.tool.ToolCallingChatOptions'$'Builder
```

**结果**：Builder 的实际方法只有 `toolCallbacks`、`toolContext`、`model`、`temperature`、`maxTokens`——**没有任何与 max iterations 相关的方法**。

**教训**：当社区说法不一致时，**jar 是最终的真相来源**。`javap` 一行命令比翻 10 篇博客准确。把这个习惯变成调研 SOP 的第一步。

---

### 第 4 步：试 Advisor 扩展点（半途卡住）

**做法**：既然没有内置 API，用 Spring AI 的 Advisor 链做横切——写一个 `MaxIterationAdvisor`，在每次迭代时递增计数器，超限就改请求或抛异常。

**遇到的问题**（按次序）：

| 尝试 | 问题 | 根因 |
|------|------|------|
| `request.adviseContext()` 存 counter | 方法不存在 | 实际方法叫 `context()`，不是 `adviseContext()` |
| `request.context().put(COUNTER, count)` | 每轮都是"现在是:1"，不递增 | `ChatClientRequest` 是 Record，构造时对 Map 做防御性拷贝——写入被丢弃 |
| Advisor `order = -1`（在 ToolCallingAdvisor 之前） | 每轮迭代不经过 Advisor | `ToolCallingAdvisor` 内部调用 `chain.copy(this).nextCall()`，copy 只包含排在它**后面**的 advisor |
| Advisor `order = +1`，用 Reactor Context | `adviseStream` 可行 | `adviseCall`（同步路径）没有 Reactor Context，只能退到 ThreadLocal |

**教训**：

1. **先用 `javap` 验证方法名**——`adviseContext` vs `context()` 这种错误花一分钟就能确认，却浪费了一整轮修改。
2. **Record 的不可变性**：`ChatClientRequest` 是 `java.lang.Record`，构造时会防御性拷贝可变字段。往一个 Record 的 Map 字段里写值然后期待下一轮读到——这是不了解 Java Record 语义的后果。
3. **框架扩展点的行为要看实现，不能猜**：`chain.copy(this)` 会移除自己——这个行为只有读字节码才知道，凭"我觉得应该是这样"就会写出 order=-1 的错误方案。
4. **当"两种路径需要两种方案"时，停下来**——如果流式和同步路径的解决方案不统一，说明你还没找到真正的答案。"统一"是判断方案是否正确的重要信号。

---

### 第 5 步：扔掉 Advisor，回到官方文档（找到答案）

**做法**：不再尝试 hack 框架内部，而是搜 Spring AI 官方文档中关于"user controlled tool calling"的内容。

**发现**：`AdvisorParams.toolCallingAdvisorAutoRegister(false)`——Spring AI 2.0 官方提供的 API，按调用禁用 `ToolCallingAdvisor` 的自动注册。禁用后工具定义仍发给模型，但模型的 tool call 不会被自动执行。你通过 `Flux.expand()` 自己驱动循环。

**验证**：

```bash
javap -public -classpath spring-ai-client-chat-2.0.0.jar \
  org.springframework.ai.chat.client.AdvisorParams
```

输出确认方法存在。**jar 里有，就是真。**

**教训**：有时候正确答案不在"框架内部"，而在"框架之上"——不是想尽办法在 ToolCallingAdvisor 的循环内部截断，而是直接关掉这个循环自己写。**问题是"框架能力缺失"，答案是"框架给了你不要这个能力的开关"。**

---

## 2. 方法论总结

### 调研 SOP（按优先级）

| 优先级 | 手段 | 场景 |
|--------|------|------|
| 1 | GitHub issues / discussions 搜 feature request | 确认是不是已知功能缺口 |
| 2 | `javap` 反编译 jar，列出所有 public 方法 | 确认 API 是否存在 |
| 3 | 官方文档（docs.spring.io） | 确认官方推荐的用法 |
| 4 | 社区博客 / 教程 | 参考但**不直接信**——版本差异可能导致 API 名变化 |
| 5 | 反编译字节码（`javap -c`） | 只在需要理解框架内部行为时用——作为最后手段 |

### "框架能力缺失"类问题的判断信号

遇到以下信号时，**停下来**——你很可能在尝试解决一个"框架当前版本不支持"的问题：

1. **社区教程里的 API 你编译报错** → 版本差异，不是你的问题
2. **连试了 3 种方案都不 work** → 不是方案问题，是框架没给这个口子
3. **流式和同步需要不同方案** → 说明你找的扩展点不是为这个场景设计的
4. **需要 ThreadLocal** → 说明框架没有提供 request-scoped 状态容器

### 这次白走了的弯路

1. **改了 6 版 MaxIterationAdvisor**（改 prompt、抛异常、Reactor Context、ThreadLocal）——因为一开始没确认"框架到底给没给配置项"
2. **分析了 ToolCallingAdvisor 字节码**——其实不需要，官方 API 就在那儿
3. **在 `request.context()` 上花了很多时间**——因为没意识到 Record 的不可变性

如果按 SOP 先走第 1 步（确认是 known gap）和第 2 步（javap 确认 API），第 3-6 步都可以跳过。

---

## 3. 适用范围

这个方法论适用于所有"Spring AI / Spring Boot 框架能力缺失"类问题——不只是 tool calling，任何你觉得"应该有但找不到"的能力，都值得先走这 5 步再动手。

> **日期**：2026-07-26 · **Spring AI 版本**：2.0.0 GA
