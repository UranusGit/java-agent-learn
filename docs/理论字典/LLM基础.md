# 理论字典：LLM 基础

> 按需查阅，不按顺序读。写代码/读教程时概念不懂 → 来这里查。

| 概念 | 一句话解释 | Java 类比 |
|------|---------|---------|
| **LLM** | 一个超大的文字函数：输入文字，输出文字 | 远程 RPC 服务 |
| **Token** | LLM 的最小处理单位（≈ 0.75 英文词 / 0.5 中文汉字） | 字符串长度单位 |
| **Context Window** | 模型一次能处理的最大 token 数（4K-200K） | RPC body 最大长度 |
| **Temperature** | 输出随机性（0=确定，1=创意） | 随机种子 |
| **Embedding** | 把文字变成数字向量（语义指纹） | 数据库索引的哈希 |
| **Function Calling** | LLM 输出 JSON 让你执行本地方法 | RPC 的反向调用 |
| **System Prompt** | 设定 AI 人格和行为规则 | HTTP Header |
| **ChatMemory** | 管理对话历史（因为 LLM 无状态） | Session 存储 |
| **Prompt Cache** | 缓存固定 prompt 部分省 90% 费用 | HTTP Cache-Control |
| **Streaming（SSE）** | 逐 token 返回而非等完整回复 | Chunked Transfer |
| **Hallucination（幻觉）** | LLM 编造不存在的事实 | RPC 返回了错误数据 |

## 相关文档
- 入门教程：`阶段0-地基/03-LLM基础认知.md`
- 深入：`阶段4-生产化/01-上下文工程.md`
