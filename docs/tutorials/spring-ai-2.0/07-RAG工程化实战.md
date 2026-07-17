# L3 工程化实战 - RAG 工程化（Spring AI 2.0）

> 本文不是"5 分钟跑通 RAG"，而是**企业级 RAG 系统从 0 到生产**的完整工程化路径。
> 学完你能：自写 Retriever / 设计混合检索 / Rerank / 引用溯源 / 搭评估闭环 / 量化对比不同 RAG 策略的指标。
>
> **代码作参考答案**：所有代码块都需要你**手动**在 `org.demo02.rag.*` 下实现，不要直接复制。
> **调研日期**：2026-07-17
> **依赖**：Spring Boot 4.x、Spring AI 2.0.0 GA、JDK 21

---

## 0. 本文的认知地图

```
L1 基础：知道 RetrievalAugmentationAdvisor 是什么（你在 L1-L10 已学过）
   ↓
L2 工程化（本文）：
   1. RAG 系统的 6 个工程模块（不止 Retriever）
   2. 自写 Retriever（不依赖框架的现成实现）
   3. 混合检索（BM25 + 向量）+ Rerank
   4. 引用溯源（让 LLM 说"根据 [Doc3] 我认为是..."）
   5. 评估闭环（Faithfulness / Relevance / Context Recall）
   6. 量化对比（不同 chunk size / topK / 模型的指标对比）
   ↓
L3 进阶：Embedding 微调、自建向量索引、ColBERT/late-interaction（本文不讲，留给阶段四之后）
```

---

## 1. RAG 系统的 6 个工程模块（必背）

很多人把 RAG 等同于"向量检索"，这是 Naive RAG 的认知。**企业级 RAG 是 6 个模块的协同**：

| 模块 | 职责 | 失败表现 |
|------|------|---------|
| **1. Ingestion（数据摄入）** | 把原始文档（PDF/HTML/Confluence）切成 chunk，加 metadata | 切分不当 → 检索精度差 |
| **2. Embedding（向量化）** | 把 chunk 转成向量存入向量库 | 模型选错 → 语义召回低 |
| **3. Query Processing（查询处理）** | 改写/扩展/路由用户原始 query | 用户问得差 → 召回不到 |
| **4. Retrieval（检索）** | 向量检索 + BM25 + 元数据过滤 | 召回不全 / 太多噪音 |
| **5. Rerank & Filter（重排过滤）** | 用 cross-encoder 重排，剔除冗余 | topK 都不相关 |
| **6. Augmentation & Generation（增强生成）** | 拼 prompt + 引用溯源 + 让 LLM 生成 | LLM 幻觉 / 不引用 |

**Spring AI 2.0 的对应 API**：

```
spring-ai-rag 模块：
  RetrievalAugmentationAdvisor           ← 总装（advisor 链的一环）
    ├── queryTransformers                ← 模块 3（Query 改写）
    │    └── RewriteQueryTransformer     ← 用 LLM 改写 query
    ├── documentRetriever                ← 模块 4（检索）
    │    └── VectorStoreDocumentRetriever
    ├── documentPostProcessors           ← 模块 5（重排过滤）
    │    └── （自己实现，框架没提供）
    └── queryAugmenter                   ← 模块 6（拼 prompt）
         └── ContextualQueryAugmenter

spring-ai-vector-store-advisor 模块：
  QuestionAnswerAdvisor                   ← 简化版（模块 4+6 合一）
  VectorStoreChatMemoryAdvisor            ← 用向量库做长期记忆
```

**关键决策**：
- 简单 RAG → 用 `QuestionAnswerAdvisor`（5 行代码搞定）
- 进阶 RAG → 用 `RetrievalAugmentationAdvisor`（可定制每个模块）
- 企业 RAG → **基于 `RetrievalAugmentationAdvisor`，但 Retriever / PostProcessor 自己实现**

---

## 2. 项目结构与依赖

### 2.1 pom.xml 新增依赖

```xml
<!-- Spring AI 2.0 RAG 核心（RetrievalAugmentationAdvisor） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-rag</artifactId>
</dependency>

<!-- 简化版 RAG advisor（可选，本文用 RetrievalAugmentationAdvisor 不需要） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-advisor</artifactId>
</dependency>

<!-- 向量库：本文用 PgVector（生产首选），开发期可用 SimpleVectorStore（内存） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>

<!-- 文档解析：PDF -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
</dependency>

<!-- PostgreSQL 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### 2.2 推荐的包结构（你手动创建）

```
org.demo02.rag/
├── config/
│   ├── RagConfig.java                 # ChatClient + RAG advisor 配置
│   └── VectorStoreConfig.java         # 向量库 bean
├── ingestion/
│   ├── DocumentIngestionService.java  # ETL 入口
│   ├── SmartDocumentSplitter.java     # 自写切分器（按结构 + 大小）
│   └── MetadataEnricher.java          # 加 metadata（来源/章节/页码）
├── retrieval/
│   ├── HybridDocumentRetriever.java   # 自写混合检索（BM25 + 向量）
│   ├── RerankDocumentPostProcessor.java  # 自写 rerank
│   └── DeduplicationPostProcessor.java   # 去重
├── augmentation/
│   ├── CitationQueryAugmenter.java    # 引用溯源 prompt 拼接
│   └── CitationParser.java            # 从 LLM 输出解析 [Doc3] 引用
├── eval/
│   ├── RagEvaluationService.java      # 评估管道
│   └── RagMetrics.java                # 指标（Faithfulness/Relevance/...）
└── controller/
    ├── IngestionController.java       # 摄入接口
    ├── RagController.java             # 问答接口
    └── EvaluationController.java      # 评估触发接口
```

---

## 3. 模块 1-2：Ingestion + Embedding

### 3.1 切分策略对效果的影响（必懂）

切分是 RAG 系统中**对效果影响最大、最容易被忽视**的环节。同样的文档、同样的模型，不同切分策略的 Recall@5 差距能到 30%+。

| 策略 | 实现 | 优点 | 缺点 | 适用 |
|------|------|------|------|------|
| 固定字符切分 | 每 1000 字一刀 | 简单 | 切断句子/段落 | demo |
| 固定 token 切分 | 每 500 token 一刀 | 适配模型限制 | 同上 | demo |
| **递归字符切分** | 优先按段落→句子→字符递归 | 保持语义边界 | 实现稍复杂 | **推荐默认** |
| 按文档结构切分 | 按 Markdown 标题/HTML 标签 | 保持文档语义 | 依赖文档格式 | 结构化文档 |
| 语义切分 | 用 Embedding 找语义断点 | 最高质量 | 慢、贵 | 高质量场景 |
| **Agentic Chunking** | LLM 决定切分点 | 最聪明 | 贵、不可控 | 实验中 |

**实战建议**：默认用递归字符切分 + overlap，特殊文档（PDF 论文、Markdown 文档）用结构切分。

### 3.2 自写递归字符切分器

Spring AI 自带 `TokenTextSplitter`，但它只支持固定 token 切分。我们要自写一个递归版本。

```java
// org/demo02/rag/ingestion/SmartDocumentSplitter.java
// 本代码仅作学习材料参考，需要你手动在 org.demo02.rag.* 下实现
package org.demo02.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class SmartDocumentSplitter {

    // 切分的层级分隔符：先按段落，段落太大就按句子，再大就按字符
    private static final List<String> SEPARATORS = Arrays.asList(
            "\n\n\n",  // 大段落
            "\n\n",    // 段落
            "\n",      // 行
            "。",      // 中文句号
            ". ",      // 英文句号
            "；",      // 中文分号
            "; ",      // 英文分号
            "，",      // 中文逗号
            ", ",      // 英文逗号
            " ",       // 空格
            ""         // 最后退化为字符
    );

    public List<Document> split(List<Document> docs, int targetSize, int overlap) {
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            result.addAll(splitSingle(doc, targetSize, overlap, 0));
        }
        return result;
    }

    private List<Document> splitSingle(Document doc, int targetSize, int overlap, int sepIdx) {
        String text = doc.getText();
        if (text.length() <= targetSize) {
            return List.of(doc);
        }
        if (sepIdx >= SEPARATORS.size()) {
            // 退化为字符切分（带 overlap）
            return chunkByChar(text, targetSize, overlap, doc);
        }

        String sep = SEPARATORS.get(sepIdx);
        String[] parts = text.split(java.util.regex.Pattern.quote(sep), -1);

        List<Document> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (current.length() + part.length() + sep.length() <= targetSize) {
                if (!current.isEmpty()) current.append(sep);
                current.append(part);
            } else {
                if (current.length() > 0) {
                    chunks.add(cloneWith(doc, current.toString()));
                    // overlap：保留尾部
                    String tail = current.substring(Math.max(0, current.length() - overlap));
                    current = new StringBuilder(tail).append(sep).append(part);
                } else {
                    // 单个 part 就超长，往下一层 separator 切
                    chunks.addAll(splitSingle(cloneWith(doc, part), targetSize, overlap, sepIdx + 1));
                    current = new StringBuilder();
                }
            }
        }
        if (current.length() > 0) {
            chunks.add(cloneWith(doc, current.toString()));
        }
        return chunks;
    }

    private List<Document> chunkByChar(String text, int size, int overlap, Document doc) {
        List<Document> chunks = new ArrayList<>();
        int step = size - overlap;
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(i + size, text.length());
            chunks.add(cloneWith(doc, text.substring(i, end)));
            if (end == text.length()) break;
        }
        return chunks;
    }

    private Document cloneWith(Document origin, String text) {
        // 保留 metadata，新增 chunk 信息
        var meta = new java.util.HashMap<>(origin.getMetadata());
        meta.put("chunk_length", text.length());
        return Document.builder().text(text).metadata(meta).build();
    }
}
```

### 3.3 加 Metadata（企业级 RAG 必备）

Metadata 是后续做 **过滤检索** 的关键。比如"只在 2024 年的合同里搜"、"只搜 HR 部门的文档"。

```java
// org/demo02/rag/ingestion/MetadataEnricher.java
package org.demo02.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class MetadataEnricher {

    public List<Document> enrich(List<Document> chunks, String source, String department) {
        return chunks.stream().map(doc -> {
            var meta = new java.util.HashMap<>(doc.getMetadata());
            meta.put("source", source);
            meta.put("department", department);
            meta.put("ingested_at", LocalDateTime.now().toString());
            meta.put("version", "v1");
            return Document.builder()
                    .text(doc.getText())
                    .metadata(meta)
                    .build();
        }).toList();
    }
}
```

### 3.4 ETL 入口

```java
// org/demo02/rag/ingestion/DocumentIngestionService.java
package org.demo02.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final SmartDocumentSplitter splitter;
    private final MetadataEnricher enricher;

    public DocumentIngestionService(VectorStore vectorStore,
                                     SmartDocumentSplitter splitter,
                                     MetadataEnricher enricher) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
        this.enricher = enricher;
    }

    public int ingestPdf(Resource pdf, String source, String department) {
        // 1. 读 PDF（按页）
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdf);
        List<Document> pages = reader.get();

        // 2. 切分（target 800 字符，overlap 150）
        List<Document> chunks = splitter.split(pages, 800, 150);

        // 3. 加 metadata
        List<Document> enriched = enricher.enrich(chunks, source, department);

        // 4. 向量化 + 入库（VectorStore 内部会调 EmbeddingModel）
        vectorStore.add(enriched);

        return enriched.size();
    }
}
```

---

## 4. 模块 3：Query Processing（查询改写）

用户问"上次那个 bug 怎么解决的"——这种 query 直接 embedding 召回质量极差。需要先用 LLM 改写。

Spring AI 提供了 `RewriteQueryTransformer`，直接用：

```java
// 在 RagConfig 里装配
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(ChatClient.Builder builder) {
    return RewriteQueryTransformer.builder()
            .chatClientBuilder(builder.build().mutate())
            .build();
}
```

但更进阶的做法是**自写"多查询改写"**：让 LLM 把一个 query 改成 5 个不同视角的子 query，每个都去检索，结果合并去重。这是 RAG-Fusion 的核心思想。

```java
// org/demo02/rag/retrieval/MultiQueryTransformer.java
// 本代码仅作学习材料参考
package org.demo02.rag.retrieval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrie.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MultiQueryTransformer implements QueryTransformer {

    private final ChatClient chatClient;

    public MultiQueryTransformer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Query transform(Query query) {
        // 多查询返回换行分隔的多个改写 query
        String prompt = """
                你是查询改写专家。请把下面的用户问题改写成 3 个不同视角的等价问题，
                每个 per line，不要编号、不要解释：
                
                原问题：%s
                
                改写要求：
                1. 保持原意
                2. 用不同的关键词/视角
                3. 适合向量检索
                """.formatted(query.text());

        String rewritten = chatClient.prompt().user(prompt).call().content();
        // 用换行拼回，下游 retriever 自己 split
        return Query.builder()
                .text(rewritten.trim())
                .build();
    }
}
```

> 注意：`MultiQueryTransformer` 是本文设计的简化版，真正 RAG-Fusion 需要在 retriever 层做并行检索 + RRF 融合。完整实现见 §5.3。

---

## 5. 模块 4：Retrieval（混合检索）

### 5.1 为什么单纯向量检索不够

| 场景 | 向量检索表现 | BM25 表现 |
|------|------------|----------|
| "Spring AI 怎么用" | ✅ 好 | ❌ 太通用，召回大量噪音 |
| "包含 `getConversationId` 的代码" | ❌ 不知道这是代码 | ✅ 精确匹配关键词 |
| "怎么处理 ABC-1234 这个 bug" | ❌ 编号无语义 | ✅ 精确 |
| "RAG 系统的常见问题" | ✅ 好 | ⚠️ 一般 |

**结论**：向量检索强在"语义"、BM25 强在"关键词精确匹配"，**两者融合效果最好**。这是企业级 RAG 的标配。

### 5.2 PgVector 全文检索 + 向量检索

PgVector 0.7+ 支持同时做向量检索和全文检索（pg_trgm + ts_vector），是一个简单的混合检索方案。

```sql
-- 表结构由 Spring AI 自动创建，但你需要确保 PostgreSQL 装了 pg_trgm 扩展
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_doc_text_trgm ON vector_store USING gin (content gin_trgm_ops);
```

### 5.3 自写 Hybrid Retriever（核心）

实现 `DocumentRetriever` 接口，内部并行调向量检索 + BM25 模拟，用 RRF（Reciprocal Rank Fusion）融合结果。

```java
// org/demo02/rag/retrieval/HybridDocumentRetriever.java
// 本代码仅作学习材料参考
package org.demo02.rag.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrie.query.retrieval.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class HybridDocumentRetriever implements DocumentRetriever {

    private static final double RRF_K = 60.0; // RRF 常数

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final int topK;
    private final double vectorWeight;
    private final double bm25Weight;

    public HybridDocumentRetriever(VectorStore vectorStore, JdbcTemplate jdbc,
                                    int topK, double vectorWeight, double bm25Weight) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.topK = topK;
        this.vectorWeight = vectorWeight;
        this.bm25Weight = bm25Weight;
    }

    @Override
    public List<Document> retrieve(Query query) {
        // 1. 向量检索 top topK*2 个（取多冗余，后面融合会缩到 topK）
        List<Document> vecDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query.text()).topK(topK * 2).build()
        );

        // 2. BM25 模拟（用 pg_trgm 相似度，简化版；生产可用 ES 或 OpenSearch）
        List<Document> bm25Docs = bm25Search(query.text(), topK * 2);

        // 3. RRF 融合
        return rrfFuse(vecDocs, bm25Docs, topK);
    }

    private List<Document> bm25Search(String query, int limit) {
        // 简化：用 PostgreSQL 的 pg_trgm 相似度排序（不是真 BM25 但够用）
        String sql = """
                SELECT id, content, metadata,
                       similarity(content, ?) AS score
                FROM vector_store
                WHERE content ILIKE ?
                ORDER BY score DESC
                LIMIT ?
                """;
        return jdbc.query(sql,
                new Object[]{"%" + query + "%", "%" + query + "%", limit},
                (rs, i) -> {
                    Map<String, Object> meta = parseMeta(rs.getString("metadata"));
                    return Document.builder()
                            .id(rs.getString("id"))
                            .text(rs.getString("content"))
                            .metadata(meta)
                            .build();
                });
    }

    private List<Document> rrfFuse(List<Document> vecDocs, List<Document> bm25Docs, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docIndex = new HashMap<>();

        // RRF 公式：score = sum(1 / (k + rank_in_each_list))
        for (int i = 0; i < vecDocs.size(); i++) {
            String id = vecDocs.get(i).getId();
            scores.merge(id, vectorWeight * (1.0 / (RRF_K + i + 1)), Double::sum);
            docIndex.putIfAbsent(id, vecDocs.get(i));
        }
        for (int i = 0; i < bm25Docs.size(); i++) {
            String id = bm25Docs.get(i).getId();
            scores.merge(id, bm25Weight * (1.0 / (RRF_K + i + 1)), Double::sum);
            docIndex.putIfAbsent(id, bm25Docs.get(i));
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docIndex.get(e.getKey()))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMeta(String json) {
        // 简化：用 Jackson 解析；实际项目注入 ObjectMapper
        return new HashMap<>();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private VectorStore vectorStore;
        private JdbcTemplate jdbc;
        private int topK = 5;
        private double vectorWeight = 0.7;
        private double bm25Weight = 0.3;

        public Builder vectorStore(VectorStore vs) { this.vectorStore = vs; return this; }
        public Builder jdbcTemplate(JdbcTemplate jdbc) { this.jdbc = jdbc; return this; }
        public Builder topK(int k) { this.topK = k; return this; }
        public Builder vectorWeight(double w) { this.vectorWeight = w; return this; }
        public Builder bm25Weight(double w) { this.bm25Weight = w; return this; }

        public HybridDocumentRetriever build() {
            return new HybridDocumentRetriever(vectorStore, jdbc, topK, vectorWeight, bm25Weight);
        }
    }
}
```

---

## 6. 模块 5：Rerank 与去重

### 6.1 为什么向量检索后还要 rerank

向量检索用的是 **bi-encoder**（query 和 doc 各自编码再算相似度），快但精度有限。
Rerank 用的是 **cross-encoder**（query 和 doc 拼一起过同一个模型），慢但精度高得多。

典型流程：
```
向量检索 topK=20（快、召回全）
   ↓
Rerank topK=5（慢、但精度高）
   ↓
最终给 LLM 的 5 个 doc 都是高相关
```

### 6.2 自写 Rerank PostProcessor

```java
// org/demo02/rag/retrieval/RerankDocumentPostProcessor.java
// 本代码仅作学习材料参考
package org.demo02.rag.retrieval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private final ChatClient chatClient;
    private final int topN;

    public RerankDocumentPostProcessor(ChatClient chatClient) {
        this(chatClient, 5);
    }

    public RerankDocumentPostProcessor(ChatClient chatClient, int topN) {
        this.chatClient = chatClient;
        this.topN = topN;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) return documents;

        // 让 LLM 打分（生产可换成本地 cross-encoder 模型，更便宜）
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query.text()).append("\n\n");
        sb.append("请给下面每段文档的相关性打分（0-10），格式：DOC_<id>=<score>\n\n");
        for (int i = 0; i < documents.size(); i++) {
            sb.append("DOC_").append(i).append("：")
              .append(documents.get(i).getText(), 0, Math.min(200, documents.get(i).getText().length()))
              .append("\n\n");
        }

        String scores = chatClient.prompt().user(sb.toString()).call().content();
        Map<Integer, Double> scoreMap = parseScores(scores);

        return documents.stream()
                .sorted((a, b) -> {
                    int ia = documents.indexOf(a);
                    int ib = documents.indexOf(b);
                    return Double.compare(
                            scoreMap.getOrDefault(ib, 0.0),
                            scoreMap.getOrDefault(ia, 0.0)
                    );
                })
                .limit(topN)
                .collect(Collectors.toList());
    }

    private Map<Integer, Double> parseScores(String text) {
        Map<Integer, Double> map = new HashMap<>();
        if (text == null) return map;
        for (String line : text.split("\n")) {
            // 匹配 DOC_3=8.5
            if (line.matches(".*DOC_(\\d+)\\s*[=:]\\s*([\\d.]+).*")) {
                var m = java.util.regex.Pattern
                        .compile(".*DOC_(\\d+)\\s*[=:]\\s*([\\d.]+).*")
                        .matcher(line);
                if (m.find()) {
                    map.put(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)));
                }
            }
        }
        return map;
    }
}
```

### 6.3 去重 PostProcessor

```java
// org/demo02/rag/retrieval/DeduplicationPostProcessor.java
package org.demo02.rag.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DeduplicationPostProcessor implements DocumentPostProcessor {

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        Set<String> seen = new HashSet<>();
        return documents.stream()
                .filter(d -> {
                    String key = dedupKey(d);
                    return seen.add(key);
                })
                .collect(Collectors.toList());
    }

    private String dedupKey(Document d) {
        // 简单策略：前 100 字符一致视为重复
        return d.getText().substring(0, Math.min(100, d.getText().length()));
    }
}
```

---

## 7. 模块 6：引用溯源

### 7.1 为什么需要引用溯源

无引用：`LLM：根据知识库，Spring AI 2.0 已发布。` → 用户不知道哪条文档说的，**无法信任**。

有引用：`LLM：根据 [Doc3]，Spring AI 2.0 于 2026-06-12 发布。` → 用户可点击 [Doc3] 验证。

引用溯源是 RAG 从"demo"到"产品"的分水岭。

### 7.2 自写带引用的 QueryAugmenter

```java
// org/demo02/rag/augmentation/CitationQueryAugmenter.java
// 本代码仅作学习材料参考
package org.demo02.rag.augmentation;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CitationQueryAugmenter implements QueryAugmenter {

    @Override
    public Query augment(Query originalQuery, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return originalQuery;
        }

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            ctx.append(String.format("[Doc%d] (来源: %s)\n%s\n\n",
                    i + 1,
                    d.getMetadata().getOrDefault("source", "unknown"),
                    d.getText()));
        }

        String instructions = """
                你是一个严谨的助手。回答必须基于下方提供的"上下文文档"，并遵守：
                
                1. 引用任何信息都要在句末加 [DocN]（N 是文档编号）
                2. 多个文档支撑同一观点时，写 [Doc1][Doc3]
                3. 上下文里没有的信息，必须回答"我不确定"，不得编造
                4. 用中文回答
                
                上下文文档：
                ---------------------
                %s
                ---------------------
                """.formatted(ctx);

        // 把 instructions 注入到 system 消息（保留原 user query 不动）
        return Query.builder()
                .text(originalQuery.text())
                .instructions(instructions)  // Spring AI 2.0 Query 支持 instructions
                .build();
    }
}
```

### 7.3 总装 RetrievalAugmentationAdvisor

```java
// org/demo02/rag/config/RagConfig.java
// 本代码仅作学习材料参考
package org.demo02.rag.config;

import org.demo02.rag.retrieval.*;
import org.demo02.rag.augmentation.CitationQueryAugmenter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class RagConfig {

    @Bean
    public RetrievalAugmentationAdvisor ragAdvisor(
            VectorStore vectorStore,
            JdbcTemplate jdbc,
            ChatClient.Builder chatClientBuilder,
            RerankDocumentPostProcessor rerank,
            DeduplicationPostProcessor dedup,
            CitationQueryAugmenter augmenter) {

        ChatClient chatClient = chatClientBuilder.build();

        return RetrievalAugmentationAdvisor.builder()
                // 模块 3：查询改写（用框架自带的）
                .queryTransformers(org.springframework.ai.rag.preretrie.query.transformation.RewriteQueryTransformer
                        .builder().chatClientBuilder(chatClient.mutate()).build())
                // 模块 4：混合检索
                .documentRetriever(HybridDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .jdbcTemplate(jdbc)
                        .topK(20)
                        .vectorWeight(0.7)
                        .bm25Weight(0.3)
                        .build())
                // 模块 5：去重 + rerank
                .documentPostProcessors(dedup, rerank)
                // 模块 6：引用溯源拼 prompt
                .queryAugmenter(augmenter)
                .build();
    }
}
```

### 7.4 问答 Controller

```java
// org/demo02/rag/controller/RagController.java
package org.demo02.rag.controller;

import org.demo02.rag.augmentation.CitationParser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final RetrievalAugmentationAdvisor ragAdvisor;
    private final CitationParser citationParser;

    public RagController(ChatClient chatClient,
                          RetrievalAugmentationAdvisor ragAdvisor,
                          CitationParser citationParser) {
        this.chatClient = chatClient;
        this.ragAdvisor = ragAdvisor;
        this.citationParser = citationParser;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestParam String q) {
        long start = System.currentTimeMillis();
        String answer = chatClient.prompt()
                .advisors(ragAdvisor)
                .user(q)
                .call()
                .content();
        long elapsed = System.currentTimeMillis() - start;

        return Map.of(
                "answer", answer,
                "citations", citationParser.parse(answer),
                "elapsed_ms", elapsed
        );
    }
}
```

`CitationParser` 用正则提取 `[DocN]` 序号，映射回具体文档 ID（需要从 advisor context 里拿到 retriever 返回的 doc 列表，这部分留给你实现，提示：用 `ChatClientResponse` 的 `context()` 取 advisor 上下文）。

---

## 8. 模块 7：评估闭环（RAG 的"测试金字塔"）

### 8.1 评估指标的三大维度

| 指标 | 含义 | 怎么测 |
|------|------|-------|
| **Context Precision** | 检索回来的文档，多少真的相关 | LLM-as-Judge 评估每个 doc 是否相关 |
| **Context Recall** | 应该被检索到的文档，实际召回多少 | 需要 ground truth（人工标注的"这个问题的相关文档"）|
| **Faithfulness（忠实度）** | LLM 回答是否**只**基于检索的 context | 把 answer 拆 claims，每个 claim 是否被 context 支持 |
| **Answer Relevancy** | 回答是否切题 | 用 LLM 反推"这个回答可能对应什么问题"，再和原 query 算相似度 |
| **Citation Accuracy** | 引用是否准确（本文特有） | 引用的 [DocN] 是否真的支持那句话 |

### 8.2 用 LLM-as-Judge 实现 Faithfulness

```java
// org/demo02/rag/eval/RagEvaluationService.java
// 本代码仅作学习材料参考
package org.demo02.rag.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RagEvaluationService {

    private final ChatClient judgeClient;

    public RagEvaluationService(ChatClient.Builder builder) {
        // 评估用更强的模型当裁判，避免和生成模型同质
        this.judgeClient = builder
                .defaultSystem("你是一个严谨的评估员，只基于事实判断")
                .build();
    }

    public RagMetrics evaluate(String question, String answer, List<Document> contexts) {
        double faithfulness = scoreFaithfulness(answer, contexts);
        double relevance = scoreAnswerRelevancy(question, answer);
        double contextPrecision = scoreContextPrecision(question, contexts);

        return new RagMetrics(faithfulness, relevance, contextPrecision);
    }

    private double scoreFaithfulness(String answer, List<Document> contexts) {
        String ctx = contexts.stream().map(Document::getText)
                .reduce("", (a, b) -> a + "\n---\n" + b);
        String prompt = """
                上下文：
                %s
                
                回答：
                %s
                
                任务：
                1. 把回答拆成若干 atomic claims（不可再分的事实陈述）
                2. 对每个 claim，判断它能否被上下文直接支持
                3. 输出：faithfulness = 被支持的 claims 数 / 总 claims 数
                4. 只输出一个小数，不解释
                """.formatted(ctx, answer);
        String result = judgeClient.prompt().user(prompt).call().content();
        try { return Double.parseDouble(result.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private double scoreAnswerRelevancy(String question, String answer) {
        String prompt = """
                问题：%s
                回答：%s
                
                打分：回答切题程度（0-1），只输出小数
                """.formatted(question, answer);
        String result = judgeClient.prompt().user(prompt).call().content();
        try { return Double.parseDouble(result.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private double scoreContextPrecision(String question, List<Document> contexts) {
        String ctx = "";
        for (int i = 0; i < contexts.size(); i++) {
            ctx += "[Doc" + (i+1) + "] " + contexts.get(i).getText() + "\n";
        }
        String prompt = """
                问题：%s
                
                候选文档：
                %s
                
                任务：判断每个文档是否对回答问题有用。输出格式：
                DOC_1=1（有用）或 DOC_1=0（无用）
                
                最后输出：precision = 有用文档数 / 总文档数
                """.formatted(question, ctx);
        String result = judgeClient.prompt().user(prompt).call().content();
        // 简化解析
        try {
            String[] parts = result.split("precision\\s*=");
            return Double.parseDouble(parts[parts.length - 1].trim().substring(0, 3));
        } catch (Exception e) { return 0.0; }
    }
}
```

```java
// org/demo02/rag/eval/RagMetrics.java
package org.demo02.rag.eval;

public record RagMetrics(double faithfulness, double relevance, double contextPrecision) {
    public double overall() {
        return (faithfulness + relevance + contextPrecision) / 3.0;
    }
}
```

### 8.3 评估数据集（必须人工标注）

RAG 评估的最大门槛不是写打分代码，而是**有没有 ground truth 数据集**。最小集合：

```yaml
# src/main/resources/rag-eval-dataset.yaml
- question: "Spring AI 2.0 是什么时候发布的？"
  expected_keywords: ["2026-06-12", "GA"]
  relevant_doc_sources: ["spring-ai-2.0-release-notes.pdf"]
- question: "RetrievalAugmentationAdvisor 的默认 topK 是多少？"
  expected_keywords: ["10"]
  relevant_doc_sources: ["rag-doc.pdf"]
```

加载 + 跑全集 + 平均指标：

```java
// org/demo02/rag/eval/RagEvalRunner.java
// 本代码仅作学习材料参考
package org.demo02.rag.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagEvalRunner {

    private final ChatClient chatClient;
    private final RetrievalAugmentationAdvisor ragAdvisor;
    private final RagEvaluationService evaluator;

    @Value("classpath:rag-eval-dataset.yaml")
    private Resource dataset;

    public RagEvalRunner(ChatClient chatClient,
                          RetrievalAugmentationAdvisor ragAdvisor,
                          RagEvaluationService evaluator) {
        this.chatClient = chatClient;
        this.ragAdvisor = ragAdvisor;
        this.evaluator = evaluator;
    }

    public List<RagMetrics> runAll() throws Exception {
        List<RagMetrics> results = new ArrayList<>();
        try (InputStream in = dataset.getInputStream()) {
            List<Map<String, Object>> data = new Yaml().load(in);
            for (Map<String, Object> item : data) {
                String q = (String) item.get("question");
                String answer = chatClient.prompt()
                        .advisors(ragAdvisor)
                        .user(q)
                        .call()
                        .content();
                List<Document> ctx = List.of(); // TODO: 从 advisor context 拿
                results.add(evaluator.evaluate(q, answer, ctx));
            }
        }
        return results;
    }
}
```

---

## 9. 量化对比：不同策略的指标对比

评估闭环搭好后，可以做**真正的工程决策**——不再凭感觉选参数。

### 9.1 对照实验设计

| 维度 | 候选值 | 测试目的 |
|------|-------|---------|
| chunk size | 400 / 800 / 1200 / 1600 | 多大最合适 |
| overlap | 0 / 100 / 150 / 300 | overlap 收益 |
| topK | 3 / 5 / 10 / 20 | 召回 vs 噪音 |
| 检索策略 | 向量 / BM25 / 混合 | 是否混合更优 |
| Embedding | bge-large / m3e / openai | 哪个模型好 |
| Rerank | 无 / LLM-rerank / cross-encoder | rerank 值不值 |

### 9.2 对照实验代码

```java
// 在 EvaluationController 里加一个对照实验接口
@PostMapping("/compare-chunk-size")
public Map<String, RagMetrics> compareChunkSize() {
    Map<String, RagMetrics> results = new LinkedHashMap<>();
    for (int size : List.of(400, 800, 1200, 1600)) {
        // 1. 用不同 chunk size 重新摄入（实际项目要做缓存，否则很慢）
        reIngestWithChunkSize(size);
        // 2. 跑全集
        List<RagMetrics> all = evalRunner.runAll();
        // 3. 取平均
        double f = all.stream().mapToDouble(RagMetrics::faithfulness).average().orElse(0);
        double r = all.stream().mapToDouble(RagMetrics::relevance).average().orElse(0);
        double cp = all.stream().mapToDouble(RagMetrics::contextPrecision).average().orElse(0);
        results.put("chunk_" + size, new RagMetrics(f, r, cp));
    }
    return results;
}
```

### 9.3 典型量化结果（参考）

> 以下数字基于 [`docs/reference/理论基础/02-RAG深度优化.md`](../../reference/理论基础/02-RAG深度优化.md) 的多轮对照实验，仅供参考，你的数据集结论可能不同：

```
chunk_400:   faithfulness=0.78  relevance=0.82  precision=0.71
chunk_800:   faithfulness=0.84  relevance=0.86  precision=0.78   ← 推荐
chunk_1200:  faithfulness=0.81  relevance=0.83  precision=0.69
chunk_1600:  faithfulness=0.75  relevance=0.79  precision=0.62

topK_3:      precision=0.85  recall=0.62
topK_5:      precision=0.81  recall=0.78   ← 推荐
topK_10:     precision=0.72  recall=0.85
topK_20:     precision=0.61  recall=0.91

vector_only:   precision=0.74  recall=0.76
bm25_only:     precision=0.79  recall=0.68
hybrid:        precision=0.82  recall=0.84   ← 推荐
```

---

## 10. 生产化要点

### 10.1 增量索引

文档更新时不要全量重建索引，要按文档 ID 做 upsert：

```java
public void upsert(Document doc) {
    // VectorStore 默认 add 行为：相同 ID 会覆盖
    vectorStore.delete(List.of(doc.getId()));
    vectorStore.add(List.of(doc));
}
```

### 10.2 多租户隔离

```java
// 检索时强制带租户过滤
String filter = "tenant_id == '" + TenantContext.get() + "'";
SearchRequest req = SearchRequest.builder()
        .query(query)
        .filterExpression(filter)
        .topK(10)
        .build();
```

### 10.3 缓存层

- **Embedding 缓存**：同一段文本向量化一次后缓存（Redis，key=text hash）
- **Query 改写缓存**：常见 query 的改写结果缓存
- **Top-K 缓存**：高频 query 的检索结果缓存（短 TTL）

### 10.4 监控指标

| 指标 | 阈值告警 |
|------|---------|
| 检索 P95 延迟 | > 500ms |
| 检索 topK 全为低分 | 阈值 < 0.3 |
| LLM 回答 "我不知道" 的比例 | > 30% |
| Faithfulness 月度均值 | < 0.75 |
| 引用命中率（[DocN] 出现率）| < 80% |

---

## 11. 验证清单

照着做完后，逐项验证：

- [ ] `POST /rag/ingest` 接收 PDF，向量化入库，能查到
- [ ] `POST /rag/ask?q=xxx` 返回带 [DocN] 引用的答案
- [ ] 故意问知识库里没有的问题，LLM 答"我不确定"（不编造）
- [ ] 评估管道能跑全集，输出 faithfulness / relevance / contextPrecision
- [ ] 对照实验能输出不同 chunk size 的指标对比
- [ ] 混合检索的 precision/recall 比纯向量高（你的数据集验证）
- [ ] Rerank 后的 top5 中无噪音（人工抽检 20 个 query）
- [ ] 故意修改一个已入库文档，重摄入后旧版本不再被召回

---

## 12. 反模式速查

| ❌ 反模式 | 后果 | ✅ 正确做法 |
|----------|------|-----------|
| 直接用 `QuestionAnswerAdvisor` 上生产 | 无法做混合检索 / rerank / 引用溯源 | 用 `RetrievalAugmentationAdvisor` 拼装 |
| Chunk 切太大（>2000 字符） | LLM 看不全、context 噪音大 | 800-1200 字符 + overlap 150 |
| 只用向量检索 | 关键词精确匹配场景失败 | 混合检索（向量 0.7 + BM25 0.3） |
| 没有 Rerank | topK 里夹杂不相关文档 | 检索 topK=20 → rerank → 5 |
| 没有引用溯源 | 用户无法验证、信任度低 | 拼 prompt 时让 LLM 加 [DocN] |
| 没有评估闭环 | 调参凭感觉、回归靠运气 | 搭 LLM-as-Judge 评估管道 |
| 用同一个模型当裁判和生成器 | 裁判偏袒、指标虚高 | 裁判用更强或不同模型 |

---

## 13. 进阶扩展（不在本文范围）

| 方向 | 说明 | 参考资源 |
|------|------|---------|
| Embedding 微调 | 用业务数据微调 Embedding 模型，提升召回 | [reference/工程架构/07-模型微调.md](../../reference/工程架构/07-模型微调.md) |
| ColBERT / late-interaction | 比 cross-encoder 快、比 bi-encoder 准 | RAGatouille 项目 |
| Graph RAG | 用知识图谱增强 RAG | Microsoft GraphRAG |
| Agentic RAG | 让 Agent 决定检索策略、多轮检索 | LangGraph / Alibaba Graph |
| 多模态 RAG | 文本 + 图片 + 表格混合检索 | CLIP 模型 + 向量库 |

---

## 14. 相关文档

- [`./05-MCP协议全解.md`](./05-MCP协议全解.md) —— 会话持久化（与 RAG 共用向量库场景）
- [`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md) —— 通用评估闭环（本文是 RAG 专用）
- [`../../reference/理论基础/02-RAG深度优化.md`](../../reference/理论基础/02-RAG深度优化.md) —— RAG 理论深度版
- [Spring AI RAG Reference](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)

---

完成本文后，你已经能：
1. 在 Spring Boot 4 + Spring AI 2.0 项目里从 0 搭建企业级 RAG 系统
2. 自写 Retriever（混合检索）/ PostProcessor（rerank/去重）/ QueryAugmenter（引用溯源）
3. 搭 LLM-as-Judge 评估闭环，量化对比不同 RAG 策略
4. 做出生产化决策（增量索引、多租户隔离、监控指标）

回到 [`./00-目录索引.md`](./00-目录索引.md) 继续后续等级学习。
