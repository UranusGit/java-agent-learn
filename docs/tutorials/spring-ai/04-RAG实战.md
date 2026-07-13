# Spring AI 04 - RAG 实战

> 用 Spring AI 重做 RAG。重点对比 LangChain4j 的差异。
> 前置：已完成 [01-03]。

---

## 1. Spring AI 的 RAG 设计哲学

### 1.1 核心抽象

| Spring AI | LangChain4j | 作用 |
|-----------|-------------|------|
| `EmbeddingModel` | `EmbeddingModel` | 文本转向量 |
| `VectorStore` | `EmbeddingStore` | 向量存储与检索 |
| `ETL` 管道（`DocumentReader` 等） | `DocumentReader/Splitter` | 文档处理 |
| `QuestionAnswerAdvisor` | `ContentRetriever` | 把检索结果注入 prompt |

### 1.2 关键差异

**LangChain4j**：检索器（`ContentRetriever`）单独装配。

**Spring AI**：检索是 **Advisor** 的一种，能和其他 Advisor 自由组合（如 Memory、日志）。

---

## 2. pom.xml 依赖

```xml
<!-- RAG 核心 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-rag</artifactId>
</dependency>

<!-- 文档读取（PDF/Markdown/Word） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>

<!-- 向量库：三选一 -->

<!-- A. SimpleVectorStore：内存版，开发用 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-simple</artifactId>
</dependency>

<!-- B. Chroma -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-chroma-store-spring-boot-starter</artifactId>
</dependency>

<!-- C. Redis -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-redis-store-spring-boot-starter</artifactId>
</dependency>
```

**起步用 SimpleVectorStore**（内存），跑通后再换 Chroma。

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
    vectorstore:
      chroma:
        client:
          base-url: http://localhost:8000
        store:
          collection-name: product-manual
```

---

## 4. ETL 管道：离线索引

### 4.1 概念

```
Document (原始文档)
    ↓ Extract
TextDocument (统一格式)
    ↓ Transform (分块、清洗)
List<Document> (分块后)
    ↓ Load (入库)
VectorStore
```

### 4.2 完整索引代码

```java
@Service
public class IndexingService {

    private final VectorStore vectorStore;
    private final DocumentReader documentReader;
    private final DocumentTransformer documentTransformer;

    public IndexingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void indexMarkdown(Path file) {
        // 1. Read
        Document doc = new TikaDocumentReader(file.toUri().toString()).get().get(0);

        // 2. Transform (分块)
        DocumentTransformer splitter = TokenTextSplitter.builder()
                .chunkSize(300)
                .minChunkSizeChars(50)
                .overlap(30)
                .build();
        List<Document> chunks = splitter.apply(List.of(doc));

        // 3. Load (向量化 + 入库)
        vectorStore.add(chunks);

        System.out.println("Indexed " + chunks.size() + " chunks");
    }
}
```

### 4.3 ETL 组件详解

#### DocumentReader
- `TikaDocumentReader` —— 万能（PDF/Word/HTML，基于 Apache Tika）
- `TextReader` —— 纯文本
- `JsonReader` —— JSON
- `PagePdfDocumentReader` —— PDF 按页读

#### DocumentTransformer
- `TokenTextSplitter` —— 按 token 分块
- 自定义实现 `DocumentTransformer` —— 如清洗、摘要

#### VectorStore.add
- 内部：调用 `EmbeddingModel.embed()` 批量向量化，再写入存储

---

## 5. 在线问答：用 QuestionAnswerAdvisor

### 5.1 装配

```java
@Bean
ChatClient ragClient(ChatClient.Builder builder, VectorStore vectorStore) {
    return builder
            .defaultSystem("""
                你是基于公司文档的助手。
                严格根据下面的上下文回答问题。
                如果上下文中没有，回答"我不知道"。
                
                上下文：
                {question_answer_context}
                """)
            .defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(
                        SearchRequest.builder()
                            .topK(5)
                            .similarityThreshold(0.7)
                            .build())
                    .build()
            )
            .build();
}
```

### 5.2 关键点

#### `{question_answer_context}`
- 占位符，Advisor 会自动用检索到的片段替换
- 必须出现在 system prompt 里

#### `SearchRequest`
- `topK` —— 返回 top-K 片段
- `similarityThreshold` —— 相似度阈值（小于该值的过滤掉）
- `filterExpression` —— 元数据过滤（如 `department == 'ops'`）

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
QuestionAnswerAdvisor 拦截：
   1. vectorStore.similaritySearch("退货政策是几天？", topK=5)
   2. 拿到 5 个片段
   3. 拼成字符串，替换 system prompt 的 {question_answer_context}
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
Document doc = new Document("退货政策内容...");
doc.getMetadata().put("source", "产品手册.pdf");
doc.getMetadata().put("page", "12");
doc.getMetadata().put("department", "运营");

vectorStore.add(List.of(doc));
```

### 6.2 查询时过滤

```java
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

### 6.4 实战：多租户知识库

```java
@GetMapping("/ask")
public String ask(@RequestParam String q, @RequestParam String tenantId) {
    return client.prompt()
            .user(q)
            .toolContext(Map.of("tenantId", tenantId))
            // 用 Advisor 配合元数据过滤（自定义 Advisor）
            .call()
            .content();
}
```

---

## 7. 自定义 Retriever Advisor

如果 `QuestionAnswerAdvisor` 不能满足，自己写。

```java
public class CustomRagAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final int topK;

    @Override
    public String getName() { return "CustomRagAdvisor"; }

    @Override
    public int getOrder() { return 0; }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest req, CallAdvisorChain chain) {
        // 1. 检索
        List<Document> docs = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(req.userText())
                .topK(topK)
                .build());

        // 2. 拼接上下文
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        // 3. 修改 system prompt
        AdvisedRequest newReq = AdvisedRequest.from(req)
                .withSystemText(req.systemText() + "\n\n上下文：\n" + context)
                .build();

        // 4. 继续链
        return chain.nextAroundCall(newReq);
    }
}
```

---

## 8. 混合检索（高阶）

### 8.1 用 Elasticsearch 一站式

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
        // ES 特有：原生查询
        .build();
```

详细混合检索参考 `reference/理论基础/02-RAG深度优化.md`。

---

## 9. RAG 评估

### 9.1 Spring AI 内置评估（基础）

```java
RelevanceAdvisor evaluator = RelevanceAdvisor.builder()
        .chatClientBuilder(clientBuilder)
        .build();
```

### 9.2 推荐用 RAGAS（更强）

详见 `reference/生产化与运营/11-LLMOps.md`。

---

## 10. 常见错误

### 10.1 VectorStore 没数据

**症状**：检索结果为空。
**诊断**：`vectorStore.similaritySearch("test", 5)` 看返回。
**解决**：先跑索引脚本。

### 10.2 embedding 模型不支持中文

**症状**：中文检索效果差。
**解决**：换 `bge-m3` 或 `bge-small-zh`。

### 10.3 SimpleVectorStore 重启丢数据

**原因**：内存存储。
**解决**：换 Chroma / Redis / pgvector。

### 10.4 占位符 `{question_answer_context}` 没被替换

**原因**：system prompt 里没写，或写错位置。
**解决**：必须在 `defaultSystem(...)` 文本里出现。

### 10.5 Chroma 容器挂了

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
2. `QuestionAnswerAdvisor` 的 `{question_answer_context}` 占位符为什么必须出现在 system prompt？
3. 元数据过滤语法（如 `department == '运营'`）是怎么传到向量库的？
4. 想要在 RAG 之上再加一层缓存，应该怎么做？
5. `SimpleVectorStore` 为什么不适合生产？

---

## 13. 练习任务

1. 用 SimpleVectorStore 跑通最小 RAG（10 条产品政策）
2. 换成 Chroma，对比差异
3. 实现元数据过滤：按部门搜文档
4. 写一个自定义 `RagAdvisor`，加日志看每次检索了多少片段
5. 用 ES 做混合检索（需要先起 ES）
6. 准备 10 条 QA 测试集，统计召回准确率
7. （进阶）实现多租户：每个租户独立 collection 或用元数据隔离

完成后进入 [05-结构化输出与 Prompt 模板](./05-结构化输出与Prompt模板.md)。
