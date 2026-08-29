# 01-最小 Demo：三层记忆最小读写

> **定位**：用不到百行造出记忆中枢的最小骨架：**① 官方 ChatMemory 工作记忆 ② 一个自研 ChatMemoryRepository 长期持久化 ③ 一个向量库语义召回**，一个请求写入三层、读出三层。验证三件事：工作记忆窗口、长期记忆不因重启丢、语义召回命中。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 95-工业级记忆架构](../../教程/95-工业级记忆架构.md)。
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

### 一.1 本节核对（四问）

- [ ] 四问口径齐全（新增需求/影响模块/架构演进/上一版痛点"无（起点）"）
- [ ] 三条本迭代验收可度量：写一次三层可查 / 重启不丢 / 语义召回命中，均是可判定动作

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

### 二.1 本节测试与验证（三层读写最小闭环）

**前置条件**：`MemoryHub`（短窗 `shortMem` + 长期 `longMem` 自研 Repository + `vectorStore` 三适配）可编译；一个对话入口能触发 `remember(convId, msg)` 与 `recall(convId, query)`。

**材料——核对命令/调用**（按 §十 代码手写后执行，无外部 API）：

```bash
mvn clean compile                     # 先编译通过
mvn spring-boot:run                   # 启动单体 MemoryHub
# 通过对话入口依次触发 remember / recall（无固定端点名，按手写入口）
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 编译启动 | `BUILD SUCCESS`；`MemoryHub` Bean 装配无缺依赖报错 |
| 2 | 写入"我偏好深色主题" → 触发 `recall` | 短窗 `get(convId)` 返回该会话消息；三层（短/长/向量）均能查到，`MemoryPacket` 三字段非空 |
| 3 | 模拟重启（新实例 load 长期 Repository 后 query） | 长期记忆仍能读到（自研 Repository 落库，重启不丢）；纯内存短窗按预期清空 |
| 4 | `recall` 查询"喜欢什么主题" | 语义召回命中"偏好深色主题"（写入过向量索引的记录上榜） |

**失败排查**：①写后三层查不全→`remember` 未完整写满三层（漏 `saveAll` 或漏 `vectorStore.add`）；②重启丢长期→自研 `ChatMemoryRepository` 未真正落库（内存实现）；③语义召回不命中→`vectorStore.add` 未执行或 query 向量与写入差异过大。

## 三、为什么三层分开

| 层 | 读法 | 何时用 |
|----|------|--------|
| 工作记忆 | 全窗口(短) | 本次对话连续上下文 |
| 长期记忆 | 语义召回(按相关度) | 跨会话记住偏好/事实 |
| RAG | 知识库相似度 | 外部知识文档 |

### 三.1 本节核对（为什么三层分开）

- [ ] 三层各自的"读法"与"何时用"能复述；"不要混用一个存储"的三层后果（窗口扫全量超 Token / 语义被窗口稀释 / 语义请求错配）能说出至少两条
- [ ] 三层的读法语义（全窗口 / 语义召回 / 相似度）与 §二 的 `recall` 三路读取一一对应

## 四、全篇回归验证

> §二.1（三层读写）与 §三.1（三层语义）均通过后的整体验收。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 重建一轮完整链路：写入"偏好深色" → 三层读 → 模拟重启 → 语义召回"喜欢什么主题" | 全程三层一致：写有、重启持久化不丢、语义命中；任一步 FAIL 按 §二.1 排查项回溯 |
| 2 | 核对三层读法语义 | 短窗 `get(convId)` 返回本会话；长期/RAG 各按召回/相似度返回，未被窗口稀释 |

**回归失败排查**：按 §二.1 的失败排查逐条回溯（漏写某层 / Repository 未落库 / 向量 add 未执行）。

> **下一步**：三层读写已通，但①长期记忆无管理（写满/重复/无元数据）②工作记忆窗口无策略 → 02 迭代做**工作记忆与会话窗口管理**，03 迭代做**长期持久化工程化**。
