# L3 工程化实战 - RAG 工程化（Spring AI 2.0）

> 本文不是一篇把所有概念一次性铺开的"参考手册"，而是按**真实企业项目从零到上线的迭代顺序**写的实战手册。
>
> 每一章都遵循同一个节奏（**五步走**）：
> 1. **痛点**：为什么需要这一章
> 2. **最小实现**：贴代码，让你能跑
> 3. **验证**：curl / 接口调用 + 期望输出
> 4. **对照**：上一步 vs 这一步，差异在哪
> 5. **避坑**：这一章常踩的雷
>
> **不要跳章**：每章都是后一章的前置。第 1 章用 100 行代码让"问 PDF 一个问题"跑通，第 9 章把它升级成生产系统。中间 7 章每章只做一件事——让效果更好一点。
>
> **代码作参考答案**：所有代码块都需要你**手动**在 `org.demo02.rag.*` 下实现，不要直接复制。
> **调研日期**：2026-07-17
> **依赖**：Spring Boot 4.x、Spring AI 2.0.0 GA、JDK 21

---

## 0. 全文地图

```
第 1 章  一键跑通：用 SimpleVectorStore + QuestionAnswerAdvisor 问 PDF
第 2 章  换生产级存储：PgVector + Redis（docker run 起来）
第 3 章  提升召回 1：把文档切好（Ingestion 优化）
第 4 章  提升召回 2：查询改写（Query Processing）
第 5 章  提升召回 3：混合检索（Hybrid Retrieval，BM25 + 向量）
第 6 章  提升精度：Rerank + 去重
第 7 章  产品级体验：引用溯源（让 LLM 说 "根据 [Doc3]"）
第 8 章  让系统不崩：评估闭环 + 量化对比
第 9 章  上生产：增量索引 / 多租户 / 缓存 / 监控
```

**RAG 系统的 6 个工程模块**（不必现在背，每章会用到对应模块）：

| 模块 | 职责 | 第几章用 |
|------|------|---------|
| 1. Ingestion | 切分文档、加 metadata | 第 1 章初识、第 3 章深入 |
| 2. Embedding | 文本转向量 | 第 1 章初识 |
| 3. Query Processing | 改写 / 扩展 / 路由 query | 第 4 章 |
| 4. Retrieval | 向量 + BM25 + 元数据过滤 | 第 1 章初识、第 5 章深入 |
| 5. Rerank & Filter | 重排、去噪、去重 | 第 6 章 |
| 6. Augmentation & Generation | 拼 prompt + 引用溯源 + 生成 | 第 1 章初识、第 7 章深入 |

---

## 第 1 章 一键跑通：5 分钟问 PDF 一个问题

### 1.1 痛点

"我想做一个能读 PDF、回答 PDF 内容的 AI 助手"——这是 90% 学习 RAG 的人的起点。

但你打开 Spring AI 文档，会被 `RetrievalAugmentationAdvisor` / `QueryTransformer` / `DocumentPostProcessor` 一堆概念砸晕：**我没有要这么多东西，我只是想问一句话。**

所以这一章我们**只做一件事**：用最少的代码、最简单的内存向量库、最快的 advisor，让"问 PDF 一个问题"这条链路跑通。能跑通，再谈优化。

### 1.2 最小实现

#### 1.2.1 加依赖

```xml
<!-- pom.xml -->
<!-- Spring AI 2.0 简化版 RAG advisor（一行装好"检索 + 拼 prompt"） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-vector-store-advisor</artifactId>
</dependency>

<!-- 文档解析：PDF -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
</dependency>
```

> SimpleVectorStore 是 `spring-ai-vector-store-advisor` 自带的内存实现，**不需要 PgVector / Redis**——所以这一章连 docker 都不用起。

#### 1.2.2 application.yaml（最小配置）

```yaml
# src/main/resources/application.yaml
spring:
  application:
    name: rag-demo
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${OPENAI_CHAT_MODEL:gpt-4o-mini}
          temperature: 0.0
      embedding:
        options:
          model: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
```

#### 1.2.3 装一个 SimpleVectorStore Bean

```java
// org/demo02/rag/config/RagConfig.java
package org.demo02.rag.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```

#### 1.2.4 摄入 PDF（最朴素的版本）

第 1 章先**不做切分优化、不加 metadata**——PDF 按页读出来直接塞进向量库。

```java
// org/demo02/rag/ingestion/SimpleIngestionService.java
package org.demo02.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SimpleIngestionService {

    private final VectorStore vectorStore;

    public SimpleIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int ingestPdf(Resource pdf) {
        // 1. 读 PDF（按页）
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdf);
        List<Document> pages = reader.get();

        // 2. 切分（用框架自带的 TokenTextSplitter，先不管参数）
        List<Document> chunks = new TokenTextSplitter().split(pages);

        // 3. 向量化 + 入库（VectorStore 内部会调 EmbeddingModel）
        vectorStore.add(chunks);
        return chunks.size();
    }
}
```

#### 1.2.5 问答接口（用 QuestionAnswerAdvisor）

```java
// org/demo02/rag/controller/RagController.java
package org.demo02.rag.controller;

import org.demo02.rag.ingestion.SimpleIngestionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.VectorStoreChatMemoryAdvisor;  // ← 暂时不用
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final SimpleIngestionService ingestionService;

    public RagController(ChatClient.Builder builder,
                         SimpleIngestionService ingestionService) {
        // 装配 QuestionAnswerAdvisor：检索 + 拼 prompt 一步到位
        this.chatClient = builder
                .defaultAdvisors(new QuestionAnswerAdvisor(
                        // VectorStore 通过构造器注入下面补
                        null, null))
                .build();
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam String path) {
        int n = ingestionService.ingestPdf(new FileSystemResource(path));
        return Map.of("chunks", n);
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestParam String q) {
        String answer = chatClient.prompt().user(q).call().content();
        return Map.of("answer", answer);
    }
}
```

> ⚠️ 上面 `new QuestionAnswerAdvisor(null, null)` 是占位，**正确做法是把 VectorStore 注入进来**：

```java
// 正确的 RagController（替换 1.2.5 整段）
@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final SimpleIngestionService ingestionService;

    public RagController(ChatClient.Builder builder,
                         VectorStore vectorStore,
                         SimpleIngestionService ingestionService) {
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).build();
        this.chatClient = builder.defaultAdvisors(qaAdvisor).build();
        this.ingestionService = ingestionService;
    }
    // ... /ingest 和 /ask 同上
}
```

### 1.3 验证

启动应用，准备一个 PDF（比如随便一个产品手册 PDF 放在 `/tmp/test.pdf`）。

```bash
# 1. 摄入 PDF
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/test.pdf'
# 期望输出：{"chunks":47}

# 2. 问一个问题（必须是 PDF 里能找到答案的）
curl -X POST 'http://localhost:8080/rag/ask?q=这个产品的退款政策是什么'
# 期望输出：{"answer":"根据文档，该产品支持 7 天无理由退款..."}
```

**如果回答里出现了 PDF 内容 → 跑通了**。

### 1.4 对照（这步做了什么）

| 项 | 第 1 章 |
|----|--------|
| 向量库 | `SimpleVectorStore`（内存，重启丢） |
| Advisor | `QuestionAnswerAdvisor`（检索 + 拼 prompt 合一） |
| 切分 | `TokenTextSplitter` 默认参数 |
| Metadata | 没有 |
| 查询改写 | 没有 |
| 引用溯源 | 没有 |
| 代码量 | ~80 行 |

**够用，但只能 demo**。第 2 章开始解决"重启数据丢"。

### 1.5 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| `OPENAI_API_KEY` 报错 | 没设环境变量 | `export OPENAI_API_KEY=sk-xxx`，重启应用 |
| 启动慢、卡在 HikariPool | 没用到数据库，是其他自动装配 | 第 1 章确实不需要 datasource，确认 pom 没引入 jdbc starter |
| 摄入报 429 | OpenAI 限流 | 文档小一点，或者加 `spring.ai.openai.embedding.options.retry` |
| `answer` 是泛泛而谈、不引用 PDF | topK 太少 / 切分太粗 | 这是第 3 章要解决的问题，先跳过 |
| `/tmp/test.pdf` 找不到 | 路径权限 | 改成绝对路径，或放到 `src/main/resources/` 用 `classpath:` 前缀 |

---

## 第 2 章 换生产级存储：PgVector + Redis

### 2.1 痛点

第 1 章的 `SimpleVectorStore` 把所有向量存在 JVM 内存里：
- 应用一重启 → 数据全丢
- 文档量大了 → JVM OOM
- 多实例部署 → 数据不一致

**生产必须用持久化的向量库**。本章用 PostgreSQL + pgvector（生产首选）+ Redis（缓存层）。

### 2.2 启动三方组件（docker run 一键起）

本文用到的三方组件：
- **PostgreSQL + pgvector + pg_trgm**：向量存储 + 全文检索（第 5 章混合检索用）
- **Redis**：Embedding 缓存、Query 改写缓存（第 9 章用）
- **OpenAI API**：Chat 模型 + Embedding 模型（不需本地部署，配 API key 即可）

> 假设本地装好了 Docker。验证：`docker --version` 能打印版本号即可。两个镜像都从 Docker Hub 公开仓库拉，不需要登录。
> 数据**挂载到本地路径**（不是命名卷）：删容器 / 升级镜像后数据都在，方便备份和迁移。

> 📌 **路径占位约定**：下面命令里的 `<DOCKER_DATA_ROOT>` 替换为你本机的 docker 数据根目录。例如作者本机是 `/Volumes/data/software/docker/containers`，读者按自己的实际目录替换即可。

#### 2.2.1 准备本地挂载目录

```bash
mkdir -p <DOCKER_DATA_ROOT>/postgres
mkdir -p <DOCKER_DATA_ROOT>/redis
```

> **如果你已经有一个 postgres 容器在跑**（用 `postgres:latest` 镜像，不含 pgvector），按 §2.2.2 的步骤删旧容器、清空目录、换 pgvector 镜像重起。空库可以直接清目录；非空库请先 `pg_dumpall` 备份。

#### 2.2.2 启动 PostgreSQL（含 pgvector）

`pgvector/pgvector:pg16` 是 pgvector 官方维护的镜像，基于 PostgreSQL 16，**预装 `vector` 和 `pg_trgm` 两个扩展**，开箱即用。

**如果你已经有 postgres 容器在跑**（用 `postgres:latest` 镜像，不含 pgvector），按下面三步切换：

```bash
# 1. 删掉旧容器（数据目录保留）
docker rm -f postgres

# 2. 清空旧数据目录（⚠️ 仅当确认 postgres 是空库时才能清；非空先 pg_dumpall 备份）
rm -rf /Volumes/data/software/docker/containers/postgres/*

# 3. 用 pgvector 镜像重起（同样的容器名 + 同样的挂载目录 + 同样的端口）
docker run -d \
--name postgres \
--restart unless-stopped \
-e POSTGRES_DB=rag \
-e POSTGRES_USER=postgres \
-e POSTGRES_PASSWORD=postgres \
-e TZ=Asia/Shanghai \
-e PGDATA=/var/lib/postgresql/data/pgdata \
-p 5432:5432 \
-v /Volumes/data/software/docker/containers/postgres:/var/lib/postgresql/data \
pgvector/pgvector:pg17
```

**首次启动（没有旧容器）**，直接跑第三步即可。

参数说明：
- `-d` 后台运行
- `--restart unless-stopped`：开机 / 异常重启自动拉起，手动 stop 不会被拉起
- `POSTGRES_DB / USER / PASSWORD`：建库建用户，应用用这套连接
- `-p 5432:5432`：暴露 5432 端口到本机；**端口冲突**改成 `-p 15432:5432`，yaml 里端口同步改
- `-v <host path>:<container path>`：**bind mount 到本地路径**——容器删了 / 升级镜像后数据都在

> **为什么必须清空旧数据目录**：如果旧目录被 `postgres:latest`（可能是 PG15/PG17）初始化过，pgvector 镜像（PG16）启动时会拒绝读不兼容版本的 data 目录。空库直接 `rm -rf` 最干净。

#### 2.2.3 启动 Redis

`redis:7-alpine` 是 Redis 官方精简版：

```bash
docker run -d \
  --name redis \
  --restart unless-stopped \
  -p 6379:6379 \
  -v /Volumes/data/software/docker/containers/redis:/data \
  redis:8.8.0 \
  redis-server --appendonly yes   --requirepass root
```

`redis-server --appendonly yes` 开启 AOF 持久化（重启不丢缓存）。生产要加密码：把最后两行换成
`redis:7-alpine redis-server --requirepass YOUR_PWD --appendonly yes`，yaml 里同步加 `spring.data.redis.password`。

#### 2.2.4 验证容器状态

```bash
docker ps --filter name=postgres --filter name=redis
# STATUS 列应为 Up xxx

docker logs postgres
# 看到 "database system is ready to accept connections" 即就绪

docker logs redis
# 看到 "Ready to accept connections" 即就绪
```

#### 2.2.5 装 pgvector / pg_trgm 扩展

进入 PostgreSQL 跑两条 SQL（首次必须执行，Spring AI 才能用 pgvector）：

```bash
docker exec -it postgres psql -U rag -d rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker exec -it postgres psql -U rag -d rag -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

验证扩展装好：

```bash
docker exec -it postgres psql -U rag -d rag -c "\dx"
# 期望输出包含 vector 和 pg_trgm 两行
```

验证 Redis 通：

```bash
docker exec -it redis redis-cli ping
# 期望输出 PONG
```

#### 2.2.6 启动后：补 GIN 索引（第 5 章混合检索用）

Spring AI 应用第一次跑起来会自动建 `vector_store` 表。**应用启动过一次后**再补这条索引（用于第 5 章 `similarity()` 加速）：

```bash
docker exec -it postgres psql -U postgres -d rag -c \
    "CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm ON vector_store USING gin (content gin_trgm_ops);"
```

> 如果表还没建（应用没跑过），这条会报 `relation "vector_store" does not exist`——先跑应用让它建表，再回来补索引。

#### 2.2.7 停止 / 重启 / 清理

```bash
# 停止（数据保留在本地挂载目录里）
docker stop postgres redis

# 重新启动
docker start postgres redis

# 删容器（本地数据目录还在）
docker rm -f postgres redis

# 彻底清数据（⚠️ 向量库数据会丢）
rm -rf /Volumes/data/software/docker/containers/postgres/*
rm -rf /Volumes/data/software/docker/containers/redis/*
```

#### 2.2.8 常见启动报错速查

| 报错 | 原因 | 解决 |
|------|------|------|
| `Bind for 0.0.0.0:5432 failed: port already allocated` | 本机已装 PG / 上次容器没关 | 改 `-p 15432:5432`，yaml 里也改；或 `docker rm -f` 占用容器 |
| `password authentication failed for user "rag"` | 旧库密码不一致 | `docker rm -f postgres && rm -rf <DOCKER_DATA_ROOT>/postgres/*` 再重 run |
| `connection refused localhost:5432` | 容器还没 ready | `docker logs postgres` 看到 `database system is ready` 再起应用 |
| `permission denied` 挂载目录报错 | 宿主目录权限不对 | macOS 一般无此问题；Linux 需 `chown -R 999:999 <host path>` |
| `extension "vector" does not exist` | 镜像不对 | 检查镜像名是 `pgvector/pgvector:pg16` 不是 `postgres:16` |
| `pg_ctl: could not start server` | 旧数据目录版本不兼容 | 清空 `<DOCKER_DATA_ROOT>/postgres/*` 再 run |

### 2.3 改 pom（加 PgVector starter）

```xml
<!-- 在第 1 章 pom 基础上新增 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 2.4 改 yaml（加 datasource + pgvector 配置）

```yaml
# src/main/resources/application.yaml（在第 1 章基础上新增）
spring:
  application:
    name: rag-demo

  datasource:
    # 端口与 §2.2.2 的 -p 一致
    url: jdbc:postgresql://localhost:5432/rag
    username: rag
    password: rag_pwd
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10

  data:
    redis:
      host: localhost
      port: 6379
      # password: ${REDIS_PASSWORD:}
      timeout: 2s

  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${OPENAI_CHAT_MODEL:gpt-4o-mini}
          temperature: 0.0
      embedding:
        options:
          model: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}
    vectorstore:
      pgvector:
        table-name: vector_store
        # 维度必须和 embedding 模型对齐：
        #   text-embedding-3-small = 1536
        #   text-embedding-3-large = 3072
        #   bge-large-zh-v1.5（Ollama / 自建）= 1024
        dimensions: 1536
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        initialize-schema: true   # 启动自动建表/索引；生产建议 false，DBA 管

logging:
  level:
    org.springframework.ai: INFO
    org.springframework.jdbc.core: WARN
```

### 2.5 改 RagConfig（去掉 SimpleVectorStore）

```java
// org/demo02/rag/config/RagConfig.java
// 改成这样（PgVectorStore 由 starter 自动装配，不再手写 Bean）
@Configuration
public class RagConfig {
    // 空：所有 Bean 由 starter 自动装配
}
```

> `spring-ai-starter-vector-store-pgvector` 会自动注入一个 `VectorStore` Bean（类型是 `PgVectorStore`），第 1 章的所有 Service / Controller **不用改一行代码**。

### 2.6 OpenAI 环境变量

```bash
# macOS / Linux
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_BASE_URL=https://api.openai.com
export OPENAI_CHAT_MODEL=gpt-4o-mini
export OPENAI_EMBEDDING_MODEL=text-embedding-3-small

# Windows PowerShell
$env:OPENAI_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
```

**国内访问**：OpenAI 官方 API 在国内需代理。可改 `OPENAI_BASE_URL` 为兼容 OpenAI 协议的国内服务（通义/智谱/DeepSeek/月之暗面），按各家文档填对应 `base_url` 和 `model`。

> ⚠️ **维度对齐陷阱**：yaml `dimensions` 必须**严格等于** embedding 模型输出维度，否则写入报 `dimension mismatch`。换 embedding 模型时要么换表，要么 `rm -rf <DOCKER_DATA_ROOT>/postgres/*` 彻底重建。

### 2.7 验证

```bash
# 1. 启动应用，确认 PgVectorStore 自动建表
docker exec -it postgres psql -U rag -d rag -c "\dt"
# 期望输出包含 vector_store 表

# 2. 重新摄入 PDF
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/test.pdf'
# {"chunks":47}

# 3. 重启应用（关键验证：数据不丢）
# ⌘C 停掉应用，再 mvn spring-boot:run

# 4. 再问同样的问题
curl -X POST 'http://localhost:8080/rag/ask?q=这个产品的退款政策是什么'
# 期望：还能回答出来（数据在 PostgreSQL 里）

# 5. 看 PostgreSQL 里到底存了什么
docker exec -it postgres psql -U rag -d rag \
    -c "SELECT id, LEFT(content, 60) AS preview FROM vector_store LIMIT 5;"
```

### 2.8 对照

| 项 | 第 1 章 | 第 2 章 |
|----|--------|--------|
| 向量库 | SimpleVectorStore（内存） | **PgVectorStore（PostgreSQL 持久化）** |
| 重启行为 | 数据全丢 | **数据保留** |
| 缓存 | 无 | Redis 已就绪（第 9 章用） |
| 切分 / Metadata | 还是默认 | 还没动（第 3 章处理） |
| 代码改动 | - | pom + yaml + 删一个 Bean |

**Service / Controller 一行没改**——这就是抽象的好处。

### 2.9 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| 启动报 `relation "vector_store" already exists` | 之前用别的维度建过 | `DROP TABLE vector_store;` 再启动，或把 `initialize-schema: false` 后手动管理 |
| 启动慢、卡在 HikariPool | datasource 配置错 | 看 §2.4，url/user/password 要与 docker run 一致 |
| 写入报 `dimension mismatch` | yaml `dimensions` 与 embedding 模型维度不一致 | 对齐维度（§2.6 警告），删表重建 |
| `relation "vector_store" does not exist` | 应用没跑过、表没建 | 启动一次应用让 Spring AI 建表 |
| 中文检索召回差 | PgVectorStore 默认不带分词器 | 第 5 章混合检索解决 |
| 重启后还能问但变慢 | HNSW 索引加载 | 第一次查询会构造图，慢一点正常 |

---

## 第 3 章 提升召回 1：把文档切好（Ingestion 优化）

### 3.1 痛点

第 2 章能跑通，但你会发现：
- 问"产品 A 的退款政策" → 答的是产品 B 的（**召回错文档**）
- 问"合同第 7 条" → 答的笼统，找不到第 7 条（**切分太粗，第 7 条和别的条款黏在一起**）
- 问"2024 年的数据" → 把 2023 的也召回了（**没有 metadata 过滤**）

切分是 RAG 系统中**对效果影响最大、最容易被忽视**的环节。同样的文档、同样的模型，不同切分策略的 Recall@5 差距能到 30%+。

### 3.2 切分策略对照表

| 策略 | 实现 | 优点 | 缺点 | 适用 |
|------|------|------|------|------|
| 固定字符切分 | 每 1000 字一刀 | 简单 | 切断句子/段落 | demo（第 1 章够用） |
| 固定 token 切分 | 每 500 token 一刀 | 适配模型限制 | 同上 | demo（`TokenTextSplitter`） |
| **递归字符切分** | 优先按段落→句子→字符递归 | 保持语义边界 | 实现稍复杂 | **本章默认** |
| 按文档结构切分 | 按 Markdown 标题/HTML 标签 | 保持文档语义 | 依赖文档格式 | 结构化文档 |
| 语义切分 | 用 Embedding 找语义断点 | 最高质量 | 慢、贵 | 高质量场景 |
| Agentic Chunking | LLM 决定切分点 | 最聪明 | 贵、不可控 | 实验中 |

**实战建议**：默认用递归字符切分 + overlap；特殊文档（PDF 论文、Markdown）用结构切分。

### 3.3 最小实现：自写递归字符切分器

Spring AI 自带的 `TokenTextSplitter` 只支持固定 token 切分，本章我们自写一个递归版本。

```java
// org/demo02/rag/ingestion/SmartDocumentSplitter.java
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
        var meta = new java.util.HashMap<>(origin.getMetadata());
        meta.put("chunk_length", text.length());
        return Document.builder().text(text).metadata(meta).build();
    }
}
```

### 3.4 加 Metadata（企业级 RAG 必备）

Metadata 是后续做 **过滤检索** 的关键。比如"只在 2024 年的合同里搜"、"只搜 HR 部门的文档"。

```java
// org/demo02/rag/ingestion/MetadataEnricher.java
package org.demo02.rag.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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

### 3.5 ETL 入口（替换第 1 章的 SimpleIngestionService）

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

        // 4. 向量化 + 入库
        vectorStore.add(enriched);
        return enriched.size();
    }
}
```

### 3.6 改 Controller（带 metadata 参数）

```java
// 改 RagController 的 ingest 方法
@PostMapping("/ingest")
public Map<String, Object> ingest(@RequestParam String path,
                                    @RequestParam(defaultValue = "unknown") String source,
                                    @RequestParam(defaultValue = "general") String department) {
    int n = ingestionService.ingestPdf(new FileSystemResource(path), source, department);
    return Map.of("chunks", n, "source", source, "department", department);
}
```

> 注意：第 2 章已经把 `SimpleIngestionService` 替换成 `DocumentIngestionService`，记得同步改 `RagController` 的构造器注入类型。

### 3.7 验证

```bash
# 摄入 HR 部门的产品手册
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/test.pdf&source=hr-handbook-2024.pdf&department=hr'

# 看数据库里的 metadata
docker exec -it postgres psql -U rag -d rag \
    -c "SELECT LEFT(content, 40), metadata->>'department' AS dept, metadata->>'source' AS src FROM vector_store LIMIT 5;"

# 期望输出：每条 chunk 都带 department=hr、source=hr-handbook-2024.pdf

# 问问题（注意效果对比：第 2 章同样的 PDF 切分粗，可能找不到；第 3 章切细了，能找到）
curl -X POST 'http://localhost:8080/rag/ask?q=试用期多长时间'
# 期望：能从 HR 手册里召回具体条款
```

### 3.8 对照

| 项 | 第 2 章 | 第 3 章 |
|----|--------|--------|
| 切分 | `TokenTextSplitter` 默认 | **递归字符切分（800/150）** |
| 切分质量 | 切断句子 | **保持语义边界** |
| Metadata | 无 | **source / department / ingested_at / version** |
| 过滤检索 | 不能 | **第 9 章多租户用** |

### 3.9 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| 切完的 chunk 太碎（每条 100 字） | targetSize 设太小 | 800-1200 是常用区间 |
| chunk 还是很长 | PDF 是扫描件、文本提取失败 | 用 OCR 库预处理，Spring AI 的 PDF reader 不做 OCR |
| Metadata 没存进去 | `Document.builder()` 时 metadata 没传 | 看 `MetadataEnricher` 是否真的传到了 builder |
| 检索时按 metadata 过滤报错 | 过滤表达式语法错 | 第 9 章细讲，先用 `department == 'hr'` 这种简单表达式 |
| 同一个 PDF 摄入两次，chunk 翻倍 | 没做幂等 | 第 9 章增量索引解决，先用 `vectorStore.delete(...)` 清理 |

---

## 第 4 章 提升召回 2：查询改写（Query Processing）

### 4.1 痛点

用户问"上次那个 bug 怎么解决的"——这种 query 直接 embedding 召回质量极差：用户的口语和文档的书面语差异大。

更常见的痛点：
- 用户问"Spring AI 怎么用"——太通用，召回大量噪音
- 用户问"订单状态怎么查"——但文档里写的是"查询订单"，**同义词**召回不到
- 用户用缩写、口语、错别字

**Query 改写**就是用 LLM 把用户的烂 query 改成适合检索的 query。

### 4.2 Spring AI 自带的 RewriteQueryTransformer

Spring AI 提供了 `RewriteQueryTransformer`，一行装配就能用：

```java
// 在 RagConfig 里装配
@Bean
public RewriteQueryTransformer rewriteQueryTransformer(ChatClient.Builder builder) {
    return RewriteQueryTransformer.builder()
            .chatClientBuilder(builder.build().mutate())
            .build();
}
```

但 `QuestionAnswerAdvisor` **不支持插 query transformer**——它是个简化封装。从这一章开始，我们换用更强大的 `RetrievalAugmentationAdvisor`。

### 4.3 最小实现：换用 RetrievalAugmentationAdvisor

#### 4.3.1 RagConfig 改装

```java
// org/demo02/rag/config/RagConfig.java
package org.demo02.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrie.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.rag.preretrie.query.retrieval.VectorStoreDocumentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public RetrievalAugmentationAdvisor ragAdvisor(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder) {

        ChatClient chatClient = chatClientBuilder.build();

        return RetrievalAugmentationAdvisor.builder()
                // 模块 3：查询改写
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(chatClient.mutate())
                        .build())
                // 模块 4：检索（先用最简单的向量检索，第 5 章换混合）
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(5)
                        .build())
                // 模块 6：拼 prompt（先用默认的 ContextualQueryAugmenter，第 7 章换带引用的）
                .build();
    }
}
```

#### 4.3.2 改 RagController（用新的 advisor）

```java
// org/demo02/rag/controller/RagController.java
@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final DocumentIngestionService ingestionService;

    public RagController(ChatClient.Builder builder,
                         RetrievalAugmentationAdvisor ragAdvisor,
                         DocumentIngestionService ingestionService) {
        this.chatClient = builder.defaultAdvisors(ragAdvisor).build();
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestParam String q) {
        long start = System.currentTimeMillis();
        String answer = chatClient.prompt().user(q).call().content();
        long elapsed = System.currentTimeMillis() - start;
        return Map.of("answer", answer, "elapsed_ms", elapsed);
    }

    // /ingest 同第 3 章
}
```

#### 4.3.3 更进阶：自写多查询改写（RAG-Fusion 思路）

让 LLM 把一个 query 改成 3 个不同视角的子 query，每个都去检索，结果合并去重——这是 RAG-Fusion 的核心思想。

```java
// org/demo02/rag/retrieval/MultiQueryTransformer.java
package org.demo02.rag.retrieval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrie.query.transformation.QueryTransformer;
import org.springframework.stereotype.Component;

@Component
public class MultiQueryTransformer implements QueryTransformer {

    private final ChatClient chatClient;

    public MultiQueryTransformer(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Query transform(Query query) {
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
        return Query.builder().text(rewritten.trim()).build();
    }
}
```

> 注意：`MultiQueryTransformer` 是本文设计的简化版，真正 RAG-Fusion 需要在 retriever 层做并行检索 + RRF 融合。完整实现见第 5 章。

### 4.4 验证

```bash
# 用一个口语化的、和文档原话不完全一致的 query
curl -X POST 'http://localhost:8080/rag/ask?q=假期怎么请'
# 文档原文可能是"年假申请流程"——没改写前可能召回不到，改写后能召回

# 看日志里的改写过程
# 应该看到 OpenAI 被调用了两次：第一次改写、第二次回答
```

### 4.5 对照

| 项 | 第 3 章 | 第 4 章 |
|----|--------|--------|
| Advisor | QuestionAnswerAdvisor | **RetrievalAugmentationAdvisor**（可插每个模块） |
| Query 改写 | 无 | **RewriteQueryTransformer / MultiQueryTransformer** |
| 调用 LLM 次数 | 1 次 | **2 次**（改写 + 回答），延迟翻倍 |
| 召回质量 | 看运气 | **稳定提升**（口语化场景） |

**Trade-off**：改写多调一次 LLM，单请求延迟从 1s 涨到 2s+。生产里用 Redis 缓存改写结果（第 9 章）。

### 4.6 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| 改写后的 query 完全偏离原意 | prompt 不够约束 | 加约束："不要扩展话题，只换表达方式" |
| 改写完反而召回更差 | LLM 把同义词换成了文档里没有的词 | 多查询改写（MultiQueryTransformer）+ RRF 融合 |
| 每次都调 LLM 太慢 | 没缓存 | 第 9 章 Redis 缓存改写结果 |
| `RewriteQueryTransformer` 报 NPE | `chatClientBuilder` 传错 | 必须传 `chatClient.mutate()`，不能直接传 builder |
| 答非所问 | 改写 + 检索 + 拼装链路太长 | 一步步日志验证：先看改写输出，再看检索结果，最后看回答 |

---

## 第 5 章 提升召回 3：混合检索（Hybrid Retrieval）

### 5.1 痛点

向量检索强在"语义"，但对**精确关键词**召回差：

| 场景 | 向量检索表现 | BM25 表现 |
|------|------------|----------|
| "Spring AI 怎么用" | ✅ 好 | ❌ 太通用，召回大量噪音 |
| "包含 `getConversationId` 的代码" | ❌ 不知道这是代码 | ✅ 精确匹配关键词 |
| "怎么处理 ABC-1234 这个 bug" | ❌ 编号无语义 | ✅ 精确 |
| "RAG 系统的常见问题" | ✅ 好 | ⚠️ 一般 |

**结论**：向量检索强在"语义"、BM25 强在"关键词精确匹配"，**两者融合效果最好**。这是企业级 RAG 的标配。

### 5.2 PgVector 全文检索基础

PgVector 0.7+ 支持同时做向量检索和全文检索（pg_trgm + ts_vector），是一个简单的混合检索方案。

第 2 章已经装了 pg_trgm 扩展，第 2.2.6 节补了 GIN 索引，现在直接能用。

### 5.3 最小实现：自写 Hybrid Retriever

实现 `DocumentRetriever` 接口，内部并行调向量检索 + BM25 模拟，用 **RRF（Reciprocal Rank Fusion）** 融合结果。

```java
// org/demo02/rag/retrieval/HybridDocumentRetriever.java
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
        // 1. 向量检索 top topK*2 个（取多冗余，融合后缩到 topK）
        List<Document> vecDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query.text()).topK(topK * 2).build()
        );

        // 2. BM25 模拟（用 pg_trgm 相似度，简化版；生产可用 ES 或 OpenSearch）
        List<Document> bm25Docs = bm25Search(query.text(), topK * 2);

        // 3. RRF 融合
        return rrfFuse(vecDocs, bm25Docs, topK);
    }

    private List<Document> bm25Search(String query, int limit) {
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

### 5.4 装配到 RagConfig

```java
// 在 RagConfig 的 ragAdvisor Bean 里替换 documentRetriever
@Bean
public RetrievalAugmentationAdvisor ragAdvisor(
        VectorStore vectorStore,
        JdbcTemplate jdbc,
        ChatClient.Builder chatClientBuilder) {

    ChatClient chatClient = chatClientBuilder.build();

    return RetrievalAugmentationAdvisor.builder()
            .queryTransformers(RewriteQueryTransformer.builder()
                    .chatClientBuilder(chatClient.mutate())
                    .build())
            // ↓↓↓ 第 5 章变化：换成混合检索
            .documentRetriever(HybridDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .jdbcTemplate(jdbc)
                    .topK(10)
                    .vectorWeight(0.7)
                    .bm25Weight(0.3)
                    .build())
            .build();
}
```

### 5.5 验证

```bash
# 用一个精确关键词的 query（向量检索容易漏的）
curl -X POST 'http://localhost:8080/rag/ask?q=getConversationId 是干啥的'
# 文档原文是 JavaDoc 风格，纯向量可能找不到，混合检索能命中

# 看日志里实际跑的 SQL，应该看到 similarity(...) 查询
```

为了看到检索到底召回了什么，可以加个临时日志接口（验证完删）：

```java
// 临时调试接口
@GetMapping("/debug/retrieve")
public List<Map<String, Object>> debugRetrieve(@RequestParam String q) {
    // 直接调用 HybridDocumentRetriever 看召回
    // 实现略：把 retriever 单独注入，调用 retrieve(Query.builder().text(q).build())
    return List.of();
}
```

### 5.6 对照

| 项 | 第 4 章 | 第 5 章 |
|----|--------|--------|
| 检索 | 纯向量 | **向量 + BM25 混合（RRF 融合）** |
| 关键词召回 | 差 | **强（精确匹配）** |
| 语义召回 | 强 | 强（两者融合） |
| 检索延迟 | 50-100ms | **100-200ms**（多一次 SQL） |

**Trade-off**：混合检索延迟翻倍，但召回质量提升明显。生产标配。

### 5.7 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| `similarity()` 报错 | 没装 pg_trgm / 没建 GIN 索引 | 回 §2.2.5、§2.2.6 补 |
| BM25 召回 0 条 | `ILIKE '%query%'` 太严格 | 改成对 query 分词后用 OR；或上 ES / OpenSearch |
| 中文检索还是差 | pg_trgm 对中文支持一般 | 生产用 ES + IK 分词器，本文 pg_trgm 仅作演示 |
| RRF 权重怎么调 | 没有金标准 | 第 8 章评估闭环做对照实验 |
| `parseMeta` 返回空 map | 占位实现没填 | 注入 `ObjectMapper` 真正解析 JSON |

---

## 第 6 章 提升精度：Rerank + 去重

### 6.1 痛点

第 5 章混合检索后召回的 10 条文档，质量参差不齐——可能 top1 很好，但 top5-10 夹杂了不相关的。

原因：**向量检索和 BM25 都是用粗粒度模型打分**——向量用的是 bi-encoder（query 和 doc 各自编码再算相似度），快但精度有限。

Rerank 用 **cross-encoder**（query 和 doc 拼一起过同一个模型），慢但精度高得多。

典型流程：
```
混合检索 topK=20（快、召回全）
   ↓
Rerank topN=5（慢、但精度高）
   ↓
最终给 LLM 的 5 个 doc 都是高相关
```

### 6.2 最小实现：Rerank PostProcessor

```java
// org/demo02/rag/retrieval/RerankDocumentPostProcessor.java
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

混合检索可能召回同一段文档的不同切片（或重复摄入的文档），需要去重。

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

### 6.4 装配到 RagConfig

```java
// 在 RagConfig 注入两个 PostProcessor，加到 advisor 链
@Bean
public RetrievalAugmentationAdvisor ragAdvisor(
        VectorStore vectorStore,
        JdbcTemplate jdbc,
        ChatClient.Builder chatClientBuilder,
        RerankDocumentPostProcessor rerank,
        DeduplicationPostProcessor dedup) {

    ChatClient chatClient = chatClientBuilder.build();

    return RetrievalAugmentationAdvisor.builder()
            .queryTransformers(RewriteQueryTransformer.builder()
                    .chatClientBuilder(chatClient.mutate())
                    .build())
            .documentRetriever(HybridDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .jdbcTemplate(jdbc)
                    .topK(20)             // ← 检索多召回一些
                    .vectorWeight(0.7)
                    .bm25Weight(0.3)
                    .build())
            // ↓↓↓ 第 6 章新增：去重 + rerank
            .documentPostProcessors(dedup, rerank)  // 顺序：先去重再 rerank
            .build();
}
```

### 6.5 验证

```bash
# 问一个会让 top5-10 夹带噪音的问题
curl -X POST 'http://localhost:8080/rag/ask?q=RAG 的核心目标是什么'
# 第 5 章可能答偏（被 top1 的噪音带跑），第 6 章 rerank 后更准

# 看延迟：rerank 会再调一次 LLM
curl -X POST 'http://localhost:8080/rag/ask?q=RAG 的核心目标' -w '\n耗时: %{time_total}s\n'
# 第 5 章约 2s，第 6 章约 3s（多一次 LLM rerank）
```

### 6.6 对照

| 项 | 第 5 章 | 第 6 章 |
|----|--------|--------|
| 检索 topK | 10 | **20（多召回）** |
| PostProcessor | 无 | **去重 + rerank** |
| 最终给 LLM 的文档数 | 10 | **5（精筛）** |
| 精度 | 中等 | **高（cross-encoder 风格打分）** |
| LLM 调用次数 | 2（改写 + 回答） | **3**（改写 + rerank + 回答） |

**Trade-off**：又多一次 LLM 调用，但精度提升明显。生产标配，但 rerank 模型可换成本地 cross-encoder（如 bge-reranker）替代 LLM，更便宜。

### 6.7 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| Rerank 后反而更差 | LLM 打分不稳 | 改用本地 cross-encoder 模型；或 prompt 加更多约束 |
| LLM 输出格式错，解析失败 | prompt 不够明确 | 强化 prompt：必须输出 `DOC_N=score`，不解释 |
| Rerank 太慢 | 每条 doc 都过 LLM | 用 cross-encoder 本地模型，或批处理 |
| 去重把好文档也去了 | dedupKey 策略太粗 | 改成 hash 全文，或加 metadata 联合 key |
| PostProcessor 顺序错 | 先 rerank 再去重导致重复占名额 | **先去重再 rerank**（如 §6.4） |

---

## 第 7 章 产品级体验：引用溯源

### 7.1 痛点

无引用：`LLM：根据知识库，Spring AI 2.0 已发布。` → 用户不知道哪条文档说的，**无法信任**。

有引用：`LLM：根据 [Doc3]，Spring AI 2.0 于 2026-06-12 发布。` → 用户可点击 [Doc3] 验证。

引用溯源是 RAG 从"demo"到"产品"的分水岭。产品经理、合规、法务都会要求这个能力。

### 7.2 最小实现：自写带引用的 QueryAugmenter

```java
// org/demo02/rag/augmentation/CitationQueryAugmenter.java
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

        return Query.builder()
                .text(originalQuery.text())
                .instructions(instructions)
                .build();
    }
}
```

### 7.3 装配到 RagConfig

```java
@Bean
public RetrievalAugmentationAdvisor ragAdvisor(
        VectorStore vectorStore,
        JdbcTemplate jdbc,
        ChatClient.Builder chatClientBuilder,
        RerankDocumentPostProcessor rerank,
        DeduplicationPostProcessor dedup,
        CitationQueryAugmenter augmenter) {  // ← 第 7 章新增

    ChatClient chatClient = chatClientBuilder.build();

    return RetrievalAugmentationAdvisor.builder()
            .queryTransformers(RewriteQueryTransformer.builder()
                    .chatClientBuilder(chatClient.mutate())
                    .build())
            .documentRetriever(HybridDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .jdbcTemplate(jdbc)
                    .topK(20)
                    .vectorWeight(0.7)
                    .bm25Weight(0.3)
                    .build())
            .documentPostProcessors(dedup, rerank)
            // ↓↓↓ 第 7 章新增：换带引用的 augmenter
            .queryAugmenter(augmenter)
            .build();
}
```

### 7.4 改 RagController（解析引用）

```java
// org/demo02/rag/augmentation/CitationParser.java
package org.demo02.rag.augmentation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationParser {

    private static final Pattern PATTERN = Pattern.compile("\\[Doc(\\d+)\\]");

    public List<Integer> parse(String answer) {
        List<Integer> citations = new ArrayList<>();
        Matcher m = PATTERN.matcher(answer);
        while (m.find()) {
            citations.add(Integer.parseInt(m.group(1)));
        }
        return citations.stream().distinct().sorted().toList();
    }
}
```

```java
// 改 RagController.ask
@PostMapping("/ask")
public Map<String, Object> ask(@RequestParam String q) {
    long start = System.currentTimeMillis();
    String answer = chatClient.prompt().user(q).call().content();
    long elapsed = System.currentTimeMillis() - start;

    return Map.of(
            "answer", answer,
            "citations", citationParser.parse(answer),
            "elapsed_ms", elapsed
    );
}
```

> 完整的引用映射（把 [Doc3] 映射回具体文档 ID）需要从 advisor context 里拿到 retriever 返回的 doc 列表，这部分留给你实现，提示：用 `ChatClientResponse` 的 `context()` 取 advisor 上下文。

### 7.5 验证

```bash
curl -X POST 'http://localhost:8080/rag/ask?q=退款政策是什么'
# 期望输出：
# {
#   "answer": "根据 [Doc1]，该产品支持 7 天无理由退款。具体规则：[Doc1][Doc3]...",
#   "citations": [1, 3],
#   "elapsed_ms": 3124
# }

# 故意问知识库里没有的
curl -X POST 'http://localhost:8080/rag/ask?q=明天天气怎么样'
# 期望：answer 是 "我不确定"，citations 是 []
```

### 7.6 对照

| 项 | 第 6 章 | 第 7 章 |
|----|--------|--------|
| QueryAugmenter | 默认（仅拼 context） | **CitationQueryAugmenter（带引用指令）** |
| 输出可验证性 | 不能（不知道哪条说的） | **能（[DocN] 可点击）** |
| 拒答能力 | 没有 | **有（"我不确定"）** |
| 产品级体验 | 不达标 | **达标** |

### 7.7 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| LLM 不加 [DocN] | 指令太弱 | 强化 prompt：必须每句都加；或加 few-shot 示例 |
| LLM 编造 [Doc5] 但只给了 3 个文档 | 模型幻觉 | prompt 里明确"文档编号范围 1-N" |
| LLM 还在乱编内容 | 指令不够严 | 加："如果你不确定，输出我不确定" + temperature=0 |
| citations 解析为空 | LLM 用了 `(Doc1)` 而不是 `[Doc1]` | 正则放宽：`[\[(]Doc(\d+)[\])]` |
| 引用映射回文档 ID 缺失 | 没存 advisor context | 用 `chatClient.prompt(...).advisors(ragAdvisor).call().context()` 取 |

---

## 第 8 章 让系统不崩：评估闭环 + 量化对比

### 8.1 痛点

第 7 章做完了，怎么知道效果好不好？凭感觉吗？

凭感觉的问题：
- 改了一个 prompt → 不知道是变好还是变差
- 调了 chunk size → 召回质量提升了还是下降了
- 换了 embedding 模型 → 答案精度有没有变化

**没有评估闭环 = 调参凭感觉、回归靠运气**。本章搭一个 LLM-as-Judge 评估管道。

### 8.2 评估指标对照表

| 指标 | 含义 | 怎么测 |
|------|------|-------|
| **Context Precision** | 检索回来的文档，多少真的相关 | LLM-as-Judge 评估每个 doc 是否相关 |
| **Context Recall** | 应该被检索到的文档，实际召回多少 | 需要 ground truth（人工标注的"这个问题的相关文档"）|
| **Faithfulness（忠实度）** | LLM 回答是否**只**基于检索的 context | 把 answer 拆 claims，每个 claim 是否被 context 支持 |
| **Answer Relevancy** | 回答是否切题 | 用 LLM 反推"这个回答可能对应什么问题"，再和原 query 算相似度 |
| **Citation Accuracy** | 引用是否准确（本文特有） | 引用的 [DocN] 是否真的支持那句话 |

### 8.3 最小实现：用 LLM-as-Judge 实现 Faithfulness

```java
// org/demo02/rag/eval/RagEvaluationService.java
package org.demo02.rag.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

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
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            ctx.append("[Doc").append(i + 1).append("] ")
               .append(contexts.get(i).getText()).append("\n");
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

### 8.4 评估数据集（必须人工标注）

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

### 8.5 评估 Runner

```java
// org/demo02/rag/eval/RagEvalRunner.java
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

### 8.6 触发评估接口 + 量化对比

```java
// org/demo02/rag/controller/EvaluationController.java
@RestController
@RequestMapping("/rag/eval")
public class EvaluationController {

    private final RagEvalRunner evalRunner;

    public EvaluationController(RagEvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @PostMapping("/run")
    public Map<String, Double> run() throws Exception {
        List<RagMetrics> all = evalRunner.runAll();
        double f = all.stream().mapToDouble(RagMetrics::faithfulness).average().orElse(0);
        double r = all.stream().mapToDouble(RagMetrics::relevance).average().orElse(0);
        double cp = all.stream().mapToDouble(RagMetrics::contextPrecision).average().orElse(0);
        return Map.of("faithfulness", f, "relevance", r, "contextPrecision", cp);
    }
}
```

### 8.7 量化对比实验（chunk size 案例）

```java
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

### 8.8 典型量化结果（参考）

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

### 8.9 验证

```bash
# 先准备至少 5-10 条评估数据集（rag-eval-dataset.yaml）
# 摄入对应的 PDF

# 跑评估
curl -X POST 'http://localhost:8080/rag/eval/run'
# 期望输出：{"faithfulness":0.83,"relevance":0.86,"contextPrecision":0.78}

# 跑对照实验（chunk size）
curl -X POST 'http://localhost:8080/rag/eval/compare-chunk-size'
# 期望：能看到不同 chunk size 的指标对比，找到最优解
```

### 8.10 对照

| 项 | 第 7 章 | 第 8 章 |
|----|--------|--------|
| 调参方式 | 凭感觉 | **量化对比** |
| 回归测试 | 没有 | **跑全集自动评估** |
| 决策依据 | "我觉得变好了" | **指标对比** |

### 8.11 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| 指标每次跑都不一样 | temperature 不是 0 | 评估时强制 temperature=0 |
| Faithfulness 永远 1.0 | judge 太宽松 | 换更强的模型当 judge；加约束 |
| 评估太贵（每条 3 次 LLM 调用） | 全集太大 | 抽样 50 条；或用本地小模型当 judge |
| 用同一个模型当裁判和生成器 | 裁判偏袒、指标虚高 | 裁判用更强或不同模型 |
| Context Recall 没法算 | 没有 ground truth | 必须人工标注 relevant_doc_sources |

---

## 第 9 章 上生产：增量索引 / 多租户 / 缓存 / 监控

### 9.1 痛点

第 8 章做出了能跑、能评估的 RAG 系统，但离生产还差几件事：
- 文档更新后怎么同步（全量重建太慢）
- 多租户怎么隔离（A 公司不能查到 B 公司的数据）
- 每次都调 LLM 太慢太贵（缓存）
- 出问题怎么发现（监控）

### 9.2 增量索引

文档更新时不要全量重建索引，要按文档 ID 做 upsert：

```java
public void upsert(Document doc) {
    // VectorStore 默认 add 行为：相同 ID 会覆盖
    vectorStore.delete(List.of(doc.getId()));
    vectorStore.add(List.of(doc));
}
```

更完整的：监听文档源（如 Confluence webhook / OSS 事件），变更时自动重新摄入。

### 9.3 多租户隔离

第 3 章存的 `department` metadata 现在派上用场了——检索时强制带租户过滤：

```java
// 检索时强制带租户过滤
String tenantId = TenantContext.get();  // 从 ThreadLocal / JWT 拿
String filter = "tenant_id == '" + tenantId + "'";
SearchRequest req = SearchRequest.builder()
        .query(query)
        .filterExpression(filter)
        .topK(10)
        .build();
```

> 注意：filterExpression 语法见 Spring AI 文档。**永远不要**用字符串拼接 SQL 防注入——metadata filter 是 Spring AI 解析的，不是 SQL。

### 9.4 缓存层

第 2 章起的 Redis 现在用上：

- **Embedding 缓存**：同一段文本向量化一次后缓存（Redis，key=text hash）
- **Query 改写缓存**：常见 query 的改写结果缓存（解决第 4 章的延迟翻倍问题）
- **Top-K 缓存**：高频 query 的检索结果缓存（短 TTL）

```java
// Embedding 缓存示例
@Service
public class CachedEmbeddingService {

    private final EmbeddingModel delegate;
    private final RedisTemplate<String, byte[]> redis;

    public float[] embedCached(String text) {
        String key = "emb:" + DigestUtils.md5DigestAsHex(text.getBytes());
        byte[] cached = redis.opsForValue().get(key);
        if (cached != null) return deserialize(cached);
        float[] vec = delegate.embed(text);
        redis.opsForValue().set(key, serialize(vec), Duration.ofDays(7));
        return vec;
    }
    // serialize / deserialize 略
}
```

### 9.5 监控指标

| 指标 | 阈值告警 |
|------|---------|
| 检索 P95 延迟 | > 500ms |
| 检索 topK 全为低分 | 阈值 < 0.3 |
| LLM 回答 "我不确定" 的比例 | > 30% |
| Faithfulness 月度均值 | < 0.75 |
| 引用命中率（[DocN] 出现率）| < 80% |

接入 Micrometer + Prometheus，把上述指标暴露成 metrics：

```java
@Bean
public MeterRegistryCustomizer<MeterRegistry> ragMetrics() {
    return registry -> {
        Counter.builder("rag.ask.total").register(registry);
        Counter.builder("rag.citation.hit").register(registry);
        Timer.builder("rag.retrieve.latency").register(registry);
    };
}
```

### 9.6 验证

```bash
# 多租户：摄入两份不同 tenant 的文档，互相查不到
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/a.pdf&source=a.pdf&department=tenant_a'
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/b.pdf&source=b.pdf&department=tenant_b'
# 设置请求头 X-Tenant-Id=tenant_a，再问 b.pdf 里的内容 → 应该答不出来

# 增量索引：改一份已摄入的 PDF，重摄入 → 旧版本不再被召回
curl -X POST 'http://localhost:8080/rag/ingest?path=/tmp/test-v2.pdf&source=test.pdf&department=hr'
# 查询应该返回 v2 内容，不是 v1

# 缓存：第二次问同样的 query，elapsed_ms 应该明显下降
curl -X POST 'http://localhost:8080/rag/ask?q=退款政策' -w '\n%{time_total}s\n'
curl -X POST 'http://localhost:8080/rag/ask?q=退款政策' -w '\n%{time_total}s\n'
```

### 9.7 对照

| 项 | 第 8 章 | 第 9 章 |
|----|--------|--------|
| 索引更新 | 全量重建 | **增量 upsert** |
| 多租户 | 不支持 | **metadata 过滤隔离** |
| 缓存 | 无 | **Embedding / 改写 / TopK 三层** |
| 可观测性 | 日志 | **Micrometer + Prometheus** |
| 是否生产就绪 | 否 | **是** |

### 9.8 避坑

| 现象 | 原因 | 解决 |
|------|------|------|
| 多租户过滤被绕过 | filterExpression 没传 / 传错 | 在 retriever 层强制注入，不让 controller 传 |
| 增量 upsert 后新旧版本都召回 | delete 没生效 | 检查文档 ID 是否一致；PgVectorStore 的 delete 行为 |
| 缓存导致回答陈旧 | TTL 设太长 | query 改写缓存 TTL 短（1h），embedding 缓存可以长（7d） |
| Prometheus 抓不到 | metrics endpoint 没暴露 | 加 `spring-boot-starter-actuator` + `management.endpoints.web.exposure.include=prometheus` |
| 监控告警太多 | 阈值设太严 | 先观察 1 周基线，再调阈值 |

---

## 验证清单（全 9 章做完）

照着做完后，逐项验证：

- [ ] 第 1 章：`POST /rag/ingest` + `POST /rag/ask` 能跑通（内存版）
- [ ] 第 2 章：重启应用后数据还在（PgVector）
- [ ] 第 3 章：摄入后能查到 metadata（source/department）
- [ ] 第 4 章：口语化 query 也能召回
- [ ] 第 5 章：精确关键词 query 也能召回
- [ ] 第 6 章：rerank 后 top5 中无噪音（人工抽检 20 个 query）
- [ ] 第 7 章：返回带 [DocN] 引用，可点击验证
- [ ] 第 7 章：故意问知识库里没有的，答"我不确定"
- [ ] 第 8 章：评估管道能跑全集，输出 faithfulness / relevance / contextPrecision
- [ ] 第 8 章：对照实验能输出不同 chunk size 的指标对比
- [ ] 第 9 章：多租户 A 查不到 B 的数据
- [ ] 第 9 章：增量 upsert 后旧版本不再被召回

---

## 反模式速查（全章常见错误）

| ❌ 反模式 | 后果 | ✅ 正确做法 | 章节 |
|----------|------|-----------|------|
| 直接用 `QuestionAnswerAdvisor` 上生产 | 无法做混合检索 / rerank / 引用溯源 | 用 `RetrievalAugmentationAdvisor` 拼装 | 第 4 章 |
| Chunk 切太大（>2000 字符） | LLM 看不全、context 噪音大 | 800-1200 字符 + overlap 150 | 第 3 章 |
| 只用向量检索 | 关键词精确匹配场景失败 | 混合检索（向量 0.7 + BM25 0.3） | 第 5 章 |
| 没有 Rerank | topK 里夹杂不相关文档 | 检索 topK=20 → rerank → 5 | 第 6 章 |
| 没有引用溯源 | 用户无法验证、信任度低 | 拼 prompt 时让 LLM 加 [DocN] | 第 7 章 |
| 没有评估闭环 | 调参凭感觉、回归靠运气 | 搭 LLM-as-Judge 评估管道 | 第 8 章 |
| 用同一个模型当裁判和生成器 | 裁判偏袒、指标虚高 | 裁判用更强或不同模型 | 第 8 章 |
| 多租户用 SQL 字符串拼接 | 注入风险 | filterExpression + metadata 过滤 | 第 9 章 |
| 改了 embedding 模型没删表 | 维度不匹配写入失败 | 删表重建，对齐 dimensions | 第 2 章 |

---

## 进阶扩展（不在本文范围）

| 方向 | 说明 | 参考资源 |
|------|------|---------|
| Embedding 微调 | 用业务数据微调 Embedding 模型，提升召回 | [reference/工程架构/07-模型微调.md](../../reference/工程架构/07-模型微调.md) |
| ColBERT / late-interaction | 比 cross-encoder 快、比 bi-encoder 准 | RAGatouille 项目 |
| Graph RAG | 用知识图谱增强 RAG | Microsoft GraphRAG |
| Agentic RAG | 让 Agent 决定检索策略、多轮检索 | LangGraph / Alibaba Graph |
| 多模态 RAG | 文本 + 图片 + 表格混合检索 | CLIP 模型 + 向量库 |

---

## 相关文档

- [`./05-MCP协议全解.md`](./05-MCP协议全解.md) —— 会话持久化（与 RAG 共用向量库场景）
- [`./12-评估闭环与Prompt版本管理.md`](./12-评估闭环与Prompt版本管理.md) —— 通用评估闭环（本文是 RAG 专用）
- [`../../reference/理论基础/02-RAG深度优化.md`](../../reference/理论基础/02-RAG深度优化.md) —— RAG 理论深度版
- [Spring AI RAG Reference](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)

---

完成本文后，你已经能：
1. 在 Spring Boot 4 + Spring AI 2.0 项目里**从零、按企业项目节奏**搭建 RAG 系统
2. 一步步把"5 分钟 demo"升级成生产系统（持久化 / 切分 / 改写 / 混合检索 / rerank / 引用 / 评估 / 多租户）
3. 用 LLM-as-Judge 评估闭环量化对比不同策略
4. 做出生产化决策（增量索引、多租户隔离、缓存、监控）

回到 [`./00-目录索引.md`](./00-目录索引.md) 继续后续等级学习。
