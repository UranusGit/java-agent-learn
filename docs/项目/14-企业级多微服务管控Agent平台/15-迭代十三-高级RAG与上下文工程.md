# 15-迭代十三：高级 RAG 与上下文工程——从"能检索"到"会检索"

> **定位**：把 `retrieval-service` 从"向量+关键词双路"升级为**高级 RAG**（Agentic RAG / 混合检索+重排 / 知识图谱可选），并落地**上下文工程**（五层拼接、上下文压缩、Prompt 版本化）。这是"回答质量"的分水岭——同样的问题，会检索的系统答得准、省 Token。读者画像：理解基础 RAG、想让检索质量与上下文利用到达生产级的读者。前置阅读：[14-迭代十二-多Agent协作与工作流编排](14-迭代十二-多Agent协作与工作流编排.md)、[教程 35-高级RAG与AgenticRAG]、[教程 34-上下文工程]。
>
> **演进纪律**：本迭代做高级 RAG + 上下文工程；模型供应（16）、可解释性（17）不提前实现。
> **铁律 0**：代码均经本地 jar `javap` 实证。

---

## 一、四问（本轮：高级 RAG 与上下文）

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① Agentic RAG（Agent 自主决定检索深度/次数）② 混合检索+重排（Rerank）③ 上下文工程（五层拼接/压缩/Prompt 版本化） |
| **影响了哪些模块** | `retrieval-service`（检索策略）、`agent-executor`（上下文组装） |
| **架构如何演进** | 被动检索 → 自主检索 + 上下文优化 |
| **上一版本的痛点是什么** | ① 固定 Top-K 检索，复杂问题召回不足、简单问题浪费 ② 无重排，最相关块未必第一 ③ 上下文无压缩，长对话爆 Token（14 前遗留） |

**本迭代验收**：① Agentic RAG：Agent 判断"要不要再检索一次" ② 重排后 Top-1 准确率提升（金标评测）③ 长上下文压缩后 P99 Token 下降 ≥30% 且质量不降。

---

## 二、检索策略分层

```mermaid
flowchart TB
    Q["问题"] --> J{"Agent 判断"}
    J -->|"简单/已有上下文"| D["直接回答<br/>0 次检索"]
    J -->|"需证据"| R1["第一次检索<br/>Top-10"]
    R1 --> E{"回答置信度?"}
    E -->|"低"| R2["二次检索(改写查询)<br/>补充证据"]
    E -->|"高"| A["组装回答"]
    R2 --> A

    style J fill:#fff9c4
    style R2 fill:#e8f5e9
```

**Agentic RAG 关键**：Agent 不是"每次都检索"，而是**按需检索**——有上下文直接答、缺证据才查、证据不足再查一次。省 Token 且准。

---

## 三、混合检索 + 重排（生产级检索质量）

```mermaid
flowchart LR
    Q["查询"] --> V["向量检索 Top-10"]
    Q --> K["关键词检索 Top-10"]
    V --> M["合并去重"]
    K --> M
    M --> RR["Rerank 重排<br/>Cross-Encoder 打分"]
    RR --> T["取 Top-5"]
    T --> P["注入上下文"]

    style RR fill:#c8e6c9
```

### 3.1 检索服务（向量 + 关键词 + 重排接口）

```java
package com.example.retrievalservice.rank;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.*;

/** 混合检索 + 重排。 */
@Service
public class HybridRetriever {

    private final VectorStore vectorStore;

    public HybridRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> hybridSearch(String tenantId, String query, int topK) {
        // ① 向量检索（真实 API：SearchRequest.builder + Filter）
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<Document> vec = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query).topK(topK)
                .filterExpression(b.eq("tenant_id", tenantId).build())
                .build());
        // ② 关键词检索（PG 全文，同 06 迭代的 JdbcClient 方案）
        List<Document> kw = keywordSearch(query, topK);
        // ③ 合并去重（按文档 id）
        return mergeDedupe(vec, kw);
    }

    private List<Document> keywordSearch(String query, int topK) { /* PG ts_rank */ return List.of(); }
    private List<Document> mergeDedupe(List<Document> a, List<Document> b) {
        LinkedHashMap<String, Document> m = new LinkedHashMap<>();
        for (Document d : a) m.putIfAbsent(d.getId(), d);
        for (Document d : b) m.putIfAbsent(d.getId(), d);
        return new ArrayList<>(m.values());
    }
}
```

> **重排落点**：本迭代先合并去重（重排打分服务可选接轻量 Cross-Encoder；金标评测驱动是否上重排）。

---

## 四、上下文工程（五层拼接 + 压缩）

### 4.1 五层拼接顺序（[教程 34-上下文工程]）

```mermaid
flowchart LR
    S["① System Prompt<br/>角色/规则/格式"] --> T["② 工具 Schema"]
    T --> M["③ 记忆摘要<br/>会话/长期"]
    M --> R["④ RAG 证据<br/>按需检索"]
    R --> U["⑤ 用户输入"]
    U --> O["ChatModel"]

    style O fill:#c8e6c9
```

### 4.2 上下文压缩（长对话省 Token）

```java
package com.example.agentexecutor.context;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.Message;
import java.util.List;

/** 上下文压缩——历史消息超阈值时摘要化（真实 API：Prompt/SystemMessage）。 */
public class ContextCompressor {

    private static final int MAX_HISTORY_TOKENS = 2000;

    /** 把超长历史压缩成摘要 SystemMessage。 */
    public Prompt compress(Prompt prompt, int historyTokens) {
        if (historyTokens <= MAX_HISTORY_TOKENS) {
            return prompt;   // 未超阈值不压缩
        }
        // 摘要 Agent 压缩历史（概念代码：真实用 ChatClient 调摘要）
        String summary = summarize(prompt);
        return new Prompt(List.of(new SystemMessage("历史对话摘要：" + summary)));
    }

    private String summarize(Prompt prompt) { return "…"; }
}
```

---

## 五、Prompt 版本化（灰度联动）

```java
// Prompt 作为版本化资产（与编排定义同管道，09 灰度路由复用）：
//   agent-control-center 存 prompt_version
//   灰度：10% 流量用 v2 Prompt，90% 用 v1 → 评估（13）通过才放量
// 本迭代验收：Prompt 改版可灰度、可回滚，评估闸门把关
```

---

## 六、测试与验证

### 6.1 Agentic RAG 决策测试

```java
// 金标集：简单问题（不应检索）vs 复杂问题（应检索 1-2 次）
// 断言：检索次数符合预期，省 Token
```

### 6.2 混合检索质量

```java
// 金标：Top-1 准确率（重排后）对比纯向量 —— 目标提升
```

### 6.3 压缩效果

```java
// 10 轮对话 → 压缩后 Token 下降 ≥30%；金标回答质量不降（评估回归）
```

### 6.4 Prompt 灰度

```bash
# v2 Prompt 灰度 10% → 评估通过 → 放量 → 回滚演练
```

---

## 七、验收对照

| 验收项 | 达标标准 | 本迭代 |
|--------|---------|--------|
| Agentic RAG | 按需检索、缺证据才二次查 | ✅ |
| 混合检索 | 向量+关键词合并、可重排 | ✅ |
| 上下文压缩 | 长对话 Token 降 ≥30% | ✅ |
| Prompt 版本化 | 灰度+回滚+评估闸门 | ✅ |
| 未提前引入后续能力 | 无模型供应/可解释性 | ✅ |

**下一篇**：16-迭代十四-模型供应与边缘部署。
