# LangChain4j 05 - RAG 实战（检索增强生成）

> 目标：从零搭建一个能问答本地 PDF/Markdown 文档的应用，理解 RAG 全链路。
> 前置：已完成 01-04。

---

## 1. RAG 是什么 / 为什么需要它

### 1.1 LLM 的两个根本限制

| 限制 | 表现 | RAG 怎么解决 |
|------|------|------------|
| **知识截止** | 训练数据有截止日期，不知道最新信息 | 实时检索外部资料 |
| **不知道私有知识** | 公司文档、产品手册、内部数据 LLM 都不知道 | 把私有数据检索出来塞进 prompt |

### 1.2 一句话定义

> **RAG = 检索 + 生成**。先用用户问题去知识库检索相关片段，再把片段拼到 prompt 里让 LLM 基于它回答。

### 1.3 与微调的对比

| 维度 | RAG | 微调 |
|------|-----|------|
| 适合 | 事实性知识、动态更新 | 风格、格式、特定术语 |
| 成本 | 低（无训练） | 高（GPU 训练） |
| 更新 | 加新文档即可 | 重新训练 |
| 准确率 | 取决于检索质量 | 取决于数据质量 |
| 上手难度 | ⭐⭐ | ⭐⭐⭐⭐ |

**优先用 RAG**，微调是最后手段。

---

## 2. RAG 全链路（必懂一图）

```
【离线索引阶段】（一次性，文档更新时重做）

   原始文档（PDF/Markdown/HTML）
              ↓
       DocumentReader（解析）
              ↓
       Document（统一中间格式）
              ↓
       DocumentSplitter（分块）
              ↓
       List<TextSegment>（文本片段）
              ↓
       EmbeddingModel（向量化）
              ↓
       List<Embedding>
              ↓
       EmbeddingStore（入库）


【在线问答阶段】（每次用户提问）

   用户问题
              ↓
       EmbeddingModel（向量化）
              ↓
       Embedding
              ↓
       EmbeddingStore.search（相似度检索）
              ↓
       List<TextSegment>（最相关的 K 个片段）
              ↓
       拼接进 prompt
       "根据以下上下文回答：
        {{context}}
        问题：{{question}}"
              ↓
       LLM 生成答案
```

### 2.1 五个核心组件

| 组件 | 作用 | LangChain4j 接口 |
|------|------|----------------|
| `DocumentReader` | 解析 PDF/Word/HTML | `FileSystemDocumentReader` |
| `DocumentSplitter` | 文档分块 | `DocumentSplitters` |
| `EmbeddingModel` | 文本转向量 | `EmbeddingModel` |
| `EmbeddingStore` | 向量存储与检索 | `EmbeddingStore` |
| `ContentRetriever` | 把上面整合给 AiServices 用 | `EmbeddingStoreContentRetriever` |

---

## 3. 环境准备

### 3.1 选向量库

| 方案 | 适合 | 部署复杂度 |
|------|------|----------|
| **InMemoryEmbeddingStore（本教程默认）** | 学习/原型，临时跑 | 零依赖，进程内 + JSON 持久化 |
| **Qdrant** | 生产单机/小集群 | Docker 一行，LangChain4j 1.0.1 GA 支持 |
| **pgvector** | 已有 Postgres | 装扩展 |
| **Milvus** | 大规模生产 | 完整集群部署 |
| **Chroma** | 单机原型 | ⚠️ `langchain4j-chroma:1.0.1-beta6` 有兼容性 bug，暂不推荐 |

> **本教程选 InMemoryEmbeddingStore**：`langchain4j` 主包自带、零依赖、`serializeToJson()` 持久化。学习 RAG 全链路完全够用，做项目时换 Qdrant/Milvus 即可（都实现同一个 `EmbeddingStore` 接口，业务代码不变）。

### 3.2（可选）启动 Chroma

> ⚠️ **当前不推荐**：`langchain4j-chroma:1.0.1-beta6` 按集合**名字**操作，但 Chroma 0.5.x 服务端已改用 UUID 路径，会触发 `textSegment cannot be null`。等 LangChain4j 修复后再用。

如果想试 Chroma：

```bash
docker run -d --name chroma \
  -p 8000:8000 \
  -v ./chroma-data:/chroma/chroma \
  chromadb/chroma:0.4.24
```

验证：访问 `http://localhost:8000/api/v1/heartbeat` 返回 nanoseconds 即可。

### 3.3 选 Embedding 模型

本教程**对话模型 + Embedding 模型都通过 LM Studio（OpenAI 兼容服务器）调用**，无需在 Java 进程内引入 ONNX 依赖。

| 模型（LM Studio 中加载） | 类型 | 维度 | 适合 |
|------|------|------|------|
| `text-embedding-bge-large-zh-v1.5`（**本教程默认**） | LM Studio | 1024 | 中文，效果好 |
| `text-embedding-nomic-embed-text-v1.5` | LM Studio | 768 | 英文/通用 |
| `bge-small-zh-v1.5`（进程内 ONNX 备选） | 本地 | 512 | 离线、不依赖 LM Studio |
| `text-embedding-3-small` | OpenAI API | 1536 | 省本地资源 |

> **本教程默认方案**：embedding 走 LM Studio（与对话模型共用同一个 Server），代码用 `OpenAiEmbeddingModel` 指向 `http://localhost:1234/v1`。
>
> **备选方案**：如果不想依赖 LM Studio，可用进程内 ONNX 版 `bge-small-zh-v1.5`（引入 `langchain4j-embeddings-bge-small-zh-v15` 依赖，类 `BgeSmallZhV15EmbeddingModel`）。本教程不展开。
>
> **LM Studio 准备**：
> 1. 在 LM Studio 里下载需要的对话模型（如 `qwen/qwen3.5-9b`、`deepseek/deepseek-r1-0528-qwen3-8b`）
> 2. 下载 embedding 模型 `text-embedding-bge-large-zh-v1.5`
> 3. 进入 Developer → Start Server（默认端口 `1234`）
> 4. 在 Server 中加载对话模型和 embedding 模型
> 5. 验证：`curl http://localhost:1234/v1/models` 应该能列出这两个模型

### 3.4 pom.xml 依赖

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <langchain4j.version>1.0.1</langchain4j.version>
</properties>

<dependencies>
    <!-- langchain4j 主包：含 InMemoryEmbeddingStore、DocumentSplitters、AiServices 等 -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- OpenAI 兼容协议：一个包同时支持 chat + embedding，LM Studio/DeepSeek 都用它 -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
</dependencies>
```

> **为什么只需要两个包**：
> - `FileSystemDocumentLoader`、`TextDocumentParser`、`DocumentSplitters`、`InMemoryEmbeddingStore` 都在 `langchain4j-core`（被 `langchain4j` 主包传递引入）
> - LM Studio 和 DeepSeek 都暴露 OpenAI 兼容协议，`langchain4j-open-ai` 同时提供 `OpenAiChatModel` 和 `OpenAiEmbeddingModel`
>
> **解析其他格式需要额外依赖**（默认只能解析 txt/md）：
> - PDF：`langchain4j-document-parser-apache-pdfbox`
> - docx/xlsx：`langchain4j-document-parser-apache-poi`
> - 通用兜底：`langchain4j-document-parser-apache-tika`
>
> **版本坑**：1.0.x 正式版只覆盖主线模块（`langchain4j`、`langchain4j-open-ai` 等）。
> `easy-rag` / `chroma` 等扩展模块在 1.0.x 只有 beta 版（`1.0.1-beta6`），且 `chroma` 当前有兼容性 bug。学习阶段用 InMemoryEmbeddingStore 避坑。

---

## 4. 离线索引：从文档到向量库

### 4.1 完整代码

```java
package org.demo01.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class Test01 {

    public static void main(String[] args) throws Exception {
        // 1. 加载文档
        TextDocumentParser parser = new TextDocumentParser(Charset.defaultCharset());
        Document doc = FileSystemDocumentLoader
                .loadDocument(Path.of("docs/产品手册.txt"), parser);

        // 2. 分块
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        List<TextSegment> segments = splitter.split(doc);
        System.out.println("分块数：" + segments.size());

        // 3. 向量化（通过 LM Studio 的 bge-large-zh-v1.5）
        //    强制 HTTP/1.1，规避 JDK HTTP/2 与 LM Studio 的兼容性问题
        EmbeddingModel embedModel = OpenAiEmbeddingModel.builder()
                .baseUrl("http://127.0.0.1:1234/v1")
                .apiKey("lm-studio")
                .modelName("text-embedding-bge-large-zh-v1.5")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1))
                        .readTimeout(Duration.ofSeconds(60)))
                .build();
        List<Embedding> embeddings = embedModel.embedAll(segments).content();

        // 4. 入库（InMemoryEmbeddingStore + 序列化到 JSON 文件）
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.addAll(embeddings, segments);

        // 5. 持久化到文件，供 Test02 加载
        Path indexFile = Path.of("docs/embeddings.json");
        Files.writeString(indexFile, store.serializeToJson());

        System.out.println("索引完成，共 " + embeddings.size() + " 个向量，已写入 " + indexFile);
    }
}
```

> **关于 JDK HTTP/1.1**：JDK 21 的 HttpClient 默认走 HTTP/2，与 LM Studio 本地服务在长响应上会偶发挂起。代码里强制走 HTTP/1.1 + 60s 读超时，跑起来稳定。
>
> **为什么用 InMemoryEmbeddingStore**：`langchain4j-chroma:1.0.1-beta6` 与 Chroma 0.5.x 服务端有兼容性 bug（按 collection 名字查不到数据，触发 `textSegment cannot be null`）。学习阶段 InMemoryEmbeddingStore 零依赖、JSON 持久化够用。做项目时换 Milvus / Qdrant / pgvector 即可，下游代码不变（都实现 `EmbeddingStore` 接口）。

### 4.2 每一步发生了什么

#### Step 1：加载文档
- `FileSystemDocumentLoader` 读文件内容
- 用不同 `DocumentParser` 处理不同格式：
  - `.txt/.md` → `TextDocumentParser`（在 `langchain4j-core`，开箱即用）
  - `.pdf` → 需要 `langchain4j-document-parser-apache-pdfbox`
  - `.docx` → 需要 `langchain4j-document-parser-apache-poi`
  - 通用兜底 → `langchain4j-document-parser-apache-tika`

#### Step 2：分块（最关键的环节）
**为什么要分块**：
- 文档太长（一本书几十万字），LLM 处理不了
- 检索粒度：小块更精准定位

**`DocumentSplitters.recursive(300, 30)` 含义**：
- `300`：每块目标 300 token
- `30`：相邻块之间重叠 30 token（避免在边界处割裂语义）

**分块策略对比**（参考 `reference/01-RAG深度优化.md`）：

| 策略 | 适合 |
|------|------|
| `recursive` | Markdown / 有结构的文档（默认推荐） |
| `tokenCount(300, 30)` | 纯文本，按 token 计数 |
| 语义分块 | 长文叙述，按语义切 |

#### Step 3：向量化
- `embedAll(segments)` 一次性把所有片段转成向量（批量调用，比循环 `embed` 快 10 倍）
- 向量维度由模型决定（bge-large-zh 是 1024 维）

#### Step 4：入库 + 持久化
- `addAll(embeddings, segments)` 把"向量 + 原文"一起存到内存
- `serializeToJson()` 把整个 store 序列化成 JSON 字符串写盘
- Test02 用 `InMemoryEmbeddingStore.fromFile(...)` 直接加载，跨 JVM 共享

---

## 5. 在线问答：检索 + 生成

### 5.1 完整代码

```java
package org.demo01.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

public class Test02 {

    public interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) throws Exception {
        // 1. 对话模型（这里用 DeepSeek 云端，也可以改成 LM Studio 本地）
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .modelName("deepseek-chat")
                .temperature(0.7)
                .build();

        // 2. Embedding 模型：通过 LM Studio（强制 HTTP/1.1）
        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl("http://127.0.0.1:1234/v1")
                .apiKey("lm-studio")
                .modelName("text-embedding-bge-large-zh-v1.5")
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1))
                        .readTimeout(Duration.ofSeconds(60)))
                .build();

        // 3. 向量库：从 Test01 持久化的 JSON 加载
        EmbeddingStore<TextSegment> store =
                InMemoryEmbeddingStore.fromFile(Path.of("docs/embeddings.json"));

        // 4. 检索器
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)            // 返回 top-5 片段
                .minScore(0.5)            // 相似度阈值
                .build();

        // 5. AiServices 整合 RAG
        Assistant agent = AiServices.builder(Assistant.class)
                .chatModel(model)
                .contentRetriever(retriever)   // 关键：注入检索器
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        // 6. 提问
        System.out.println(agent.chat("你们的退货政策是什么"));
    }
}
```

> **关于 API Key**：1.0.x 起 `AiServices.builder()` 用 `.chatModel(...)` 而不是 `.chatLanguageModel(...)`（旧名仍兼容但已弃用）。生产代码用 `System.getenv("DEEPSEEK_API_KEY")`，不要硬编码到源码里。
>
> **对话模型可以本地**：把 `baseUrl` 改成 `http://127.0.0.1:1234/v1`、`apiKey("lm-studio")`、`modelName(...)` 写 LM Studio 里加载的对话模型 id，就完全本地跑。

### 5.2 `ContentRetriever` 的本质

`contentRetriever(retriever)` 这一行背后发生了什么：

```
用户问："退货政策是什么？"
   ↓
LangChain4j 自动调用 retriever.retrieve(query)
   ↓
retriever 内部：
   1. embedModel.embed("退货政策是什么？") → 用户问题的向量
   2. store.search(向量, topK=5) → 找到最相关的 5 个片段
   3. 返回 List<Content>
   ↓
LangChain4j 自动把检索结果拼到 prompt：
   "基于以下信息回答问题：
    ---
    片段1: 7 天无理由退货...
    片段2: 商品需保持完好...
    ---
    问题：退货政策是什么？"
   ↓
发给 LLM 生成答案
```

**关键认知**：你**完全不需要手动拼 prompt**，`AiServices` 自动处理。

---

## 6. 完整 RAG 链路进阶配置

### 6.1 检索器调优

`EmbeddingStoreContentRetriever.Builder` 在 1.0.x 可用的方法（验证自 [源码](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/EmbeddingStoreContentRetriever.java)）：

| 方法 | 说明 |
|------|------|
| `embeddingStore / embeddingModel` | 必填，向量库 + 向量化模型 |
| `maxResults(Integer)` | top-K，默认 3 |
| `minScore(Double)` | 0~1，相似度阈值 |
| `filter(Filter)` | 按 `Metadata` 静态过滤 |
| `dynamicMaxResults(Function<Query, Integer>)` | 按问题/用户动态返回 K |
| `dynamicMinScore(Function<Query, Double>)` | 动态阈值 |
| `dynamicFilter(Function<Query, Filter>)` | 动态过滤 |
| `displayName(String)` | 多检索器时的日志标识 |
| `embeddingInputType(EmbeddingInputType)` | 区分 query/document 编码（OpenAI v3 等需要） |

> ⚠️ **1.0 已移除的旧 API**：`queryTransformer(...)`、`contentFilter(...)` 只存在于 0.x 版本，1.0 完全没有。如果看到老教程用这俩方法，是过时的。

**静态过滤示例**（按 metadata 一次过滤）：

```java
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(embedModel)
        .maxResults(5)
        .minScore(0.7)
        // 只检索部门 = "运营" 的片段
        .filter(metadataKey("department").isEqualTo("运营"))
        .build();
```

**`MetadataFilterBuilder` 常用方法**（实例方法，由 `metadataKey("...")` 返回）：

| 方法 | 含义 |
|------|------|
| `isEqualTo(v)` / `isNotEqualTo(v)` | 等于 / 不等于 |
| `isGreaterThan(v)` / `isGreaterThanOrEqualTo(v)` | 大于 / 大于等于 |
| `isLessThan(v)` / `isLessThanOrEqualTo(v)` | 小于 / 小于等于 |
| `isBetween(from, to)` | 区间 |
| `isIn(...)` / `isNotIn(...)` | 在 / 不在集合内 |
| `containsString(s)` | 字符串包含 |

多个条件用 `.and(...)` / `.or(...)` 串联（`Filter` 接口的方法）。

**动态过滤示例**（按用户身份切换部门）：

```java
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(embedModel)
        .dynamicFilter(query -> {
            // 从 chat memory 元数据里取出当前用户部门
            String dept = query.metadata().chatMemoryId().toString();
            return metadataKey("department").isEqualTo(dept);
        })
        .dynamicMaxResults(query -> {
            // 复杂问题多召回，简单问题少召回
            return query.text().length() > 30 ? 8 : 3;
        })
        .build();
```

### 6.2 元数据增强（企业 RAG 必备）

```java
// 入库时给每个片段加元数据
TextSegment segment = TextSegment.from("退货政策内容...");
segment.metadata().put("source", "产品手册.pdf");
segment.metadata().put("page", "12");
segment.metadata().put("department", "运营");
segment.metadata().put("updated_at", "2026-07-01");
```

查询时可按元数据过滤（如只搜"运营部门"的文档）。

---

## 7. RAG 的常见病与诊断

### 7.1 检索不准

**症状**：用户问 A，检索到的片段讲 B。

**诊断步骤**：
1. 开日志：`logRequests(true)`
2. 看 LangChain4j 实际拼到 prompt 里的内容是什么
3. 如果检索片段与问题确实不相关 → **检索环节问题**

**解决方案**（按优先级）：
1. 检查 embedding 模型是否适合你的语言（中文必须用中文友好模型）
2. 调整 `maxResults` 和 `minScore`
3. 优化分块策略（chunk size、overlap）
4. 引入 Query Rewriting
5. 引入重排序（rerank，详见 `reference/01-RAG深度优化.md`）

### 7.2 答案不忠实上下文（幻觉）

**症状**：检索到了正确片段，但 LLM 编造了检索中没有的内容。

**解决方案**：
```java
@SystemMessage("""
    严格根据以下上下文回答。如果上下文中没有相关信息，
    回答"根据现有资料无法回答"。不要编造。
    """)
String chat(String userMessage);
```

### 7.3 索引后内容变化但搜不到

**原因**：向量库里还是旧数据。
**解决**：重新跑索引脚本。生产环境推荐用 `update` 而非 `rebuild`（按 metadata 中的 doc_id 增量更新）。

---

## 8. 评估你的 RAG 系统

### 8.1 准备测试集

`evaluation.jsonl`：
```json
{"question":"退货政策是几天？","expected_answer":"7天无理由退货","expected_source":"产品手册.pdf"}
{"question":"保修期多久？","expected_answer":"1年","expected_source":"产品手册.pdf"}
```

### 8.2 三种评估方式

| 方式 | 简单度 | 价值 |
|------|-------|------|
| 人工抽检 20 条 | 最简单 | 起步必做 |
| Recall@K（检索准确率） | 简单 | 评估检索环节 |
| **RAGAS**（自动化评估） | 中等 | 业界标准 |

### 8.3 关键指标

- **检索 Recall@5**：前 5 个片段里包含正确答案的比例
- **答案 Faithfulness**：答案是否只基于检索到的内容（无幻觉）
- **答案 Relevance**：答案是否切题

详见 `reference/06-LLMOps.md` 的 RAGAS 章节。

---

## 9. 常见错误

### 9.1 LM Studio 连不上 / 模型名不对

```
Connection refused: localhost:1234
或
model 'qwen2.5-7b-instruct' not found
```

**排查**：
1. 浏览器访问 `http://localhost:1234/v1/models`，确认服务在线
2. 返回的 JSON 里 `id` 字段就是 `modelName` 该填的值（区分大小写、后缀）
3. LM Studio → Developer 面板里必须**已加载**模型（不只是下载）

### 9.2 Embedding 调用失败 / 模型名不对

```
{"error":{"message":"model 'text-embedding-bge-large-zh-v1.5' not found"...
```

**排查**：
1. `curl http://localhost:1234/v1/models` 看 embedding 模型是否在列表里
2. `modelName` 必须完全匹配 LM Studio 返回的 `id`（区分大小写、版本号）
3. LM Studio Server 必须正在运行（Developer 面板绿色指示灯）

### 9.3 中文检索效果差

**原因**：用了英文 embedding 模型（如 `nomic-embed-text`）。
**解决**：换中文友好的 `text-embedding-bge-large-zh-v1.5` 或 `bge-m3`。

### 9.4 LM Studio embedding 调用超时

```
java.net.http.HttpTimeoutException: request timed out
```

**原因**：JDK 21 的 HttpClient 默认走 HTTP/2，与 LM Studio 本地服务在长响应（embedding 批量请求）上偶发挂起。

**解决**：强制 HTTP/1.1 + 设置读超时：

```java
.httpClientBuilder(new JdkHttpClientBuilder()
        .httpClientBuilder(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1))
        .readTimeout(Duration.ofSeconds(60)))
```

### 9.5 `textSegment cannot be null`（Chroma SDK 不兼容）

```
IllegalArgumentException: textSegment cannot be null
```

**原因**：`langchain4j-chroma:1.0.1-beta6` 按集合**名字**操作，但 Chroma 0.5.x 服务端已改用 UUID 路径。客户端按名字查不到集合，服务端返回空，触发该校验。

**当前解决**：本教程已切换到 InMemoryEmbeddingStore + JSON 持久化，绕开此 bug。

**等修复后想用 Chroma**：关注 LangChain4j 后续版本（1.1+）对 Chroma 0.5.x 的支持，或自建 HTTP 客户端直接调 Chroma REST API。

### 9.6 `InMemoryEmbeddingStore.fromJsonString` 编译失败

```
cannot find symbol: method fromJsonString(String)
```

**原因**：1.0.x 实际方法是 `fromJson(String)` / `fromFile(Path)`，没有 `fromJsonString`。

**解决**：直接从文件加载：

```java
EmbeddingStore<TextSegment> store =
        InMemoryEmbeddingStore.fromFile(Path.of("docs/embeddings.json"));
```

---

## 10. 理解检查

1. RAG 比微调有哪些优势？什么时候用哪个？
2. 分块时为什么要 `overlap`？
3. `ContentRetriever` 和 `EmbeddingStore` 是什么关系？
4. 答案有幻觉该怎么排查？
5. 如何评估 RAG 系统的好坏？

---

## 11. 练习任务

1. 准备一个 `docs/产品手册.txt`（写 10 条产品政策）
2. 跑通 `Test01`，确认 `docs/embeddings.json` 已生成
3. 跑通 `Test02`，能问答产品政策
4. 故意问一个文档中没有的问题，观察 LLM 是否会"瞎编"
5. 加 `@SystemMessage` 限制 LLM "只基于上下文回答"
6. 准备 5 条 QA 测试集，统计检索准确率

完成后写笔记，进入 [06-流式输出](./06-流式输出.md)。
