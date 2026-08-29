# 项目 00：智能客服系统 — 03-RAG 知识库

> **定位**：为客服 Agent 接入向量数据库（PGVector），实现基于产品手册的智能问答。涵盖文档 ETL 流水线、Embedding 向量化、PGVector 配置、`QuestionAnswerAdvisor` 集成。读完这篇，客服 Agent 能回答任意产品文档问题。本文给出**完整可手写代码**。
> **读者画像**：已完成工具集成，需要让 Agent 具备「阅读长文档」能力。
> **前置阅读**：[02-工具集成]。
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

### 2.3 `VectorStoreConfig.java` ——【重要】不要手写 `vectorStore` bean

> **⚠️ 2026-08-22 实证修正**：`spring-ai-starter-vector-store-pgvector` 自带的 `PgVectorStoreAutoConfiguration` **已注册名为 `vectorStore` 的 bean**（javap 实证：该方法标 `@ConditionalOnMissingBean`）。若再手写一个同名 `vectorStore` bean，会触发 `BeanDefinitionOverrideException`（Spring Boot 默认 `allow-bean-definition-overriding=false`）。**正确姿势：不手写 `vectorStore`，让 starter 自动配置接管**——它在启动时读取 `application.yml` 里 `spring.ai.vectorstore.pgvector.*` 的全部参数自动建库。你只需要提供一个 `TokenTextSplitter` bean：

```java
package com.shop.customer.rag;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只保留切分器；vectorStore 由 starter 自动配置提供，不要在 SQL 之外手动注册。 */
@Configuration
public class VectorStoreConfig {

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
}
```

> **为什么不手写 `vectorStore`？** ① 手写的 `PgVectorStore.builder(jdbcTemplate, embeddingModel).build()` 是**空构建**，**读不到** yaml 里配的 `index-type/dimensions/schema/table` 等参数，配置会白白丢失；② 与 starter 自动配置同名冲突，直接启动失败。自动配置会完整读取 yaml 的 `spring.ai.vectorstore.pgvector.*` 并应用（含 `initialize-schema` 建表）。**二选一原则**：引了 starter 就用自动配置；只有当你确实需要自定义构建参数且想关掉自动配置时，才用下面 §2.3.1 的手写方式——两种方式互斥，禁止并存。

> `PgVectorStore.builder()` 的真实签名是 `(JdbcTemplate, EmbeddingModel)`（javap 实证，[教程 06-向量数据库选型] 与 [附录 05]），仅在手写方式（§2.3.1）下使用。`JdbcTemplate` 由 `spring-ai-starter-vector-store-pgvector` + `spring-boot-starter-jdbc` 自动装配，`EmbeddingModel` 由 OpenAI starter 自动装配。

#### 2.3.1（可选）必须手写 `vectorStore` 时的排他方式

如果你坚持手写 `vectorStore`（例如要注入观测/自定义构建），必须**排除 starter 的自动配置**，否则同名 bean 冲突（`BeanDefinitionOverrideException`）。二选一：

- **方式一（Java 排除）**：在启动类关掉自动配置——
  ```java
  @SpringBootApplication(exclude = {
      org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration.class
  })
  ```
  注意：排除后 yaml 的 `spring.ai.vectorstore.pgvector.*` **不再被自动读取**，你必须在手写 bean 里显式传参，否则建库参数（dimensions/schema）丢失。

- **方式二（开关关闭）**：在 `application.yml` 加配置（具体开关键以 `PgVectorStoreProperties` 实证为准）：
  ```yaml
  spring:
    ai:
      vectorstore:
        pgvector:
          enabled: false   # 概念键，需按实际 `@ConditionalOnProperty` 前缀核对
  ```

> 一般场景**不需要走 §2.3.1**，直接交给自动配置即可（本节开头推荐）。

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

> `TokenTextSplitter` 需要作为 Bean 提供——已在上文 §2.3 的 `VectorStoreConfig` 中定义（见 [§2.3](#23-vectorstoreconfigjava-重要不要手写-vectorstore-bean)）。

#### 2.4.1 本节测试与验证（ETL 入库）

**前置条件**：PGVector 依赖与 `application.yml` 已就绪；PG 实例可连（5432）；`manual/产品手册.md` 在 classpath。

**材料——入库核对 SQL**：

```sql
SELECT COUNT(*) FROM vector_store;
SELECT LEFT(content, 50), metadata FROM vector_store LIMIT 3;
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 启动应用 | 日志无 `BeanDefinitionOverrideException`（未手写同名 vectorStore）；KnowledgeBaseLoader 正常执行 |
| 2 | 材料 COUNT | 行数 ≥1（demo01 手册约 940 字节、chunkSize=800 → 1–2 块） |
| 3 | 材料 LIMIT 抽查 | content 为手册正文片段，`embedding` 列非空 |
| 4 | 维度核对 | `dimensions` 与 embedding 模型真实输出一致（本机 bge-large-zh 为 1024，见 §4 运行备注） |

**失败排查**：①启动即 `expected 1536 dimensions, not 1024`→配置维度与模型不符（改 dimensions 或核模型）；②表不存在→`initialize-schema` 未开或自动配置被排除；③入库 0 行→手册路径/名称不对，`ClassPathResource` 读空。

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

> `QuestionAnswerAdvisor` 的真实包路径是 `org.springframework.ai.chat.client.advisor.vectorstore`（教程 05 旧稿曾写错），以 [附录 05-00 §1.4] 为准。

### 2.6 一句话说清：`QuestionAnswerAdvisor` 是什么

它**不做回答，做检索增强**：调用链在真正调模型前，先用你的问题查向量库，把命中的文档块拼进 Prompt 当上下文，再让模型基于资料作答——这就是本项目的 RAG。它是 `BaseAdvisor` 实现，靠 `before`（检索注入）/ `after`（回写 `RETRIEVED_DOCUMENTS` 供观测）两钩子生效，所以挂在 `defaultAdvisors` 上所有调用自动生效。

> **详细原理（两阶段时序图、为什么做成 Advisor、`topK`/`similarityThreshold` 调优、常见坑）→ [教程 23-Advisor链与拦截器 §4.3]**。本项目的落地代码与验证包就是这里的实践篇。

### 2.7 参数速查（本项目取值）

| 旋钮 | 本项目值 | 效果 | 详解 |
|------|---------|------|------|
| `topK` | 3 | 最多召回文档块数 | [教程 23-Advisor链与拦截器 §4.3.2] |
| `similarityThreshold` | 0.7 | 低于该相似度的块**直接丢弃** | [教程 23-Advisor链与拦截器 §4.3.2] |

**三项自查**（对照 §3 失败排查）：① import 必须为 `…advisor.vectorstore.QuestionAnswerAdvisor`（包名易写错）；② pom 已引 `spring-ai-vector-store-advisor` 模块；③ Advisor 挂了多半是库空（`KnowledgeBaseLoader` 没跑成）或 threshold 高到检不出东西。

### 2.8 本节测试与验证（RAG 问答与工具协同）

> **前置**：§2.4.1 入库验证已通过（§2.5 Advisor 已挂载）。以下问题按「手册正文真实内容」和仓库真实数据（FAQ 3 条、订单 2 单）逐一设计，可直接复制到 `/demo01/chat` 逐个提问。

### 材料 A——手册内可答（RAG 应命中 → 基于手册作答）

直接引用 `src/main/resources/manual/产品手册.md` 正文出题（这是 RAG 唯一能召回的语料）：

| # | 问题（可直接复制） | 手册依据 |
|---|-------------------|---------|
| A1 | 人工客服的上班时间是什么时候？ | 注意事项：「工作日 09:00 - 18:00」 |
| A2 | 退款前需要注意什么？ | 注意事项：「退款需经过客户二次确认，禁止未确认直接退款」 |
| A3 | 哪些情况需要转人工客服？ | 话术规范「无法确定的信息转人工」「涉及金额/退款/维权转人工」 |
| A4 | 支持哪些支付方式？ | FAQ「支持支付宝、微信支付、银行卡及信用卡分期」 |
| A5 | 怎么申请退货？ | FAQ「联系人工客服，15 天内支持无理由退货」 |
| A6 | 客服回答有哪几条语气要求？ | 话术规范「语气友好、简洁明了」等 |
| A7 | 本产品叫什么、是做什么的？ | 产品概述「面向电商场景的智能客服 Agent」 |

### 材料 A'——长尾问题（未写进 FAQ，但手册有据 → RAG 兜底，FAQ 工具不命中）

FAQ 工具（`FaqTool`）只有「退换货/以旧换新/发货时效」3 条。以下问题**手册有答案但 FAQ 查不到**，用来验证 RAG 兜底：

| # | 问题（可直接复制） | 手册依据 |
|---|-------------------|---------|
| W1 | 退款之前需要客户做什么？ | 注意事项（二次确认） |
| W2 | 订单有纠纷该怎么处理？ | 话术规范（维权转人工） |
| W3 | 平台接不接受信用卡分期？ | FAQ（支付方式含信用卡分期） |
| W4 | 值班时间之外客服在吗？ | 注意事项（工作时间之外无人工） |
| W5 | 不确定的信息客服要怎么回应？ | 话术规范（转人工） |

### 材料 A"——工具类（命中 FaqTool / OrderTool，验证工具链路）

| # | 问题 | 预期命中的工具 |
|---|------|---------------|
| T1 | 退换货政策是什么 | FaqTool（faq=「退换货政策是什么」） |
| T2 | 支持以旧换新吗 | FaqTool（faq=「支持以旧换新吗」） |
| T3 | DD20240810 这个订单什么状态了 | OrderTool（订单「已发货/顺丰 SF123456789」） |
| T4 | 查一下订单 DD20240811 | OrderTool（订单「待支付/无物流」） |
| T5 | 我的订单 DD123456789 呢（不存在的单号）| OrderTool 返回 null → 如实说查不到 |

### 材料 B——无关问题（RAG 应**拒绝召回**，明确"手册未提及"）

| # | 问题（可直接复制） | 预期 |
|---|-------------------|------|
| X1 | 推荐一部好看的电影 | 不强行召回，答"手册未提及" |
| X2 | 明天上海会下雨吗 | 同上 |
| X3 | 帮我写一首关于秋天的诗 | 同上 |
| X4 | 今天股票涨了吗 | 同上 |
| X5 | 川菜怎么做 | 同上 |

### 步骤与断言

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 材料 A 七问逐条 | 命中并回答正确 ≥6/7 |
| 2 | 材料 A' 五问逐条 | 全部能从向量库召回回答（FAQ 工具不触发） |
| 3 | 材料 A" T1–T5 | T1–T4 命中工具返回真实数据；T5 如实返回"查不到" |
| 4 | 材料 B 五条 | 明确"手册未提及"；similarityThreshold=0.7 生效（不强行召回） |
| 5 | 回答抽检 3 条 | 含引用/依据（能指出来自手册哪一部分） |

### 失败排查

- A 命中率低 → 分块过大（关键句被子块稀释）或 `topK`/`similarityThreshold` 过严
- W/W1–W5 误触发 FAQ 工具 → FAQ 与手册措辞撞了，长尾口径要在 System Prompt 讲清"工具查不到再看手册"
- B 强召回 → `similarityThreshold` 过低或未生效（确认 QuestionAnswerAdvisor 真的挂了）
- T5 捏造物流 → `OrderTool` 返回 null 但 System Prompt 没约束"查不到就明说"
- 全部无依据 → 检索块没注入 Prompt（RAG Advisor 未挂 or 向量库为空）

## 3. 全篇回归验证

**回归断言**（§2.4.1 与 §2.8 本节验证均通过后，最终整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重启应用（重新触发 ETL），重跑材料 A 七问 | 入库幂等不报错（重复运行），七问 PASS 率不降 |
| 2 | 混合问一轮：A1 + T3 + X1 各一次 | 三类链路（RAG/工具/拒答）在同一个会话进程内均正常 |

**失败排查**：重启后重复入库导致块翻倍→ETL 加"先按 metadata.source 删旧再写"；混合轮异常→RAG 与工具 Advisor 顺序问题，回查 §2.8 排查项。

## 4. 运行备注（本项目/本机实测，跟随项目迁移）

> 以下为 demo01 工程在本机的实测排错记录。它们**只对本项目成立**，可能随向量库/模型部署变化，移植到别处必须先按当前环境复核。

- **Maven profile 激活**：本项目核心依赖（webflux、spring-ai）在 Maven `demo` profile、向量依赖在 `pgvector` profile。命令行启动必须同时激活，否则抛 `NoClassDefFoundError: SpringApplication`（classpath 里连 spring-boot 都没有）：
  ```bash
  mvn -Ddemo.demo=demo -Ddemo.pgvector=pgvector -DskipTests spring-boot:run
  ```
  IDEA 里则把同一参数放入 Run Configuration 的 VM options（配合 `spring.profiles.active=demo01` 生效于 `application-demo01.yaml`）。

- **嵌入维度必须等于模型真实输出**：PGVector 表的 `embedding` 列维度由配置 `spring.ai.vectorstore.pgvector.dimensions` 决定，必须与实际 embedding 模型输出一致。本机本地 embedding（`text-embedding-bge-large-zh-v1.5`）实测为 **1024 维**；配置写成 1536 会报 `expected 1536 dimensions, not 1024`。换模型/换部署时用一次实序（如 `curl POST /v1/embeddings`）确认真实维度再写配置。

- **vectorStore bean 交由 starter 自动配置，勿手写同名 bean**：详见 §2.3 的实证修正——手写 `vectorStore` bean 会与 `PgVectorStoreAutoConfiguration` 冲突抛 `BeanDefinitionOverrideException`。


> **定位回顾**：本篇让 Agent 会读文档。下一站 [04-记忆与会话]——接 ChatMemory 做多轮记忆。
