# 01 · Java 核心速成

> 阶段：0 地基 · 难度：⭐ · 预计：1 周（每天 2 小时）
> 前置：基本的编程概念（变量、条件、循环、函数）
> 产出：能读写 Java 代码，理解类/接口/泛型/Lambda/Stream，能跟着后面的 AI 教程写代码

---

## 你将学会

- Java 的类、接口、继承体系（和 Python/JS 有什么不同）
- 泛型：为什么 Java 到处都是 `<T>`
- Lambda + Stream：Java 的函数式编程
- 异常处理：checked vs unchecked
- Maven 项目结构：`pom.xml` 是什么

> ⚠️ 这篇不是让你成为 Java 专家，而是让你**能看懂后续教程里的 Java 代码**。够用就行。

---

## 为什么需要这个

后面所有的 AI 代码都是 Java 写的。如果你看不懂 Java 语法，每篇教程都会卡在"这段代码什么意思"。

**好消息**：做 AI 应用不需要 Java 高级特性。你只需要掌握核心语法的 20%，就能跟着教程走。

---

## 知识讲解

### 1. 类与对象

Java 是强类型面向对象语言。每个文件都是一个类：

```java
// 定义一个类（相当于 Python 的 class）
public class Person {
    // 字段（属性）—— 类型必须明确声明
    private String name;
    private int age;

    // 构造方法（相当于 Python 的 __init__）
    public Person(String name, int age) {
        this.name = name;   // this = Python 的 self
        this.age = age;
    }

    // 方法
    public String greet() {
        return "你好，我是 " + name + "，今年 " + age + " 岁";
    }
}

// 使用
Person p = new Person("小明", 25);
System.out.println(p.greet());  // 你好，我是 小明，今年 25 岁
```

**和 Python 的关键区别**：
- 类型必须显式声明（`String name` 而不是 `name`）
- 每个语句以 `;` 结尾
- `this` 代替 `self`
- `new` 关键字创建对象

### 2. 接口与实现

接口定义"能做什么"，类实现"怎么做"。这在 Spring AI 中无处不在（`ChatModel` 是接口，`OpenAiChatModel` 是实现）：

```java
// 接口：定义能力（只声明方法签名，不实现）
public interface LLM {
    String chat(String message);
}

// 实现类 A：DeepSeek
public class DeepSeekLLM implements LLM {
    @Override
    public String chat(String message) {
        return "DeepSeek 回复：" + message;
    }
}

// 实现类 B：OpenAI
public class OpenAILLM implements LLM {
    @Override
    public String chat(String message) {
        return "OpenAI 回复：" + message;
    }
}

// 使用——面向接口编程，不关心具体实现
LLM model = new DeepSeekLLM();
String reply = model.chat("你好");
```

> 💡 **这就是 Spring AI 的核心设计**：`ChatModel` 是接口，DeepSeek/OpenAI/Ollama 都是实现。换模型 = 换实现类，业务代码不动。

### 3. 泛型

Java 到处都是 `<T>`，它是为了类型安全：

```java
// 没有泛型：什么都能塞，取出来要强转（容易出错）
List list = new ArrayList();
list.add("hello");
list.add(123);              // 编译通过，但运行时可能出问题
String s = (String) list.get(1);  // ClassCastException！

// 有泛型：编译时就限制类型
List<String> safeList = new ArrayList<>();
safeList.add("hello");
// safeList.add(123);       // 编译报错！类型安全
String s = safeList.get(0); // 不用强转
```

> 在 Spring AI 中你会看到 `ChatResponse`、`List<Message>`、`Flux<ChatResponse>` 等——泛型只是告诉你"这个容器里装的是什么类型的东西"。

### 4. Record（Java 16+，你一定会遇到）

`record` 是 Java 的轻量级数据类，一行代替几十行样板代码：

```java
// 以前：写一个数据类要几十行（getter/setter/equals/hashCode/toString）
// 现在：一行搞定
public record ChatMessage(String role, String content) {}

// 使用
ChatMessage msg = new ChatMessage("user", "你好");
System.out.println(msg.role());     // user
System.out.println(msg.content());  // 你好
```

> 在 Spring AI 中，请求/响应对象大量使用 record。你就把它当成"不可变的数据结构"。

### 5. Lambda 与函数式接口

Lambda 是 Java 的"匿名函数"，用于传递行为：

```java
// 函数式接口：只有一个抽象方法的接口
@FunctionalInterface
public interface Tool {
    String execute(String input);
}

// Lambda：直接传递行为（类似 Python 的 lambda）
Tool weatherTool = (input) -> "今天晴天，25°C";
Tool timeTool = (input) -> "现在是 " + java.time.LocalTime.now();

// 调用
System.out.println(weatherTool.execute("北京天气"));  // 今天晴天，25°C
```

> 在 Spring AI 中，`@Tool` 方法和 Advisor 回调大量使用 Lambda。

### 6. Stream API

Stream 是 Java 处理集合的函数式方式（类似 Python 的列表推导式）：

```java
List<String> messages = List.of("你好", "hello", "こんにちは", "안녕하세요");

// 过滤 + 转换 + 收集
List<String> result = messages.stream()
    .filter(msg -> msg.length() <= 3)         // 过滤：保留长度<=3的
    .map(String::toUpperCase)                 // 转换：转大写
    .collect(Collectors.toList());            // 收集：变成 List

System.out.println(result);  // [你好, HELLO]
```

> 在 RAG 教程中你会大量使用 Stream 来处理文档分块、过滤、转换。

### 7. 异常处理

```java
try {
    String result = callLLM("你好");  // 可能抛异常
} catch (TimeoutException e) {
    // checked 异常：必须 catch（网络超时等）
    System.out.println("LLM 超时了：" + e.getMessage());
} catch (RuntimeException e) {
    // unchecked 异常：可选 catch（空指针等）
    System.out.println("运行时错误：" + e.getMessage());
} finally {
    // 无论是否异常都执行
    System.out.println("清理资源");
}
```

> 在 Agent 教程中，工具错误处理的核心就是：**catch 异常 → 返回错误信息给 LLM → 让 LLM 自我修复**。

---

## 动手实践

### Step 1：安装 JDK 21

```bash
# macOS (用 Homebrew)
brew install openjdk@21

# 验证
java -version
# 应该输出类似：openjdk version "21.0.x"
```

### Step 2：写第一个 Java 程序

创建文件 `HelloAI.java`：

```java
public class HelloAI {
    public static void main(String[] args) {
        // 用 record 定义数据
        var msg = new ChatMessage("user", "什么是 AI Agent？");

        // 用 Stream 处理
        var words = msg.content()
            .lines()
            .flatMap(line -> Arrays.stream(line.split("")))
            .filter(ch -> ch.matches("[\\u4e00-\\u9fa5]"))  // 只保留中文
            .collect(Collectors.joining());

        System.out.println("原始消息：" + msg.content());
        System.out.println("中文字符：" + words);
    }

    // 内部 record
    record ChatMessage(String role, String content) {}
}
```

编译运行：

```bash
javac HelloAI.java
java HelloAI
```

### Step 3：完成小练习

创建 `Practice.java`，完成以下练习：

```java
import java.util.*;
import java.util.stream.*;

public class Practice {
    public static void main(String[] args) {
        // 练习 1：定义一个 record 表示"消息"
        record Message(String role, String content) {}

        // 练习 2：创建一个消息列表
        var messages = List.of(
            new Message("user", "你好"),
            new Message("assistant", "你好！有什么可以帮你的？"),
            new Message("user", "今天天气怎么样？")
        );

        // 练习 3：用 Stream 过滤出 user 角色的消息
        var userMessages = messages.stream()
            .filter(m -> m.role().equals("user"))
            .collect(Collectors.toList());

        System.out.println("用户消息：" + userMessages);

        // 练习 4：用 Stream 统计每个角色的消息数量
        var roleCount = messages.stream()
            .collect(Collectors.groupingBy(Message::role, Collectors.counting()));

        System.out.println("角色统计：" + roleCount);

        // 练习 5：定义一个函数式接口并使用 Lambda
        //（模拟"把消息发给 LLM"）
        java.util.function.Function<String, String> mockLLM =
            prompt -> "（模拟回复）" + prompt;

        System.out.println(mockLLM.apply("你好"));
    }
}
```

---

## 常见坑

- ❌ **忘记 `;`** → Java 每个语句必须以分号结尾
- ❌ **忘记类型声明** → `String name = "hello"` 不能写成 `name = "hello"`（var 除外）
- ❌ **混淆 `==` 和 `.equals()`** → 字符串比较用 `.equals()`，不用 `==`
- ❌ **数组 vs List** → `String[]` 是数组（定长），`List<String>` 是列表（可变长）
- ❌ **忘记 `import`** → 用了某个类但没有 import，编译报错。IDE 会提示自动导入
- ❌ **`null` 检查** → Java 引用类型默认是 `null`，不检查就调用方法会 `NullPointerException`

---

## Java 语法速查表（写代码时翻这个）

```java
// 变量
String s = "hello";          // 字符串
int i = 42;                   // 整数
double d = 3.14;              // 小数
boolean b = true;             // 布尔
var x = "自动推断类型";         // var 让编译器推断

// 集合
List<String> list = new ArrayList<>();         // 可变列表
List<String> immutable = List.of("a", "b");    // 不可变列表
Map<String, Integer> map = new HashMap<>();     // 键值对
Set<String> set = new HashSet<>();              // 去重集合

// 条件
if (x.equals("hello")) { ... }
switch (s) {
    case "a" -> System.out.println("A");
    case "b" -> System.out.println("B");
    default -> System.out.println("其他");
}

// 循环
for (String item : list) { System.out.println(item); }
list.forEach(System.out::println);
list.stream().filter(s -> s.length() > 3).toList();

// 字符串
String result = "a" + "b";                    // 拼接
String formatted = "%s 有 %d 个".formatted("小明", 3);  // 格式化
boolean contains = "hello".contains("ell");   // 包含
String[] parts = "a,b,c".split(",");          // 分割

// 空安全
String maybeNull = getName();
if (maybeNull != null && maybeNull.length() > 0) { ... }
// 或用 Optional（更安全）
Optional.ofNullable(maybeNull).filter(s -> !s.isEmpty()).orElse("默认值");
```

---

## 验收检查

- [ ] 能定义一个 record 并使用
- [ ] 能用 Stream 过滤和转换列表
- [ ] 能定义一个函数式接口并用 Lambda 实现
- [ ] 能用 try-catch 处理异常
- [ ] 能编译运行一个 `.java` 文件

---

## 随堂练习：消息格式化器（30 分钟）

用刚学的 record + Stream 写一个 CLI 程序：输入一组聊天消息，格式化输出并统计。

**目标输出**：
```
👤 用户：你好
🤖 助手：你好！有什么可以帮你的？
👤 用户：今天天气怎么样

--- 统计 ---
总消息数：3
用户消息：2 条
助手手消息：1 条
总字符数：22
```

**提示**：
```java
record ChatMessage(String role, String content) {}

List<ChatMessage> messages = List.of(
    new ChatMessage("user", "你好"),
    new ChatMessage("assistant", "你好！有什么可以帮你的？"),
    new ChatMessage("user", "今天天气怎么样")
);

// 用 stream().map() 格式化输出，根据 role 选 emoji
// 用 Collectors.groupingBy(ChatMessage::role, Collectors.counting()) 统计
// 用 mapToInt(m -> m.content().length()).sum() 统计字符数
```

**验收**：输出格式正确、统计数据正确、没有硬编码（全用 Stream 算）。

**扩展**：加 `filter` 只显示 user 消息；加 `sorted` 按长度排序。

---

## 下一步

→ 下一篇：[02 Spring Boot 入门](02-SpringBoot入门.md) —— 学会用 Spring Boot 搭建 Web 应用
→ 概念卡壳？你暂时不需要查理论字典，这些是纯 Java 基础
