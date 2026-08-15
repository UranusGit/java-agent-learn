# 01-最小 Demo 搭建

> **定位**：迭代零——从零搭建一个可运行的端到端 RAG 文档问答 Demo：文档上传、文本分块、向量化存储、向量检索、LLM 生成回答，全链路打通。这篇文档聚焦"让管线跑通"，给出**完整可手写代码**（一行不省略）。不追求精度优化和性能调优。
>
> **读者画像**：已了解 RAG 概念，需要动手搭建第一个 Spring AI RAG 应用的开发者。
>
> **前置阅读**：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 05-RAG 检索增强生成](../../教程/05-RAG检索增强生成.md)、[教程 13-结构化输出](../../教程/13-结构化输出.md)。API 真实性以 [附录 05-SpringAI2-API基准](../../附录/05-SpringAI2-API基准/00-Advisor与ChatMemory.md) 为准。

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的 RAG 文档问答：上传 Markdown → 分块 → 向量化 → 向量检索 → 结构化回答（含来源）→ SSE 流式 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：Controller → DocumentService（同步 ETL）→ VectorStore；QaService → ChatClient.entity() 结构化输出 |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**：只支持 Markdown、同步阻塞、固定分块、纯向量检索） |

## 2. Demo 目标

### 2.1 要实现什么

一个最简的文档问答管线：用户上传一篇 Markdown 文档 → 系统解析并分块 → 向量化存储 → 用户提问 → 系统检索相关块 → LLM 基于检索结果回答。

```mermaid
graph LR
    subgraph Demo流程["最小 Demo 端到端流程"]
        direction LR
        S1["1. 上传 Markdown"] --> S2["2. 文本分块"]
        S2 --> S3["3. Embedding 向量化"]
        S3 --> S4["4. 存入 PgVector"]
        S4 --> S5["5. 用户提问"]
        S5 --> S6["6. 向量检索"]
        S6 --> S7["7. 组装 Prompt"]
        S7 --> S8["8. LLM 生成回答"]
    end

    style Demo流程 fill:#e3f2fd
```

### 2.2 技术范围与量化验收

| # | 能力 | 是否包含 | 验收标准 |
|---|------|---------|---------|
| 1 | Markdown 文档上传 | 是 | curl 上传 200，返回 status=READY |
| 2 | 文本分块 | 是（固定 Token） | chunkCount > 0 |
| 3 | 向量检索 | 是 | 提问能命中相关文档块 |
| 4 | 结构化回答 | 是 | 返回 answer + sources + confident |
| 5 | SSE 流式输出 | 是 | curl -N 看到逐 token 输出 |
| 6 | PDF / Word 解析 | 否 | 迭代一实现 |
| 7 | 混合检索 / 重排 | 否 | 迭代二实现 |

---

## 3. 项目搭建

### 3.1 完整 `pom.xml`（迭代零精简版）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>doc-assistant</artifactId>
    <version>0.1.0</version>
    <name>doc-assistant</name>
    <description>智能文档助手 —— 企业级 RAG 文档问答系统</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>2.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- WebFlux 响应式 Web 框架（SSE 原生支持） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Spring AI OpenAI Starter（Chat + Embedding） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>

        <!-- Spring AI PgVector Starter（向量存储） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>

        <!-- JPA（文档元数据 DocumentEntity） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- PostgreSQL 驱动 -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- 可观测性 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Lombok 简化实体样板代码 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

> Tika（多格式解析）、JDBC（关键词检索）在迭代一 / 迭代二逐次追加，见后续文档的「pom.xml 追加依赖」块。

### 3.2 完整 `application.yml`

```yaml
spring:
  application:
    name: doc-assistant

  # PostgreSQL + PgVector 配置
  datasource:
    url: jdbc:postgresql://localhost:5432/docassistant
    username: ${DB_USER:docassistant}
    password: ${DB_PASSWORD:docassistant}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

  # WebFlux 上传大小限制（替代 MVC 的 spring.servlet.multipart）
  codec:
    max-in-memory-size: 20MB

  # Spring AI 配置
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: gpt-4o
          temperature: 0.3          # 低温度：事实性问答需要确定性
      embedding:
        options:
          model: text-embedding-3-small  # 1536 维，性价比高
    vectorstore:
      pgvector:
        index-type: HNSW            # 近似搜索索引
        distance-type: COSINE_DISTANCE  # 余弦相似度
        dimensions: 1536            # 与 Embedding 模型维度一致
        initialize-schema: true     # 自动创建 vector_store 表

server:
  port: 8080
```

**关键配置说明**：

- `temperature: 0.3`：文档问答是事实性任务，不需要高创造性。低温度让回答更确定、更贴合检索到的文档内容。
- `index-type: HNSW`：Hierarchical Navigable Small World 图索引，在召回率和查询延迟之间取得最佳平衡，是 PgVector 推荐的索引类型。
- `distance-type: COSINE_DISTANCE`：余弦距离最适合文本语义相似度计算。
- `spring.codec.max-in-memory-size`：WebFlux 的请求体/上传大小上限（MVC 的 `spring.servlet.multipart.*` 在 WebFlux 下不生效）。

> **向量数据库索引和距离度量深入** → [教程 06-向量数据库选型](../../教程/06-向量数据库选型.md)：该教程详细对比了 HNSW、IVF 等索引类型的性能特征，以及余弦距离、欧氏距离、内积在不同场景下的选择依据。

### 3.3 数据库准备（SQL DDL）

```sql
-- 创建数据库
CREATE DATABASE docassistant;

-- 连接到 docassistant 数据库后，启用 PgVector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 可选：手动创建 vector_store（Spring AI 的 initialize-schema: true 会自动创建，
-- 手动建表用于提前建 HNSW 索引、控制列类型）
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding vector(1536)          -- 与 Embedding 模型维度一致
);

-- 创建 HNSW 索引（cosine 距离）
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);
```

### 3.4 启动类 `DocAssistantApplication.java`

```java
package com.example.docassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocAssistantApplication.class, args);
    }
}
```

### 3.5 AI 核心配置类 `AiConfig.java`

```java
package com.example.docassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 核心配置——ChatClient 和 EmbeddingModel 由 Spring AI Starter 自动注入，
 * 这里只需构建 ChatClient 并设置系统提示词。
 * // Spring AI 2.0.0
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                    你是企业文档问答助手。请严格基于提供的参考文档回答用户问题。

                    规则：
                    1. 只使用参考文档中的信息回答，不要编造。
                    2. 如果参考文档中没有相关信息，请明确回答"根据现有文档，我无法回答这个问题"。
                    3. 回答时请标注信息来源（文档标题 + 相关段落）。
                    4. 保持简洁、专业、准确。
                    """)
                .build();
    }
}
```

系统提示词是 RAG 问答质量的基石。上面这段提示词做了三件关键的事：

- **约束信息来源**：强制 LLM 只基于检索到的文档回答，不要"自由发挥"。这是降低幻觉风险的第一道防线。
- **处理无答案情况**：当检索结果与问题不相关时，让 LLM 明确说"无法回答"，而不是硬编一个。
- **要求标注来源**：为后续的引用来源标注功能做铺垫。

---

## 4. 文档上传与处理

### 4.1 文档元数据实体

```java
package com.example.docassistant.model;

import com.example.docassistant.model.enums.DocumentStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@Setter
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String title;            // 文档标题

    private String format;           // MARKDOWN / PDF / WORD

    @Column(length = 1000)
    private String filePath;         // 原始文件存储路径

    @Enumerated(EnumType.STRING)
    private DocumentStatus status;   // UPLOADING / PROCESSING / READY / FAILED

    private Integer chunkCount;      // 分块数量

    @Column(length = 2000)
    private String errorMessage;     // 处理失败原因

    private LocalDateTime uploadedAt;

    private LocalDateTime processedAt;
}
```

```java
package com.example.docassistant.model.enums;

public enum DocumentStatus {
    UPLOADING,    // 文件已上传，等待处理
    PROCESSING,   // 正在解析 + 向量化
    READY,        // 处理完成，可被检索
    FAILED        // 处理失败
}
```

```java
package com.example.docassistant.repository;

import com.example.docassistant.model.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
}
```

文档状态机设计是工程化的关键——用户上传后不需要等待处理完成，前端轮询状态即可。

```mermaid
stateDiagram-v2
    [*] --> UPLOADING: 用户上传文件
    UPLOADING --> PROCESSING: 开始 ETL
    PROCESSING --> READY: 解析 + 向量化成功
    PROCESSING --> FAILED: 解析失败 / Embedding 失败
    READY --> [*]: 用户删除文档
    FAILED --> [*]: 用户删除文档
```

### 4.2 文档上传 API `DocumentController.java`

> WebFlux 下文件上传用 `FilePart`（响应式），配合 `DataBufferUtils.join` 读取字节，阻塞 IO（落盘 / DB）用 `boundedElastic` 调度器隔离，符合 WebFlux 铁律（EventLoop 上不阻塞）。

```java
package com.example.docassistant.api;

import com.example.docassistant.api.dto.DocumentStatusResponse;
import com.example.docassistant.api.dto.UploadResponse;
import com.example.docassistant.model.DocumentEntity;
import com.example.docassistant.service.DocumentService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文档——同步处理（Demo 阶段简化为同步，迭代一改为异步）
     */
    @PostMapping("/upload")
    public Mono<UploadResponse> upload(@RequestPart("file") FilePart filePart) {
        return documentService.upload(filePart)
                .map(doc -> new UploadResponse(
                        doc.getId(),
                        doc.getTitle(),
                        doc.getStatus().name(),
                        doc.getChunkCount()));
    }

    /**
     * 查询文档处理状态
     */
    @GetMapping("/{id}/status")
    public DocumentStatusResponse getStatus(@PathVariable UUID id) {
        DocumentEntity doc = documentService.getById(id);
        return new DocumentStatusResponse(
                doc.getId(),
                doc.getStatus().name(),
                doc.getChunkCount());
    }
}
```

### 4.3 文档服务 `DocumentService.java`

```java
package com.example.docassistant.service;

import com.example.docassistant.exception.DocumentException;
import com.example.docassistant.model.DocumentEntity;
import com.example.docassistant.model.enums.DocumentStatus;
import com.example.docassistant.repository.DocumentRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;

    // TokenTextSplitter：Spring AI 内置的文本分块器，按 Token 数切分
    // Spring AI 2.0.0 —— builder 方式配置 chunkSize（overlap 由迭代一的自研分块器实现）
    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder()
            .withChunkSize(500)
            .build();

    public DocumentService(VectorStore vectorStore, DocumentRepository documentRepository) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
    }

    /**
     * 响应式上传：读取 FilePart 字节 → boundedElastic 上执行阻塞 ETL → 返回文档实体
     */
    public Mono<DocumentEntity> upload(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes, 0, bytes.length);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(bytes -> uploadAndProcess(filePart.filename(), bytes));
    }

    public DocumentEntity getById(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentException("文档不存在: " + id));
    }

    /**
     * 同步 ETL：保存文件 → 读取内容 → 分块 → 向量化存储 → 更新状态
     */
    private DocumentEntity uploadAndProcess(String filename, byte[] bytes) {
        DocumentEntity doc = new DocumentEntity();
        doc.setTitle(filename);
        doc.setFormat(detectFormat(filename));
        doc.setStatus(DocumentStatus.UPLOADING);
        doc.setUploadedAt(LocalDateTime.now());
        doc = documentRepository.save(doc);

        try {
            // 1. 保存原始文件
            Path filePath = saveFile(filename, bytes, doc.getId());
            doc.setFilePath(filePath.toString());
            doc.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(doc);

            // 2. 读取文件内容（Demo 阶段：纯文本 Markdown）
            String content = new String(bytes, StandardCharsets.UTF_8);

            // 3. 创建 Spring AI Document 对象
            Document fullDoc = Document.builder()
                    .text(content)
                    .metadata(Map.of(
                            "documentId", doc.getId().toString(),
                            "title", doc.getTitle()))
                    .build();

            // 4. 文本分块
            List<Document> chunks = textSplitter.apply(List.of(fullDoc));

            // 5. 向量化 + 存入 PgVector（VectorStore 内部自动调用 EmbeddingModel）
            vectorStore.add(chunks);

            // 6. 更新文档状态
            doc.setStatus(DocumentStatus.READY);
            doc.setChunkCount(chunks.size());
            doc.setProcessedAt(LocalDateTime.now());
            return documentRepository.save(doc);

        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            throw new DocumentException("文档处理失败: " + e.getMessage(), e);
        }
    }

    private String detectFormat(String filename) {
        if (filename.endsWith(".md")) return "MARKDOWN";
        if (filename.endsWith(".pdf")) return "PDF";
        if (filename.endsWith(".doc") || filename.endsWith(".docx")) return "WORD";
        return "UNKNOWN";
    }

    private Path saveFile(String filename, byte[] bytes, UUID docId) throws IOException {
        Path dir = Path.of("data", "uploads");
        Files.createDirectories(dir);
        Path target = dir.resolve(docId + "_" + filename);
        Files.write(target, bytes);
        return target;
    }
}
```

**关键代码解析**：

1. **`TokenTextSplitter`**：Spring AI 内置的分块器，按 Token 数自动切分文本。参数 `500` 是目标块大小。Demo 阶段不配置重叠窗口（`TokenTextSplitter` 的 overlap 支持版本间不稳定），跨块边界信息丢失的问题由迭代一的自研 `ParagraphAwareSplitter` 解决。

2. **`Document` 对象**：Spring AI 对文档的抽象。`text` 是文本内容，`metadata` 是键值对元数据（文档 ID、标题等），检索时可以用来过滤或展示来源。

3. **`vectorStore.add(chunks)`**：这一行代码做了三件事——对每个分块调用 Embedding 模型生成向量 → 将向量和元数据写入 PgVector → 自动维护索引。VectorStore 接口屏蔽了所有底层细节。

4. **响应式上传**：`DataBufferUtils.join(filePart.content())` 把响应式数据流收拢为一个字节缓冲，`subscribeOn(Schedulers.boundedElastic())` 确保后续阻塞操作（文件写、JPA 写、Embedding 调用）不占用 Netty EventLoop。

> **Spring AI ETL Pipeline 深入** → [教程 05-RAG 检索增强生成](../../教程/05-RAG检索增强生成.md)：教程中详细讲解了 `DocumentReader → DocumentTransformer → DocumentWriter` 三段式 ETL 架构，以及 `TokenTextSplitter` 的工作原理和参数调优。

### 4.4 统一异常处理

```java
package com.example.docassistant.exception;

public class DocumentException extends RuntimeException {

    public DocumentException(String message) {
        super(message);
    }

    public DocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.example.docassistant.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<ErrorResponse> handleDocumentException(DocumentException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("DOCUMENT_ERROR", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "系统内部错误"));
    }

    public record ErrorResponse(String code, String message) {}
}
```

---

## 5. 问答 API

### 5.1 请求与响应 DTO

为了让回答可以被程序消费，问答结果需要用结构化输出返回——不仅仅是答案文本，还要包含引用的文档来源信息。

```java
package com.example.docassistant.api.dto;

/**
 * 问答请求
 */
public record QaRequest(
        String question,            // 用户问题
        Integer topK,               // 检索的文档块数量，默认 5
        Double similarityThreshold  // 相似度阈值，默认 0.7
) {}
```

```java
package com.example.docassistant.api.dto;

import java.util.List;

/**
 * 结构化问答响应
 *
 * 使用 record 定义，配合 Spring AI 的 entity() 方法实现结构化输出。
 * LLM 会严格按照这个结构返回 JSON。
 */
public record QaResponse(
        String answer,                  // 回答正文
        List<SourceReference> sources,  // 引用来源列表
        boolean confident               // 是否有足够信心回答
) {
    public record SourceReference(
            String documentTitle,   // 文档标题
            String relevantText,    // 被引用的具体段落
            double similarity       // 相似度分数
    ) {}
}
```

```java
package com.example.docassistant.api.dto;

import java.util.UUID;

public record UploadResponse(
        UUID documentId,
        String title,
        String status,
        Integer chunkCount
) {}
```

```java
package com.example.docassistant.api.dto;

import java.util.UUID;

public record DocumentStatusResponse(
        UUID documentId,
        String status,
        Integer chunkCount
) {}
```

**为什么用 `record` 而不是 class？**

Java 21 的 record 天然不可变、自带 equals/hashCode/toString、构造器简洁。在 RAG 场景中，问答响应是一次性数据对象，不需要可变性——record 是最合适的选择。

> **结构化输出深入** → [教程 13-结构化输出](../../教程/13-结构化输出.md)：教程详细讲解了 Spring AI 2.0 的 `entity()` 方法、BeanOutputConverter 底层原理、JSON Schema 自动生成机制，以及如何通过结构化输出让 LLM 返回精确的 Java 对象。

### 5.2 问答服务 `QaService.java`

```java
package com.example.docassistant.service;

import com.example.docassistant.api.dto.QaRequest;
import com.example.docassistant.api.dto.QaResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QaService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    private static final String QA_PROMPT_TEMPLATE = """
            参考文档：
            {context}

            用户问题：{question}

            请基于参考文档回答。如果参考文档中没有相关信息，请明确说明。
            """;

    public QaService(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    /**
     * 同步问答（阻塞检索 + 阻塞 LLM 调用，放到 boundedElastic 上执行）
     */
    public Mono<QaResponse> answer(QaRequest request) {
        return Mono.fromCallable(() -> {
            // 1. 向量检索
            List<Document> relevantDocs = retrieve(request);

            // 2. 没有相关文档，直接返回
            if (relevantDocs.isEmpty()) {
                return new QaResponse(
                        "根据现有文档，我无法回答这个问题。",
                        List.of(),
                        false);
            }

            // 3. 组装上下文文本
            String context = buildContext(relevantDocs);

            // 4. 调用 LLM 生成回答（结构化输出）
            QaResponse response = chatClient.prompt()
                    .user(userSpec -> userSpec
                            .text(QA_PROMPT_TEMPLATE)
                            .param("context", context)
                            .param("question", request.question()))
                    .call()
                    .entity(QaResponse.class);

            // 5. 用真实检索来源覆盖 LLM 生成的 sources
            return enrichWithSources(response, relevantDocs);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SSE 流式问答——打字机效果
     */
    public Flux<String> answerStream(QaRequest request) {
        return Mono.fromCallable(() -> retrieve(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(docs -> {
                    if (docs.isEmpty()) {
                        return Flux.just("根据现有文档，我无法回答这个问题。");
                    }
                    String context = buildContext(docs);
                    return chatClient.prompt()
                            .user(userSpec -> userSpec
                                    .text(QA_PROMPT_TEMPLATE)
                                    .param("context", context)
                                    .param("question", request.question()))
                            .stream()
                            .content();   // 返回 Flux<String>，逐 token 推送
                });
    }

    private List<Document> retrieve(QaRequest request) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(request.question())
                        .topK(request.topK() != null ? request.topK() : 5)
                        .similarityThreshold(
                                request.similarityThreshold() != null
                                        ? request.similarityThreshold()
                                        : 0.70)
                        .build());
    }

    private String buildContext(List<Document> docs) {
        return docs.stream()
                .map(doc -> "---\n" + doc.getText())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 将检索到的文档元数据补充到响应的 sources 字段
     */
    private QaResponse enrichWithSources(QaResponse response, List<Document> docs) {
        List<QaResponse.SourceReference> sources = docs.stream()
                .map(doc -> {
                    String text = doc.getText();
                    double score = doc.getMetadata().get("distance") instanceof Number n
                            ? n.doubleValue()
                            : 0.0;
                    return new QaResponse.SourceReference(
                            (String) doc.getMetadata().get("title"),
                            text.substring(0, Math.min(200, text.length())),
                            score);
                })
                .toList();
        return new QaResponse(response.answer(), sources, response.confident());
    }
}
```

**关键代码解析**：

1. **`SearchRequest.builder()`**：Spring AI 的检索请求构建器。`topK` 控制召回数量，`similarityThreshold` 过滤掉相似度过低的噪声结果。

2. **`.entity(QaResponse.class)`**：Spring AI 的结构化输出方法。底层会自动生成 JSON Schema，附加到 Prompt 中，让 LLM 返回符合 `QaResponse` 结构的 JSON，然后反序列化为 Java 对象。

3. **上下文组装**：将检索到的多个文档块用 `---` 分隔拼接，形成 `context` 文本注入 Prompt。这是 RAG 的核心——把检索结果"喂"给 LLM。

4. **`subscribeOn(Schedulers.boundedElastic())`**：相似度检索和 LLM 调用都是阻塞操作，放到弹性线程池执行，不阻塞 Netty EventLoop——WebFlux 铁律的落地。

### 5.3 `QaController.java`

```java
package com.example.docassistant.api;

import com.example.docassistant.api.dto.QaRequest;
import com.example.docassistant.api.dto.QaResponse;
import com.example.docassistant.service.QaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    /**
     * 同步问答——返回结构化 JSON
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<QaResponse> ask(@RequestBody QaRequest request) {
        return qaService.answer(request);
    }

    /**
     * SSE 流式问答——打字机效果
     * 返回 text/event-stream，前端逐字接收
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> askStream(@RequestBody QaRequest request) {
        return qaService.answerStream(request);
    }
}
```

SSE 流式输出的价值在于用户体验——用户不需要等 LLM 生成完整回答后才能看到结果，而是看到逐字输出的"打字机"效果。在 3-5 秒的回答场景中，这极大提升了感知速度。

```mermaid
sequenceDiagram
    participant U as 浏览器
    participant C as QaController
    participant S as QaService
    participant V as VectorStore
    participant L as LLM

    U->>C: POST /api/qa/stream (question)
    C->>S: answerStream(request)
    S->>V: similaritySearch(question)
    V-->>S: Top-K 文档块
    S->>L: stream().content()（流式调用）
    loop 逐 token 推送
        L-->>S: token chunk
        S-->>C: Flux<String> 元素
        C-->>U: SSE: data: token
    end
    L-->>S: [DONE]
    S-->>C: Flux 完成
    C-->>U: SSE 连接关闭
```

---

## 6. 端到端验证

### 6.1 启动

```bash
# 环境变量（不落明文到仓库）
export OPENAI_API_KEY=sk-xxx
export DB_USER=docassistant
export DB_PASSWORD=docassistant

# 启动（PostgreSQL 需已创建 docassistant 数据库并启用 vector 扩展）
mvn spring-boot:run
```

### 6.2 上传文档

```bash
# 上传 Markdown 文档
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@employee-handbook.md"

# 响应示例
{
  "documentId": "a1b2c3d4-...",
  "title": "employee-handbook.md",
  "status": "READY",
  "chunkCount": 12
}
```

### 6.3 提问

```bash
# 同步问答
curl -X POST http://localhost:8080/api/qa \
  -H "Content-Type: application/json" \
  -d '{"question": "年假超过多少天需要总监审批？"}'

# 响应示例
{
  "answer": "根据员工手册，年假超过5天需要部门总监审批...",
  "sources": [
    {
      "documentTitle": "employee-handbook.md",
      "relevantText": "请假审批权限：3天以内直属主管审批，3-5天经理审批，超过5天需总监审批...",
      "similarity": 0.87
    }
  ],
  "confident": true
}
```

```bash
# SSE 流式问答
curl -N http://localhost:8080/api/qa/stream \
  -H "Content-Type: application/json" \
  -d '{"question": "出差报销流程是什么？"}'

# 响应（逐 token 推送）
data: 根据
data: 员工
data: 手册
data: ，
data: 出差
...
```

### 6.4 Demo 验证清单

```mermaid
graph TB
    subgraph 验证清单["Demo 端到端验证清单"]
        V1["✅ Markdown 文档成功上传"]
        V2["✅ 文档被正确分块（检查 chunkCount）"]
        V3["✅ 向量成功写入 PgVector"]
        V4["✅ 向量检索返回相关文档块"]
        V5["✅ LLM 基于文档内容回答"]
        V6["✅ 回答包含引用来源"]
        V7["✅ 无关问题返回'无法回答'"]
        V8["✅ SSE 流式逐字输出正常"]
    end

    style 验证清单 fill:#c8e6c9
```

---

## 7. Demo 的局限性与改进方向

当前 Demo 能跑通端到端管线，但距离生产级还有明显差距：

| 局限性 | 表现 | 改进方向 | 迭代 |
|--------|------|---------|------|
| 只支持 Markdown | PDF / Word 无法处理 | 引入 Apache Tika 多格式解析 | 迭代一 |
| 分块策略粗糙 | 固定 Token 分块可能截断段落 | 按段落 + 语义分块 | 迭代一 |
| 同步处理 | 大文件上传阻塞请求线程 | 异步任务队列（@Async） | 迭代一 |
| 纯向量检索 | 专有名词召回率低 | 混合检索（向量 + 关键词） | 迭代二 |
| 无重排 | Top-K 中最相关的不在前面 | Reranker 重排 | 迭代二 |
| 无引用标注 | sources 是后补的，非 LLM 生成 | LLM 原生生成引用 | 迭代二 |

---

## 8. ADR 演进决策

### ADR 001-01：迭代零允许"正确架构缺席"，但必须埋下三个换接口
- **决策**：v0 用最直白实现（同步 ETL、固定分块、纯向量检索），但三个换接口在代码里留名——① 文档处理收敛到 `DocumentService.upload`（迭代一换异步 `EtlService`）② 分块收敛到 `TextSplitter`（迭代一换 `ParagraphAwareSplitter`）③ 检索收敛到 `VectorStore.similaritySearch`（迭代二换 `RetrievalService` 混合检索）
- **取舍理由**：接口名先立、实现后换——让后续迭代有抓手又不破坏最小 demo

### ADR 001-02：上传用响应式 FilePart + boundedElastic，不用 MVC 的 MultipartFile 同步上传
- **决策**：WebFlux 下 `@RequestPart("file") FilePart` + `DataBufferUtils.join` 读字节，阻塞 ETL 用 `subscribeOn(Schedulers.boundedElastic())`
- **取舍理由**：MVC 的 `spring.servlet.multipart` 在 WebFlux 下不生效；FilePart 是响应式原生类型，避免 EventLoop 上阻塞（WebFlux 铁律）

### ADR 001-03：结构化输出用 entity(Class) 真实重载，不用带 spec-lambda 的虚构变体
- **决策**：`chatClient.prompt()...call().entity(QaResponse.class)`，禁止 `entity(Class, spec -> ...)` 全家族（[附录 12-02 §2] 确认其为虚构 API）
- **取舍理由**：仅两种真实形态（Class / ParameterizedTypeReference），带 lambda 的重载不存在，照抄会编译失败

---

## 9. 总结

本文从零搭建了一个端到端的 RAG 文档问答 Demo，核心完成了以下工作：

1. **项目骨架**：Spring Boot 4.1 + Spring AI 2.0 + PgVector 配置，建立了 ETL + 检索 + 问答的完整管线。

2. **文档上传与处理**：Markdown 文件上传 → `TokenTextSplitter` 分块 → `VectorStore.add()` 自动向量化存储。`DocumentEntity` 状态机管理文档全生命周期。

3. **问答服务**：`VectorStore.similaritySearch()` 检索 → 组装上下文 Prompt → `ChatClient.entity(QaResponse.class)` 结构化输出。SSE 流式端点提供打字机体验。

4. **端到端验证**：上传、问答、流式输出三条链路全部跑通。

Demo 的核心价值在于**验证了 Spring AI RAG 管线的可行性**——从文档到向量到回答，全链路用 Spring AI 内置组件完成，代码量极少。但 Demo 只处理 Markdown 且分块策略粗糙，下一篇 [02-迭代一-文档解析与向量化](02-迭代一-文档解析与向量化.md) 将引入 PDF/Word 多格式解析和智能分块策略，让系统真正能处理企业级文档。
