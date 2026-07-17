# 19 RAG 高级篇

> 本文是 [`./09-RAG工程化实战.md`](./09-RAG工程化实战.md) 的进阶深化。
>
> 07 讲了 6 模块的基础版（Hybrid Search、Rerank、Query 改写都有，但停留在"会调"）。本文讲"调到极致"：融合算法（RRF / 加权）、Re-ranking 模型选型、HyDE / 子问题分解、Parent-Child chunk、自适应检索、Graph RAG、Agentic RAG。
>
> 前置：[`./09-RAG工程化实战.md`](./09-RAG工程化实战.md) + [`./03-Advisor链全解.md`](./03-Advisor链全解.md)
> 预计：2 天

---

## 0. 认知地图

```
基础 RAG（07）：chunk + embed + 检索 + 拼接 prompt
    ↓
进阶 RAG（本文）：
    ├── 召回融合：RRF / 加权 / cascaded
    ├── 排序精修：cross-encoder / LLM rerank / listwise
    ├── 查询增强：HyDE / 子问题 / Multi-Query / Step-Back
    ├── 索引结构：Parent-Child / APIClient / 层级摘要
    ├── 自适应检索：判断是否要 RAG、要查几次
    ├── Graph RAG：实体关系图谱增强
    └── Agentic RAG：让 Agent 决策检索策略
```

**核心心法**：基础 RAG 是"召回 + 拼接"，进阶 RAG 是**"召回精度 × 排序精度 × 查询精度 × 索引精度"**四项乘积。任一项短板都会拖垮整体。

---

## 1. 为什么基础 RAG 不够

### 1.1 基础 RAG 的天花板

```
用户问："我上周问过的那个退货流程，最新版是什么？"
    ↓
基础 RAG：
    1. embed("我上周问过的那个退货流程，最新版是什么")
    2. 向量检索 → top5 chunk
    3. 拼到 prompt → LLM 答
    ↓
问题：
    - "上周问过"是上下文，单查 query 检索不到
    - 退货流程有多个版本，没排序就乱
    - 检索到的可能是"如何下单"的 chunk（语义近但错）
```

### 1.2 进阶 RAG 怎么解

| 问题 | 进阶手段 | 本文章节 |
|------|---------|---------|
| Query 表达不充分 | Query Rewriting（HyDE / 子问题） | §4 |
| 单路召回漏召回 | Multi-Query + 融合（RRF） | §2-3 |
| 召回对但排序乱 | Cross-encoder / LLM Rerank | §5 |
| 命中局部信息 | Parent-Child chunk / 层级摘要 | §6 |
| 不该查也查 | 自适应检索（先判断） | §7 |
| 跨文档推理 | Graph RAG | §8 |
| 多步检索需求 | Agentic RAG | §9 |

---

## 2. 召回融合：RRF vs 加权

### 2.1 为什么单路召回不够

单路向量检索的失败模式：

- **语义近但概念错**：query "退款" 召回到 "退货"（语义近，但退货 ≠ 退款）。
- **OOV（Out-of-Vocabulary）**：业务专有名词（"工单 SLA"）向量模型没见过。
- **跨语言**：中文 query 但知识库是英文。

**多路召回**：同时跑 BM25（关键词精确）+ 向量（语义模糊）+ 元数据过滤（业务约束），结果融合。

### 2.2 RRF（Reciprocal Rank Fusion，倒数排名融合）

**公式**：`score(d) = Σ 1 / (k + rank_i(d))`

- `d`：某个文档
- `rank_i(d)`：文档 d 在第 i 路检索结果中的排名（1-based）
- `k`：常数（通常 60），避免排名 1 的文档权重过大

```java
// org.demo02.rag.fusion.RRFFusion
// 本代码仅作学习材料参考

public class RRFFusion {

    private static final int K = 60;

    public List<ScoredDocument> fuse(List<List<Document>> rankedLists, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docs = new HashMap<>();

        for (List<Document> ranked : rankedLists) {
            for (int rank = 0; rank < ranked.size(); rank++) {
                Document doc = ranked.get(rank);
                String id = doc.getId();
                double contribution = 1.0 / (K + rank + 1);
                scores.merge(id, contribution, Double::sum);
                docs.putIfAbsent(id, doc);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> ScoredDocument.from(docs.get(e.getKey()), e.getValue()))
                .toList();
    }
}
```

**RRF 优势**：

- 不需要分数归一化（BM25 分数和向量 cosine 不可比）。
- 对 outlier 不敏感（一路暴高分不会主导）。
- 实现简单，效果好（业界默认）。

### 2.3 加权融合（Weighted Fusion）

```java
public class WeightedFusion {

    public List<ScoredDocument> fuse(List<ScoredList> rankedLists,
                                      List<Double> weights, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docs = new HashMap<>();

        for (int i = 0; i < rankedLists.size(); i++) {
            ScoredList list = rankedLists.get(i);
            double weight = weights.get(i);
            double max = list.scores().stream().mapToDouble(Double::doubleValue).max().orElse(1);

            for (int j = 0; j < list.docs().size(); j++) {
                Document doc = list.docs().get(j);
                double normalizedScore = list.scores().get(j) / max;
                scores.merge(doc.getId(), weight * normalizedScore, Double::sum);
                docs.putIfAbsent(doc.getId(), doc);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> ScoredDocument.from(docs.get(e.getKey()), e.getValue()))
                .toList();
    }
}
```

**加权 vs RRF 怎么选**：

| 维度 | RRF | 加权 |
|------|-----|------|
| 是否需要调权重 | 否 | 是（权重调坏会跌） |
| 分数可解释 | 否（只是排名融合） | 是 |
| 对异常分数敏感 | 不敏感 | 敏感 |
| 默认推荐 | ✅ | 调过权重后更准 |

### 2.4 Cascaded Retrieval（级联检索）

不一定融合，可以**先用一路粗筛，再用另一路精排**：

```
1. BM25 召回 top100（精确关键词覆盖）
2. 向量精排 top100 → top30（语义补充）
3. cross-encoder 精排 top30 → top5（最终答案）
```

适合**大规模知识库**（千万级 chunk），单路向量检索成本太高。

---

## 3. Multi-Query：让 LLM 帮你生成多个 query

### 3.1 思路

一个 query 检索 1 次 → top5。让 LLM 把 query 改写成 5 个不同视角 → 检索 5 次 → top25 → 融合 → top5。

**收益**：召回率提升 15-30%（来自 LangChain 实测）。

**成本**：多 4 次 embedding + 4 次向量查询（便宜）+ 1 次 LLM 改写（贵）。

### 3.2 实现

```java
// org.demo02.rag.query.MultiQueryExpander
// 本代码仅作学习材料参考

@Component
public class MultiQueryExpander {

    private static final int NUM_QUERIES = 4;
    private final ChatClient planner;

    public List<String> expand(String originalQuery) {
        String response = planner.prompt()
                .system("""
                    你是搜索查询优化器。把用户问题改写成 %d 个不同视角的搜索查询，
                    用于检索知识库。每个查询独立一行，不要编号，不要解释。
                    查询要覆盖：同义词替换、概念具体化、问题反向、场景化。
                    """.formatted(NUM_QUERIES))
                .user(originalQuery)
                .call()
                .content();

        List<String> queries = new ArrayList<>();
        queries.add(originalQuery);
        queries.addAll(Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(NUM_QUERIES)
                .toList());
        return queries;
    }
}
```

### 3.3 用法

```java
public List<Document> retrieveWithMultiQuery(String query) {
    List<String> queries = expander.expand(query);
    List<List<Document>> rankedLists = queries.parallelStream()
            .map(q -> vectorStore.similaritySearch(
                    SearchRequest.builder().query(q).topK(10).build()))
            .toList();
    return rrfFusion.fuse(rankedLists, 5);
}
```

---

## 4. Query Rewriting 三大范式

### 4.1 HyDE（Hypothetical Document Embedding）

**直觉**：query 和文档的语言风格不一致（query 是问题，文档是陈述）。先让 LLM **编一个假答案**，再用假答案去检索 —— 假答案的语言风格和真文档更接近。

```
用户问："如何配置 Spring AI 的 ChatClient？"
    ↓ HyDE
LLM 编答案（不一定对）：
    "要配置 Spring AI 的 ChatClient，首先在 pom.xml 引入
     spring-ai-openai-spring-boot-starter，然后在 application.yaml
     配置 api-key，最后通过 ChatClient.builder()..."
    ↓
用假答案 embed → 检索 → 真答案
```

```java
// org.demo02.rag.query.HyDERewriter
// 本代码仅作学习材料参考

@Component
public class HyDERewriter {

    private final ChatClient planner;

    public String rewrite(String userQuery) {
        return planner.prompt()
                .system("""
                    请基于下面的问题，写一段 200 字左右的"假设性答案"。
                    假设答案完全正确，用陈述句写，包含可能的关键词和概念。
                    不要解释你在写假设性答案，直接给出答案内容。
                    """)
                .user(userQuery)
                .call()
                .content();
    }
}

// 检索时用假答案代替原 query
public List<Document> retrieveWithHyDE(String query) {
    String hydeText = hydeRewriter.rewrite(query);
    return vectorStore.similaritySearch(
            SearchRequest.builder().query(hydeText).topK(5).build());
}
```

**收益**：对**事实性查询**（"XX 怎么用"）效果显著，召回率 +20%。
**反作用**：对**观点性查询**（"你觉得 XX 怎么样"）效果差，假答案可能误导检索。

### 4.2 子问题分解（Sub-Question Decomposition）

复杂问题拆成多个子问题，分别检索：

```
用户问："比较 Spring AI 和 LangChain4j 在流式响应和工具调用上的差异"
    ↓ 拆解
1. Spring AI 流式响应怎么实现
2. LangChain4j 流式响应怎么实现
3. Spring AI 工具调用怎么实现
4. LangChain4j 工具调用怎么实现
    ↓
4 次检索 → 拼到一起 → LLM 综合
```

```java
@Component
public class SubQuestionDecomposer {

    private final ChatClient planner;

    public List<String> decompose(String complexQuery) {
        String response = planner.prompt()
                .system("""
                    把用户的复杂问题拆解成 2-5 个独立的子问题，
                    每个子问题独立成行，可以分别检索回答。
                    不要编号，不要解释，直接给子问题。
                    """)
                .user(complexQuery)
                .call()
                .content();

        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

public Map<String, List<Document>> retrieveBySubQuestions(String query) {
    return decomposer.decompose(query).stream()
            .collect(Collectors.toMap(
                    subQ -> subQ,
                    subQ -> vectorStore.similaritySearch(
                            SearchRequest.builder().query(subQ).topK(3).build())
            ));
}
```

**收益**：对**对比类、多步骤问题**特别有效。
**成本**：N 倍检索 + 1 次 LLM 拆解 + 1 次 LLM 综合（贵）。

### 4.3 Step-Back Prompting（后退一步提问）

不直接回答，先问一个更抽象的问题：

```
用户问："2024 年 Q3 GPT-4 在 MATH 基准上的分数是多少？"
    ↓ Step-Back
后退问题："GPT-4 在 2024 年的基准测试表现如何？"
    ↓
两个问题都检索，结合回答
```

适合**容易过拟合到具体细节的问题**，后退一步召回更全的上下文。

### 4.4 三种 Rewriting 对比

| 方法 | 适用 | 成本 | 实现难度 |
|------|------|------|---------|
| HyDE | 事实性查询 | 1 LLM call | 简单 |
| 子问题分解 | 对比 / 多步推理 | 1 + N LLM call | 中 |
| Step-Back | 容易陷入细节的问题 | 1 + 1 LLM call | 简单 |
| Multi-Query（§3） | 通用增强 | 1 LLM call | 简单 |

---

## 5. Re-ranking 深度选型

### 5.1 为什么向量检索的"分数"不可信

向量检索返回的 cosine 相似度**不是答案正确性概率**：

- top1 cosine 0.89 不代表 89% 正确。
- top5 的 cosine 可能都接近（0.85-0.89），但实际只有 1 个相关。

**Re-ranking** 用更贵但更准的模型重新排序。

### 5.2 三种 Re-ranker

#### Bi-encoder（向量检索本身）

```
query → encoder → vector
doc   → encoder → vector
score = cosine(q_vec, d_vec)
```

**特点**：快（向量可以预算存），但 query 和 doc 没交互，精度有限。

#### Cross-encoder

```
[CLS] query [SEP] doc [SEP] → encoder → score（标量）
```

query 和 doc **拼成一句话**喂给 encoder，自注意力让两者充分交互。

**特点**：准（比 bi-encoder 高 10-20%），但慢（每对都要算一次，无法预算）。

#### LLM Rerank

```
让 LLM 看一遍 query + top10 docs，输出重新排序后的 top5。
```

**特点**：最准（理解能力强），最贵（每次都调 LLM）。

### 5.3 主流 Cross-encoder 模型

| 模型 | 参数规模 | 语言 | 部署 |
|------|---------|------|------|
| **bge-reranker-large** | 560M | 中英 | 自托管 |
| **bge-reranker-v2-m3** | 568M | 多语言 | 自托管 |
| **Cohere Rerank 3** | 商业 | 多语言 | API |
| **Jina Reranker v2** | 278M | 多语言 | 自托管 / API |
| **Qwen-rerank** | 商业 | 中英 | API（通义） |

**推荐**：中文为主选 `bge-reranker-large` 自托管；英文 / 国际化选 `Cohere Rerank 3`。

### 5.4 Spring AI 接入 Cross-encoder

Spring AI 没有原生 rerank 抽象，自己实现一个：

```java
// org.demo02.rag.rerank.CrossEncoderReranker
// 本代码仅作学习材料参考

@Component
public class CrossEncoderReranker {

    private final OnnxBertBiEncoder model;   // 用 ONNX runtime 加载 bge-reranker

    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        record ScoredDoc(Document doc, double score) {}
        List<ScoredDoc> scored = new ArrayList<>();

        for (Document doc : candidates) {
            double score = model.score(query, doc.getText());
            scored.add(new ScoredDoc(doc, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(topK).map(ScoredDoc::doc).toList();
    }
}
```

### 5.5 LLM Listwise Rerank（排名式重排）

比 pointwise（一个个打分）更准 —— 让 LLM 直接看 top10 输出排名：

```java
@Component
public class LlmListwiseReranker {

    private final ChatClient ranker;

    public List<Document> rerank(String query, List<Document> docs, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(query).append("\n\n候选文档：\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
              .append(truncate(docs.get(i).getText(), 200)).append("\n\n");
        }
        sb.append("\n请按相关性从高到低输出文档编号（如 [3, 1, 5, 2, 7]），不要解释。");

        String response = ranker.prompt()
                .user(sb.toString())
                .call()
                .content();

        List<Integer> ranks = parseRanks(response, docs.size());
        return ranks.stream().limit(topK).map(i -> docs.get(i - 1)).toList();
    }
}
```

**收益**：比 cross-encoder 再高 5-10%（来自 RankGPT 论文）。
**成本**：top10 一次 LLM call（输入约 2000 token，输出 50 token）。

### 5.6 Reranker 三档选型

| 场景 | 选 |
|------|---|
| 知识库小（< 10万 chunk） | 不需要 rerank，向量检索够 |
| 通用场景 | bge-reranker-large（自托管） |
| 高准确率优先 | bge-reranker → LLM listwise 两级 |
| 不想自托管 | Cohere Rerank API |

---

## 6. Parent-Child Chunking（父子分块）

### 6.1 问题

**小块召回准**（向量纯净），**大块答得全**（上下文充足）。两者矛盾：

- chunk 切 256 字 → 召回精准，但答案可能在前后文。
- chunk 切 1024 字 → 上下文够，但向量混入太多无关信息，召回率下降。

### 6.2 解决：父子分块

```
原文档（4000 字）
    ↓ 切大块（parent，1024 字）
[Parent-1] [Parent-2] [Parent-3] [Parent-4]
    ↓ 每个大块再切小块（child，256 字）
[Child-1a][Child-1b][Child-1c][Child-1d]  ← Parent-1
[Child-2a][Child-2b][Child-2c][Child-2d]  ← Parent-2
...

索引：只 embed child
检索：用 child query → 找到 child → 返回对应的 parent
```

```java
// org.demo02.rag.chunk.ParentChildChunker
// 本代码仅作学习材料参考

public class ParentChildChunker {

    private final TokenTextSplitter parentSplitter;  // 大块
    private final TokenTextSplitter childSplitter;   // 小块

    public record Chunk(String id, String parentId, String text, boolean isChild) {}

    public List<Chunk> chunk(Document doc) {
        List<Chunk> result = new ArrayList<>();
        List<String> parents = parentSplitter.split(doc.getText());

        for (int p = 0; p < parents.size(); p++) {
            String parentId = doc.getId() + "_p" + p;
            result.add(new Chunk(parentId, null, parents.get(p), false));

            List<String> children = childSplitter.split(parents.get(p));
            for (int c = 0; c < children.size(); c++) {
                String childId = parentId + "_c" + c;
                result.add(new Chunk(childId, parentId, children.get(c), true));
            }
        }
        return result;
    }
}

// 索引时只 embed child
public void index(List<ParentChildChunker.Chunk> chunks) {
    List<Document> childDocs = chunks.stream()
            .filter(ParentChildChunker.Chunk::isChild)
            .map(c -> Document.builder()
                    .id(c.id())
                    .text(c.text())
                    .metadata("parent_id", c.parentId())
                    .build())
            .toList();
    vectorStore.add(childDocs);

    // parent 存关系库（不入向量库）
    chunks.stream().filter(c -> !c.isChild()).forEach(c ->
            jdbc.update("INSERT INTO parent_chunks (id, text) VALUES (?, ?)",
                    c.id(), c.text()));
}

// 检索：召回 child → 拿 parent_id → 取 parent 文本
public List<Document> retrieve(String query, int topK) {
    List<Document> childHits = vectorStore.similaritySearch(
            SearchRequest.builder().query(query).topK(topK).build());

    Set<String> parentIds = childHits.stream()
            .map(d -> d.getMetadata().get("parent_id").toString())
            .collect(Collectors.toSet());

    return parentIds.stream()
            .map(pid -> jdbc.queryForObject(
                    "SELECT text FROM parent_chunks WHERE id = ?",
                    (rs, i) -> new Document(rs.getString("text"), Map.of("id", pid)),
                    pid))
            .toList();
}
```

### 6.3 层级摘要（Hierarchical Summary）

更进一步：每个文档生成多级摘要，每级都 embed：

```
L0：全文摘要（100 字）—— 用于"宏观问题"
L1：章节摘要（每章 100 字）—— 用于"中观问题"
L2：原文 chunk（每块 500 字）—— 用于"细节问题"
```

检索时三层并行，融合结果。适合长文档（>10K 字）。

### 6.4 不同 chunk 策略对比

| 策略 | 召回 | 上下文 | 适合 |
|------|------|--------|------|
| 固定小块（256 字） | 高 | 弱 | QA 简单事实 |
| 固定大块（1024 字） | 低 | 强 | 长上下文推理 |
| Parent-Child | 高 | 强 | **大多数场景（推荐）** |
| 层级摘要 | 高 | 强 | 长文档 / 多层级问题 |
| Semantic Chunking（按语义切） | 中 | 中 | 对切分边界敏感的领域（法律、医学） |

---

## 7. 自适应检索（Adaptive Retrieval）

### 7.1 问题

不是所有问题都需要 RAG：

- "你好" —— 闲聊，无需检索。
- "1+1=?" —— 计算，无需检索。
- "请把上面这段翻译成英文" —— 上下文已有，无需检索。

**无脑 RAG** 浪费 token、降低速度、还可能引入误导。

### 7.2 解决：先让 LLM 判断

```java
// org.demo02.rag.adaptive.RetrievalDecider
// 本代码仅作学习材料参考

@Component
public class RetrievalDecider {

    private final ChatClient planner;

    public enum Decision { NEEDS_RETRIEVAL, NO_RETRIEVAL, NEEDS_MULTI_HOP }

    public Decision decide(String userQuery, List<Message> conversationHistory) {
        String response = planner.prompt()
                .system("""
                    判断回答用户问题是否需要查询知识库。输出：
                    - RETRIEVE：需要查询
                    - NO：不需要（闲聊、计算、翻译、上下文已有答案等）
                    - MULTI：需要多次查询（对比、多步推理）
                    只输出一个词，不要解释。
                    """)
                .user(userQuery)
                .call()
                .content()
                .trim();

        return switch (response.toUpperCase()) {
            case "RETRIEVE" -> Decision.NEEDS_RETRIEVAL;
            case "MULTI" -> Decision.NEEDS_MULTI_HOP;
            default -> Decision.NO_RETRIEVAL;
        };
    }
}

// 用法：作为 Advisor 链的最外层
public class AdaptiveRetrievalAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        String query = req.prompt().getUserMessage().getText();
        var decision = decider.decide(query, req.history());

        req.context().put("retrieval_decision", decision);
        if (decision == RetrievalDecider.Decision.NO_RETRIEVAL) {
            req.context().put("skip_rag", true);
        }
        return req;
    }
}
```

### 7.3 Self-RAG（自反思 RAG）

更进一步：每一步检索后让 LLM 判断"召回的够不够"，不够就再查：

```
1. 判断：要检索 → 检索 top5
2. LLM 看召回：相关吗？够吗？
   - 相关够 → 回答
   - 不相关 → 改写 query，再检索
3. 最多 3 轮
```

成本翻倍但准确率显著提升。适合**高准确率场景**（医疗、法律）。

---

## 8. Graph RAG：用知识图谱增强

### 8.1 问题

向量 RAG 的盲区：**跨文档推理**。

```
用户问："张三管理的团队的 SLA 达标率怎么样？"
    ↓
向量 RAG 检索：
    - chunk1："张三是 X 团队的 leader"
    - chunk2："X 团队的 SLA 是 99.9%"
    - chunk3："Y 团队的 SLA 达标率是 95%"
    - chunk4："X 团队 2024 年 Q3 SLA 达标率是 92%"
    ↓
LLM 看了一堆，但可能选错 chunk3（Y 团队）
```

**根因**：向量相似度不知道"张三 → X 团队"是关键关系。

### 8.2 Graph RAG 思路

构建知识图谱：

```
节点：张三、X 团队、SLA、Q3 达标率
边：管理、属于、指标
```

检索时**先图查询，再向量补充**：

```cypher
// 先图查询
MATCH (p:Person {name: '张三'})-[:MANAGES]->(t:Team)
MATCH (t)-[:HAS_KPI]->(k:KPI)
RETURN k.value
```

### 8.3 实现：Microsoft GraphRAG 思路

**索引阶段**：

1. chunk 文档。
2. LLM 抽取每个 chunk 的实体 + 关系。
3. 合并去重，构建图。
4. 用社区发现算法（Leiden）把图分成社区。
5. LLM 给每个社区生成摘要。

**检索阶段**：

- **Global Search**（全局问题）：用社区摘要回答。
- **Local Search**（局部问题）：图查询 + 向量检索。

### 8.4 轻量级 Graph RAG

全量 Graph RAG 工程复杂。轻量做法：**只索引实体 → 检索时用实体过滤**：

```java
@Component
public class EntityIndexer {

    private final ChatClient extractor;

    public Set<String> extractEntities(String text) {
        String response = extractor.prompt()
                .system("抽取文本中的实体（人名、机构、产品、地名），逗号分隔，不要解释。")
                .user(text)
                .call()
                .content();
        return Arrays.stream(response.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}

// 索引时把实体存进 chunk 元数据
public void index(Document doc) {
    Set<String> entities = entityIndexer.extractEntities(doc.getText());
    Document enriched = Document.builder()
            .id(doc.getId())
            .text(doc.getText())
            .metadata("entities", String.join("|", entities))
            .build();
    vectorStore.add(List.of(enriched));
}

// 检索时用实体过滤
public List<Document> retrieve(String query) {
    Set<String> queryEntities = entityIndexer.extractEntities(query);
    String filter = queryEntities.stream()
            .map(e -> "'*" + e + "*'")
            .collect(Collectors.joining(" OR "));
    return vectorStore.similaritySearch(
            SearchRequest.builder()
                    .query(query)
                    .filterExpression("entities LIKE " + filter)
                    .topK(5)
                    .build());
}
```

---

## 9. Agentic RAG：让 Agent 决定检索

### 9.1 思路

最高阶的 RAG：把检索策略当成工具，让 Agent 自己决定查什么、查几次。

```
用户问："对比 2023 和 2024 年中国新能源汽车销量前三的品牌"
    ↓ Agent 思考
1. 调工具 search_kb("2023 中国新能源汽车销量")
2. 看结果 → 调工具 search_kb("2024 中国新能源汽车销量")
3. 综合回答
```

### 9.2 实现：把检索包装成工具

```java
// org.demo02.rag.agent.RagTools
// 本代码仅作学习材料参考

@Component
public class RagTools {

    private final VectorStore vectorStore;
    private final CrossEncoderReranker reranker;

    @Tool(description = "在公司知识库中搜索相关信息，返回 top 5 文档片段")
    public String searchKnowledgeBase(
            @ToolParam(description = "搜索查询") String query
    ) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(10).build());
        List<Document> reranked = reranker.rerank(query, hits, 5);

        return reranked.stream()
                .map(d -> "【来源: " + d.getMetadata().get("source_url") + "】\n"
                        + d.getText())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "在数据库中查询结构化数据（如销量、用户数）")
    public String queryDatabase(
            @ToolParam(description = "SQL 查询语句，只支持 SELECT") String sql
    ) {
        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            return "错误：只支持 SELECT 查询";
        }
        return jdbc.queryForList(sql).toString();
    }
}

// 给 Agent 用
ChatClient agentClient = ChatClient.builder(model)
        .defaultSystem("你是数据分析师，可以调用工具查知识库和数据库")
        .defaultTools(ragTools)
        .defaultAdvisors(new ToolCallingAdvisor())
        .build();
```

### 9.3 Agentic RAG vs 传统 RAG

| 维度 | 传统 RAG | Agentic RAG |
|------|---------|-------------|
| 检索次数 | 固定（1-3 次） | 动态（按需） |
| 决策 | 规则 | LLM 自主 |
| 延迟 | 低 | 高（多次 LLM 调用） |
| 成本 | 低 | 高 |
| 准确率 | 中 | 高 |
| 适合 | 高并发 / 简单 QA | 低并发 / 复杂推理 |

**何时上 Agentic RAG**：

- 问题需要多步推理（对比、汇总、跨域）。
- 用户接受 10s+ 延迟。
- 单次问答成本可承受（> $0.05）。

详见 [`./10-多Agent编排实战.md`](./10-多Agent编排实战.md) 和 [`./11-五大Workflow模式与代码评审助手.md`](./11-五大Workflow模式与代码评审助手.md) 的 Orchestrator-Workers 模式。

---

## 10. 量化效果对比

某电商客服场景实测（10 万条 FAQ + 商品文档）：

| 配置 | Recall@5 | MRR | 平均延迟 |
|------|---------|-----|---------|
| 纯向量 | 0.72 | 0.58 | 80ms |
| BM25 + 向量 RRF | 0.83 | 0.69 | 120ms |
| 上 + bge-reranker | 0.89 | 0.81 | 250ms |
| 上 + Multi-Query | 0.92 | 0.85 | 600ms |
| 上 + HyDE | 0.91 | 0.84 | 700ms |
| 上 + Parent-Child | 0.93 | 0.87 | 280ms |
| 全部叠加 | 0.95 | 0.90 | 800ms |
| Agentic RAG | 0.96 | 0.93 | 4500ms |

**结论**：

- 短平快：BM25 + 向量 RRF + bge-reranker（250ms，覆盖 80% 场景）。
- 高准确率：再加 Parent-Child + Multi-Query（800ms，覆盖 95% 场景）。
- 极致准确：Agentic RAG（4.5s，复杂推理场景）。

---

## 11. 实战避坑

### 11.1 "RRF 调不出效果"

**原因**：rankedLists 的 rank 起点错了（用了 0-based）。

**解决**：`rank + 1`（1-based），公式才是标准 RRF。

### 11.2 "Multi-Query 改写的 query 都很像"

**原因**：LLM 改写时没强调"不同视角"。

**解决**：system prompt 显式要求"同义词 / 概念具体化 / 反向 / 场景化"四个方向。

### 11.3 "HyDE 反而拖累召回率"

**原因**：知识库是观点 / 评价类（如影评），LLM 编的假答案带偏了。

**解决**：HyDE 只用于事实性 / 操作类查询；评价类用原 query。

### 11.4 "Cross-encoder rerank 太慢"

**原因**：候选太多（top50），cross-encoder 是 N 次 forward。

**解决**：先用 cross-encoder rerank top10 → top5，不要 top50 全跑；或上更小的 reranker（bge-reranker-base）。

### 11.5 "Parent-Child 索引膨胀"

**原因**：parent 也被索引了。

**解决**：**只 embed child**，parent 存关系库（不入向量库）。检索时通过 child_id → parent_id → 查 parent 表。

### 11.6 "Agentic RAG 跑飞了"

**症状**：Agent 反复查知识库，10 次还不答。

**解决**：

- 在 ToolCallingAdvisor 加 `maxTurns=5` 硬限制。
- system prompt 明确："最多调 3 次 search_kb，然后必须综合回答"。

详见 [`./14-安全工程与红队.md`](./14-安全工程与红队.md) 的 Agent 防失控三重保护。

### 11.7 "Graph RAG 索引成本爆炸"

**原因**：每个 chunk 都调 LLM 抽实体，10 万 chunk = 10 万次 LLM call。

**解决**：

- 批量抽（一次喂 5 个 chunk）。
- 只对长文档抽（< 500 字的不抽）。
- 用 NER 模型（如 HanLP、spaCy）替代 LLM 抽实体。

---

## 12. 实战任务

1. 实现 `RRFFusion`，把 BM25 和向量检索结果融合，对比单独一路的 Recall@5。
2. 实现 `MultiQueryExpander`，对 10 个测试 query 做扩展，看 Recall@5 提升。
3. 实现 `HyDERewriter`，对比事实性 query 和评价性 query 的效果差异。
4. 接入 bge-reranker（ONNX runtime 自托管），rerank top10 → top5。
5. 实现 `ParentChildChunker`，对比小块 vs 父子块的回答完整度。
6. 实现 `RetrievalDecider`，10% 流量路由到 no-retrieval，看响应速度提升。
7. 实现"轻量级 Graph RAG"：实体抽取 + 元数据过滤。
8. （进阶）把检索包装成 `@Tool`，实现 Agentic RAG，跑通多步检索场景。
9. （选做）搭一套评估集，对比本文所有策略的 Recall@5 / MRR / 延迟，画成表格。

---

## 13. 理解检查

1. RRF 比加权融合好在哪？什么时候反而该用加权？
2. Multi-Query 为什么能提升召回率？成本和收益怎么权衡？
3. HyDE 在什么场景下反而拖累召回率？为什么？
4. Cross-encoder 比 bi-encoder 准多少？为什么不能直接用 cross-encoder 检索？
5. Parent-Child chunk 解决什么矛盾？为什么只 embed child 不 embed parent？
6. 自适应检索的判断逻辑放在 Advisor 链的哪一层？为什么？
7. Graph RAG 适合什么场景？轻量级实现的核心思路？
8. Agentic RAG 的延迟成本主要在哪？怎么控制不跑飞？

---

## 14. 相关文档

- [`./09-RAG工程化实战.md`](./09-RAG工程化实战.md) —— RAG 基础 6 模块
- [`./03-Advisor链全解.md`](./03-Advisor链全解.md) —— 自适应检索用 Advisor 实现
- [`./02-Tool与AgentLoop.md`](./02-Tool与AgentLoop.md) —— Agentic RAG 的 Tool 基础
- [`./10-多Agent编排实战.md`](./10-多Agent编排实战.md) —— Agentic RAG 的编排
- [`./14-安全工程与红队.md`](./14-安全工程与红队.md) —— Agentic RAG 防失控
- [`./12-评估闭环与Prompt版本管理.md`](./12-评估闭环与Prompt版本管理.md) —— RAG 评估闭环
- [RRF 论文](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)
- [HyDE 论文](https://arxiv.org/abs/2212.10496)
- [Microsoft GraphRAG](https://microsoft.github.io/graphrag/)
- [RankGPT 论文（LLM Listwise Rerank）](https://arxiv.org/abs/2308.01618)

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
