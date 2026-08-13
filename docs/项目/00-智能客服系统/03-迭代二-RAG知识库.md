# 03-迭代二：RAG 知识库

> **定位**：为客服 Agent 接入向量数据库（PGVector），实现基于产品手册的智能问答。涵盖文档 ETL 流水线、Embedding 向量化、PGVector 配置、QuestionAnswerAdvisor 集成、检索质量调优。读完这篇，你的客服 Agent 不再局限于预置 FAQ，能回答任意产品文档中的问题。
>
> **读者画像**：已完成工具集成，需要让 Agent 具备"阅读长文档"的能力。
>
> **前置阅读**：[02-迭代一-工具集成](02-迭代一-工具集成.md)。
>
> **关联教程**：[教程 05-RAG 检索增强生成](../../教程/05-RAG检索增强生成.md)、[教程 30-高级 RAG 与 Agentic RAG](../../教程/30-高级RAG与AgenticRAG.md)。

---

## 1. FAQ 工具的局限

上一迭代加入了 FAQ 查询工具，但它有两个硬伤：

```mermaid
graph TB
    subgraph FAQ局限["FAQ 工具的局限"]
        L1["覆盖面窄<br/>FAQ 只收录了高频问题<br/>长尾问题答不了"]
        L2["维护成本高<br/>新产品上架 → 手动写 FAQ<br/>参数变更 → 手动更新"]
        L3["语义匹配弱<br/>关键词匹配<br/>用户换种说法就搜不到"]
    end

    subgraph RAG优势["RAG 的解法"]
        R1["全量文档入库<br/>产品手册、规格书全文可检索"]
        R2["自动同步<br/>文档更新 → 重新向量化"]
        R3["语义理解<br/>Embedding 向量检索<br/>同义说法也能命中"]
    end

    L1 --> R1
    L2 --> R2
    L3 --> R3

    style FAQ局限 fill:#ffcdd2
    style RAG优势 fill:#c8e6c9
```

典型场景：用户问"空气炸锅 5.5L 型号能不能放下一整只鸡？"——FAQ 里没有这条，但产品手册的"容量说明"章节写了"可放入 1.5kg 食材"。RAG 能从手册中检索到这段内容并回答。

---

## 2. RAG 流水线全貌

RAG 不是一行代码搞定的，它分两个阶段：

```mermaid
graph LR
    subgraph 离线阶段["离线阶段：文档入库（一次性）"]
        D1["产品手册<br/>Markdown/PDF"] --> D2["文档切分<br/>按段落/Token"]
        D2 --> D3["Embedding<br/>文本→向量"]
        D3 --> D4["存入 PGVector<br/>向量+原文"]
    end

    subgraph 在线阶段["在线阶段：检索增强（每次查询）"]
        Q1["用户提问"] --> Q2["Embedding<br/>问题→向量"]
        Q2 --> Q3["PGVector<br/>相似度搜索"]
        Q3 --> Q4["Top-K 文档片段"]
        Q4 --> Q5["注入 Prompt"]
        Q5 --> Q6["LLM 生成回复"]
    end

    style 离线阶段 fill:#e3f2fd
    style 在线阶段 fill:#e8f5e9
```

本篇按这两个阶段分别实现。

> 「遇到阻塞？→ [教程 05-RAG 检索增强生成：RAG 流水线](../../教程/05-RAG检索增强生成.md)」

---

## 3. 离线阶段：文档向量化

### 3.1 准备知识库文档

在 `src/main/resources/data/` 下放置产品手册 `product-manual.md`：

```markdown
# 空气炸锅 5.5L 产品手册

## 第一章 产品概述
本产品为 XX 品牌 5.5L 大容量空气炸锅，采用高速热风循环技术...

## 第二章 使用指南
### 2.1 首次使用
1. 拆除所有包装材料
2. 用温水清洗炸篮和煎锅
3. 空烧 10 分钟去除出厂保护油（会有轻微气味，属正常现象）

### 2.2 烹饪建议
| 食材 | 温度 | 时间 | 容量 |
|------|------|------|------|
| 薯条 | 200°C | 15 分钟 | 500g |
| 鸡翅 | 190°C | 20 分钟 | 8 只 |
| 整鸡 | 180°C | 40 分钟 | 1.2kg 以内 |
| 红薯 | 200°C | 35 分钟 | 3-4 个 |

### 2.3 注意事项
- 可以使用锡纸，建议垫在炸篮底部接油，不要遮挡热风循环孔
- 烹饪中途建议翻面或摇晃炸篮，确保受热均匀
- 容量上限 1.5kg，超过会导致加热不均

## 第三章 清洁与保养
...
```

### 3.2 文档切分策略

整篇手册不能直接 Embedding——LLM 上下文有限，且检索精度要求片段足够"聚焦"。切分策略：

```mermaid
graph TB
    subgraph 切分策略["文档切分三要素"]
        S1["切分粒度<br/>chunk_size: 300-500 Token"]
        S2["重叠区<br/>overlap: 50-100 Token"]
        S3["切分依据<br/>优先按段落/标题"]
    end

    subgraph 效果["为什么这样切"]
        E1["太长 → 检索噪声多<br/>一段包含多个主题"]
        E2["太短 → 语义不完整<br/>半句话无法理解"]
        E3["重叠 → 避免关键信息<br/>被切到两半"]
    end

    style 切分策略 fill:#e3f2fd
    style 效果 fill:#e8f5e9
```

Spring AI 提供 `TokenTextSplitter`，开箱即用：

```java
@Configuration
public class VectorStoreConfig {

    @Bean
    public TokenTextSplitter textSplitter() {
        return new TokenTextSplitter(
            400,    // chunkSize：每段约 400 Token
            80,     // overlap：段间重叠 80 Token
            10,     // minChunkSizeChars：最小段长度
            5000,   // maxNumChunks：最大段数（防 OOM）
            true    // keepSeparator：保留段落分隔符
        );
    }
}
```

`chunkSize = 400` 的考量：产品手册的一段（如"注意事项"）通常 200-500 字，约 300-500 Token。400 是一个平衡点——一个片段包含一个完整子主题。

### 3.3 文档 ETL 流水线

Spring AI 的 ETL 三件套：`DocumentReader` → `DocumentSplitter` → `VectorStore`。

```java
@Component
public class KnowledgeBaseLoader {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public KnowledgeBaseLoader(VectorStore vectorStore, TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    /**
     * 应用启动时自动加载产品手册到向量库
     */
    @PostConstruct
    public void loadKnowledgeBase() {
        // 1. 读取 Markdown 文档
        DocumentReader reader = new MarkdownDocumentReader(
            "classpath:data/product-manual.md"
        );
        List<Document> documents = reader.get();

        // 2. 切分
        List<Document> chunks = textSplitter.apply(documents);

        // 3. 给每个片段打元数据标签
        chunks.forEach(doc -> {
            doc.getMetadata().put("source", "product-manual");
            doc.getMetadata().put("category", "产品手册");
            doc.getMetadata().put("version", "v2.0");
        });

        // 4. 写入向量库（Spring AI 自动 Embedding + 存储）
        vectorStore.add(chunks);

        log.info("知识库加载完成：{} 个文档片段", chunks.size());
    }
}
```

流程解析：

**`DocumentReader`**——读取文件并解析为 `Document` 对象（`content` + `metadata`）。Spring AI 提供 `MarkdownDocumentReader`、`PdfDocumentReader`、`TextReader` 等多种 Reader。

**`TokenTextSplitter.apply()`**——按 Token 数切分。注意是 Token 不是字符——一个中文字约 1-2 Token，按 Token 切能更精确控制喂给 LLM 的内容量。

**`metadata`**——给每个片段附加结构化标签。后续检索时可以按 metadata 过滤（如只搜"产品手册"类别），提高召回精度。

**`vectorStore.add(chunks)`**——一行代码完成 Embedding + 存储。`VectorStore` 内部调用 `EmbeddingModel` 将文本转向量，再写入 PGVector。

> 「遇到阻塞？→ [教程 05-RAG 检索增强生成：ETL 流水线](../../教程/05-RAG检索增强生成.md)」

---

## 4. PGVector 配置

### 4.1 添加依赖

`pom.xml` 追加：

```xml
<!-- PGVector 向量数据库支持 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>

<!-- PostgreSQL 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### 4.2 数据库配置

`application.yml` 追加：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/customer_db
    username: postgres
    password: postgres
  ai:
    vectorstore:
      pgvector:
        # 向量维度，需与 Embedding 模型一致
        dimensions: 1536
        # 距离计算方式：cosine 余弦相似度
        distance-type: cosine_distance
        # 自动创建表结构
        initialize-schema: true
        # 索引类型：HNSW（高性能近似搜索）
        index-type: hnsw
```

关键参数解析：

| 参数 | 选择 | 理由 |
|------|------|------|
| `dimensions` | 1536 | DeepSeek Embedding 模型输出维度 |
| `distance-type` | `cosine_distance` | 余弦相似度，适合语义搜索（方向比绝对值重要） |
| `index-type` | `hnsw` | HNSW 索引查询速度快，适合在线检索（精确搜索 IVF-flat 也可以但慢） |
| `initialize-schema` | `true` | 开发环境自动建表，生产环境用 Flyway 管理 |

> 「遇到阻塞？→ [教程 26-向量数据库选型：PGVector](../../教程/26-向量数据库选型.md)」

### 4.3 PGVector 表结构

Spring AI 自动创建的表：

```sql
CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT,               -- 原文片段
    metadata JSON,              -- 元数据
    embedding vector(1536)      -- 向量（PGVector 扩展类型）
);

-- HNSW 索引加速向量搜索
CREATE INDEX ON vector_store
    USING hnsw (embedding vector_cosine_ops);
```

---

## 5. 在线阶段：检索增强

### 5.1 QuestionAnswerAdvisor

Spring AI 2.0 提供 `QuestionAnswerAdvisor`——一个封装了"检索→注入 Prompt"逻辑的 Advisor。加到 ChatClient 的 Advisor 链中，自动为每次对话注入相关文档。

```mermaid
graph TB
    subgraph 无RAG["无 RAG Advisor"]
        A1["用户问题"] --> A2["直接发给 LLM"]
        A2 --> A3["LLM 用内置知识回答<br/>可能幻觉"]
    end

    subgraph 有RAG["有 QuestionAnswerAdvisor"]
        B1["用户问题"] --> B2["Advisor 拦截"]
        B2 --> B3["向量检索 Top-K 文档"]
        B3 --> B4["文档注入 Prompt"]
        B4 --> B5["LLM 基于文档回答<br/>有据可依"]
    end

    style 无RAG fill:#ffcdd2
    style 有RAG fill:#c8e6c9
```

### 5.2 修改 ChatClientConfig

```java
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            FaqQueryTool faqTool,
            OrderQueryTool orderTool,
            LogisticsTool logisticsTool,
            VectorStore vectorStore) {

        // 创建 RAG Advisor
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .promptTemplate(createRagPromptTemplate())
            .searchRequest(
                SearchRequest.builder()
                    .topK(3)                        // 检索 Top-3 文档
                    .similarityThreshold(0.7)       // 相似度阈值
                    .build()
            )
            .build();

        return builder
            .defaultSystem("""
                你是"小智"，XX 电商平台的智能客服助手。
                保持专业、友好、简洁的语气。
                当回答产品问题时，优先依据知识库检索结果。
                如果检索结果不足以回答，诚实告知并建议联系人工客服。
                """)
            .defaultTools(faqTool, orderTool, logisticsTool)
            .defaultAdvisors(ragAdvisor)
            // .defaultAdvisors(memoryAdvisor)  // 迭代三加入
            .build();
    }

    /**
     * 自定义 RAG Prompt 模板
     * 控制"检索结果如何呈现给 LLM"
     */
    private PromptTemplate createRagPromptTemplate() {
        return new PromptTemplate("""
            以下是相关的知识库信息，请优先基于这些信息回答用户问题：

            ---知识库内容---
            {question_answer_context}
            ---内容结束---

            用户问题：{question_answer}

            如果知识库内容足以回答，请基于内容给出准确回复。
            如果知识库内容与问题无关或不足以回答，请忽略这些内容，
            用你的通用知识回答或告知用户无法回答。
            """);
    }
}
```

逐段解析：

**`QuestionAnswerAdvisor.builder(vectorStore)`**

绑定向量库。每次用户提问时，Advisor 自动用用户消息做 query，检索相关文档。

**`.topK(3)`**

检索 Top-3 最相似的文档片段。3 是一个经验值——太少（1）可能漏掉相关信息，太多（5+）会引入噪声且浪费 Token。

**`.similarityThreshold(0.7)`**

余弦相似度阈值——低于 0.7 的文档视为"不太相关"被过滤掉。这个值需要根据实际效果调：如果召回率低，降到 0.6；如果精确率低（噪声多），提高到 0.8。

**自定义 Prompt 模板**

默认模板只是简单拼上下文，我们的自定义模板加入了行为引导："如果检索结果无关，请忽略"——这避免了 LLM 强行用不相关的文档回答（幻觉的常见来源）。

> 「遇到阻塞？→ [教程 05-RAG 检索增强生成：QuestionAnswerAdvisor](../../教程/05-RAG检索增强生成.md)」

### 5.3 Advisor 链的执行顺序

```mermaid
graph TB
    subgraph 请求处理["请求方向（LLM 调用前）"]
        R1["用户消息进入 ChatClient"]
        R2["RAG Advisor 拦截<br/>向量检索文档"]
        R3["文档注入 Prompt"]
        R4["Tool Advisor 准备工具描述"]
        R5["发送给 LLM"]
    end

    subgraph 响应处理["响应方向（LLM 返回后）"]
        H1["LLM 返回 / 工具调用请求"]
        H2["如需工具 → 执行工具"]
        H3["工具结果注入 → 再次调 LLM"]
        H4["最终回复"]
    end

    R1 --> R2 --> R3 --> R4 --> R5
    H1 --> H2 --> H3 --> H4

    style 请求处理 fill:#e8f5e9
    style 响应处理 fill:#fff3e0
```

RAG Advisor 在请求方向拦截——在发给 LLM 之前把检索结果塞进 Prompt。Tool Advisor 在响应方向处理——LLM 返回工具调用请求时，框架执行工具并把结果送回。

两者协同工作：用户问"空气炸锅能不能放整只鸡"→ RAG 检索到容量说明 → LLM 看到文档直接回答（不需要调工具）。用户问"我的订单到哪了"→ RAG 没有相关文档 → LLM 决定调用订单查询工具。

> 「遇到阻塞？→ [教程 13-Advisor 链与拦截器](../../教程/13-Advisor链与拦截器.md)」

---

## 6. 检索质量调优

### 6.1 常见问题与解决

```mermaid
graph TB
    subgraph 问题["RAG 检索常见问题"]
        P1["召回率低<br/>该找到的没找到"]
        P2["精确率低<br/>找到一堆不相关的"]
        P3["多跳问题<br/>需要跨段落推理"]
        P4["专有名词<br/>向量化后语义丢失"]
    end

    subgraph 解法["调优手段"]
        S1["降 similarityThreshold<br/>增加 topK"]
        S2["升 similarityThreshold<br/>加 metadata 过滤"]
        S3["Agentic RAG<br/>多轮检索"]
        S4["混合检索<br/>向量+关键词"]
    end

    P1 --> S1
    P2 --> S2
    P3 --> S3
    P4 --> S4

    style 问题 fill:#ffcdd2
    style 解法 fill:#c8e6c9
```

### 6.2 Metadata 过滤检索

如果用户明确指定了产品类别，可以在检索时加过滤条件：

```java
// 在 ChatService 中按场景定制检索
SearchRequest request = SearchRequest.builder()
    .query(userMessage)
    .topK(3)
    .filterExpression("category == '产品手册' && version == 'v2.0'")
    .build();
```

`filterExpression` 在向量搜索前先按 metadata 过滤——相当于"在产品手册中搜索"而非"在全部文档中搜索"，显著提升精确率。

### 6.3 混合检索（进阶）

纯向量检索在遇到型号编号（如"XX-2000W"）时会失效——这些字符串的语义信号很弱。解法是向量检索 + 全文检索双路召回：

```java
// 向量检索
List<Document> vectorResults = vectorStore.similaritySearch(
    SearchRequest.builder().query(query).topK(3).build()
);

// 全文检索（利用 PGVector 的全文搜索能力）
List<Document> keywordResults = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query(query)
        .topK(3)
        .build()
);

// 合并去重（简单取并集，生产环境可用 RRF 重排）
List<Document> merged = Stream.concat(
        vectorResults.stream(),
        keywordResults.stream()
    )
    .distinct()
    .limit(5)
    .toList();
```

> 「遇到阻塞？→ [教程 30-高级 RAG 与 Agentic RAG：混合检索与重排](../../教程/30-高级RAG与AgenticRAG.md)」

---

## 7. 验证 RAG 效果

### 7.1 产品参数问答

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "空气炸锅能放下一整只鸡吗？最多多大？"}'
```

预期：Agent 基于产品手册"容量上限 1.5kg"和"整鸡 1.2kg 以内"的内容回答。

### 7.2 边界测试——知识库覆盖不到的问题

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "空气炸锅有 WiFi 功能吗？"}'
```

预期：RAG 检索不到相关内容 → 自定义 Prompt 模板引导 LLM 说"产品手册中未提及 WiFi 功能，建议联系人工客服确认"。

### 7.3 查看检索的文档片段（调试技巧）

开发时，可以临时加日志查看 RAG 到底检索到了什么：

```java
@Around
public AdvisedResponse around(AdvisedRequest request, Chain chain) {
    // 在 RAG Advisor 执行后查看注入的上下文
    log.debug("RAG context: {}", request.context());
    return chain.nextAround(request);
}
```

或在 PGVector 中直接查询：

```sql
SELECT content, metadata,
       embedding <=> '[0.1, 0.2, ...]'::vector AS distance
FROM vector_store
ORDER BY distance
LIMIT 3;
```

观察 `distance` 列——值越小越相似，超过 1.0 说明几乎没有语义关联。

---

## 8. 当前架构状态

```mermaid
graph TB
    subgraph 完整能力["客服 Agent 当前能力"]
        C1["ChatClient 对话<br/>+ SSE 流式"]
        C2["工具调用<br/>FAQ/订单/物流"]
        C3["RAG 知识库<br/>产品手册问答"]
    end

    subgraph 缺失["❌ 仍缺失"]
        M1["多轮对话记忆<br/>用户追问会丢失上下文"]
        M2["会话持久化<br/>刷新页面历史消失"]
    end

    style 完整能力 fill:#c8e6c9
    style 缺失 fill:#ffcdd2
```

### 当前请求链路

```mermaid
sequenceDiagram
    participant U as 用户
    participant CC as ChatClient
    participant RAG as RAG Advisor
    participant L as DeepSeek
    participant VS as VectorStore
    participant T as 工具

    U->>CC: "空气炸锅能放整鸡吗？"
    CC->>RAG: 拦截请求

    rect rgb(232, 245, 233)
        Note over RAG,VS: 检索阶段
        RAG->>VS: similaritySearch("整鸡容量")
        VS-->>RAG: [容量说明片段, 烹饪建议片段]
        RAG->>RAG: 拼接文档到 Prompt
    end

    CC->>L: Prompt + 知识库上下文
    L-->>CC: "可以放 1.2kg 以内的整鸡..."

    Note over U,L: 无需工具调用，RAG 直接命中
    CC-->>U: 流式回复
```

---

## 9. 小结

本篇为客服 Agent 接入了完整的 RAG 能力：

1. **离线 ETL**——`MarkdownDocumentReader` 读取手册 → `TokenTextSplitter` 按 400 Token 切分 → `VectorStore.add()` 自动 Embedding 入库
2. **PGVector 配置**——1536 维、cosine 距离、HNSW 索引，兼顾检索精度和性能
3. **QuestionAnswerAdvisor**——`topK=3` + `similarityThreshold=0.7`，自定义 Prompt 模板引导"无关时忽略"
4. **质量调优**——metadata 过滤提升精确率，混合检索解决专有名词召回问题

核心架构变化：`defaultAdvisors(ragAdvisor)` 一行代码，ChatClient 的每次调用自动携带知识库上下文。RAG 与 Tool Calling 协同工作——文档能查到的走 RAG，实时数据走工具。

当前最大不足：**没有记忆**。用户说"刚才那个订单"，Agent 不知道"刚才"指什么。下一篇 [04-迭代三-记忆与会话](04-迭代三-记忆与会话.md) 将用 ChatMemory + Redis 实现多轮对话。
