# 24 Agent 记忆架构（短 / 长 / 情节 / 语义 / 程序）

> Spring AI 的 `MessageWindowChatMemory` 只是"会话窗口"，撑不起真正的 Agent 记忆。本文把人类记忆的五种类型映射到 AI 系统，给出工程化落地方案。
>
> 前置：[`./03-Advisor链全解.md`](./03-Advisor链全解.md) + [`./07-RAG工程化实战.md`](./07-RAG工程化实战.md)
> 预计：1.5 天

---

## 0. 认知地图

```
人类记忆分类（认知心理学）
├── 短时记忆（Working Memory）        ← ChatClient 的上下文窗口
├── 长时记忆（Long-term）
│   ├── 情节记忆（Episodic）           ← "我经历过 X"——会话历史
│   ├── 语义记忆（Semantic）           ← "我知道 X"——知识库 / RAG
│   └── 程序记忆（Procedural）         ← "我会做 X"——工具 / skill
└── 元记忆（Meta）                     ← "我学到了什么"——偏好 / 人格
```

Spring AI 2.0 提供的：

| 类型 | Spring AI 设施 | 局限 |
|------|--------------|------|
| 短时 | `MessageWindowChatMemory` | 仅近 N 条 |
| 情节 | `ChatMemoryRepository`（JDBC/Cassandra/Mongo） | **不支持 tool call 消息**（除非用 Session 项目） |
| 语义 | `VectorStore` + RAG | 无偏好层 |
| 程序 | `@Tool` / `ToolCallback` | 不能动态学习新 skill |
| 元 | （无原生设施，本文实现） | 需自研 |

---

## 1. 短时记忆：会话窗口

### 1.1 MessageWindowChatMemory 的边界

```java
// 本代码仅作学习材料参考
MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();
```

- **永远保留 system message**（不计入窗口）
- 超出窗口的消息**直接丢弃**——不归档、不摘要
- 2.0.0 新增 `sequence_id` 列，按 turn 边界裁剪

### 1.2 短时记忆的两个扩展

#### A. 滚动摘要（rolling summary）

```java
// 本代码仅作学习材料参考
public class SummarizingMemory implements ChatMemory {
    private final ChatMemoryRepository repo;
    private final ChatClient summarizer;
    private final int window;
    private final int summaryTrigger;

    @Override
    public void add(String convId, List<Message> msgs) {
        List<Message> all = new ArrayList<>(repo.findByConversationId(convId));
        all.addAll(msgs);

        if (all.size() > summaryTrigger) {
            // 把最早一半的消息摘要成一条 system message
            List<Message> toSummarize = all.subList(0, all.size() / 2);
            String summary = summarizer.prompt()
                    .system("把以下对话摘要成 200 字内的关键信息")
                    .user(toSummarize.toString())
                    .call().content();
            Message summaryMsg = new SystemMessage("[过往摘要] " + summary);

            List<Message> kept = new ArrayList<>();
            kept.add(summaryMsg);
            kept.addAll(all.subList(all.size() / 2, all.size()));
            repo.saveAll(convId, kept);
        } else {
            repo.saveAll(convId, all);
        }
    }
}
```

#### B. token-aware 窗口

按消息数窗口的问题是 token 不均匀（一段代码 token = 一句客套话 10 倍）。改进：按 token 数裁剪。

```java
// 本代码仅作学习材料参考
private List<Message> trimByTokens(List<Message> all, int maxTokens) {
    int total = 0;
    List<Message> kept = new ArrayList<>();
    for (int i = all.size() - 1; i >= 0; i--) {
        int t = tokenizer.count(all.get(i).getText());
        if (total + t > maxTokens) break;
        kept.add(0, all.get(i));
        total += t;
    }
    if (all.get(0).getType() == MessageType.SYSTEM) {
        kept.add(0, all.get(0));  // system 永远保留
    }
    return kept;
}
```

---

## 2. 情节记忆：跨会话历史

### 2.1 JDBC / Cassandra / Mongo 的局限

Spring AI 2.0 内置的 `ChatMemoryRepository` 实现（JDBC / Cassandra / Mongo）**会静默丢弃 ToolCall / ToolResponse 消息**——它们只持久化文本消息。

如果你的 Agent 跨会话恢复后还要继续推进工具调用（如"上次帮我订的机票，现在改签"），必须用 **Spring AI Session 项目**（`spring-ai-session`）：

- 事件溯源（每个 message 是一个 event）
- 可重放（replay from event log）
- 完整保留 tool call 上下文

### 2.2 情节记忆的检索：把会话历史当 RAG

把所有过往对话作为文档存进向量库，新 query 时检索最相关的 K 条历史：

```java
// 本代码仅作学习材料参考
public class EpisodicMemoryAdvisor implements BaseAdvisor {
    private final VectorStore vs;
    private final ChatClient client;

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String userId = (String) req.context().get("userId");
        String query = req.prompt().getUserMessage().getText();

        List<Document> hits = vs.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("userId == '" + userId + "' AND type == 'episode'")
                .build());

        String episodeContext = hits.stream()
                .map(d -> "[过去对话] " + d.getText())
                .collect(Collectors.joining("\n"));

        return req.mutate()
                .prompt(req.prompt().mutate()
                        .userMessage(new UserMessage(
                                "相关历史：\n" + episodeContext + "\n\n当前问题：" + query))
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        // 把这次对话存为 episode
        String userId = (String) resp.context().get("userId");
        String text = resp.chatClientRequest().prompt().getUserMessage().getText()
                + "\n=> " + resp.chatResponse().getResult().getOutput().getText();
        vs.add(List.of(Document.builder()
                .text(text)
                .metadata(Map.of("userId", userId, "type", "episode",
                        "ts", Instant.now().toString()))
                .build()));
        return resp;
    }

    @Override public String getName() { return "EpisodicMemoryAdvisor"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 250; }
}
```

### 2.3 隐私：情节记忆的合规边界

- **PII 必须脱敏**后再入库（用户身份证号、电话）。
- **TTL**：90 天默认，用户可主动删除（GDPR / 个人信息保护法）。
- **多租户隔离**：metadata 里 `userId` 是必填，filter 强制带。

---

## 3. 语义记忆：知识库

`VectorStore` + RAG 已经是 07 篇的内容，这里只补三点关键：

### 3.1 语义记忆 vs 情节记忆

| 维度 | 语义 | 情节 |
|------|------|------|
| 内容 | 客观事实（"公司退款政策是 7 天"） | 主观经历（"用户上次问过退款"） |
| 来源 | 文档导入 | 对话抽取 |
| 时效 | 缓慢变化 | 快速变化 |
| 共享 | 多用户共享 | 用户私有 |

### 3.2 从对话里抽取语义记忆

每次对话结束后，让 LLM 判断"这次对话产生了哪些可沉淀的事实"：

```java
// 本代码仅作学习材料参考
record ExtractedFact(String content, String category, double confidence) {}

public Flux<ExtractedFact> extractFacts(String conversation) {
    return Flux.fromArray(client.prompt()
            .system("""
                    从对话中抽取可长期保存的事实（用户偏好、产品规则、约定）。
                    忽略一次性细节。返回 JSON 数组。
                    """)
            .user(conversation)
            .call()
            .entity(ExtractedFact[].class));
}
```

抽取后入库：

```java
facts.filter(f -> f.confidence() > 0.7)
     .subscribe(f -> semanticVs.add(List.of(Document.builder()
             .text(f.content())
             .metadata(Map.of("category", f.category(),
                     "userId", userId, "ts", Instant.now().toString()))
             .build())));
```

### 3.3 语义冲突处理

新事实与旧事实冲突时（"以前政策是 7 天，现在 14 天"）：

- 不要直接覆盖，先存版本（`version=2`, `valid_from=...`）
- 检索时按时间过滤（默认最新版本）
- 用户问"以前政策是什么"时， retrieves 历史版本

---

## 4. 程序记忆：工具 / Skill

### 4.1 静态 vs 动态

- **静态程序记忆**：`@Tool` 注解，编译期固定。
- **动态程序记忆**：ToolCallback 接口，运行时构造（见 02 篇 §6）。

### 4.2 "Agent 学会新技能"

更激进的设想：Agent 在对话中发现"我需要某个工具"，自动生成 tool definition 并注册。

```java
// 本代码仅作学习材料参考（实验性）
public ToolCallback synthesizeTool(String userIntent) {
    ToolDefinition def = client.prompt()
            .system("""
                    根据用户意图生成一个 tool definition。
                    输出 JSON Schema 格式。
                    """)
            .user(userIntent)
            .call()
            .entity(ToolDefinition.class);

    return new DynamicToolCallback(def, args -> {
        // 这个工具"实际行为"可以是调 LLM 实现（self-implementing）
        return client.prompt()
                .system("You are tool: " + def.name())
                .user("Args: " + args)
                .call().content();
    });
}
```

⚠️ **生产慎用**：自动生成工具 = 把 prompt injection 风险面放大无数倍。必须配合 [`./13-安全工程与红队.md`](./13-安全工程与红队.md) 的红队测试。

### 4.3 程序记忆的"工具市场"

见 [`./05-MCP协议全解.md`](./05-MCP协议全解.md) 的 MCP Hub 设计。

---

## 5. 元记忆：人格 / 偏好

模型默认人格是"通用助手"，但业务里往往需要"懂你的助手"。

### 5.1 用户画像

```java
// 本代码仅作学习材料参考
public record UserProfile(
        String userId,
        String persona,           // "技术决策者"
        List<String> preferences, // ["简洁", "带数字", "中英术语"]
        Map<String, String> facts // ["team" -> "后端", "stack" -> "Spring"]
) {}
```

### 5.2 Profile Advisor：注入人格

```java
// 本代码仅作学习材料参考
public class ProfileAdvisor implements BaseAdvisor {
    private final ProfileStore store;

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String userId = (String) req.context().get("userId");
        UserProfile p = store.get(userId);

        return req.mutate()
                .prompt(req.prompt().mutate()
                        .system(p.persona() + " 偏好：" + p.preferences())
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        // 异步从对话更新画像（用 LLM 抽取偏好）
        String userId = (String) resp.context().get("userId");
        CompletableFuture.runAsync(() ->
                store.updateFrom(userId, resp.chatResponse().getText()));
        return resp;
    }

    @Override public String getName() { return "ProfileAdvisor"; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }
}
```

### 5.3 不要让 LLM 自己"决定"人格

反模式：

```
You are a helpful assistant. Adapt to the user.
```

模型会过度拟合最近的反馈（recency bias），不稳定。**显式 persona + 偏好列表**才稳。

---

## 6. 五种记忆协同：完整架构

```
用户消息
    ↓
[Profile Advisor]    注入人格 + 偏好
    ↓
[Episodic Advisor]   检索"过去对话"
    ↓
[Semantic Advisor]   检索"知识库"（RAG）
    ↓
[Short Memory]       滚动窗口 + 摘要
    ↓
ChatModel.call
    ↓
[Tool Calling]       程序记忆（工具）
    ↓
响应
    ↓
[After 阶段]
    ├── 抽取语义事实 → 入语义记忆
    ├── 抽取偏好 → 更新画像
    └── 写入情节记忆 → 入向量库
```

每个 advisor 各管一种记忆类型，正交组合。

---

## 7. 数据基础设施

### 7.1 三套存储

| 存储 | 用途 | 技术 |
|------|------|------|
| OLTP（PostgreSQL） | 用户画像、配置、元记忆 | 关系表 |
| 时序 / Event Store | 情节记忆、会话日志 | Kafka + Postgres event table |
| 向量库（pgvector / Milvus） | 语义记忆 + 情节向量索引 | pgvector / Milvus / Qdrant |

### 7.2 一致性

- **强一致**：用户主动操作（"删除我所有对话"）→ 必须跨三库事务。
- **最终一致**：异步抽取 → 用 outbox + Kafka 解耦。

详见 [`./16-AI原生系统设计.md`](./16-AI原生系统设计.md) Event Sourcing + CQRS。

---

## 8. 遗忘机制

人类有遗忘，AI 也需要——否则画像/情节会无意义膨胀。

| 遗忘类型 | 触发 | 实现 |
|---------|------|------|
| **衰减** | 7-30 天未被命中 | cron 扫描 + 删除低权重 |
| **冲突覆盖** | 新事实与旧事实冲突 | 版本号 + 默认取最新 |
| **主动遗忘** | 用户删除请求 | GDPR "right to be forgotten" |
| **混淆** | 偏好漂移 | 最近 30 条对话 relearn |

---

## 9. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| 把所有历史塞进 context | token 爆炸 + 模型混淆 | 用 Episodic Advisor 检索 K 条 |
| 用 JDBC ChatMemory 存 tool call | tool call 静默丢失 | 用 Spring AI Session |
| 没有遗忘机制 | 数据无限增长 + 召回降级 | TTL + 衰减 + 版本 |
| 元记忆每次都让 LLM 现场判断 | 不稳定 | 显式 persona 注入 |
| 把语义记忆当情节用 | 用户私有信息被共享 | metadata 隔离 userId |
| 把敏感信息存进向量库明文 | 合规风险 | 入库前 PII 脱敏 |
| 工具自动生成不审计 | prompt injection 风险 | 人工 review 才能注册 |

---

## 10. 实战任务

1. 实现 `SummarizingMemory`（滚动摘要），对比与 `MessageWindowChatMemory` 在 50 轮对话下的效果。
2. 实现 `EpisodicMemoryAdvisor`，在客服场景验证"用户上次问过 X" 能被准确召回。
3. 实现 `ProfileAdvisor`，让 Agent 学会用户偏好（喜欢简洁 / 喜欢详细）。
4. 设计遗忘机制：30 天未被命中的 episode 自动归档。
5. （进阶）把 5 种记忆整合成一个 `MemoryOrchestrator`，对外只暴露 `recall(userId, query)` / `consolidate(session)`。
6. （选做）调研 MemGPT / Letta 的虚拟内存管理思路，对比本文设计。

---

## 11. 理解检查

1. 短 / 长 / 情节 / 语义 / 程序五种记忆的差异？分别对应 Spring AI 的什么设施？
2. 为什么 JDBC ChatMemoryRepository 不能存 tool call？替代方案是？
3. 情节记忆和语义记忆在数据模型上有什么区别？
4. 元记忆为什么不能让 LLM 自己现场判断？
5. 遗忘机制有哪四种？分别解决什么问题？
6. 五种记忆的协同顺序是什么？为什么 Profile 在最外层？

---

## 12. 相关文档

- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— Advisor 注入记忆的标准做法
- [`./07-RAG工程化实战.md`](./07-RAG工程化实战.md) —— 语义记忆基础设施
- [`./16-AI原生系统设计.md`](./16-AI原生系统设计.md) —— Event Sourcing 记忆
- [`./17-大规模Agent平台与数据基础设施.md`](./17-大规模Agent平台与数据基础设施.md) —— 数据基础设施
- [`./21-框架源码精读.md`](./21-框架源码精读.md) §5 —— ChatMemory 源码
- [MemGPT Paper](https://arxiv.org/abs/2310.08560)
- [Letta (MemGPT) GitHub](https://github.com/letta-ai/letta)
- [Generative Agents (Park et al., 2023)](https://arxiv.org/abs/2304.03442) —— Memory stream 设计原型

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
