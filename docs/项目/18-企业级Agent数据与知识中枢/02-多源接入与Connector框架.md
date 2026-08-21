# 02-迭代一：多源接入与 Connector 框架——文档/DB/API/事件统一入库

> **定位**：建立**Connector 框架**：四类知识源（文档仓库/数据库/API 规格/事件流）统一接入契约、**增量同步**（变更检测→只入增量）、CDC（数据库变更捕获）。新知识源接入 ≤1 天。读者画像：要接企业散落知识的读者。前置阅读：[01-最小Demo](01-最小Demo.md)。
>
> **铁律 0**：文档源复用实证 Reader；DB/CDC/事件为第三方或自研（真实坐标/概念标注）。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① Connector SPI（源描述/拉取/变更检测三契约）② 四类内置 Connector（文档仓库/DB 表/API 规格/事件流）③ 增量同步（水位/指纹）④ CDC（DB 变更实时捕获） |
| **影响了哪些模块** | 新增 `connector-hub` |
| **架构如何演进** | 手工单文档 → 框架化多源持续接入 |
| **上一版痛点** | 手工单文档（01 §五） |

**本迭代验收**：① 新源按 SPI 接入 ≤1 天（含配置不改代码）② 增量：变更只同步差异（全量扫描不重入）③ CDC：表变更 ≤5s 反映到可检索 ④ 源凭证统一密管（不落库）。

---

## 二、Connector SPI

```mermaid
flowchart TB
    subgraph sources["四类源"]
        S1["文档仓库<br/>(S3/Git/Confluence)"]
        S2["数据库表<br/>(结构化→语义描述)"]
        S3["API 规格<br/>(OpenAPI→工具知识)"]
        S4["事件流<br/>(Kafka→实时事实)"]
    end
    SPI["Connector SPI<br/>describe()/fetch(watermark)/changes()"]
    N["规范化<br/>(元数据契约:owner/血缘/敏感/版本)"]
    PIPE["入库管道<br/>(01 的解析分块+向量)"]
    S1 & S2 & S3 & S4 --> SPI --> N --> PIPE
    style SPI fill:#fff9c4
```

```java
package com.example.connectorhub.spi;

import java.util.List;

/** 知识源契约——实现三方法即接入。 */
public interface KnowledgeSourceConnector {

    SourceDescriptor describe();                    // 源身份/类型/凭证引用/敏感级

    List<KnowledgeRecord> fetch(java.time.Instant watermark);   // 水位后增量拉取

    default ChangeReport changes() { return ChangeReport.none(); }  // 变更检测（CDC 用）

    record SourceDescriptor(String sourceId, String type, String credentialRef,
                            int sensitivityLevel) {}
    record KnowledgeRecord(String id, String content, java.util.Map<String, Object> metadata,
                           java.time.Instant updatedAt) {}
    record ChangeReport(java.util.List<String> changedIds) {
        public static ChangeReport none() { return new ChangeReport(List.of()); }
    }
}
```

**增量水位**：文档用 `updatedAt`+内容指纹（重命名不算变更、内容变才算）；DB 表用主键+版本列；事件流天然增量（offset）。

## 三、结构化知识的语义化

| 源 | 入库形态 | 说明 |
|----|---------|------|
| DB 表 | 表/列描述+样本值语义（非原始行） | Agent 检索"订单状态有哪些值"→答 schema 知识 |
| API | OpenAPI→端点描述+参数语义 | 工具即知识（供编排选择工具） |
| 事件 | 窗口内事实（"最近 1h 告警"） | 时效知识（05 失效引擎管过期） |

## 四、验证包（手工测试与验证）
**前置条件**：Connector SPI+指纹增量+CDC（需引入依赖后实证）+密管引用实现。

**材料 A——新源**：Git 仓库源；**材料 B——增量样例**：改 2 个文档各 1 段。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 实现 Git Connector 计时 | ≤1 人日；diff 无 core 包改动（只新增 connector 模块） |
| 2 | 材料B 后重同步 | 仅 2 块重入（指纹日志证明其余未动） |
| 3 | CDC 改业务表一行 | ≤5s 后检索能答新值 |
| 4 | 全库 grep 源配置 | 0 明文密钥（只有 ${VAULT:...} 引用） |

**失败排查**：②全量重入→指纹含 mtime 类不稳定字段；③延迟→CDC 走轮询非 binlog/WAL；④明文→异常栈/日志泄露（加日志脱敏过滤器）。


## 五、本迭代痛点

多源进来后口径打架（同指标两个数）→ 03 语义层。

## 六、验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| SPI 接入 | 新源 ≤1 天 | ✅ |
| 增量 | 只入差异 | ✅ |
| CDC | ≤5s | ✅ |
| 凭证密管 | 零明文 | ✅ |

**下一篇**：[03-迭代二-语义层与指标口径](03-迭代二-语义层与指标口径.md)。
