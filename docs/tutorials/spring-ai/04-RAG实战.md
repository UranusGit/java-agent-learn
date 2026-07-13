# Spring AI 04 - RAG 实战

> 用 Spring AI 重做 RAG。重点对比 LangChain4j 的差异。
> 前置：已完成 [01-03]。
>
> ⚠️ **重要前置说明**：
> 1. 本文基于 Spring AI **1.0.0**。0.x 时代的 `QuestionAnswerAdvisor` 已被 `RetrievalAugmentationAdvisor` 替代。
> 2. RAG 相关类**不在主依赖**（`spring-ai-starter-model-openai`）里，需要额外引入下方第 2 节的依赖。
> 3. **当前 demo01 项目的 pom 没有引入 RAG 依赖**，下方代码不能直接在本项目编译运行。先把依赖加上，或者把本文当作原理文档阅读。

---

## 1. Spring AI 的 RAG 设计哲学

### 1.1 核心抽象（1.0.0）

| Spring AI 1.0.0 | LangChain4j | 作用 |
|-----------|-------------|------|
| `EmbeddingModel` | `EmbeddingModel` | 文本转向量 |
| `VectorStore` | `EmbeddingStore` | 向量存储与检索 |
| `DocumentReader` / `DocumentTransformer` | `DocumentReader/Splitter` | 文档处理 |
| `ContentRetriever` + `RetrievalAugmentationAdvisor` | `ContentRetriever` + `RetrievalAugmentor` | 检索 + 注入 prompt |

### 1.2 关键差异

**LangChain4j**：`ContentRetriever` 单独装配，由框架自动调起。

**Spring AI 1.0.0**：检索是 **Advisor** 的一种（`RetrievalAugmentationAdvisor`），能和其他 Advisor 自由组合（如 Memory、日志）。

---

## 2. pom.xml 依赖（项目里需要新增）

> 当前 pom.xml **没有**这些依赖，下列代码仅供学习。

```xml
<!-- RAG 核心抽象：RetrievalAugmentationAdvisor / ContentRetriever -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-rag</artifactId>
</dependency>

<!-- 文档读取：Tika（万能） / Markdown / PDF 等 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>

<!-- 向量库：三选一 -->

<!-- A. SimpleVectorStore：内存版，开发用 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-simple</artifactId>
</dependency>

<!-- B. Chroma（生产可选） -->
<!--
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
</dependency>
-->

<!-- C. pgvector / Redis / Milvus / Qdrant 等同理 -->
```

> 🔍 **artifactId 命名规则**：1.0.0 起，Starter 改成 `spring-ai-starter-vector-store-{provider}` 模式（如 `simple` / `chroma` / `pgvector`）。0.x 时代的 `spring-ai-{provider}-store-spring-boot-starter` 已废弃。

---

## 3. 配置 application.yml

```yaml
spring:
  ai:
    openai:
      # Chat 走 DeepSeek
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
      # Embedding 走本地 LM Studio
      embedding:
        base-url: http://127.0.0.1:1234
        api-key: lm-studio
        options:
          model: text-embedding-bge-large-zh-v1.5
```

> VectorStore 自身的配置（如 Chroma 的 `client.base-url`）由所选 starter 的 `@ConfigurationProperties` 决定，请参考对应 starter 文档。

---

## 4. ETL 管道：离线索引

### 4.1 概念

```
原始文档（PDF/Word/HTML）
    ↓ Read（DocumentReader）
Document（统一格式）
    ↓ Transform（TokenTextSplitter 等）
List<Document>（分块后）
    ↓ Load（VectorStore.add）
向量库
```

### 4.2 完整索引代码

```java
@Service
public class IndexingService {

    private final VectorStore vectorStore;

    public IndexingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void indexMarkdown(Path file) {
        // 1. Read
        List<Document> docs = new TikaDocumentReader(file.toUri().toString()).get();

        // 2. Transform（分块）
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(docs);

        // 3. Load（向量化 + 入库）
        vectorStore.add(chunks);

        System.out.println("Indexed " + chunks.size() + " chunks");
    }
}
```

> 📌 `TokenTextSplitter` 在 1.0.0 推荐通过 `spring-ai-model` 中对应的 builder 配置参数（`chunkSize` / `minChunkSizeChars` / `overlap` 等仍然存在，但参数构造方式以当前 starter 版本 IDE 自动补全为准）。

### 4.3 ETL 组件详解

#### DocumentReader（来自 `spring-ai-tika-document-reader` / `spring-ai-pdf-document-reader` 等）
- `TikaDocumentReader` —— 万能（PDF/Word/HTML，基于 Apache Tika）
- `PagePdfDocumentReader` —— PDF 按页读（在 `spring-ai-pdf-document-reader`）
- `TextReader` / `JsonReader` —— 纯文本 / JSON（在 `spring-ai-document-reader`）

#### DocumentTransformer
- `TokenTextSplitter` —— 按 token 分块
- 自定义实现 `Function<List<Document>, List<Document>>` —— 清洗、摘要等

#### VectorStore.add
- 内部：调用 `EmbeddingModel.embed()` 批量向量化，再写入存储

---

## 5. 在线问答：用 RetrievalAugmentationAdvisor

### 5.1 装配

```java
import org.springframework.ai.rag.Advisor;
import org.springframework.ai.rag.retrieval.search.ContentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreContentRetriever;
import org.springframework.ai.rag.Advisors.RetrievalAugmentationAdvisor;

@Bean
ChatClient ragClient(ChatClient.Builder builder, VectorStore vectorStore) {

    // 1. 定义检索器：基于向量库
    ContentRetriever retriever = VectorStoreContentRetriever.builder()
            .vectorStore(vectorStore)
            .topK(5)
            .similarityThreshold(0.7)
            .build();

    // 2. 包装成 Advisor
    RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .build();

    return builder
            .defaultSystem("""
                你是基于公司文档的助手。
                严格根据下面的上下文回答问题。
                如果上下文中没有，回答"我不知道"。

                上下文：
                {question_answer_context}
                """)
            .defaultAdvisors(ragAdvisor)
            .build();
}
```

> 📌 **占位符**：`{question_answer_context}` 仍然是默认的占位符名称，由 `RetrievalAugmentationAdvisor` 内部替换。如果你自定义 `ContentFormatter`，可以改成任意占位符。

### 5.2 关键点

#### `VectorStoreContentRetriever`
- `topK` —— 返回 top-K 片段
- `similarityThreshold` —— 相似度阈值（小于该值的过滤掉）
- `filterExpression` —— 元数据过滤（如 `department == 'ops'`）

#### `RetrievalAugmentationAdvisor`
- 接收一个 `DocumentRetriever`（注意是 `DocumentRetriever`，不是 `ContentRetriever`；1.0.0 区分了"文档检索"和"内容检索"两层）
- 内部把检索结果格式化为文本，注入到 prompt
- 是 Advisor，所以可以和 Memory / 日志等自由组合

### 5.3 调用

```java
@GetMapping("/ask")
public String ask(@RequestParam String q) {
    return ragClient.prompt()
            .user(q)
            .call()
            .content();
}
```

### 5.4 实际发生的事

```
用户问："退货政策是几天？"
   ↓
RetrievalAugmentationAdvisor 拦截：
   1. 用 ContentRetriever 检索 topK=5 相关片段
   2. 拼成字符串，替换 system prompt 的 {question_answer_context}
   ↓
   System: 你是基于公司文档的助手...上下文：[7 天无理由退货...][商品需保持完好...]
   User: 退货政策是几天？
   ↓
   LLM 返回：7 天无理由退货
```

---

## 6. 元数据过滤（企业级 RAG 必备）

### 6.1 入库时打元数据

```java
Document doc = new Document("退货政策内容...",
        Map.of(
            "source", "产品手册.pdf",
            "page", "12",
            "department", "运营"
        ));

vectorStore.add(List.of(doc));
```

### 6.2 查询时过滤（直接用 VectorStore）

```java
import org.springframework.ai.vectorstore.SearchRequest;

SearchRequest request = SearchRequest.builder()
        .query("退货政策")
        .topK(5)
        .filterExpression("department == '运营' && page >= '10'")
        .build();

List<Document> results = vectorStore.similaritySearch(request);
```

### 6.3 过滤语法

- `key == 'value'`
- `key != 'value'`
- `key > 10` / `key < 10`
- `expr1 && expr2`
- `expr1 || expr2`
- `IN('a', 'b', 'c')`

### 6.4 通过 ContentRetriever 应用过滤

```java
ContentRetriever retriever = VectorStoreContentRetriever.builder()
        .vectorStore(vectorStore)
        .topK(5)
        .similarityThreshold(0.7)
        .filterExpression(new Expression("department == '运营'"))  // 动态过滤需要自定义 Advisor 注入
        .build();
```

> 多租户场景下"按用户动态过滤"，通常需要自己写一个 Advisor 在 before 阶段把 tenantId 注入到 `SearchRequest`。下面第 7 节给范例。

---

## 7. 自定义 RAG Advisor（动态注入上下文）

如果 `RetrievalAugmentationAdvisor` 不能满足，自己写一个 1.0.0 风格的 Advisor：

```java
public class CustomRagAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final int topK;

    public CustomRagAdvisor(VectorStore vectorStore, int topK) {
        this.vectorStore = vectorStore;
        this.topK = topK;
    }

    @Override
    public String getName() { return "CustomRagAdvisor"; }

    @Override
    public int getOrder() { return 0; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        // 1. 检索
        List<Document> docs = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(req.prompt().getUserMessage().getText())
                .topK(topK)
                .build());

        // 2. 拼接上下文
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        // 3. 修改 prompt（不可变 record，用 mutate）
        ChatClientRequest newReq = req.mutate()
                .prompt(req.prompt())  // 在此基础上追加 system message
                .build();

        // 更简单的做法：直接把 context 拼到 user message 后面
        String augmentedUserText = req.prompt().getUserMessage().getText()
                + "\n\n参考资料：\n" + context;
        UserMessage augmented = new UserMessage(augmentedUserText);

        ChatClientRequest augmentedReq = req.mutate()
                .prompt(Prompt.builder()
                        .withMessages(req.prompt().getInstructions().stream()
                                .filter(m -> !(m instanceof UserMessage))
                                .toList())
                        .withMessage(augmented)
                        .build())
                .build();

        // 4. 继续链
        return chain.nextCall(augmentedReq);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest req, StreamAdvisorChain chain) {
        // 同步逻辑可用，把 chain.nextCall 改成 chain.nextStream
        // 为简洁略，参考 adviseCall 实现
        return chain.nextStream(req);
    }
}
```

> 📌 `ChatClientRequest` 是 record（不可变），改 prompt 必须通过 `mutate().prompt(...).build()`。这是 1.0.0 和 0.x 的重大差异。

---

## 8. 混合检索（高阶）

### 8.1 用 Elasticsearch 一站式

引入 `spring-ai-starter-vector-store-elasticsearch`：

```yaml
spring:
  ai:
    vectorstore:
      elasticsearch:
        uris: http://localhost:9200
        index-name: product-manual
```

Elasticsearch 8+ 自带 `knn` 查询和 `rrf` 融合，**一个查询完成混合检索**。

### 8.2 Java 侧

```java
SearchRequest req = SearchRequest.builder()
        .query("退货政策")
        .topK(5)
        .build();
```

详细混合检索参考 `reference/理论基础/02-RAG深度优化.md`。

---

## 9. RAG 评估

### 9.1 推荐用 RAGAS / 第三方评估

Spring AI 1.0.0 没有内置的 `RelevanceAdvisor`（0.x 有），评估请用：

- **Ragas**（Python，标准做法）
- **DeepEval**
- **自建评估集 + LLM-as-a-Judge**

详见 `reference/生产化与运营/11-LLMOps.md`。

---

## 10. 常见错误

### 10.1 VectorStore 没数据

**症状**：检索结果为空。
**诊断**：`vectorStore.similaritySearch(SearchRequest.builder().query("test").topK(5).build())` 看返回。
**解决**：先跑索引脚本。

### 10.2 embedding 模型不支持中文

**症状**：中文检索效果差。
**解决**：换 `bge-m3` 或 `bge-small-zh`。

### 10.3 SimpleVectorStore 重启丢数据

**原因**：内存存储。
**解决**：换 Chroma / Redis / pgvector。

### 10.4 找不到 `QuestionAnswerAdvisor`

**症状**：IDE 红线、编译报错。
**原因**：`QuestionAnswerAdvisor` 是 0.x 类，1.0.0 已移除。
**解决**：用 `RetrievalAugmentationAdvisor`（本文第 5 节）。

### 10.5 找不到 `RetrievalAugmentationAdvisor`

**症状**：类找不到。
**原因**：1.0.0 的 RAG 入口在 `spring-ai-rag` 模块，不在主依赖。
**解决**：pom 加 `spring-ai-rag` + 一个 vector-store starter。

### 10.6 Chroma 容器挂了

**诊断**：`docker ps` 看是否在跑。
**解决**：`docker restart chroma`。

---

## 11. Spring AI vs LangChain4j：RAG 选型

| 维度 | LangChain4j | Spring AI |
|------|-------------|-----------|
| 学习曲线 | 平 | 陡（要懂 Advisor） |
| 灵活度 | 中 | 高（Advisor 可组合） |
| 元数据过滤 | 各向量库不同 API | **统一 API** |
| 与业务系统集成 | 手动 | 自动（Spring Bean） |
| 文档解析 | 各模块独立 | Tika 一站式 |
| 生产推荐 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

## 12. 理解检查

1. Spring AI 的 `VectorStore` 和 LangChain4j 的 `EmbeddingStore` 接口设计有何异同？
2. 1.0.0 里 `RetrievalAugmentationAdvisor` 内部做了哪几步？
3. 元数据过滤语法（如 `department == '运营'`）是怎么传到向量库的？
4. 想要在 RAG 之上再加一层缓存，应该怎么做？
5. `SimpleVectorStore` 为什么不适合生产？
6. 当前项目 pom 没引入 RAG 依赖时，本文代码能跑吗？（答：不能，先加依赖）

---

## 13. 练习任务

1. 加上 RAG 依赖后，用 SimpleVectorStore 跑通最小 RAG（10 条产品政策）
2. 换成 Chroma，对比差异
3. 实现元数据过滤：按部门搜文档
4. 写一个自定义 `RagAdvisor`，加日志看每次检索了多少片段
5. 用 ES 做混合检索（需要先起 ES）
6. 准备 10 条 QA 测试集，统计召回准确率
7. （进阶）实现多租户：每个租户独立 collection 或用元数据隔离

完成后进入 [05-结构化输出与 Prompt 模板](./05-结构化输出与Prompt模板.md)。
