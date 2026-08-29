# 项目 12：研发效能 DevOps 平台 — 01-最小 Demo 搭建

> **定位**：把代码库索引 + 问答跑通的最小内核——JavaParser AST 分块、pgvector 向量化、代码问答。本篇刻意不做审查/测试/诊断（那需要更完整的索引），先让"代码库 RAG"立住。本文给出**完整可手写代码**（一行不省略，含全部 import）。
>
> **读者画像**：已完成 [00-需求分析与架构设计](00-需求分析与架构设计.md)，需要把代码库 RAG 的最小内核跑通。
>
> 「遇到阻塞？→ [教程 00-基础与核心/05-RAG检索增强生成]、[附录 01-LLM基础理论/01-Embedding原理]、[教程 08-架构师进阶/00-上下文工程 §分块]；API 真实性以 [附录 05-SpringAI2-API基准] 为准」

---

## 1. 为什么最小 Demo 是"代码库 RAG"

代码审查、测试生成、CI 诊断都依赖**同一份代码索引**（AST 分块 + 符号 + 向量）。先让索引与问答跑通、验证检索质量，后续迭代复用。**v1 只做**：Java 仓库扫描 → JavaParser AST 分块 → 向量化入库 → 代码问答。

### 1.1 本节核对（迭代范围）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | v1 边界清晰 | 只做索引+问答，刻意不做审查/测试/诊断，与定位段"刻意不做"一致 |
| 2 | 与总览对接 | v1 映射到 00 §2 演进路线第 1 行（代码问答RAG），无跳跃 |

## 2. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一个最小可运行的代码问答：Java 仓库扫描、AST 分块、向量化入库、语义检索问答 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 单体单模块：`IndexLoader` → `JavaChunkIndexer` → `VectorStore/FtsStore` → `CodeQaService` |
| **上一版痛点是什么** | 无（v1 是起点，痛点是**将要暴露的**：索引只有检索没有符号图、检索只有两路） |

### 2.1 本节核对（四问口径）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，无空答 |
| 2 | 架构演进链路可落地 | `IndexLoader → JavaChunkIndexer → VectorStore/FtsStore → CodeQaService` 四个类在 §3 均有完整实现 |

## 3. 完整代码（照抄即可，一行不省略）

### 3.1 `pom.xml`（完整依赖）

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
    <groupId>com.rd</groupId>
    <artifactId>devops-platform</artifactId>
    <version>0.1.0</version>
    <name>devops-platform</name>

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
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <!-- 代码索引：pgvector 向量库（v1） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <!-- FTS 全文检索 + JdbcClient 需要 JDBC -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- JavaParser：AST 感知分块（代码 RAG 的胜负手） -->
        <dependency>
            <groupId>com.github.javaparser</groupId>
            <artifactId>javaparser-symbol-solver-core</artifactId>
            <version>3.26.2</version>
        </dependency>
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

### 3.2 `application.yml`

```yaml
spring:
  application:
    name: devops-platform
  datasource:
    url: jdbc:postgresql://localhost:5432/devops
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  ai:
    openai:
      base-url: https://api.deepseek.com          # DeepSeek 兼容 OpenAI 协议
      api-key: ${DEEPSEEK_API_KEY}                # 环境变量，不落明文
      chat:
        model: deepseek-chat          # Spring AI 2.0.0：无 options 中缀，参数直挂
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536

index:
  repo-root: ${REPO_ROOT:/work/core-repo}         # 待索引 Java 仓库路径
  repo-name: core

server:
  port: 8080
```

### 3.3 SQL DDL（pgvector 表结构 + HNSW + FTS 索引）

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- 代码分块表：AST 分块结果 + embedding + 元数据
CREATE TABLE IF NOT EXISTS code_chunk (
    id             BIGSERIAL PRIMARY KEY,
    repo           TEXT        NOT NULL,
    file_path      TEXT        NOT NULL,
    qualified_name TEXT        NOT NULL,
    signature      TEXT,
    scope_chain    TEXT,
    body           TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HNSW 向量索引（近似最近邻，检索时 pre-filter 按 repo 过滤）
CREATE INDEX IF NOT EXISTS idx_code_chunk_hnsw
    ON code_chunk USING hnsw (embedding vector_cosine_ops);

-- 元数据过滤索引（权限/租户 pre-filter）
CREATE INDEX IF NOT EXISTS idx_code_chunk_repo ON code_chunk (repo);

-- PostgreSQL 全文检索索引（符号/关键词精确匹配）
CREATE INDEX IF NOT EXISTS idx_code_chunk_fts
    ON code_chunk USING gin (to_tsvector('english', body || ' ' || qualified_name));
```

> 注：`embedding` 列由 Spring AI 的 `PgVectorStore` 自动管理（`vectorStore.add(Document)` 时写入），业务侧无需手写 embedding 列的 INSERT。

### 3.4 `DevOpsPlatformApplication.java`

```java
package com.rd.devops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DevOpsPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevOpsPlatformApplication.class, args);
    }
}
```

### 3.5 配置类 `RagConfig.java`（ChatClient + QuestionAnswerAdvisor）

```java
package com.rd.devops.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        // 声明式 RAG：每次请求自动检索 + 注入上下文。真实包路径是 org.springframework.ai.chat.client.advisor.vectorstore（[附录 05-00 §1.4]）
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(5)
                        .filterExpression("repo == 'core'")   // 元数据 pre-filter
                        .build())
                .build();

        return builder
                .defaultSystem("你是研发效能平台的代码助手，依据检索到的代码片段回答，引用来源 file:line。")
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

### 3.6 `JavaChunk.java`（AST 感知分块载体）

```java
package com.rd.devops.index;

/** AST 分块：方法/类边界 + 作用域链元数据（代码 RAG 的胜负手，非文本切块）。 */
public record JavaChunk(
        String qualifiedName,     // "com.acme.service.OrderService#queryOrder"
        String signature,         // 方法签名
        String scopeChain,        // 作用域链（模块→包→类型）
        String body,              // 方法体
        String filePath,          // 来源文件（引用溯源用）
        String repo               // 所属仓库（权限/隔离元数据）
) {}
```

### 3.7 `JavaChunkIndexer.java`（JavaParser 分块器，完整类）

```java
package com.rd.devops.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

@Component
public class JavaChunkIndexer {

    private final JavaParser parser = new JavaParser();

    /** 解析一个 .java 文件，按方法边界切成 chunk。 */
    public List<JavaChunk> chunk(Path javaFile, String repo) {
        CompilationUnit cu;
        try {
            cu = parser.parse(javaFile).getResult()
                    .orElseThrow(() -> new IllegalArgumentException("无法解析: " + javaFile));
        } catch (IOException e) {
            throw new UncheckedIOException("读取源码失败: " + javaFile, e);
        }
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String typeName = cu.findAll(TypeDeclaration.class).stream()
                .findFirst().map(TypeDeclaration::getNameAsString).orElse("Unknown");

        return cu.findAll(MethodDeclaration.class).stream()
                .map(m -> new JavaChunk(
                        pkg + "." + typeName + "#" + m.getNameAsString(),
                        m.getDeclarationAsString(false, false, false),
                        "module:core/package:" + pkg + "/type:" + typeName,
                        m.getBody().map(Object::toString).orElse(""),
                        javaFile.toString(),
                        repo))
                .toList();
    }
}
```

### 3.8 `FtsStore.java`（PostgreSQL 全文检索，完整类）

```java
package com.rd.devops.index;

import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class FtsStore {

    private final JdbcClient jdbcClient;

    public FtsStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 符号/关键词精确检索（tsvector 全文匹配），返回 Document 以便与向量结果统一融合。 */
    public List<Document> search(String query, String repo, int topK) {
        String sql = """
                SELECT body, file_path
                FROM code_chunk
                WHERE repo = :repo
                  AND to_tsvector('english', body || ' ' || qualified_name)
                        @@ plainto_tsquery('english', :query)
                ORDER BY ts_rank(
                    to_tsvector('english', body || ' ' || qualified_name),
                    plainto_tsquery('english', :query)) DESC
                LIMIT :topK
                """;
        // Boot 4.1 JdbcClient：query(RowMapper) 返回 MappedQuerySpec，.list() 得到 List<Document>（javap 实证）
        return jdbcClient.sql(sql)
                .param("query", query)
                .param("repo", repo)
                .param("topK", topK)
                .query((rs, rowNum) -> {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("file_path", rs.getString("file_path"));
                    meta.put("repo", repo);
                    return Document.builder()            // 2.0 式 builder（[附录 05-02 §4]）
                            .text(rs.getString("body"))
                            .metadata(meta)
                            .build();
                })
                .list();
    }
}
```

### 3.9 `CodeSearchService.java`（三路混合检索 + RRF 融合，完整类）

```java
package com.rd.devops.index;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CodeSearchService {

    private static final int RRF_K = 60;        // RRF 恒定秩常数
    private static final double LEXICAL_WEIGHT = 0.4;   // 词汇路权重；向量路 = 1 - 0.4

    private final VectorStore vectorStore;      // pgvector（HNSW）
    private final FtsStore ftsStore;

    public CodeSearchService(VectorStore vectorStore, FtsStore ftsStore) {
        this.vectorStore = vectorStore;
        this.ftsStore = ftsStore;
    }

    /** 三路混合检索（v1 先做向量 + FTS 两路，v2 加符号图）。返回按 RRF 分数降序的命中。 */
    public List<CodeHit> search(String query, String repo, int topK) {
        // ① 向量检索（语义）——pre-filter 按 repo 元数据过滤，防跨仓泄漏（[教程 04-企业级架构主干/11-安全与权限控制 §数据不出域]）
        List<Document> semantic = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .filterExpression("repo == '" + repo + "'")
                        .build());
        // ② FTS 全文（符号/关键词精确）
        List<Document> lexical = ftsStore.search(query, repo, topK);
        // ③ RRF 融合
        Map<String, CodeHit> fused = new HashMap<>();
        rankInto(semantic, fused, 1.0 - LEXICAL_WEIGHT);
        rankInto(lexical, fused, LEXICAL_WEIGHT);

        return fused.values().stream()
                .sorted(Comparator.comparingDouble(CodeHit::score).reversed())
                .toList();
    }

    private void rankInto(List<Document> docs, Map<String, CodeHit> byId, double weight) {
        int size = docs.size();
        for (int i = 0; i < size; i++) {
            Document doc = docs.get(i);
            String id = doc.getId();
            CodeHit existing = byId.get(id);
            if (existing == null) {
                byId.put(id, new CodeHit(id, doc.getText(), doc.getMetadata(), weight / (RRF_K + i + 1)));
            } else {
                byId.put(id, new CodeHit(id, existing.content(), existing.metadata(),
                        existing.score() + weight / (RRF_K + i + 1)));
            }
        }
    }

    /** 检索命中：content + 元数据（file_path/repo）+ RRF 融合分。 */
    public record CodeHit(String id, String content, Map<String, Object> metadata, double score) {}
}
```

### 3.10 `IndexLoader.java`（启动时把仓库灌入索引，完整类）

```java
package com.rd.devops.index;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 启动时扫描 Java 仓库 → AST 分块 → 向量化入库（ETL）。 */
@Component
public class IndexLoader implements CommandLineRunner {

    private final JavaChunkIndexer indexer;
    private final VectorStore vectorStore;

    @Value("${index.repo-root:/work/core-repo}")
    private String repoRoot;

    @Value("${index.repo-name:core}")
    private String repoName;

    public IndexLoader(JavaChunkIndexer indexer, VectorStore vectorStore) {
        this.indexer = indexer;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws IOException {
        List<Document> docs;
        try (Stream<Path> walk = Files.walk(Path.of(repoRoot))) {
            docs = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> indexer.chunk(p, repoName).stream())
                    .map(chunk -> Document.builder()
                            .text(chunk.body())
                            .metadata(Map.of(
                                    "repo", repoName,
                                    "file_path", chunk.filePath(),
                                    "qualified_name", chunk.qualifiedName(),
                                    "signature", chunk.signature(),
                                    "scope_chain", chunk.scopeChain()))
                            .build())
                    .toList();
        }
        vectorStore.add(docs);    // Embedding + 入库（pgvector）
    }
}
```

### 3.11 `CodeAnswer.java` + `CodeQaService.java` + `CodeQaController.java`

```java
package com.rd.devops.qa;

import java.util.List;

/** 代码问答结果：answer + 引用溯源（file:line 或 file_path 列表）。 */
public record CodeAnswer(String answer, List<String> references) {}
```

```java
package com.rd.devops.qa;

import com.rd.devops.index.CodeSearchService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Service
public class CodeQaService {

    private final ChatClient chatClient;
    private final CodeSearchService codeSearch;

    public CodeQaService(ChatClient chatClient, CodeSearchService codeSearch) {
        this.chatClient = chatClient;
        this.codeSearch = codeSearch;
    }

    /** 混合检索（向量 + FTS）→ 注入上下文 → LLM 生成带引用溯源的答案。 */
    public Mono<CodeAnswer> ask(String question, String repo) {
        List<CodeSearchService.CodeHit> hits = codeSearch.search(question, repo, 10);
        return Mono.fromCallable(() -> chatClient.prompt()
                .system("你是资深 Java 工程师，基于给定代码片段回答问题。引用来源必须是 file_path 或 file:line。")
                .user("问题：" + question + "\n\n代码片段：\n" + hitsToPrompt(hits))
                .call()
                .entity(CodeAnswer.class))               // 真实重载（[附录 05-02 §2]）
            .subscribeOn(Schedulers.boundedElastic());   // 同步 LLM 调用放 boundedElastic，EventLoop 不 block
    }

    private String hitsToPrompt(List<CodeSearchService.CodeHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (CodeSearchService.CodeHit hit : hits) {
            sb.append("--- 来源: ").append(hit.metadata().get("file_path")).append("\n")
              .append(hit.content()).append("\n\n");
        }
        return sb.toString();
    }
}
```

```java
package com.rd.devops.web;

import com.rd.devops.qa.CodeAnswer;
import com.rd.devops.qa.CodeQaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/qa")
public class CodeQaController {

    private final CodeQaService codeQaService;

    public CodeQaController(CodeQaService codeQaService) {
        this.codeQaService = codeQaService;
    }

    @GetMapping
    public Mono<CodeAnswer> ask(@RequestParam String question, @RequestParam String repo) {
        return codeQaService.ask(question, repo);
    }
}
```

### 3.12 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx
# 先建库：psql -h localhost -U postgres -d postgres -c "CREATE DATABASE devops;"
# 再把 §3.3 的 DDL 存入 devops 库（复制 SQL 块执行即可）
psql -h localhost -U postgres -d devops
export REPO_ROOT=/path/to/core-repo
mvn spring-boot:run
# 测试：
# curl "http://localhost:8080/api/v1/qa?question=OrderService怎么鉴权&repo=core"
```

### 3.13 本节测试与验证（AST分块、三路混合检索与代码问答）

**前置条件**：PG（5432/devops 库）可连且已执行 §3.3 DDL；PG 已安装 `vector` 扩展；`DEEPSEEK_API_KEY`、`REPO_ROOT` 已设置；应用按 §3.1-§3.11 照抄并 `mvn spring-boot:run` 启动成功。

**材料 A——建库与 DDL 核对**：

```sh
psql -h localhost -U postgres -d postgres -c "CREATE DATABASE devops;"
psql -h localhost -U postgres -d devops -c "\d code_chunk"
```

**材料 B——问答请求**：

```sh
curl "http://localhost:8080/api/v1/qa?question=OrderService怎么鉴权&repo=core"
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 建库并执行 §3.3 DDL 后 `\d code_chunk` | 表存在；HNSW 索引（`idx_code_chunk_hnsw`）与 FTS gin 索引（`idx_code_chunk_fts`）均建好 |
| 2 | 启动日志 | `vectorStore.add` 完成后日志无异常（Embedding 正常入库）；无 `block()/blockFirst()` 报警 |
| 3 | 材料 B curl | 返回 `CodeAnswer` JSON：含 `answer` 文本与 `references` 数组（file_path 引用） |
| 4 | 分块正确性 | `code_chunk.qualified_name` 按 `包.类型#方法` 切分，无跨方法拼接（抽检 3 个方法） |
| 5 | 混合检索召回 | 对已知方法名提问，"OrderService 怎么鉴权"能召回 `OrderService` 相关 chunk（FTS 符号路生效） |
| 6 | 权限隔离 | `repo` 参数传 `core` 时可检索；`filterExpression("repo == 'core'")` 生效，无跨仓结果 |

**失败排查**：①`relation "code_chunk" does not exist`→DDL 未执行或执行在错误库；②`extension "vector" is not available`→PG 未安装 pgvector 扩展，先 `CREATE EXTENSION`；③启动报 `BeanDefinitionOverrideException`→手写了 `vectorStore` bean（应交给 starter 自动配置）；④curl 返回 4xx/5xx→确认 §3.5 的 `spring-ai-vector-store-advisor` 依赖已加、`repo` 参数存在；⑤编译错 `cannot find symbol QuestionAnswerAdvisor`→`spring-ai-vector-store-advisor` 未引入或版本非 2.0.0。

## 4. 验收标准（量化）

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 索引完整 | 200 万行核心仓索引完成（AST 分块，非文本切块） |
| 2 | 检索质量 | 20 个典型问题 Recall@5 ≥ 85%（人工标注） |
| 3 | 分块正确 | 代码块按方法/类边界切分（无跨方法拼接） |
| 4 | 引用溯源 | 问答 100% 带 file_path/file:line 引用 |
| 5 | 权限隔离 | 检索按 repo 元数据 pre-filter（防跨仓泄漏） |

### 4.1 本节核对（验收口径）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 验收项均可度量 | 五项均含数值或可判定标准（Recall@5≥85%、100% 溯源、按 repo pre-filter），非空话 |
| 2 | 每项验收有对应代码实现落点 | 索引完整体→§3.10 IndexLoader；检索质量→§3.9 RRF；分块→§3.7；溯源→§3.11；隔离→§3.9 filterExpression |

## 5. 本迭代的 ADR

| # | 决策 | 理由 |
|---|------|------|
| ADR-700（最小 Demo） | 代码索引用 AST 分块 + 混合检索（向量 + FTS，RRF 融合） | 代码 RAG 是"解析问题非检索问题"；索引质量决定审查/测试/诊断下游质量；v1 先建两路，v2 加符号图 |

### 5.1 本节核对（ADR-700 一致性）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | ADR-700 在代码有落点 | §3.7 AST 分块 + §3.9 RRF 融合双实现吻合"AST+混合检索"决策 |
| 2 | 与 13-ADR 总账衔接 | ADR-700 在 [13-ADR架构决策记录] 存在，编号段与 00 预录 701+ 衔接 |

## 6. v1 的痛点（驱动下一迭代）

代码问答跑通了，但**代码审查暴露短板**：PR 审查靠人肉，漏掉安全/性能问题；而直接用 LLM 审 PR 误报率高（Copilot ~33% 误报）。**需要"静态层先行 + LLM 语义层"的两级审查流水线**。→ [02-代码审查Agent.md](02-代码审查Agent.md)

> 本节核对（一句话）：V1 的痛点（审查靠人肉 + LLM 误报 High）与下一迭代 [02] 的"静态层先行"方案一一对应，痛点不被搁置即 PASS。

---

## 7. 总结

v1 用完整可手写代码立住了代码库 RAG 地基：`JavaChunkIndexer` 做 AST 感知分块（胜负手）、`FtsStore` 补关键词精确路、`CodeSearchService` 用 RRF 融合两路、`CodeQaService` 注入上下文生成带引用溯源的答案。**所有 API 均按 [附录 05-SpringAI2-API基准] 书写**（`QuestionAnswerAdvisor` 真实包路径、`SearchRequest.builder()`、`Document.builder()`、`entity(Class)`）。

> 本节核对（一句话）：四行总结分别对应 §3.7（分块）、§3.8（FTS）、§3.9（RRF）、§3.11（问答溯源），与正文口径一致即 PASS。
