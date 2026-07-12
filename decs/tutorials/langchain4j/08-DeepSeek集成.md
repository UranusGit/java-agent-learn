# LangChain4j 08 - DeepSeek API 集成

> 目标：把 DeepSeek 作为云端主力模型，掌握成本控制、JSON Mode、缓存等生产技巧。
> 前置：已完成 01-07。

---

## 1. 为什么选 DeepSeek

### 1.1 与其他云端 API 对比

| 维度 | DeepSeek | OpenAI | 通义千问 | Claude |
|------|---------|--------|---------|--------|
| 价格 | 💰 极低 | 💰💰💰 高 | 💰💰 中 | 💰💰 高 |
| 中文能力 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Function Calling | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 国内访问 | ✅ | ❌ 需代理 | ✅ | ⚠️ 不稳定 |
| 代码能力 | ⭐⭐⭐⭐⭐ (coder) | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| OpenAI 协议 | ✅ 兼容 | - | ✅ | ❌ |

### 1.2 价格（2026）

| 模型 | 输入 | 输出 | 备注 |
|------|------|------|------|
| `deepseek-chat` | ¥1/M token | ¥2/M token | 主力对话 |
| `deepseek-reasoner` | ¥4/M token | ¥16/M token | 推理模型 |
| `deepseek-coder` | 已并入 deepseek-chat | - | 旧版 |

**对比**：OpenAI gpt-4o 输入 ¥18/M，输出 ¥72/M。**DeepSeek 便宜 30 倍以上**。

### 1.3 学习期成本预估

- 每天 100 次对话，每次 1000 token
- 月成本：约 ¥10
- **充值 20 元够你学 2 个月**

---

## 2. 接入准备

### 2.1 注册与申请

1. 访问 `platform.deepseek.com`
2. 注册账号（需手机号）
3. 充值（最少 1 元起）
4. 创建 API Key（`sk-...` 格式）
5. **保存好 Key，只显示一次**

### 2.2 环境变量

```bash
# Mac/Linux
export DEEPSEEK_API_KEY=sk-xxxxxxxx

# 写到 ~/.zshrc 持久化
echo 'export DEEPSEEK_API_KEY=sk-xxx' >> ~/.zshrc
source ~/.zshrc

# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-xxx"

# IDEA：在 Run Configuration 里设置 Environment variables
```

### 2.3 验证 Key

```bash
curl https://api.deepseek.com/v1/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role":"user","content":"ping"}]
  }'
```

返回 200 + 正常 JSON = OK。

---

## 3. LangChain4j 集成

### 3.1 pom.xml

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

**注意**：DeepSeek 是 OpenAI 兼容协议，用 `langchain4j-open-ai` 模块。

### 3.2 基础调用

```java
import dev.langchain4j.model.openai.OpenAiChatModel;

ChatLanguageModel model = OpenAiChatModel.builder()
        .baseUrl("https://api.deepseek.com")    // 注意：不带末尾斜杠
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .modelName("deepseek-chat")
        .temperature(0.7)
        .maxTokens(1024)
        .build();

String answer = model.chat("你好");
```

### 3.3 关键坑：baseUrl 不要带斜杠

```java
// ✅ 正确
.baseUrl("https://api.deepseek.com")

// ❌ 错误（部分版本会 404）
.baseUrl("https://api.deepseek.com/")
```

LangChain4j 会自动拼 `/v1/chat/completions`。如果你写了 `/`，可能拼成 `//v1/...`。

---

## 4. 成本控制（生产必读）

### 4.1 监控每次调用的 token 用量

```java
import dev.langchain4j.model.chat.response.ChatResponse;

ChatResponse response = model.chat(ChatRequest.builder()
        .messages(UserMessage.from("你好"))
        .build());

TokenUsage usage = response.tokenUsage();
System.out.println("输入 token: " + usage.inputTokenCount());
System.out.println("输出 token: " + usage.outputTokenCount());
System.out.println("总 token: " + usage.totalTokenCount());
```

### 4.2 三条降低成本的铁律

#### 铁律 1：控制 prompt 长度

```java
// ❌ 重复发送完整对话历史
chatMemory = MessageWindowChatMemory.withMaxMessages(100);  // 上下文会爆炸

// ✅ 合理窗口
chatMemory = MessageWindowChatMemory.withMaxMessages(10);   // 通常足够
```

#### 铁律 2：用便宜模型做简单事

```java
// 简单分类、判断 → 用最便宜模型
ChatLanguageModel cheapModel = OpenAiChatModel.builder()
        .modelName("deepseek-chat")
        .maxTokens(50)            // 短输出
        .build();

// 复杂生成 → 同模型但调参
ChatLanguageModel bigModel = OpenAiChatModel.builder()
        .modelName("deepseek-chat")
        .maxTokens(2048)
        .build();
```

#### 铁律 3：实现缓存

```java
// 用相同 prompt 直接返回缓存结果
// 详见第 6 节
```

### 4.3 预算告警

DeepSeek 后台可设置余额告警。**学习期建议**：
- 设 ¥10 告警线
- 每天看一眼用量

---

## 5. JSON Mode（结构化输出神器）

### 5.1 痛点

让 LLM 返回 JSON，经常遇到：
- 输出带 markdown 包裹（```json ... ```）
- 字段名乱写
- 数组里多一个 null

### 5.2 DeepSeek 的 JSON Mode

```java
ChatLanguageModel model = OpenAiChatModel.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey(apiKey)
        .modelName("deepseek-chat")
        .responseFormat("json_object")   // 关键：强制 JSON 输出
        .build();

String json = model.chat("""
    提取用户信息：
    姓名：张三，年龄：25，城市：北京
    返回格式：{"name":"...","age":...,"city":"..."}
    """);
// 输出永远是合法 JSON
```

### 5.3 配合 AiServices

```java
public record UserInfo(String name, int age, String city) {}

public interface Extractor {
    @SystemMessage("从文本中提取用户信息，严格返回 JSON")
    UserInfo extract(String text);
}

ChatLanguageModel model = OpenAiChatModel.builder()
        // ...
        .responseFormat("json_object")
        .build();

Extractor extractor = AiServices.builder(Extractor.class)
        .chatLanguageModel(model)
        .build();

UserInfo u = extractor.extract("张三 25 北京");
// u.name() = "张三"
```

---

## 6. 缓存策略

### 6.1 为什么需要缓存

- 用户重复问同样的问题（如 FAQ）
- 测试时反复跑相同 prompt
- **省 token = 省钱**

### 6.2 简单实现

```java
public class CachedModel implements ChatLanguageModel {
    private final ChatLanguageModel delegate;
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Override
    public String chat(String userMessage) {
        return cache.get(userMessage, delegate::chat);
    }
    // ... 其他方法 delegate
}
```

### 6.3 注意事项

- **生产慎用全局缓存**：用户 A 的隐私数据可能被用户 B 拿到
- 缓存 key 要包含**用户 ID + prompt**
- Tool 调用的中间结果不要缓存（依赖外部状态）

---

## 7. 错误处理

### 7.1 限流（429）

```
Rate limit exceeded
```

**原因**：请求太快或额度用尽。
**解决**：
```java
OpenAiChatModel.builder()
        // ...
        .maxRetries(3)         // 自动重试
        .build();
```

### 7.2 余额不足（402）

```
Insufficient balance
```

**解决**：充值。

### 7.3 超时

```java
OpenAiChatModel.builder()
        .timeout(Duration.ofSeconds(60))   // 默认 60s
        .build();
```

DeepSeek 推理任务可能耗时 30 秒+，超时设长一点。

### 7.4 网络抖动

```java
// 用 Resilience4j 实现重试
RetryConfig config = RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofSeconds(2))
        .retryOnException(e -> e instanceof IOException)
        .build();
```

---

## 8. DeepSeek vs Ollama：什么时候用哪个

| 场景 | 推荐 |
|------|------|
| 本地开发调试 | Ollama（免费） |
| 学习新概念、跑 Demo | Ollama |
| 评估生产效果 | DeepSeek（质量高） |
| Function Calling 测试 | DeepSeek（小模型 FC 准确率低） |
| 处理隐私数据 | Ollama（数据不出本地） |
| 写测试用例 | DeepSeek（输出稳定） |
| 长上下文任务 | DeepSeek（质量更高） |

### 8.1 双模型切换架构（推荐）

```java
// 开发环境用 Ollama，生产用 DeepSeek，配置切换
@Configuration
class AiConfig {

    @Bean
    @Profile("dev")
    ChatLanguageModel devModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen2.5:7b")
                .build();
    }

    @Bean
    @Profile("prod")
    ChatLanguageModel prodModel() {
        return OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .modelName("deepseek-chat")
                .build();
    }
}
```

---

## 9. Reasoner 模型（推理增强）

DeepSeek 提供 `deepseek-reasoner`（R1），会在输出前**显式思考**。

```java
ChatLanguageModel reasoner = OpenAiChatModel.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey(apiKey)
        .modelName("deepseek-reasoner")
        .build();
```

### 9.1 特点

| 维度 | deepseek-chat | deepseek-reasoner |
|------|--------------|------------------|
| 速度 | 快 | 慢 5-10 倍 |
| 成本 | 低 | 高 4-8 倍 |
| 推理质量 | 一般 | 强 |
| 适合 | 日常对话 | 数学、逻辑、代码 |

### 9.2 使用建议

- 简单对话不要用 reasoner
- Tool 调用不要用 reasoner（输出有 reasoning_content 字段，部分 SDK 不兼容）
- **适合**：复杂业务决策、数学计算、深度代码评审

---

## 10. 常见错误

### 10.1 404 Not Found

```
{"error":{"message":"Not Found"}}
```

**原因**：`baseUrl` 末尾带 `/`，或拼错了路径。
**解决**：去掉末尾斜杠，只写 `https://api.deepseek.com`。

### 10.2 字段名不一致

DeepSeek 返回结构与 OpenAI 略有差异（如 `reasoning_content`）。
**解决**：用 LangChain4j 抽象层，别直接调原始 HTTP。

### 10.3 SSL 握手失败

**原因**：JDK 版本太旧。
**解决**：升级 JDK 21+，或加信任所有证书（仅调试用）。

---

## 11. 理解检查

1. DeepSeek 为什么便宜？质量会比 OpenAI 差吗？
2. `baseUrl` 为什么不能带末尾斜杠？
3. JSON Mode 解决了什么问题？怎么开启？
4. 生产场景下，如何实现"开发用 Ollama、生产用 DeepSeek"切换？
5. `deepseek-reasoner` 什么时候用，什么时候不用？

---

## 12. 练习任务

1. 注册 DeepSeek，充值 10 元，跑通第一次调用
2. 用 `TokenUsage` 打印每次调用的 token 数和成本估算
3. 测试 JSON Mode：写一个 `UserInfoExtractor` 接口
4. 实现 `CachedModel` 装饰器，重复调用同一个 prompt 看是否走缓存
5. 写一个 Spring `@Profile` 切换配置，dev 用 Ollama、prod 用 DeepSeek
6. 用 `deepseek-reasoner` 解一道数学题，对比与 `deepseek-chat` 的速度差异

完成后进入 [09-常见错误与排查手册](./09-常见错误与排查手册.md)。
