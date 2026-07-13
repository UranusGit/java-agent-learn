# Java 工程师 AI 实战学习仓库

> 面向有 Java 后端经验的工程师，从零起步用 **LangChain4j + Spring AI** 双轨进入 AI 应用开发。
> 理念：**先动手，再补理论**。

---

## 目录结构

```
desc/
├── 00-README.md                              # 你正在看的这个文件
│
├── plan/                                     # 📋 学习计划（任务导向，按这个走）
│   ├── 00-整体路线.md                        # 8 阶段主线 + 阶段 8+ 长期方向
│   └── progress.md                           # 进度追踪
│
├── tutorials/                                # 🛠️ 教程文档（代码导向，对照手搓）
│   ├── langchain4j/                          # LangChain4j 系列（阶段 1 入门）
│   │   ├── 01-快速起步.md
│   │   ├── 02-ChatMemory.md
│   │   ├── 03-Tool调用.md
│   │   ├── 04-AiServices声明式.md
│   │   ├── 05-RAG实战.md
│   │   ├── 06-流式输出.md
│   │   ├── 07-本地模型集成.md
│   │   ├── 08-DeepSeek集成.md
│   │   └── 09-常见错误与排查手册.md
│   │
│   ├── spring-ai/                            # Spring AI 系列（主线，最终交付栈）
│   │   ├── 01-快速起步.md
│   │   ├── 02-Advisor链.md
│   │   ├── 03-Tool调用.md
│   │   ├── 04-RAG实战.md
│   │   ├── 05-结构化输出与Prompt模板.md
│   │   ├── 06-流式与多模型.md
│   │   ├── 07-与LangChain4j对比.md
│   │   ├── 08-升级到SpringAI2.0.md          # 阶段 5 Day 1-3
│   │   ├── 09-MCP接入实战.md                 # 阶段 5 + 阶段 8.1
│   │   └── 10-Anthropic五大Workflow模式.md   # 阶段 4 Week 5
│   │
│   └── agent/                                # Agent 进阶系列（阶段 4）
│       ├── 00-阶段总览.md
│       ├── 01-Tool设计原则.md
│       ├── 02-防止Agent失控.md
│       ├── 03-多Tool编排.md
│       ├── 04-实战项目-个人助理.md
│       ├── 05-实战项目-智能运维.md
│       └── 06-评估与测试.md
│
└── reference/                                # 📚 理论字典（概念导向，按需查阅）
    ├── 心智模型与决策树/                     # 入门必读（阶段 0.5）
    │   └── 01-心智模型与决策树.md
    │
    ├── 理论基础/                             # 概念字典，按需查
    │   ├── 02-RAG深度优化.md
    │   ├── 03-Agent原理.md
    │   └── 04-多模态与多Agent.md
    │
    ├── 工程架构/                             # Java 工程师核心
    │   ├── 05-Java与AI融合架构.md
    │   ├── 06-模型服务部署.md
    │   └── 07-模型微调.md
    │
    ├── 选型与对比/                           # 决策类（核心）
    │   ├── 08-SpringAI与LangChain4j分工模型.md       # 理论范式（部分过时）
    │   ├── 09-企业级Java-AI架构选型真相.md           # ⚠️ 现实校正（选型前必读）
    │   └── 10-SpringAI-vs-LangChain4j何时用何框架.md # ⭐ 决策手册（选型最终答案）
    │
    ├── 生产化与运营/                         # LLMOps + 生产工程
    │   ├── 11-LLMOps.md
    │   ├── 12-ClaudeCode源码启示录.md
    │   ├── 14-MCP协议与生态.md                       # P0 新增
    │   ├── 15-成本工程与PromptCache.md               # P1 新增
    │   └── 16-Agent可靠性工程Java视角.md             # P2 新增（编号 13 留作扩展位）
    │
    └── 长期方向/                             # 架构师视角
        └── 13-架构师进阶.md
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

更多类比见 [reference/心智模型与决策树/01-心智模型与决策树.md](./reference/心智模型与决策树/01-心智模型与决策树.md)

---

## 立即开始

1. 打开 [plan/00-整体路线.md](./plan/00-整体路线.md) 看完整路线
2. 从"阶段 0：环境准备"开始
3. 进入阶段 1 时，对照 [tutorials/langchain4j/01-快速起步.md](./tutorials/langchain4j/01-快速起步.md) 手搓代码

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
- ❌ 不要追新框架：每周都有新框架，盯死 **Spring AI 2.0**（LangChain4j 仅作入门与对照）
- ❌ 没跑通就上复杂特性（RAG/Agent）：基础不牢地动山摇
- ❌ **不要一上来就搞"Spring AI + LangChain4j 混用"**：理论范式，企业不这么做（详见 `reference/选型与对比/09`）
- ❌ **盲目追求自主 Agent**：能用 Workflow（确定性 DAG）解决的不要用 Agent
- ❌ **过早押注 Beta 框架**（Embabel/Koog/Google ADK）：跟进不押注
- ✅ 写文章输出：博客/笔记是最佳学习加速器
