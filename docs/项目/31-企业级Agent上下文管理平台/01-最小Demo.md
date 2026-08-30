# 01-最小 Demo：五层管线最小装配

> **定位**：用不到百行造出上下文中枢的最小骨架：**① 五层各供一段内容 ② 估算总 Token ③ 超预算按序丢弃低优先层 ④ 输出装配结果**。验证三件事：层可装配、预算可估、裁剪有序。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 04-企业级架构主干/04-多页面流式响应与会话管理 §2](../../教程/09-前沿专题/11-上下文工程生产化.md)。
>
> **铁律 0**：管线自研「概念代码」；Token 估算用近似 tokenizer。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①五个 ContextLayer（系统/工具/记忆/RAG/用户）②Token 估算 ③超预算按优先级裁 ④装配输出 |
| **影响了哪些模块** | 单体 ContextAssembler |
| **架构如何演进** | 从无到有：先证明"装配+有序裁剪"可行 |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①五层装配成功 ②超预算时 RAG→记忆→工具顺序被裁、系统/用户保留 ③装配结果可直接喂 ChatClient。

### 一.1 本节核对（四问与迭代验收）

- [ ] 四问四行齐全且"上一版痛点=无（起点）"表述自洽；本迭代验收三条可度量，非空话
- [ ] 验收①「五层装配成功」与正文 §二 的 `List.of(...)` 五层一一对应；②「裁剪顺序」与 `trimOrDrop` 的优先级逻辑一致

## 二、最小管线


```java
// 概念代码：五层最小装配
@Component
public class ContextAssembler {
    public String assemble(int budget, String userInput) {
        var layers = List.of(
            new ContextLayer(0, est(systemPrompt()), () -> systemPrompt()),   // 永不裁
            new ContextLayer(1, est(toolSchemas()),  () -> toolSchemas()),
            new ContextLayer(2, est(memoryDigest()), () -> memoryDigest()),
            new ContextLayer(3, est(ragEvidence()),  () -> ragEvidence()),
            new ContextLayer(4, est(userInput),      () -> userInput));        // 永不裁
        int total = layers.stream().mapToInt(ContextLayer::tokenEstimate).sum();
        while (total > budget) {                       // 超预算:从低优先(3→2→1)裁
            ContextLayer victim = lowestTrimmable(layers);
            layers = trimOrDrop(layers, victim);
            total = recalc(layers);
        }
        return render(layers);                          // 稳定层在前(缓存友好序)
    }
}
```

### 二.1 本节测试与验证（最小管线装配）

> 装配结果不以"对错"断言，而以"层保留/裁剪顺序/边界底线"断言；`est()` 用近似 tokenizer（铁律 0）。

**前置条件**：`ContextAssembler` 五层（`systemPrompt/toolSchemas/memoryDigest/ragEvidence/userInput` 的 `est` 取值）可拿真实值；`render` 可输出。

**材料——构造输入**：设 `systemPrompt=200`、`toolSchemas=100`、`memoryDigest=100`、`ragEvidence=200`、`userInput=200`（token 估算值），初始 `total=800`。

**核对命令**（按 §二 代码手写用例后执行）：

```bash
mvn test -Dtest=ContextAssemblerTest        # 五层装配/裁剪顺序/极端保底三组用例
# 预期输出（节选）：
#   Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
#   budget=600 裁序 RAG→记忆→工具；系统/用户层未被裁
#   BUILD SUCCESS
```


**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `assemble(budget=900, ...)`（充足） | 返回五层全保留，`render` 含全部层内容 |
| 2 | `assemble(budget=600, ...)`（紧张） | 裁剪顺序为 RAG(200)→记忆(100)→工具(100)，`total` 降至 ≤600，系统/用户层保留 |
| 3 | `assemble(budget=300, ...)`（极端） | 仅剩系统提示+用户输入（400≤? 若 200+200=400>300 则裁无可裁时保底 → 至少这两层仍在），其余层被裁 |
| 4 | 输出格式 | `render` 结果为可拼接字符串/消息列表，可直接喂 ChatClient |

**失败排查**：①系统/用户被裁→`trimOrDrop` 未跳过 `lowestTrimmable` 里的"永不可裁"标记层；②裁剪顺序乱（裁了系统）→优先级未按层序写死；③预算充足却丢了层→`est` 与真实渲染 token 估算偏差过大。

## 三、验收

| 输入 | 期望 |
|------|------|
| 预算充足 | 五层全保留 |
| 预算紧张 | RAG→记忆→工具顺序被裁 |
| 极端预算 | 系统提示+用户输入仍在 |

### 三.1 本节核对（验收与下一步）

- [ ] 验收表三行分别与 §一.1（充足保五层/紧张裁序/极端保底线）、§二.1（步骤 1/2/3）对应，已通过即本迭代 PASS
- [ ] 三行验收均达成后回看 §一.1 验收指标，无口径不一致

## 四、全篇回归验证

> 各节断言已上移至 §二.1（最小管线装配）；本表为整篇迭代的回归验收，不重复材料。

| # | 验收项（断言） | 标准 | 复验方式 |
|---|---------------|------|---------|
| 1 | 五层装配成功 | 预算充足五层全保留 | 复验：执行 §二.1 核对命令 |
| 2 | 裁剪有序 | 预算紧张按 RAG→记忆→工具裁，`total≤budget` | §二.1 步骤2 |
| 3 | 底线保留 | 极端预算系统/用户层仍在 | §二.1 步骤3 |
| 4 | 产物可用 | `render` 可直接喂 ChatClient | §二.1 步骤4 |

**回归失败排查**：按 §二.1 失败排查逐条回溯（永不可裁标记缺失/优先级未写死/est 偏差过大）。

## 五、验收对照

> 00-需求分析量化验收①（超预算 0 报错）与②（系统/用户 100% 保留）在本迭代以最小裁剪闭环首次收口（工程化后复验）。

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 超预算 0 报错 | 裁剪兜底不抛异常、`total≤budget` | ✅（§二.1 步骤2-3） |
| 系统/用户 100% 保留 | 永不可裁层在极端预算下仍在 | ✅（§二.1 步骤3） |
| 裁剪有序 | RAG→记忆→工具优先级裁剪 | ✅（§二.1 步骤2） |

> **下一步**：最小裁剪是"整层丢弃"太粗暴。02 迭代做**管线工程化**——层注册/部分裁剪（RAG 减 topK 而非全丢）。
