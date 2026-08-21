# 01-最小 Demo：三层记忆最小读写

> **定位**：用不到百行造出记忆中枢的最小骨架：**① 官方 ChatMemory 工作记忆 ② 一个自研 ChatMemoryRepository 长期持久化 ③ 一个向量库语义召回**，一个请求写入三层、读出三层。验证三件事：工作记忆窗口、长期记忆不因重启丢、语义召回命中。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 52-工业级记忆架构](../../教程/52-工业级记忆架构.md)。
>
> **铁律 0**：`ChatMemory`/`ChatMemoryRepository` javap 实证；持久化/召回自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①官方 ChatMemory 工作记忆(会话内) ②自研 ChatMemoryRepository 长期持久化(跨会话) ③向量库语义召回(RAG) |
| **影响了哪些模块** | 单体 MemoryHub + 三个存储适配 |
| **架构如何演进** | 从无到有：先证明"三层都能读写" |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①写一次，三层都能查到 ②长期记忆重启(模拟)后还能读到 ③"用户偏好深色"能被语义召回命中。

## 二、三层写入 + 读取（最小闭环）

```java
// Spring AI 2.0.0 / Java 21 —— 概念代码：记忆中枢最小骨架
@Component
public class MemoryHub {
    private final ChatMemory shortMem;                // 官方 InMemory(短,实证)
    private final SemanticMemoryRepository longMem;   // 自研 implements ChatMemoryRepository(长)
    private final VectorStore vectorStore;            // RAG语义召回(PgVector)

    // 写入三层：短窗口 + 长期持久化 + 语义索引
    public void remember(String convId, Message m) {
        shortMem.add(convId, m);                     // ①工作记忆(官方)
        longMem.saveAll(convId, List.of(m));         // ②长期持久化(自研Repository)
        vectorStore.add(Document.builder().id(uid()).content(m.getText()).build()); // ③语义索引
    }

    // 读取三层
    public MemoryPacket recall(String convId, String query) {
        List<Message> shortWin = shortMem.get(convId);                          // 工作记忆全窗口
        List<Message> longHit = longMem.semanticRecall(query, 3);               // 长期语义召回top3
        List<Document> ragHit = vectorStore.similaritySearch(query, 3);         // RAG相似度top3
        return new MemoryPacket(shortWin, longHit, ragHit);
    }
}
```

## 三、为什么三层分开

| 层 | 读法 | 何时用 |
|----|------|--------|
| 工作记忆 | 全窗口(短) | 本次对话连续上下文 |
| 长期记忆 | 语义召回(按相关度) | 跨会话记住偏好/事实 |
| RAG | 知识库相似度 | 外部知识文档 |

三层读写语义不同，**不要混用一个存储**（否则短窗口扫全量超 Token / 长期语义检索被窗口稀释）。

## 四、验收

| 输入 | 期望 |
|------|------|
| 写入"偏好深色" → 三层读 | 三层都查到 |
| 模拟重启(新实例load) | 长期记忆不丢(自研Repository落库) |
| 语义召回"喜欢什么主题" | 命中"偏好深色" |
| 短窗口 | `get(convId)` 返回本会话消息 |

> **下一步**：三层读写已通，但①长期记忆无管理（写满/重复/无元数据）②工作记忆窗口无策略 → 02 迭代做**工作记忆与会话窗口管理**，03 迭代做**长期持久化工程化**。
