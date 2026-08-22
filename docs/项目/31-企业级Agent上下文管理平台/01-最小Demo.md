# 01-最小 Demo：五层管线最小装配

> **定位**：用不到百行造出上下文中枢的最小骨架：**① 五层各供一段内容 ② 估算总 Token ③ 超预算按序丢弃低优先层 ④ 输出装配结果**。验证三件事：层可装配、预算可估、裁剪有序。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 57 §2](../../教程/57-上下文工程生产化.md)。
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

## 三、验收

| 输入 | 期望 |
|------|------|
| 预算充足 | 五层全保留 |
| 预算紧张 | RAG→记忆→工具顺序被裁 |
| 极端预算 | 系统提示+用户输入仍在 |

> **下一步**：最小裁剪是"整层丢弃"太粗暴。02 迭代做**管线工程化**——层注册/部分裁剪（RAG 减 topK 而非全丢）。
