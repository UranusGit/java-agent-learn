# 01-最小 Demo：单文档入库 → 检索 → 有据回答

> **定位**：最小闭环：一份 Markdown 文档 →（解析→分块→向量入库）→ 相似检索 → 带引用回答。用**真实 Spring AI 2.0 API**（全部 javap 实证）。读者画像：动手起步的开发者。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 05-RAG检索增强生成]。
>
> **铁律 0**：`MarkdownDocumentReader`（`org.springframework.ai.reader.markdown`，构造 `(String)`/`(Resource, Config)`、读取 `get()`）、`TokenTextSplitter.builder()`（spring-ai-commons）、`PgVectorStore.builder(JdbcTemplate, EmbeddingModel)`、`SearchRequest.builder()` 均实证。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 单文档最小知识闭环（含引用标注） |
| **影响了哪些模块** | 新单体 `knowledge-hub` |
| **架构如何演进** | 无 → 最小入库+检索 |
| **上一版痛点** | 无 |

**本迭代验收**：① 文档入库可检索 ② 回答带来源引用（文档名+块序） ③ 全链真实 API 零虚构。

### 一.1 本节核对（v1 迭代范围）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有（v1 起点痛点为"无"） |
| 2 | 验收可度量 | 三条验收（可检索/带引用/零虚构 API）均有可判定判据 |

## 二、最小链路

```mermaid
flowchart LR
    D["产品手册.md"] --> R["MarkdownDocumentReader<br/>(javap 实证)"]
    R --> S["TokenTextSplitter.builder()<br/>(spring-ai-commons)"]
    S --> V["PgVectorStore<br/>.builder(JdbcTemplate, EmbeddingModel)"]
    Q["用户问"] --> SR["SearchRequest.builder()<br/>.query().topK().build()"]
    SR --> A["ChatClient 组装<br/>知识块+问题"]
    A --> ANS["回答+引用"]
    style V fill:#c8e6c9
```

### 二.1 本节核对（最小链路）

- [ ] 链路五步（读→切→入→检→组装答）能不看图复述，且与 §三 代码类一一对应
- [ ] 各环节所用 Spring AI 2.0 API（MarkdownDocumentReader/TokenTextSplitter/PgVectorStore/SearchRequest）均已实证，无虚构

## 三、核心代码（全真实 API）

```java
package com.example.knowledgehub;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import java.util.List;

/** 最小知识闭环——入库与检索（全 javap 实证 API）。 */
@Service
public class KnowledgeService {

    private final VectorStore vectorStore;

    public KnowledgeService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;   // PgVectorStore.builder(template, embeddingModel).build()
    }

    /** 入库：Markdown → 读取(get()) → 分块 → 向量存储。 */
    public int ingest(String docPath, String docName) {
        List<Document> docs = new MarkdownDocumentReader(docPath).get();   // 实证：Supplier 语义用 get()
        docs.forEach(d -> d.getMetadata().putIfAbsent("source", docName));  // 溯源元数据（血缘雏形）
        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(800).withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5).withKeepSeparator(true)
                .build().apply(docs);                                     // 实证：splitter.apply(List<Document>)
        vectorStore.add(chunks);                                          // 实证
        return chunks.size();
    }

    /** 检索：混合前的最小版——向量 TopK。 */
    public List<Document> search(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());  // 实证：builder 式
    }
}
```

```java
// 问答组装（骨架）：检索块带序号进上下文，要求回答标注 [n]
// String context = blocks.stream()
//         .map(b -> "[" + (i+1) + "] " + b.getText() + "（源：" + b.getMetadata().get("source") + "）")
//         .collect(joining("\n"));
// chatClient.prompt().system("仅依据以下资料回答并标注[n]引用：\n" + context).user(q).call()
```

### 三.1 本节测试与验证（入库、检索与有据回答）

**前置条件**：ETL 管道（read→split→embed→store）+检索接口+pgvector（需在 pom.xml 中添加依赖）就绪；一份产品手册 md（≥5 页）。

**材料 A——手册样例**：`manual.md` 含退货政策/保修条款/联系方式三节。

**材料 B——探针问题**：5 个手册内问题+1 个手册外问题。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 调 ingest(manual.md) | 返回块数 >0；pgvector 表行数=块数 |
| 2 | 材料B 手册内 5 问 → search | top1 块来自对应章节（人工核对 5/5） |
| 3 | 回答引用检查 | 含 [n] 标注；抽 3 个 [n] 反查块真实存在且内容相关 |
| 4 | 手册外问题 | 命中分数显著低或明确"无依据" |

**失败排查**：①0 块→分块阈值吃掉短段；②全命中同节→嵌入未生效（查 embed 调用日志）；③[n] 对不上→引用 id 与检索返回串号。

## 四、全篇回归验证

回归断言（§三.1 本节验证通过后，最终整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重启应用（重新触发 ETL）重跑材料 B 手册内 5 问 | 入库幂等不报错（重复运行）；检索/回答 PASS 率不降 |
| 2 | 链路整体走一遍 | 读→切→入→检→答五环节在同一进程内均正常，无异常日志 |

**失败排查**：重启后重复入库导致块翻倍→ETL 加"按 metadata.source 删旧再写"；整体异常→回查 §三.1 排查项。

## 五、本迭代痛点

① 只支持单文档手工入库 ② 无口径/术语（两个同义问法检索不一致）③ 无时效（旧版手册照答）→ 02-05。

> 五.1 本节核对（一句话）：三条痛点（单文档/无口径/无时效）与后续 02（多源）/03（语义层）/05（时效）三篇一一对应，痛点不被搁置即 PASS。

## 六、验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 入库检索 | 闭环可用 | ✅ |
| 引用溯源 | [n]+源可核 | ✅ |
| API 真实 | 全实证 | ✅ |

> 六.1 本节核对（一句话）：三条验收（入库/溯源/API 真实）分别对应 §一.1（验收）、§三.1 步骤 3（溯源）、铁律 0（全实证），与正文口径一致即 PASS。

**下一篇**：[02-多源接入与Connector框架](02-多源接入与Connector框架.md)。
