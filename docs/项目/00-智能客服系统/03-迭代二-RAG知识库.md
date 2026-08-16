# 项目 00：智能客服系统 — 03-迭代二·RAG 知识库

> **定位**：为客服 Agent 接入向量数据库（PGVector），实现基于产品手册的智能问答。涵盖文档 ETL 流水线、Embedding 向量化、PGVector 配置、`QuestionAnswerAdvisor` 集成。读完这篇，客服 Agent 能回答任意产品文档问题。本文给出**完整可手写代码**。
> **读者画像**：已完成工具集成，需要让 Agent 具备「阅读长文档」能力。
> **前置阅读**：[02-迭代一-工具集成]。
> **关联教程**：[教程 05-RAG 检索增强生成]、[教程 06-向量数据库选型]、[教程 35-高级 RAG]；API 真实性以 [附录 05] 为准。

---

## 1. 为什么需要 RAG

FAQ 工具两个硬伤：① 只能回答预置的几条 ② 产品手册几百页，无法全塞进 Prompt。RAG 让 Agent 从「预置 FAQ」升级为「任意产品文档可答」。

## 2. 完整代码（照抄即可）

### 2.1 `pom.xml` 追加依赖

```xml
        <!-- 追加（RAG）：向量库 PGVector + 文档解析 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-pdf-document-reader</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-markdown-document-reader</artifactId>
        </dependency>
```

### 2.2 `application.yml` 追加（PGVector）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/customer
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
```

### 2.3 `VectorStoreConfig.java`

```java
package com.shop.customer.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        // javap 实证：PgVectorStore.builder(JdbcTemplate, EmbeddingModel)
        return PgVectorStore.builder(jdbcTemplate, embeddingModel).build();
    }
}
```

> `PgVectorStore.builder()` 的真实签名是 `(JdbcTemplate, EmbeddingModel)`（javap 实证，[教程 06-向量数据库选型] 与 [附录 05]）。`JdbcTemplate` 由 `spring-ai-starter-vector-store-pgvector` + `spring-boot-starter-jdbc` 自动装配，`EmbeddingModel` 由 OpenAI starter 自动装配。语义不变：`VectorStore` 是检索抽象。

### 2.4 `KnowledgeBaseLoader.java`（ETL：读文档 → 切分 → 向量化 → 存库）

```java
package com.shop.customer.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动时把产品手册灌入向量库（ETL 三件套：Reader → Splitter → VectorStore）。 */
@Component
public class KnowledgeBaseLoader implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public KnowledgeBaseLoader(VectorStore vectorStore, TokenTextSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    @Override
    public void run(String... args) {
        // Spring AI 2.0.0：资源在构造器传入；DocumentReader extends Supplier<List<Document>>，用 get() 读取
        MarkdownDocumentReader reader = new MarkdownDocumentReader(
                new ClassPathResource("manual/产品手册.md"),
                MarkdownDocumentReaderConfig.builder()
                        .withIncludeCodeBlock(false)   // 产品手册基本不含代码块
                        .withIncludeBlockquote(true)   // 手册中的「注意/警告」引用块是客服问答的高价值内容
                        .build());
        List<Document> docs = reader.get();
        List<Document> chunks = splitter.apply(docs);   // 切分成块
        vectorStore.add(chunks);                         // Embedding + 存储
    }
}
```

> `TokenTextSplitter` 需要作为 Bean 提供——在 `VectorStoreConfig` 加：

```java
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        // Spring AI 2.0.0：构造器自 2.0.0-M3 起弃用（forRemoval），统一使用 builder()
        return TokenTextSplitter.builder()
                .withChunkSize(800)            // 每个 chunk 的目标 token 数
                .withMinChunkSizeChars(100)    // chunk 内段落最小字符数
                .withMinChunkLengthToEmbed(5)  // 短于该长度的 chunk 跳过嵌入
                .withMaxNumChunks(10000)       // 单文档最大 chunk 数
                .withKeepSeparator(true)       // 切分时保留分隔符
                .build();
    }
```

### 2.5 `ChatClientConfig` 更新（加 RAG Advisor）

```java
package com.shop.customer.config;

import com.shop.customer.tool.FaqQueryTool;
import com.shop.customer.tool.OrderQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 FaqQueryTool faqTool,
                                 OrderQueryTool orderTool,
                                 VectorStore vectorStore) {
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(0.7)
                        .build())
                .build();

        return builder
                .defaultSystem("你是电商客服小智，只能依据工具查询结果回答，不要编造订单/物流信息。")
                .defaultTools(faqTool, orderTool)
                .defaultAdvisors(ragAdvisor)
                .build();
    }
}
```
> **需在 pom.xml 中添加依赖**（`QuestionAnswerAdvisor` 所在模块）：2.0.0 起模块名从 `spring-ai-advisors-vector-store` 改为 `spring-ai-vector-store-advisor`（老坐标已不存在）——版本走 `spring-ai-bom`，不写 `<version>`：
>
> ```xml
> <dependency>
>     <groupId>org.springframework.ai</groupId>
>     <artifactId>spring-ai-vector-store-advisor</artifactId>
> </dependency>
> ```

> `QuestionAnswerAdvisor` 的真实包路径是 `...advisor.vectorstore`（教程 05 旧稿曾写错），以 [附录 05-00 §1.4] 为准。

## 3. 验收

- [x] 问「产品手册里没写进 FAQ 的问题」→ 从向量库召回并回答
- [x] 检索 Top-3 命中率 ≥ 90%（量化目标）
- [x] 无关问题不强行召回（similarityThreshold 0.7）

> **定位回顾**：本篇让 Agent 会读文档。下一站 [04-迭代三-记忆与会话]——接 ChatMemory 做多轮记忆。
