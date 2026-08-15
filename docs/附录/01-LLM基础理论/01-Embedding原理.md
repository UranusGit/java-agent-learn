# Embedding 原理：向量表示与语义相似度

> 「本文是对 [教程 05-RAG §2-§3] 的深入展开」

> **定位**：系统讲解 Embedding（词嵌入/句嵌入）的数学原理——从 Word2Vec 到 Transformer Embedding、向量空间模型、余弦相似度、Spring AI 2.0 的 EmbeddingModel API，以及 RAG 场景中 Embedding 的工程实践。
>
> **读者画像**：有基本数学基础，想理解 RAG 检索"为什么能找到相关文档"背后原理的开发者。

---

## 1. Embedding 是什么

### 1.1 从离散到连续

计算机处理文本的第一步是将文字变成数字。最简单的方式是 One-Hot 编码：

```mermaid
graph TB
    subgraph OneHot["One-Hot 编码（问题很大）"]
        W1["猫 = [1,0,0,0,...,0]"]
        W2["狗 = [0,1,0,0,...,0]"]
        W3["汽车 = [0,0,1,0,...,0]"]
        NOTE["维度 = 词汇表大小（~10万）<br/>所有词之间的距离相同<br/>无法表达语义相似性"]
    end

    subgraph Embedding["Embedding 编码（语义连续）"]
        E1["猫 = [0.2, 0.8, -0.1, ...]"]
        E2["狗 = [0.3, 0.7, -0.2, ...]"]
        E3["汽车 = [-0.5, 0.1, 0.9, ...]"]
        NOTE2["维度：通常 768-3072<br/>猫和狗的向量很接近<br/>猫和汽车的向量很远"]
    end

    OneHot -->|"学习映射"| Embedding

    style OneHot fill:#ffcdd2
    style Embedding fill:#c8e6c9
```

### 1.2 Embedding 的核心思想

Embedding 的目标：**把离散的文本映射到连续的向量空间，使得语义相似的文本在向量空间中距离相近**。

```java
// 在 Spring AI 中，Embedding 就是一组浮点数
float[] embedding = embeddingModel.embed("Spring AI 是一个 AI 框架");
// embedding = [0.0234, -0.0871, 0.1543, ..., 0.0421]  // 通常 768 或 1536 维

// 语义相似的文本，向量也相似
float[] similar = embeddingModel.embed("Spring AI 是 Spring 官方的 AI 开发框架");
// similar 的值会非常接近 embedding

// 语义不相关的文本，向量差异大
float[] different = embeddingModel.embed("今天天气真好");
// different 的值与 embedding 差异很大
```

---

## 2. Embedding 的演化

### 2.1 Word2Vec（2013）

```mermaid
graph TB
    subgraph W2V["Word2Vec：词级别的 Embedding"]
        I["输入：单个词"]
        C["CBOW / Skip-gram 模型"]
        O["输出：词向量（~300维）"]
        EQ["经典等式：<br/>king - man + woman ≈ queen<br/>证明向量编码了语义关系"]
    end

    subgraph 局限["Word2Vec 的局限"]
        L1["一词一义：bank 只有一个向量<br/>但 bank 可以是「银行」或「河岸」"]
        L2["没有上下文：无法区分多义词"]
        L3["词级别，不是句子级别"]
    end

    W2V --> 局限

    style W2V fill:#e3f2fd
    style 局限 fill:#fff3e0
```

### 2.2 上下文 Embedding（BERT/GPT 时代）

Transformer 架构的革命性在于：**同一个词在不同上下文中产生不同的 Embedding**。

```java
// "bank" 在不同上下文中，Embedding 不同
float[] bankFinancial = embeddingModel.embed("I went to the bank to deposit money");
float[] bankRiver = embeddingModel.embed("I sat by the river bank");
// bankFinancial 和 bankRiver 是不同的向量
// 因为 Embedding 模型考虑了整个句子的上下文
```

### 2.3 现代 Embedding 模型

```mermaid
graph TB
    subgraph 模型对比["主流 Embedding 模型对比"]
        direction LR
        subgraph OpenAI["OpenAI"]
            O1["text-embedding-3-small<br/>1536 维"]
            O2["text-embedding-3-large<br/>3072 维"]
        end

        subgraph OS["开源模型"]
            S1["BAAI/bge-large-zh<br/>1024 维<br/>中文效果好"]
            S2["sentence-transformers<br/>多语言"]
            S3["nomic-embed-text<br/>768 维<br/>轻量级"]
        end

        subgraph 特征["关键特征"]
            F1["维度越高 = 表达能力越强"]
            F2["但存储和计算成本也越高"]
            F3["需要平衡效果和成本"]
        end
    end

    style 模型对比 fill:#e3f2fd
```

---

## 3. 向量空间与相似度

### 3.1 余弦相似度（Cosine Similarity）

Embedding 最常用的距离度量是余弦相似度——衡量两个向量方向的接近程度：

```java
public class CosineSimilarity {

    /**
     * 余弦相似度 = dot(a, b) / (|a| * |b|)
     * 范围：[-1, 1]
     * 1 = 方向完全一致（语义相同）
     * 0 = 正交（语义无关）
     * -1 = 方向相反（语义对立）
     */
    public static double cosine(float[] a, float[] b) {
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
```

```mermaid
graph LR
    subgraph 相似度可视化["余弦相似度示例"]
        Q["查询：如何使用 Spring AI"]
        D1["文档1：Spring AI 框架使用指南<br/>相似度：0.92<br/>（高度相关）"]
        D2["文档2：Spring Boot 入门教程<br/>相似度：0.71<br/>（部分相关）"]
        D3["文档3：Python Django 开发<br/>相似度：0.23<br/>（不相关）"]

        Q --> D1
        Q --> D2
        Q --> D3
    end

    style 相似度可视化 fill:#e3f2fd
```

### 3.2 其他距离度量

| 度量 | 公式 | 特点 | 适用场景 |
|------|------|------|---------|
| **余弦相似度** | dot/(|a|*|b|) | 只看方向 | 文本语义（最常用） |
| **欧几里得距离** | sqrt(Σ(ai-bi)^2) | 看绝对距离 | 图像、数值 |
| **点积** | Σ(ai*bi) | 简单快速 | 向量已归一化时等价于余弦 |
| **曼哈顿距离** | Σ|ai-bi| | 网格距离 | 稀疏向量 |

```java
// 不同距离度量的实现
public class VectorDistances {

    public static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    public static double dotProduct(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    // 如果向量已归一化（|v|=1），点积 = 余弦相似度
    // 大多数 Embedding 模型输出的向量已归一化
}
```

---

## 4. Spring AI 的 EmbeddingModel

### 4.1 基本用法

```java
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    // 单文本嵌入
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    // 批量嵌入（更高效）
    public List<float[]> embedBatch(List<String> texts) {
        return embeddingModel.embed(texts, EmbeddingOptionsBuilder.builder().build());
    }
}
```

### 4.2 与向量数据库结合

```java
@Service
public class DocumentIndexService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    // 索引文档
    public void indexDocuments(List<Document> documents) {
        // Spring AI 自动完成：Embedding + 存储
        vectorStore.add(documents);
    }

    // 语义搜索
    public List<Document> semanticSearch(String query, int topK) {
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.7)  // 相似度阈值
                .build()
        );
    }
}
```

### 4.3 Embedding 模型配置

```yaml
spring:
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-3-small    # 模型名
          dimensions: 1536                  # 维度（3-small 支持降维）
```

```java
// 使用不同的 Embedding 模型
@Bean
public EmbeddingModel embeddingModel(OpenAiConnectionProperties props) {
    // 可以配置多个 EmbeddingModel，根据场景选择
    return new OpenAiEmbeddingModel(
        openAiApi,
        MetadataMode.EMBED,
        OpenAiEmbeddingOptions.builder()
            .model("text-embedding-3-large")
            .dimensions(3072)  // 高维度 = 更精确
            .build()
    );
}
```

---

## 5. RAG 中的 Embedding 工程

### 5.1 分块（Chunking）策略

Embedding 是对"一段文本"的向量化。太长的文档需要先分块：

```mermaid
graph TB
    subgraph 分块["文档分块策略"]
        D["完整文档（10000 字）"]
        D --> C1["块1：200 字<br/>主题A"]
        D --> C2["块2：200 字<br/>主题B"]
        D --> C3["块3：200 字<br/>主题C"]
        D --> CN["..."]

        C1 --> E1["Embedding 1"]
        C2 --> E2["Embedding 2"]
        C3 --> E3["Embedding 3"]

        E1 --> VS["向量数据库"]
        E2 --> VS
        E3 --> VS
    end

    style 分块 fill:#e8f5e9
```

```java
@Service
public class DocumentChunkingService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public void indexDocument(Document document) {
        // Spring AI 内置的 TokenTextSplitter
        TokenTextSplitter splitter = new TokenTextSplitter(
            500,   // 每块最大 token 数
            100,   // 重叠区（保留上下文连贯性）
            10,    // 最小块大小
            5000,  // 最大块大小
            true
        );

        List<Document> chunks = splitter.split(document);

        // 为每个块添加元数据
        chunks.forEach(chunk -> {
            chunk.getMetadata().put("source", document.getMetadata().get("file_name"));
            chunk.getMetadata().put("chunk_index", chunks.indexOf(chunk));
            chunk.getMetadata().put("total_chunks", chunks.size());
        });

        // 批量嵌入并存储
        vectorStore.add(chunks);
    }
}
```

### 5.2 查询优化

```java
@Service
public class QueryOptimizationService {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    public List<Document> optimizedSearch(String userQuery) {
        // 1. 查询改写（Query Rewriting）
        String expandedQuery = chatClient.prompt()
            .system("""
                将用户问题改写为更适合检索的关键词形式。
                保持语义不变，使用更精确的术语。
                只输出改写后的问题。
                """)
            .user(userQuery)
            .call()
            .content();

        // 2. 用改写后的查询做向量搜索
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(expandedQuery)
                .topK(5)
                .similarityThreshold(0.75)
                .build()
        );
    }
}
```

---

## 6. Embedding 的维度与成本

### 6.1 维度选择

```mermaid
graph TB
    subgraph 维度["Embedding 维度选择决策"]
        Q{"检索精度要求？"}
        Q -->|"高（法律/医疗）"| HIGH["3072 维<br/>text-embedding-3-large<br/>存储大，检索精确"]
        Q -->|"中（通用知识库）"| MID["1536 维<br/>text-embedding-3-small<br/>性价比较好"]
        Q -->|"低（大规模粗筛）"| LOW["768 维<br/>bge-small / nomic<br/>速度快，成本低"]
    end

    style 维度 fill:#e3f2fd
```

### 6.2 成本计算

```java
// OpenAI Embedding 定价参考（2026年）
// text-embedding-3-small: $0.02 / 1M tokens
// text-embedding-3-large: $0.13 / 1M tokens

// 索引 100 万篇文档（每篇 ~500 tokens）的成本估算：
// small: 1,000,000 * 500 / 1,000,000 * $0.02 = $10
// large: 1,000,000 * 500 / 1,000,000 * $0.13 = $65
```

### 6.3 向量降维

OpenAI 的 text-embedding-3 系列支持原生降维——高维模型输出低维向量：

```java
@Bean
public EmbeddingModel embeddingModel() {
    return new OpenAiEmbeddingModel(
        openAiApi,
        MetadataMode.EMBED,
        OpenAiEmbeddingOptions.builder()
            .model("text-embedding-3-large")
            .dimensions(1024)  // 从 3072 降到 1024，减少存储
            .build()
    );
}
```

---

## 7. 向量索引与近似最近邻搜索

### 7.1 精确搜索 vs 近似搜索

当向量数据库中有百万级向量时，计算每个向量的余弦相似度太慢。实际系统使用**近似最近邻（ANN）搜索**：

```mermaid
graph TB
    subgraph 搜索方式["向量搜索方式对比"]
        subgraph 精确["精确搜索（暴力）"]
            EX1["计算查询向量与所有N个向量的相似度"]
            EX2["排序取 Top-K"]
            EX3["时间复杂度：O(N*D)"]
            EX4["适用：N < 10万"]
        end

        subgraph ANN["近似最近邻搜索"]
            AN1["HNSW：层次化可导航小世界图"]
            AN2["IVF：倒排文件索引"]
            AN3["PQ：乘积量化压缩"]
            AN4["时间复杂度：O(log(N)*D)"]
            AN5["适用：N > 100万"]
            AN6["代价：~95% 召回率（不是100%）"]
        end
    end

    style 精确 fill:#e3f2fd
    style ANN fill:#c8e6c9
```

### 7.2 Spring AI 向量数据库的选择

```java
// 不同向量数据库的 ANN 支持
// Pinecone: HNSW + PQ
// Milvus: HNSW / IVF / DiskANN
// Weaviate: HNSW
// pgvector: HNSW / IVFFlat
// Chroma: HNSW

// pgvector 示例：创建 HNSW 索引
// CREATE INDEX ON documents USING hnsw (embedding vector_cosine_ops)
// WITH (m = 16, ef_construction = 64);
```

---

## 8. Embedding 质量评估

### 8.1 检索质量指标

```java
public class RetrievalQualityEvaluator {

    record EvalSet(
        List<QueryDocPair> pairs  // 查询-相关文档对
    ) {}

    record QueryDocPair(
        String query,
        String relevantDocId,      // 已知相关的文档ID
        List<String> irrelevantDocIds  // 已知不相关的文档ID
    ) {}

    // 计算 Recall@K：前K个结果中包含正确文档的比例
    public double recallAtK(VectorStore store, EvalSet evalSet, int k) {
        int hits = 0;

        for (QueryDocPair pair : evalSet.pairs()) {
            List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                    .query(pair.query())
                    .topK(k)
                    .build()
            );

            boolean found = results.stream()
                .anyMatch(doc -> doc.getId().equals(pair.relevantDocId()));

            if (found) hits++;
        }

        return (double) hits / evalSet.pairs().size();
    }

    // 计算 MRR（Mean Reciprocal Rank）：正确文档的排名倒数的均值
    public double mrr(VectorStore store, EvalSet evalSet) {
        double sum = 0;

        for (QueryDocPair pair : evalSet.pairs()) {
            List<Document> results = store.similaritySearch(
                SearchRequest.builder()
                    .query(pair.query())
                    .topK(10)
                    .build()
            );

            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).getId().equals(pair.relevantDocId())) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }

        return sum / evalSet.pairs().size();
    }
}
```

---

## 9. 常见问题与最佳实践

### 9.1 跨语言 Embedding

```java
// 多语言 Embedding 模型：不同语言的相同含义文本，向量应该相似
float[] zh = embeddingModel.embed("人工智能");
float[] en = embeddingModel.embed("artificial intelligence");
// 好的多语言模型：cosine(zh, en) > 0.85

// 推荐模型：text-embedding-3-large（多语言）、BAAI/bge-m3
```

### 9.2 长文本处理

```java
// Embedding 模型有输入长度限制（通常 512-8192 tokens）
// 超长文本需要分块后分别 Embedding

// 方法1：平均池化（简单但不精确）
float[] embedLongDocument(String longText) {
    List<Document> chunks = splitter.split(Document.builder().text(longText).build());
    List<float[]> embeddings = chunks.stream()
        .map(doc -> embeddingModel.embed(doc.getText()))
        .toList();

    // 对所有块的向量取平均
    return averageVectors(embeddings);
}

// 方法2：max pooling
// 方法3：使用支持长文本的 Embedding 模型（如 Voyage AI）
```

### 9.3 向量归一化

```java
// 确保 Embedding 向量归一化（|v| = 1）
// 大多数现代 Embedding 模型已自动归一化
// 如果不确定，可以手动归一化
float[] normalize(float[] vector) {
    double norm = 0;
    for (float v : vector) norm += v * v;
    norm = Math.sqrt(norm);

    float[] normalized = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
        normalized[i] = (float) (vector[i] / norm);
    }
    return normalized;
}
```

---

## 10. 总结

Embedding 是 RAG 和语义搜索的数学基础。理解它的工作原理，才能在工程实践中做出正确的决策：

1. **本质**：把文本映射到连续向量空间，语义相似的文本向量距离近
2. **上下文感知**：现代 Embedding 模型（基于 Transformer）根据上下文生成不同的向量
3. **余弦相似度** 是最常用的语义相似度度量——衡量向量方向的接近程度
4. **分块策略**：长文档需要分块后分别 Embedding，块大小影响检索精度
5. **维度选择**：768（轻量）→ 1536（标准）→ 3072（高精度），权衡效果与成本
6. **近似搜索**：百万级向量需要 ANN 算法（HNSW/IVF），牺牲少量精度换取巨大速度提升
7. **质量评估**：用 Recall@K 和 MRR 量化检索质量

在教程 05（RAG 检索增强生成）中，`VectorStore.similaritySearch()` 底层就是 Embedding + ANN 搜索。理解 Embedding 原理，才能优化分块策略、选择合适维度、调试检索质量问题。
