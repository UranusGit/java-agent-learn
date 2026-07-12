# LangChain4j 07 - LM Studio 本地集成（深度使用）

> 目标：把 LM Studio 当作本地推理服务，掌握模型加载、并发配置、Chat + Embedding 双角色。
> 前置：已完成 01-06。
>
> 注：本教程系列不依赖 Ollama，所有"本地模型"需求由 LM Studio 满足。本文件保留旧名 `07-Ollama本地集成.md` 是为了避免破坏其他章节的相对引用。

---

## 1. LM Studio 在你学习路径中的角色

### 1.1 为什么用 LM Studio

| 优势 | 说明 |
|------|------|
| **零成本** | 不消耗 API token，随便测 |
| **离线可用** | 不依赖网络 |
| **数据隐私** | 数据不出本地 |
| **GUI 友好** | 图形界面管模型，比命令行直观 |
| **OpenAI 兼容** | 暴露 `/v1/chat/completions` 和 `/v1/embeddings` |

### 1.2 适合的场景

- 本地跑 Embedding 模型（本教程默认用法，`text-embedding-bge-large-zh-v1.5`）
- 学习开发期跑本地 Chat 模型（不想用 DeepSeek 时）
- 隐私敏感数据
- 网络不稳定环境

### 1.3 不适合的场景

- 生产部署高并发（用 vLLM / TGI / TensorRT-LLM）
- 极致延迟（专用推理引擎）
- 需要自定义 Modelfile 之类的"固化 system prompt"（LM Studio 没有这个抽象，用 LangChain4j 端的 `@SystemMessage` 替代）

### 1.4 LM Studio vs Ollama

| 维度 | LM Studio | Ollama |
|------|-----------|--------|
| 界面 | GUI + CLI | 纯 CLI |
| 模型源 | HuggingFace 任意 GGUF | 官方库精选 |
| 自定义模型 | 直接加载本地文件 | 需要 Modelfile 打包 |
| OpenAI 兼容 | 有 | 有 |
| 配置灵活性 | 中（GUI 引导） | 高（Modelfile） |
| 本教程选择 | ✅ | ❌ |

---

## 2. 安装与服务启动

### 2.1 安装

从官网 `lmstudio.ai` 下载 Mac / Windows / Linux 安装包，正常安装即可。

### 2.2 加载模型

1. 打开 LM Studio → 左侧 **Search** 标签
2. 搜索需要的模型，点 **Download**：
   - 对话：`qwen2.5-7b-instruct`（GGUF，Q4_K_M 量化版）
   - Embedding：`bge-large-zh-v1.5`（GGUF 或 ONNX 都可）
3. 下载完进入左侧 **Developer** 标签
4. 顶部下拉框**加载模型**（可以同时加载多个）
5. 点 **Start Server** 启动 OpenAI 兼容服务（默认端口 `1234`）

### 2.3 验证服务

```bash
curl http://127.0.0.1:1234/v1/models
```

返回 JSON 里 `data[].id` 就是你后面 `modelName(...)` 该填的值（区分大小写、版本号、后缀）。

### 2.4 测试调用

```bash
# Chat
curl http://127.0.0.1:1234/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5-7b-instruct",
    "messages": [{"role":"user","content":"你好"}]
  }'

# Embedding
curl http://127.0.0.1:1234/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "text-embedding-bge-large-zh-v1.5",
    "input": "你好"
  }'
```

---

## 3. 模型选型

### 3.1 对话模型（按内存选）

| 模型 | 参数量 | 内存需求 | 适合 |
|------|--------|---------|------|
| `qwen2.5-0.5b-instruct` | 0.5B | 1GB | 极速测试 |
| `qwen2.5-7b-instruct` | 7B | 5GB（Q4） | **本地开发主力（推荐）** |
| `qwen2.5-14b-instruct` | 14B | 9GB（Q4） | 质量更高 |
| `deepseek-r1-distill-qwen-7b` | 7B | 5GB | 推理类任务 |

### 3.2 Embedding 模型

| 模型 | 维度 | 适合 |
|------|------|------|
| **`text-embedding-bge-large-zh-v1.5`（本教程默认）** | 1024 | 中文，效果好 |
| `text-embedding-bge-small-zh-v1.5` | 512 | 中文，轻量 |
| `text-embedding-nomic-embed-text-v1.5` | 768 | 英文/通用 |
| `bge-m3` | 1024 | 多语言 |

### 3.3 重排模型（RAG 进阶）

| 模型 | 说明 |
|------|------|
| `bge-reranker-v2-m3` | 中文友好，常用 |

> 本教程系列中 **Chat 走 DeepSeek 远端**，**Embedding 走 LM Studio 本地**，这是性价比最高的组合。如果完全离线，把 Chat 也改成 LM Studio 即可（下一节）。

---

## 4. LangChain4j 调用 LM Studio（Chat）

### 4.1 普通对话

```java
import dev.langchain4j.model.openai.OpenAiChatModel;

var model = OpenAiChatModel.builder()
        .baseUrl("http://127.0.0.1:1234/v1")
        .apiKey("lm-studio")                   // LM Studio 不校验，随便填
        .modelName("qwen2.5-7b-instruct")      // LM Studio 里加载的模型 id
        .temperature(0.7)
        .timeout(Duration.ofMinutes(2))
        .build();

System.out.println(model.chat("用一句话解释什么是 RAG"));
```

### 4.2 流式对话

```java
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

var model = OpenAiStreamingChatModel.builder()
        .baseUrl("http://127.0.0.1:1234/v1")
        .apiKey("lm-studio")
        .modelName("qwen2.5-7b-instruct")
        .temperature(0.7)
        .build();

model.chat("讲个笑话", new StreamingResponseHandler<>() {
    @Override public void onPartialResponse(String t) { System.out.print(t); }
    @Override public void onCompleteResponse(Object r) { System.out.println("\n[done]"); }
    @Override public void onError(Throwable e) { e.printStackTrace(); }
});
```

---

## 5. LangChain4j 调用 LM Studio（Embedding）

### 5.1 基本调用

```java
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.net.http.HttpClient;
import java.time.Duration;

var embedModel = OpenAiEmbeddingModel.builder()
        .baseUrl("http://127.0.0.1:1234/v1")
        .apiKey("lm-studio")
        .modelName("text-embedding-bge-large-zh-v1.5")
        // 强制 HTTP/1.1：JDK 21 的 HttpClient 默认 HTTP/2，与 LM Studio 长响应偶发不兼容
        .httpClientBuilder(new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1))
                .readTimeout(Duration.ofSeconds(60)))
        .build();

var embedding = embedModel.embed("你好").content();
System.out.println("维度：" + embedding.vector().length);  // 1024
```

> ⚠️ **HTTP/1.1 配置必须加**：不加会偶发 `HttpTimeoutException`，详见 09-常见错误。

### 5.2 批量向量化

```java
List<TextSegment> segments = ...;
List<Embedding> embeddings = embedModel.embedAll(segments).content();
```

`embedAll` 比 `embed` 循环快 10 倍以上（LM Studio 一次请求处理多个输入）。

---

## 6. 多模型共存

LM Studio 可以同时加载多个模型（受内存限制）。LangChain4j 端通过不同 `modelName` 区分：

```java
// 对话走 DeepSeek 远端
ChatModel chat = OpenAiChatModel.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .modelName("deepseek-chat")
        .build();

// Embedding 走本地 LM Studio
EmbeddingModel embed = OpenAiEmbeddingModel.builder()
        .baseUrl("http://127.0.0.1:1234/v1")
        .apiKey("lm-studio")
        .modelName("text-embedding-bge-large-zh-v1.5")
        .httpClientBuilder(new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1))
                .readTimeout(Duration.ofSeconds(60)))
        .build();
```

---

## 7. LM Studio 配置进阶

### 7.1 Server 设置

Developer 靺板里的 **Settings**：

| 选项 | 推荐值 | 说明 |
|------|--------|------|
| Port | 1234 | 默认 |
| CORS | 开启 | 跨域调用需要 |
| GPU Offload | 最大 | 把模型层放到 GPU/Metal |
| Context Size | 4096 | 越长越占内存 |
| Parallel Calls | 1-4 | 看内存决定 |

### 7.2 上下文长度（Context Size）

| Context | 内存增量（7B 模型） |
|---------|------------------|
| 2048 | 基准 |
| 4096 | +2GB |
| 8192 | +4GB |
| 32768 | +16GB |

**坑**：Context Size 调大后内存占用线性增长，速度变慢。RAG 场景建议 4096-8192。

### 7.3 模型常驻内存

LM Studio 加载的模型默认常驻，直到显式卸载或关闭 Server。**不像 Ollama 有 `KEEP_ALIVE`**——LM Studio 更直接，加载就常驻。

---

## 8. 硬件配置指南

### 8.1 最低配置（纯 CPU）

- 内存 16GB（7B 模型 Q4）
- 速度：5-10 token/s

### 8.2 推荐配置

| 硬件 | 模型 | 速度 |
|-----|------|------|
| RTX 3060 12GB | 7B | 30-50 t/s |
| RTX 4090 24GB | 14B | 50-80 t/s |
| M2 Pro 16GB | 7B | 15-25 t/s |
| M2 Max 32GB | 14B | 20-30 t/s |
| M3 Max 64GB | 30B+ | 20-30 t/s |

### 8.3 Mac 的优势

苹果芯片的**统一内存**对 LLM 极其友好：
- 16GB Mac ≈ 12GB 显存可用
- 32GB Mac 能跑 14B
- 64GB Mac 能跑 30B+
- Metal 加速原生支持

---

## 9. 常见问题

### 9.1 启动 Server 后调不通

**诊断**：
```bash
curl http://127.0.0.1:1234/v1/models
```

**可能原因**：
- 防火墙拦截 1234 端口
- LM Studio Server 没点 Start
- 模型没加载（Developer 面板顶部下拉框选模型）

### 9.2 `model 'xxx' not found`

**原因**：`modelName` 与 LM Studio 实际加载的 id 不一致。
**解决**：`curl http://127.0.0.1:1234/v1/models`，看返回的 `data[].id`，严格按那个值填。

### 9.3 `HttpTimeoutException`

**原因**：JDK 21 的 HttpClient 默认 HTTP/2，与 LM Studio 长响应偶发不兼容。
**解决**：强制 HTTP/1.1（见 5.1 节代码）。

### 9.4 速度突然变慢

**原因**：可能操作系统把内存换页了，或同时加载了多个大模型。
**解决**：
- LM Studio 卸载不用的模型
- 关掉其他占内存的程序
- Mac 用户检查 Activity Monitor → Memory Pressure

### 9.5 GPU 没用上

**诊断**：LM Studio 推理时看 **GPU Utilization** 指标。
**解决**：
- 确认 GPU Offload 设置为最大
- Mac 确认是 Apple Silicon（M1/M2/M3+）
- Windows/Linux 确认 CUDA 驱动正常

### 9.6 并发请求排队

**原因**：LM Studio Server 默认单线程处理。
**解决**：
- 在 Settings 里调高 `Parallel Calls`
- 但不要超过 CPU/GPU 能力，否则延迟反而升高
- 真正高并发场景考虑 vLLM / TGI

---

## 10. 学习路径建议

### 阶段 A：跑通（你已经在这里）
- [x] 安装 LM Studio
- [x] 加载 `qwen2.5-7b-instruct`
- [x] 用 LangChain4j 调用本地 Chat

### 阶段 B：双角色
- [ ] 同时加载 Chat 和 Embedding 模型
- [ ] 测 Embedding 调用，验证维度
- [ ] 写一个最小 RAG demo（用本地 Chat + 本地 Embedding）

### 阶段 C：性能
- [ ] 测不同 Context Size 对内存和速度的影响
- [ ] 测 GPU Offload 不同层级的速度差异
- [ ] 监控 GPU 利用率

---

## 11. 理解检查

1. LM Studio 和 Ollama 的核心区别？为什么 LM Studio 更适合新手？
2. 为什么本教程选择 "DeepSeek 远端 Chat + LM Studio 本地 Embedding" 的组合？
3. 调用 LM Studio Embedding 时为什么要强制 HTTP/1.1？
4. Mac M2 跑 LLM 为什么比同显存的 N 卡更香？
5. 如何让 LM Studio 同时为 Chat 和 Embedding 服务？

---

## 12. 练习任务

1. 加载 `qwen2.5-7b-instruct`，用 LangChain4j 跑通最小 Chat
2. 加载 `text-embedding-bge-large-zh-v1.5`，跑通 Embedding 调用
3. 测 Context Size 从 2048 到 8192，速度变化多少
4. 同时加载 Chat + Embedding 两个模型，跑一个完整的 RAG demo（替代 DeepSeek Chat）
5. 在 LM Studio GUI 里观察 GPU 利用率，确认 Metal/CUDA 是否生效

完成后进入 [08-DeepSeek 集成](./08-DeepSeek集成.md)。
