# Java 工程师 AI 实战学习仓库

> 面向有 Java 后端经验的工程师，从零起步用 **LangChain4j + Spring AI** 双轨进入 AI 应用开发。
> 理念：**先动手，再补理论**。

---

## 目录结构

```
desc/
├── 00-README.md                  # 你正在看的这个文件
│
├── plan/                         # 📋 学习计划（任务导向，按这个走）
│   ├── 00-整体路线.md            # 8-12 周完整路线
│   └── progress.md               # 进度追踪
│
├── tutorials/                    # 🛠️ 教程文档（代码导向，对照手搓）
│   ├── langchain4j/              # LangChain4j 系列（阶段 1）
│   │   ├── 01-快速起步.md
│   │   ├── 02-ChatMemory.md
│   │   ├── 03-Tool调用.md
│   │   ├── 04-AiServices声明式.md
│   │   ├── 05-RAG实战.md
│   │   ├── 06-流式输出.md
│   │   ├── 07-Ollama本地集成.md
│   │   ├── 08-DeepSeek集成.md
│   │   └── 09-常见错误与排查手册.md
│   └── spring-ai/                # Spring AI 系列（阶段 2）
│       ├── 01-快速起步.md
│       ├── 02-Advisor链.md
│       ├── 03-Tool调用.md
│       ├── 04-RAG实战.md
│       ├── 05-结构化输出与Prompt模板.md
│       ├── 06-流式与多模型.md
│       └── 07-与LangChain4j对比.md
│   │
│   └── agent/                    # Agent 进阶系列（阶段 4）
│       ├── 00-阶段总览.md
│       ├── 01-Tool设计原则.md
│       ├── 02-防止Agent失控.md
│       ├── 03-多Tool编排.md
│       ├── 04-实战项目-个人助理.md
│       ├── 05-实战项目-智能运维.md
│       └── 06-评估与测试.md
│
└── reference/                    # 📚 理论字典（概念导向，按需查阅）
    ├── 01-RAG深度优化.md
    ├── 02-Agent原理.md
    ├── 03-模型服务部署.md
    ├── 04-Java与AI融合架构.md
    ├── 05-模型微调.md
    ├── 06-LLMOps.md
    ├── 07-多模态与多Agent.md
    ├── 08-架构师进阶.md
    └── 09-心智模型与决策树.md
```

---

## 三类文档怎么用

| 类型 | 用途 | 何时读 |
|------|------|--------|
| **plan/** | 学习路线、任务清单、进度追踪 | **每天**对照看，跟着走 |
| **tutorials/** | 代码级教程，API 细节 + 完整示例 + 报错排查 | **写代码时**对照手搓 |
| **reference/** | 概念、原理、架构、决策树 | **遇到概念不懂时**查阅 |

---

## 核心心智模型（一图记住）

**把"模型推理"看作一个特殊的微服务**：高延迟、不稳定、有概率出错。

- LLM API = 一个有概率出错的远程 RPC
- Prompt = RPC 的请求参数模板
- Tool = 注解声明的 RPC，LLM 帮你"决定调用哪个"
- 向量库 = 一个特殊的 B+ 树索引（按相似度查而不是精确匹配）
- Agent = `while(true) { decide(); act(); observe(); }` 循环
- LangChain4j / Spring AI = AI 版的 Spring Framework

更多类比见 [reference/09-心智模型与决策树.md](./reference/09-心智模型与决策树.md)

---

## 立即开始

1. 打开 [plan/00-整体路线.md](./plan/00-整体路线.md) 看完整路线
2. 从"阶段 0：环境准备"开始
3. 进入阶段 1 时，对照 [tutorials/LangChain4j-01-快速起步.md](./tutorials/LangChain4j-01-快速起步.md) 手搓代码

---

## 学习纪律

- **手搓优先**：不复制粘贴框架搭好的骨架，自己写 pom.xml 和 Main
- **每周 Git 提交**：至少 5 次，commit message 写清楚
- **每周学习笔记**：写一篇 200-500 字，存到 `desc/notes/`（按需创建）
- **不追新框架**：盯死 LangChain4j + Spring AI
- **不跳阶段**：前一阶段没跑通，不要急着进下一阶段

---

## 防止迷失的红线

- ❌ 不要试图学会所有模型架构：会用比会改重要 10 倍
- ❌ 不要陷在 Python 教程里：你是 Java 工程师，每个概念都用 Java 实现一遍才算掌握
- ❌ 不要追新框架：每周都有新框架，盯死 LangChain4j + Spring AI
- ❌ 没跑通就上复杂特性（RAG/Agent）：基础不牢地动山摇
- ✅ 写文章输出：博客/笔记是最佳学习加速器
