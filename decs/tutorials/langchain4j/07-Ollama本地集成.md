# LangChain4j 07 - Ollama 本地集成（深度使用）

> 目标：把 Ollama 当作开发主力模型，掌握模型管理、自定义 Modelfile、性能调优。
> 前置：已完成 01-06。

---

## 1. Ollama 在你学习路径中的角色

### 1.1 为什么用 Ollama

| 优势 | 说明 |
|------|------|
| **零成本** | 不消耗 API token，随便测 |
| **离线可用** | 不依赖网络 |
| **数据隐私** | 数据不出本地 |
| **多模型切换** | 一个命令换模型 |
| **API 兼容** | 提供 OpenAI 兼容接口 |

### 1.2 适合的场景

- 学习开发期（你目前阶段）
- 本地测试、调试
- 隐私敏感数据
- 网络不稳定环境

### 1.3 不适合的场景

- 生产部署高并发（用 vLLM）
- 极致延迟（用 TensorRT-LLM）
- 模型不在 Ollama 库（自己打包）

---

## 2. 安装与基本命令

### 2.1 安装

```bash
# Mac
brew install ollama

# Linux
curl -fsSL https://ollama.com/install.sh | sh

# Windows
# 下载 https://ollama.com/download/windows
```

### 2.2 服务管理

```bash
# 启动后台服务
ollama serve

# 查看运行状态
ollama ps

# 查看已下载的模型
ollama list

# 删除模型（释放磁盘）
ollama rm qwen2.5:7b
```

### 2.3 模型管理

```bash
# 拉模型
ollama pull qwen2.5:7b

# 拉指定 tag（如 4-bit 量化版）
ollama pull qwen2.5:7b-instruct-q4_K_M

# 跑一次性命令（不进入交互）
ollama run qwen2.5:7b "你好"

# 进入交互式对话
ollama run qwen2.5:7b
>>> /?
>>> /show info      # 看模型信息
>>> /set system ... # 改 system prompt
>>> /bye
```

---

## 3. 模型选型（必读）

### 3.1 入门三件套（按顺序试）

| 模型 | 参数量 | 显存（INT4） | 内存（纯CPU） | 适合 |
|------|--------|------------|-------------|-----|
| **qwen2.5:0.5b** | 0.5B | 1GB | 2GB | 极速测试 |
| **qwen2.5:7b** | 7B | 5GB | 8GB | **开发主力（推荐）** |
| **qwen2.5:14b** | 14B | 9GB | 16GB | 质量更高 |

### 3.2 按场景选

| 场景 | 推荐 |
|------|------|
| 通用对话 | `qwen2.5:7b` / `glm4:9b` |
| 代码生成 | `qwen2.5-coder:7b` / `deepseek-coder-v2` |
| 长文档 | `qwen2.5:14b`（32K 上下文） |
| Embedding | `bge-m3` / `nomic-embed-text` |
| 重排 | `bge-reranker-v2-m3` |

### 3.3 命令速查

```bash
# 看官方推荐
ollama show qwen2.5:7b --info

# 看所有可用 tag
ollama show qwen2.5 --list-tags
```

---

## 4. Modelfile：自定义模型

### 4.1 什么是 Modelfile

类比 Dockerfile：用文本定义一个"定制模型"，基于基础模型加自己的配置。

### 4.2 创建自定义模型

`Modelfile`：
```
FROM qwen2.5:7b

# 永久 system message
SYSTEM """
你是 Acme 公司的客服助手，遵循规则：
1. 礼貌专业
2. 涉及订单用工具
3. 不确定时转人工
"""

# 调整生成参数
PARAMETER temperature 0.3
PARAMETER top_p 0.9
PARAMETER num_ctx 8192

# 关闭随机性
PARAMETER seed 42
```

### 4.3 构建与使用

```bash
# 构建（命名为 my-cs）
ollama create my-cs -f Modelfile

# 使用
ollama run my-cs
```

### 4.4 在 LangChain4j 里用

```java
var model = OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("my-cs")   // 用自定义模型名
        .build();
```

**价值**：把 system prompt 固化在模型里，业务代码不用关心。

---

## 5. 性能调优

### 5.1 上下文长度（num_ctx）

```
PARAMETER num_ctx 4096    # 默认 2048
```

**坑**：上下文越长，显存/内存占用越大，速度越慢。

| num_ctx | 显存增量（7B 模型） |
|---------|------------------|
| 2048 | 基准 |
| 4096 | +2GB |
| 8192 | +4GB |
| 32768 | +16GB |

### 5.2 并发数

```bash
# 启动时设置
OLLAMA_NUM_PARALLEL=4 ollama serve
```

默认 1（一次只处理一个请求）。生产调到 4-8。

### 5.3 保持模型常驻显存

```bash
OLLAMA_KEEP_ALIVE=24h ollama serve
```

默认模型空闲 5 分钟后从显存卸载，下次调用要重新加载（慢）。开发时设长一点。

### 5.4 GPU 选择

```bash
OLLAMA_GPU_LAYERS=35 ollama serve   # 把 35 层放 GPU
# 或在 Modelfile 里
PARAMETER num_gpu 35
```

显存不够时部分层放 CPU 跑（混合推理）。

---

## 6. 硬件配置指南

### 6.1 最低配置（纯 CPU）

- 内存 16GB（7B 模型）
- M1/M2/M3 Mac（统一内存很香）
- 速度：5-10 token/s（7B 模型）

### 6.2 推荐配置（GPU）

| GPU | 模型 | 速度 |
|-----|------|------|
| RTX 3060 12GB | 7B | 30-50 t/s |
| RTX 4090 24GB | 14B | 50-80 t/s |
| M2 Max 64GB | 30B+ | 20-30 t/s |

### 6.3 Mac 的优势

苹果芯片的**统一内存**对 LLM 极其友好：
- 16GB Mac ≈ 12GB 显存
- 32GB Mac 能跑 14B
- 64GB Mac 能跑 30B+
- Metal 加速原生支持

---

## 7. LangChain4j 集成进阶

### 7.1 StreamingChatModel

```java
var model = OllamaStreamingChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("qwen2.5:7b")
        .temperature(0.7)
        .numPredict(1024)    // 最大输出 token
        .topK(40)
        .topP(0.9)
        .numCtx(8192)        // 上下文长度
        .timeout(Duration.ofMinutes(5))
        .format("json")      // 强制 JSON 输出
        .build();
```

### 7.2 Embedding 模型

```java
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

var embedModel = OllamaEmbeddingModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("bge-m3")   // 需要先 ollama pull bge-m3
        .build();
```

> 注：embedding 模型不要用对话模型当替代，效果差很多。

### 7.3 多模型共存

```java
// 在一个项目里用不同模型干不同事
ChatLanguageModel chatForDialog = OllamaChatModel.builder()
        .modelName("qwen2.5:7b")     // 对话用
        .build();

ChatLanguageModel chatForRerank = OllamaChatModel.builder()
        .modelName("qwen2.5:14b")    // 复杂任务用
        .build();

EmbeddingModel embed = OllamaEmbeddingModel.builder()
        .modelName("bge-m3")         // 向量化用
        .build();
```

---

## 8. OpenAI 兼容模式

Ollama 默认提供 `/v1/chat/completions` 接口，与 OpenAI 协议完全兼容。

```bash
curl http://localhost:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5:7b",
    "messages": [{"role":"user","content":"hi"}]
  }'
```

**用途**：用 OpenAI SDK 的老项目无缝切换到本地 Ollama。

---

## 9. 常见问题

### 9.1 下载模型卡住

**原因**：网络问题。
**解决**：
- 重试 `ollama pull`
- 用代理：`HTTPS_PROXY=... ollama pull`

### 9.2 GPU 没用上

**诊断**：`ollama ps` 看 Processor 列。
- 显示 `100% GPU` = 全 GPU
- 显示 `100% CPU` = 全 CPU
- 显示 `48/52 CPU/GPU` = 混合

**解决**：
- 确认 CUDA / Metal 驱动正常
- 调 `num_gpu` 参数

### 9.3 速度突然变慢

**原因**：模型从显存被卸载。
**解决**：调 `OLLAMA_KEEP_ALIVE`。

### 9.4 多个并发请求排队

**原因**：`OLLAMA_NUM_PARALLEL` 默认 1。
**解决**：
```bash
OLLAMA_NUM_PARALLEL=4 ollama serve
```

### 9.5 内存占用持续上涨

**原因**：上下文没控制好，每次请求都带超长历史。
**解决**：
- 限制 `ChatMemory` 大小
- 在 LangChain4j 端做 prompt 截断

---

## 10. 学习路径建议

### 阶段 A：跑通（你已经在这里）
- [x] 安装 Ollama
- [x] 跑通 qwen2.5:7b
- [x] 用 LangChain4j 调用

### 阶段 B：定制
- [ ] 写一个 Modelfile，固化 system prompt
- [ ] 调 num_ctx 测上下文长度对速度的影响
- [ ] 试 3 个不同尺寸的模型，对比质量

### 阶段 C：性能
- [ ] 调 `OLLAMA_KEEP_ALIVE`
- [ ] 调 `OLLAMA_NUM_PARALLEL`
- [ ] 测 GPU 利用率

---

## 11. 理解检查

1. Ollama 和 vLLM 的核心区别？为什么 Ollama 不适合生产？
2. Modelfile 解决了什么问题？什么场景下用？
3. `num_ctx` 调到 32768 会有什么副作用？
4. Mac M2 跑 LLM 为什么比同显存的 N 卡更香？
5. 如何让 Ollama 模型常驻显存不卸载？

---

## 12. 练习任务

1. 拉一个 `qwen2.5-coder:7b` 模型，测试代码生成
2. 写一个 Modelfile，定制"翻译助手"模型
3. 用 OpenAI 兼容模式 curl 调用 Ollama
4. 测 `num_ctx` 从 2048 到 8192，速度变化多少
5. 跑 `ollama ps` 观察 Processor 列，确认是否用上 GPU

完成后进入 [08-DeepSeek 集成](./08-DeepSeek集成.md)。
