# 03 · LLM 基础认知

> 阶段：0 地基 · 难度：⭐ · 预计：3 天
> 前置：[02 Spring Boot 入门](02-SpringBoot入门.md)
> 产出：理解 LLM 的基本概念，用 curl 第一次调通 LLM API

---

## 你将学会

- LLM 是什么（用 Java 工程师能听懂的话）
- Token / Context Window / Temperature / Embedding 分别是什么
- 用 curl 调通一次 LLM API（第一次拿到 AI 回复）
- 三种消息角色（System / User / Assistant）
- 为什么多轮对话需要"记忆"

---

## 为什么需要这个

你马上要用 Java 代码调 LLM。如果不理解 LLM 的基本机制，后面的代码你只是"照抄"而不是"理解"。

**核心心智模型**：**把 LLM 当成一个"有概率出错的远程 RPC 服务"**。你发请求（prompt），它返回 JSON（回复）。和调一个普通的 HTTP API 没有本质区别。

---

## 知识讲解

### 1. LLM 是什么

LLM（Large Language Model，大语言模型）= 一个超大的函数：输入一段文字，输出一段文字。

```
输入：你好
输出：你好！有什么可以帮你的？
```

它"住"在远程服务器上（比如 DeepSeek 的机房），你通过 HTTP API 调用它。**它就是一个微服务**，只是：

- 延迟高（几百毫秒到几十秒）
- 输出不稳定（同样的输入，每次输出可能不同）
- 有概率出错（"幻觉"——编造不存在的事实）

### 2. Token（令牌）

LLM 不是按"字"而是按"token"处理文本。Token 是 LLM 的最小处理单位：

| 语言 | 1 token ≈ |
|------|----------|
| 英文 | 0.75 个单词 |
| 中文 | 0.5-1 个汉字 |

**为什么要在意 token**：因为 LLM 按 token 计费。而且每个模型有"上下文窗口"限制（最多处理多少 token）。

### 3. Context Window（上下文窗口）

每个模型能处理的最大 token 数：

| 模型 | 上下文窗口 |
|------|---------|
| GPT-4 | 8K-128K |
| Claude Sonnet | 200K |
| DeepSeek | 64K-128K |

**重要概念**：输入 token + 输出 token ≤ 上下文窗口。超了就报错。

### 4. Temperature（温度）

控制输出随机性：

| Temperature | 效果 | 适合 |
|------------|------|------|
| 0 | 几乎确定性输出（每次一样） | 代码生成、事实问答 |
| 0.7 | 有一定创造性（默认值） | 通用对话 |
| 1.0+ | 高度随机、有创意 | 创意写作 |

> ⚠️ temperature=0 也不保证 100% 相同（因为 GPU 浮点运算有微小不确定性）。

### 5. 三种消息角色

LLM 的对话由三种角色组成：

```json
{
  "messages": [
    {"role": "system",    "content": "你是一个简洁的技术助手"},
    {"role": "user",      "content": "什么是 RAG？"},
    {"role": "assistant", "content": "RAG 是检索增强生成..."}
  ]
}
```

| 角色 | Java 类比 | 作用 |
|------|---------|------|
| **system** | HTTP Header | 设定 AI 的"人格"和行为规则 |
| **user** | HTTP Request Body | 用户的问题/指令 |
| **assistant** | HTTP Response | AI 之前的回复（历史） |

### 6. 为什么多轮对话需要"记忆"

**LLM 是无状态的**。每次调用都是独立的 HTTP 请求，它不记得上一次你说了什么。

所以多轮对话时，你必须**把之前的对话历史一起发过去**：

```
第一次：
  user: 我叫小明
  → assistant: 你好小明！

第二次：
  user: 我叫什么？     ← 如果只发这一句，LLM 不知道你叫什么
  messages: [
    {user: 我叫小明},
    {assistant: 你好小明！},
    {user: 我叫什么？}    ← 把历史一起发，LLM 才知道
  ]
  → assistant: 你叫小明。
```

> 这就是 `ChatMemory` 存在的原因——帮你管理对话历史，每次请求时自动塞回去。

### 7. Embedding（向量嵌入）

把一段文字变成一个数字数组（向量），让计算机能算"语义相似度"：

```
"你好"    → [0.12, 0.85, 0.33, ...]  （768 维向量）
"hello"   → [0.11, 0.82, 0.35, ...]  （和"你好"很接近）
"数据库"   → [0.91, 0.22, 0.77, ...]  （和前两个差很远）
```

> 这是 RAG（检索增强生成）的基础——用向量找到"语义最相关"的文档。阶段 2 会深入讲。

---

## 动手实践

### Step 1：获取 API Key

选一个模型服务（二选一）：

**方案 A：DeepSeek API（推荐，便宜）**
1. 注册 `platform.deepseek.com`
2. 充值 10 元（够你学很久）
3. 创建 API Key

**方案 B：本地 LM Studio（零成本）**
1. 下载 `lmstudio.ai`
2. 搜索下载一个小模型（如 `qwen2.5:7b`）
3. 启动 Local Server（默认端口 1234）

### Step 2：用 curl 第一次调用 LLM

```bash
# DeepSeek
curl https://api.deepseek.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "messages": [
      {"role": "system", "content": "你是一个简洁的技术助手，用一句话回答"},
      {"role": "user", "content": "什么是 AI Agent？"}
    ],
    "temperature": 0.7
  }'
```

你会收到类似这样的 JSON：

```json
{
  "id": "chatcmpl-xxx",
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "AI Agent 是能自主感知环境、做出决策、执行动作来完成任务的智能系统。"
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 25,
    "completion_tokens": 20,
    "total_tokens": 45
  }
}
```

**你刚刚完成了第一次 LLM 调用！** 注意看 `usage`——这就是 token 计费依据。

### Step 3：手动实验

按以下步骤做实验，加深理解：

```bash
# 实验 1：temperature=0 跑 3 次，看输出是否一致
for i in 1 2 3; do
  curl -s https://api.deepseek.com/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"用三个词形容春天"}],"temperature":0}' \
    | grep '"content"'
  echo "---"
done

# 实验 2：temperature=1 跑 3 次，看变化
for i in 1 2 3; do
  curl -s https://api.deepseek.com/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"用三个词形容春天"}],"temperature":1}' \
    | grep '"content"'
  echo "---"
done

# 实验 3：看 system prompt 如何影响输出
curl https://api.deepseek.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "messages": [
      {"role": "system", "content": "你是一个海盗，所有回答都用海盗口吻"},
      {"role": "user", "content": "你好"}
    ]
  }'
```

### Step 4：保存 API Key 到环境变量

不要把 API Key 写在代码里！用环境变量：

```bash
# 创建 .env 文件（已被 .gitignore 忽略）
echo "DEEPSEEK_API_KEY=sk-your-actual-key-here" > .env

# 后续 Spring Boot 会自动加载 .env
```

---

## 常见坑

- ❌ **API Key 泄露** → 绝不提交到 Git。`.env` 已在 `.gitignore` 中
- ❌ **Token 超限** → 对话太长会超上下文窗口。阶段 2 讲 ChatMemory 窗口策略
- ❌ **temperature=0 还是输出不同** → 正常，GPU 浮点不确定性 + top-p 采样
- ❌ **中文 token 比英文贵** → 中文 1 字 ≈ 1-2 token，英文 1 词 ≈ 1.3 token。做成本预算时注意

---

## 验收检查

- [ ] 能用 curl 调通 LLM 并拿到回复
- [ ] 能解释"为什么 LLM 是无状态的"
- [ ] 能解释"为什么多轮对话需要 ChatMemory"
- [ ] 能解释 temperature=0 和 1 的区别
- [ ] 知道 token 计费的三个指标（input/output/total）
- [ ] API Key 保存在环境变量中，没有提交到 Git

---

## 企业级视角补充

### LLM API 调用的成本意识

作为未来的架构师，从第一天起就要有成本意识：

| 场景 | 每次调用 Token | 每次成本（估算） | 1 万次/日成本 |
|------|--------------|----------------|-------------|
| 简单问答 | ~500 | ~$0.001 | ~$10 |
| 带工具的 Agent | ~3000 | ~$0.006 | ~$60 |
| RAG + 长上下文 | ~8000 | ~$0.016 | ~$160 |
| 多 Agent 协作 | ~20000 | ~$0.040 | ~$400 |

> 这些数字会在阶段 4 成本工程中深入讲解。现在只需知道：**每次 LLM 调用都有成本，不是免费的**。

### LLM 不稳定的工程应对

```
LLM 的不稳定性来源：
1. 温度 > 0 → 同一输入不同输出
2. 模型版本升级 → 行为可能变化
3. Provider 服务波动 → 偶尔超时/限流
4. Prompt 微小改动 → 效果可能剧变

工程应对（后面阶段会学）：
- 评估集 → 量化"变好还是变差"
- 重试 + 熔断 → 应对服务波动
- 版本锁定 → 防止模型升级破坏
- Prompt 管理 → 像管代码一样管 Prompt
```

### 随堂练习：Token 成本计算器

> 用 curl 调用 LLM，记录不同 prompt 的 token 消耗，计算月度成本估算。

```bash
# 你的任务：
# 1. 设计 5 种不同复杂度的 prompt（一句话/一段话/带格式/带示例/带约束）
# 2. 分别调用 LLM，记录 prompt_tokens 和 completion_tokens
# 3. 按 DeepSeek 定价计算每次调用成本
# 4. 估算如果每天调用 1000 次，月成本是多少
# 5. 把结果整理成表格

# 提示：
# - DeepSeek 输入价格：约 $0.14/百万 token（cache miss）
# - DeepSeek 输出价格：约 $0.28/百万 token
# - 注意观察 system prompt 对 token 消耗的影响
```

---

## 下一步

🎉 **恭喜！阶段 0 完成！** 你已经有了 Java + Spring Boot + LLM 的基础。

→ 进入 [阶段 1 入门](../阶段1-入门/01-第一次调用LLM.md) —— 用 Java 代码调通 LLM，开始做第一个 AI 应用
→ 概念卡壳？查 `理论字典/LLM基础.md`
