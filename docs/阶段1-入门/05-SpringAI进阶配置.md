# 05 · Spring AI 进阶配置（补充篇）

> 阶段：1 入门 · 难度：⭐⭐ · 预计：1 天
> 前置：[04 项目 P1 命令行助手](04-项目P1-命令行助手.md)
> 产出：掌握 Spring AI 的高级配置——多模型切换、超时控制、重试策略、Builder 复用

---

## 为什么需要这个

P1 项目用到了最基础的 ChatClient 配置。在实际企业项目中，你需要：

- 根据场景切换不同模型（通用对话 vs 代码生成）
- 设置超时和重试（LLM API 不稳定）
- 复用 ChatClient 配置（不同 Controller 用不同配置）
- 管理多个 ChatClient（不同租户用不同模型）

---

## 多模型配置

### 方案一：YAML 配置多模型

```yaml
spring:
  ai:
    openai:
      # 默认模型
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
          max-tokens: 2000
```

### 方案二：Java 配置多 ChatClient

```java
@Configuration
public class MultiModelConfig {

    /**
     * 通用对话 Client
     * - 高温度，有创造性
     * - 用于客服聊天
     */
    @Bean("chatClient")
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("你是一个友好的助手。")
            .defaultOptions(OpenAiChatOptions.builder()
                .withModel("deepseek-chat")
                .withTemperature(0.7f)
                .withMaxTokens(2000)
                .build())
            .build();
    }

    /**
     * 代码生成 Client
     * - 低温度，确定性
     * - 用于代码生成和评审
     */
    @Bean("codeClient")
    public ChatClient codeClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("你是一个代码专家。只输出代码，不解释。")
            .defaultOptions(OpenAiChatOptions.builder()
                .withModel("deepseek-coder")
                .withTemperature(0.1f)
                .withMaxTokens(4000)
                .build())
            .build();
    }

    /**
     * 摘要 Client
     * - 极低温度
     * - 用于确定性输出
     */
    @Bean("summaryClient")
    public ChatClient summaryClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("你是摘要生成器。用 3 句话概括。")
            .defaultOptions(OpenAiChatOptions.builder()
                .withModel("deepseek-chat")
                .withTemperature(0.0f)
                .withMaxTokens(500)
                .build())
            .build();
    }
}
```

### 按场景注入

```java
@RestController
public class SmartController {

    @Qualifier("chatClient")
    @Autowired
    private ChatClient chatClient;      // 通用对话

    @Qualifier("codeClient")
    @Autowired
    private ChatClient codeClient;      // 代码生成

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        return chatClient.prompt().user(message).call().content();
    }

    @PostMapping("/code")
    public String generateCode(@RequestBody String description) {
        return codeClient.prompt().user(description).call().content();
    }
}
```

---

## 超时与重试

### HTTP 客户端超时配置

```java
@Configuration
public class HttpClientConfig {

    /**
     * LLM 调用的 HTTP 客户端配置
     * - 连接超时：5 秒
     * - 读取超时：60 秒（LLM 生成可能很慢）
     * - 重试：3 次，指数退避
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout(5000);   // 5 秒连接
                setReadTimeout(60000);     // 60 秒读取
            }});
    }
}
```

### 重试 Advisor

```java
@Component
public class RetryAdvisor implements CallAdvisor {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000}; // 指数退避

    @Override
    public int getOrder() { return -150; }

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        Exception lastError = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return chain.nextCall(request);
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("LLM 调用失败（重试 " + MAX_RETRIES + " 次）", lastError);
    }
}
```

---

## ChatClient Builder 复用

```java
/**
 * ChatClient 工厂——根据租户配置创建定制的 ChatClient
 */
@Component
public class ChatClientFactory {

    private final ChatClient.Builder baseBuilder;
    private final AgentConfigCenter configCenter;

    /**
     * 为特定租户创建定制 ChatClient
     */
    public ChatClient forTenant(String tenantId) {
        var config = configCenter.getConfig(tenantId);

        return baseBuilder
            .defaultSystem(config.getSystemPrompt())
            .defaultOptions(OpenAiChatOptions.builder()
                .withModel(config.getModelName())
                .withTemperature(config.getTemperature())
                .withMaxTokens(config.getMaxTokens())
                .build())
            .build();
    }
}
```

---

## 常见配置问题速查

| 问题 | 原因 | 解决 |
|------|------|------|
| `Connection refused` | API 地址错误或网络不通 | 检查 base-url，用 curl 测试 |
| `401 Unauthorized` | API Key 错误 | 检查环境变量是否正确加载 |
| `429 Too Many Requests` | 调用频率超限 | 加重试 + 限流 |
| 超时 60s+ | max_tokens 太大或模型慢 | 降低 max_tokens 或增大超时 |
| 中文输出乱码 | HTTP 编码问题 | 确保 RestClient 使用 UTF-8 |
| 输出被截断 | max_tokens 不够 | 增大 max_tokens 或检查 finish_reason |

---

## 随堂练习：多模型路由器

```java
// 任务：实现一个简单的模型路由器
// 规则：
// - 包含 "代码" 或 "code" → 用 codeClient（低温度）
// - 包含 "总结" 或 "摘要" → 用 summaryClient（极低温度）
// - 其他 → 用 chatClient（默认温度）

// 提示：
// 1. 注入三个 ChatClient
// 2. 根据用户消息关键词选择
// 3. 记录每次使用了哪个模型
// 4. 输出模型名称 + 响应
```

---

## 验收检查

- [ ] 能配置多个 ChatClient（不同模型/参数）
- [ ] 理解 @Qualifier 注入特定 ChatClient
- [ ] 能配置 HTTP 超时
- [ ] 能实现简单重试
- [ ] 能根据租户配置动态创建 ChatClient
- [ ] 能排查常见的配置问题

---

## 下一步

→ 进入 [阶段 2 核心能力](../阶段2-核心能力/01-工具体系设计.md) —— 深入工具体系设计
