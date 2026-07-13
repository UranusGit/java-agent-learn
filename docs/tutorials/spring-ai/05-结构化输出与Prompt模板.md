# Spring AI 05 - 结构化输出与 Prompt 模板

> 让 LLM 返回 Java 对象，把 prompt 当成模板管理。
> 前置：已完成 [01-04]。

---

## 1. 结构化输出

### 1.1 痛点

LLM 默认返回自然语言，业务代码要的是结构化数据：
```java
// 你想要的
Sentiment result = analyze(text);

// 默认行为
String json = analyze(text);   // 还要自己解析 JSON
```

### 1.2 Spring AI 的解决方案：`entity()`

```java
public record Sentiment(String emotion, double score, String reason) {}

@GetMapping("/analyze")
public Sentiment analyze(@RequestParam String text) {
    return chatClient.prompt()
            .system("分析文本情感，返回 JSON：{emotion, score, reason}")
            .user(text)
            .call()
            .entity(Sentiment.class);
}
```

**底层发生了什么**：
1. Spring AI 在 prompt 里追加"必须返回符合 schema 的 JSON"
2. LLM 返回 JSON 字符串
3. Spring AI 用 Jackson 反序列化成 `Sentiment`

### 1.3 LangChain4j 对比

```java
// LangChain4j
public interface Analyzer {
    Sentiment analyze(String text);   // 返回类型自动触发结构化输出
}

// Spring AI
Sentiment s = chatClient.prompt().user(text).call().entity(Sentiment.class);
```

LangChain4j 是接口驱动，Spring AI 是 builder 链驱动。

---

## 2. 输出转换器（OutputConverter）

### 2.1 三种内置转换器

| 转换器 | 用途 |
|--------|------|
| `BeanOutputConverter` | 转 POJO/Record |
| `ListOutputConverter` | 转 List<String> |
| `MapOutputConverter` | 转 Map |

### 2.2 手动使用

```java
BeanOutputConverter<Sentiment> converter = new BeanOutputConverter<>(Sentiment.class);

String formatInstructions = converter.getFormat();  // 自动生成的 schema 描述
String response = chatClient.prompt()
        .system("分析情感。" + formatInstructions)
        .user(text)
        .call()
        .content();

Sentiment result = converter.convert(response);
```

### 2.3 自动模式（推荐）

直接用 `.entity(Class)` 就行，Spring AI 自动处理。

---

## 3. PromptTemplate：模板管理

### 3.1 基本使用

```java
PromptTemplate template = new PromptTemplate("""
    你是 {role}。
    请回答：{question}
    """);

Prompt prompt = template.create(Map.of(
    "role", "客服",
    "question", "退货政策"
));

String answer = chatClient.prompt(prompt).call().content();
```

### 3.2 与 LangChain4j `@UserMessage` 对比

```java
// LangChain4j：注解 + @V
@UserMessage("将 {{text}} 翻译成 {{language}}")
String translate(@V("text") String text, @V("language") String lang);

// Spring AI：PromptTemplate
PromptTemplate t = new PromptTemplate("将 {text} 翻译成 {language}");
Prompt p = t.create(Map.of("text", text, "language", lang));
```

**差异**：
- LangChain4j 用 `{{}}`（双大括号，Mustache 风格）
- Spring AI 用 `{}`（单大括号，String.format 风格）
- Spring AI 模板可以**单独存到文件**，便于版本管理

### 3.3 模板文件（生产推荐）

`src/main/resources/prompts/translate.st`：
```
你是专业翻译，目标语言：$language

请翻译以下文本，注意保持原文风格：
$text
```

调用：
```java
PromptTemplate template = new PromptTemplate(
    new ClassPathResource("prompts/translate.st")
);
Prompt prompt = template.create(Map.of(
    "language", "English",
    "text", "你好世界"
));
```

**注意**：用 `.st` 后缀（Spring 模板约定），避免 Spring Boot 把它当静态资源处理。

---

## 4. Stuffing：把多个变量塞进模板

### 4.1 场景

RAG 场景下，要把检索到的多个文档拼成一个字符串塞进 prompt：

```
"基于以下文档回答：{documents}"
```

### 4.2 Spring AI 的 Stuffing

```java
List<Document> docs = vectorStore.similaritySearch(...);

String docText = docs.stream()
    .map(d -> "## 文档" + d.getMetadata().get("source") + "\n" + d.getText())
    .collect(Collectors.joining("\n\n"));

PromptTemplate template = new PromptTemplate("""
    基于以下文档回答用户问题：
    {documents}
    
    问题：{question}
    """);

Prompt prompt = template.create(Map.of(
    "documents", docText,
    "question", userQuestion
));
```

### 4.3 `RetrievalAugmentationAdvisor` 内部就是这么做的

1.0.0 的 RAG Advisor `RetrievalAugmentationAdvisor` 默认就是把检索到的文档拼成 `{question_answer_context}` 占位符的内容。

---

## 5. SystemPromptTemplate

专门管理系统 prompt：

```java
SystemPromptTemplate sysTemplate = new SystemPromptTemplate(
    new ClassPathResource("prompts/system-customer-service.st")
);

// 注意：1.0.0 中 createMessage(Map) 返回 Message（不是 SystemMessage）
// SystemPromptTemplate 内部已经把模板渲染成 SystemMessage，类型转换是安全的
Message systemMessage = sysTemplate.createMessage(Map.of(
    "company", "Acme",
    "tone", "专业"
));

Prompt prompt = new Prompt(List.of(
    systemMessage,
    new UserMessage(userInput)
));
```

---

## 6. 多模态 prompt（图片/音频）

### 6.1 UserMessage 支持多模态

```java
UserMessage msg = UserMessage.builder()
        .text("这张图里有什么？")
        .media(MediaBuilder.image(new ClassPathResource("test.png")))
        .build();

String answer = chatClient.prompt()
        .messages(msg)
        .call()
        .content();
```

注意：需要模型支持多模态（GPT-4o / Claude / Qwen-VL 等）。

---

## 7. 实战：通用情感分析 Service

### 7.1 模板文件

`src/main/resources/prompts/sentiment.st`：
```
你是情感分析专家。分析以下文本的情感。

严格返回以下 JSON 格式：
{{
  "emotion": "positive|negative|neutral",
  "score": 0.0-1.0,
  "reason": "简短说明"
}}

文本：
{text}
```

### 7.2 Service

```java
@Service
public class SentimentService {

    private final ChatClient chatClient;

    public Sentiment analyze(String text) {
        PromptTemplate template = new PromptTemplate(
            new ClassPathResource("prompts/sentiment.st")
        );
        Prompt prompt = template.create(Map.of("text", text));

        return chatClient.prompt(prompt)
                .call()
                .entity(Sentiment.class);
    }
}
```

### 7.3 record

```java
public record Sentiment(String emotion, double score, String reason) {}
```

---

## 8. Prompt 工程最佳实践

### 8.1 模板管理

| 做法 | 优劣 |
|------|------|
| 写在代码里 | 简单但难维护 |
| **写在 `.st` 文件** | 推荐，版本控制 + 团队协作 |
| 数据库存 | 动态但难审计 |

### 8.2 Prompt 版本化

```
prompts/
├── v1/
│   ├── translate.st
│   └── sentiment.st
└── v2/
    └── sentiment.st   # 改进版
```

通过 `@Profile` 或配置切换版本，AB 测试时方便。

### 8.3 几条铁律

- 占位符要有**语义命名**（`{user_input}` 比 `{x}` 好）
- 模板里**不要写业务逻辑**（业务逻辑在代码里）
- 用 `{}` 或 `{{}}` 要统一（Spring AI 用 `{}`）

---

## 9. 常见错误

### 9.1 JSON 解析失败

**症状**：`JsonProcessingException`

**排查**：
1. 打印 LLM 原始返回（开 log）
2. 看返回是不是合法 JSON
3. record 字段名是否和 JSON 键匹配

**解决**：
- 强化 system prompt："严格返回 JSON，不要有其他文字"
- 加 `responseFormat("json_object")`（OpenAI/DeepSeek）

### 9.2 占位符没替换

**症状**：prompt 里直接出现 `{xxx}` 字样。
**原因**：用了 `String.format` 而不是 `template.create(...)`。
**解决**：必须用 `PromptTemplate.create()`。

### 9.3 找不到模板文件

**症状**：`ClassPathResource` 找不到。
**解决**：
- 文件放 `src/main/resources/prompts/`
- 用 `.st` 后缀
- 检查 `mvn clean package` 后 jar 里有这个文件

### 9.4 多模态 prompt 报错

**症状**：`UnsupportedMediaException`
**原因**：模型不支持图片。
**解决**：换支持多模态的模型。

---

## 10. Spring AI vs LangChain4j 对比

| 维度 | LangChain4j | Spring AI |
|------|-------------|-----------|
| 结构化输出 | 接口返回类型自动触发 | `.entity(Class)` |
| Prompt 模板 | `@UserMessage` + `@V` | `PromptTemplate` + 文件 |
| 模板文件支持 | 弱 | **强（推荐生产）** |
| 多模态 | 通过 `Image` 等类 | 通过 `Media` 统一 |

---

## 11. 理解检查

1. `.entity(Sentiment.class)` 内部做了什么？
2. `PromptTemplate` 和直接用 `String.format` 有什么区别？
3. 为什么推荐把 prompt 写在 `.st` 文件里？
4. `BeanOutputConverter.getFormat()` 返回什么？给谁看的？
5. 多模态 prompt 的 `Media` 对象支持哪些类型？

---

## 12. 练习任务

1. 实现 `SentimentService`，把 prompt 放到 `.st` 文件
2. 测试 `entity(ListOfStrings.class)` 返回 List
3. 用 `PromptTemplate` 实现一个翻译 Service（多语言）
4. 实现多模态：上传图片，让 LLM 描述内容
5. 准备两个版本的 prompt，用配置切换版本
6. （进阶）实现 Prompt 版本管理（v1/v2 目录 + 配置切换）

完成后进入 [06-流式与多模型](./06-流式与多模型.md)。
