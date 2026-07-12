# 第三阶段 - RAG 的深度与优化

> 目标：把 Naive RAG 升级为可量化评估的 Hybrid + Rerank 系统，能讲清每个环节的失败模式。

---

## 1. Naive RAG 的三大病（必须能说出"为什么"）

| 病灶 | 根本原因 |
|------|---------|
| **查不准** | 向量检索对精确术语（产品型号、人名、代码符号）不敏感 |
| **查不全** | 单路召回的天花板 |
| **答案差** | 上下文塞太长导致 LLM "lost in the middle"，或排序靠前的不是最相关 |

---

## 2. 向量检索基础原理

### 2.1 基本流程
```
文本 → Embedding 模型 → 高维向量（如 768/1536 维）
                        ↓
                  相似度计算（余弦相似度 / 点积）
                        ↓
                  返回 Top-K 文档
```

### 2.2 主流索引算法（了解即可）

| 算法 | 原理 | 特点 |
|------|------|------|
| **HNSW** (Hierarchical Navigable Small World) | 分层图算法 | 查得快，内存大（最常见） |
| **IVF** (Inverted File) | 聚类后倒排 | 内存中等，需要训练 |
| **PQ** (Product Quantization) | 乘积量化压缩 | 内存极小，精度有损 |
| **HNSW + PQ** | 组合 | 大规模生产首选 |

---

## 3. 进阶检索技术（按优先级学）

### 3.1 混合检索 Hybrid Search

#### 原理
- **BM25**（TF-IDF 变种）：基于词频 + 逆文档频率，对**精确匹配**强。
- **向量检索**：基于语义，对**同义/近义**强。
- **融合方法：RRF (Reciprocal Rank Fusion)** —— `score = Σ 1/(k + rank_i)`，k 通常取 60。
  - RRF 不需要两路分数归一化，比加权平均更鲁棒。

#### 实操选型
- **Elasticsearch 8.x**：自带 `knn` 查询 + `bool query`，可用 `rrf` 检索器融合。
- **Weaviate / Qdrant / Milvus**：均内置混合检索，开箱即用。

---

### 3.2 重排序 Re-ranking

#### 为什么必须做
- 双塔召回模型（Embedding）为了速度牺牲了精度。
- **Cross-encoder 重排模型**把 query 和 doc **拼在一起**进 Transformer，精度碾压，但慢、贵。
- 标准流程：**召回 100 → 重排 10**。

#### 主流重排模型

| 类型 | 模型 | 特点 |
|------|------|------|
| 闭源 | Cohere Rerank v3 / Voyage Rerank | 贵但好 |
| 开源 | `bge-reranker-v2-m3` | 中英多语言，首选 |
| 开源 | `jina-reranker-v2` | 速度快 |
| 开源 | `Qwen3-Reranker` | 阿里出品，强 |

#### Java 集成方式
- Python 侧起 **Infinity / Text Embeddings Inference (TEI)** 服务，Java 通过 HTTP 调用。
- 或用 Qdrant 的内置 RBF。

---

### 3.3 分块策略

| 策略 | 适用场景 | 工具 |
|------|---------|------|
| 固定大小 + overlap | 通用起步 | LangChain `RecursiveCharacterTextSplitter` |
| 递归分块 | Markdown/HTML 等结构化文档 | **默认推荐** |
| 语义分块 Semantic Chunking | 长文叙述、小说 | LangChain `SemanticChunker` |
| 父子分块 Parent-Child | 小块检索、大块送入 LLM | LlamaIndex `HierarchicalNodeParser` |
| 基于文档结构（标题/页/表） | PDF/Word | Unstructured.io |

#### 经验法则
- 块大小 **256~512 token**，overlap **10~20%**。
- 不要追求"最优分块"，准备一份评估集，**AB 测试**。

---

### 3.4 高阶主题（先了解，需要时深挖）

| 技术 | 思路 |
|------|------|
| **Query Rewriting** | 让 LLM 把用户口语化的 query 改写成多个检索友好的 query |
| **HyDE** (Hypothetical Document Embeddings) | 先让 LLM 生成"假答案"，用假答案做向量检索 |
| **Multi-Vector Retrieval** | 每个文档存"摘要向量 + 全文向量 + 关键词向量" |
| **GraphRAG**（微软 2024） | 用 LLM 抽取实体关系建知识图谱，检索时做社区检测 + 摘要 |

> GraphRAG 适合"全局性总结"问题，复杂度和成本高，**不要一上来就上**。

---

## 4. 推荐资料

### 官方文档（按顺序读）
- LangChain 检索概念：`python.langchain.com/docs/concepts/retrieval/`
- LlamaIndex 文档（RAG 章节比 LangChain 讲得更透）：`docs.llamaindex.ai/en/stable/optimizing/`
- Qdrant 官方博客（向量检索原理写得很扎实）：`qdrant.tech/articles/`
- Elasticsearch The Good Parts（混合检索部分）

### 书
- 《大模型应用开发指南》（吴茂贵等，机械工业）—— 中文，偏实战
- 《Building LLM Applications》— LangChain 作者团队博客合集

### 视频
- DeepLearning.AI 短课：*"Advanced Retrieval for AI with Chroma"*（1 小时，免费）
- *"Building and Evaluating Advanced RAG"*（LlamaIndex 出品）

---

## 5. 实操项目：PDF 问答 V2

### 目标
把 Naive RAG 升级为**可量化评估的 Hybrid + Rerank 系统**。

### 技术栈
- Java 17 + Spring Boot 3.x
- 向量库：**Milvus** 或 **Qdrant**（Docker 一行起）
- Embedding：`bge-m3`（中英多语言，1024 维）通过 TEI 服务暴露
- Rerank：`bge-reranker-v2-m3` 通过 TEI 暴露
- Java 框架：**Spring AI** 或 **LangChain4j**

### 关键步骤
1. 准备 20~50 条 QA 评估集（人工标注），写入 `evaluation.jsonl`。
2. 实现三套检索：`only_vector` / `only_bm25` / `hybrid_rrf`，对比 top-k 命中率。
3. 引入 Rerank，对比 top-3 准确率。
4. 用 **RAGAS** 跑自动评估（忠实度、答案相关性、上下文召回率）。

---

## 6. 避坑点

- 中文 Embedding 必须用中文友好的模型（`bge-m3` / `Qwen3-Embedding` / `text2vec`），**别用 `text-embedding-ada-002`**（对中文一般）。
- 向量库**建索引时**的 `ef_construction`（HNSW）、`nlist`（IVF）参数会显著影响召回。
- 别忘了**元数据过滤**（如按部门、时间过滤）—— 这是企业 RAG 的刚需。
- 长文档表格式数据丢失：用 **Unstructured.io** 或 **LlamaParse** 先解析。

---

## 7. 学习检查点

> 能画出"用户 Query → 改写 → 混合召回 → 重排 → Prompt 拼接 → LLM"的完整链路，并说清楚每个环节的失败模式。
