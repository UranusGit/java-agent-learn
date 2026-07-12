# LangChain4j 04 - AiServices 声明式接口

> 目标：理解 `AiServices` 如何把"组装零件"变成"声明接口"，掌握 SystemMessage、结构化输出、Tool 整合。
> 前置：已完成 01-03。

---

## 1. AiServices 是什么

### 1.1 痛点回顾

前面三节你已经手动管理了 `ChatLanguageModel`、`ChatMemory`、`Tool`。代码大概长这样：

```java
// 手动装配的"零散"代码
String answer = model.chat(memory.messages() + userMessage);
memory.add(UserMessage.from(userMessage));
memory.add(AiMessage.from(answer));
// ... 还要处理 Tool 调用循环
```

### 1.2 AiServices 的价值

> `AiServices` 是 LangChain4j 的"自动装配"。你声明一个 Java 接口，它帮你实现所有底层细节。

**类比**：
- 像 MyBatis 的 Mapper —— 你写接口，框架生成实现
- 像 Spring Data JPA 的 Repository —— 声明即用
- 像 Feign —— 通过接口调远程服务

---

## 2. 第一个 AiServices

### 2.1 定义接口 + 装配

```java
package org.example;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.UserMessageProvider;
import dev.langchain4j.service.V;

public interface Assistant {

    String chat(String userMessage);
}

// 装配
public class Demo {
    public static void main(String[] args) {
        var model = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen2.5:7b")
                .build();

        Assistant agent = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .build();

        System.out.println(agent.chat("你好"));
    }
}
```

### 2.2 神奇之处

接口没有任何实现类，但 `AiServices.builder(Assistant.class).build()` 返回一个能用的实例。
**底层是 Java 动态代理**（和 Feign、MyBatis Mapper 同一个套路）。

---

## 3. SystemMessage：定义 AI 的角色

### 3.1 三种写法

#### 方式 1：注解（最常用）

```java
public interface Assistant {

    @SystemMessage("你是一个海盗风格的助手，所有回答都要带'啊哈！'")
    String chat(String userMessage);
}
```

#### 方式 2：Builder 配置

```java
Assistant agent = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .systemMessageProvider(chatMemoryId -> "你是海盗助手")
        .build();
```

#### 方式 3：多行模板（推荐复杂场景）

```java
@SystemMessage("""
    你是公司客服助手，遵循以下规则：
    1. 只回答产品相关问题
    2. 不确定时回答'我帮您转人工'
    3. 永远用礼貌用语
    """)
String chat(String userMessage);
```

### 3.2 关键认知

`SystemMessage` 不是直接发给 LLM 的 system 字段，就是 ChatMemory 里第一条 `SystemMessage`。
每次请求时它都会被带上（不会被淘汰），用来稳定约束 AI 行为。

---

## 4. 结构化输出：让 LLM 返回 Java 对象

### 4.1 返回自定义类型

```java
public record Sentiment(String emotion, double score, String reason) {}

public interface SentimentAnalyzer {

    @SystemMessage("分析文本的情感倾向，必须返回 JSON 格式")
    Sentiment analyze(String text);
}

// 使用
Sentiment result = analyzer.analyze("这个产品真烂，没法用");
// result.emotion() = "negative"
// result.score() = 0.95
```

### 4.2 底层原理

1. AiServices 在 prompt 里追加"必须返回符合 schema 的 JSON"
2. LLM 返回 JSON 字符串
3. AiServices 用 Jackson 反序列化成你的 record

### 4.3 注意事项

- 必须**用 record 或简单 POJO**，字段要有清晰的语义命名
- 模型能力决定成功率（GPT-4 / Claude 几乎 100%，小模型可能失败）
- **重要**：在 `@SystemMessage` 里明确"返回 JSON"，否则模型可能返回自然语言

### 4.4 List / Map 类型

```java
List<String> extractKeywords(String text);

Map<String, Object> extractInfo(String text);
```

LangChain4j 自动处理泛型。

---

## 5. UserMessageProvider：动态拼接 Prompt

### 5.1 注解方式（最常用）

```java
public interface Translator {

    @UserMessage("将以下文本翻译成 {{language}}: {{text}}")
    String translate(@V("language") String language, @V("text") String text);
}

// 使用
translator.translate("English", "你好世界");
```

### 5.2 模板语法

- `{{variable}}` —— 变量占位
- `@V("variable")` —— 绑定参数
- LangChain4j 默认使用 Mustache 模板

### 5.3 多行模板

```java
@UserMessage("""
    请按以下格式输出：
    输入：{{text}}
    输出：
    """)
String format(String text);
```

---

## 6. 完整装配：Memory + Tool + SystemMessage

```java
public interface CustomerService {

    @SystemMessage("""
        你是 Acme 公司的客服助手。
        - 礼貌、专业
        - 涉及订单查询请使用工具
        - 不确定时转人工
        """)
    String chat(@MemoryId String userId, @UserMessage String message);
}

// 装配
CustomerService service = AiServices.builder(CustomerService.class)
        .chatLanguageModel(model)
        .chatMemoryProvider(userId ->
            MessageWindowChatMemory.builder()
                .id(userId)
                .maxMessages(20)
                .build())
        .tools(new OrderTools(orderRepo), new ProductTools(productRepo))
        .build();

// 多用户使用，互不干扰
service.chat("user-001", "我的订单到哪了？");
service.chat("user-002", "iPhone 15 多少钱？");
```

### 6.1 关键注解

| 注解 | 作用 |
|------|------|
| `@MemoryId` | 标记哪个参数是用户 ID（多租户隔离） |
| `@UserMessage` | 标记哪个参数是用户输入 |
| `@SystemMessage` | 类级别或方法级别定义 system prompt |
| `@UserMessage`（方法上） | 模板化用户消息 |
| `@V` | 模板变量绑定 |

---

## 7. 流式输出（Streaming）

```java
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.TokenStream;

public interface StreamingAssistant {
    TokenStream chat(String userMessage);
}

var streamingModel = OllamaStreamingChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("qwen2.5:7b")
        .build();

StreamingAssistant agent = AiServices.builder(StreamingAssistant.class)
        .streamingChatLanguageModel(streamingModel)
        .build();

TokenStream stream = agent.chat("讲个笑话");
stream.onPartialResponse(System.out::print)
      .onCompleteResponse(r -> System.out.println("\n[完成]"))
      .onError(Throwable::printStackTrace)
      .start();
```

### 7.1 何时用流式

- 用户面向的场景（聊天 UI）→ **必须**流式
- 内部异步任务（批量处理）→ 可不用
- Tool 调用 → 注意：流式 + Tool 组合在 LangChain4j 里要小心（版本相关）

---

## 8. 常见错误

### 8.1 结构化输出失败

```
com.fasterxml.jackson.databind.exc.MismatchedInputException
```

**原因**：LLM 没返回合法 JSON。
**解决**：
1. `@SystemMessage` 里明确要求"返回 JSON 格式"
2. 用更强的模型
3. 加 `OpenAiChatModel.builder().responseFormat("json_object")`（OpenAI/DeepSeek 支持）

### 8.2 `@V` 没生效

**原因**：模板里 `{{xxx}}` 的变量名和 `@V("xxx")` 不一致。
**解决**：严格对齐变量名（区分大小写）。

### 8.3 多用户对话混乱

**原因**：没加 `@MemoryId`，所有用户共享 memory。
**解决**：方法第一个参数加 `@MemoryId String userId`，并用 `chatMemoryProvider` 而不是 `chatMemory`。

### 8.4 Tool 没被调用

**原因**：可能忘了 `.tools(...)`，或 Tool 描述太模糊。
**解决**：开 `logRequests` 看请求体里有没有 `tools` 数组。

---

## 9. AiServices 的设计哲学

### 9.1 接口优先

```java
// 声明意图
interface SentimentAnalyzer {
    Sentiment analyze(String text);
}

// 实现 = 配置
var analyzer = AiServices.builder(SentimentAnalyzer.class)
        .chatLanguageModel(model)
        .build();
```

**好处**：
- 业务代码不依赖 LangChain4j API（依赖接口）
- 易测试（mock 接口）
- 易复用（多个 Service 装配不同模型）

### 9.2 与 Spring 一起用

把 AiServices 实例声明为 Bean，业务层 `@Autowired` 进来即可。

```java
@Configuration
class AiConfig {
    @Bean
    SentimentAnalyzer sentimentAnalyzer(ChatLanguageModel model) {
        return AiServices.builder(SentimentAnalyzer.class)
                .chatLanguageModel(model)
                .build();
    }
}

@Service
class CommentService {
    private final SentimentAnalyzer analyzer;
    // ...
}
```

---

## 10. 理解检查

1. `AiServices` 用了 Java 的什么机制生成接口实现？
2. `@SystemMessage` 和 ChatMemory 的关系是什么？
3. 结构化输出失败通常怎么排查？
4. 多用户场景下，`chatMemory` 和 `chatMemoryProvider` 该用哪个？
5. `@MemoryId` 和 `@UserMessage` 在同一个方法参数上时怎么用？

---

## 11. 练习任务

1. 重构前面的命令行聊天机器人：用 `AiServices` 替换 `ConversationalChain`
2. 加 `@SystemMessage`，定义 AI 是"产品经理风格的助手"
3. 实现一个 `SentimentAnalyzer`，让 LLM 返回结构化的情感分析结果
4. 实现一个 `Translator` 接口，用 `@UserMessage` 模板支持任意语言翻译
5. （进阶）用 `@MemoryId` 改造代码，支持多用户对话

完成后，阶段 1（LangChain4j 基础）就结束了。可以写一篇学习笔记，进入阶段 2（Spring AI）。

---

## 12. 阶段 1 复盘要点

完成本节后，你应该能回答：

- [ ] LangChain4j 的 5 个核心抽象是什么？
- [ ] ChatMemory 如何实现多轮对话？
- [ ] Tool 的底层协议（Function Calling JSON）长什么样？
- [ ] AiServices 比"手动拼装"好在哪里？
- [ ] 在生产场景，你会怎么把 AiServices 和 Spring Boot 结合？

写一篇 300-500 字的学习笔记存到 `desc/notes/W1-LangChain4j基础.md`。
