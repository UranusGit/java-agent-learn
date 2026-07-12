# 学习进度追踪

> 每完成一项把 `[ ]` 改成 `[x]`，填上完成日期。
> 不要赶进度，前一阶段没跑通不要急着进下一阶段。

---

## 阶段 0：环境准备（Day 0）

- [ ] JDK 21 已确认（`java -version`）
- [ ] Maven 3.9+ 已安装（`mvn -v`）
- [ ] IDEA 准备好（可选：Markdown 插件）
- [ ] 模型服务二选一：
  - [ ] 方案 A：Ollama 已安装 + 已拉模型（如 `qwen2.5:7b`）
  - [ ] 方案 B：DeepSeek API Key 已获取
- [ ] curl 或 Postman 能调通 LLM 拿到回复

**完成日期**：______

---

## 阶段 1：LangChain4j 基础（Week 1）

> 对照教程：[tutorials/LangChain4j-01-快速起步.md](../tutorials/LangChain4j-01-快速起步.md) 起

### Day 1-2：第一次调用
- [ ] 在 `pom.xml` 加 LangChain4j 依赖
- [ ] 写 `Main.java`，调用 LLM，打印回复
- [ ] 理解 `ChatLanguageModel` 抽象

### Day 3-4：多轮对话 + 记忆
- [ ] 加 `ChatMemory`，实现多轮对话
- [ ] 命令行交互式 REPL

### Day 5-6：第一个 Tool
- [ ] 写一个 `@Tool` 方法
- [ ] 让 LLM 自己决定调用
- [ ] 观察日志中的 Function Calling JSON

### Day 7：声明式 Agent
- [ ] 用 `AiServices` 重构
- [ ] 写一篇学习笔记（300 字）

**阶段验收**：
- [ ] 命令行多轮对话正常
- [ ] 至少 1 个自定义 Tool 可用
- [ ] 能讲清 ChatMemory 工作原理
- [ ] Git 提交至少 5 次

**完成日期**：______

---

## 阶段 2：Spring AI 切入（Week 2）

### Day 1-2：Spring Boot 项目搭建
- [ ] 新建 `demo02-spring-ai` 项目
- [ ] 加 `spring-ai-openai-spring-boot-starter`
- [ ] 配置 `application.yml`
- [ ] `/hello` 接口调通 LLM

### Day 3-4：ChatClient + Advisor
- [ ] 注入 `ChatClient.Builder`
- [ ] 加 `MessageChatMemoryAdvisor`
- [ ] 理解 Advisor 链

### Day 5-6：Tool（@Tool 注解）
- [ ] Spring AI 的 `@Tool` 重写工具
- [ ] 加数据库 Tool

### Day 7：双框架对比
- [ ] 完成 `desc/对比-LangChain4j-vs-SpringAI.md`

**完成日期**：______

---

## 阶段 3：RAG 入门（Week 3-4）

### Week 3：LangChain4j 实现
- [ ] 引入向量库（Chroma 或 Milvus Lite）
- [ ] 文档加载 → 分块 → 向量化 → 入库
- [ ] `ContentRetriever` 检索增强
- [ ] 10 条 QA 测试集
- [ ] 不同分块参数对比

### Week 4：Spring AI 实现 + 进阶
- [ ] Spring AI 的 `VectorStore` + ETL 管道
- [ ] （可选）混合检索 + 重排
- [ ] （可选）RAGAS 自动评估

**完成日期**：______

---

## 阶段 4：Agent + Tool 进阶（Week 5-6）

- [ ] 设计 3-5 个 Tool
- [ ] LangChain4j 整合
- [ ] Tool 失败兜底
- [ ] Spring AI 重做
- [ ] "生产环境防 Agent 失控"笔记

**完成日期**：______

---

## 阶段 5：生产化（Week 7-9）

- [ ] 流式输出（SSE）
- [ ] 会话持久化（Redis）
- [ ] 超时 + 重试 + 降级
- [ ] token 计费监控
- [ ] JSON 严格解析
- [ ] 接入 Langfuse
- [ ] 限流（Bucket4j）
- [ ] 多模型路由

**完成日期**：______

---

## 阶段 6：综合项目（持续）

**方向选择**（三选一）：
- [ ] A. 智能运维助手（K8s/Prom）
- [ ] B. 企业知识库客服
- [ ] C. 代码评审助手

**完成日期**：______

---

## 学习笔记索引

> 每周写一篇，记录到这里。

| 周次 | 主题 | 文件 |
|------|------|------|
| W1 | | |
| W2 | | |
| W3 | | |
| ... | | |

---

## 踩坑记录

> 遇到坑就记下来，避免重蹈覆辙。

| 日期 | 问题描述 | 解决方案 |
|------|---------|---------|
| | | |
